---
skill: explore-develop
date: 2026-07-11 17:36
project: E:\Desktop\IDEA\XAiWebHook
scope: actions-single-flight
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Actions Single Flight

## 调用背景

- 用户目标：为一组 actions 增加开关，开启后上一轮任务未完成时不能触发下一轮动作。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：incoming endpoint、outgoing route、动作组并发状态、cooldown 交互、默认/完整示例和用户文档。
- 明确约束：保持现有配置兼容；不覆盖上一项浏览器会话缓存的未提交改动；使用项目现有 Kotlin/Gradle 结构，不新增依赖。

## 项目快照

- 技术栈：Kotlin 1.9.22、mirai-console 2.16.0、Ktor、kotlinx.coroutines、SnakeYAML、Gradle 7.3.3。
- 运行入口：`./gradlew.bat build`；本机需使用 JDK 14，JDK 25 与 Gradle 7.3.3 不兼容。
- 关键模块：`WebHookConfig.kt`、`WebHookServer.kt`、`OutgoingRouteProcessor.kt`、`WebHookActionExecutor.kt`、默认/完整 YAML 示例。
- 初始风险：工作区包含上一项浏览器 session cache 的未提交改动；现有 cooldown 在动作开始前按时间占用，无法表示“任务仍未完成”。

## 项目文档与提示词需求

- 现有文档设计：README 覆盖配置、incoming/outgoing、cooldown、截图和安全说明；CLI Bridge 文档覆盖授权状态机、限流和凭证风险；默认 YAML 通过逐项中文注释承担配置参考职责。
- 文档缺口：此前没有动作组运行中防重入语义，也没有说明 incoming 与多个 outgoing 入口如何共同保护同一截图任务。
- 现有提示词资产：未发现与本功能相关的 AI 系统提示词、Agent 模板或生成式输出协议；项目模板表达式不是 AI 提示词。
- 提示词需求：本功能不需要新增 AI 提示词。用户可见输出由 `single_flight.message` 模板渲染，只允许现有 `request.*` / `event.*` 变量。
- 提示词模板草案：不适用；采用固定失败协议和受限模板消息，而非生成式提示词。
- 评估样例：成功样例为首个触发执行、并发触发收到 busy、首个完成后第三次触发成功；边界样例为执行协程取消后租约释放，及未配置 single_flight 时保持原并发行为。
- 完善方案：README 与 CLI Bridge 文档新增配置、反馈、cooldown 顺序和风险说明；默认 YAML 为每个选项补全中文注释。

## 真实用户体验假设

- 目标用户：通过群聊、好友消息或 incoming HTTP 触发长耗时网页截图的机器人管理员及调用方。
- 核心任务：同一截图任务运行期间避免重复登录、重复截图和重复发送，同时让第二次触发者明确知道任务仍在执行。
- 成功标准：第一轮 actions 独占运行；同 key 的后续触发不排队、不执行动作；outgoing 获得聊天提示，incoming 获得 HTTP 409；任务结束后立即可再次触发。
- 易失败点：仅依赖 cooldown 时，任务执行时间超过冷却仍会重入；incoming、群聊和好友若按 route id 隔离，仍可能跨入口并发；异常或取消未释放会永久锁死。

## 探索证据

- 查看内容：动作执行器、incoming Ktor 处理、outgoing 路由处理、冷却跟踪器、配置解析、默认/完整 YAML、README、CLI Bridge 文档和现有测试。
- 运行观察：JDK 14 下定向执行 `OutgoingRouteProcessorTest`，随后执行完整 `./gradlew.bat --no-daemon build`。
- 关键证据：`WebHookActionExecutor.execute` 串行执行列表但没有跨触发互斥；`OutgoingCooldownTracker` 只记录到期时间且动作失败不退还；incoming 与 outgoing 之前直接调用动作执行器，没有共享运行状态。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 长耗时 actions 没有运行中互斥 | 冷却到期后可重复登录、截图、发送 | `WebHookServer.kt`、`OutgoingRouteProcessor.kt` | fixed |
| ED-002 | P1 | incoming、群聊、好友入口无法共享同一任务锁 | 不同入口仍可同时触发同一命名动作 | endpoint/route 仅持有各自动作列表 | fixed |
| ED-003 | P1 | 需要确保成功、失败、取消均释放占用 | 异常路径可能让后续任务永久不可用 | 动作执行边界缺少租约式 finally | fixed |
| ED-004 | P2 | outgoing 原逻辑会把协程取消当普通异常吞掉 | 插件停止或任务取消时协程语义不正确 | `runCatching` 包裹 suspend actionExecutor | fixed |
| ED-005 | P1 | 默认资源的 session cache 实际为 true，与既定默认关闭约束冲突 | 新安装会把敏感浏览器登录态落盘 | `src/main/resources/webhook_config.yml` 与测试断言 | fixed |

## 本次预开发改动

- 改动摘要：新增进程级原子单飞注册表和幂等租约；endpoint/route 新增 `single_flight.enabled/key/notify/message`；incoming 返回 409，outgoing 发送可模板化忙碌提示；相同 key 可跨入口共享；cooldown 被检查前先取得单飞锁，忙碌拦截不消耗冷却。
- 用户可感知结果：截图任务尚未完成时，重复指令不会启动下一轮动作；HTTP 调用方和聊天用户能获得明确反馈；任务完成或取消后可立即重试。
- 受影响区域：配置模型、incoming 服务、outgoing 路由处理、默认与完整配置、README、CLI Bridge 文档、路由测试。

## 验证结果

- 自动验证：JDK 14 下 `./gradlew.bat --no-daemon build` 成功；49 项测试全部通过，其中 Markdown 3 项、网页截图/会话缓存 33 项、outgoing/cooldown/single-flight 13 项，0 failure、0 error。
- 手动验证：通过受控协程测试让第一轮 action 挂起，确认第二轮不执行且渲染 busy 提示；释放后第三轮可执行；相同 key 在不同 fallback source 间互斥；取消后可重新获取。
- 文档验证：默认 YAML 和完整示例均由 SnakeYAML 测试解析；默认资源的 `session_cache_enabled` 已恢复为 false，完整示例保持 true；README 与 CLI Bridge 文档说明一致。
- 提示词验证：不涉及 AI 提示词；消息模板成功样例 `${event.senderName}:busy` 渲染为触发者对应文本，空消息/notify false 路径保持静默。
- 未验证项：未启动真实 mirai 与 Ktor 网络端点并发发送两次 HTTP 请求；incoming 使用与 outgoing 相同的共享注册表，编译和配置解析已覆盖，但网络层行为主要由代码审查验证。

## 残留风险

- 若底层 action 永久挂起且不响应取消，同一 single-flight key 会一直占用，直到协程结束或进程停止；这是“未完成不得再次触发”的直接语义。
- 单飞状态只在当前进程内有效，不跨多个插件进程或多机实例协调；多实例部署若需要全局互斥需外部锁。
- `single_flight.key` 区分大小写；配置不同 key 或修改 key 后会被视为不同任务组。

## 后续建议

- 若未来需要排队而不是拒绝，可另增显式 queue 模式和最大队列长度，不应改变 single-flight 当前的立即拒绝语义。
- 若需观测长期占用，可增加运行开始时间和状态命令，但应避免把请求体或敏感 key 内容写入普通日志。
