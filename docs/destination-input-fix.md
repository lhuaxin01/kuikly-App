# 文本目的地识别与路线应用

本次对照 MapTemplate 的 `MapFragment.submitNavigationIntent`、
`NaviClawNavigationRepository`、`NavigationResponseCoordinator` 和
`NavigationDraftUiCoordinator`，修复鸿蒙输入到算路之间的业务差异。
尚未收到本次真机失败的具体输入及错误终点；以下是代码中可复现的缺陷，
不能据此断言真机现场只存在这些原因。

## 问题与修复

| 环节 | 原行为 | 当前行为 |
| --- | --- | --- |
| 初始化 | 自动把配置中的示例终点填入可执行草稿 | 配置仅提供地图初始中心，目的地必须由文本结果或地图选择确认 |
| 文本方式 | 总把页面当前模式作为约束 | 复用 MapTemplate 最后有效出行词优先；忽略直接否定及常见“步行街、汽车站”等地点词 |
| 导航中修改 | 所有文本均为 `new_intent`，无当前任务 | 导航中使用 `modify_task + existing_task`，包含最新起点、现有途经点及终点；预览状态仍为新意图 |
| 目的地应用 | 算路/导航中拒绝新任务 | 有效新任务先停止并释放旧 SDK 会话，收到停止事件后重新定位、规划新路线；开始导航仍需用户操作 |
| 输入与路线 | 输入新目的地后，旧路线仍可开始 | 新请求、候选未确认或失败时禁止启动旧路线；确认后回填真实 POI 名称 |
| 后端候选 | 按搜索结果序号分配 stop，清空所有已知 POI | 按查询/POI 证据匹配实际 stop，保留已确认地点；无法唯一关联时要求补充 |
| 确认候选 | 交给模型重新决定 | 后端根据保存的 group/item ID 精确应用所选 POI，多组逐项确认 |

## 分层约定

- `NavigationTextIntent` 只识别出行方式偏好，完整输入原文仍交给 navi-claw；
  不在 App 内靠截取“去”之后的字符串、默认坐标或关键词猜测终点。
- `NavigationAgentRepository` 区分请求类型，`NavigationAgentRequestCodec`
  编码 `existing_task`；空标题/备注不发送，以满足后端非空字符串校验。
- `KRNavigationAgentModule` 传输上述语义，不再把修改任务重写为新意图。
- `NavigationStore` 只接收坐标有效且终点唯一的完整任务。候选标题显示正在选择的
  起点/途经点/目的地，确认候选时保留待确认任务的方式和当前位置。
- 原生层仍负责地图、定位与算路；停止后旧 SDK 代次的回调不能用于新路线。
- 本次没有扩展任意指定起点算路：鸿蒙实际导航起点仍使用设备实时定位。
  地点同名、城市不明确时仍应通过候选或补充文本确认，不能保证任意自然语言一次识别成功。

## 部署与验证

此修改涉及 App 和 navi-claw 两侧：

1. 在后端目录运行 `pnpm test:protocol`、`pnpm check`、`pnpm build`，
   更新服务器并重启实际提供 `/ws` 的进程。
2. 在 App 目录执行：

   ```powershell
   .\scripts\test-shared-offline.ps1
   .\scripts\test-navigation-agent-bridge.ps1
   .\scripts\test-navigation.ps1
   .\scripts\test-amap-adapter.ps1
   .\scripts\build-harmony.ps1
   .\scripts\verify-navigation-layout.ps1
   ```

3. 安装新的 `artifacts/harmony/navi-harmony-navigation-signed.hap`。

真机重点复测：

- 冷启动未输入目的地时，不应能够规划或启动示例路线。
- 输入完整城市与地点名，例如“去重庆北站”，检查回填名称、终点坐标和路线一致。
- 输入“步行去附近人民医院”，如出现候选，选择后仍应为步行，不能被旧的驾车按钮值覆盖。
- 输入“去汽车站”“去步行街”，不应仅因地点名含方式词而改变方式。
- 已在导航时输入“终点改成重庆大学”或“顺路找加油站”，检查修改请求保留当前路线语境；
  新结果确认后停止旧会话，重新定位、预览，再由用户开始导航。
- 起点已确定、终点有多个候选时，选择终点不得覆盖起点；多组候选逐项选择不得丢失上一组。
- 新输入识别失败或尚待确认时，不能点击“开始导航”启动旧终点；等待过程中点停止，迟到回复不应重新打开导航。

自动测试使用假的地点、模型/SDK 响应及临时目录，不代替真实 POI 服务、手机定位和视觉验收。

2026-10-13 本地验证：共享 Kotlin 测试 79 项、Agent 桥接测试 5 项、导航状态机测试
46 项、SDK 适配测试 18 项均通过；页面布局静态检查通过。navi-claw 协议测试
33 项、`pnpm check`、`pnpm build` 均通过。完整 Kotlin Native、ArkTS 编译及签名成功，
已输出 `artifacts/harmony/navi-harmony-navigation-signed.hap`。尚未部署服务器或进行真机复测。
