# Recurring issue checks of the production smoke test (SPEC.md section 4.4, scenarios S13 and
# S14), dot-sourced by prod-smoke.ps1 after smoke-scenarios.ps1. Before S13, every player with a
# streak on the S13 key (S2Bot, from S2) joins once without the failure, which must end the
# streak, so S13Bot is alone on the key as S13 says. S13Bot then fails at 4 joins in a row: no
# record after the second, a record counting 3 at the third, 4 after the fourth, and the
# operator online gets the recurring notice once. S14 is S13Bot's next join with the test mod
# fixed: the key loads, and the record moves to recurring/closed/.
# Works on Windows PowerShell 5.1 and on PowerShell 7.
# Uses from prod-smoke.ps1 and smoke-scenarios.ps1: $serverDir, $Loader, $expectedIncidents,
# $playerMessage, Get-ScenarioValue, Get-OfflineUuid, Set-PlayerModes, Start-JoinBot,
# Wait-JoinBot, Get-LatestLogLength, Wait-ForLog.

function Get-RecurringKey {
    # The key S13 sets aside on this loader, or $null where the game drops it without an error (T4).
    $expected = (Get-ScenarioValue -Table $expectedIncidents -ScenarioId 'S13').Value
    if ($null -eq $expected) { return $null }
    return $expected.Entry
}

function Get-RecurringRecords {
    param([string]$Folder)
    $dir = Join-Path $serverDir "smokeworld/playerdatarescue/recurring$Folder"
    if (-not (Test-Path -LiteralPath $dir)) { return , @() }
    return , @(Get-ChildItem -LiteralPath $dir -Filter '*.json' -File | ForEach-Object {
            @{ File = $_; Record = (Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json) }
        })
}

function Get-OpenRecord {
    param([string]$Key)
    return , @((Get-RecurringRecords -Folder '') | Where-Object { $_.Record.key -eq $Key })
}

function Get-RecurringStreaks {
    # The streak of every player on the S13 key, by UUID, with the player's name.
    param([string]$Key)
    $streaks = @{}
    $root = Join-Path $serverDir 'smokeworld/playerdatarescue'
    if (-not (Test-Path -LiteralPath $root)) { return $streaks }
    foreach ($dir in @(Get-ChildItem -LiteralPath $root -Directory)) {
        $file = Join-Path $dir.FullName 'streaks.json'
        if (-not (Test-Path -LiteralPath $file)) { continue }
        $json = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
        $value = $json.streaks.$Key
        if ($null -ne $value) { $streaks[$dir.Name] = @{ Name = $json.name; Streak = [int]$value } }
    }
    return $streaks
}

function Reset-RecurringStreaks {
    # Before S13: S2 set the same key aside once for S2Bot, so S2Bot has a streak of 1 on it. A
    # join without the failure must end it (SPEC.md section 4.4).
    param([hashtable]$Scenario)
    $problems = @()
    $lines = @()
    $key = Get-RecurringKey
    if ($Scenario.Id -ne 'S13' -or $null -eq $key) { return @{ Problems = $problems; Lines = $lines } }
    $streaks = Get-RecurringStreaks -Key $key
    $s2 = Get-OfflineUuid -Name 'S2Bot'
    if ($streaks.Count -ne 1 -or $null -eq $streaks[$s2] -or $streaks[$s2].Streak -ne 1) {
        $found = @($streaks.Keys | ForEach-Object { "$($streaks[$_].Name)=$($streaks[$_].Streak)" }) -join ', '
        $problems += "Before S13, the streaks on $key are [$found], expected only S2Bot=1 (from S2)."
    }
    foreach ($uuid in @($streaks.Keys)) {
        $name = $streaks[$uuid].Name
        Set-PlayerModes -Player $name -Modes ''
        $offset = Get-LatestLogLength
        $output = Wait-JoinBot -Bot (Start-JoinBot -Player $name -StaySeconds 2)
        if ($output -notmatch 'left after staying') { $problems += "Before S13, $name could not join normally: $output" }
        if (-not (Wait-ForLog -Offset $offset -Pattern "$name left the game")) { $problems += "Before S13, $name never left." }
    }
    $left = Get-RecurringStreaks -Key $key
    if ($left.Count -gt 0) {
        $problems += "Before S13, a normal join did not end the streak on $key of $(@($left.Keys | ForEach-Object { $left[$_].Name }) -join ', ')."
    } elseif ($streaks.Count -gt 0) {
        $lines += "S13 setup: a normal join ended the streak of $(@($streaks.Keys | ForEach-Object { $streaks[$_].Name }) -join ', ') on $key"
    }
    return @{ Problems = $problems; Lines = $lines }
}

function Test-RecurringJoin {
    # S13, after each join: the record appears at the third and counts every join from then on.
    param([hashtable]$Scenario, [int]$Index, [string]$JoinLog, [string]$BotOutput)
    $problems = @()
    $key = Get-RecurringKey
    if ($Scenario.Id -ne 'S13' -or $null -eq $key) { return , $problems }
    $join = $Index + 1
    $open = Get-OpenRecord -Key $key
    $made = $JoinLog -match ([regex]::Escape($key) + ' fails to load at every join')
    if ($join -lt 3) {
        if ($open.Count -ne 0) { $problems += "S13 join ${join}: a record of $key exists already." }
    } elseif ($open.Count -ne 1) {
        $problems += "S13 join ${join}: $($open.Count) open record(s) of $key, expected 1."
    } elseif ($open[0].Record.count -ne $join) {
        $problems += "S13 join ${join}: the record of $key counts $($open[0].Record.count), expected $join."
    }
    if ($made -ne ($join -eq 3)) {
        $problems += "S13 join ${join}: the log line that $key is now a recurring issue is $(if ($made) { 'there' } else { 'missing' })."
    }
    # The incident and its messages still come at every join (SPEC.md section 4.4).
    if (-not $BotOutput.Contains($playerMessage)) { $problems += "S13 join ${join}: the player did not get the message '$playerMessage'." }
    return , $problems
}

function Test-RecurringRecord {
    # The record files S13 and S14 must leave; returns failures and summary lines.
    param([hashtable]$Scenario, [string]$JoinLog)
    $problems = @()
    $lines = @()
    $key = Get-RecurringKey
    if ($null -eq $key) {
        if ((Get-RecurringRecords -Folder '').Count -gt 0) { $problems += "$($Scenario.Id) left a recurring record, none expected on $Loader." }
        return @{ Problems = $problems; Lines = $lines }
    }
    $open = Get-OpenRecord -Key $key
    if ($Scenario.Id -eq 'S13') {
        if ($open.Count -ne 1) { return @{ Problems = @("S13 left $($open.Count) open record(s) of $key, expected 1."); Lines = $lines } }
        $record = $open[0].Record
        $players = @($record.players.PSObject.Properties | ForEach-Object { $_.Name })
        if ($players.Count -ne 1 -or $players[0] -ne (Get-OfflineUuid -Name $Scenario.Player)) {
            $problems += "S13 record of ${key}: players [$($players -join ', ')], expected only $($Scenario.Player)."
        }
        if ($record.suspect -ne 'pdrtest') { $problems += "S13 record of ${key}: suspect '$($record.suspect)', expected pdrtest." }
        foreach ($name in $record.firstPayloadFile, $record.latestPayloadFile) {
            if ([string]::IsNullOrEmpty($name) -or -not (Test-Path -LiteralPath (Join-Path $open[0].File.DirectoryName $name))) {
                $problems += "S13 record of ${key}: payload file '$name' is missing."
            }
        }
        $lines += "S13 recurring record of ${key}: made at the third join, count $($record.count)"
    }
    if ($Scenario.Id -eq 'S14') {
        $closed = @((Get-RecurringRecords -Folder '/closed') | Where-Object { $_.Record.key -eq $key })
        if ($open.Count -ne 0) { $problems += "S14 left the record of $key open." }
        if ($closed.Count -ne 1) {
            $problems += "S14 left $($closed.Count) closed record(s) of $key, expected 1."
        } else {
            $base = $closed[0].File.BaseName
            foreach ($suffix in '.first.nbt', '.latest.nbt') {
                if (-not (Test-Path -LiteralPath (Join-Path $closed[0].File.DirectoryName "$base$suffix"))) {
                    $problems += "S14: the closed record of $key has no $suffix next to it."
                }
            }
        }
        $left = @(Get-ChildItem -LiteralPath (Join-Path $serverDir 'smokeworld/playerdatarescue/recurring') -Filter '*.nbt' -File)
        if ($left.Count -gt 0) { $problems += "S14: payload files left in recurring/: $(@($left | ForEach-Object { $_.Name }) -join ', ')." }
        if ($JoinLog -notmatch ([regex]::Escape($key) + " loaded again for $($Scenario.Player)")) {
            $problems += "S14: the log does not say that $key loaded again for $($Scenario.Player)."
        }
        if ($JoinLog -match 'pdrtest: deliberate failure') { $problems += 'S14: the test mod still failed at this join.' }
        $lines += "S14 recurring record of $key closed"
    }
    return @{ Problems = $problems; Lines = $lines }
}

function Test-RecurringNotice {
    # S13: the operator online during the 4 joins gets the rescue notice at each of them, so it was
    # still there at the fourth, and the recurring notice, with a suspect, exactly once.
    param([hashtable]$Scenario, [string]$OperatorOutput)
    $key = Get-RecurringKey
    if ($Scenario.Id -ne 'S13' -or $null -eq $key) { return @{ Problems = @(); Lines = @() } }
    $problems = @()
    $received = @($OperatorOutput -split "`r?`n")
    $notices = @($received | Where-Object {
            $_.Contains('playerdatarescue.operator.recurring') -and -not $_.Contains('playerdatarescue.operator.recurring_unknown') -and $_.Contains($key)
        })
    if ($notices.Count -ne 1) { $problems += "S13 the operator OpBot got the recurring notice $($notices.Count) time(s), expected 1." }
    $rescues = @($received | Where-Object { $_.Contains('playerdatarescue.operator.rescued') -and $_.Contains($Scenario.Player) })
    if ($rescues.Count -ne $Scenario.Join.Count) {
        $problems += "S13 the operator OpBot got the rescue notice $($rescues.Count) time(s), expected $($Scenario.Join.Count)."
    }
    if ($problems.Count -gt 0) { return @{ Problems = $problems; Lines = @() } }
    return @{ Problems = @(); Lines = @('S13 OpBot got the rescue notice at each join and the recurring notice once') }
}
