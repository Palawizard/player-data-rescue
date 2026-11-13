[CmdletBinding()]
param(
    [string[]]$OnlyBands,
    [ValidateSet('forge', 'fabric', 'neoforge')]
    [string[]]$OnlyLoaders,
    [switch]$SkipBuild,
    [switch]$Fresh
)

# Runs the production-JAR smoke test for every band and loader in the version matrix.
# Server installs are cached under build/prod-smoke. Passing combinations are recorded
# in results.json with the JAR hash, so an interrupted run resumes instead of
# restarting, and a rebuilt JAR is always tested again.

. (Join-Path $PSScriptRoot 'common.ps1')

$bandScript = Join-Path $PSScriptRoot 'prod-smoke.ps1'
$resultsPath = Join-Path $ProjectRoot 'build/prod-smoke/results.json'
$modVersion = (Get-ModProperties)['mod_version']

$prior = @{}
if ((Test-Path -LiteralPath $resultsPath) -and -not $Fresh) {
    foreach ($r in @(Get-Content -LiteralPath $resultsPath -Raw | ConvertFrom-Json)) {
        if ($r.Status -eq 'PASS' -and $r.JarHash) { $prior["$($r.Band)/$($r.Loader)"] = $r.JarHash }
    }
}

$results = New-Object System.Collections.Generic.List[object]
function Add-Result {
    param([string]$Band, [string]$Loader, [string]$Status, [string]$Detail, [string]$JarHash)
    $results.Add([pscustomobject]@{ Band = $Band; Loader = $Loader; Status = $Status; Detail = $Detail; JarHash = $JarHash })
    Write-ResultLine -Status $Status -Text "$Band / $Loader - $Detail"
    New-Item -ItemType Directory -Path (Split-Path -Parent $resultsPath) -Force | Out-Null
    ConvertTo-Json -InputObject $results.ToArray() | Set-Content -LiteralPath $resultsPath -Encoding utf8
}

function Get-JarHash {
    param([object]$LoaderConfig)
    $jar = Join-Path (Get-BandDirectory $LoaderConfig) "build/libs/$($LoaderConfig.artifact)-$modVersion.jar"
    if (Test-Path -LiteralPath $jar) { return (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash }
    return $null
}

foreach ($band in (Get-VersionMatrix).bands) {
    if ($OnlyBands -and ($band.id -notin $OnlyBands)) { continue }
    foreach ($loaderName in (Get-LoaderNames $band)) {
        if ($OnlyLoaders -and ($loaderName -notin $OnlyLoaders)) { continue }
        $loader = $band.loaders.$loaderName
        $key = "$($band.id)/$loaderName"

        if ($SkipBuild -and -not $Fresh) {
            $hash = Get-JarHash $loader
            if ($hash -and $prior[$key] -eq $hash) {
                Add-Result -Band $band.id -Loader $loaderName -Status 'PASS' -Detail 'same JAR passed in a previous run' -JarHash $hash
                continue
            }
        }

        $smokeArgs = @{ BandId = $band.id; Loader = $loaderName }
        if ($SkipBuild) { $smokeArgs.SkipBuild = $true }
        if ($Fresh) { $smokeArgs.Fresh = $true }
        try {
            & $bandScript @smokeArgs
            Add-Result -Band $band.id -Loader $loaderName -Status 'PASS' -Detail 'real dedicated server' -JarHash (Get-JarHash $loader)
        } catch {
            Add-Result -Band $band.id -Loader $loaderName -Status 'FAIL' -Detail $_.Exception.Message
        }
    }
}

Write-Host ''
$results | Select-Object Band, Loader, Status, Detail | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
$failures = @($results | Where-Object Status -eq 'FAIL')
if ($failures.Count -gt 0) { throw "$($failures.Count) production smoke test(s) failed." }
Write-Host 'All production smoke tests passed.' -ForegroundColor Green
