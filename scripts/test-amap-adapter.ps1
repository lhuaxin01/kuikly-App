param([string]$DevEcoHome = 'C:\Program Files\Huawei\DevEco Studio')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$node = Join-Path $DevEcoHome 'tools\node\node.exe'
$typescript = Join-Path $DevEcoHome 'tools\hvigor\hvigor\node_modules\typescript'
foreach ($tool in @($node, (Join-Path $typescript 'lib\typescript.js'))) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing build tool: $tool" }
}
# Execute the actual ArkTS adapter with SDK boundary mocks. No API key file,
# network service, GPS, or connected device is used by these regression tests.
& $node (Join-Path $projectRoot 'tests\amap-navigation-adapter.test.cjs') $typescript
if ($LASTEXITCODE -ne 0) { throw 'AMap navigation adapter tests failed.' }
