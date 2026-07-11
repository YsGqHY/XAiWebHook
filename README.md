# XAiWebHook

XAiWebHook 是一个 [mirai-console](https://github.com/mamoe/mirai) Kotlin 插件，通过一份 YAML 完成 WebHook 的双向配置：

- Incoming：外部 HTTP 请求触发机器人动作（发送群/好友消息、网页截图、转发 HTTP、执行命令、返回自定义响应）。
- Outgoing：mirai 群聊/好友消息事件按规则匹配后执行 HTTP 转发、网页截图等动作。

所有路由、鉴权、模板、安全策略都写在 `webhook_config.yml`，无需改动源码。

## 首期能力边界

已支持：

- Incoming：Bearer Token 鉴权、自定义 method/path、JSON body 解析、群/好友消息发送、网页元素截图发送、HTTP JSON 响应（reply）。
- Outgoing：群消息、好友消息事件，按事件类型 / 群号 / 好友号 / 发送者 / 消息文本（contains/startsWith/endsWith/regex）/ 布尔条件匹配，支持个人、管理员、路由全局冷却后执行 `http_request`、`send_webpage_screenshot` 等动作。
- 表达式模板：变量替换、默认值、字符串函数、基础布尔条件。
- 命令：`/xwebhook reload`、`/xwebhook status`。

暂未支持（计划中，勿在配置里使用）：HMAC 签名鉴权、成员进退群等非消息事件、At / 引用等完整消息链、HTTP 重试、route test 命令、配置 schema 校验、限流与审计日志。

## 环境要求

- JDK 11（插件 JVM 目标为 Java 11）。
- mirai-console 2.16.0 运行环境。
- 使用网页截图动作时需要可用浏览器。Windows 默认示例复用系统 Microsoft Edge；其他环境可配置 Chrome/Edge channel、浏览器可执行路径，或预先安装 Playwright Chromium。

## 构建与安装

使用项目自带的 Gradle wrapper 构建：

```bash
# Windows
./gradlew.bat build

# Linux / macOS
./gradlew build
```

如果运行机没有可复用的 Chrome/Edge，可显式安装 Playwright Chromium：

```bash
# Windows
./gradlew.bat playwrightInstallChromium

# Linux / macOS
./gradlew playwrightInstallChromium
```

插件运行时不会自动下载浏览器；缺少浏览器时动作会失败并在 `/xwebhook status` 中记录 browser error。

构建产物位于 `build/mirai/XAiWebHook-0.1.0.mirai2.jar`。将该 jar 放入 mirai-console 的 `plugins/` 目录，然后启动或重启 mirai-console。

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
browser:
  enabled: false
  engine: "chromium"
  channel: "msedge"
  executable_path: ""
  headless: true
  viewport_width: 1440
  viewport_height: 1000
  timeout_ms: 30000
  optional_step_timeout_ms: 1000
  allowed_hosts: []
  max_screenshot_bytes: 10485760
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
browser.enabled：网页截图动作总开关，默认 `false`。
browser.engine：浏览器引擎，默认 `chromium`，可选 `chromium` / `firefox` / `webkit`。
browser.channel：浏览器发行通道；Windows 默认示例使用 `msedge`。配置 `executable_path` 时优先使用指定文件。
browser.headless：是否无界面运行，默认 `true`。
browser.viewport_width / viewport_height：截图浏览器视口，默认 `1440x1000`。
browser.timeout_ms：导航、元素和动作默认超时，默认 `30000` 毫秒。
browser.optional_step_timeout_ms：可选登录步骤查找元素的等待时间，默认 `1000` 毫秒。
browser.allowed_hosts：允许主页面导航的域名白名单，支持精确域名和 `*.example.com` 子域通配；为空时拒绝所有网页导航。
browser.max_screenshot_bytes：单张截图最大字节数，默认 `10485760`（10 MiB）。
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
cooldown：可选的路由级指令冷却；未配置时不启用，详见下文。
actions：匹配并通过冷却检查后执行的动作列表。

所有匹配条件之间是「与」关系：配置了的条件都要满足，未配置（空）的条件跳过。

### Outgoing 指令冷却

`cooldown` 状态只在内存中保存，并按路由隔离。普通用户使用 `personal_ms`，群管理员/群主或持有 `kim.hhhhhy.x.webhook:admin` 权限的用户使用 `administrator_ms`；两者还会共同受该路由的 `global_ms` 约束。时长范围均为 `0-604800000` 毫秒，`0` 表示关闭对应维度，`administrator_ms` 未配置时继承 `personal_ms`。

```yaml
outgoing:
  routes:
    - id: "monitor-command"
      events: ["group_message"]
      message:
        contains: ["监控截图"]
      cooldown:
        enabled: true
        personal_ms: 60000
        administrator_ms: 10000
        global_ms: 5000
        notify: true
        message: "指令冷却中，请在 ${cooldown.remainingSeconds} 秒后重试。"
      actions:
        - ref: "geek2api-monitor-screenshot"
```

冷却在动作开始前原子占用，因此并发发送同一指令只会有一个请求通过；动作后续失败也不会退还本次冷却。若个人/管理员冷却与全局冷却同时生效，提示最长剩余时间。`notify: false` 或空 `message` 可静默拦截。

冷却提示可使用 `${cooldown.routeId}`、`${cooldown.scope}`、`${cooldown.remainingMillis}`、`${cooldown.remainingSeconds}`。其中 `scope` 为 `personal`、`administrator` 或 `global`。

管理员同时拥有 `kim.hhhhhy.x.webhook:cooldown-bypass` 时无视全部冷却，并且不会读取或写入冷却状态。这里的管理员必须是群管理员/群主，或已拥有插件 `admin` 权限；普通成员只授予 `cooldown-bypass` 不生效。群内可通过 mirai-console 权限主体 `m<群号>.<QQ号>` 授权，例如：

```text
/permission permit m123456789.987654321 kim.hhhhhy.x.webhook:cooldown-bypass
```

好友消息没有群角色；如需让好友管理员绕过，需先按 mirai-console 权限规则授予插件 `admin`，再授予 `cooldown-bypass`。执行 `/xwebhook reload` 或停止插件会清空全部内存冷却。

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

## 网页登录与截图发送

`send_webpage_screenshot` 用 Playwright 执行受限的浏览器步骤，截取 CSS/XPath 对应的网页元素并作为 mirai 图片发送。推荐把动作定义在顶层 `actions`，再由 outgoing 消息路由和 incoming endpoint 共同引用。

可直接复制为运行时 `webhook_config.yml` 的完整配置位于 [`examples/webhook_config.geek2api-cli-bridge.yml`](examples/webhook_config.geek2api-cli-bridge.yml)，包含系统浏览器 CLI Bridge 授权、token 轮询与刷新、incoming、群聊 outgoing、好友 outgoing、安全和日志配置。

Geek2Api 监控页面核心示例（默认配置中也有带完整中文注释的版本）：

```yaml
browser:
  enabled: true
  engine: "chromium"
  channel: "msedge"
  headless: true
  allowed_hosts: ["hk3.geek2api.com"]

actions:
  geek2api-monitor-screenshot:
    type: "send_webpage_screenshot"
    enabled: true
    session_key: "geek2api-monitor"
    group_id: "${request.body.group_id}"
    friend_id: "${request.body.friend_id}"
    pending_message: "正在获取监控截图；首次使用请在系统浏览器中完成 Geek2Api 授权..."
    failure_message: "监控页面截图失败，请稍后重试。"
    auth:
      cli_bridge:
        start_url: "https://hk3.geek2api.com/api/v1/auth/cli-bridge/start"
        browser_url: "https://hk3.geek2api.com/cli-bridge"
        poll_url: "https://hk3.geek2api.com/api/v1/auth/cli-bridge/poll"
        profile_url: "https://hk3.geek2api.com/api/v1/user/profile"
        refresh_url: "https://hk3.geek2api.com/api/v1/auth/refresh"
        poll_interval_ms: 2500
        max_wait_ms: 300000
        refresh_before_expiry_seconds: 120
        retry_cooldown_ms: 60000
      local_storage:
        origin: "https://hk3.geek2api.com"
        key: "auth_token"
        refresh_token_key: "refresh_token"
        expires_at_key: "token_expires_at"
        user_key: "auth_user"
    steps:
      - op: "goto"
        url: "https://hk3.geek2api.com/monitor"
        wait_until: "commit"
      - op: "wait"
        selector: '//*[@id="app"]/div[2]/div[2]/main'
        state: "visible"
      # 主区域在骨架屏阶段已可见，继续等待真实监控卡片或合法空状态
      - op: "wait"
        selector: "(//*[@id='app']/div[2]/div[2]/main//button[@type='button' and contains(concat(' ', normalize-space(@class), ' '), ' group ') and contains(concat(' ', normalize-space(@class), ' '), ' min-h-[280px] ')] | //*[@id='app']/div[2]/div[2]/main//div[contains(concat(' ', normalize-space(@class), ' '), ' empty-state ')])[1]"
        state: "visible"
        timeout_ms: 90000
    screenshot:
      selector: '//*[@id="app"]/div[2]/div[2]/main'
      timeout_ms: 30000

outgoing:
  routes:
    - id: "geek2api-monitor-command"
      enabled: true
      events: ["group_message"]
      groups: [123456789]
      message:
        contains: ["监控截图"]
      cooldown:
        enabled: true
        personal_ms: 60000
        administrator_ms: 10000
        global_ms: 5000
        notify: true
        message: "监控截图指令冷却中，请在 ${cooldown.remainingSeconds} 秒后重试。"
      actions:
        - ref: "geek2api-monitor-screenshot"

incoming:
  endpoints:
    - id: "webpage-screenshot"
      enabled: true
      method: "POST"
      path: "/webpage-screenshot"
      actions:
        - ref: "geek2api-monitor-screenshot"
        - type: "reply"
          status: 200
          body:
            success: true
```

CLI Bridge 模式依次执行：`POST /api/v1/auth/cli-bridge/start` 创建 300 秒会话；在 mirai 所在系统的默认浏览器打开 `/cli-bridge?bridge_id=...`；每 2.5 秒向 `POST /api/v1/auth/cli-bridge/poll` 提交 `{bridge_id, poll_secret}`；首次收到 `authorized` 后立即保留一次性交付的 access/refresh token，再用 `GET /api/v1/user/profile` 补齐 `auth_user`。浏览器 URL 只包含公开的 `bridge_id`，`poll_secret` 不会进入 URL、日志、状态命令或聊天消息。

首次授权必须在 `start` 返回的 `expires_in` 内完成，默认上限为 300 秒。登录发生在真实系统浏览器，因此账号密码、Google/GitHub 等快捷登录、验证码和网页 2FA 都由站点正常处理；插件不生成或绕过验证码。`browser.headless` 只控制 Playwright 截图浏览器，不会阻止系统授权浏览器显示。

授权成功后插件在首次导航前写入与 Geek2Api 前端一致的四项 localStorage：`auth_token`、`refresh_token`、`token_expires_at` 和 `auth_user`。access token 临近过期时向 `POST /api/v1/auth/refresh` 提交 `{refresh_token}`，并用旋转后的 access/refresh token 重建 BrowserContext；页面自身若刷新了 localStorage，插件也会同步最新 token。

hk3 的监控主区域在 API 数据返回前就会显示六张 `animate-pulse` 骨架卡片，因此只等待主 XPath 可见会过早截图。默认实例会继续等待真实 `button.group.min-h-[280px]` 监控卡片或 `.empty-state` 合法空状态；任一完成态出现时 Vue 已结束首次数据加载，骨架分支也已卸载。生产环境已有运行时 YAML 不会被新 JAR 自动覆盖，需要同步增加这个等待步骤后再执行 `/xwebhook reload`。

CLI Bridge 模式必须配置 `session_key`，命名 action 未显式设置时会自动使用 action ID。一次授权失败后默认冷却 60 秒，避免重复调用 start 触发 20 次/分钟限流；普通页面、XPath 或截图失败不会清除有效认证会话。当前 token pair 只保存在插件内存中，执行 `/xwebhook reload`、停服或进程退出后，下一次截图需要重新在系统浏览器授权。

旧的 `auth.login` 账号密码/2FA/TOTP 流程，以及 `auth.token` / `auth.token_env`、限定 host 请求头、Cookie 和 `/auth/me` bootstrap 均保留用于已有配置兼容。`token/token_env`、`login`、`cli_bridge` 三种认证源必须且只能配置一种；hk3 默认示例改用 CLI Bridge，不再受纯 email/password 请求的 `CAP_VERIFICATION_FAILED` 阻断。

outgoing 触发时，未显式配置有效 `group_id` / `friend_id` 会自动把截图发回当前群或好友。incoming 没有事件会话，请求体必须提供其中一个目标，例如 `{"group_id": 123456789}`。两个目标不能同时有效。

浏览器步骤支持：`goto` 访问 URL；`fill` 填写输入框；`click` 点击元素；`wait` 等待 `visible` / `attached` / `hidden` / `detached`；`wait_url` 等待 URL 匹配。步骤最多 32 个，不支持执行自定义 JavaScript。

`goto.wait_until` 控制 Playwright 导航等待阶段，可选 `commit`、`domcontentloaded`、`load`、`networkidle`，默认 `commit`。`commit` 只要求目标服务器已开始返回文档，DNS、连接、TLS、无响应或未提交导航仍会正常超时失败；页面是否真正可截图由后续 `wait` 和 `screenshot.selector` 精确验证。对于会持续请求、重定向或延迟触发生命周期事件的 SPA，不建议把 `domcontentloaded` / `networkidle` 当作业务就绪条件。

相同 `session_key` 会在内存中复用 BrowserContext 和 token pair。每次执行使用独立 Page，`/xwebhook reload` 和插件停止都会清理内存登录态；旧 `auth.login` 文件模式的邮箱和密码只保存在现有运行时 YAML 中，插件不会另行持久化。

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

行为细节：当 `templates.enable_expressions` 为 `false` 时模板原样输出；未解析到变量时，`strict_missing_variables: true` 保留原始 `${...}` 文本，否则替换为空串。incoming 的 `send_group_message` / `send_friend_message` 中，单个 `${request.body.xxx}` 变量实际内容超过 200 字符时，会在该变量位置渲染为 Markdown 图片消息段，模板中的前后文本仍保留为普通文本。表达式有递归深度上限（32 层）以防止异常嵌套。

## 动作类型参考

每个动作含 `type`、`enabled`（默认 `true`）及各自参数。参数值均支持模板表达式。

send_group_message：发送群消息。参数 `group_id`（必填，需可解析为数字）、`message`（文本）。
send_friend_message：发送好友消息。参数 `friend_id`（必填，需可解析为数字）、`message`（文本）。
http_request：发起 HTTP 请求。参数 `url`（必填）、`method`（默认 `POST`）、`headers`（键值对）、`body`（任意结构，序列化为 JSON）。
send_webpage_screenshot：按 `steps` 执行网页访问。`auth.cli_bridge` 支持系统浏览器授权、一次性 token 轮询、profile 用户补全、refresh token 轮换和四项 localStorage 登录态；兼容模式仍支持 `auth.login` 账号密码/2FA、AccessToken 请求头、Cookie 与 `auth.bootstrap`。最后截取 `screenshot.selector` 元素并发送。可选参数含 `session_key`、`group_id`、`friend_id`、`pending_message`、`failure_message`、`message`。
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

`/xwebhook status`：查看服务运行状态、监听地址、端点与路由数量、浏览器 engine/channel，以及最近的配置、服务和浏览器错误。

execute_command 动作另有独立权限 `kim.hhhhhy.x.webhook:execute-command`。Outgoing 冷却绕过权限为 `kim.hhhhhy.x.webhook:cooldown-bypass`，且仅对群管理员/群主或插件管理员生效。

## 安全建议

- 生产环境务必替换默认 token `change-me-token`，使用足够长的随机串。
- 默认仅监听 `127.0.0.1`。需要公网/局域网访问时再改 `host`，并确保所有启用端点都有 token。
- 不要在绑定非回环地址时开启 `allow_empty_for_localhost`，否则等于暴露未鉴权接口（启动会有安全告警）。
- `execute_command` 风险高，默认双重关闭；确需使用时再同时打开全局开关与动作开关，并留意审计日志。
- 按需设置 `max_body_bytes` 限制请求体大小，防止超大请求。
- 网页截图功能默认关闭。启用后只把必要域名加入 `browser.allowed_hosts`。Geek2Api 默认使用 CLI Bridge，不需要在 YAML 保存账号密码；兼容 `auth.login` 的密码、TOTP 密钥和 Token 仍应限制文件权限或使用环境变量。
- `send_webpage_screenshot` 不支持自定义 JavaScript，浏览器登录态只保存在内存中，并会在 reload/停服时清理。

## 故障排查

服务未启动：执行 `/xwebhook status` 查看 `server error`。常见原因是启用了无 token 端点而未开启 `allow_empty_for_localhost`，或端口被占用。
请求返回 401：检查 `Authorization: Bearer <token>` 头是否携带且与配置一致。
请求返回 404：确认请求路径等于 `base_path + 端点 path`，且 HTTP 方法与端点 `method` 一致、端点 `enabled` 为 `true`。
请求返回 413：请求体超过 `security.max_body_bytes`，调大限制或减小请求体。
请求返回 500：某个业务动作执行失败（如群号不存在、机器人未登录、目标 URL 不可达）。具体原因见控制台日志，对外响应已脱敏。
网页截图提示失败：执行 `/xwebhook status` 查看 `browser error`，确认 `browser.enabled`、`allowed_hosts`、浏览器 channel/路径和截图 XPath。步骤或截图超时时会附带最终 URL 与页面标题。系统不会自动下载缺失的 Playwright 浏览器。
`goto` 报 `waiting until "domcontentloaded"` 超时，但错误中的最终 URL 和标题已是目标页面：升级到包含 `goto.wait_until` 的版本；新版本对未配置该字段的旧 YAML 默认使用 `commit`，无需等待不稳定的 SPA 生命周期事件。后续元素等待仍会阻止未真正加载完成的页面截图。
截图仍是灰色骨架卡片：现有运行时 YAML 可能只有主 `<main>` 的 visible 等待；同步默认实例中的真实监控卡片 XPath 等待步骤，执行 `/xwebhook reload` 后重试。
`CLI bridge could not open the system browser`：mirai 所在系统没有可用桌面浏览器集成；控制台警告会给出只含 `bridge_id` 的安全授权 URL，插件会继续轮询，可在同一桌面环境立即手动打开。
`CLI bridge login timed out`：未在 start 返回的有效期内完成浏览器授权；等待 60 秒冷却后重新触发截图。
`CLI bridge poll failed: HTTP 400`：桥接会话已过期或 token 已被一次性取走，需要重新 start；不要复用旧 `bridge_id`。
`CLI bridge refresh failed`：refresh token 已失效、被重用撤销或接口不可用；对应内存 session 会被清理，下一次触发将重新打开浏览器授权。
`CLI bridge retry cooldown active`：上一次授权失败后仍在冷却期；默认最多等待 60 秒，插件不会在 start 的 20 次/分钟限流下循环重试。
`credential login failed: HTTP 400 (CAP_VERIFICATION_FAILED...)`：仅适用于旧 `auth.login` 兼容模式；hk3 要求网页验证码，建议改用默认 CLI Bridge。
`credential login requires 2FA` / `credential refresh failed`：仅适用于旧账号密码兼容模式，检查 TOTP 环境变量或重新登录。
`auth bootstrap failed` / `auth bootstrap response path not found`：仅适用于旧 Token 兼容模式，检查 Token 与 bootstrap JSON 路径。
配置改了不生效：只修改 YAML 时执行 `/xwebhook reload`；修改环境变量后必须重启 mirai-console 进程。





