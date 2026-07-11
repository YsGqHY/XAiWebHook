---
skill: explore-develop
date: 2026-07-10 23:20
project: XAiWebHook
scope: config-file-credentials
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：config-file-credentials

## 调用背景

- 用户目标：生产环境需要直接在 `webhook_config.yml` 中配置 Geek2Api 登录邮箱和密码，不强制使用环境变量。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：`auth.login` 凭据来源解析、默认 YAML、完整实例、README、单元测试、Edge 集成测试和插件打包。
- 明确约束：文件凭据不得进入日志、状态输出或错误消息；默认资源只能提交占位值；继续兼容环境变量模式。

## 项目快照

- 技术栈：Kotlin 1.9.22、Gradle 7.3.3、mirai-console 2.16.0、Playwright Java 1.60.0、SnakeYAML。
- 运行入口：`./gradlew test`、`XAI_WEBHOOK_BROWSER_IT=true ./gradlew build buildPlugin --rerun-tasks`。
- 关键模块：`WebPageScreenshotAction.kt`、`WebPageCredentialAuth.kt`、`webhook_config.yml`、Geek2Api 示例、README 和截图动作测试。
- 初始风险：原配置强制 `email_env` / `password_env`；直接把密码改成普通字符串时容易被错误地 trim；运行时 YAML 保存明文密码，需要文档明确文件权限边界。

## 项目文档与提示词需求

- 现有文档设计：默认 YAML 提供逐字段中文注释，`examples/` 提供完整生产配置骨架，README 覆盖认证流程、安全和故障排查。
- 文档缺口：文档仍要求邮箱密码环境变量，未说明文件值与环境变量的二选一规则，也未说明明文配置文件权限。
- 现有提示词资产：未发现 AI 系统提示词、Agent 模板或自然语言提示词资产；本功能不涉及模型推理。
- 提示词需求：不适用。输入为结构化 YAML 凭据字段，输出为解析后的登录请求或脱敏配置错误。
- 提示词模板草案：不新增自然语言提示词；配置模板使用 `email/password`，并保留注释形式的 `email_env/password_env` 替代项。
- 评估样例：成功样例为 YAML 中配置邮箱密码且空环境变量映射，Edge 登录流程仍成功；失败样例为同时配置 `email` 与 `email_env`，解析阶段拒绝且错误不包含字段值。
- 完善方案：默认资源始终保留 `change-me-*` 占位值；真实运行时配置由部署者维护文件 ACL，不进入版本库。

## 真实用户体验假设

- 目标用户：在固定生产主机部署 mirai-console、希望统一通过 YAML 管理服务账号的管理员。
- 核心任务：直接编辑运行时 `webhook_config.yml` 填写邮箱和密码，执行 reload 后完成登录截图。
- 成功标准：无需设置进程环境变量；配置值原样进入登录请求；修改 YAML 后 reload 生效；日志和错误中看不到凭据。
- 易失败点：同时配置文件值和环境变量、密码首尾空格被破坏、真实配置误提交仓库、文件读取权限过宽。

## 探索证据

- 查看内容：当前 `BrowserLoginAuth` 只保存环境变量名，`resolveAuth` 只从环境变量读取；默认 YAML、完整实例和 README 均要求 `GEEK2API_EMAIL` / `GEEK2API_PASSWORD`。
- 运行观察：新增文件凭据后使用本地认证服务和系统 Edge 执行完整 login、截图、refresh 流程，环境变量映射为空时仍成功。
- 关键证据：密码解析使用保留字符串函数，不执行 trim；邮箱做首尾规范化；认证客户端错误仅使用状态、reason 和 message，不拼接请求体。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-201 | P1 | 邮箱密码只能从环境变量读取 | 用户无法按生产运维习惯在统一 YAML 中管理账号 | `BrowserLoginAuth` 仅有 `emailEnv/passwordEnv` | fixed |
| ED-202 | P1 | 文件密码若复用普通字符串解析会丢失首尾空格 | 特殊合法密码登录失败 | `renderOptionalString` 会 trim | fixed |
| ED-203 | P1 | 文件值与环境变量同时存在时来源不明确 | 配置修改后可能使用非预期凭据 | 原模型没有双来源校验 | fixed |
| ED-204 | P2 | 文档未说明明文配置文件风险 | 运行时密码可能被其他本机用户读取或误提交 | README 只推荐环境变量 | fixed |

## 本次预开发改动

- 改动摘要：`email/email_env` 与 `password/password_env` 分别实现严格二选一；文件密码保留原始字符；默认 YAML 和完整实例改用占位文件凭据；环境变量模式继续兼容。
- 用户可感知结果：管理员现在可直接替换 YAML 中的邮箱和密码，执行 `/xwebhook reload` 后使用新凭据，无需重启进程或设置环境变量。
- 受影响区域：认证配置模型与解析、默认配置、Geek2Api 示例、README 和测试。

## 验证结果

- 自动验证：27 项测试通过，其中截图动作测试 24 项、Markdown 图片测试 3 项，失败和错误均为 0。
- 浏览器验证：系统 Edge 集成测试的普通 login 改为直接读取 action 文件值，完成登录、localStorage 注入、截图和后续 refresh；2FA 与错误路径继续验证环境变量兼容。
- 构建验证：`build buildPlugin --rerun-tasks` 成功，生成 `build/mirai/XAiWebHook-0.1.0.mirai2.jar`。
- 文档验证：SnakeYAML 测试确认默认 YAML 与完整实例包含占位 `email/password`，不再启用 `email_env/password_env`。
- 提示词验证：不涉及自然语言提示词；成功与双来源冲突样例由测试覆盖。
- 未验证项：未将真实生产邮箱密码写入仓库或用于当前 hk3 登录；当前实例仍受 Cap 验证限制。

## 残留风险

- 运行时 `webhook_config.yml` 中的密码为明文，安全性依赖操作系统文件权限、备份权限和部署流程。
- `/xwebhook reload` 会重建浏览器会话并使用新凭据，正在执行的截图可能在 reload 时中止。
- 当前 hk3 的 `CAP_VERIFICATION_FAILED` 限制与凭据来源无关，文件配置不能绕过验证码。

## 后续建议

- 在生产主机限制 mirai 配置目录只允许运行账号和管理员读取。
- 将真实运行时配置排除在备份共享、日志采集和代码仓库之外。
- 如后续需要更强的静态凭据保护，可设计操作系统密钥库或加密配置，而不是在日志层做补救。
