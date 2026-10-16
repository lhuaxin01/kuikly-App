param(
    [string]$DevEcoHome = 'C:\Program Files\Huawei\DevEco Studio',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-17',
    [switch]$InstallDependencies
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$harmonyRoot = Join-Path $projectRoot 'ohosApp'
$node = Join-Path $DevEcoHome 'tools\node\node.exe'
$hvigor = Join-Path $DevEcoHome 'tools\hvigor\bin\hvigorw.js'
$ohpm = Join-Path $DevEcoHome 'tools\ohpm\bin\ohpm.bat'
foreach ($tool in @($node, $hvigor, $ohpm, (Join-Path $JavaHome 'bin\java.exe'))) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing build tool: $tool" }
}

$previousSdk = $env:DEVECO_SDK_HOME
$previousJava = $env:JAVA_HOME
$previousPath = $env:Path
try {
    $env:DEVECO_SDK_HOME = Join-Path $DevEcoHome 'sdk'
    # Gradle 8.5 cannot run with the Java 25 bundled in recent DevEco versions.
    $env:JAVA_HOME = $JavaHome
    $env:Path = (Join-Path $JavaHome 'bin') + ';' + (Join-Path $DevEcoHome 'tools\node') + ';' + (Join-Path $DevEcoHome 'tools\ohpm\bin') + ';' + $previousPath
    # Hvigor alone may reuse the checked-in/native-cache library. Always rebuild the shared Kotlin code first.
    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'gradlew.bat') -c settings.ohos.gradle.kts :navi_app_shared:linkDebugSharedOhosArm64 --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Shared Kotlin build failed; HAP packaging has not started.' }
        $nativeOutput = Join-Path $projectRoot 'navi_app_shared\build\bin\ohosArm64\debugShared'
        $nativeLibrary = Join-Path $nativeOutput 'libnavi_app_shared.so'
        $nativeHeader = Join-Path $nativeOutput 'libnavi_app_shared_api.h'
        foreach ($file in @($nativeLibrary, $nativeHeader)) {
            if (-not (Test-Path -LiteralPath $file)) { throw "Shared native artifact missing: $file" }
        }
        Copy-Item -LiteralPath $nativeLibrary -Destination (Join-Path $harmonyRoot 'entry\libs\arm64-v8a\libnavi_app_shared.so') -Force
        Copy-Item -LiteralPath $nativeHeader -Destination (Join-Path $harmonyRoot 'entry\src\main\cpp\libnavi_app_shared_api.h') -Force
    } finally { Pop-Location }
    Push-Location $harmonyRoot
    try {
        if ($InstallDependencies) {
            & $ohpm install --all
            if ($LASTEXITCODE -ne 0) { throw 'OHPM dependency installation failed.' }
        }
        & $node $hvigor --mode module -p module=entry@default -p product=default assembleHap --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'HAP build failed; no artifact has been exported.' }

        $signedHap = Join-Path $harmonyRoot 'entry\build\default\outputs\default\entry-default-signed.hap'
        if (-not (Test-Path -LiteralPath $signedHap)) {
            throw 'Signed HAP missing. Configure a valid signing profile in DevEco Studio, then rebuild.'
        }
        # Check the actual output archive before exporting it; no fallback to an old artifact on build failure.
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($signedHap)
        try {
            $entries = @($archive.Entries | ForEach-Object { $_.FullName })
            foreach ($required in @('ets/modules.abc', 'libs/arm64-v8a/libAMapOpenNaviJNI.so',
                'libs/arm64-v8a/libentry.so', 'libs/arm64-v8a/libnavi_app_shared.so',
                'resources/rawfile/navigation_config.json')) {
                if ($entries -notcontains $required) { throw "HAP is missing required content: $required" }
            }
        } finally { $archive.Dispose() }

        $output = Join-Path $projectRoot 'artifacts\harmony'
        New-Item -ItemType Directory -Force -Path $output | Out-Null
        $artifact = Join-Path $output 'navi-harmony-navigation-signed.hap'
        Copy-Item -LiteralPath $signedHap -Destination $artifact -Force
        $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
        Set-Content -LiteralPath ($artifact + '.sha256') -Value ($hash + '  navi-harmony-navigation-signed.hap') -Encoding ascii
        Write-Output "HAP: $artifact"
        Write-Output "SHA256: $hash"
    } finally { Pop-Location }
} finally {
    $env:DEVECO_SDK_HOME = $previousSdk
    $env:JAVA_HOME = $previousJava
    $env:Path = $previousPath
}
