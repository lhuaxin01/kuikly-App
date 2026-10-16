param([string]$DevEcoHome = 'C:\Program Files\Huawei\DevEco Studio')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$node = Join-Path $DevEcoHome 'tools\node\node.exe'
$tsc = Join-Path $DevEcoHome 'tools\hvigor\hvigor\node_modules\typescript\bin\tsc'
foreach ($tool in @($node, $tsc)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing build tool: $tool" }
}
$domain = Join-Path $projectRoot 'ohosApp\entry\src\main\ets\navigation\domain'
$output = Join-Path $projectRoot 'artifacts\navigation-tests'
$sources = Get-ChildItem -LiteralPath $domain -Filter '*.ts' | ForEach-Object { $_.FullName }
& $node $tsc --target ES2021 --module commonjs --strict --noEmitOnError --outDir $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Navigation domain type checking failed.' }
# Direct execution uses node:test without spawning another process.
& $node (Join-Path $projectRoot 'tests\navigation-controller.test.cjs')
if ($LASTEXITCODE -ne 0) { throw 'Navigation controller tests failed.' }
