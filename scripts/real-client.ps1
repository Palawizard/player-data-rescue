# Real game client checks, dot-sourced by prod-smoke.ps1 (-RealClient) and by
# real-client-singleplayer.ps1. The client is the real Minecraft client, started by the
# portablemc launcher with an offline account (ADR-PDR-011). It opens a game window, so these
# checks run on a desktop, never in CI. Written for Windows PowerShell 5.1 and PowerShell 7
# (check-powershell-compat.ps1), run so far with PowerShell 7 on Linux. Uses from common.ps1:
# $ProjectRoot, $OnWindows and Get-BandProperties; the dedicated server scenarios also use
# $javaExe, $ServerPort, $BandId, $Loader and $band from prod-smoke.ps1.

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

function Get-PlayerHeight {
    param([string]$Player)
    $answer = Invoke-RconCommand -Command "data get entity $Player Pos"
    if ($answer -notmatch '\[(-?[0-9.E-]+)d, (-?[0-9.E-]+)d, (-?[0-9.E-]+)d\]') { throw "no position for $Player in '$($answer.Trim())'" }
    return [double]::Parse($Matches[2], [Globalization.CultureInfo]::InvariantCulture)
}

function Test-RealClientFalls {
    # The client moves its own player: lifted 3 blocks, it falls back, and the server sees the
    # player's height drop. A client whose player is not in the world it shows sends no move and
    # stays in the air: that is how the vanilla client looked after a second login packet, frozen
    # and shaking on screen while the server saw nothing wrong.
    param([string]$Player)
    $lifted = (Get-PlayerHeight -Player $Player) + 3
    Invoke-RconCommand -Command "execute as $Player at @s run tp @s ~ ~3 ~" | Out-Null
    $deadline = (Get-Date).AddSeconds(5)
    $height = $lifted
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $height = Get-PlayerHeight -Player $Player
        if ($height -lt $lifted - 1) { return @{ Detail = "fell from $lifted to $height"; Problem = $null } }
    }
    return @{ Detail = "stayed at $height"; Problem = "$Player stayed in the air 5 s after a lift of 3 blocks (height $lifted, then $height): the client does not move its player." }
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
    # S15 with a real client: the server places the player twice on one connection, the failed
    # placement then the minimal profile, and the client only gets the second. The client must stay.
    @{ Id = 'S15'; Player = 'S15Client'; Prepare = 'attach'; Join = 'attach,placement-rejects-attachment'; Setup = @();
        Proof = 'saved attachment pdrtest:broken of S15Client as int'; TwoPlacements = $true }
)
$realClientStaySeconds = 30

function Invoke-RealClientScenario {
    param([hashtable]$Scenario, [hashtable]$Context)
    $id = $Scenario.Id
    $player = $Scenario.Player
    $problems = @()
    $fallDetail = 'not lifted'
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
            $fall = Test-RealClientFalls -Player $player
            $fallDetail = $fall.Detail
            if ($fall.Problem) { $problems += "$id (real client): $($fall.Problem)" }
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
    if ($Scenario.TwoPlacements -and $logins -lt 2) { $problems += "$id (real client): $logins placement(s) of $player, expected the failed one and the minimal profile." }
    $chat = @(Get-RealClientChat -Client $client)
    if (@($chat | Where-Object { $_.Contains($playerMessage) }).Count -eq 0) {
        $problems += "$id (real client): $player did not show the player message. Chat: [$($chat -join ' | ')]"
    }
    # The message was checked in the client's own chat above.
    $problems += Test-ScenarioIncidents -Scenario @{ Id = $id; Player = $player } -Before $before -BotOutputs $playerMessage -JoinLog $joinLog
    $line = "$id real client ${player}: $logins placement(s), stayed $realClientStaySeconds s, $fallDetail, chat [$($chat -join ' | ')], closed cleanly: $closed"
    return @{ Line = $line; Problems = $problems }
}

function Invoke-RealClientDisconnect {
    # S11 with a real client, two joins. At the first, every placement fails, so the client never
    # gets a login packet, only the game's "Invalid player data" disconnect (ARCHITECTURE.md,
    # Cleanup after a failed placement). At the second no rescue is tried, and the disconnect lets
    # out the packets the connection held. Each time the client must show the reason and log no
    # packet error or crash. The 1.20.1 client shows the reason without logging it
    # (ClientPacketListener#onDisconnect), so there the server's line is the proof.
    param([hashtable]$Context)
    $player = 'S11Client'
    $problems = @()
    $lines = @()
    Invoke-PrepareJoin -Scenario @{ Id = 'S11'; Player = $player; Setup = @() } -Modes '' -RunSetup $false | Out-Null
    Set-PlayerModes -Player $player -Modes 'throw-on-placement'
    $logsReason = $band.compileTarget -ne '1.20.1'
    foreach ($join in 1, 2) {
        $joinOffset = Get-LatestLogLength
        $client = Start-RealClient -Launcher $Context.Launcher -Root $Context.Root -Version $Context.Version -JavaExe $javaExe `
            -Name $player -GameDir (Join-Path $Context.RunRoot $player) -Port $ServerPort
        try {
            if (-not (Wait-ForLog -Offset $joinOffset -Pattern "$player lost connection: Invalid player data" -Seconds 300)) {
                $problems += "S11 (real client) join ${join}: $player was not disconnected with 'Invalid player data'."
            } elseif ($logsReason -and -not (Wait-RealClientLog -Client $client -Pattern 'Invalid player data' -Seconds 20)) {
                $problems += "S11 (real client) join ${join}: $player did not show the reason 'Invalid player data'."
            } elseif (-not $logsReason) {
                Start-Sleep -Seconds 5
            }
            $problems += @((Test-RealClientHealthy -Client $client) | Where-Object { $_ -notmatch 'Client disconnected with reason|Disconnecting from server|Lost connection' })
        } finally {
            $closed = Close-RealClient -Client $client
        }
        $joinLog = Get-LogSince -Offset $joinOffset
        if ($joinLog -notmatch 'pdrtest: deliberate failure: placement of S11Client') { $problems += "S11 (real client) join ${join}: the join did not hit the test mod failure." }
        if ($join -eq 2 -and $joinLog -notmatch 'no rescue is tried') { $problems += 'S11 (real client) join 2: the server log does not say that no rescue is tried.' }
        $reason = 'not logged by this client'
        if ($logsReason) {
            $reason = "$(@((Get-RealClientLog -Client $client) -split "`r?`n" | Where-Object { $_ -match 'Invalid player data' }) | Select-Object -First 1)".Trim()
        }
        $lines += "join ${join}: client log '$reason', closed cleanly: $closed"
    }
    return @{ Line = "S11 real client ${player}: disconnected twice; $($lines -join '; ')"; Problems = $problems }
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
        try {
            $result = Invoke-RealClientDisconnect -Context $Context
            $summary += $result.Line
            $failures += $result.Problems
        } catch {
            $failures += "S11 (real client) failed: $($_.Exception.Message)"
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
