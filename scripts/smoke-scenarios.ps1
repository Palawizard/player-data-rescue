# Failure scenarios of the production smoke test, dot-sourced by prod-smoke.ps1 and run while
# the server is up. A headless client (testmod/join-bot) joins as one player per scenario. In a
# first join the game saves real data for that player, given through RCON (an item, an effect,
# a vehicle) and the dev-only test mod then renames ids, cuts the file or breaks an attachment,
# as listed in docs/TESTING.md. Later joins record what happens with that data, and the files
# the rescue wrote under <world>/playerdatarescue are checked against the SPEC scenarios.
# Works on Windows PowerShell 5.1 and on PowerShell 7.
# Uses from prod-smoke.ps1: $band, $BandId, $Loader, $serverDir, $javaExe, $ServerPort,
# $RconPort, $rconPassword, and Read-ExactBytes, Send-RconPacket, Read-RconPacketId.
# S13 and S14 also check the recurring record (smoke-recurring.ps1).
# Proof is a test mod log line expected while preparing; JoinProof is expected at each
# later join, so a scenario cannot pass without the failure it is meant to cause. After the
# scenarios, Invoke-TeleportCheck checks that the join client keeps a teleported player loaded.

$scenarioDefinitions = @(
    @{ Id = 'S1'; Player = 'S1Bot'; Prepare = @('rename-item'); Join = @('');
        Setup = @('item replace entity S1Bot container.17 with minecraft:brick 12'); Proof = 'renamed the item in slot 17 of S1Bot' }
    @{ Id = 'S2'; Player = 'S2Bot'; Prepare = @('attach'); Join = @('attach,attachment-throws'); Setup = @(); Operator = $true;
        Proof = 'saved attachment pdrtest:broken of S2Bot as int'; JoinProof = 'pdrtest: deliberate failure: reading attachment pdrtest:broken of S2Bot' }
    @{ Id = 'S3'; Player = 'S3Bot'; Prepare = @('rename-effect'); Join = @('');
        Setup = @('effect give S3Bot minecraft:speed 600'); Proof = 'renamed 1 effect\(s\) of S3Bot' }
    @{ Id = 'S4'; Player = 'S4Bot'; Prepare = @('rename-vehicle'); Join = @('');
        Setup = @('execute at S4Bot run summon minecraft:minecart ~ ~ ~',
            'execute at S4Bot run ride S4Bot mount @e[type=minecraft:minecart,sort=nearest,limit=1]');
        Proof = 'renamed the vehicle of S4Bot from minecraft:minecart' }
    @{ Id = 'S5'; Player = 'S5Bot'; Prepare = @('', 'truncate'); Join = @(''); Setup = @(); Proof = 'truncated .*\.dat of S5Bot' }
    @{ Id = 'S6'; Player = 'S6Bot'; Prepare = @('', 'truncate-both'); Join = @(''); Setup = @(); Proof = 'truncated .*\.dat_old of S6Bot' }
    @{ Id = 'S11'; Player = 'S11Bot'; Prepare = @(''); Join = @('throw-on-placement', 'throw-on-placement'); Setup = @(); Proof = '';
        JoinProof = 'pdrtest: deliberate failure: placement of S11Bot' }
    @{ Id = 'S11new'; Player = 'S11NewBot'; Prepare = @(); Join = @('throw-on-placement'); Setup = @(); Proof = '';
        JoinProof = 'pdrtest: deliberate failure: placement of S11NewBot' }
    @{ Id = 'S12'; Player = 'S12Bot'; Prepare = @(''); Join = @(''); Setup = @(); Proof = '' }
    @{ Id = 'S13'; Player = 'S13Bot'; Prepare = @('unreadable-attachment'); Setup = @(); Operator = $true;
        Join = @('unreadable-attachment', 'unreadable-attachment', 'unreadable-attachment', 'unreadable-attachment');
        Proof = 'saved attachment pdrtest:broken of S13Bot as unreadable'; JoinProof = 'pdrtest: deliberate failure: attachment pdrtest:broken of S13Bot cannot read back' }
    @{ Id = 'S14'; Player = 'S13Bot'; Prepare = @(); Join = @(''); Setup = @(); Proof = '' }
    @{ Id = 'S15'; Player = 'S15Bot'; Prepare = @('attach'); Join = @('attach,placement-rejects-attachment'); Setup = @();
        Proof = 'saved attachment pdrtest:broken of S15Bot as int'; JoinProof = 'pdrtest: deliberate failure: placement of S15Bot with attachment pdrtest:broken' }
)

# How each join ends with the rescue in place: 'invalid' is the "Invalid player data" disconnect,
# 'joined' means the player got in. Keys are scenario ids, optionally with a loader
# ("S2/neoforge") or a band and loader ("S5/1.20-1.20.1/forge").
$expectedOutcomes = @{
    'S1' = 'joined'
    'S2' = 'joined'
    'S3' = 'joined'
    'S4' = 'joined'
    'S5' = 'joined'
    'S6' = 'joined'
    'S11' = 'invalid'
    'S11new' = 'invalid'
    'S12' = 'joined'
    'S13' = 'joined'
    'S14' = 'joined'
    'S15' = 'joined'
}

# The incidents each scenario must leave in <world>/playerdatarescue/<uuid>/ (SPEC.md sections 3
# and 6), or $null for none. S1, S3 and S4, and S2 and S13 on NeoForge, are units the game drops
# without an error (trigger T4, a later milestone), so no incident is written for them yet.
$playerMessage = 'Some of your saved data could not be loaded and was set aside. An admin can restore it.'
$expectedIncidents = @{
    'S1' = $null
    'S2' = @{ Count = 1; Trigger = 'T1'; Outcome = 'rescued'; Entry = 'attachment.pdrtest:broken'; Suspect = 'pdrtest'; Message = $playerMessage }
    'S2/fabric' = @{ Count = 1; Trigger = 'T1'; Outcome = 'rescued'; Entry = 'key.pdrtest:broken'; Suspect = 'pdrtest'; Message = $playerMessage }
    'S2/neoforge' = $null
    'S3' = $null
    'S4' = $null
    'S5' = @{ Count = 1; Trigger = 'T2'; Outcome = 'previous_save'; Originals = 2;
        Message = 'Your latest save could not be read, so you joined with the save before it.' }
    'S6' = @{ Count = 1; Trigger = 'T2'; Outcome = 'unreadable_file'; Originals = 2; Report = 'unreadable file';
        Message = 'Your saved data could not be read, so you joined as a new player.' }
    # S11Bot has saved data: the rescue removes the half-placed player, tries the minimal profile,
    # and then writes the diagnosis. S11NewBot has none, so its diagnosis comes at once.
    'S11' = @{ Count = 1; Trigger = 'T3'; Outcome = 'not_data_related'; Suspect = 'pdrtest'; Report = 'joining failed even with a minimal profile';
        Failure = 'pdrtest: deliberate failure: placement of S11Bot' }
    'S11new' = @{ Count = 1; Trigger = 'T3'; Outcome = 'not_data_related'; Suspect = 'pdrtest'; Originals = 0; Report = 'could not join as a new player' }
    'S12' = $null
    # S13: every join keeps its incident, the recurring record only groups them (SPEC.md section 4.4).
    'S13' = @{ Count = 4; Trigger = 'T1'; Outcome = 'rescued'; Entry = 'attachment.pdrtest:broken'; Suspect = 'pdrtest'; Message = $playerMessage }
    'S13/fabric' = @{ Count = 4; Trigger = 'T1'; Outcome = 'rescued'; Entry = 'key.pdrtest:broken'; Suspect = 'pdrtest'; Message = $playerMessage }
    'S13/neoforge' = $null
    'S14' = $null
    # S15: the placement fails because of the attachment (T3), so the minimal profile is placed on
    # the same connection and every other section is set aside whole, the attachment among them.
    'S15' = @{ Count = 1; Trigger = 'T3'; Outcome = 'minimal_profile'; Includes = 'section.ForgeCaps'; Suspect = 'pdrtest'; Message = $playerMessage;
        Report = 'could not join even with the broken parts set aside' }
    'S15/fabric' = @{ Count = 1; Trigger = 'T3'; Outcome = 'minimal_profile'; Includes = 'key.pdrtest:broken'; Suspect = 'pdrtest'; Message = $playerMessage;
        Report = 'could not join even with the broken parts set aside' }
    'S15/neoforge' = @{ Count = 1; Trigger = 'T3'; Outcome = 'minimal_profile'; Includes = 'section.neoforge:attachments'; Suspect = 'pdrtest'; Message = $playerMessage;
        Report = 'could not join even with the broken parts set aside' }
}

# SPEC.md scenario S12: the time this mod's hooks take on a normal join, logged when the server
# runs with -Dplayerdatarescue.timing=true (prod-smoke.ps1 sets it), must stay under 1 ms.
$normalPathBudgetMicros = 1000

$scenarioModes = @{}
$scenarioServer = $null
$normalPathMicros = @()
$incidentSnapshot = ''
$scenarioDeadline = $null

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

function Assert-ScenarioTime {
    if ($scenarioServer.HasExited) { throw 'The server stopped during the scenarios.' }
    if ((Get-Date) -gt $scenarioDeadline) { throw "The scenarios did not finish within -TimeoutMinutes $TimeoutMinutes." }
}

function Wait-ForLog {
    param([int]$Offset, [string]$Pattern, [int]$Seconds = 60)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-LogSince -Offset $Offset) -match $Pattern) { return $true }
        Assert-ScenarioTime
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Start-JoinBot {
    param([string]$Player, [int]$StaySeconds)
    $output = Join-Path $serverDir "bot-$Player.log"
    Assert-ScenarioTime
    $botArgs = @('-cp', 'join-bot', 'JoinBot', '--port', "$ServerPort", '--protocol', "$(Get-ProtocolVersion)", '--name', $Player,
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

function Get-ScenarioValue {
    param([hashtable]$Table, [string]$ScenarioId)
    foreach ($key in "$ScenarioId/$BandId/$Loader", "$ScenarioId/$Loader", $ScenarioId) {
        if ($Table.ContainsKey($key)) { return @{ Found = $true; Value = $Table[$key] } }
    }
    return @{ Found = $false; Value = $null }
}

function Get-ExpectedOutcome {
    param([string]$ScenarioId)
    $entry = Get-ScenarioValue -Table $expectedOutcomes -ScenarioId $ScenarioId
    if (-not $entry.Found) { throw "No expected outcome recorded for $ScenarioId." }
    return $entry.Value
}

function Get-OfflineUuid {
    # The UUID an offline-mode server gives a player: a name-based (version 3) UUID of "OfflinePlayer:<name>".
    param([string]$Name)
    $md5 = [System.Security.Cryptography.MD5]::Create()
    try { $hash = $md5.ComputeHash([System.Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$Name")) } finally { $md5.Dispose() }
    $hash[6] = ($hash[6] -band 0x0f) -bor 0x30
    $hash[8] = ($hash[8] -band 0x3f) -bor 0x80
    $hex = -join ($hash | ForEach-Object { $_.ToString('x2') })
    return '{0}-{1}-{2}-{3}-{4}' -f $hex.Substring(0, 8), $hex.Substring(8, 4), $hex.Substring(12, 4), $hex.Substring(16, 4), $hex.Substring(20, 12)
}

function Get-PlayerIncidents {
    param([string]$Player)
    $dir = Join-Path $serverDir "smokeworld/playerdatarescue/$(Get-OfflineUuid -Name $Player)"
    if (-not (Test-Path -LiteralPath $dir)) { return @() }
    return @(Get-ChildItem -LiteralPath $dir -Directory | Sort-Object Name)
}

function Save-ScenarioFixtures {
    # Real files for the core tests (core/src/test/resources/fixtures/README.md): the player files
    # as the game and the test mod left them before the scenario joins.
    param([string]$ScenarioId, [string]$Player)
    $target = Join-Path $ProjectRoot "build/prod-smoke/fixtures/$BandId-$Loader/$ScenarioId"
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    $uuid = Get-OfflineUuid -Name $Player
    # 26.3 moved the player files from playerdata/ to players/data/.
    $dataDir = Join-Path $serverDir 'smokeworld/players/data'
    if (-not (Test-Path -LiteralPath $dataDir)) { $dataDir = Join-Path $serverDir 'smokeworld/playerdata' }
    foreach ($suffix in '.dat', '.dat_old') {
        $file = Join-Path $dataDir "$uuid$suffix"
        if (Test-Path -LiteralPath $file) { Copy-Item -LiteralPath $file -Destination (Join-Path $target "$Player$suffix") -Force }
    }
}

function Test-ScenarioIncidents {
    # Checks the incident folders a scenario left against $expectedIncidents; returns failures.
    param([hashtable]$Scenario, [System.IO.DirectoryInfo[]]$Before, [string]$BotOutputs, [string]$JoinLog)
    $id = $Scenario.Id
    $problems = @()
    $expected = (Get-ScenarioValue -Table $expectedIncidents -ScenarioId $id).Value
    $known = @($Before | ForEach-Object { $_.Name })
    $new = @(Get-PlayerIncidents -Player $Scenario.Player | Where-Object { $known -notcontains $_.Name })
    if ($null -eq $expected) {
        if ($new.Count -gt 0) { $problems += "$id wrote $($new.Count) incident(s), none expected." }
        return , $problems
    }
    if ($new.Count -ne $expected.Count) {
        $problems += "$id wrote $($new.Count) incident(s), expected $($expected.Count)."
        return , $problems
    }
    foreach ($folder in $new) {
        $reportPath = Join-Path $folder.FullName 'report.json'
        if (-not (Test-Path -LiteralPath $reportPath)) { $problems += "$id $($folder.Name) has no report.json."; continue }
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
        $text = Get-Content -LiteralPath (Join-Path $folder.FullName 'report.txt') -Raw
        if ($report.trigger -ne $expected.Trigger) { $problems += "$id $($folder.Name): trigger $($report.trigger), expected $($expected.Trigger)." }
        if ($report.outcome -ne $expected.Outcome) { $problems += "$id $($folder.Name): outcome $($report.outcome), expected $($expected.Outcome)." }
        $originals = @(Get-ChildItem -LiteralPath (Join-Path $folder.FullName 'original') -File -ErrorAction SilentlyContinue)
        if (-not $expected.ContainsKey('Originals') -and $originals.Count -lt 1) { $problems += "$id $($folder.Name): original/ is empty." }
        if ($expected.ContainsKey('Originals') -and $originals.Count -ne $expected.Originals) {
            $problems += "$id $($folder.Name): original/ holds $($originals.Count) file(s), expected $($expected.Originals)."
        }
        if ($expected.Entry) {
            $entries = @($report.entries | ForEach-Object { $_.id })
            if (($entries.Count -ne 1) -or ($entries[0] -ne $expected.Entry)) {
                $problems += "$id $($folder.Name): set aside [$($entries -join ', ')], expected only $($expected.Entry)."
            }
            $payload = Join-Path $folder.FullName ($report.entries[0].payload)
            if (-not (Test-Path -LiteralPath $payload)) { $problems += "$id $($folder.Name): no payload file $payload." }
        }
        if ($expected.Includes) {
            $entry = @($report.entries | Where-Object { $_.id -eq $expected.Includes }) | Select-Object -First 1
            if (-not $entry) {
                $problems += "$id $($folder.Name): set aside [$(@($report.entries | ForEach-Object { $_.id }) -join ', ')], none is $($expected.Includes)."
            } elseif (-not (Test-Path -LiteralPath (Join-Path $folder.FullName $entry.payload))) {
                $problems += "$id $($folder.Name): no payload file for $($expected.Includes)."
            }
        }
        if ($expected.Suspect -and (@($report.suspects | ForEach-Object { $_.modId }) -notcontains $expected.Suspect)) {
            $problems += "$id $($folder.Name): suspects [$(@($report.suspects | ForEach-Object { $_.modId }) -join ', ')] do not name $($expected.Suspect)."
        }
        # The diagnosis rests on the last failure: it must be the test mod's, not a side effect of the retry.
        if ($expected.Failure -and -not ([string]$report.failure.message).Contains($expected.Failure)) {
            $problems += "$id $($folder.Name): the last failure is '$($report.failure.class): $($report.failure.message)', expected '$($expected.Failure)'."
        }
        if ($expected.Report -and -not $text.Contains($expected.Report)) { $problems += "$id $($folder.Name): report.txt does not say '$($expected.Report)'." }
    }
    if ($expected.Message -and -not $BotOutputs.Contains($expected.Message)) {
        $problems += "$id the player did not get the message '$($expected.Message)'."
    }
    if ($JoinLog -match "Couldn't place player in world" -and $expected.Outcome -ne 'not_data_related') {
        $problems += "$id logged 'Couldn't place player in world' although the rescue let the player in."
    }
    return , $problems
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

function Invoke-TeleportCheck {
    # The join client reports its position after a teleport. Without that report, 1.20.1 and
    # 1.21.1 keep tracking the player at its join position, and the destination unloads once the
    # short ticket of the teleport expires. On 26.3 the client must also end each tick, or its
    # second position packet disconnects it. The destination is 1000 blocks from the join
    # position, so neither the spawn chunks nor the chunks around the join position cover it.
    # The teleport ticket lasts a few server ticks, which can take seconds on a busy server, so
    # the destination must stay loaded at every check from 8 to 24 seconds after the teleport,
    # and the fill must work at the end. The check runs after the scenarios, once the server has
    # settled.
    $player = 'TpBot'
    Set-PlayerModes -Player $player -Modes ''
    $offset = Get-LatestLogLength
    $bot = Start-JoinBot -Player $player -StaySeconds 40
    $unloaded = @()
    $checks = 0
    try {
        if (-not (Wait-ForLog -Offset $offset -Pattern "$player\[[^\]]*\] logged in")) {
            throw "teleport check: $player never logged in."
        }
        # Creative mode lets the client stand in the air at the destination without being kicked for flying.
        Invoke-RconCommand -Command "gamemode creative $player" | Out-Null
        $teleported = (Invoke-RconCommand -Command "execute at $player run tp $player ~1000 100 ~").Trim()
        $start = Get-Date
        while (((Get-Date) - $start).TotalSeconds -lt 24) {
            Start-Sleep -Seconds 2
            Assert-ScenarioTime
            $seconds = ((Get-Date) - $start).TotalSeconds
            if ($seconds -lt 8) { continue }
            $elapsed = [int][Math]::Floor($seconds)
            $checks++
            # A command that fails under execute answers nothing over RCON.
            $answer = (Invoke-RconCommand -Command "execute at $player if loaded ~ ~-1 ~").Trim()
            if ($answer -notmatch 'Test passed') { $unloaded += "+${elapsed}s" }
        }
        $filled = (Invoke-RconCommand -Command "execute at $player run fill ~-8 ~-1 ~-8 ~7 ~-1 ~7 minecraft:glass").Trim()
        if (-not $filled) { $filled = '(no answer: the fill failed)' }
        $position = (Invoke-RconCommand -Command "data get entity $player Pos").Trim()
    } finally {
        $output = Wait-JoinBot -Bot $bot
    }
    $line = "teleport check ${player}: tp -> $teleported; destination loaded at $($checks - $unloaded.Count) of $checks checks from +8 s to +24 s; fill under the player -> $filled; $position"
    if ($checks -lt 6) { throw "$line (too few checks)" }
    if ($unloaded.Count -gt 0) { throw "$line (not loaded at $($unloaded -join ', '))" }
    if ($filled -notmatch 'Successfully filled') { throw "$line (the destination is not loaded around the player)" }
    if ($output -notmatch 'left after staying') { throw "$line; the client did not stay: $output" }
    return $line
}

function Get-BrickCount {
    # The bricks a player carries, counted by the game: "clear <player> <item> 0" removes nothing.
    param([string]$Player)
    $answer = (Invoke-RconCommand -Command "clear $Player minecraft:brick 0").Trim()
    if ($answer -match 'Found (\d+) matching') { return [int]$Matches[1] }
    if ($answer -match 'No items were found') { return 0 }
    throw "S16: unexpected answer to the brick count: $answer"
}

function Invoke-RestoreJoin {
    # Joins with a placement that works again, counts the bricks while the player is in, and leaves.
    param([string]$Player)
    Set-PlayerModes -Player $Player -Modes 'attach'
    $offset = Get-LatestLogLength
    $bot = Start-JoinBot -Player $Player -StaySeconds 8
    try {
        if (-not (Wait-ForLog -Offset $offset -Pattern "$Player\[[^\]]*\] logged in")) { throw "S16: $Player never logged in." }
        Start-Sleep -Seconds 2
        $bricks = Get-BrickCount -Player $Player
        $slot = (Invoke-RconCommand -Command "data get entity $Player Inventory[{Slot:17b}]").Trim()
    } finally {
        $output = Wait-JoinBot -Bot $bot
    }
    if ($output -notmatch 'left after staying') { throw "S16: $Player could not stay: $output" }
    Wait-ForLog -Offset $offset -Pattern "$Player left the game" | Out-Null
    Start-Sleep -Seconds 2
    return @{ Bricks = $bricks; Slot = $slot; Log = (Get-LogSince -Offset $offset); Output = $output }
}

function Invoke-RestoreCheck {
    # SPEC.md scenario S16: S15's minimal profile set the whole inventory aside; an operator
    # restores it while the player is offline, the next join brings the 12 bricks back once, and a
    # second restore of the same entry changes nothing.
    $player = 'S16Bot'
    $scenario = @{ Id = 'S16'; Player = $player; Setup = @("item replace entity $player container.17 with minecraft:brick 12") }
    Invoke-PrepareJoin -Scenario $scenario -Modes 'attach' -RunSetup $true | Out-Null
    Set-PlayerModes -Player $player -Modes 'attach,placement-rejects-attachment'
    $rescue = Wait-JoinBot -Bot (Start-JoinBot -Player $player -StaySeconds 4)
    if ($rescue -notmatch 'left after staying') { throw "S16: the rescue join did not end in the world: $rescue" }
    $incidents = @(Get-PlayerIncidents -Player $player)
    if ($incidents.Count -ne 1) { throw "S16: $($incidents.Count) incident(s) after the rescue, expected 1." }
    $incident = $incidents[0].Name
    $listed = Invoke-RconCommand -Command "pdrescue list $player"
    if (-not $listed.Contains($incident)) { throw "S16: /pdrescue list does not show $incident`: $listed" }
    $shown = Invoke-RconCommand -Command "pdrescue show $player $incident"
    if (-not $shown.Contains('section.Inventory')) { throw "S16: /pdrescue show does not list section.Inventory: $shown" }

    $first = Invoke-RconCommand -Command "pdrescue restore $player $incident section.Inventory"
    if ($first -notmatch 'will be restored at their next join') { throw "S16: the first restore answered: $first" }
    $afterFirst = Invoke-RestoreJoin -Player $player
    if ($afterFirst.Bricks -ne 12) { throw "S16: $($afterFirst.Bricks) brick(s) after the restore, expected 12." }
    if ($afterFirst.Slot -notmatch 'minecraft:brick') { throw "S16: slot 17 does not hold the bricks after the restore: $($afterFirst.Slot)" }
    if ($afterFirst.Output -notmatch 'set-aside part of your saved data was restored') { throw 'S16: the player was not told about the restore.' }

    $second = Invoke-RconCommand -Command "pdrescue restore $player $incident section.Inventory"
    if ($second -notmatch 'Already restored, nothing to do: section.Inventory') { throw "S16: the second restore answered: $second" }
    $afterSecond = Invoke-RestoreJoin -Player $player
    if ($afterSecond.Bricks -ne 12) { throw "S16: $($afterSecond.Bricks) brick(s) after the second restore, expected 12." }

    $report = Get-Content -LiteralPath (Join-Path $incidents[0].FullName 'report.json') -Raw | ConvertFrom-Json
    $entry = @($report.entries | Where-Object { $_.id -eq 'section.Inventory' })[0]
    if ($entry.status -ne 'restored') { throw "S16: section.Inventory is '$($entry.status)', expected restored." }
    $all = @(Get-PlayerIncidents -Player $player)
    $backups = @($all | Where-Object { (Get-Content -LiteralPath (Join-Path $_.FullName 'report.json') -Raw | ConvertFrom-Json).trigger -eq 'RESTORE' })
    if ($all.Count -ne 2 -or $backups.Count -ne 1) { throw "S16: $($all.Count) incident(s) with $($backups.Count) restore backup(s), expected the rescue and one backup." }
    if (-not (Test-Path -LiteralPath (Join-Path $backups[0].FullName 'original'))) { throw 'S16: the restore backup has no original/ folder.' }
    return "S16 ${player}: restore queued offline, 12 bricks back in slot 17 after the next join, second restore refused ('Already restored'), still 12 bricks; entry restored; one backup incident"
}

function Start-Operator {
    # An online operator for the S2 and S13 notices (SPEC.md step 7 and section 4.4): joins, is
    # made an operator, and stays.
    param([int]$StaySeconds = 25)
    $offset = Get-LatestLogLength
    Set-PlayerModes -Player 'OpBot' -Modes ''
    $bot = Start-JoinBot -Player 'OpBot' -StaySeconds $StaySeconds
    if (-not (Wait-ForLog -Offset $offset -Pattern "OpBot\[[^\]]*\] logged in")) { throw 'OpBot never logged in.' }
    Invoke-RconCommand -Command 'op OpBot' | Out-Null
    Start-Sleep -Seconds 1
    return $bot
}

function Save-HostDataFixture {
    # SPEC.md scenario S8 fixture: a level.dat whose Data.Player holds the test attachment, written
    # by the game's own save while the test mod's host-data mode makes S8Bot the host player
    # (Fabric 1.20.1 and 1.21.1 only; on 26.3 the host data is a player file).
    if ($Loader -ne 'fabric' -or @('1.20.1', '1.21.1') -notcontains $band.compileTarget) { return 'S8 fixture: not on this band' }
    Set-PlayerModes -Player 'S8Bot' -Modes 'host-data'
    $offset = Get-LatestLogLength
    $bot = Start-JoinBot -Player 'S8Bot' -StaySeconds 10
    if (-not (Wait-ForLog -Offset $offset -Pattern "S8Bot\[[^\]]*\] logged in")) { throw 'S8Bot never logged in.' }
    Invoke-RconCommand -Command 'save-all flush' | Out-Null
    if (-not (Wait-ForLog -Offset $offset -Pattern 'wrote the data of S8Bot as the host player')) { throw 'S8: the test mod did not write the host data.' }
    Wait-JoinBot -Bot $bot | Out-Null
    $target = Join-Path $ProjectRoot "build/prod-smoke/fixtures/$BandId-$Loader/S8"
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    foreach ($name in 'level.dat', 'level.dat_old') {
        $file = Join-Path $serverDir "smokeworld/$name"
        if (Test-Path -LiteralPath $file) { Copy-Item -LiteralPath $file -Destination (Join-Path $target $name) -Force }
    }
    Set-PlayerModes -Player 'S8Bot' -Modes ''
    return 'S8 fixture: level.dat with the host data of S8Bot saved'
}

function Get-IncidentSnapshot {
    # Every file under <world>/playerdatarescue with its SHA-256, to prove S10 leaves it untouched.
    $root = Join-Path $serverDir 'smokeworld/playerdatarescue'
    if (-not (Test-Path -LiteralPath $root)) { return '' }
    $lines = @(Get-ChildItem -LiteralPath $root -Recurse -File | Sort-Object FullName | ForEach-Object {
            "$($_.FullName.Substring($root.Length)) $((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash)"
        })
    return ($lines -join "`n")
}

function Invoke-RemovedModCheck {
    # SPEC.md scenario S10: the release JAR is gone, the world still loads, a player joins, and
    # the playerdatarescue folder the scenarios wrote is left as it was.
    param([System.Diagnostics.Process]$ServerProcess, [datetime]$Deadline)
    $script:scenarioServer = $ServerProcess
    $script:scenarioDeadline = $Deadline
    if (-not $incidentSnapshot) { throw 'S10: the scenarios left no playerdatarescue folder to check.' }
    $offset = Get-LatestLogLength
    # Forge refuses logins for a moment after the "Done" line ("Server is still starting! Please
    # wait before reconnecting."), and nothing else runs before this join to hide that moment.
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        $attemptOffset = Get-LatestLogLength
        $output = Wait-JoinBot -Bot (Start-JoinBot -Player 'S12Bot' -StaySeconds 4)
        if ($output -match 'left after staying' -or (Get-LogSince -Offset $attemptOffset) -notmatch 'Server is still starting') { break }
        Start-Sleep -Seconds 1
    }
    if ($output -notmatch 'left after staying') { throw "S10: S12Bot could not join without the mod after $attempt attempt(s): $output" }
    if ((Get-LogSince -Offset $offset) -match '\[playerdatarescue\]') { throw 'S10: the mod logged a line although its JAR is removed.' }
    if ((Get-IncidentSnapshot) -ne $incidentSnapshot) { throw 'S10: the playerdatarescue folder changed while the mod was removed.' }
    Write-Host "  S10: the world loaded without the mod, S12Bot joined, and the $(@($incidentSnapshot -split "`n").Count) incident file(s) are unchanged"
}

function Install-JoinBot {
    # Compiled once per run instead of once per join by the single-file source launcher.
    $botDir = Join-Path $serverDir 'join-bot'
    if (Test-Path -LiteralPath $botDir) { Remove-Item -LiteralPath $botDir -Recurse -Force }
    $javac = Join-Path (Split-Path -Parent $javaExe) $(if ($OnWindows) { 'javac.exe' } else { 'javac' })
    & $javac -d $botDir (Join-Path $ProjectRoot 'testmod/join-bot/JoinBot.java')
    if ($LASTEXITCODE -ne 0) { throw "javac failed on the join bot (exit code $LASTEXITCODE)." }
}

function Invoke-SmokeScenarios {
    param([System.Diagnostics.Process]$ServerProcess, [datetime]$Deadline)
    $script:scenarioServer = $ServerProcess
    $script:scenarioDeadline = $Deadline
    Install-JoinBot
    $failures = @()
    $summary = @()
    try {
        $summary += Save-HostDataFixture
    } catch {
        $failures += $_.Exception.Message
    }
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
            Save-ScenarioFixtures -ScenarioId $id -Player $scenario.Player
            $expected = Get-ExpectedOutcome -ScenarioId $id
            $incidentsBefore = Get-PlayerIncidents -Player $scenario.Player
            $operator = $null
            if ($scenario.Operator -and $null -ne (Get-ScenarioValue -Table $expectedIncidents -ScenarioId $id).Value) {
                $operator = Start-Operator -StaySeconds (15 + 10 * $scenario.Join.Count)
            }
            $streaksBefore = Get-RecurringStreaks
            $botOutputs = ''
            $scenarioLogOffset = Get-LatestLogLength
            for ($j = 0; $j -lt $scenario.Join.Count; $j++) {
                Set-PlayerModes -Player $scenario.Player -Modes $scenario.Join[$j]
                $offset = Get-LatestLogLength
                $botOutput = Wait-JoinBot -Bot (Start-JoinBot -Player $scenario.Player -StaySeconds 4)
                $botOutputs += $botOutput
                Start-Sleep -Seconds 2
                $joinLog = Get-LogSince -Offset $offset
                $result = Get-JoinOutcome -BotOutput $botOutput -Player $scenario.Player -ServerLog $joinLog
                $placeFailed = $joinLog -match "Couldn't place player in world"
                if ($scenario.JoinProof -and -not ($joinLog -match $scenario.JoinProof)) {
                    $failures += "$id join did not hit the test mod failure: the log has no '$($scenario.JoinProof)' line."
                }
                $line = "$id $($scenario.Player): $($result.Outcome) ($($result.Detail)); log has 'Couldn't place player in world': $placeFailed; expected: $expected"
                $summary += $line
                if ($result.Outcome -ne $expected) { $failures += $line }
                if ($result.Outcome -eq 'invalid' -and -not $placeFailed) { $failures += "$id disconnected without the 'Couldn't place player in world' log line." }
                if (@('S11', 'S15') -contains $id -and $j -eq 0 -and @([regex]::Matches($joinLog, "$($scenario.Player)\[[^\]]*\] logged in")).Count -lt 2) {
                    $failures += "$id first join: the minimal profile was not placed after the failed placement."
                }
                # The client only gets the placement that worked (ARCHITECTURE.md, Cleanup after a failed placement).
                # When no rescue is tried (S11's second join), the disconnect lets out the failed placement's packets.
                $logins = @([regex]::Matches($botOutput, 'joined the world')).Count
                $expectedLogins = @{ 'S15' = 1; 'S11new' = 0 }[$id]
                if ($id -eq 'S11') { $expectedLogins = @(0, 1)[$j] }
                if ($null -ne $expectedLogins -and $logins -ne $expectedLogins) {
                    $failures += "$id join $($j + 1): the join client got $logins login packet(s), expected $expectedLogins."
                }
                if ($id -eq 'S11' -and $j -eq 1 -and $joinLog -notmatch 'no rescue is tried') {
                    $failures += 'S11 second join: the log does not say that no rescue is tried.'
                }
                if ($id -eq 'S12') {
                    if ($joinLog -match "normal path for S12Bot: ([0-9.]+) us in ([0-9]+) hook call") {
                        $micros = [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture)
                        $script:normalPathMicros += $micros
                        $summary += "S12 normal path: $micros us in $($Matches[2]) hook call(s), budget $normalPathBudgetMicros us"
                        if ($micros -ge $normalPathBudgetMicros) { $failures += "S12 normal path took $micros us, over $normalPathBudgetMicros us." }
                    } else {
                        $failures += 'S12: the log has no normal path timing line for S12Bot.'
                    }
                }
            }
            $scenarioLog = Get-LogSince -Offset $scenarioLogOffset
            $failures += Test-ScenarioIncidents -Scenario $scenario -Before $incidentsBefore -BotOutputs $botOutputs -JoinLog $scenarioLog
            if (@('S13', 'S14') -contains $id) {
                $recurring = Test-RecurringRecord -Scenario $scenario -JoinLog $scenarioLog -StreaksBefore $streaksBefore
                $failures += $recurring.Problems
                $summary += $recurring.Lines
            }
            if ($operator) {
                $operatorOutput = Wait-JoinBot -Bot $operator
                # The notice is a translatable component: its English fallback and its arguments
                # arrive as separate fields, in JSON (1.20.1) or as network NBT.
                $notice = @($operatorOutput -split "`r?`n" | Where-Object {
                        $_.Contains('playerdatarescue.operator.rescued') -and $_.Contains('joined after a rescue') -and $_.Contains($scenario.Player)
                    })
                if ($notice.Count -gt 0) { $summary += "$id operator notice received by OpBot" }
                else { $failures += "$id the operator OpBot did not get the rescue notice for $($scenario.Player)." }
                $recurringNotice = Test-RecurringNotice -Scenario $scenario -OperatorOutput $operatorOutput
                $failures += $recurringNotice.Problems
                $summary += $recurringNotice.Lines
            }
        } catch {
            $failures += "$id failed: $($_.Exception.Message)"
            if ($scenarioServer.HasExited) { break }
        }
    }
    try {
        $summary += Invoke-RestoreCheck
    } catch {
        $failures += $_.Exception.Message
    }
    try {
        $summary += Invoke-TeleportCheck
    } catch {
        $failures += $_.Exception.Message
    }
    $script:incidentSnapshot = Get-IncidentSnapshot
    Write-Host "Failure scenarios on $BandId/${Loader}:"
    foreach ($line in $summary) { Write-Host "  $line" }
    if ($failures.Count -gt 0) {
        throw "Scenario check failed on $BandId/${Loader}:$([Environment]::NewLine)  $($failures -join "$([Environment]::NewLine)  ")"
    }
}
