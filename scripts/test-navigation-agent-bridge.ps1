param([string]$DevEcoHome = 'C:\Program Files\Huawei\DevEco Studio')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$node = Join-Path $DevEcoHome 'tools\node\node.exe'
$typescript = Join-Path $DevEcoHome 'tools\hvigor\hvigor\node_modules\typescript'
& $node (Join-Path $projectRoot 'tests\navigation-agent-bridge.test.cjs') $typescript
if ($LASTEXITCODE -ne 0) { throw 'Navigation agent bridge tests failed.' }
