# 算路错误码 24：诊断与恢复

## 已确认的事实

高德 HarmonyOS 导航 SDK 定义 `24 = USERKEY_PLAT_NOMATCH`：请求使用的 Key 与绑定平台不符。它不是网络超时（2）、参数非法（18）、应用签名校验失败（22）或坐标超出服务范围（25）。错误码与算路策略值属于不同概念，修改策略不能绕过平台鉴权。

用户已确认高德控制台中该 Key 属于 HarmonyOS 平台。本次仅在本机内存中比较配置，没有输出真实 Key：

- 生效配置是 `navigation_config.json`，没有 `navigation.local.json` 覆盖。
- 当前鸿蒙 Key 与 MapTemplate 的 Android Key 不同。
- 本机导出 HAP 的生效 Key 与源码相同。
- 地图 `MapsInitializer.setApiKey` 与导航 `AMapNaviFactory.getAMapNaviInstance` 均传入同一个 `NavigationConfig.apiKey`，没有读取 navi-claw 的 Web 服务 Key。

这能排除本机的几类配置串用，不能证明测试设备装的是同一个 HAP，也不能验证高德控制台实际绑定和服务端鉴权状态。本轮没有拿到故障设备的 `errorDescription/errorDetail`，因此尚不能确定“已配置鸿蒙 Key 仍返回 24”的最终原因。

## 代码修改

1. 新增 `AmapRouteErrors`，分别解释常见算路错误。24 明确显示 SDK 返回的平台不匹配，并指向配置诊断入口；不再只显示数字或一律让用户检查网络。
2. 原代码的数字失败回调会立即触发 `engine.release()`，可能先于详细失败回调销毁监听，丢失排查所需的信息。现将算路失败独立为 `onRouteFailure`，数字回调最多等待 50ms，让详细回调优先；同一次失败只通知一次，晚到的详细错误仍记入脱敏日志。释放或新请求会取消等待。
3. 预览算路失败保留地图和真实位置，清理失败路线与等待状态，恢复到可重新规划阶段。不会自动反复重试鉴权错误；导航进行中的失败仍停止导航并释放资源。该行为参考 MapTemplate 的失败恢复方式。
4. “我的”页面新增可展开的“地图服务诊断”，未登录也可查看：实际配置来源、仅保留首尾的 Key 标识、集成 SDK 版本、运行包的完整 `signatureInfo.appId`。不会展示完整 Key，也不会把“字段名叫鸿蒙 Key”当作平台已验证。
5. `NaviRoute` 日志记录 SDK 版本、配置来源、Key 标识、出行方式、策略、途经点数量、错误码及脱敏后的 `errorDescription/errorDetail`。不主动记录起终点坐标；SDK 错误文本先遮盖 Key、Token 等凭据再写日志。
6. 驾车默认策略改用 SDK 推荐且 MapTemplate 也使用的 `DRIVING_MULTIPLE_ROUTES_DEFAULT`，仍选择首条返回路线用于预览和启动导航；步骑行显示模式在请求前设置。这是兼容完善，不是错误码 24 的鉴权修复。
7. HAP 验证脚本按与运行时一致的本地覆盖优先级比较配置，并检查打包 Key 与源码一致，避免更换 Key 后误用旧包。

本次没有修改用户 Key、SDK 版本或 navi-claw 后端，也不需要新增第二个导航 Key 字段。

## 本轮验证

共享 Kotlin 59 项、导航控制器 46 项、原生适配层 SDK 模拟测试 18 项通过。完整 Kotlin Native、ArkTS 和签名 HAP 构建成功，页面结构与 HAP 生效配置一致性检查通过。自动化测试验证本地参数、回调与错误恢复，不会访问高德鉴权服务，不能证明真机上的 24 已消失。

## 新包复测

1. 构建并安装本次 HAP，进入“我的 → 地图服务诊断”，将脱敏 Key 标识与控制台核对。确认来源是实际修改的配置文件。
2. 对照完整的“当前安装包 AppID”。高德要的是 `bundleInfo.signatureInfo.appId`（包名加签名信息），不是 AGC 的数字 App ID；若使用云真机重新签名，应以云真机运行包为准。AppID 不一致需要调整控制台绑定，但不能只凭 24 就断定是签名问题。
3. 获取真实位置，选择附近目的地，分别测试驾车、步行和骑行。失败时地图应继续保留；解决配置问题并重新安装后，再手动规划。
4. 若仍为 24，采集 `NaviRoute` 的 `code/description/detail` 和 SDK 版本。USB 调试可执行：

```powershell
& 'C:\Program Files\Huawei\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe' shell hilog -x -T NaviRoute
```

AppGallery Connect 远程测试可在测试日志中筛选 `NaviRoute`。如控制台平台、运行包 Key、AppID 都确认无误，应携带这些错误详情向高德排查导航服务鉴权或 SDK 兼容问题；不要改用 Web/Android Key，也不要通过修改 navi-claw 算路协议处理客户端 SDK 鉴权错误。

构建命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-harmony.ps1
```

产物：`artifacts/harmony/navi-harmony-navigation-signed.hap`。

## 依据

- [高德鸿蒙导航算路错误码](https://developer.amap.com/api/harmonyosnext-navi-sdk/guide/tools/errorcode)
- [高德鸿蒙 Key 与运行包 AppID](https://developer.amap.com/api/harmonyosnext-navi-sdk/guide/create-project/get-key)
- [驾车算路推荐策略](https://lbs.amap.com/api/harmonyosnext-navi-sdk/guide/route-plan/drive-route-plan)
- 本工程安装的 `@amap/amap_lbs_navi` 11.2.0 声明文件；MapTemplate `MapFragment.java` 的算路失败处理。
