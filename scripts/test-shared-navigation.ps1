param([string]$JavaHome = 'C:\Program Files\Java\jdk-17')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'gradlew.bat') :navi_app_shared:testDebugUnitTest --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Shared Kotlin navigation tests failed.' }
    } finally { Pop-Location }
} finally { $env:JAVA_HOME = $previousJava }
