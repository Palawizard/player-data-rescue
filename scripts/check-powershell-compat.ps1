[CmdletBinding()]
param(
    # Folder whose .ps1 files are checked.
    [string]$Path = $PSScriptRoot
)

# The scripts must run on Windows PowerShell 5.1. This refuses the PowerShell 7 operators
# that 5.1 cannot parse (??, ??=, ?., ?[], the ternary ? :, && and ||) and
# ForEach-Object -Parallel. It reads the PowerShell tokens instead of searching text, so
# these characters inside strings, comments or regular expressions are not reported.
# Run it with PowerShell 7: on 5.1 those operators are already parse errors, reported too.

$ErrorActionPreference = 'Stop'
$forbiddenTokens = @{
    'QuestionQuestion'      = '??'
    'QuestionQuestionEquals' = '??='
    'QuestionDot'           = '?.'
    'QuestionLBracket'      = '?['
    'QuestionMark'          = 'ternary ? :'
    'AndAnd'                = '&&'
    'OrOr'                  = '||'
}

$problems = 0
$files = @(Get-ChildItem -LiteralPath $Path -Filter '*.ps1' -File -Recurse)
foreach ($file in $files) {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    foreach ($parseError in $errors) {
        Write-Host "$($file.Name):$($parseError.Extent.StartLineNumber): parse error: $($parseError.Message)" -ForegroundColor Red
        $problems++
    }
    foreach ($token in $tokens) {
        $kind = $token.Kind.ToString()
        if ($forbiddenTokens.ContainsKey($kind)) {
            Write-Host "$($file.Name):$($token.Extent.StartLineNumber): '$($forbiddenTokens[$kind])' needs PowerShell 7." -ForegroundColor Red
            $problems++
        } elseif ($kind -eq 'Parameter' -and $token.Text -match '^-Parallel$') {
            Write-Host "$($file.Name):$($token.Extent.StartLineNumber): ForEach-Object -Parallel needs PowerShell 7." -ForegroundColor Red
            $problems++
        }
    }
}

if ($problems -gt 0) { throw "$problems PowerShell 5.1 compatibility problem(s) in $Path." }
Write-Host "Checked $($files.Count) script(s): no PowerShell 7-only syntax." -ForegroundColor Green
