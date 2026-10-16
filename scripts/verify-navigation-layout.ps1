param(
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent),
    [string]$HapPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts\harmony\navi-harmony-navigation-signed.hap')
)
$ErrorActionPreference = 'Stop'

function Require-Text([string]$Path, [string]$Text) {
    if (-not (Select-String -LiteralPath $Path -Encoding UTF8 -SimpleMatch -Pattern $Text -Quiet)) {
        throw "布局验收失败：$Path 缺少 '$Text'"
    }
}

$page = Join-Path $ProjectRoot 'navi_app_shared\src\commonMain\kotlin\com\example\navi_app\pages\NavigationPage.kt'
$tokens = Join-Path $ProjectRoot 'navi_app_shared\src\commonMain\kotlin\com\example\navi_app\navigation\presentation\NavigationDesignTokens.kt'
$delegate = Join-Path $ProjectRoot 'ohosApp\entry\src\main\ets\kuikly\KuiklyViewDelegate.ets'
$agentModule = Join-Path $ProjectRoot 'ohosApp\entry\src\main\ets\kuikly\modules\KRNavigationAgentModule.ets'
$hostPage = Join-Path $ProjectRoot 'ohosApp\entry\src\main\ets\pages\NavigationHostPage.ets'
foreach ($path in @($page, $tokens, $delegate, $agentModule, $hostPage)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "布局验收失败：文件不存在 $path" }
}

$pageText = Get-Content -Raw -LiteralPath $page -Encoding UTF8
$required = @(
    'NavigationMap',
    'Input {',
    '语音',
    '"发送"',
    '"驾车"',
    '"步行"',
    '"骑行"',
    '"首页"',
    '"我的"',
    'AccountPresentation.primaryLabel',
    'AccountPresentation.switchLabel',
    'AccountPresentation.title',
    '"会话历史"',
    '"当前位置"',
    '"规划路线"',
    '"开始导航"',
    '"停止"',
    'ctx.state.candidateGroup',
    'NavigationDesignTokens.pageBackground',
    'absolutePosition(bottom = 86f, left = 16f, right = 16f)',
    'NavigationDesignTokens.searchPanel',
    'absolutePosition(bottom = 158f, left = 12f, right = 12f)',
    'ctx.state.runtime.message',
    'ctx.historyMessages(this)',
    'parent.Scroller {',
    'sessionOverlay',
    'NavigationDesignTokens.navigationBar',
    'vbind({ ctx.shell.session.items })',
    'positionAbsolute()',
    'zIndex(20, false)',
    'NavigationAgentModule.MODULE_NAME'
)
foreach ($text in $required) { Require-Text $page $text }

# ArkUI owns keyboard avoidance. Kuikly propagates the resized root viewport to
# its bottom-anchored controls; a second keyboard offset would move them twice.
Require-Text $hostPage 'setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE)'
$hostText = Get-Content -Raw -LiteralPath $hostPage -Encoding UTF8
if ($hostText -match 'expandSafeArea\([^)]*SafeAreaType\.KEYBOARD') {
    throw '布局验收失败：导航宿主不能扩展到输入法避让区'
}

$agentRequired = @('channel.attach', 'navigation.submit', 'navigation.feedback', 'sessions.list', 'sessions.history', 'sessionHttpRpc', 'naviClawWebSocketUrl', 'connect.challenge', 'X-Navi-Device-Id', 'Authorization', 'preferences', 'readDeviceId')
foreach ($text in $agentRequired) { Require-Text $agentModule $text }
$map = $pageText.IndexOf('NavigationMap {')
$overlay = $pageText.IndexOf('private fun homeOverlay')
$tabs = $pageText.IndexOf('private fun bottomTabs')
if ($map -lt 0 -or $overlay -le $map -or $tabs -le $overlay) {
    throw '布局验收失败：地图背景、首页 overlay 和底部 Tab 的声明顺序异常'
}

$entryCandidates = @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'navi_app_shared\build\generated\ksp') -Recurse -File -Filter 'KuiklyCoreEntry.kt' -ErrorAction SilentlyContinue)
foreach ($entry in $entryCandidates) {
    $entryText = Get-Content -Raw -LiteralPath $entry.FullName -Encoding UTF8
    if ($entryText -notmatch 'NavigationPage') { throw "页面注册验收失败：$($entry.FullName) 未注册 NavigationPage" }
    if ($entryText -match 'RouterPage|MyPage|NavigationDemo') { throw "页面注册验收失败：$($entry.FullName) 仍包含模板页面" }
}

$forbidden = @('h5App', 'miniApp', 'iosApp', 'static_server', 'ohosApp\entry\src\main\ets\pages\Index.ets')
foreach ($relative in $forbidden) {
    if (Test-Path -LiteralPath (Join-Path $ProjectRoot $relative)) { throw "模板清理验收失败：仍存在 $relative" }
}

if (Test-Path -LiteralPath $HapPath -PathType Leaf) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($HapPath)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName })
        foreach ($name in @('ets/modules.abc', 'resources/base/media/navigation_icon.svg', 'resources/rawfile/navigation_config.json')) {
            if ($names -notcontains $name) { throw "HAP 验收失败：缺少 $name" }
        }
        # Match the runtime's local override precedence; do not silently verify
        # the base file when the installed app will use a different Key.
        $configRoot = Join-Path $ProjectRoot 'ohosApp\entry\src\main\resources\rawfile'
        $localConfigExists = Test-Path -LiteralPath (Join-Path $configRoot 'navigation.local.json')
        $packagedLocalConfig = $archive.GetEntry('resources/rawfile/navigation.local.json')
        if ($localConfigExists -ne ($null -ne $packagedLocalConfig)) {
            throw 'HAP verification failed: local config override presence differs from source. Rebuild the HAP.'
        }
        $effectiveConfigName = if ($localConfigExists) { 'navigation.local.json' } else { 'navigation_config.json' }
        $sourceConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $configRoot $effectiveConfigName) | ConvertFrom-Json
        $packagedConfigEntry = $archive.GetEntry('resources/rawfile/' + $effectiveConfigName)
        $packagedConfigReader = [System.IO.StreamReader]::new($packagedConfigEntry.Open())
        try { $packagedConfig = $packagedConfigReader.ReadToEnd() | ConvertFrom-Json } finally { $packagedConfigReader.Dispose() }
        if ($sourceConfig.amapHarmonyApiKey -cne $packagedConfig.amapHarmonyApiKey) {
            throw 'HAP verification failed: the effective HarmonyOS Key differs from source. Rebuild the HAP.'
        }
        if ($sourceConfig.naviClawWebSocketUrl -ne $packagedConfig.naviClawWebSocketUrl) {
            throw "HAP 验收失败：内嵌 navi-claw 地址与源码配置不一致（source=$($sourceConfig.naviClawWebSocketUrl), package=$($packagedConfig.naviClawWebSocketUrl)）"
        }
        if (($names | Where-Object { $_ -match 'RouterPage|NavigationDemo|KRMyView|KRMyModule' }).Count -gt 0) {
            throw 'HAP 验收失败：包内仍包含模板页面或示例模块'
        }
    } finally { $archive.Dispose() }
}

Write-Output 'Navigation layout verification passed (MapTemplate shell controls, source order, page registry, cleanup and HAP assets).'
