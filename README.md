# Navi App

导航专用 Kuikly 应用。以 MapTemplate 的业务规则和交互流程为参考，Kotlin 负责共享业务和页面，鸿蒙负责地图、定位、导航 SDK 与资源生命周期。

## 工程入口

- `navi_app_shared/src/commonMain/kotlin/com/example/navi_app/pages/NavigationPage.kt`：唯一业务页面，页名 `navigation`。
- `navi_app_shared/src/commonMain/kotlin/com/example/navi_app/navigation/`：地点、路线草稿、协议就绪判断、页面状态和平台端口。
- `ohosApp/entry/src/main/ets/pages/NavigationHostPage.ets`：鸿蒙页面宿主。
- `ohosApp/entry/src/main/ets/navigation/platform/HarmonyNavigationSession.ets`：页面会话，通过 Kuikly 自定义模块和自定义视图连接共享层与 SDK。
- `androidApp`：保留 Kuikly Android 宿主与通用渲染适配器；尚未接入 Android 地图导航，页面会明确提示。

共享业务不得引用 Android、高德或鸿蒙类。新增平台实现 `NavigationPort` 对应的命令/事件和 `KRNavigationMapView`，复用已有业务规则。平台执行状态以 SDK 回调为准，页面不能自行制造定位、算路成功或导航进度。

## 构建与测试

在本目录打开 PowerShell：

```powershell
.\scripts\test-navigation.ps1
.\scripts\test-amap-adapter.ps1
.\scripts\test-navigation-agent-bridge.ps1
.\scripts\test-shared-navigation.ps1
.\scripts\verify-navigation-layout.ps1
.\scripts\build-harmony.ps1
```

构建需要 DevEco Studio 和 **JDK 17**。脚本默认路径分别是 `C:\Program Files\Huawei\DevEco Studio`、`C:\Program Files\Java\jdk-17`；可通过 `-DevEcoHome`、`-JavaHome` 覆盖。首次安装鸿蒙依赖可加 `-InstallDependencies`。新版 DevEco 内置 Java 25 与本工程 Gradle 8.5 不兼容，不能直接用于 Gradle。

构建脚本先生成最新 Kotlin Native 共享库，再执行 Hvigor，并检查签名 HAP 内容，成功后导出到 `artifacts/harmony/navi-harmony-navigation-signed.hap`。失败时不会用旧 HAP 作为成功结果。

`test-shared-offline.ps1` 可使用本机已有的精确版本 JVM 依赖，离线编译共享页面/桥接并执行业务测试；它不替代 Kotlin Native 和鸿蒙编译。

## 当前阶段

2026-10-13 导航交互优化：实时导航使用街道级镜头跟随当前位置和可靠的行进方向，静止时保持方向稳定，路线全览北向上；支持“路线全览/跟随当前位置”切换。“退出导航”和首次系统返回保留当前路线，可重新选择真实/模拟导航。目的地解析、定位、算路、启动导航期间在输入栏位置显示一行进度和取消入口，保留地图浏览并阻止重复提交。详见 [导航跟随、返回预览与加载状态](docs/guidance-preview-loading.md)。

底图回调提示修复：地图正常显示时，不再因 30 秒内未收到完成回调而持续告警；回调状态保留在内部诊断和 `NaviMap` 日志，真实 SDK/定位/算路错误仍显示。详见 [底图提示原因与复测](docs/map-loading-timeout-fix.md)。

交互优化：自动加载地图，停止/到达后保留地图，定位授权独立处理；移除鸿蒙评分界面，并通过 `supports_feedback=false` 与后端协商；键盘弹出时宿主缩小可视区域，输入框随之上移。详见 [地图常显与输入优化](docs/navigation-interaction-polish.md)。

文本目的地修复：复用 MapTemplate 的出行方式识别、导航中修改任务和候选确认流程；启动不再把示例地点当作真实终点，新目的地未确认时禁止启动旧路线。需要同步部署 navi-claw 的候选索引及确定性选择修复。详见 [文本目的地识别与复测](docs/destination-input-fix.md)。

算路错误码 24：增加平台鉴权错误解释、运行包配置诊断、SDK 详细错误脱敏日志及算路失败恢复。详见 [错误码 24 排查与复测](docs/route-error-24.md)。

地图加载超时修复：区分地图对象就绪与底图加载，修正回调/计时器顺序及重试时原生地图重建，新增阶段诊断。详见 [超时原因与真机排查](docs/map-loading-timeout-fix.md)。

2026-09-28：补齐自动定位、定位后继续算路、预览切换出行方式自动重算；会话详情改为 MapTemplate 左右气泡和滚动展示，新增共享会话解析与绑定协调层。详见 [本次修改与真机验收](docs/location-route-session-update.md)。

2026-09-20：MapTemplate 页面壳已迁移到 Kuikly（底部搜索栏、语音/发送、出行方式、会话浮层、登录/注册、首页/我的导航）；28 项共享 Kotlin 页面/协议/Agent 测试和 21 项导航状态机测试通过；完整鸿蒙构建及签名成功。安装包为 `artifacts/harmony/navi-harmony-navigation-signed.hap`，同目录附 SHA-256 校验文件。

已实现地图背景叠加的搜索栏、语音入口、发送按钮、驾车/步行/骑行选择、导航底部面板、首页/我的 Tab、登录/注册表单、会话历史和候选地点；共享状态由 `NavigationStore` 管理。鸿蒙端与 MapTemplate 保持同一连接分工：登录/注册使用 HTTP `/auth/*`，会话列表、历史和创建使用带 Bearer Token 的 HTTP `/rpc`，导航提交使用 WebSocket `connect.challenge → connect → channel.attach → navigation.submit`。鸿蒙不再展示或提交评分，底层保留反馈协议兼容能力。鸿蒙配置以 `navigation_config.json` 的 `naviClawWebSocketUrl` 为准，地址应填写完整的 `wss://<navi-claw-host>/ws`（本地开发可使用 `ws://.../ws`）；模块会从该 WebSocket 地址反推出 HTTP 根地址。

鸿蒙 Key 的填写位置是 `ohosApp/entry/src/main/resources/rawfile/navigation_config.json`，不要把真实 Key 写入文档或日志。构建不依赖 Key；真实地图服务和导航验收需要匹配的 Key、签名和远程设备定位。

架构、MapTemplate 复用映射、桥接约定、清理记录及远程测试步骤见 [迁移说明](docs/harmony-navigation-milestone.md)。
