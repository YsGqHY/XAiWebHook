---
skill: explore-develop
date: 2026-07-11 17:15
project: E:\Desktop\IDEA\XAiWebHook
scope: browser-session-cookie-cache
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：browser-session-cookie-cache

## 调用背景

- 用户目标：为网页截图流程增加 Cookie 缓存，使重复截图无需每次重新登录即可进入截图页面。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：`send_webpage_screenshot` 的 BrowserContext 生命周期、CLI Bridge/账号认证恢复、纯 Cookie 会话、配置、默认实例、文档与测试。
- 明确约束：沿用 Playwright 与现有 `session_key` 架构，不新增任意脚本执行，不泄露 token/Cookie/session key，不破坏未启用缓存时的内存会话行为。

## 项目快照

- 技术栈：Kotlin 1.9.22、JVM 11 目标、mirai-console 2.16.0、Playwright 1.60.0、SnakeYAML、Gradle 7.3.3。
- 运行入口：`./gradlew.bat test`、`XAI_WEBHOOK_BROWSER_IT=true ./gradlew.bat test --tests "kim.hhhhhy.x.webhook.action.WebPageScreenshotActionTest"`、`./gradlew.bat build`。
- 关键模块：`WebPageScreenshotAction.kt`、新增 `WebPageSessionCache.kt`、`WebHookConfig.kt`、默认与完整实例 YAML、README、CLI Bridge 文档和截图测试。
- 初始风险：工作区进入任务时干净；现有会话只在进程内存中，reload/停服/重启后丢失；当前系统 Java 25 与 Gradle 7.3.3 不兼容，需使用本机 JDK 14 验证。

## 项目文档与提示词需求

- 现有文档设计：README 覆盖安装、完整配置、截图认证和故障排查；默认 YAML 为逐项中文注释参考；`examples/` 提供 Geek2Api 可复制实例；CLI Bridge 文档记录协议与敏感信息边界。
- 文档缺口：文档明确说明登录态只保存在内存，未提供跨 reload/重启免登录方案；没有说明 storageState 缓存的敏感性、目录、失效回退和纯 Cookie 登录步骤约束。
- 现有提示词资产：未发现 AI 系统提示词、Agent 提示词或自然语言生成链路；本功能是确定性的 YAML/浏览器状态机，不需要新增自然语言提示词。
- 提示词需求：以结构化配置表达缓存开关与目录，输入为 `session_key`、认证配置和 Playwright storageState，输出为可恢复的 BrowserContext；禁止把原始 session key、Cookie、token 或缓存正文写入日志。
- 提示词模板草案：`resolve session key -> hash cache path -> verify auth fingerprint -> restore cookies/localStorage -> refresh token if needed -> create context -> capture -> atomically persist state -> fallback to fresh login on invalid cache`。
- 评估样例：成功样例为首次登录后保存 Cookie/localStorage，关闭 BrowserWorker，再次截图从磁盘恢复且登录/刷新调用计数不增加；失败样例为缓存损坏、认证指纹变化、相对目录越界或 refresh 失败，旧缓存被拒绝或删除并回退正常登录。
- 完善方案：默认解析保持缓存关闭，默认 Geek2Api 示例显式开启；文档同步说明磁盘缓存安全取舍和高安全环境保持关闭的建议。

## 真实用户体验假设

- 目标用户：运行 mirai-console 的插件管理员，以及通过群聊、好友消息或 incoming HTTP 重复请求监控截图的使用者。
- 核心任务：仅在首次或登录态真正失效时完成一次授权，之后即使执行 reload 或重启插件，也能直接进入已登录截图页面。
- 成功标准：同一 `session_key` 恢复完整 Cookie/localStorage；token 临期自动刷新；刷新时不丢 Cookie；缓存错误不阻断恢复；日志与文件名不泄露 session key 或认证内容。
- 易失败点：Cookie 只在内存、刷新重建上下文丢 Cookie、缓存文件损坏、认证配置变更后误用旧状态、session key 路径注入、明文 storageState 权限不足、网页表单登录步骤在已登录页面仍被强制执行。

## 探索证据

- 查看内容：BrowserWorker 会话创建/刷新/关闭、CLI Bridge 与凭据认证客户端、默认与完整实例 YAML、README、CLI Bridge 安全文档、截图单元与 Edge 集成测试。
- 运行观察：原实现相同 `session_key` 仅在当前进程复用 BrowserContext；`WebHookActionExecutor.reload()` 会调用截图 reset；刷新 token 时用仅含 localStorage 的新状态重建上下文，无法保留页面产生的 Cookie。
- 关键证据：Playwright `BrowserContext.storageState()` 同时包含 Cookie 与 origin localStorage，可用于新上下文恢复；现有四项 localStorage 足以重建 token pair 并继续 refresh；JDK 14 下真实 Edge 测试可验证 worker 重建后的免登录路径。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-301 | P1 | 登录态只保存在 BrowserWorker 内存 | reload、停服或进程重启后再次要求登录 | `WebPageScreenshotAction.reset/close` 与原 README | fixed |
| ED-302 | P1 | token 刷新重建 BrowserContext 时未合并旧 storageState | 站点 Cookie 在刷新后丢失，可能被重新判定未登录 | 原 `newContext(browser, config, refreshedAuth)` | fixed |
| ED-303 | P1 | 直接持久化 session key 或不校验认证配置会产生路径与串号风险 | 恶意 key 可影响路径，配置切换可能复用错误账号状态 | 动态 `session_key` 与多认证模式 | fixed |
| ED-304 | P1 | 缓存损坏或 refresh 失效没有可恢复路径 | 截图持续失败或必须人工删除文件 | 新增缓存恢复状态机前无磁盘缓存容错 | fixed |
| ED-305 | P2 | 文档没有说明磁盘登录态的敏感性与表单登录步骤约束 | 管理员可能误共享缓存，或已登录时仍执行必选 fill/click | README 与 CLI Bridge 安全章节 | fixed |

## 本次预开发改动

- 改动摘要：新增基于 Playwright storageState 的会话缓存，使用 SHA-256 文件名、认证指纹、5 MiB 上限、原子替换和尽力收紧文件权限；所有带 `session_key` 的截图会话均可缓存 Cookie/localStorage，CLI Bridge 与账号认证会额外恢复 token pair 并在临期时刷新。
- 用户可感知结果：首次成功登录后，后续截图以及 reload/停服/进程重启后的首次截图可直接进入已登录页面；refresh 失败时清除缓存并在当前触发中重新授权，而不是永久卡在坏状态。
- 受影响区域：截图 worker、会话缓存 I/O、浏览器配置模型、默认配置、Geek2Api 完整实例、README、CLI Bridge 安全文档与测试。

## 验证结果

- 自动验证：JDK 14 下执行 `./gradlew.bat test` 成功，共 45 项测试通过（3 + 33 + 9），0 失败、0 错误。
- 手动验证：设置 `XAI_WEBHOOK_BROWSER_IT=true` 运行 `WebPageScreenshotActionTest`，33 项通过；真实系统 Edge 覆盖首次登录、token refresh、关闭 BrowserWorker、磁盘恢复和第三次截图，登录与刷新调用计数均未增加。
- 构建验证：JDK 14 下执行 `./gradlew.bat build` 成功，生成 `build/libs/XAiWebHook-0.1.0.jar` 与 `build/mirai/XAiWebHook-0.1.0.mirai2.jar`。
- 文档验证：默认 YAML 显式保持 `session_cache_enabled: false`，完整实例显式配置为 `true` 并设置缓存目录；README 记录默认值、路径、安全边界、失效回退和 Cookie 表单登录步骤；CLI Bridge 文档保留系统密钥库优先建议并说明 storageState 明文缓存取舍。
- 提示词验证：成功样例验证缓存恢复后不重复登录；边界样例验证认证指纹变化会删除旧缓存、`../` 相对目录被拒、无显式 auth 的 Cookie-only 会话可往返恢复。
- 未验证项：未使用真实 hk3 账号执行跨操作系统重启验收；当前浏览器测试使用本地协议模拟与系统 Edge，不包含真实站点主动撤销未过期 token 的场景。

## 残留风险

- Playwright storageState 包含 Cookie、localStorage 和 refresh token，当前仅使用原子写入、哈希文件名与尽力限制文件权限，没有接入 Windows Credential Manager、Keychain 或 libsecret 加密；高安全环境应保持 `session_cache_enabled: false`。
- 站点若在 token 标记到期前主动撤销访问权，通用截图步骤无法可靠区分“认证失效”和“页面结构变化”，本次不会对任意元素失败自动强制重新登录；正常到期、refresh 失败、缓存损坏和配置变化已覆盖。
- 既有运行时 `webhook_config.yml` 不会被新 JAR 自动覆盖，需要手动增加 `browser.session_cache_enabled: true` 与 `session_cache_dir` 后执行 `/xwebhook reload`。
- 纯网页表单登录若把 `fill`/`click` 设为必选步骤，恢复 Cookie 后这些元素不存在仍会失败；应按文档设置 `optional: true`。

## 后续建议

- 如需更高安全级别，为 refresh token/storageState 增加 Windows Credential Manager、Keychain、libsecret 适配，并保留当前文件缓存作为显式低依赖模式。
- 可增加可选的认证健康检查 URL 或登录页 URL 规则，在服务器主动撤销未过期 token 时精确清缓存并重试，避免用页面元素失败做不可靠推断。
