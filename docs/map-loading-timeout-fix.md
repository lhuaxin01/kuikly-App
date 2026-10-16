# 地图加载超时排查与修复

## 地图正常显示时的持续提示修复

真机反馈已确认：道路、地名正常显示，但持续出现“尚未收到底图加载完成回调，定位和算路仍可继续”。
该文案由应用自己的 30 秒计时器生成，不是 SDK 返回的地图失败结果。
之前将“未观察到加载完成回调”写入 `warning`，且地图复用时继续保留，导致正常地图也持续报警。

当前改为独立的内部 `mapContentStatus` 诊断状态：`unknown / waiting / loaded / unconfirmed`。
30 秒未收到回调只记录 `unconfirmed`，不进入页面警告、不打断导航、不重建地图。
仅 SDK 的真实 `onMapContentLoaded` 能将状态改为 `loaded`，不通过定位或算路成功伪造底图完成。
状态变化通过 `NaviMap` 的 `stage=map_content_observation` 日志记录，GPS 更新不会重复刷同一条诊断。
地图容器未创建、AMap 对象未就绪、SDK 显式失败、定位和算路错误仍按原有流程提示。

本次只需安装更新的 HAP，无需修改 Key 或 navi-claw。真机复测应等待超过 30 秒，并覆盖停止后再规划、
切换“首页/我的”以及键盘弹出/收起：正常显示的地图不应再出现该提示。

本轮验证：导航状态机 52 项、SDK 适配 23 项测试全部通过；页面布局静态检查通过。
完整 HAP 构建与签名成功，产物为 `artifacts/harmony/navi-harmony-navigation-signed.hap`。
回归覆盖无回调持续导航、真实告警保留、迟到/旧会话回调隔离及地图复用；尚未安装真机复测。

## 原因分析

旧提示“地图加载超时，请检查网络与鸿蒙 Key / AppID”来自应用 `NavigationController` 的 30 秒计时器，不是高德 SDK 返回的鉴权错误。它只能说明未收到应用期待的地图就绪事件，不能据此判定 Key 有误。

本次检查发现以下代码问题：

1. 适配层只在 `setOnMapLoadedListener` 回调中通知地图可用，把 AMap 对象就绪和底图加载完成混为一谈。回调迟到或未触发时，即使已经拿到地图对象，也会销毁整张地图，定位和路线规划均无法继续。
2. 加载监听在设置 UI、移动相机之后安装；应先安装监听，再进行地图操作，减少错过首次加载事件的风险。异步地图初始化发生异常时，原代码也未向页面转报具体失败阶段。
3. 控制器先发布加载状态，再安装地图计时器。如果发布状态期间同步收到地图就绪并触发自动定位，旧地图计时器可能覆盖新的定位计时器；初始化 Promise 返回前的地图就绪回调也会被丢弃。
4. SDK 的 `MapViewComponent.mapViewName` 是普通字段。快速停止并重开时，ArkUI 可能合并状态更新，原 `if (visible)` 分支不能保证创建新地图实例，而新会话只接受新名称的地图，最终超时。

这些是已确认的代码缺陷和可复现的时序风险。没有本次故障的 SDK 鉴权日志，尚不能排除网络、签名 AppID 不匹配等外部原因。

## 本次修改

- 在原生适配层分开发出 `onMapViewCreated`（收到视图）、`onMapReady`（AMap 对象与操作监听就绪）、`onMapContentLoaded`（SDK 报告底图加载完成）。先装加载监听，再操作相机；Key 在 SDK 初始化之前设置。
- 地图对象与初始化流程均完成后才允许定位。控制器暂存早到回调，先安装计时器再发布状态，避免同步回调覆盖后续阶段。
- 地图视图或 AMap 对象一直未创建仍会明确报错并释放资源。底图加载回调超时只更新内部诊断状态，保留可用地图、定位和路线流程；迟到回调确认该状态，其他 SDK/GPS 警告保留。SDK 显式失败仍正常报错。
- 用 `mapViewName` 作为 ArkUI `ForEach` 的键，保证重新加载时重建原生地图组件。
- 新增 `NaviMap` 日志，记录初始化、地图视图创建、地图对象就绪、底图完成、释放阶段与耗时。常规阶段日志不输出 Key、Token 或坐标；异步初始化异常会转报页面并遮盖配置中的 Key。

本次不修改 navi-claw：上述问题发生在设备侧高德地图的初始化与显示流程，和登录、`/ws` 频道绑定无关。

## 验证结果

导航控制器 35 项回归测试通过，其中新增 10 项覆盖同步/提前就绪、定位计时器不被覆盖、底图延迟和告警恢复、地图容器/AMap 缺失、停止重开后旧回调隔离。完整 Kotlin Native、ArkTS 与签名 HAP 编译通过，页面结构和 HAP 资源静态检查通过。本轮没有在真机复现并验证该故障，不能以编译通过代替设备验收。

## 真机验收与故障定位

重新构建并安装 `artifacts/harmony/navi-harmony-navigation-signed.hap`。检查首次进入、停止后重新加载、快速重试、切后台再回来后手动重新加载。地图可用后确认能够定位和规划路线。弱网下底图延迟不应直接导致已就绪地图被销毁。

查看本应用新增的地图阶段日志：

```powershell
& 'C:\Program Files\Huawei\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe' shell hilog -x -T NaviMap
```

| 最后出现的阶段 | 排查方向 |
| --- | --- |
| `sdk_initialized` | 地图原生组件未创建，检查页面挂载与视图尺寸；此时不能直接归因于 Key |
| `map_view_created` | `onCreate/getMapAsync` 未正常完成，检查地图 SDK 与原生库日志 |
| `map_controller_ready` | 地图对象已可用；若底图仍空白，检查网络和高德鉴权/资源加载日志 |
| `map_content_loaded` | SDK 已报告底图加载；后续定位、算路失败应按对应阶段排查 |
| `map_content_observation`，`status=unconfirmed` | 尚未观察到完成回调，仅供诊断；正常地图无需因此重试或更换 Key |

阶段日志按同一个 `view` 名称关联；内容完成与地图对象就绪回调可能紧接发生，顺序以 SDK 回调为准。

如果 SDK 日志明确显示鉴权失败，再核对高德控制台所绑定的 HarmonyOS AppID 与当前安装包运行时 `bundleInfo.signatureInfo.appId`。AppID 包含签名信息，仅包名一致不够；真机和云真机签名发生变化时，需要使用对应 AppID 的 Key。当前配置文件为 `ohosApp/entry/src/main/resources/rawfile/navigation_config.json`；若存在 `navigation.local.json`，应用会优先读取后者。

参考高德官方：[HarmonyOS 地图创建流程](https://lbs.amap.com/api/harmonyosnext-map3d-sdk/guide/create-map/show-map)、[鸿蒙 Key 与真机/云真机 AppID 说明](https://developer.amap.com/api/harmonyosnext-location-sdk/guide/get-key)。使用的 SDK API 还对照了本工程安装的 `@amap/amap_lbs_navi` 声明文件，未升级依赖。
