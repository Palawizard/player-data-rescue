# Helpers dot-sourced by the build, smoke and artifact scripts. Works on Windows
# PowerShell 5.1 and on PowerShell 7 (Windows, Linux, macOS).

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$OnWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows

function Get-VersionMatrix {
    Get-Content -LiteralPath (Join-Path $ProjectRoot 'versions/version-matrix.json') -Raw | ConvertFrom-Json
}

function Get-LoaderNames {
    param([object]$Band)
    @('forge', 'fabric', 'neoforge') | Where-Object { $Band.loaders.$_ }
}

function Read-Properties {
    param([string]$Path)
    $result = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#!=\s][^=]*?)\s*=(.*)$') { $result[$Matches[1]] = $Matches[2].Trim() }
    }
    return $result
}

function Get-ModProperties {
    Read-Properties (Join-Path $ProjectRoot 'gradle.properties')
}

function Get-BandDirectory {
    param([object]$LoaderConfig)
    Join-Path $ProjectRoot $LoaderConfig.path
}

function Get-BandProperties {
    param([object]$LoaderConfig)
    Read-Properties (Join-Path (Get-BandDirectory $LoaderConfig) 'gradle.properties')
}

function Get-JdkHome {
    # CI first (actions/setup-java exports JAVA_HOME_<v>_X64), then usual install folders.
    param([int]$Version, [switch]$Optional)
    foreach ($arch in 'X64', 'ARM64') {
        $ciHome = [Environment]::GetEnvironmentVariable("JAVA_HOME_${Version}_${arch}")
        if ($ciHome -and (Test-Path -LiteralPath $ciHome)) { return $ciHome }
    }
    $patterns = if ($OnWindows) {
        @("C:\Program Files\Eclipse Adoptium\jdk-$Version*", "C:\Program Files\Microsoft\jdk-$Version*", "C:\Program Files\Java\jdk-$Version*")
    } else {
        @("/usr/lib/jvm/java-$Version-openjdk*", "/usr/lib/jvm/temurin-$Version*", "/usr/lib/jvm/jdk-$Version*", "/Library/Java/JavaVirtualMachines/*-$Version*/Contents/Home")
    }
    foreach ($pattern in $patterns) {
        $found = Get-Item -Path $pattern -ErrorAction SilentlyContinue |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin') } |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    if ($Optional) { return $null }
    throw "No JDK $Version found. Install it or set JAVA_HOME_${Version}_X64."
}

function Get-JavaExe {
    param([int]$Version)
    $name = if ($OnWindows) { 'java.exe' } else { 'java' }
    Join-Path (Join-Path (Get-JdkHome -Version $Version) 'bin') $name
}

function Invoke-Gradle {
    # Gradle itself runs on Java 21 (Gradle 8.14 cannot run on Java 25); each band
    # compiles through its own toolchain, found through the installation paths.
    param([string]$ProjectDirectory, [string[]]$Arguments)
    $wrapper = if ($OnWindows) { Join-Path $ProjectDirectory 'gradlew.bat' } else { Join-Path $ProjectDirectory 'gradlew' }
    if (-not (Test-Path -LiteralPath $wrapper)) { throw "No Gradle wrapper in $ProjectDirectory." }
    $paths = @(17, 21, 25 | ForEach-Object { Get-JdkHome -Version $_ -Optional } | Where-Object { $_ })
    $previousJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = Get-JdkHome -Version 21
    Push-Location -LiteralPath $ProjectDirectory
    try {
        & $wrapper @Arguments '--no-daemon' "-Dorg.gradle.java.installations.paths=$($paths -join ',')"
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed in $ProjectDirectory (exit code $LASTEXITCODE)." }
    } finally {
        Pop-Location
        $env:JAVA_HOME = $previousJavaHome
    }
}

function Write-ResultLine {
    param([string]$Status, [string]$Text)
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host "[$Status] $Text" -ForegroundColor $color
}
