[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$BandId,
    [Parameter(Mandatory)]
    [ValidateSet('forge', 'fabric', 'neoforge')]
    [string]$Loader,
    [switch]$SkipBuild,
    [switch]$Fresh,
    [int]$ServerPort = 25566,
    [int]$RconPort = 25576,
    [int]$TimeoutMinutes = 10
)

# Production smoke test: install the loader's real dedicated server for one band, drop
# the packaged JAR into mods/, boot the server, check that the mod logged its loaded
# line, then stop the server over RCON and check that it shut down cleanly. This tests
# the file players install, not a dev run (gradlew runServer).

. (Join-Path $PSScriptRoot 'common.ps1')

$band = (Get-VersionMatrix).bands | Where-Object { $_.id -eq $BandId } | Select-Object -First 1
if (-not $band) { throw "Unknown band '$BandId'." }
$loaderConfig = $band.loaders.$Loader
if (-not $loaderConfig) { throw "Loader '$Loader' is not defined for band '$BandId'." }

$modProps = Get-ModProperties
$bandProps = Get-BandProperties $loaderConfig
$modId = $modProps['mod_id']
$expectedLine = "[$modId] loaded on $Loader, band $BandId"
$rconPassword = "$modId-smoke"
$cacheRoot = Join-Path $ProjectRoot 'build/prod-smoke'
$serverDir = Join-Path $cacheRoot "$BandId-$Loader"
$installedMarker = Join-Path $serverDir '.installed'

function Get-File {
    param([string]$Url, [string]$Destination)
    if (Test-Path -LiteralPath $Destination) { return }
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    Write-Host "  download $Url"
    $partial = "$Destination.part"
    Invoke-WebRequest -Uri $Url -OutFile $partial -UseBasicParsing -TimeoutSec 300
    Move-Item -LiteralPath $partial -Destination $Destination -Force
}

function Get-ModJar {
    $libs = Join-Path (Get-BandDirectory $loaderConfig) 'build/libs'
    $name = "$($loaderConfig.artifact)-$($modProps['mod_version']).jar"
    $jar = Join-Path $libs $name
    if (-not (Test-Path -LiteralPath $jar)) { throw "No release JAR $name in $libs. Build the band first." }
    return $jar
}

function Install-FabricServer {
    $installers = Invoke-RestMethod -Uri 'https://meta.fabricmc.net/v2/versions/installer' -TimeoutSec 120
    $installer = ($installers | Where-Object { $_.stable } | Select-Object -First 1).version
    $loaderVersion = $bandProps['fabric_loader_version']
    $url = "https://meta.fabricmc.net/v2/versions/loader/$($band.compileTarget)/$loaderVersion/$installer/server/jar"
    Get-File -Url $url -Destination (Join-Path $serverDir 'fabric-server-launch.jar')
}

function Install-InstallerServer {
    param([string]$Url, [string]$JavaExe)
    $installer = Join-Path $cacheRoot "_installers/$(Split-Path -Leaf $Url)"
    Get-File -Url $Url -Destination $installer
    Write-Host "  installServer ($BandId/$Loader)"
    $log = Join-Path $serverDir 'install.log'
    # The installer writes its own log next to the current directory, so run it from the server folder.
    Push-Location -LiteralPath $serverDir
    try { & $JavaExe '-jar' $installer '--installServer' $serverDir *> $log } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "installServer failed, see $log." }
}

function Get-InstallerLaunchArgs {
    $libraries = Join-Path $serverDir 'libraries'
    $argsName = if ($OnWindows) { 'win_args.txt' } else { 'unix_args.txt' }
    $argsFile = Get-ChildItem -LiteralPath $libraries -Recurse -Filter $argsName -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $launch = @()
    if (Test-Path -LiteralPath (Join-Path $serverDir 'user_jvm_args.txt')) { $launch += '@user_jvm_args.txt' }
    if ($argsFile) {
        $relative = $argsFile.FullName.Substring($serverDir.Length).TrimStart('\', '/')
        $launch += "@$relative"
    } else {
        $shim = Get-ChildItem -LiteralPath $serverDir -Filter '*-shim.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $shim) { throw "The installer produced neither $argsName nor a shim JAR in $serverDir." }
        $launch += @('-jar', $shim.Name)
    }
    return $launch + 'nogui'
}

function Write-ServerConfig {
    Set-Content -LiteralPath (Join-Path $serverDir 'eula.txt') -Value 'eula=true' -Encoding ascii
    Set-Content -LiteralPath (Join-Path $serverDir 'server.properties') -Encoding ascii -Value @(
        'online-mode=false'
        "server-port=$ServerPort"
        'enable-rcon=true'
        "rcon.port=$RconPort"
        "rcon.password=$rconPassword"
        'view-distance=4'
        'simulation-distance=4'
        'spawn-protection=0'
        'level-name=smokeworld'
    )
}

function Reset-ServerState {
    param([string]$ModJar)
    $mods = Join-Path $serverDir 'mods'
    if (Test-Path -LiteralPath $mods) { Remove-Item -LiteralPath $mods -Recurse -Force }
    New-Item -ItemType Directory -Path $mods -Force | Out-Null
    Copy-Item -LiteralPath $ModJar -Destination $mods
    foreach ($stale in 'smokeworld', 'logs', 'crash-reports') {
        $path = Join-Path $serverDir $stale
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    }
}

function Read-ExactBytes {
    param([System.IO.Stream]$Stream, [int]$Count)
    $buffer = New-Object byte[] $Count
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) { throw 'RCON connection closed.' }
        $offset += $read
    }
    return , $buffer
}

function Send-RconPacket {
    param([System.IO.Stream]$Stream, [int]$RequestId, [int]$Type, [string]$Body)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $length = 4 + 4 + $bytes.Length + 2
    $packet = New-Object byte[] (4 + $length)
    [Array]::Copy([BitConverter]::GetBytes([int]$length), 0, $packet, 0, 4)
    [Array]::Copy([BitConverter]::GetBytes($RequestId), 0, $packet, 4, 4)
    [Array]::Copy([BitConverter]::GetBytes($Type), 0, $packet, 8, 4)
    [Array]::Copy($bytes, 0, $packet, 12, $bytes.Length)
    $Stream.Write($packet, 0, $packet.Length)
    $Stream.Flush()
}

function Read-RconPacketId {
    param([System.IO.Stream]$Stream)
    $lengthBytes = Read-ExactBytes -Stream $Stream -Count 4
    $payload = Read-ExactBytes -Stream $Stream -Count ([BitConverter]::ToInt32($lengthBytes, 0))
    return [BitConverter]::ToInt32($payload, 0)
}

function Stop-ServerWithRcon {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $RconPort)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 10000
        Send-RconPacket -Stream $stream -RequestId 1 -Type 3 -Body $rconPassword
        if ((Read-RconPacketId -Stream $stream) -ne 1) { throw 'RCON authentication failed.' }
        Send-RconPacket -Stream $stream -RequestId 2 -Type 2 -Body 'stop'
        Read-RconPacketId -Stream $stream | Out-Null
    } finally {
        $client.Dispose()
    }
}

function Get-ServerLog {
    $latest = Join-Path $serverDir 'logs/latest.log'
    $stdout = Join-Path $serverDir 'smoke.stdout.log'
    $text = ''
    foreach ($path in $latest, $stdout) {
        if (Test-Path -LiteralPath $path) { $text += (Get-Content -LiteralPath $path -Raw -ErrorAction SilentlyContinue) }
    }
    return $text
}

function Invoke-Server {
    param([string]$JavaExe, [string[]]$LaunchArgs)
    $stdout = Join-Path $serverDir 'smoke.stdout.log'
    $stderr = Join-Path $serverDir 'smoke.stderr.log'
    Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue

    Write-Host "Starting the $BandId/$Loader dedicated server with the release JAR"
    $startArgs = @{
        FilePath               = $JavaExe
        ArgumentList           = $LaunchArgs
        WorkingDirectory       = $serverDir
        RedirectStandardOutput = $stdout
        RedirectStandardError  = $stderr
        PassThru               = $true
    }
    if ($OnWindows) { $startArgs.WindowStyle = 'Hidden' }
    $process = Start-Process @startArgs

    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    $started = $false
    $stopSent = $false
    $rconFailures = 0
    try {
        while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
            $log = Get-ServerLog
            $started = $log -match 'Done \([^)]+\)! For help'
            if ($started -and -not $stopSent) {
                # The RCON listener can open a little after the "Done" line.
                try { Stop-ServerWithRcon; $stopSent = $true }
                catch { $rconFailures++; if ($rconFailures -gt 30) { throw "RCON stop failed: $($_.Exception.Message)" } }
            }
            Start-Sleep -Seconds 1
        }
        if ($stopSent -and -not $process.HasExited) { $process.WaitForExit(60000) | Out-Null }
    } finally {
        if (-not $process.HasExited) {
            Write-Host '  server still running, killing it'
            try { $process.Kill($true) } catch { & taskkill.exe /PID $process.Id /T /F 2>$null | Out-Null }
            $process.WaitForExit(30000) | Out-Null
        }
    }

    $log = Get-ServerLog
    $tail = (Get-Content -LiteralPath $stderr -Tail 30 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
    if (-not $started) { throw "$BandId/$Loader never reached 'Done! For help'.$([Environment]::NewLine)$tail" }
    $loadedLine = ($log -split "`r?`n" | Where-Object { $_.Contains($expectedLine) } | Select-Object -First 1)
    if (-not $loadedLine) { throw "$BandId/$Loader started, but the log has no '$expectedLine' line." }
    if (-not $stopSent) { throw "$BandId/$Loader could not be stopped over RCON." }
    if ($log -notmatch 'Stopping (the )?server') { throw "$BandId/$Loader did not log a clean shutdown." }
    Write-Host "  log: $($loadedLine.Trim())"
    Write-Host "$BandId/$Loader OK: the release JAR loaded on a real dedicated server, which then stopped cleanly." -ForegroundColor Green
}

if (-not $SkipBuild) {
    Invoke-Gradle -ProjectDirectory (Get-BandDirectory $loaderConfig) -GradleJava $loaderConfig.gradleJava -Arguments @('build')
}

if ($Fresh -and (Test-Path -LiteralPath $serverDir)) { Remove-Item -LiteralPath $serverDir -Recurse -Force }
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null

$javaExe = Get-JavaExe -Version $band.java
$modJar = Get-ModJar

$installKey = switch ($Loader) {
    'fabric' { "fabric $($band.compileTarget) $($bandProps['fabric_loader_version'])" }
    'forge' { "forge $($band.compileTarget)-$($bandProps['forge_version'])" }
    'neoforge' { "neoforge $($bandProps['neo_version'])" }
}
$installed = if (Test-Path -LiteralPath $installedMarker) { (Get-Content -LiteralPath $installedMarker -Raw).Trim() } else { '' }
if ($installed -and $installed -ne $installKey) {
    Write-Host "  cached server is '$installed', the band wants '$installKey': reinstalling"
    Remove-Item -LiteralPath $serverDir -Recurse -Force
    New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
    $installed = ''
}
if (-not $installed) {
    switch ($Loader) {
        'fabric' { Install-FabricServer }
        'forge' {
            $version = "$($band.compileTarget)-$($bandProps['forge_version'])"
            Install-InstallerServer -JavaExe $javaExe -Url "https://maven.minecraftforge.net/net/minecraftforge/forge/$version/forge-$version-installer.jar"
        }
        'neoforge' {
            $version = $bandProps['neo_version']
            Install-InstallerServer -JavaExe $javaExe -Url "https://maven.neoforged.net/releases/net/neoforged/neoforge/$version/neoforge-$version-installer.jar"
        }
    }
    Set-Content -LiteralPath $installedMarker -Value $installKey -Encoding ascii
}

Write-ServerConfig
Reset-ServerState -ModJar $modJar
if ($Loader -eq 'fabric') {
    $launchArgs = @('-Xmx2G', '-jar', 'fabric-server-launch.jar', 'nogui')
} else {
    $launchArgs = Get-InstallerLaunchArgs
}
Invoke-Server -JavaExe $javaExe -LaunchArgs $launchArgs
