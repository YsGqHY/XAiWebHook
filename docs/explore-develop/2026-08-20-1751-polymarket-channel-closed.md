---
skill: explore-develop
date: 2026-08-20 17:51
project: E:\Desktop\IDEA\XAiWebHook
scope: polymarket-channel-closed
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：polymarket-channel-closed

## 调用背景

- 用户目标：修复 Polymarket 搜索经本机代理请求时出现 `ClosedReceiveChannelException: Channel was closed`，并避免增加多余调试信息。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：Polymarket Gamma/CLOB HTTP 请求、代理链路、客户端缓存与关闭、协程取消传播、网络回归测试和使用文档。
- 明确约束：保留生产代理 `http://127.0.0.1:7890`；不隐式改为直连；不新增依赖；成功重试不写日志。

## 项目快照

- 技术栈：Kotlin 1.9.22、mirai-console 2.16.0、Gradle、Ktor 2.3.3、Java 11 目标、SnakeYAML。
- 运行入口：`./gradlew.bat test`、`./gradlew.bat build`、`./gradlew.bat buildPlugin`。
- 关键模块：`PolymarketClient.kt`、`PolymarketSearchAction.kt`、`WebHookActionExecutor.kt`、`HttpProxySupport.kt`、Polymarket 测试与文档。
- 初始风险：工作区已有大量未提交开发改动；本次只在相关文件上增量协作，不还原其他变更。

## 项目文档与提示词需求

- 现有文档设计：`docs/POLYMARKET_USAGE.md` 说明 Polymarket 与 Model Plaza 配置；`examples/webhook_config.yml` 提供生产配置；`docs/explore-develop/` 保存决策记录。
- 文档缺口：Polymarket 指南未说明专用代理、瞬时 TLS 断链恢复和显式代理不会回退直连。
- 现有提示词资产：未发现 Polymarket 请求链路使用 AI/Agent 系统提示词；本次故障不涉及提示词运行时。
- 提示词需求：未来如增加自动故障诊断，输入应包括脱敏代理描述、目标主机、异常类别与尝试次数，输出应为单一根因摘要和可执行恢复建议。
- 提示词模板草案：角色为 XAiWebHook 网络诊断助手；任务为根据脱敏网络错误判断代理、DNS、TLS 或 HTTP 层；输出固定为“层级、证据、建议”；不得回显代理凭据或建议绕过显式代理。
- 评估样例：成功样例为输入“代理 curl 偶发 TLS 超时、直连固定超时”，输出“保留代理并重建连接有限重试”；边界样例为输入含 `user:password@host` 的代理 URL，输出中只允许显示 `host:port (authenticated)`。
- 完善方案：本次未新增运行时提示词；已补充用户文档，并在最终错误中使用脱敏代理描述。

## 真实用户体验假设

- 目标用户：通过群聊命令触发 Polymarket 搜索、且所在网络必须经过本机代理的机器人管理员。
- 核心任务：发送 `poly <关键词>` 后，插件稳定获取 Gamma 市场与 CLOB 历史并回复结果。
- 成功标准：代理偶发断开 TLS 通道时自动恢复一次；最终失败时用户得到明确失败提示，管理员日志只出现一条有上下文的错误。
- 易失败点：取消异常被当作普通搜索失败；显式代理失败后错误只有 `Channel was closed`；共享 CIO TLS 通道无法从日志判断目标和代理。

## 探索证据

- 查看内容：Polymarket 客户端、搜索动作、动作执行器、插件启停与 reload、代理解析器、配置、现有测试和用户文档。
- 运行观察：经 `http://127.0.0.1:7890` 请求 Gamma API 返回 HTTP 200；直连 10 秒超时。代理连续 5 次 curl 请求中 1 次出现 TLS 超时，其余成功；Java 14 `HttpClient` 经同一代理连续 5 次成功。
- 关键证据：异常堆栈位于 Ktor CIO `TLSClientHandshake`，早于 HTTP 状态和 JSON 解析；`/xwebhook reload` 不关闭 Polymarket 客户端，只有插件禁用调用 `close()`。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | CIO 经代理的 TLS 通道偶发关闭后请求直接失败，无恢复机会 | 一次瞬时代理抖动即可让群聊搜索失败 | 用户堆栈与重复代理请求实测 | fixed |
| ED-002 | P1 | 错误文本只有 `Channel was closed`，缺少目标、代理和尝试信息 | 管理员无法区分直连、代理或业务响应问题 | `PolymarketSearchAction` 最终日志 | fixed |
| ED-003 | P1 | Polymarket 动作与通用动作执行器会捕获取消异常 | 插件停服/任务取消可能被误报为搜索失败并发送失败提示 | 两处 `catch Throwable` / `runCatching` | fixed |
| ED-004 | P2 | 使用文档缺少代理恢复语义 | 管理员不了解失败时是否绕过代理或是否自动恢复 | `docs/POLYMARKET_USAGE.md` | fixed |

## 本次预开发改动

- 改动摘要：Polymarket 改用 Java 11 `HttpClient` 和 HTTP/1.1；按超时与代理缓存客户端；异步请求可随协程取消；仅对幂等 GET 的 `IOException` 重建客户端并重试一次；最终错误包含操作、目标主机、脱敏代理和尝试次数。
- 用户可感知结果：瞬时代理/TLS 断链不再立即返回 `Channel was closed`；重试成功时无额外日志；停服取消不会发送错误提示。
- 受影响区域：Gamma 市场查询、CLOB 历史查询、Polymarket action、通用动作取消传播、测试和使用文档。

## 验证结果

- 自动验证：Polymarket 客户端定向测试通过，覆盖 URI 编码、Gamma/CLOB 解析、一次重试、重试上限、凭据脱敏、非网络异常不重试、取消不重试、在途请求取消和关闭后重建。
- 自动验证：代理解析与 action 覆盖定向测试通过。
- 自动验证：Java 14 下 Polymarket 客户端测试通过，确认所用 API 与 Java 11 目标兼容。
- 自动验证：`./gradlew.bat test`、`./gradlew.bat build`、`./gradlew.bat buildPlugin` 全部通过。
- 手动验证：生产代理访问 Gamma API 成功；直连超时；代理重复请求复现一次 TLS 超时；JDK 客户端经代理连续请求成功。
- 文档验证：Polymarket 指南已加入 `proxy` 示例、有限重试和不回退直连说明。
- 提示词验证：不涉及运行时提示词；诊断模板成功样例能给出保留代理和有限重试，边界样例要求代理凭据脱敏。
- 未验证项：未在真实 mirai 群聊中触发完整消息回复链；本环境没有生产机器人会话。

## 残留风险

- 代理连续两次不可用时仍会失败，这是有限重试的预期边界；不会无限等待或绕过代理。
- 每次尝试分别使用 `timeout_ms`，连续超时时最坏耗时约为两次请求超时加 250ms；这是提高瞬时故障恢复率的取舍。
- Java 11 `HttpClient` 没有显式关闭 API；插件关闭时会清空客户端缓存并取消所有已登记的异步请求，由 JVM 回收客户端资源。

## 后续建议

- 若代理长期高频抖动，可后续增加可配置的重试次数/退避，但默认仍应保持有限且不输出逐次日志。
- 可在 `/xwebhook status` 增加 Polymarket 最近失败的脱敏摘要，不应记录代理密码或完整查询参数。
