[CmdletBinding()]
param(
    [string[]]$OnlyBands,
    [ValidateSet('forge', 'fabric', 'neoforge')]
    [string[]]$OnlyLoaders,
    [switch]$SkipCore,
    [switch]$SkipSmoke
)

# Builds and tests the core, then builds every band and loader listed in
# versions/version-matrix.json with its own Gradle wrapper. Without -SkipSmoke it then
# runs the production-JAR smoke test on the JARs it just built.

. (Join-Path $PSScriptRoot 'common.ps1')

$results = New-Object System.Collections.Generic.List[object]
function Add-Result {
    param([string]$Band, [string]$Loader, [string]$Status, [string]$Detail)
    $results.Add([pscustomobject]@{ Band = $Band; Loader = $Loader; Status = $Status; Detail = $Detail })
    Write-ResultLine -Status $Status -Text "$Band / $Loader - $Detail"
}

if (-not $SkipCore) {
    try {
        $core = (Get-VersionMatrix).core
        Invoke-Gradle -ProjectDirectory (Join-Path $ProjectRoot $core.path) -GradleJava $core.gradleJava -Arguments @('clean', 'build')
        Add-Result -Band 'core' -Loader '-' -Status 'PASS' -Detail 'unit tests and formatting'
    } catch {
        Add-Result -Band 'core' -Loader '-' -Status 'FAIL' -Detail $_.Exception.Message
    }
}

foreach ($band in (Get-VersionMatrix).bands) {
    if ($OnlyBands -and ($band.id -notin $OnlyBands)) { continue }
    foreach ($loaderName in (Get-LoaderNames $band)) {
        if ($OnlyLoaders -and ($loaderName -notin $OnlyLoaders)) { continue }
        $loader = $band.loaders.$loaderName
        try {
            $props = Get-BandProperties $loader
            if ($props['minecraft_version'] -ne $band.compileTarget) {
                throw "gradle.properties compiles $($props['minecraft_version']), the matrix says $($band.compileTarget)."
            }
            if ([int]$props['java_release'] -ne [int]$band.java) {
                throw "gradle.properties uses Java $($props['java_release']), the matrix says $($band.java)."
            }
            if ($props['minecraft_version_range'] -notin @($band.declaredRange, ($band.declaredRange -replace '^\[(.*)\]$', '$1'))) {
                throw "gradle.properties declares $($props['minecraft_version_range']), the matrix says $($band.declaredRange)."
            }
            Invoke-Gradle -ProjectDirectory (Get-BandDirectory $loader) -GradleJava $loader.gradleJava -Arguments @('clean', 'build')
            Add-Result -Band $band.id -Loader $loaderName -Status 'PASS' -Detail $loader.artifact
        } catch {
            Add-Result -Band $band.id -Loader $loaderName -Status 'FAIL' -Detail $_.Exception.Message
        }
    }
}

if (-not $SkipSmoke) {
    $smokeArgs = @{ SkipBuild = $true }
    if ($OnlyBands) { $smokeArgs.OnlyBands = $OnlyBands }
    if ($OnlyLoaders) { $smokeArgs.OnlyLoaders = $OnlyLoaders }
    try {
        & (Join-Path $PSScriptRoot 'prod-smoke-all.ps1') @smokeArgs
        Add-Result -Band 'all' -Loader 'smoke' -Status 'PASS' -Detail 'real dedicated servers'
    } catch {
        Add-Result -Band 'all' -Loader 'smoke' -Status 'FAIL' -Detail $_.Exception.Message
    }
}

Write-Host ''
$results | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
$failures = @($results | Where-Object Status -eq 'FAIL')
if ($failures.Count -gt 0) { throw "$($failures.Count) build step(s) failed." }
Write-Host 'All builds passed.' -ForegroundColor Green
