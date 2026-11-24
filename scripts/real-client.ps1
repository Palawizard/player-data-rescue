# Real game client checks, dot-sourced by prod-smoke.ps1 (-RealClient) and by
# real-client-singleplayer.ps1. The client is the real Minecraft client, started by the
# portablemc launcher with an offline account (ADR-PDR-011). It opens a game window, so these
# checks run on a desktop, never in CI. Works on Windows PowerShell 5.1 and on PowerShell 7.
# Uses from common.ps1: $ProjectRoot, $OnWindows and Get-BandProperties.

$PortableMcVersion = '5.0.6'

function Get-PortableMc {
    # The launcher is installed by hand, outside the repository (docs/TESTING.md, Real client
    # checks): its path comes from -Launcher or the PDR_PORTABLEMC environment variable.
    param([string]$Launcher)
    $path = $Launcher
    if (-not $path) { $path = $env:PDR_PORTABLEMC }
    if (-not $path) { throw "No portablemc launcher: pass -Launcher or set PDR_PORTABLEMC (portablemc $PortableMcVersion, see docs/TESTING.md)." }
    if (-not (Test-Path -LiteralPath $path)) { throw "The portablemc launcher $path does not exist." }
    $version = (& $path '--version' | Select-Object -First 1)
    if ("$version".Trim() -ne "portablemc $PortableMcVersion") {
        throw "portablemc $PortableMcVersion is required (ADR-PDR-011), $path is '$version'."
    }
    return (Resolve-Path -LiteralPath $path).Path
}

function Get-ClientRoot {
    # Versions, libraries and assets shared by every client run (about 1.5 GB for the three
    # vanilla versions), from -ClientRoot or PDR_CLIENT_ROOT, else build/real-client/.minecraft.
    param([string]$ClientRoot)
    $root = $ClientRoot
    if (-not $root) { $root = $env:PDR_CLIENT_ROOT }
    if (-not $root) { $root = Join-Path $ProjectRoot 'build/real-client/.minecraft' }
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    return (Resolve-Path -LiteralPath $root).Path
}

function Get-ClientVersion {
    # The portablemc version id: the band's vanilla client, or with -Loader the band's loader on
    # the client, for a singleplayer world with the mods.
    param([object]$Band, [object]$LoaderConfig, [string]$Loader)
    if (-not $Loader) { return $Band.compileTarget }
    $props = Get-BandProperties $LoaderConfig
    switch ($Loader) {
        'fabric' { return "fabric:$($Band.compileTarget):$($props['fabric_loader_version'])" }
        'forge' { return "forge::$($Band.compileTarget)-$($props['forge_version'])" }
        'neoforge' { return "neoforge::$($props['neo_version'])" }
    }
    throw "No client version for loader '$Loader'."
}

function Write-ClientOptions {
    # Skips the first-launch screens and keeps the client light: no sound, short view distance,
    # and no pause when the window loses focus.
    param([string]$GameDir)
    New-Item -ItemType Directory -Path $GameDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $GameDir 'options.txt') -Encoding ascii -Value @(
        'onboardAccessibility:false'
        'skipMultiplayerWarning:true'
        'joinedFirstServer:true'
        'tutorialStep:none'
        'pauseOnLostFocus:false'
        'soundCategory_master:0.0'
        'renderDistance:2'
        'simulationDistance:5'
        'maxFps:30'
        'narrator:0'
    )
}

function Format-ProcessArgument {
    # Start-Process joins its arguments with spaces and quotes none of them, so a path such as
    # C:\Program Files\... would reach the launcher as two arguments.
    param([string]$Value)
    if ($Value -match '[\s"]') { return '"' + ($Value -replace '"', '\"') + '"' }
    return $Value
}

function Start-RealClient {
    # Starts the client as player $Name, joining 127.0.0.1:$Port or opening the singleplayer
    # world $World of $GameDir. Returns once the launcher has started the game process.
    param([string]$Launcher, [string]$Root, [string]$Version, [string]$JavaExe, [string]$Name, [string]$GameDir,
        [int]$Port, [string]$World)
    Write-ClientOptions -GameDir $GameDir
    foreach ($stale in 'logs', 'crash-reports') {
        $path = Join-Path $GameDir $stale
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    }
    $output = Join-Path $GameDir 'launcher.out.log'
    $launchArgs = @('--main-dir', $Root, '--output', 'machine', 'start', '--mc-dir', $GameDir, '--jvm', $JavaExe,
        '--jvm-arg=-Xmx2G', '-u', $Name)
    if ($World) { $launchArgs += @('--join-world', $World) }
    else { $launchArgs += @('--join-server', '127.0.0.1', '--join-server-port', "$Port") }
    $launchArgs += $Version
    $startArgs = @{
        FilePath               = $Launcher
        ArgumentList           = @($launchArgs | ForEach-Object { Format-ProcessArgument $_ })
        # The test mod reads pdrtest.properties from the current directory.
        WorkingDirectory       = $GameDir
        RedirectStandardOutput = $output
        RedirectStandardError  = (Join-Path $GameDir 'launcher.err.log')
        PassThru               = $true
    }
    $launcherProcess = Start-Process @startArgs
    # The first run of a version downloads it, so the launcher can take minutes before the game starts.
    $deadline = (Get-Date).AddMinutes(10)
    while ((Get-Date) -lt $deadline) {
        $text = Get-Content -LiteralPath $output -Raw -ErrorAction SilentlyContinue
        if ($text -match "(?m)^launched\t(\d+)") {
            return @{ Launcher = $launcherProcess; GamePid = [int]$Matches[1]; GameDir = $GameDir; Name = $Name }
        }
        if ($launcherProcess.HasExited) {
            throw "portablemc stopped before starting the $Version client (exit code $($launcherProcess.ExitCode)), see $output."
        }
        Start-Sleep -Milliseconds 500
    }
    Stop-RealClientProcess -Client @{ Launcher = $launcherProcess; GamePid = 0 }
    throw "portablemc did not start the $Version client within 10 minutes, see $output."
}

function Get-RealClientLog {
    param([hashtable]$Client)
    $latest = Join-Path $Client.GameDir 'logs/latest.log'
    if (-not (Test-Path -LiteralPath $latest)) { return '' }
    return [string](Get-Content -LiteralPath $latest -Raw -ErrorAction SilentlyContinue)
}

function Test-RealClientRunning {
    param([hashtable]$Client)
    return [bool](Get-Process -Id $Client.GamePid -ErrorAction SilentlyContinue)
}

function Wait-RealClientLog {
    param([hashtable]$Client, [string]$Pattern, [int]$Seconds = 60)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-RealClientLog -Client $Client) -match $Pattern) { return $true }
        if (-not (Test-RealClientRunning -Client $Client)) { return ((Get-RealClientLog -Client $Client) -match $Pattern) }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Get-RealClientChat {
    # The chat lines the client logged ("[CHAT] ..."), in order.
    param([hashtable]$Client)
    return @((Get-RealClientLog -Client $Client) -split "`r?`n" | Where-Object { $_ -match '\[CHAT\] ' } |
            ForEach-Object { ($_ -split '\[CHAT\] ', 2)[1] })
}

function Stop-RealClientProcess {
    param([hashtable]$Client)
    foreach ($id in @($Client.GamePid, $Client.Launcher.Id)) {
        if ($id -gt 0) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    }
}

function Close-RealClient {
    # Closes the game window the way a player does, so the game stops cleanly and a singleplayer
    # world is saved. Windows: the window's close message. Hyprland: its window close dispatcher.
    # Elsewhere the game is stopped with a signal, which does not save a singleplayer world.
    # Returns true when the game stopped on its own within a minute; the process is killed otherwise.
    param([hashtable]$Client)
    $game = Get-Process -Id $Client.GamePid -ErrorAction SilentlyContinue
    if (-not $game) { return $true }
    if ($OnWindows) {
        $game.CloseMainWindow() | Out-Null
    } elseif ($env:HYPRLAND_INSTANCE_SIGNATURE) {
        # Hyprland 0.57.2 takes a Lua dispatcher; older releases take the plain one.
        $answer = & hyprctl dispatch "hl.dsp.window.close({ window = `"pid:$($Client.GamePid)`" })" 2>&1
        if ("$answer".Trim() -ne 'ok') { & hyprctl dispatch closewindow "pid:$($Client.GamePid)" | Out-Null }
    } else {
        & kill -TERM $Client.GamePid
    }
    $closed = $game.WaitForExit(60000)
    if (-not $closed) {
        Write-Host "  the $($Client.Name) client did not close within a minute, killing it"
        Stop-RealClientProcess -Client $Client
    }
    if (-not $Client.Launcher.WaitForExit(30000)) { Stop-RealClientProcess -Client $Client }
    return $closed
}

function Test-RealClientHealthy {
    # Problems a client shows when it does not survive a join: a crash report, a disconnect, or a
    # packet it could not handle. Returns the problems found.
    param([hashtable]$Client)
    $problems = @()
    $log = Get-RealClientLog -Client $Client
    $crashes = @(Get-ChildItem -LiteralPath (Join-Path $Client.GameDir 'crash-reports') -File -ErrorAction SilentlyContinue)
    if ($crashes.Count -gt 0) { $problems += "$($Client.Name): crash report $($crashes[0].Name)" }
    foreach ($pattern in 'Client disconnected with reason', 'Lost connection', 'Failed to handle packet', 'Exception caught in connection',
        'Error executing task', 'Disconnecting from server') {
        $line = @($log -split "`r?`n" | Where-Object { $_.Contains($pattern) }) | Select-Object -First 1
        if ($line) { $problems += "$($Client.Name): $($line.Trim())" }
    }
    if (-not (Test-RealClientRunning -Client $Client)) { $problems += "$($Client.Name): the game process stopped" }
    return , $problems
}

# The real client scenarios of a dedicated server, run by prod-smoke.ps1 -RealClient after the
# join client scenarios. Uses from smoke-scenarios.ps1: Invoke-PrepareJoin, Set-PlayerModes,
# Invoke-RconCommand, Get-LatestLogLength, Wait-ForLog, Get-LogSince, Test-ScenarioIncidents,
# Get-PlayerIncidents and $playerMessage.
$realClientScenarios = @(
    # S2 with a real client: the rescued player's message. On NeoForge the game skips the
    # attachment itself (T4), so there is no rescue to see.
    @{ Id = 'S2'; Player = 'S2Client'; Prepare = 'attach'; Join = 'attach,attachment-throws'; Setup = @();
        Proof = 'saved attachment pdrtest:broken of S2Client as int'; SkipOn = @('neoforge') }
    # S15 with a real client: the failed placement sent a first login packet, and the minimal
    # profile is placed on the same connection, which sends a second one. The client must stay.
    @{ Id = 'S15'; Player = 'S15Client'; Prepare = 'attach'; Join = 'attach,placement-rejects-attachment'; Setup = @();
        Proof = 'saved attachment pdrtest:broken of S15Client as int'; SecondLogin = $true }
)
$realClientStaySeconds = 30

function Invoke-RealClientScenario {
    param([hashtable]$Scenario, [hashtable]$Context)
    $id = $Scenario.Id
    $player = $Scenario.Player
    $problems = @()
    $offset = Invoke-PrepareJoin -Scenario $Scenario -Modes $Scenario.Prepare -RunSetup $false
    if (-not ((Get-LogSince -Offset $offset) -match $Scenario.Proof)) { throw "$id (real client) was not set up: no '$($Scenario.Proof)' line." }
    $before = Get-PlayerIncidents -Player $player
    Set-PlayerModes -Player $player -Modes $Scenario.Join
    $joinOffset = Get-LatestLogLength
    $client = Start-RealClient -Launcher $Context.Launcher -Root $Context.Root -Version $Context.Version -JavaExe $javaExe `
        -Name $player -GameDir (Join-Path $Context.RunRoot $player) -Port $ServerPort
    try {
        if (-not (Wait-ForLog -Offset $joinOffset -Pattern "\[playerdatarescue\] rescued $player " -Seconds 300)) {
            $problems += "$id (real client): no rescue of $player in the server log."
        } else {
            Start-Sleep -Seconds $realClientStaySeconds
            $marker = "pdr-real-client-check-$player"
            # A system message, not "say", whose RCON answer stayed pending on 1.21.1 Forge; on 26.3
            # the answer to tellraw can stay pending too, and the client's log is the proof anyway.
            try { Invoke-RconCommand -Command "tellraw $player {`"text`":`"$marker`"}" | Out-Null }
            catch { Write-Host "  no RCON answer to the chat message for $player ($($_.Exception.Message))" }
            if (-not (Wait-RealClientLog -Client $client -Pattern ([regex]::Escape($marker)) -Seconds 20)) {
                $problems += "$id (real client): $player did not get a chat message sent $realClientStaySeconds s after the rescue."
            }
            $online = Invoke-RconCommand -Command 'list'
            if (-not $online.Contains($player)) { $problems += "$id (real client): $player is not in the player list ($($online.Trim()))." }
            $problems += Test-RealClientHealthy -Client $client
        }
    } finally {
        $closed = Close-RealClient -Client $client
    }
    $joinLog = Get-LogSince -Offset $joinOffset
    if ($joinLog -match "$player lost connection: (?!Disconnected)(.*)") { $problems += "$id (real client): server log '$player lost connection: $($Matches[1].Trim())'." }
    $logins = @([regex]::Matches($joinLog, "$player\[[^\]]*\] logged in")).Count
    if ($Scenario.SecondLogin -and $logins -lt 2) { $problems += "$id (real client): $logins placement(s) of $player, expected the failed one and the minimal profile." }
    $chat = @(Get-RealClientChat -Client $client)
    if (@($chat | Where-Object { $_.Contains($playerMessage) }).Count -eq 0) {
        $problems += "$id (real client): $player did not show the player message. Chat: [$($chat -join ' | ')]"
    }
    # The message was checked in the client's own chat above.
    $problems += Test-ScenarioIncidents -Scenario @{ Id = $id; Player = $player } -Before $before -BotOutputs $playerMessage -JoinLog $joinLog
    $line = "$id real client ${player}: $logins placement(s), stayed $realClientStaySeconds s, chat [$($chat -join ' | ')], closed cleanly: $closed"
    return @{ Line = $line; Problems = $problems }
}

function Invoke-RealClientScenarios {
    param([hashtable]$Context)
    $summary = @()
    $failures = @()
    # The operator is a real client too: it must show the notice of each rescue.
    Set-PlayerModes -Player 'OpClient' -Modes ''
    $opOffset = Get-LatestLogLength
    $operator = Start-RealClient -Launcher $Context.Launcher -Root $Context.Root -Version $Context.Version -JavaExe $javaExe `
        -Name 'OpClient' -GameDir (Join-Path $Context.RunRoot 'OpClient') -Port $ServerPort
    try {
        if (-not (Wait-ForLog -Offset $opOffset -Pattern 'OpClient\[[^\]]*\] logged in' -Seconds 300)) { throw 'OpClient (real client) never logged in.' }
        Invoke-RconCommand -Command 'op OpClient' | Out-Null
        $rescued = @()
        foreach ($scenario in $realClientScenarios) {
            if ($scenario.SkipOn -contains $Loader) { $summary += "$($scenario.Id) real client: skipped on $Loader (T4)"; continue }
            try {
                $result = Invoke-RealClientScenario -Scenario $scenario -Context $Context
                $summary += $result.Line
                $failures += $result.Problems
                $rescued += $scenario.Player
            } catch {
                $failures += "$($scenario.Id) (real client) failed: $($_.Exception.Message)"
            }
        }
        $chat = @(Get-RealClientChat -Client $operator)
        foreach ($player in $rescued) {
            $notice = @($chat | Where-Object { $_.Contains("[Player Data Rescue] $player joined after a rescue") }) | Select-Object -First 1
            if ($notice) { $summary += "operator notice shown by OpClient: $notice" }
            else { $failures += "OpClient (real client) did not show the operator notice for $player. Chat: [$($chat -join ' | ')]" }
        }
        $failures += Test-RealClientHealthy -Client $operator
    } finally {
        Close-RealClient -Client $operator | Out-Null
        # The S10 check compares the incident files with this snapshot: take it after these runs too.
        $script:incidentSnapshot = Get-IncidentSnapshot
    }
    Write-Host "Real client scenarios on $BandId/${Loader} (vanilla $($band.compileTarget) client, portablemc $PortableMcVersion):"
    foreach ($line in $summary) { Write-Host "  $line" }
    if ($failures.Count -gt 0) {
        throw "Real client check failed on $BandId/${Loader}:$([Environment]::NewLine)  $($failures -join "$([Environment]::NewLine)  ")"
    }
}
