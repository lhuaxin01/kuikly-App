# 导航专用 Kuikly：MapTemplate 复用与鸿蒙迁移

## 本次落地范围

入口从原生 `NavigationDemo` 和模板 `router` 页切换为 Kuikly `navigation` 页。共享 Kotlin 提供导航草稿、业务校验、协议解释和页面；地图、权限、定位、算路、语音播报和导航监听留在鸿蒙。

第一阶段提供：同意并加载地图 → 获取当前位置 → 默认终点或地图长按选终点 → 规划驾车路线 → 预览 → 开始真实/模拟导航 → SDK 进度 → 停止并释放。地图可长按添加最多 3 个途经点，导航中可切换路线全览与位置跟随。

本次复用的是 MapTemplate 的业务规则和交互流程，没有将依赖 Android View/Fragment、LiveData、LatLng、AMapNavi 的代码直接复制到 commonMain。没有修改参考项目 MapTemplate 或 navi-claw。

## 分层与归属

```text
NavigationPage（Kuikly Kotlin 页面）
  ↓ 用户动作                   ↑ 不可变页面状态
NavigationStore + NavigationDraft + NavigationResponseCoordinator
  ↓ NavigationPort             ↑ RuntimeSnapshot / GeoPoint / Environment
NavigationModule（Kotlin 自定义模块，JSON 边界）
  ↕ KRNavigationModule（ArkTS）
HarmonyNavigationSession（每个页面一个会话）
  ├─ NavigationController（执行状态、超时、旧回调隔离）
  ├─ AmapNavigationEngine（地图、定位、算路、导航 SDK）
  └─ KRNavigationMapView（Kuikly 自定义原生地图视图）
```

| 目录/文件 | 职责 |
| --- | --- |
| `navi_app_shared/.../navigation/model` | 无平台依赖的坐标、地点、路线、进度、能力、执行状态 DTO |
| `navigation/domain/NavigationDraft.kt` | 终点/途经点草稿、坐标校验、去重、平台能力校验 |
| `navigation/protocol` | navi-claw 字段解析、完整性/反馈规则、候选组顺序、响应到业务动作 |
| `navigation/agent` | 与 MapTemplate/navi-claw 对齐的文本、候选确认、反馈请求和会话 Transport 契约 |
| `navigation/presentation/NavigationStore.kt` | 草稿与执行快照组合、按钮门禁、MAP/ROUTE_PREVIEW/GUIDANCE 状态 |
| `bridge/NavigationModule.kt` | 命令编码、事件解码、订阅/退订；不持有 SDK 对象 |
| `pages/NavigationPage.kt` | 页面和交互；不独立推进 SDK 状态 |
| `ohosApp/.../platform/HarmonyNavigationSession.ets` | 页面会话，模块与地图视图注入同一实例 |
| `ohosApp/.../domain/NavigationController.ts` | 前台执行状态机、有效定位、超时、幂等停止/清理 |
| `ohosApp/.../platform/AmapNavigationEngine.ets` | 唯一导航 SDK 适配器，封装权限、地图、GPS、路线和监听 |

SDK 执行状态以鸿蒙控制器为准。Kotlin 不再建一套竞争的初始化/超时状态机，而是维护跨端可复用的业务状态。禁止把 Context、AMap 对象或鸿蒙视图传进共享层。原生桥接层只处理命令、快照、生命周期和平台能力。

## MapTemplate 复用映射

| MapTemplate 原实现 | 本工程对应实现 | 处理方式 |
| --- | --- | --- |
| `model/NavigationIntentResponse.java` | `NavigationResponse.kt`、`NavigationResponseCodec.kt` | 迁移任务就绪、反馈、候选组及协议字段规则 |
| `ui/home/NavigationResponseCoordinator.java` | `NavigationResponseCoordinator` | 迁移响应分类与顺序；完整任务立即生成草稿，评分不阻断执行 |
| `ui/home/NavigationDraftManager.java` | `NavigationDraft.kt` | 迁移终点/途经点、坐标去重、数量限制；SDK LatLng 换成 GeoPoint |
| `ui/home/MapUiState.java`、`MapIntent.java` | `NavigationUiState`、`NavigationStore` | 保留动作驱动状态与效果、地图/预览/导航三种模式 |
| `NavigationFlowController`、`AmapMapUiAdapter` | `NavigationPort` + 鸿蒙会话/SDK 适配 | 保留职责边界，使用鸿蒙 SDK 重新实现平台操作 |
| `NavigationFeatureGraph` | 页面构造并注入同一 HarmonyNavigationSession | 模块与地图组件共享会话，页面退出统一释放 |

已有两项协议修正继续保留：`task_complete=false` 或任一地点未确认/无有效坐标时禁止执行；`feedback_required` 与任务执行分开，不能因为等待评分而阻塞完整任务。缺失坐标保留 NaN，不会默认为 0。兼容旧网关缺少 `task_complete`，但仍严格校验全部地点及唯一终点；该字段存在但类型错误时按未完成处理。

本阶段共享协议模型覆盖任务地点、出行方式、候选组、回复、反馈及链路标识。Kuikly 页面已经采用地图背景 + overlay 结构，包含搜索输入、语音入口、发送、出行方式、底部导航、登录/注册、会话历史和反馈；候选面板、请求取消和会话关闭由共享层管理。当前支持实际定位起点；指定起点仍会明确拒绝，不会静默替换用户起点。

第二阶段已补充 `NavigationAgentRepository`、`NavigationAgentTransport` 和 `NavigationAgentRequestCodec`。鸿蒙 `KRNavigationAgentModule` 负责 `/auth/login`、`/auth/register`、Token preferences 持久化；会话列表、历史和创建复用 MapTemplate 的带 Bearer Token HTTP `/rpc`，导航请求再通过 WebSocket `connect.challenge`、`connect`、`channel.attach`、`navigation.submit`、`navigation.feedback` 完成。WebSocket 升级请求同时带 `Authorization` 和 `X-Navi-Device-Id`，频道参数与 MapTemplate 保持一致。鸿蒙只需在 `navigation_config.json` 填写 `naviClawWebSocketUrl`，例如 `wss://<navi-claw-host>/ws`；模块从该地址推导 HTTP 根地址。未配置时页面会显示可诊断错误。

## 桥接与生命周期约定

自定义模块名 `KRNavigationModule`，自定义视图名 `KRNavigationMapView`。

| 命令 | 参数/用途 |
| --- | --- |
| `subscribe` / `unsubscribe` | 持续事件回调；订阅立即收到环境和当前快照 |
| `open` | `consent: true`，检查配置、权限并初始化地图 |
| `locate` | 请求真实定位，不使用示例坐标代替当前位置 |
| `plan` | `destination`、`waypoints`、`travelMode`、`revision` |
| `start` | `simulation`、`revision`；共享层和原生层均校验路线版本 |
| `clearRoute` | 草稿变化后撤销路线、移除覆盖物 |
| `overview` / `follow` | 控制地图视角 |
| `stop` | 停止导航、GPS、语音，清理路线/地图/监听和 SDK |
| `privacy` | 打开服务隐私政策 |

地点为 `{name, latitude, longitude}`，全部使用 GCJ-02。事件为 JSON 字符串：`environment` 包含默认终点、Key 是否填写、签名 AppID 和途经点能力；`state` 包含执行快照与 `routeRevision`；`point` 为地图长按坐标。普通命令回执为 `{ok, message?}`，算路/启动是否成功仍以 SDK 快照为准。

修改终点/途经点会增加 `draftRevision`、清除原生路线。只有 `ROUTE_READY` 且 `routeRevision == draftRevision` 才允许启动，过期定位快照不能恢复旧路线的开始按钮。算路、启动、导航中禁止编辑草稿。

地图与导航由一个页面会话拥有。返回、切后台、页面销毁、停止、超时和到达均释放资源；前台恢复后需要重新加载。模块退订移除 Kotlin CallbackRef，地图销毁移除观察者。不同会话的旧回调不能改变新会话。当前不支持后台持续导航。

Release 混淆规则保留 JSON 合同字段，避免 Kotlin 与 ArkTS 的动态字段名不一致。

## 模板清理

已移除模板 RouterPage、图片基准页、模板 BasePager/BridgeModule/工具、ArkUI Index 和 NavigationDemo、KRMyView/KRMyModule/KRBridgeModule，以及 Android 示例分享/桥接模块。鸿蒙路由只注册 NavigationHostPage，共享代码只有一个 `@Page("navigation")`。

Gradle 不再包含 H5/小程序/iOS 目标及其前端插件；鸿蒙构建只加载共享模块。Android 保留编译宿主和通用渲染适配器，页面明确显示尚未接入 Android 地图 SDK。不能把通用框架接入代码当作示例业务一并删除。

备份：`artifacts/migration-backup/template-before-navigation-20260917-145233.zip`。待清理目录的 72 个文件已逐项核对备份覆盖；批量删除会先校验路径与备份 SHA-256，拒绝越界路径、链接或备份后被修改的文件。

**目录清理状态（2026-09-18）：** 已校验备份 SHA-256 并删除上述 72 个模板文件；H5/小程序/iOS/static_server、图片示例资源、鸿蒙示例测试和 mock 目录均已移除。同时移除废弃的代理配置、ohosTest 构建目标和示例测试依赖。保留现有导航领域与共享业务测试。

已用有效的 `navigation_icon.svg` 替换工程中三个空 PNG 图标，应用、Ability 和启动窗口统一引用导航图标。HAP 中仍可见高德 SDK 自带的 `icon.png` / `startIcon.png`，已通过哈希核对来源；这部分是第三方资源，不是工程模板残留。

## 配置、构建与验收

Key 位置：`ohosApp/entry/src/main/resources/rawfile/navigation_config.json`，按包名和签名填写本机高德 Key，不把真实 Key 写入文档。navi-claw 使用与 MapTemplate 相同的 WebSocket `/ws` 入口，在 `naviClawWebSocketUrl` 填写 `wss://<navi-claw-host>/ws`；登录和会话 HTTP 地址由模块从该地址推导。配置随 HAP 打包，修改后必须重编译；非法配置会报错，不会静默回退。

`fixedDestination` 是初始目的地，可改成远程设备所在城市的 GCJ-02 坐标；页面可通过长按地图修改终点。包名 `com.example.navi_app`，实际签名 AppID 可在页面“设置”中读取。Key 必须与运行环境匹配。若云测试重签，应根据实际签名核对。

PowerShell，在 `navi_app` 根目录执行：

```powershell
# 平台执行状态机：21 项测试
.\scripts\test-navigation.ps1

# 共享 Kotlin 页面、协议和 Agent：28 项测试，标准 Gradle Android/JVM 单测任务
.\scripts\test-shared-navigation.ps1

# 静态验收页面层级、唯一页面注册、模板清理和已生成 HAP 资源
.\scripts\verify-navigation-layout.ps1

# 本机缓存离线备选：同时编译全部共享页面、协议和 Agent 代码，并运行 28 项测试
.\scripts\test-shared-offline.ps1

# 重编译共享 .so，再构建和检查签名 HAP
.\scripts\build-harmony.ps1
# 需要安装鸿蒙依赖时：
.\scripts\build-harmony.ps1 -InstallDependencies
```

构建脚本支持 `-DevEcoHome` 和 `-JavaHome`，默认 DevEco 标准安装目录及 `C:\Program Files\Java\jdk-17`。标准共享测试脚本支持 `-JavaHome`。不要使用新版 DevEco 的 Java 25 运行 Gradle 8.5。构建需要依赖缓存可写及有效签名配置，脚本不创建身份、输出签名密码或上传应用。

成功产物：

- `ohosApp/entry/build/default/outputs/default/entry-default-signed.hap`
- `artifacts/harmony/navi-harmony-navigation-signed.hap`
- 同目录 `.hap.sha256` 校验文件

页面布局验收脚本只验证可审查的源码结构和打包内容，不伪造真机截图：

```powershell
.\scripts\verify-navigation-layout.ps1
```

它检查 MapTemplate 搜索/语音/发送、出行方式、首页/我的、登录/注册、会话/反馈、地图背景与 overlay 的源码结构，确认 KSP 只注册 `NavigationPage`，并检查 HAP 内的导航图标、配置及模板页面残留。像素间距、地图手势、系统字体和折叠屏适配仍需在 AppGallery Connect 真机/模拟器上验收。

导出前检查 HAP 包含 `modules.abc`、`libentry.so`、`libnavi_app_shared.so`、高德导航原生库及导航配置。仅运行 Hvigor 可能复用旧共享库，请使用完整脚本。构建失败时不会导出旧 HAP。

**本次验证（2026-09-20）：** 标准 Gradle/离线任务执行 28 项共享测试，导航领域 TypeScript 执行 21 项测试；静态布局脚本通过 MapTemplate 搜索栏、出行方式、会话浮层、底部导航、唯一页面注册、模板清理和 HAP 资源检查。Kotlin Native、CMake、ArkTS、资源打包、PackingCheck 和 SignHap 全部成功，已导出签名 HAP 并核对包内导航图标、原生库、配置及 SHA-256。真机视觉验收尚未执行，当前主机 `hdc list targets` 没有 USB 设备；地图 XComponent 的真实层级仍必须在 AppGallery Connect 云真机以点击命中和截图确认。

构建仍有第三方 SDK 类型声明、部分废弃 API、KSP 与 OHOS Kotlin 版本提示及 Android Gradle 插件兼容性警告，本次未关闭检查或屏蔽这些提示。未填写 Key、未执行真机导航，不将编译结果当作运行验收。

远程测试按以下顺序：

1. 上传本次成功构建的 ARM64 HAP，应进入“导航”页，不能出现 Kuikly 模板列表。
2. Key 留空时点击“同意并加载地图”，应明确提示缺少 Key。填写匹配 Key 后重新打包，再继续。
3. 同意服务说明，授权精确定位；地图加载后点击“当前位置”。定位精度需不超过 100 米、接收时间不超过 30 秒，否则等待或超时。
4. 使用默认终点或长按选点；添加/清除途经点后重新算路。预览出现真实距离和时长，尚未自动启动导航。
5. 选“开始导航”观察设备 GPS 驱动的 SDK 进度；远程设备不移动时可选“模拟导航”，界面必须明确显示模拟，不能将其算作真实 GPS 验收。
6. 规划后修改目的地，旧路线的开始按钮必须失效；重新算路成功后才恢复。
7. 验证路线全览/跟随、停止、重新加载、到达、切后台和返回；停止后地图卸载，进度计数不再增长，旧回调不能覆盖新会话。

## 后续开发顺序

1. 使用已生成的签名 HAP 进行云真机闭环验收，确认地图原生视图不遮挡顶部搜索栏、候选/路线面板和底部 Tab；实际地图服务需先填写匹配的鸿蒙 Key 并重新打包。
2. 在配置中填写 navi-claw HTTP/WS 地址，验证登录、Token 恢复、`channel.attach`、搜索、候选确认、反馈和会话历史。
3. 将当前位置、城市和精度注入 `navigation.submit.user_context`，并把语音入口接到鸿蒙语音识别后再提交文本。
4. 再扩展路线偏好、真正的多出行方式 SDK 适配和后台导航；逐项补充协议与真机回归。
