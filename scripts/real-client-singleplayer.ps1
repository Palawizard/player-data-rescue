[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$BandId,
    [Parameter(Mandatory)]
    [ValidateSet('forge', 'fabric', 'neoforge')]
    [string]$Loader,
    [string]$Launcher,
    [string]$ClientRoot,
    # Skips the start of the band's dedicated server that makes the empty world.
    [switch]$KeepWorld,
    [int]$ServerPort = 25566,
    [int]$RconPort = 25576
)

# SPEC.md scenario S8 with the real game client: the singleplayer host's own data holds the test
# attachment, and the integrated server must rescue it as a dedicated server would. Needs the
# band built (release JAR and test mod), a desktop session and the portablemc launcher
# (ADR-PDR-011). Works on Windows PowerShell 5.1 and on PowerShell 7.
#
# 1. The band's dedicated server makes an empty world (prod-smoke.ps1 -SkipScenarios).
# 2. The client opens it once with both mods: the host player is created and its data, with
#    the test attachment, is saved when the window closes.
# 3. Control: a copy of that world, opened without the release JAR while the attachment's
#    reader throws, must fail as in vanilla ("Couldn't place player in world").
# 4. The world is opened with both mods while the reader throws: the host joins, the attachment
#    is set aside, and the incident holds the host's source files.
# 5. The world is opened again with a reader that works: no new incident.

. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'real-client.ps1')

$band = (Get-VersionMatrix).bands | Where-Object { $_.id -eq $BandId } | Select-Object -First 1
if (-not $band) { throw "Unknown band '$BandId'." }
$loaderConfig = $band.loaders.$Loader
if (-not $loaderConfig) { throw "Loader '$Loader' is not defined for band '$BandId'." }

$modProps = Get-ModProperties
$bandDir = Get-BandDirectory $loaderConfig
$modJar = Join-Path $bandDir "build/libs/$($loaderConfig.artifact)-$($modProps['mod_version']).jar"
$testModJar = Join-Path $bandDir "build/testmod/pdrtest-$($band.compileTarget)-$Loader.jar"
foreach ($jar in $modJar, $testModJar) { if (-not (Test-Path -LiteralPath $jar)) { throw "No $jar. Build the band first." } }

$portableMc = Get-PortableMc -Launcher $Launcher
$root = Get-ClientRoot -ClientRoot $ClientRoot
$version = Get-ClientVersion -Band $band -LoaderConfig $loaderConfig -Loader $Loader
$javaExe = Get-JavaExe -Version $band.java
$player = 'S8Player'
$gameDir = Join-Path $ProjectRoot "build/real-client/runs/$BandId-$Loader/S8"
$serverWorld = Join-Path $ProjectRoot "build/prod-smoke/$BandId-$Loader/smokeworld"
$world = Join-Path $gameDir 'saves/S8'
$rescueRoot = Join-Path $world 'playerdatarescue'
# NeoForge skips an attachment it cannot read and logs it (trigger T4, a later milestone).
$expectRescue = $Loader -ne 'neoforge'
$entryId = if ($Loader -eq 'fabric') { 'key.pdrtest:broken' } else { 'attachment.pdrtest:broken' }
$playerMessage = 'Some of your saved data could not be loaded and was set aside. An admin can restore it.'
$summary = @()
$failures = @()

function Set-Mods {
    param([switch]$WithoutMod)
    $mods = Join-Path $gameDir 'mods'
    if (Test-Path -LiteralPath $mods) { Remove-Item -LiteralPath $mods -Recurse -Force }
    New-Item -ItemType Directory -Path $mods -Force | Out-Null
    Copy-Item -LiteralPath $testModJar -Destination $mods
    if (-not $WithoutMod) { Copy-Item -LiteralPath $modJar -Destination $mods }
}

function Set-Modes {
    param([string]$Modes)
    $file = Join-Path $gameDir 'pdrtest.properties'
    if ($Modes) { Set-Content -LiteralPath $file -Value "$player=$Modes" -Encoding ascii }
    elseif (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
}

function Get-Incidents {
    if (-not (Test-Path -LiteralPath $rescueRoot)) { return @() }
    return @(Get-ChildItem -LiteralPath $rescueRoot -Directory | ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Directory })
}

function Invoke-WorldRun {
    # Opens $WorldName, waits until the log shows $Until and the host stayed a few seconds, then
    # closes the window. Returns the client log, its chat and the problems it shows.
    param([string]$Step, [string]$WorldName, [string]$Until, [int]$StaySeconds = 10)
    $client = Start-RealClient -Launcher $portableMc -Root $root -Version $version -JavaExe $javaExe -Name $player `
        -GameDir $gameDir -World $WorldName
    try {
        $reached = Wait-RealClientLog -Client $client -Pattern $Until -Seconds 300
        if ($reached) { Start-Sleep -Seconds $StaySeconds }
        $health = @(Test-RealClientHealthy -Client $client)
    } finally {
        $closed = Close-RealClient -Client $client
    }
    $log = Get-RealClientLog -Client $client
    $chat = @(Get-RealClientChat -Client $client)
    $script:summary += "${Step}: reached '$Until': $reached; chat [$($chat -join ' | ')]; closed cleanly: $closed"
    if (-not $reached) { $script:failures += "${Step}: the client log never showed '$Until'." }
    if (-not $closed) { $script:failures += "${Step}: the game did not close by itself, so the world may not be saved." }
    return @{ Log = $log; Chat = $chat; Health = $health }
}

if (-not $KeepWorld) {
    & (Join-Path $PSScriptRoot 'prod-smoke.ps1') -BandId $BandId -Loader $Loader -SkipBuild -SkipScenarios -ServerPort $ServerPort -RconPort $RconPort
}
if (-not (Test-Path -LiteralPath (Join-Path $serverWorld 'level.dat'))) { throw "No world in $serverWorld." }
if (Test-Path -LiteralPath $gameDir) { Remove-Item -LiteralPath $gameDir -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $gameDir 'saves') -Force | Out-Null
Copy-Item -LiteralPath $serverWorld -Destination $world -Recurse
# A world made by a dedicated server that no player joined: no host data, no player files.
foreach ($stale in 'playerdata', 'players', 'playerdatarescue', 'session.lock') {
    $path = Join-Path $world $stale
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
}

# 2. First opening: the host player is created and saved with the test attachment.
Set-Mods
Set-Modes 'attach'
$first = Invoke-WorldRun -Step 'first opening' -WorldName 'S8' -Until "$player joined the game"
if ($first.Log -notmatch "saved attachment pdrtest:broken of $player as int") {
    $failures += "first opening: the test mod did not save its attachment for $player."
}
$failures += $first.Health
Copy-Item -LiteralPath $world -Destination (Join-Path $gameDir 'saves/S8control') -Recurse

# 3. Control without the release JAR.
Set-Mods -WithoutMod
Set-Modes 'attach,attachment-throws'
if ($expectRescue) {
    Invoke-WorldRun -Step 'control without the mod' -WorldName 'S8control' -Until "Couldn't place player in world" -StaySeconds 3 | Out-Null
} else {
    Invoke-WorldRun -Step 'control without the mod' -WorldName 'S8control' -Until 'Failed to deserialize data attachment' | Out-Null
}
Remove-Item -LiteralPath (Join-Path $gameDir 'saves/S8control') -Recurse -Force

# 4. The rescue of the host.
Set-Mods
$before = @(Get-Incidents)
if ($expectRescue) {
    $rescue = Invoke-WorldRun -Step 'rescue' -WorldName 'S8' -Until ([regex]::Escape($playerMessage))
} else {
    $rescue = Invoke-WorldRun -Step 'rescue (T4 on NeoForge)' -WorldName 'S8' -Until "$player joined the game"
    if ($rescue.Log -notmatch 'Failed to deserialize data attachment') { $failures += 'NeoForge: the log does not show the skipped attachment.' }
}
$failures += $rescue.Health
$incidents = @(Get-Incidents | Where-Object { $before.FullName -notcontains $_.FullName })
if (-not $expectRescue) {
    if ($incidents.Count -ne 0) { $failures += "rescue: $($incidents.Count) incident(s) on NeoForge, none expected (T4)." }
} elseif ($incidents.Count -ne 1) {
    $failures += "rescue: $($incidents.Count) incident(s), expected 1."
} else {
    $folder = $incidents[0].FullName
    $report = Get-Content -LiteralPath (Join-Path $folder 'report.json') -Raw | ConvertFrom-Json
    $text = Get-Content -LiteralPath (Join-Path $folder 'report.txt') -Raw
    $originals = @(Get-ChildItem -LiteralPath (Join-Path $folder 'original') -File -ErrorAction SilentlyContinue | ForEach-Object { $_.Name })
    $entries = @($report.entries | ForEach-Object { $_.id })
    $summary += "incident $($incidents[0].Parent.Name)/$($incidents[0].Name): trigger $($report.trigger), outcome $($report.outcome), set aside [$($entries -join ', ')], original/ [$($originals -join ', ')], suspects [$(@($report.suspects | ForEach-Object { $_.modId }) -join ', ')]"
    if ($report.trigger -ne 'T1' -or $report.outcome -ne 'rescued') { $failures += "rescue: trigger $($report.trigger), outcome $($report.outcome), expected T1 and rescued." }
    if ($entries.Count -ne 1 -or $entries[0] -ne $entryId) { $failures += "rescue: set aside [$($entries -join ', ')], expected only $entryId." }
    elseif (-not (Test-Path -LiteralPath (Join-Path $folder $report.entries[0].payload))) { $failures += 'rescue: no payload file for the attachment.' }
    if (@($report.suspects | ForEach-Object { $_.modId }) -notcontains 'pdrtest') { $failures += 'rescue: pdrtest is not a suspect.' }
    # 1.20.1 and 1.21.1 keep the host data in level.dat; 26.3 keeps it in a player file.
    $hostFile = if ($band.compileTarget -eq '26.3') { '\.dat$' } else { '^level\.dat$' }
    if (@($originals | Where-Object { $_ -match $hostFile }).Count -eq 0) { $failures += "rescue: original/ [$($originals -join ', ')] has no host data file." }
    if (-not $text.Contains('pdrtest:broken')) { $failures += 'rescue: report.txt does not name pdrtest:broken.' }
}
if ($rescue.Log -match "Couldn't place player in world") { $failures += "rescue: the log has 'Couldn't place player in world'." }

# 5. Opening again with a reader that works.
Set-Modes ''
$before = @(Get-Incidents)
$again = Invoke-WorldRun -Step 'opening again' -WorldName 'S8' -Until "$player joined the game"
$failures += $again.Health
$new = @(Get-Incidents | Where-Object { $before.FullName -notcontains $_.FullName })
if ($new.Count -ne 0) { $failures += "opening again: $($new.Count) new incident(s), expected none." }

Write-Host "S8 with the real client on $BandId/$Loader ($version, portablemc $PortableMcVersion):"
foreach ($line in $summary) { Write-Host "  $line" }
if ($failures.Count -gt 0) {
    throw "S8 real client check failed on $BandId/${Loader}:$([Environment]::NewLine)  $($failures -join "$([Environment]::NewLine)  ")"
}
Write-Host "$BandId/$Loader OK: S8 passed with the real client." -ForegroundColor Green
