[CmdletBinding()]
param(
    # Git revision range to check, for example "origin/dev..HEAD". Merge commits are skipped.
    [string]$Range,
    # Pull request title, checked with the same rules as a commit subject.
    [string]$Title
)

# Checks commit subjects (and optionally a pull request title) against the format in
# CONTRIBUTING.md: type(scope): description, 72 characters at most, lowercase start,
# no final period, and no co-author trailers or generated-by footers.

$ErrorActionPreference = 'Stop'
$types = 'feat|fix|perf|refactor|docs|test|build|ci|chore|style'
$subjectPattern = "^($types)(\([a-z0-9][a-z0-9.\-]*\))?!?: \S.*$"

function Test-Subject {
    param([string]$Subject)
    $problems = @()
    if ($Subject -cnotmatch $subjectPattern) { $problems += "does not match 'type(scope): description' (types: $($types -replace '\|', ', '))" }
    if ($Subject.Length -gt 72) { $problems += "is $($Subject.Length) characters long (72 at most)" }
    if ($Subject -cmatch '^[^:]+: [A-Z]') { $problems += 'description starts with an uppercase letter' }
    if ($Subject.EndsWith('.')) { $problems += 'ends with a period' }
    return $problems
}

$failures = 0

if ($Title) {
    foreach ($problem in (Test-Subject $Title)) {
        Write-Host "Pull request title '$Title' $problem." -ForegroundColor Red
        $failures++
    }
}

if ($Range) {
    $shas = @(git rev-list --no-merges $Range)
    if ($LASTEXITCODE -ne 0) { throw "git rev-list failed for '$Range'." }
    foreach ($sha in $shas) {
        $subject = (git log -1 --format=%s $sha)
        $body = (git log -1 --format=%B $sha) -join "`n"
        $problems = @(Test-Subject $subject)
        if ($body -match '(?im)^co-authored-by:') { $problems += 'has a Co-authored-by trailer' }
        if ($body -match '(?i)generated (with|by) ') { $problems += 'has a generated-by footer' }
        foreach ($problem in $problems) {
            Write-Host "$($sha.Substring(0, 8)) '$subject' $problem." -ForegroundColor Red
            $failures++
        }
    }
    Write-Host "Checked $($shas.Count) commit(s) in $Range."
}

if ($failures -gt 0) { throw "$failures commit message problem(s) found." }
Write-Host 'Commit messages follow the convention.' -ForegroundColor Green
