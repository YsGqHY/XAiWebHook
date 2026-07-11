---
skill: explore-develop
date: 2026-07-11 16:22
project: E:\Desktop\IDEA\XAiWebHook
scope: outgoing-command-cooldown
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：outgoing-command-cooldown

## 调用背景

- 用户目标：为 outgoing 指令触发增加普通用户个人冷却、管理员个人冷却和路由全局冷却，并允许获授权管理员绕过。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：outgoing 路由配置解析、mirai 群/好友事件适配、权限判断、动作执行前限流、用户反馈、热重载、默认配置、完整示例、README 和测试。
- 明确约束：沿用 YAML、mirai-console 2.16.0 权限体系和现有路由匹配架构；不新增依赖；保护工作区已有网页截图与 CLI Bridge 改动。

## 项目快照

- 技术栈：Kotlin 1.9.22、JVM 11 目标、mirai-console 2.16.0、Gradle 7.3.3、SnakeYAML、Ktor、Playwright。
- 运行入口：`./gradlew.bat test`、`./gradlew.bat buildPlugin`；浏览器集成测试由 `XAI_WEBHOOK_BROWSER_IT=true` 开启。
- 关键模块：`WebHookConfig.kt`、`WebHookEventListener.kt`、`OutgoingRouteProcessor.kt`、`Contexts.kt`、`TemplateEngine.kt`、`XAiWebHook.kt`、`XAiWebHookCommand.kt`。
- 初始风险：工作区已有大量未提交截图功能改动；原 outgoing 监听器在匹配后直接执行动作，没有可插入的原子策略层；默认 Java 25 无法启动 Gradle 7.3.3。

## 项目文档与提示词需求

- 现有文档设计：README 覆盖安装、配置、incoming/outgoing、动作、权限与排障；默认 YAML 提供逐项中文注释；`examples/` 提供可运行完整配置；`docs/explore-develop/` 保存决策记录。
- 文档缺口：原文档没有 outgoing 冷却字段、管理员判定、全局作用域、绕过权限、提示变量、失败后计时和 reload 清理行为说明。
- 现有提示词资产：未发现 AI 系统提示词、Agent 提示词或生成式模型调用；YAML 中的 `message` 是确定性模板，不属于 AI 提示词。
- 提示词需求：本功能不需要 AI 提示词；用户可见文本使用现有 `${...}` 表达式引擎渲染。
- 提示词模板草案：不适用。冷却提示采用确定性模板，例如 `指令冷却中，请在 ${cooldown.remainingSeconds} 秒后重试。`。
- 评估样例：成功样例为同一路由首次触发立即执行；边界样例为并发 32 次相同触发仅 1 次执行，其余渲染剩余时间并被阻止。
- 完善方案：新增 `cooldown` 只读模板根变量，并在 README 固化字段、作用域和权限规则；后续新增变量时同步解析器、默认 YAML、README 与解析测试。

## 真实用户体验假设

- 目标用户：在群聊或好友会话中触发截图、查询、HTTP 转发等 outgoing 指令的普通成员、群管理员、插件管理员。
- 核心任务：发送一次指令获得结果，同时避免重复点击、并发消息或多人短时间触发导致外部接口、浏览器和机器人资源被滥用。
- 成功标准：首次合法触发立即执行；冷却中的请求不执行动作并获得准确剩余时间；管理员使用独立时长；获授权管理员可稳定绕过；reload 后新配置立即生效。
- 易失败点：只在动作结束后计时会放过并发请求；个人与全局状态非原子会产生竞态；把群管理员角色等同于插件权限会越权；绕过请求若写入全局状态会反向限制普通用户；空提示被默认值覆盖会导致无法静默。

## 探索证据

- 查看内容：outgoing 事件监听与匹配链路、配置模型、模板引擎、命令 reload、插件生命周期、默认配置、完整 Geek2Api 示例和现有测试。
- 运行观察：本地 mirai-console 2.16.0 源码确认 `ExactMember`、`ExactFriend`、`PermissionService.hasPermission` 和 `MemberPermission.isOperator` 的权限继承与群角色 API。
- 关键证据：原 `WebHookEventListener.dispatch` 先筛选所有匹配路由，再直接调用 `WebHookActionExecutor.execute`；不存在动作前原子检查点，也没有管理员角色或权限主体信息。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | outgoing 指令没有任何个人或全局冷却 | 重复消息可并发启动昂贵截图或外部请求 | 原监听器直接执行动作 | fixed |
| ED-002 | P1 | 冷却若非原子检查并占用会被并发穿透 | 同一时刻多个请求全部通过 | 事件可由并发协程分发 | fixed |
| ED-003 | P1 | 管理员角色与插件权限缺少独立模型 | 无法提供管理员时长和安全绕过 | 事件上下文只有 senderId | fixed |
| ED-004 | P2 | 冷却拦截缺少可配置用户反馈 | 用户无法判断何时重试 | 原配置无反馈字段 | fixed |
| ED-005 | P2 | 空 `message` 若按普通字符串默认解析会恢复默认提示 | 管理员无法配置静默拦截 | 解析器 `stringOrNull` 会丢弃空串 | fixed |

## 本次预开发改动

- 改动摘要：新增路由级 `cooldown` 配置模型、线程安全冷却跟踪器、可测试的 outgoing 路由处理器、管理员与权限主体解析、`cooldown-bypass` 权限、冷却提示模板变量和生命周期清理。
- 用户可感知结果：普通成员与管理员拥有不同个人冷却；所有未绕过用户共享该路由的全局冷却；被阻止时可看到向上取整的剩余秒数；获授权管理员可连续执行且不污染普通用户冷却。
- 受影响区域：outgoing 群/好友事件、配置解析、模板上下文、插件启动/停用、`/xwebhook reload`、默认 YAML、Geek2Api 完整示例和 README。

## 验证结果

- 自动验证：`XAI_WEBHOOK_BROWSER_IT=true JAVA_HOME='C:\Program Files\Java\jdk-14' ./gradlew.bat clean test buildPlugin` 成功；41 项测试、0 跳过、0 失败、0 错误。
- 自动验证明细：新增 `OutgoingRouteProcessorTest` 9 项，覆盖默认/示例解析、缺省与负值处理、个人隔离、管理员时长、跨用户全局冷却、最长剩余时间、绕过不污染、清空状态、32 路并发原子性。
- 手动验证：核对 mirai 2.16.0 本地源码中的权限主体父子关系；确认群成员使用 `ExactMember`、好友使用 `ExactFriend`，两者均继承通用用户授权。
- 文档验证：默认 YAML 和完整示例均由实际 `WebHookConfig.parseConfig` 解析并断言相同冷却值；README 示例与字段名称一致。
- 提示词验证：不适用；确定性冷却模板已由处理器测试验证 `scope`、毫秒、秒和路由 ID 渲染。
- 未验证项：未在真实 mirai-console 群聊中执行 `/permission permit` 和发送消息；权限 API 与构造规则已通过 2.16.0 本地官方源码及编译验证。

## 残留风险

- 冷却状态只保存在内存，进程重启、插件停用或 `/xwebhook reload` 会按设计清空，不能用于跨重启配额控制。
- 冷却限制触发频率，不是任务互斥锁；当冷却时长短于实际动作耗时时，到期后仍可启动下一次动作。
- 路由 `id` 应保持唯一；当前冷却状态以路由 ID 隔离，重复 ID 会共享冷却状态，和现有“ID 为路由标识”的约定一致。

## 后续建议

- 若未来需要跨重启配额或多实例共享限流，可在不改变 YAML 表面的前提下增加可选持久化/分布式存储后端。
- 若长耗时动作需要严格单实例运行，应另加 `max_concurrency` 或 `single_flight`，不要复用时间冷却表达互斥语义。
