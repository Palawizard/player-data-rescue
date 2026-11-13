[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [string[]]$OnlyBands,
    # Check already built JARs found (recursively) under this folder instead of each
    # band's build/libs. CI uses it on the downloaded build artifacts.
    [string]$ArtifactsRoot
)

# Checks that every band and loader produced exactly one release JAR with its loader
# metadata, the core classes and the loader entrypoint, and that no metadata
# placeholder was left unexpanded. It also refuses any dev-only test mod or join bot file.

. (Join-Path $PSScriptRoot 'common.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem

$modProps = Get-ModProperties
$modId = $modProps['mod_id']
$modVersion = $modProps['mod_version']
$packagePath = ($modProps['mod_group'] -replace '\.', '/') + "/$modId"

if (-not $SkipBuild -and -not $ArtifactsRoot) {
    & (Join-Path $PSScriptRoot 'build-all-version-bands.ps1') -SkipSmoke -OnlyBands $OnlyBands
}

function Get-ReleaseJar {
    param([object]$LoaderConfig)
    $name = "$($LoaderConfig.artifact)-$modVersion.jar"
    $root = if ($ArtifactsRoot) { $ArtifactsRoot } else { Join-Path (Get-BandDirectory $LoaderConfig) 'build/libs' }
    $jars = @(Get-ChildItem -LiteralPath $root -Recurse -Filter $name -File -ErrorAction SilentlyContinue)
    if ($jars.Count -ne 1) { throw "Expected one $name under $root, found $($jars.Count)." }
    return $jars[0]
}

function Get-MetadataPath {
    param([string]$Loader)
    switch ($Loader) {
        'fabric' { 'fabric.mod.json' }
        'neoforge' { 'META-INF/neoforge.mods.toml' }
        default { 'META-INF/mods.toml' }
    }
}

function Get-EntrypointClass {
    param([string]$Loader)
    $suffix = switch ($Loader) { 'fabric' { 'Fabric' } 'neoforge' { 'NeoForge' } default { 'Forge' } }
    $className = (($modProps['mod_name'] -replace '[^A-Za-z]', '')) + $suffix
    "$packagePath/$Loader/$className.class"
}

$failures = 0
foreach ($band in (Get-VersionMatrix).bands) {
    if ($OnlyBands -and ($band.id -notin $OnlyBands)) { continue }
    foreach ($loaderName in (Get-LoaderNames $band)) {
        $label = "$($band.id)/$loaderName"
        try {
            $jar = Get-ReleaseJar $band.loaders.$loaderName
            $metadataPath = Get-MetadataPath $loaderName
            $required = @($metadataPath, "$packagePath/core/ModInfo.class", (Get-EntrypointClass $loaderName))
            $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
            try {
                $entries = @($zip.Entries | ForEach-Object { $_.FullName })
                foreach ($entry in $required) {
                    if ($entries -notcontains $entry) { throw "$($jar.Name) is missing $entry." }
                }
                $testFiles = @($entries | Where-Object { $_ -like "$packagePath/testmod/*" -or $_ -like 'pdrtest*' -or $_ -like '*JoinBot*' })
                if ($testFiles.Count -gt 0) { throw "$($jar.Name) contains dev-only test files: $($testFiles -join ', ')." }
                $reader = New-Object System.IO.StreamReader($zip.GetEntry($metadataPath).Open())
                try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
            } finally {
                $zip.Dispose()
            }
            if ($metadata -match '\$\{') { throw "$($jar.Name): $metadataPath still has unexpanded placeholders." }
            if (-not $metadata.Contains($modVersion)) { throw "$($jar.Name): $metadataPath does not declare version $modVersion." }
            Write-ResultLine -Status 'PASS' -Text "$label - $($jar.Name) ($($jar.Length) bytes)"
        } catch {
            $failures++
            Write-ResultLine -Status 'FAIL' -Text "$label - $($_.Exception.Message)"
        }
    }
}

if ($failures -gt 0) { throw "$failures release artifact check(s) failed." }
Write-Host 'All release artifacts verified.' -ForegroundColor Green
