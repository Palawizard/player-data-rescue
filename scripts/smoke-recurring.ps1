# Recurring issue checks of the production smoke test (SPEC.md section 4.4, scenarios S13 and
# S14), dot-sourced by prod-smoke.ps1 after smoke-scenarios.ps1. S13Bot fails at 4 joins in a
# row on the same mod key: one record must count them, with the streaks other players had on
# that key before (S2Bot's, from S2), and the operator online gets the recurring notice once.
# S14 is S13Bot's next join with the test mod fixed: the key loads, and the record moves to
# recurring/closed/.
# Works on Windows PowerShell 5.1 and on PowerShell 7.
# Uses from prod-smoke.ps1 and smoke-scenarios.ps1: $serverDir, $Loader, $expectedIncidents,
# Get-ScenarioValue, Get-OfflineUuid.

function Get-RecurringKey {
    # The key S13 sets aside on this loader, or $null where the game drops it without an error (T4).
    $expected = (Get-ScenarioValue -Table $expectedIncidents -ScenarioId 'S13').Value
    if ($null -eq $expected) { return $null }
    return $expected.Entry
}

function Get-RecurringRecords {
    param([string]$Folder)
    $dir = Join-Path $serverDir "smokeworld/playerdatarescue/recurring$Folder"
    if (-not (Test-Path -LiteralPath $dir)) { return @() }
    return @(Get-ChildItem -LiteralPath $dir -Filter '*.json' -File | ForEach-Object {
            @{ File = $_; Record = (Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json) }
        })
}

function Get-RecurringStreaks {
    # The streak of every player on the S13 key, by UUID, read before S13 joins: those players
    # are in the record S13 makes, and their streaks count in it (SPEC.md section 4.4).
    $key = Get-RecurringKey
    $streaks = @{}
    if ($null -eq $key) { return $streaks }
    $root = Join-Path $serverDir 'smokeworld/playerdatarescue'
    if (-not (Test-Path -LiteralPath $root)) { return $streaks }
    foreach ($file in @(Get-ChildItem -LiteralPath $root -Filter 'streaks.json' -File -Recurse)) {
        $value = (Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json).streaks.$key
        if ($null -ne $value) { $streaks[$file.Directory.Name] = [int]$value }
    }
    return $streaks
}

function Test-RecurringRecord {
    # The record files S13 and S14 must leave; returns failures and summary lines.
    param([hashtable]$Scenario, [string]$JoinLog, [hashtable]$StreaksBefore)
    $problems = @()
    $lines = @()
    $key = Get-RecurringKey
    $open = @(Get-RecurringRecords -Folder '' | Where-Object { $_.Record.key -eq $key })
    $closed = @(Get-RecurringRecords -Folder '/closed' | Where-Object { $_.Record.key -eq $key })
    if ($null -eq $key) {
        if ((Get-RecurringRecords -Folder '').Count -gt 0) { $problems += "$($Scenario.Id) left a recurring record, none expected on $Loader." }
        return @{ Problems = $problems; Lines = $lines }
    }
    if ($Scenario.Id -eq 'S13') {
        if ($open.Count -ne 1) {
            $problems += "S13 left $($open.Count) open record(s) for $key, expected 1."
            return @{ Problems = $problems; Lines = $lines }
        }
        $record = $open[0].Record
        $uuid = Get-OfflineUuid -Name $Scenario.Player
        $others = @($StreaksBefore.Keys | Where-Object { $_ -ne $uuid })
        $expectedCount = 4
        foreach ($other in $others) {
            $expectedCount += $StreaksBefore[$other]
            if ($null -eq $record.players.$other) { $problems += "S13 record of $key does not name $other, which had a streak on it." }
            if ($null -ne (Get-RecurringStreaks)[$other]) { $problems += "S13: $other still has a streak on $key after the record was made." }
        }
        if ($record.count -ne $expectedCount) {
            $problems += "S13 record of ${key}: count $($record.count), expected $expectedCount (4 joins, plus $($others.Count) other player(s) with a streak)."
        }
        if ($record.suspect -ne 'pdrtest') { $problems += "S13 record of ${key}: suspect '$($record.suspect)', expected pdrtest." }
        if ($record.players.$uuid -ne $Scenario.Player) { $problems += "S13 record of $key does not name $($Scenario.Player) ($uuid)." }
        foreach ($name in $record.firstPayloadFile, $record.latestPayloadFile) {
            if (-not (Test-Path -LiteralPath (Join-Path $open[0].File.DirectoryName $name))) { $problems += "S13 record of ${key}: no payload file $name." }
        }
        $made = @([regex]::Matches($JoinLog, [regex]::Escape($key) + ' fails to load at every join')).Count
        if ($made -ne 1) { $problems += "S13: the log says $made time(s) that $key became a recurring issue, expected 1." }
        $lines += "S13 recurring record of ${key}: count $($record.count), $($others.Count) other player(s) with a streak before"
    }
    if ($Scenario.Id -eq 'S14') {
        if ($open.Count -ne 0) { $problems += "S14 left the record of $key open." }
        if ($closed.Count -ne 1) {
            $problems += "S14 left $($closed.Count) closed record(s) for $key, expected 1."
        } else {
            $base = $closed[0].File.BaseName
            foreach ($suffix in '.first.nbt', '.latest.nbt') {
                if (-not (Test-Path -LiteralPath (Join-Path $closed[0].File.DirectoryName "$base$suffix"))) {
                    $problems += "S14: the closed record of $key has no $suffix next to it."
                }
            }
        }
        if ($JoinLog -notmatch ([regex]::Escape($key) + " loaded again for $($Scenario.Player)")) {
            $problems += "S14: the log does not say that $key loaded again for $($Scenario.Player)."
        }
        $lines += "S14 recurring record of $key closed"
    }
    return @{ Problems = $problems; Lines = $lines }
}

function Test-RecurringNotice {
    # S13: the operator online during the 4 joins gets the recurring notice exactly once.
    param([hashtable]$Scenario, [string]$OperatorOutput)
    $key = Get-RecurringKey
    if ($Scenario.Id -ne 'S13' -or $null -eq $key) { return @{ Problems = @(); Lines = @() } }
    $notices = @($OperatorOutput -split "`r?`n" | Where-Object {
            $_.Contains('playerdatarescue.operator.recurring') -and $_.Contains($key)
        })
    if ($notices.Count -ne 1) {
        return @{ Problems = @("S13 the operator OpBot got the recurring notice $($notices.Count) time(s), expected 1."); Lines = @() }
    }
    return @{ Problems = @(); Lines = @('S13 recurring notice received once by OpBot') }
}
