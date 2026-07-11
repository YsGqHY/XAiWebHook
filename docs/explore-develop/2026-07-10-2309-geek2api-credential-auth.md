---
skill: explore-develop
date: 2026-07-10 23:09
project: XAiWebHook
scope: geek2api-credential-auth
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：geek2api-credential-auth

## 调用背景

- 用户目标：将 Geek2Api 网页截图认证从预置 AccessToken 与 `/auth/me` bootstrap，切换为 `/auth/login`、`/auth/login/2fa` 和 `/auth/refresh` 纯接口账号密码流程。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：网页截图认证模型、Playwright API 请求、2FA TOTP、token pair 刷新、BrowserContext 会话、默认 YAML、完整示例、README、测试和插件打包。
- 明确约束：用户选择“纯接口强制”，登录请求只提交 email/password，不生成、绕过或提交 Cap/Turnstile token；凭据必须来自环境变量；保留旧 Token 模式兼容；目标字节码为 Java 11。

## 项目快照

- 技术栈：Kotlin 1.9.22、Gradle 7.3.3、mirai-console 2.16.0、Playwright Java 1.60.0、kotlinx.serialization JSON、SnakeYAML。
- 运行入口：`./gradlew test`、`XAI_WEBHOOK_BROWSER_IT=true ./gradlew test --rerun-tasks`、`./gradlew build buildPlugin`。
- 关键模块：`WebPageScreenshotAction.kt`、`WebPageCredentialAuth.kt`、`WebPageScreenshotActionTest.kt`、`webhook_config.yml`、Geek2Api 完整示例和 README。
- 初始风险：工作区包含前序未提交功能；现有 session 只保存 BrowserContext；请求头 route 会固定覆盖旧 access token；页面/XPath 失败会清除 session 并重复登录；登录接口限流 20 次/分钟；当前 hk3 开启 Cap 验证。

## 项目文档与提示词需求

- 现有文档设计：README 覆盖安装、动作配置、安全和故障排查；默认 YAML 作为逐字段中文参考；`examples/` 提供完整可复制实例；`docs/explore-develop/` 保存决策记录。
- 文档缺口：原文档只描述 AccessToken 与 `/auth/me` bootstrap，没有 login/2FA/refresh 请求体、四项 localStorage、刷新轮换、登录冷却和 Captcha 限制。
- 现有提示词资产：未发现 AI 系统提示词、Agent 模板或自然语言提示词资产；本功能是结构化认证与浏览器自动化，不涉及模型推理。
- 提示词需求：不适用。输入为声明式 YAML、环境变量和 API JSON，输出为截图或脱敏认证错误。
- 提示词模板草案：不新增自然语言提示词；以 `auth.login` 结构化配置作为操作模板，固定字段为 endpoint URL、凭据环境变量、TOTP 来源、刷新提前量、重试冷却和 localStorage 键。
- 评估样例：成功样例为 login 返回 token pair，四项登录态在首次导航前可读，临近过期后 refresh 旋转 token 且不重复 login；失败样例为 API 返回 `CAP_VERIFICATION_FAILED`，错误包含状态和 reason，但不包含邮箱、密码或响应正文额外字段。
- 完善方案：默认 YAML、完整实例、README 和解析测试同步维护；认证 API 新字段必须继续使用环境变量和 host allowlist，并补成功、2FA、刷新、限流和脱敏测试。

## 真实用户体验假设

- 目标用户：维护 mirai 机器人的管理员；通过群聊、好友或 incoming 请求监控截图的使用者。
- 核心任务：配置一次账号密码，后续截图复用登录态；账号启用 2FA 时自动生成动态码；token 到期后自动刷新，不需要频繁人工更新 AccessToken。
- 成功标准：首次触发最多登录一次；后续触发复用 session；刷新 token 后页面仍保持登录；页面元素失败不导致重新登录；认证错误可行动且不泄露凭据。
- 易失败点：服务端要求 Captcha、TOTP 密钥缺失、refresh token 失效、同一 session_key 被不同账号复用、页面自身刷新与插件状态不一致、失败触发登录限流。

## 探索证据

- 查看内容：网页截图执行器、会话清理、动作配置、测试、默认 YAML、完整示例、README 和前次 explore-develop 记录。
- 运行观察：读取当前 Geek2Api 前端 `index-D8F7QRQ4.js` 与 `LoginView-CBF2Uovg.js`，确认 login body 为 `{email,password,turnstile_token?}`，2FA body 为 `{temp_token,totp_code}`，refresh body 为 `{refresh_token}`。
- 关键证据：前端成功登录后写入 `auth_token`、`refresh_token`、`token_expires_at`、`auth_user`；Axios 会解包 `{code:0,data}`；前端 refresh 会旋转 access/refresh token；对当前 hk3 发送无真实凭据的 email/password 探测请求返回 HTTP 400、`CAP_VERIFICATION_FAILED`。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-101 | P1 | 默认流程依赖人工 AccessToken，无法自动续期 | Token 到期后截图中断，需要人工替换并重启 | 原 `auth.token_env` + `/auth/me` bootstrap | fixed |
| ED-102 | P1 | 固定 route 请求头会覆盖页面刷新后的新 token | refresh 成功后请求仍携带旧 access token | `resumeWithScopedHeader` 捕获不可变 token | fixed |
| ED-103 | P1 | 任意页面/XPath 失败都会销毁 session | 页面改版会导致每次触发都重新登录，可能撞到 20 次/分钟限流 | 原 capture 异常路径删除 session | fixed |
| ED-104 | P1 | 相同 session_key 可复用不同账号的上下文 | 配置错误时可能跨账号串用登录态 | session map 只按字符串键查询 | fixed |
| ED-105 | P1 | 当前 hk3 强制 Cap 验证 | 纯 email/password 接口无法完成真实登录 | 实际 HTTP 400 `CAP_VERIFICATION_FAILED` | accepted，按用户选择不实现验证码 |
| ED-106 | P2 | 原文档没有 2FA、refresh、冷却和 token pair 存储说明 | 管理员难以正确配置和排障 | README/YAML 仍描述 AccessToken | fixed |

## 本次预开发改动

- 改动摘要：新增纯接口认证客户端、直接/包装响应解析、RFC 6238 TOTP、login/2FA/refresh、四项 localStorage、可刷新会话、页面 storage state 同步、登录失败冷却和认证指纹匹配。
- 用户可感知结果：在验证码关闭的部署中，管理员只需设置邮箱、密码和可选 TOTP 密钥，插件即可建立登录态并自动刷新；页面或 XPath 错误不会引发重复登录。
- 受影响区域：网页截图核心执行器、认证客户端、测试、默认配置、Geek2Api 完整示例、README 和插件产物。

## 验证结果

- 自动验证：26 项测试通过，其中 `WebPageScreenshotActionTest` 23 项、`MarkdownImageRendererTest` 3 项，失败和错误均为 0。
- 手动/接口验证：检查当前 Geek2Api 前端构建产物中的 login、2FA、refresh 与 localStorage 行为；使用无真实账号探测确认当前 hk3 的 Cap 限制。
- 浏览器集成验证：使用系统 Edge 与本地 HTTP 服务验证首次 login、wrapped/direct 响应、2FA 请求体、refresh token 旋转、四项 storage、XPath 失败后会话复用、Cap 错误脱敏和登录冷却。
- 构建验证：使用 JDK 14 运行 Gradle 7.3.3，Kotlin 继续输出 Java 11 字节码；`build buildPlugin --rerun-tasks` 成功，生成 `build/mirai/XAiWebHook-0.1.0.mirai2.jar`。
- 文档验证：默认 YAML 与完整示例由 SnakeYAML 测试加载，确认 login、2FA、refresh 和四项 localStorage 字段存在；README 链接已切换到账号密码示例。
- 提示词验证：不涉及自然语言提示词；结构化成功和失败样例均由单元/Edge 集成测试覆盖。
- 未验证项：未使用真实 Geek2Api 账号执行端到端截图，也未进行 mirai 真机群/好友上传；当前 hk3 的 Cap 限制会在提交凭据前阻断纯接口流程。

## 残留风险

- 当前 `hk3.geek2api.com` 仍要求 `turnstile_token`；除非服务端关闭验证码，默认纯接口配置会稳定返回 `CAP_VERIFICATION_FAILED`。
- TOTP 依赖机器人主机时钟准确；明显时钟漂移会导致六位码校验失败。
- token pair 和凭据仅保存在进程内存与 BrowserContext，不跨重启持久化；重启后会重新登录一次。
- API 返回字段或前端 localStorage 键变化时，需要同步配置和解析测试。
- 最终 Gradle 构建使用 JDK 14 是因为本机 JDK 11 Gradle daemon 一度遇到 UDP file-lock 端口占用；代码已使用 JDK 11 完成编译/普通测试，目标字节码仍为 Java 11。

## 后续建议

- 若必须在当前 hk3 使用账号密码，应另行批准“浏览器辅助 Cap”方案，或由服务端为受信任自动化关闭/豁免登录验证码。
- 部署前使用专用低权限测试账号验证 `/monitor` XPath 和真实 refresh 生命周期。
- 可后续增加 `/xwebhook browser-reset <session>` 管理命令，允许管理员只清理指定认证会话。
