---
skill: explore-develop
date: 2026-07-10 20:31
project: XAiWebHook
scope: webpage-screenshot-action
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：webpage-screenshot-action

## 调用背景

- 用户目标：新增可通过 outgoing 消息监听触发的网页登录、XPath 元素截图与群/好友发送动作，并允许 incoming 复用同一动作。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：浏览器配置、动作执行器、outgoing/incoming 复用、mirai 图片发送、默认 YAML、README、测试与插件打包。
- 明确约束：不硬编码真实账号、密码、Token 或隐私群号；不提供任意脚本执行；保留工作区已有 Markdown、At 和 MiraiCode 修改；目标 JVM 11、mirai-console 2.16.0。

## 项目快照

- 技术栈：Kotlin 1.9.22、Gradle 7.3.3、mirai-console 2.16.0、Ktor 2.3.3、SnakeYAML、Playwright Java 1.60.0。
- 运行入口：`./gradlew.bat test`、`./gradlew.bat build`、`./gradlew.bat buildPlugin`；浏览器运行时可用 `./gradlew.bat playwrightInstallChromium` 显式安装。
- 关键模块：`WebHookConfig.kt`、`WebHookActionExecutor.kt`、`WebPageScreenshotAction.kt`、`WebHookEventListener.kt`、`XAiWebHookCommand.kt`、`webhook_config.yml`。
- 初始风险：默认 JDK 25 与 Gradle 7.3.3 不兼容；Playwright 对象不支持多线程共享；示例站点有 Cap.js 验证；`config/` 的宽泛忽略规则错误忽略了源码目录。

## 项目文档与提示词需求

- 现有文档设计：根目录 `README.md` 覆盖安装、配置、incoming/outgoing、动作、命令、安全和故障排查；默认 YAML 以中文注释作为可复制配置参考。项目原先没有 `docs/` 决策记录目录。
- 文档缺口：没有浏览器依赖、登录态、验证码、域名白名单、环境变量凭据、网页截图动作和 outgoing 触发说明。
- 现有提示词资产：未发现 AI 系统提示词、Agent 模板或用户提示词资产；本功能不是 AI 推理功能。
- 提示词需求：不适用。网页操作采用结构化 YAML 步骤，输入为 URL、选择器、环境变量和目标会话，输出为 PNG 图片或标准动作失败结果。
- 提示词模板草案：不新增自然语言提示词；以 `send_webpage_screenshot` 声明式 action 作为唯一操作模板，限制为 `goto`、`fill`、`click`、`wait`、`wait_url`。
- 评估样例：成功样例为 outgoing 收到“监控截图”，环境变量和登录步骤有效，截图发送回触发群；失败样例为 `allowed_hosts` 不包含目标域名或密码环境变量缺失，动作拒绝执行并发送脱敏失败提示。
- 完善方案：README 与默认 YAML 同步维护动作字段和安全边界；新增字段必须继续附中文注释并补解析/边界测试。

## 真实用户体验假设

- 目标用户：维护 mirai 机器人的管理员；在群聊中查看监控状态的成员；通过 HTTP 调用机器人的自动化系统。
- 核心任务：发送关键词触发网页登录并获取监控区域截图；或通过 incoming 指定群/好友接收同一截图。
- 成功标准：触发后有处理中反馈；登录态可复用；截图只包含目标元素；失败时聊天有可理解提示，控制台保留可诊断原因；凭据不进入日志和仓库。
- 易失败点：浏览器缺失、环境变量缺失、Captcha/登录页面变化、XPath 失效、并发事件共享浏览器对象、域名未加入白名单、截图过大。

## 探索证据

- 查看内容：Gradle 配置、插件生命周期、配置解析、动作执行器、消息监听器、HTTP server、模板引擎、默认 YAML、README 和已有测试。
- 运行观察：通过浏览器访问 `https://hk3.geek2api.com/login` 与 `/monitor`；确认未登录访问 `/monitor` 跳转登录页，邮箱/密码为 `#email`/`#password`，Cap.js 控件位于开放 Shadow DOM，点击后生成 token 并启用提交按钮。
- 关键证据：Playwright Java 官方线程模型要求同一实例在单线程内使用；真实测试显示等待完整 `load` 会被示例站点第三方资源拖延，因此导航改为 `DOMContentLoaded` 并继续显式等待业务元素。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 缺少浏览器截图动作及可复用发送目标 | 无法从聊天或 incoming 获取受保护页面截图 | 动作分发仅支持消息、HTTP、命令、reply | fixed |
| ED-002 | P1 | Playwright 对象若直接被并发事件共享会产生线程安全问题 | 多个消息触发时会话损坏或随机失败 | Playwright Java 线程模型与现有并发事件协程 | fixed |
| ED-003 | P1 | `.gitignore` 的 `config/` 规则忽略源码 `src/.../config/` | `WebHookConfig.kt` 不会进入普通 git 状态/提交，部署代码缺失 | `git status --ignored` 显示源码配置目录被忽略 | fixed |
| ED-004 | P1 | 登录凭据若直接写 YAML 易泄露 | 密码可能进入仓库或配置审查 | 原配置没有 secret/env 读取能力 | fixed |
| ED-005 | P1 | 页面导航等待完整 load 会受长期第三方资源影响 | 页面已可用但动作仍超时 | Edge/Playwright 外网站点集成测试超时在 goto load | fixed |
| ED-006 | P2 | 无账号时无法验证真实 monitor 页面布局和登录后行为 | 站点改版或首登弹窗可能导致示例步骤失效 | 当前仅能走查公开登录页与验证码 | accepted |

## 本次预开发改动

- 改动摘要：新增 `send_webpage_screenshot`、浏览器全局配置、单线程 Playwright worker、内存 session、URL allowlist、环境变量填表、XPath 元素截图、自动回发事件会话、incoming 显式目标、pending/failure 文案、reload/disable 清理和 status 错误展示。
- 用户可感知结果：群/好友消息可按 outgoing 关键词触发截图并回发；incoming 可引用同一 action，通过 `group_id` 或 `friend_id` 指定接收方。
- 受影响区域：Gradle 依赖与任务、配置解析、动作执行、命令状态、默认 YAML、README、测试、`.gitignore`。

## 验证结果

- 自动验证：使用 JDK 14（输出 Java 11 字节码）运行 `./gradlew.bat test`、`./gradlew.bat build`、`./gradlew.bat buildPlugin`，均通过。
- 手动验证：使用浏览器走查 Geek2Api 登录页、未登录重定向和 Cap.js token 生成；未提交任何真实凭据。
- 集成验证：设置 `XAI_WEBHOOK_BROWSER_IT=true`，通过系统 Edge、临时本地 HTTP 页面和真实 `WebPageScreenshotAction.capture` 验证导航、元素等待、截图和 PNG 头，测试通过。
- 打包验证：生成 `build/mirai/XAiWebHook-0.1.0.mirai2.jar`，私有依赖清单包含 `com.microsoft.playwright:playwright:1.60.0`、`driver` 和 `driver-bundle`。
- 文档验证：默认 YAML 由 SnakeYAML 测试加载，确认浏览器配置和命名截图 action 存在；README 覆盖首次配置、outgoing/incoming 示例、安全和故障恢复。
- 提示词验证：不涉及自然语言提示词；成功/失败配置样例由单元测试覆盖 host allowlist、非法协议、步骤解析、环境变量缺失、目标选择和截图大小限制。
- 未验证项：缺少真实 Geek2Api 账号密码，未完成登录后 `/monitor` 指定 XPath 的端到端截图及 mirai 真机群/好友图片发送。

## 残留风险

- Geek2Api 页面结构、XPath、Captcha 或首登协议弹窗发生变化时，需要调整 YAML 步骤；插件不会绕过人工验证码。
- 登录态仅保存在内存，插件重启或 reload 后首次触发会重新登录。
- 当前浏览器队列为单线程串行执行，保证线程安全，但大量同时触发会增加等待时间。
- Playwright Java 1.60.0 依赖较多，mirai-console 首次解析私有依赖需要可访问 Maven 仓库；浏览器本体不会由插件自动下载。

## 后续建议

- 使用真实测试账号在部署机验证 `/monitor` 截图和 mirai 上传，并根据实际页面补充可选的首登协议/弹窗点击步骤。
- 后续可增加每个 session 的队列长度限制、冷却时间和 `/xwebhook browser-reset` 管理命令。
- 若需要跨重启保留登录态，应另行设计加密的 storage state，而不是直接持久化明文 Cookie。

## 后续 AccessToken 调整

- 用户补充：目标页面可以直接使用 AccessToken 等令牌访问，无需账号密码登录。
- 实施结果：`send_webpage_screenshot.auth` 支持 `token_env`/`token` 二选一，并可将同一令牌注入限定 host 的 HTTP 请求头、指定 origin 的 localStorage 和按 URL 作用域发送的 Cookie。
- 安全取舍：请求头通过 Playwright route 按 `header_hosts` 匹配后注入，不会发送给页面引用的第三方 CDN；localStorage 使用结构化 storage state，不开放自定义 JavaScript。
- 示例变化：默认 Geek2Api action 改为读取 `GEEK2API_AUTH_TOKEN`，使用 `Authorization: Bearer <token>`，并预置 localStorage 的实际键 `auth_token`；账号密码/Captcha 步骤仅作为无令牌站点的兼容方案保留在 README。
- 验证补充：集成测试确认首次导航同时收到 Bearer 请求头和作用域 Cookie，页面脚本可在加载时读取预置 localStorage；令牌环境变量缺失、第三方 host 不注入请求头和 storage state 结构均有单元测试。
