---
skill: explore-develop
date: 2026-07-11 15:15
project: E:\Desktop\IDEA\XAiWebHook
scope: geek2api-cli-bridge-screenshot
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：geek2api-cli-bridge-screenshot

## 调用背景

- 用户目标：分析 `docs/CLI_BRIDGE_DESKTOP_INTEGRATION.md`，将文档中的 `www.geek2api.com` 改为 `hk3.geek2api.com`，并按该流程更新 `geek2api-monitor-screenshot`。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：Geek2Api 监控截图认证、CLI Bridge API 状态机、系统浏览器启动、localStorage 会话、默认配置、完整实例、README 和测试。
- 明确约束：保留旧 AccessToken 与账号密码认证兼容路径；不得在日志、错误、状态或聊天消息泄露 token、`poll_secret`、密码或完整认证响应正文。

## 项目快照

- 技术栈：Kotlin 1.9.22、JVM 11 目标、mirai-console 2.16.0、Playwright 1.60.0、SnakeYAML、Gradle 7.3.3。
- 运行入口：`./gradlew.bat test`、`./gradlew.bat clean buildPlugin`；浏览器集成通过 `XAI_WEBHOOK_BROWSER_IT=true` 显式启用。
- 关键模块：`WebPageScreenshotAction.kt`、`WebPageCliBridgeAuth.kt`、`WebPageCredentialAuth.kt`、默认与示例 YAML、`WebPageScreenshotActionTest.kt`。
- 初始风险：工作区已有大量未提交的网页截图相关改动；默认 Geek2Api 账号密码流程被 hk3 的验证码阻断；当前默认 JDK 25 与 Kotlin 1.9.22/Gradle 7.3.3 不兼容。

## 项目文档与提示词需求

- 现有文档设计：README 提供安装、配置、动作参考与故障排查；默认 YAML 为逐项中文注释参考；`examples/` 提供可复制完整配置；`docs/CLI_BRIDGE_DESKTOP_INTEGRATION.md` 给出生产验证过的协议契约。
- 文档缺口：CLI Bridge 文档仍使用 `www.geek2api.com`；README 与默认实例仍把被验证码阻断的 `auth.login` 作为 Geek2Api 默认路径；未说明 poll 一次性交付后如何补齐 `auth_user`。
- 现有提示词资产：未发现 AI 系统提示词、Agent 提示词或自然语言生成链路；本功能使用受限 YAML 动作配置，不需要新增自然语言提示词。
- 提示词需求：以结构化 `auth.cli_bridge` 作为操作模板，输入为 start/browser/poll/profile/refresh URL 与轮询参数，输出为四项 localStorage 登录态和截图图片；禁止自定义脚本与敏感值输出。
- 提示词模板草案：`start -> browser(bridge_id only) -> poll(bridge_id + poll_secret) -> retain token pair -> profile -> localStorage -> screenshot -> refresh`。
- 评估样例：成功样例为 poll 先返回 `pending`、再返回 `authorized`，profile 补齐用户后截图会话可刷新；失败样例为 profile 暂时返回 503，已一次性交付的 token pair 仍保存在内存，后续只重试 profile，不重新使用已删除的 bridge。
- 完善方案：默认配置、完整实例和 README 统一使用 CLI Bridge；旧 `auth.login` 与 token/bootstrap 仅作为兼容路径保留并明确标注。

## 真实用户体验假设

- 目标用户：在桌面环境运行 mirai-console 的插件管理员，以及通过群聊、好友消息或 incoming HTTP 请求触发截图的使用者。
- 核心任务：首次触发时在系统浏览器完成正常 Geek2Api 登录授权，之后无需保存账号密码即可重复获得 `/monitor` 指定 XPath 截图。
- 成功标准：授权 URL 不含 `poll_secret`；5 分钟内可完成登录；四项前端登录态在导航前可用；token 临期自动刷新；截图失败不破坏有效登录态。
- 易失败点：系统默认浏览器无法启动、授权超时、poll 一次性交付后 profile 网络失败、refresh token 失效、运行环境 JDK 版本不兼容。

## 探索证据

- 查看内容：CLI Bridge 协议文档、当前账号密码认证客户端、BrowserWorker 会话管理、默认与示例 YAML、README、状态与错误处理、现有 27 项测试。
- 运行观察：JDK 25 下 Kotlin 编译器在解析 Java 版本时失败；切换本机 JDK 14 后单元测试、Edge 集成测试和插件构建均成功。
- 关键证据：CLI Bridge poll 响应没有 `user`，而 Geek2Api 路由守卫要求 `auth_token` 与 `auth_user`；`GET /api/v1/user/profile` 可补齐用户；poll token 只交付一次，必须在 profile 等后续操作前保留。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-201 | P1 | 默认 Geek2Api 动作使用受 Turnstile 阻断的纯账号密码接口 | 当前 hk3 无法完成首次登录截图 | README、默认 YAML、既有 `CAP_VERIFICATION_FAILED` 测试 | fixed |
| ED-202 | P1 | CLI Bridge poll 只返回 token pair，没有 `auth_user` | 导航后仍会被前端路由守卫判定未登录 | CLI Bridge 文档与前端登录态契约 | fixed |
| ED-203 | P1 | 一次性 token 若在 profile 请求失败前未保留，会永久丢失 | 用户需重复授权，旧 bridge 无法再次 poll | 文档的一次性交付约束 | fixed |
| ED-204 | P1 | 仅使用 `java.awt.Desktop` 在无头或精简 JVM 上可能打不开浏览器 | 首次授权无法开始 | Java Desktop 行为与服务器运行场景 | fixed |
| ED-205 | P2 | 集成文档仍引用 `www.geek2api.com` | 用户复制错误基址 | 文档第 58、177 行 | fixed |
| ED-206 | P2 | README 构建产物扩展名与实际 `mirai2.jar` 不一致 | 安装时难以定位产物 | clean buildPlugin 输出 | fixed |

## 本次预开发改动

- 改动摘要：新增 `auth.cli_bridge` 配置和客户端，完成 start、系统浏览器授权、poll、profile、refresh 状态机；默认 `geek2api-monitor-screenshot` 切换到 hk3 CLI Bridge；保留旧 login/token 兼容路径。
- 用户可感知结果：不再需要在 YAML 保存 Geek2Api 邮箱、密码或 TOTP；首次触发通过真实系统浏览器处理验证码/OAuth/2FA，后续复用内存登录态并自动刷新。
- 受影响区域：认证客户端、BrowserWorker 会话匹配与刷新、默认配置、完整实例、README、CLI Bridge 文档和测试。

## 验证结果

- 自动验证：使用 JDK 14 执行 `./gradlew.bat test`，31 项测试通过；包含 start/pending/authorized/profile/refresh、本地 URL 安全、一次性交付保留、默认 YAML 与文档断言。
- 手动验证：设置 `XAI_WEBHOOK_BROWSER_IT=true` 运行 `WebPageScreenshotActionTest`，系统 Edge 集成测试通过，覆盖真实 BrowserContext、localStorage、元素截图、旧 token/bootstrap 和账号密码兼容流程。
- 文档验证：确认 `docs/CLI_BRIDGE_DESKTOP_INTEGRATION.md` 不再包含 `www.geek2api.com`；默认与完整实例不含 Geek2Api email/password/TOTP 配置；README 链接指向新 CLI Bridge 示例。
- 提示词验证：成功样例完成 token pair、profile 用户与 refresh 轮换；失败样例证明 profile 503 时 token 先进入内存待完成区，错误和授权 URL均不包含 `poll_secret`。
- 构建验证：使用 JDK 14 执行 `./gradlew.bat clean buildPlugin` 成功，产物为 `build/mirai/XAiWebHook-0.1.0.mirai2.jar`。
- 未验证项：未对真实 hk3 完成人工网页登录和 mirai 真机消息发送；该流程需要用户在系统浏览器交互，自动测试仅使用本地协议模拟与 Edge 页面集成。

## 残留风险

- CLI Bridge token pair 按现有插件安全边界仅保存在进程内存；reload、停服或进程退出后需重新授权，未接入 Windows Credential Manager、Keychain 或 libsecret。
- mirai 运行在无桌面服务器且系统浏览器启动回退也不可用时，需要管理员从控制台复制只含 `bridge_id` 的 URL，在可访问同一 hk3 站点的桌面浏览器及时打开。
- 默认系统 Java 25 无法运行当前 Gradle/Kotlin 编译链；构建需切换到与当前 Gradle/Kotlin toolchain 兼容的 JDK，本次实际使用 JDK 14。

## 后续建议

- 如需跨重启复用登录态，新增可选的系统密钥库适配层，只持久化 refresh token，并继续禁止明文写入 YAML。
- 在有真实 mirai 机器人和可交互 hk3 账号的环境执行一次首次授权、刷新后第二次截图及群/好友回发验收。
