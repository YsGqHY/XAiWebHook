# XAiWebHook

XAiWebHook 是一个 [mirai-console](https://github.com/mamoe/mirai) Kotlin 插件，通过一份 YAML 完成 WebHook 的双向配置：

- Incoming：外部 HTTP 请求触发机器人动作（发送群/好友消息、转发 HTTP、执行命令、返回自定义响应）。
- Outgoing：mirai 群聊/好友消息事件按规则匹配后转发到外部 WebHook。

所有路由、鉴权、模板、安全策略都写在 `webhook_config.yml`，无需改动源码。

## 首期能力边界

已支持：

- Incoming：Bearer Token 鉴权、自定义 method/path、JSON body 解析、群/好友文本发送、HTTP JSON 响应（reply）。
- Outgoing：群消息、好友消息事件，按事件类型 / 群号 / 好友号 / 发送者 / 消息文本（contains/startsWith/endsWith/regex）/ 布尔条件匹配，执行 `http_request`。
- 表达式模板：变量替换、默认值、字符串函数、基础布尔条件。
- 命令：`/xwebhook reload`、`/xwebhook status`。

暂未支持（计划中，勿在配置里使用）：HMAC 签名鉴权、成员进退群等非消息事件、At / 图片 / 引用等消息链、HTTP 重试、route test 命令、配置 schema 校验、限流与审计日志。

## 环境要求

- JDK 11（插件 JVM 目标为 Java 11）。
- mirai-console 2.16.0 运行环境。

## 构建与安装

使用项目自带的 Gradle wrapper 构建：

```bash
# Windows
./gradlew.bat build

# Linux / macOS
./gradlew build
```

构建产物位于 `build/mirai/XAiWebHook-0.1.0.jar`。将该 jar 放入 mirai-console 的 `plugins/` 目录，然后启动或重启 mirai-console。

## 首次启动与配置文件

插件首次启用时会在配置目录生成默认配置：

```
<mirai 根目录>/config/kim.hhhhhy.x.webhook/webhook_config.yml
```

默认配置 `server.enabled: true`，监听 `127.0.0.1:18080`，base path 为 `/webhook`，并预置两个 incoming 端点（`/send-group`、`/send-friend`）和两个默认关闭的 outgoing 路由示例。修改配置后执行 `/xwebhook reload` 即可热重载，无需重启 mirai。

## 配置详解

完整结构如下，下表给出每个字段的含义与默认值（默认值来自配置解析逻辑）。

```yaml
server:
  enabled: true
  host: "127.0.0.1"
  port: 18080
  base_path: "/webhook"
auth:
  type: "bearer"
  tokens:
    - "change-me-token"
  allow_empty_for_localhost: false
templates:
  enable_expressions: true
  strict_missing_variables: false
security:
  allow_command_execution: false
  max_body_bytes: 1048576
logging:
  request: true
  response: true
  error_stacktrace: true
incoming:
  endpoints: []
outgoing:
  routes: []
actions: {}
```

server.enabled：是否启动 HTTP 服务，默认 `true`。
server.host：监听地址，默认 `127.0.0.1`（仅本机）。需要接收外部请求时改为 `0.0.0.0`，并务必配置强 token。
server.port：监听端口，默认 `18080`，取值范围 1-65535。
server.base_path：所有 incoming 端点的统一前缀，默认 `/webhook`。
auth.type：鉴权类型，目前仅支持 `bearer`，其它值会导致所有请求被拒。
auth.tokens：全局 Bearer Token 列表，端点未单独配置 token 时回退到此处。
auth.allow_empty_for_localhost：默认 `false`。为 `true` 时允许无 token 端点，但仅放行来自回环地址的请求。回环判定包含 IPv4 回环网段 `127.0.0.0/8`（即任意 `127.` 开头地址）、`localhost`，以及 IPv6 回环 `::1` 与其完整展开形式 `0:0:0:0:0:0:0:1`。该判定与启动期 host 校验使用同一套规则。
templates.enable_expressions：是否启用 `${...}` 表达式，默认 `true`。关闭后模板原样输出。
templates.strict_missing_variables：默认 `false`。为 `true` 时未解析到的变量保留原始 `${...}` 文本，否则替换为空串。
security.allow_command_execution：全局命令执行开关，默认 `false`。
security.max_body_bytes：incoming 请求体最大字节数，默认 `1048576`（1 MiB），`0` 表示不限制。超限返回 413。
logging.request / logging.response：是否记录请求 / 响应日志，默认均 `true`。
logging.error_stacktrace：是否记录错误堆栈，默认 `true`。

安全约束（启动期校验）：当存在启用的 incoming 端点且其有效 token 为空、同时 `allow_empty_for_localhost` 为 `false` 时，服务会拒绝启动并记录错误。若 `allow_empty_for_localhost` 为 `true` 但 host 绑定到非回环地址，会输出安全告警。

## Incoming：外部请求触发机器人

端点定义在 `incoming.endpoints` 下，每个端点字段：

id：端点标识，默认 `endpoint-<序号>`。
enabled：是否启用，默认 `true`。
method：HTTP 方法，默认 `POST`（自动转大写）。支持 GET/POST/PUT/DELETE/PATCH。
path：相对 `base_path` 的子路径，默认 `/<id>`。实际访问路径为 `base_path + path`。
tokens：该端点专属 token 列表，为空则回退 `auth.tokens`。
actions：命中后依次执行的动作列表。

实际监听路径 = `base_path` + 端点 `path`。例如 `base_path: /webhook` 且端点 `path: /send-group`，则完整路径为 `/webhook/send-group`。

发送群消息示例配置：

```yaml
incoming:
  endpoints:
    - id: "send-group"
      enabled: true
      method: "POST"
      path: "/send-group"
      actions:
        - type: "send_group_message"
          group_id: "${request.body.group_id}"
          message: "${request.body.message ?: \"\"}"
        - type: "reply"
          status: 200
          body:
            success: true
            message: "group message queued"
```

调用（替换为真实 token、群号）：

```bash
curl -X POST http://127.0.0.1:18080/webhook/send-group \
  -H "Authorization: Bearer change-me-token" \
  -H "Content-Type: application/json" \
  -d '{"group_id": 123456789, "message": "hello from webhook"}'
```

成功时返回 reply 指定的状态码与 body：

```json
{ "success": true, "message": "group message queued" }
```

关于 reply 与响应规则：

- reply 用于显式控制 HTTP 响应。业务动作（非 reply）全部成功时，由最后一个 reply 接管响应，可返回自定义状态码（含 4xx/5xx）与 body。
- 任一业务动作失败时，忽略 reply，返回 `500` 与结果摘要，且失败动作的对外 message 统一脱敏为 `action failed`。
- 没有 reply 且业务全部成功时返回 `200` 与结果摘要。

因此可以用 reply 返回自定义校验失败响应，例如：

```yaml
- type: "reply"
  status: 400
  body:
    success: false
    error: "group_id is required"
```

常见响应状态码：`401` 鉴权失败（缺少或错误的 Bearer Token），`404` 路径或方法未匹配到端点，`413` 请求体超过 `max_body_bytes`，`500` 业务动作执行失败或内部异常。

## Outgoing：消息事件转发到外部

路由定义在 `outgoing.routes` 下，每个路由字段：

id：路由标识，默认 `route-<序号>`。
enabled：是否启用，默认 `true`（默认配置中的示例路由为 `false`，启用前请先填好真实 URL）。
events：匹配的事件类型列表，可选 `group_message`、`friend_message`。为空表示不限事件类型。
groups：匹配的群号列表。非空时仅群消息且群号命中才匹配。
friends：匹配的好友号列表。非空时仅好友消息且好友号命中才匹配。
senders：匹配的发送者 QQ 列表。
message：消息文本匹配器，含 `contains` / `starts_with` / `ends_with` / `regex` 四类，每类是列表，任一命中即视为该类匹配；多类之间需同时满足。无效正则会在加载时被忽略并告警。
condition：布尔表达式条件，详见模板章节。
actions：匹配后执行的动作列表。

所有匹配条件之间是「与」关系：配置了的条件都要满足，未配置（空）的条件跳过。

群消息转发示例：

```yaml
outgoing:
  routes:
    - id: "group-message-forward"
      enabled: true
      events: ["group_message"]
      groups: [123456789]
      message:
        contains: ["关键词"]
      actions:
        - type: "http_request"
          method: "POST"
          url: "https://example.invalid/webhook/group-message"
          headers:
            Authorization: "Bearer change-me-token"
          body:
            type: "${event.type}"
            bot_id: "${event.botId}"
            group_id: "${event.groupId}"
            sender_id: "${event.senderId}"
            sender_name: "${event.senderName}"
            message: "${event.messageText}"
```

当机器人在群 `123456789` 收到含「关键词」的消息时，会向目标 URL 发送上述 JSON。`http_request` 的请求/连接/读取超时分别为 15s / 10s / 15s。

可用的事件变量：`event.type`、`event.botId`、`event.groupId`、`event.friendId`、`event.senderId`、`event.senderName`、`event.messageText`、`event.timestamp`。

## 模板表达式

在动作参数的字符串值中使用 `${...}` 引用上下文变量。表达式只做变量替换与基础条件判断，不执行任意脚本。

变量路径（点号逐层取值）：

- Incoming 上下文：`request.method`、`request.path`、`request.query.<名>`、`request.headers.<名>`、`request.body`、`request.body.<字段>`、`request.remoteHost`。
- Outgoing 上下文：`event.type`、`event.botId`、`event.groupId`、`event.friendId`、`event.senderId`、`event.senderName`、`event.messageText`、`event.timestamp`。

默认值：用 `?:` 提供回退，左侧解析为 null 时取右侧字面量。

```text
${request.body.message ?: "默认内容"}
```

字面量：支持双/单引号字符串、`true`/`false`、`null`、整数、浮点数。

布尔条件（用于路由 `condition` 字段）：

- 比较：`==`、`!=`。
- 逻辑：`&&`、`||`、`!`，支持括号分组。
- 字符串函数：`contains(a, b)`、`startsWith(a, b)`、`endsWith(a, b)`。

示例：

```yaml
condition: "contains(${event.messageText}, \"紧急\") && ${event.senderId} != 10000"
```

行为细节：当 `templates.enable_expressions` 为 `false` 时模板原样输出；未解析到变量时，`strict_missing_variables: true` 保留原始 `${...}` 文本，否则替换为空串。表达式有递归深度上限（32 层）以防止异常嵌套。

## 动作类型参考

每个动作含 `type`、`enabled`（默认 `true`）及各自参数。参数值均支持模板表达式。

send_group_message：发送群消息。参数 `group_id`（必填，需可解析为数字）、`message`（文本）。
send_friend_message：发送好友消息。参数 `friend_id`（必填，需可解析为数字）、`message`（文本）。
http_request：发起 HTTP 请求。参数 `url`（必填）、`method`（默认 `POST`）、`headers`（键值对）、`body`（任意结构，序列化为 JSON）。
reply：仅对 incoming 有意义，构造 HTTP 响应。参数 `status`（默认 `200`）、`body`（任意结构）。
execute_command：以控制台身份执行 mirai 命令。参数 `command`（必填）。默认禁用，需同时满足 `security.allow_command_execution: true` 与该动作 `enabled: true` 才会执行，且每次执行都会记录审计日志。

动作可内联定义，也可在顶层 `actions` 处命名后通过字符串或 `ref` 引用：

```yaml
actions:
  notify-admin:
    type: "send_group_message"
    group_id: 123456789
    message: "收到 webhook 调用"

incoming:
  endpoints:
    - id: "ping"
      path: "/ping"
      actions:
        - "notify-admin"          # 直接用名称引用
        - ref: "notify-admin"     # 或用 ref 引用
```

## 命令参考

命令需要 `kim.hhhhhy.x.webhook:admin` 权限。

`/xwebhook reload`：重载 `webhook_config.yml` 并重启 WebHook 服务。回执会显示加载到的 incoming/outgoing 数量与服务状态；若配置解析失败，会提示「已回退安全默认配置」并附带错误摘要，便于第一时间发现 YAML 写错。

`/xwebhook status`：查看服务运行状态、监听地址、端点与路由数量，以及最近的配置错误和服务错误。

execute_command 动作另有独立权限 `kim.hhhhhy.x.webhook:execute-command`。

## 安全建议

- 生产环境务必替换默认 token `change-me-token`，使用足够长的随机串。
- 默认仅监听 `127.0.0.1`。需要公网/局域网访问时再改 `host`，并确保所有启用端点都有 token。
- 不要在绑定非回环地址时开启 `allow_empty_for_localhost`，否则等于暴露未鉴权接口（启动会有安全告警）。
- `execute_command` 风险高，默认双重关闭；确需使用时再同时打开全局开关与动作开关，并留意审计日志。
- 按需设置 `max_body_bytes` 限制请求体大小，防止超大请求。

## 故障排查

服务未启动：执行 `/xwebhook status` 查看 `server error`。常见原因是启用了无 token 端点而未开启 `allow_empty_for_localhost`，或端口被占用。
请求返回 401：检查 `Authorization: Bearer <token>` 头是否携带且与配置一致。
请求返回 404：确认请求路径等于 `base_path + 端点 path`，且 HTTP 方法与端点 `method` 一致、端点 `enabled` 为 `true`。
请求返回 413：请求体超过 `security.max_body_bytes`，调大限制或减小请求体。
请求返回 500：某个业务动作执行失败（如群号不存在、机器人未登录、目标 URL 不可达）。具体原因见控制台日志，对外响应已脱敏。
配置改了不生效：执行 `/xwebhook reload`，并查看回执是否提示配置错误。





