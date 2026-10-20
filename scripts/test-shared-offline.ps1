param(
    [string]$DevEcoHome = 'C:\Program Files\Huawei\DevEco Studio',
    [string]$DependencyCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1')
)
$ErrorActionPreference = 'Stop'
# Read existing JVM dependencies only; this diagnostic neither downloads nor updates Gradle caches.
# It type-checks the shared UI/bridge and runs business tests. It does not replace the OHOS native build.
$projectRoot = Split-Path $PSScriptRoot -Parent
$output = Join-Path $projectRoot 'artifacts\shared-offline-tests'
$java = Join-Path $DevEcoHome 'jbr\bin\java.exe'
function Find-Dependency([string]$relative, [string]$pattern = '*.jar') {
    $files = @(Get-ChildItem -LiteralPath (Join-Path $DependencyCache $relative) -Recurse -File -Filter $pattern |
        Where-Object { $_.Name -notmatch '-(sources|javadoc|all)\.' })
    if ($files.Count -ne 1) { throw "Expected one cached dependency: $relative / $pattern; found $($files.Count)" }
    return $files[0].FullName
}
$stdlib = Find-Dependency 'org.jetbrains.kotlin\kotlin-stdlib\2.1.21'
$annotations = Find-Dependency 'org.jetbrains\annotations\13.0'
$compiler = @(
    (Find-Dependency 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.21'),
    $stdlib,
    (Find-Dependency 'org.jetbrains.kotlin\kotlin-script-runtime\2.1.21'),
    (Find-Dependency 'org.jetbrains.kotlin\kotlin-reflect\1.6.10'),
    (Find-Dependency 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.8.0'),
    (Find-Dependency 'org.jetbrains.intellij.deps\trove4j\1.0.20200330'),
    $annotations
)
$aar = Find-Dependency 'com.tencent.kuikly-open\core-android\2.7.0-2.1.21' '*.aar'
New-Item -ItemType Directory -Force -Path $output | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($aar)
$core = Join-Path $output 'kuikly-core.jar'
try { [System.IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $core, $true) }
finally { $archive.Dispose() }
$dependencies = @(
    $stdlib, $annotations, $core,
    (Find-Dependency 'com.tencent.kuikly-open\core-annotations-jvm\2.7.0-2.1.21'),
    (Find-Dependency 'org.jetbrains.kotlin\kotlin-test\2.1.21'),
    (Find-Dependency 'org.jetbrains.kotlin\kotlin-test-junit\2.1.21'),
    (Find-Dependency 'junit\junit\4.13.2'),
    (Find-Dependency 'org.hamcrest\hamcrest-core\1.3')
)
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'navi_app_shared\src\commonMain'),
    (Join-Path $projectRoot 'navi_app_shared\src\commonTest') -Recurse -File -Filter '*.kt' | ForEach-Object { $_.FullName })
$compiled = Join-Path $output 'navigation-tests.jar'
& $java -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -classpath ($dependencies -join ';') -d $compiled @sources
if ($LASTEXITCODE -ne 0) { throw 'Shared Kotlin type-check failed.' }
& $java -cp ((@($compiled) + $dependencies) -join ';') org.junit.runner.JUnitCore `
    com.example.navi_app.navigation.NavigationBusinessTest `
    com.example.navi_app.navigation.NavigationAgentTest `
    com.example.navi_app.navigation.AccountPresentationTest `
    com.example.navi_app.navigation.SessionPresentationTest `
    com.example.navi_app.navigation.SessionCoordinatorTest `
    com.example.navi_app.navigation.VoiceInputTest `
    com.example.navi_app.navigation.NavigationVoiceLexiconTest
if ($LASTEXITCODE -ne 0) { throw 'Shared business tests failed.' }
