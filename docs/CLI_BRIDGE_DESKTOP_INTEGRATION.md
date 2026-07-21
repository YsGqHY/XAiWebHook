# 桌面/CLI 客户端登录对接文档（CLI Bridge）

> 面向：需要「唤起系统浏览器让用户登录、再把登录凭证取回本地」的桌面 App / CLI 工具。
> 机制：device-code 风格的登录桥（RFC 8628 思路），**已在生产验证通过**。
> 凭证：拿回的是网页登录态同款 **JWT**（access_token + refresh_token），可直接调用需鉴权接口，并能自行刷新。
>
> XAiWebHook 默认 Geek2Api 截图已使用免登录的 `/status` 公共页面；只有访问 `/keys`、`/monitor` 等受保护页面时才需要本文的 CLI Bridge 方案。

---

## 1. 为什么用这套（而不是自己接 OAuth）

桌面 App 内嵌的 webview 无法完成基于浏览器 Cookie / pending-session 的网页 OAuth 流程（Google/GitHub/LinuxDo 等快捷登录都依赖浏览器 Cookie 承载 state/PKCE）。CLI Bridge 把登录**委托给系统浏览器**：用户在真正的浏览器里用**任意方式**（账号密码 / Google / GitHub / …）登录，登录态再安全地交回桌面端。

- **Google/GitHub 登录**回答「用户是谁」——是登录方式，属于内层。
- **CLI Bridge**回答「桌面 App 怎么拿到网页里已登录好的凭证」——是凭证移交，属于外层。
- 两者正交、配合使用；用户走哪种登录方式，CLI Bridge 都能把结果 JWT 交回客户端。

---

## 2. 端到端时序

```
桌面客户端                          系统浏览器（网页端）                后端
   │ 1. POST /auth/cli-bridge/start
   │◀──────────────── {bridge_id, poll_secret, expires_in=300} ─────────────│
   │
   │ 2. 打开系统浏览器：
   │    https://<站点>/cli-bridge?bridge_id=<bridge_id>
   │                                   │ 3. 用户登录（账号密码 / Google / GitHub…）
   │                                   │    页面自动 POST /auth/cli-bridge/authorize
   │                                   │    (带用户 JWT + bridge_id)
   │                                   │    → 服务端为该会话绑定一对全新 JWT
   │                                   │◀── 显示「授权成功，可关闭窗口」
   │
   │ 3'. 轮询 POST /auth/cli-bridge/poll  {bridge_id, poll_secret}
   │       授权前 → {status:"pending"}，继续轮询
   │◀──────── 授权后（一次性）→ {status:"authorized", access_token, refresh_token, expires_in} ──────│
   │
   │ 4. 落地 token；后续用 Authorization: Bearer <access_token> 调业务接口
   │ 5. access_token 到期前用 refresh_token 调 /auth/refresh 换新的一对
```

关键约束（务必遵守）：
- **会话 TTL = 300 秒**：用户须在 5 分钟内完成授权，否则 `bridge_id` 过期，需重新 `start`。
- **令牌一次性交付**：`poll` 返回 `authorized`（连同 token）后，服务端**立即删除会话**；再次 `poll` 会得到 `400 会话已过期`。客户端必须在第一次拿到时落地保存。
- **poll_secret 是取件密钥**：只有它能取回令牌（服务端常量时间比对）。它随 `start` 返回，**只存在客户端内存**，不要泄露、不要进浏览器 URL。
- 浏览器打开的 URL 里**只带 `bridge_id`**（公开标识），**绝不带 poll_secret**。

---

## 3. 接口详情

所有请求/响应体为 JSON。统一响应信封：

```json
{ "code": 0, "message": "success", "data": { ... } }
```

`code == 0` 为成功；非 0 或 HTTP 4xx/5xx 为失败，`message` 为原因。基址示例：`https://hk5.geek2api.com`，前缀 `/api/v1`。XAiWebHook 的 `send_webpage_screenshot.base_urls` 可配置 `https://hk5.geek2api.com`、`https://hk.geek2api.com`、`https://hk2.geek2api.com`、`https://hk4.geek2api.com`；当前候选成功时保持使用，任意请求/导航错误时切换到下一条。

### 3.1 发起桥接会话
`POST /api/v1/auth/cli-bridge/start` · 公开 · 限流 **20 次/分钟/IP**

请求体：无（`{}` 即可）。

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `bridge_id` | string | 会话标识（放进浏览器 URL）|
| `poll_secret` | string | 取件密钥（仅客户端内存持有）|
| `expires_in` | int | 会话有效期，秒（当前 300）|

### 3.2 浏览器授权页（客户端不直接调）
用系统浏览器打开：

```
https://<站点>/cli-bridge?bridge_id=<bridge_id>
```

该页 `requiresAuth`：用户未登录会先跳登录页，登录后自动回到本页并调用 `authorize`。桌面端**不需要**自己调 authorize，只需打开这个 URL。

> `POST /api/v1/auth/cli-bridge/authorize`（JWT 必需，body `{bridge_id}`）由网页 SPA 完成，此处仅作说明：它取当前登录用户，生成一对**全新** JWT 绑定到会话。

### 3.3 轮询取回令牌
`POST /api/v1/auth/cli-bridge/poll` · 公开 · 限流 **120 次/分钟/IP**

请求体：

```json
{ "bridge_id": "<bridge_id>", "poll_secret": "<poll_secret>" }
```

响应 `data`：

| 情形 | 返回 |
|---|---|
| 尚未授权 | `{ "status": "pending" }` |
| 已授权（**一次性**）| `{ "status": "authorized", "access_token": "...", "refresh_token": "rt_...", "expires_in": 86400 }` |
| poll_secret 错 | HTTP **401** `invalid poll secret` |
| bridge_id 不存在 / 已过期 / 已被取走 | HTTP **400** `桥接会话已过期，请重新发起登录` |

建议轮询间隔 **2–3 秒**（限流上限 120/min），总时长以 `expires_in`（300s）为上界。

### 3.4 刷新令牌
`POST /api/v1/auth/refresh` · 公开 · 限流 **30 次/分钟/IP**

请求体：

```json
{ "refresh_token": "rt_..." }
```

响应 `data`：`access_token` / `refresh_token`（**会轮换，需覆盖保存**）/ `expires_in` / `token_type="Bearer"`。

> refresh token 采用 family 轮换 + 重用检测：每次刷新都会**换发新的 refresh_token**，旧的作废；若检测到旧 token 被重用（可能被盗），整条 family 会被撤销。**务必用返回的新 refresh_token 覆盖本地存的旧值。**

### 3.5 登出（可选，撤销 refresh token）
`POST /api/v1/auth/logout` · 公开 · body `{ "refresh_token": "rt_..." }`（可空）。用于本地退出时主动撤销服务端 refresh token。

### 3.6 用令牌调业务接口
所有需鉴权接口加请求头：

```
Authorization: Bearer <access_token>
```

自检示例：`GET /api/v1/user/profile` → 返回当前用户（id/email/role/balance/allowed_groups…）。

---

## 4. 令牌有效期

| 令牌 | 默认有效期 | 说明 |
|---|---|---|
| access_token（JWT, HS256）| **24 小时**（`expires_in=86400`）| 每次业务请求携带；改密码会使旧 token 全部失效 |
| refresh_token（`rt_` 前缀）| **30 天** | 换发 access；每次刷新轮换 |

客户端建议：在 access_token 到期前（如提前 2 分钟）用 refresh_token 静默刷新。

---

## 5. 客户端状态机 & 实现要点

```
IDLE ──start()──▶ WAIT_BROWSER ──打开URL──▶ POLLING ──authorized──▶ AUTHED
   ▲                   │                        │
   └──── 过期/超时/错误 ◀┴────────────────────────┘
AUTHED ──access将过期──▶ refresh() ──成功──▶ AUTHED（覆盖两个 token）
                                   └─失败──▶ IDLE（需重新登录）
```

- **TTL 兜底**：轮询总时长 ≤ `expires_in`；超时提示用户「登录超时，请重试」并回到 IDLE。
- **一次性落地**：第一次 `poll` 拿到 `authorized` 立即把两个 token 落地到安全存储（见 §6），再做后续动作。
- **限流礼貌**：轮询 2–3s/次；不要 1s 猛刷（上限 120/min）。
- **错误区分**：401=poll_secret 不对（客户端 bug，别重试）；400=会话过期/已取走（重新 start）；网络错误=退避重试。
- **幂等**：`authorize` 幂等（重复授权返回 already authorized），但 `poll` 的令牌交付**不幂等**（只交一次）。

XAiWebHook 在 Playwright 页面中复用同一语义：仅当业务请求携带当前 access token 且响应为 HTTP 401 时，强制 refresh 一次、立即落盘轮换后的 token pair，并重跑当前截图或文本查询一次。refresh 失败时才删除对应 session cache 并重新授权；普通页面跳转、XPath/DOM、截图、网络和非 401 错误不会清缓存。刷新或重新授权后仍收到 401 会直接返回错误，不循环。

---

## 6. 安全注意

- **传输**：生产必须走 HTTPS。
- **poll_secret**：仅客户端内存，绝不写进浏览器 URL / 日志 / 磁盘。
- **token 存储**：refresh_token 是长期凭证，优先使用系统密钥库（macOS Keychain / Windows Credential Manager / libsecret），不要明文落配置文件；日志里对 token 打码。XAiWebHook 的 `browser.session_cache_enabled` 默认关闭；显式启用后，v2 缓存会把独立 token pair 与 Playwright storageState（Cookie/localStorage）写入受限目录，旧 v1 文件可兼容恢复。首次授权和每次 refresh 成功后都会立即原子覆盖新 access/refresh token，避免轮换后的旧 refresh token 留在磁盘；必须保护插件配置目录，安全要求较高时应保持关闭。
- **回调最小暴露**：本方案无需在本机开监听端口（区别于 loopback 重定向方案），无防火墙弹窗、攻击面更小。
- **登出**：本地退出时调 `/auth/logout` 撤销服务端 refresh token。

### 6.1 XAiWebHook 截图任务防重入

CLI Bridge 的 `start` 有限流，Playwright 登录、等待页面和截图也可能持续较长时间。XAiWebHook 同时开放 incoming、群聊和好友触发时，应为这些入口的 `actions` 列表配置相同的 `single_flight.key`，避免上一轮截图未完成时再次创建授权会话或并发操作同一业务任务：

```yaml
single_flight:
  enabled: true
  key: "geek2api-monitor-screenshot"
  notify: true
  message: "Geek2Api 监控截图任务正在执行，请等待完成后再试。"
actions:
  - ref: "geek2api-monitor-screenshot"
```

相同 key 会在 incoming endpoint 和 outgoing route 之间共享执行锁。outgoing 重复触发时向当前会话发送提示；incoming 返回 HTTP `409` 和 `action group busy`。锁在整组 actions 完成、失败或取消后释放，`/xwebhook reload` 不会提前解锁仍在运行的任务。该机制只防止执行重叠，不替代 `cooldown` 的按时间限频。

若 Playwright 日志中的截图 Call log 停在 `waiting for fonts to load...`，说明页面字体请求未结束，而不是 CLI Bridge 登录失败。新版 XAiWebHook 会先按 `screenshot.font_wait_timeout_ms` 有界等待字体，默认 3 秒；超时后使用当前回退字体继续截图。可在截图配置中显式设置：

```yaml
screenshot:
  parts:
    - id: "keys-overview"
      url: "https://hk5.geek2api.com/keys"
      wait_until: "commit"
      selector: '//*[@id="app"]/div[2]/div[2]/main/div/div[1]/div/section/div[2]'
    - id: "monitor-status"
      url: "https://hk5.geek2api.com/monitor"
      wait_until: "commit"
      steps:
        - op: "wait"
          selector: '//*[@id="app"]/div[2]/div[2]/main'
          state: "visible"
      selector: '//*[@id="app"]/div[2]/div[2]/main'
  # 第一项和后续项在各自导航、steps 完成后都固定等待 3 秒再截图
  delay_before_ms: 3000
  hide_selectors: ["header.sticky"]
  timeout_ms: 30000
  font_wait_timeout_ms: 3000
  layout:
    horizontal_align: "center"
    gap_px: 0
  retry:
    enabled: true
    max_retries: 2
    delay_ms: 1000
```

Geek2Api 的已登录 `AppLayout` 使用 `header.sticky` 作为顶部 AppHeader。对较高的 `<main>` 执行元素截图时，浏览器滚动会让该 sticky header 覆盖截图顶部；`hide_selectors` 会仅在截图瞬间将它设为不可见，截图后自动恢复，不影响页面登录态或布局。

`screenshot.parts` 按配置顺序从上到下合成。上例第一项从已登录的 `/keys` 截取运行概览，第二项从 `/monitor` 截取状态区域；每项使用独立 Page，但共享同一 CLI Bridge 登录 BrowserContext。每项 `url` 和 `steps` 中的同源 URL 都随 `base_urls` 切换候选域名，单项还可覆盖公共前置等待、超时、字体等待和隐藏选择器。旧 `prepend_url` / `prepend_selector` 字段已由列表取代。

`delay_before_ms` 在每项 URL 导航和自定义 `steps` 完成后、等待最终 selector 并截图前执行，默认 `0`、范围 `0-300000` 毫秒。配置在 `screenshot` 外层时，第一项和后续项都会等待；任一 part 可用自己的 `delay_before_ms` 覆盖，设为 `0` 可仅跳过该项。固定等待不占用 `timeout_ms`，截图重试会重新执行完整列表及各项等待。

`layout.horizontal_align` 可选 `left`、`center`、`right`，`gap_px` 可在相邻截图之间保留 0-1000 像素透明间距。`max_retries` 是首次失败后的额外重试次数，`2` 表示最多 3 次完整列表尝试。重试仅覆盖浏览器 step/截图的超时与明确网络瞬断，不会重复执行 CLI Bridge 认证失败、配置错误或图片超限等确定性失败；同名 session 的 token/Cookie 会继续复用。

---

## 7. 参考实现（Python，已在生产验证通过）

> 依赖：仅标准库（`urllib`）。把 `BASE` 换成你的站点。此实现是本文档配套验证脚本的精简版。

```python
import json, time, sys, urllib.request, urllib.error, webbrowser

BASE = "https://hk5.geek2api.com"          # 你的站点
POLL_INTERVAL = 3                           # 秒
MAX_WAIT = 300                              # 秒，与服务端 TTL 对齐

def _post(path, body=None, token=None):
    req = urllib.request.Request(BASE + path,
                                 data=json.dumps(body or {}).encode(),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")

def login_via_browser():
    # 1) 发起会话
    st, resp = _post("/api/v1/auth/cli-bridge/start")
    if st != 200 or resp.get("code") != 0:
        raise RuntimeError("start 失败: %s" % resp)
    d = resp["data"]
    bridge_id, poll_secret = d["bridge_id"], d["poll_secret"]

    # 2) 打开系统浏览器（只带 bridge_id）
    url = "%s/cli-bridge?bridge_id=%s" % (BASE, bridge_id)
    print("请在浏览器完成登录授权：", url)
    webbrowser.open(url)

    # 3) 轮询取回令牌（一次性）
    deadline = time.time() + MAX_WAIT
    while time.time() < deadline:
        time.sleep(POLL_INTERVAL)
        st, resp = _post("/api/v1/auth/cli-bridge/poll",
                         {"bridge_id": bridge_id, "poll_secret": poll_secret})
        if st == 200 and resp.get("code") == 0:
            data = resp["data"]
            if data.get("status") == "authorized":
                return data          # {access_token, refresh_token, expires_in}
            # status == pending → 继续
        elif st == 401:
            raise RuntimeError("poll_secret 不匹配（客户端错误）")
        elif st == 400:
            raise RuntimeError("会话已过期，请重新登录")
        # 其它：网络抖动，继续退避轮询
    raise TimeoutError("登录超时（%ds 未授权）" % MAX_WAIT)

def refresh(refresh_token):
    st, resp = _post("/api/v1/auth/refresh", {"refresh_token": refresh_token})
    if st != 200 or resp.get("code") != 0:
        raise RuntimeError("刷新失败，需重新登录: %s" % resp)
    return resp["data"]              # 新的 {access_token, refresh_token, expires_in}

if __name__ == "__main__":
    tok = login_via_browser()
    print("access_token :", tok["access_token"][:20], "...")
    print("refresh_token:", tok["refresh_token"][:16], "...")
    print("expires_in   :", tok["expires_in"], "秒")

    # 自检：用令牌调鉴权接口（GET，带 Bearer）
    req = urllib.request.Request(BASE + "/api/v1/user/profile")
    req.add_header("Authorization", "Bearer " + tok["access_token"])
    with urllib.request.urlopen(req, timeout=15) as r:
        print("/user/profile:", json.loads(r.read().decode())["data"]["email"])
```

其它语言照抄这个状态机即可：`start → 开浏览器 → 轮询 poll → 落地 → 刷新`。

---

## 8. 错误码速查

| HTTP | 触发点 | 含义 | 客户端动作 |
|---|---|---|---|
| 200 `status:pending` | poll | 用户还没授权 | 继续轮询 |
| 200 `status:authorized` | poll | 授权完成（一次性）| 立即落地 token |
| 400 `桥接会话已过期` | poll | bridge 不存在/过期/已取走 | 重新 `start` |
| 401 `invalid poll secret` | poll | poll_secret 不对 | 检查客户端逻辑，勿盲目重试 |
| 401 | authorize | 无有效 JWT（用户未登录）| 网页侧自动跳登录，客户端无需处理 |
| 429 | 任意 | 触发限流 | 退避后重试（start 20/min、poll 120/min、refresh 30/min）|

---

## 9. 附：与「本地回环端口重定向」方案的取舍

当前实现是**轮询式**，桌面端**无需在本机开 HTTP 端口**、无防火墙弹窗、跨平台一致，实现最简单，推荐。若确有需求改成 RFC 8252 的 loopback 重定向（浏览器 302 回 `http://127.0.0.1:<port>/cb?code=…`），需后端新增「带 redirect 的授权 + 一次性 code 换 token」接口 + PKCE，前端授权页改 302，本文档不覆盖该变体。
