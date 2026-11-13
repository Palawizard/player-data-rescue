# Failure scenarios of the production smoke test, dot-sourced by prod-smoke.ps1 and run while
# the server is up. A headless client (testmod/join-bot) joins as one player per scenario. In a
# first join the game saves real data for that player, given through RCON (an item, an effect,
# a vehicle) and the dev-only test mod then renames ids, cuts the file or breaks an attachment,
# as listed in docs/TESTING.md. A second join records what the game does with that data.
# Works on Windows PowerShell 5.1 and on PowerShell 7.

$scenarioDefinitions = @(
    @{ Id = 'S1'; Player = 'S1Bot'; Prepare = @('rename-item'); Join = @('');
        Setup = @('item replace entity S1Bot container.17 with minecraft:brick 12'); Proof = 'renamed the item in slot 17 of S1Bot' }
    @{ Id = 'S2'; Player = 'S2Bot'; Prepare = @('attach'); Join = @('attach,attachment-throws'); Setup = @(); Proof = '' }
    @{ Id = 'S3'; Player = 'S3Bot'; Prepare = @('rename-effect'); Join = @('');
        Setup = @('effect give S3Bot minecraft:speed 600'); Proof = 'renamed 1 effect\(s\) of S3Bot' }
    @{ Id = 'S4'; Player = 'S4Bot'; Prepare = @('rename-vehicle'); Join = @('');
        Setup = @('execute at S4Bot run summon minecraft:minecart ~ ~ ~',
            'execute at S4Bot run ride S4Bot mount @e[type=minecraft:minecart,sort=nearest,limit=1]');
        Proof = 'renamed the vehicle of S4Bot from minecraft:minecart' }
    @{ Id = 'S5'; Player = 'S5Bot'; Prepare = @('', 'truncate'); Join = @(''); Setup = @(); Proof = 'truncated .*\.dat of S5Bot' }
    @{ Id = 'S6'; Player = 'S6Bot'; Prepare = @('', 'truncate-both'); Join = @(''); Setup = @(); Proof = 'truncated .*\.dat_old of S6Bot' }
    @{ Id = 'S11'; Player = 'S11Bot'; Prepare = @(); Join = @('throw-on-placement', 'throw-on-placement'); Setup = @(); Proof = '' }
    @{ Id = 'S13'; Player = 'S13Bot'; Prepare = @('unreadable-attachment'); Join = @('unreadable-attachment'); Setup = @(); Proof = '' }
)

# What vanilla and the loader do with each broken save, before any rescue code exists. 'invalid'
# is the "Invalid player data" disconnect, 'joined' means the player got in. Keys are scenario
# ids, optionally with a loader ("S2/neoforge") or a band and loader ("S5/1.20-1.20.1/forge").
$vanillaOutcomes = @{
    'S1' = 'joined'
    'S2' = 'invalid'
    'S2/neoforge' = 'joined'
    'S3' = 'joined'
    'S4' = 'joined'
    'S5' = 'joined'
    'S6' = 'joined'
    'S11' = 'invalid'
    'S13' = 'invalid'
    'S13/neoforge' = 'joined'
}

$scenarioModes = @{}
$scenarioServer = $null

function Get-ProtocolVersion {
    switch ($band.compileTarget) {
        '1.20.1' { return 763 }
        '1.21.1' { return 767 }
        '26.3' { return 777 }
    }
    throw "The join bot has no protocol profile for Minecraft $($band.compileTarget)."
}

function Set-PlayerModes {
    param([string]$Player, [string]$Modes)
    $scenarioModes[$Player] = $Modes
    $lines = @($scenarioModes.Keys | Sort-Object | ForEach-Object { "$_=$($scenarioModes[$_])" })
    Set-Content -LiteralPath (Join-Path $serverDir 'pdrtest.properties') -Value $lines -Encoding ascii
}

function Read-RconResponse {
    param([System.IO.Stream]$Stream)
    $lengthBytes = Read-ExactBytes -Stream $Stream -Count 4
    $payload = Read-ExactBytes -Stream $Stream -Count ([BitConverter]::ToInt32($lengthBytes, 0))
    return [System.Text.Encoding]::UTF8.GetString($payload, 8, $payload.Length - 10)
}

function Invoke-RconCommand {
    param([string]$Command)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $RconPort)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 10000
        Send-RconPacket -Stream $stream -RequestId 1 -Type 3 -Body $rconPassword
        if ((Read-RconPacketId -Stream $stream) -ne 1) { throw 'RCON authentication failed.' }
        Send-RconPacket -Stream $stream -RequestId 2 -Type 2 -Body $Command
        return Read-RconResponse -Stream $stream
    } finally {
        $client.Dispose()
    }
}

function Get-LatestLogLength {
    $latest = Join-Path $serverDir 'logs/latest.log'
    if (-not (Test-Path -LiteralPath $latest)) { return 0 }
    return (Get-Content -LiteralPath $latest -Raw).Length
}

function Get-LogSince {
    param([int]$Offset)
    $latest = Join-Path $serverDir 'logs/latest.log'
    $text = Get-Content -LiteralPath $latest -Raw
    if ($text.Length -le $Offset) { return '' }
    return $text.Substring($Offset)
}

function Wait-ForLog {
    param([int]$Offset, [string]$Pattern, [int]$Seconds = 60)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-LogSince -Offset $Offset) -match $Pattern) { return $true }
        if ($scenarioServer.HasExited) { throw 'The server stopped during the scenarios.' }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Start-JoinBot {
    param([string]$Player, [int]$StaySeconds)
    $output = Join-Path $serverDir "bot-$Player.log"
    $botArgs = @('JoinBot.java', '--port', "$ServerPort", '--protocol', "$(Get-ProtocolVersion)", '--name', $Player,
        '--stay-seconds', "$StaySeconds", '--timeout-seconds', '120')
    $startArgs = @{
        FilePath               = $javaExe
        ArgumentList           = $botArgs
        WorkingDirectory       = $serverDir
        RedirectStandardOutput = $output
        RedirectStandardError  = (Join-Path $serverDir "bot-$Player.err.log")
        PassThru               = $true
    }
    if ($OnWindows) { $startArgs.WindowStyle = 'Hidden' }
    return @{ Process = (Start-Process @startArgs); Output = $output }
}

function Wait-JoinBot {
    param([hashtable]$Bot)
    if (-not $Bot.Process.WaitForExit(180000)) {
        try { $Bot.Process.Kill() } catch { Write-Host '  could not kill the join bot' }
        throw 'The join bot did not finish within 3 minutes.'
    }
    return (Get-Content -LiteralPath $Bot.Output -Raw)
}

function Get-JoinOutcome {
    # The server log line counts too: when the server closes the socket while the client is
    # still writing, the client can get a reset before it reads the disconnect packet.
    param([string]$BotOutput, [string]$Player, [string]$ServerLog)
    if ($ServerLog -match "$Player lost connection: Invalid player data") {
        return @{ Outcome = 'invalid'; Detail = 'server log: lost connection: Invalid player data' }
    }
    if ($BotOutput -match 'disconnected by the server, joined (true|false), reason: (.*)') {
        $reason = $Matches[2].Trim()
        if ($reason -match 'invalid_player_data') { return @{ Outcome = 'invalid'; Detail = $reason } }
        return @{ Outcome = 'disconnected'; Detail = $reason }
    }
    if ($BotOutput -match 'left after staying') { return @{ Outcome = 'joined'; Detail = 'stayed, then left' } }
    $last = @($BotOutput -split "`r?`n" | Where-Object { $_ }) | Select-Object -Last 1
    return @{ Outcome = 'error'; Detail = "$last" }
}

function Get-ExpectedOutcome {
    param([string]$ScenarioId)
    foreach ($key in "$ScenarioId/$BandId/$Loader", "$ScenarioId/$Loader", $ScenarioId) {
        if ($vanillaOutcomes.ContainsKey($key)) { return $vanillaOutcomes[$key] }
    }
    throw "No vanilla outcome recorded for $ScenarioId."
}

function Invoke-PrepareJoin {
    param([hashtable]$Scenario, [string]$Modes, [bool]$RunSetup)
    $player = $Scenario.Player
    Set-PlayerModes -Player $player -Modes $Modes
    $offset = Get-LatestLogLength
    $bot = Start-JoinBot -Player $player -StaySeconds 8
    if (-not (Wait-ForLog -Offset $offset -Pattern "$player\[[^\]]*\] logged in")) {
        Wait-JoinBot -Bot $bot | Out-Null
        throw "$($Scenario.Id): $player never logged in while preparing."
    }
    if ($RunSetup) {
        foreach ($command in $Scenario.Setup) {
            $answer = Invoke-RconCommand -Command $command
            Write-Host "  $($Scenario.Id) rcon> $command -> $($answer.Trim())"
        }
    }
    $output = Wait-JoinBot -Bot $bot
    if ($output -notmatch 'left after staying') { throw "$($Scenario.Id): $player could not stay while preparing: $output" }
    if (-not (Wait-ForLog -Offset $offset -Pattern "$player left the game")) { throw "$($Scenario.Id): $player never left." }
    Start-Sleep -Seconds 2
    return $offset
}

function Invoke-SmokeScenarios {
    param([System.Diagnostics.Process]$ServerProcess)
    $script:scenarioServer = $ServerProcess
    Copy-Item -LiteralPath (Join-Path $ProjectRoot 'testmod/join-bot/JoinBot.java') -Destination $serverDir -Force
    $failures = @()
    $summary = @()
    foreach ($scenario in $scenarioDefinitions) {
        $id = $scenario.Id
        try {
            $prepareOffset = Get-LatestLogLength
            for ($i = 0; $i -lt $scenario.Prepare.Count; $i++) {
                Invoke-PrepareJoin -Scenario $scenario -Modes $scenario.Prepare[$i] -RunSetup ($i -eq 0) | Out-Null
            }
            if ($scenario.Proof -and -not ((Get-LogSince -Offset $prepareOffset) -match $scenario.Proof)) {
                throw "$id was not set up: the log has no '$($scenario.Proof)' line from the test mod."
            }
            $expected = Get-ExpectedOutcome -ScenarioId $id
            foreach ($modes in $scenario.Join) {
                Set-PlayerModes -Player $scenario.Player -Modes $modes
                $offset = Get-LatestLogLength
                $botOutput = Wait-JoinBot -Bot (Start-JoinBot -Player $scenario.Player -StaySeconds 4)
                Start-Sleep -Seconds 2
                $joinLog = Get-LogSince -Offset $offset
                $result = Get-JoinOutcome -BotOutput $botOutput -Player $scenario.Player -ServerLog $joinLog
                $placeFailed = $joinLog -match "Couldn't place player in world"
                $line = "$id $($scenario.Player): $($result.Outcome) ($($result.Detail)); log has 'Couldn't place player in world': $placeFailed; vanilla outcome on record: $expected"
                $summary += $line
                if ($result.Outcome -ne $expected) { $failures += $line }
                if ($result.Outcome -eq 'invalid' -and -not $placeFailed) { $failures += "$id disconnected without the 'Couldn't place player in world' log line." }
            }
        } catch {
            $failures += "$id failed: $($_.Exception.Message)"
            if ($scenarioServer.HasExited) { break }
        }
    }
    Write-Host "Failure scenarios on $BandId/$Loader (no rescue code yet):"
    foreach ($line in $summary) { Write-Host "  $line" }
    if ($failures.Count -gt 0) {
        throw "Scenario check failed on $BandId/${Loader}:$([Environment]::NewLine)  $($failures -join "$([Environment]::NewLine)  ")"
    }
}
