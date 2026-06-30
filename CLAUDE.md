# XAiWebHook 开发指南

## 项目概览

XAiWebHook 是一个 mirai-console Kotlin 插件，目标是通过 YAML 完成 WebHook 的双向配置：外部 HTTP 请求可以触发机器人动作，mirai 群聊/好友消息事件也可以转发到外部 WebHook。

当前首期范围是可编译项目骨架和最小闭环基础架构：HTTP incoming 发送群/好友文本消息、outgoing 群/好友消息转发、`/xwebhook reload`、`/xwebhook status`、基础表达式模板。

## 功能规划

- 已确认首期方向为双向收发：HTTP incoming 触发机器人动作，mirai group/friend message outgoing 转发到外部 WebHook。
- YAML 配置化目标是把 server、auth、template、security、logging、incoming endpoints、outgoing routes、actions 都放入 `webhook_config.yml`，避免在源码里硬编码业务路由。
- Incoming 首期闭环支持 Bearer Token、POST endpoint、JSON body、群/好友文本发送、HTTP JSON reply。
- Outgoing 首期闭环支持群消息和好友消息事件，按事件类型、群号、好友号、发送者、消息文本匹配后执行 HTTP request action。
- 表达式模板首期支持 `${request.body.xxx}`、`${event.messageText}`、默认值、字符串匹配函数和基础布尔条件，不引入任意脚本执行。
- 后续优先级建议：HMAC 签名鉴权、成员进退群事件、At/图片/引用等 message chain、HTTP 重试与超时细化、route test 命令、配置 schema 校验、限流和审计日志。

## 构建命令

- 优先使用 `./gradlew build` 或 `./gradlew.bat build`。
- 当前仓库结构复用 `XAiYan` 的 Gradle wrapper 文件。如果 `gradle/wrapper/gradle-wrapper.jar` 缺失，wrapper 构建会失败，需要先补齐 wrapper 后再验证。
- 本项目目标 JVM 为 Java 11，mirai-console 插件版本为 `2.16.0`。

## 代码约定

- Kotlin 包名固定为 `kim.hhhhhy.x.webhook`。
- Gradle 开启 `explicitApi()`，所有 public API 必须显式声明可见性和返回类型。
- 配置解析放在 `config/WebHookConfig.kt`，HTTP 服务放在 `server/WebHookServer.kt`，mirai 事件监听放在 `listener/WebHookEventListener.kt`。
- 不复制 `XAiYan` 的 AI、PageIndex、mem0、S3、TrMenu 业务逻辑。

## YAML 配置

- 默认配置资源位于 `src/main/resources/webhook_config.yml`。
- 插件首次启动会复制到 mirai 配置目录下的 `webhook_config.yml`。
- 示例 token、URL、QQ群号必须使用占位符，不要提交真实 token、真实公网地址或隐私群号。
- 默认配置文件必须为每个配置选项附带中文注释，说明用途、默认值与取值范围；新增配置项时同步补注释，并保持注释描述与 `WebHookConfig.kt` 的解析逻辑一致。

## 安全边界

- Incoming endpoint 默认必须使用 `Authorization: Bearer <token>`。
- `execute_command` 动作默认禁用，必须同时开启 `security.allow_command_execution` 和 action 自身 `enabled` 才能执行。
- 不要新增任意脚本执行能力；表达式模板只用于变量替换和基础条件判断。
