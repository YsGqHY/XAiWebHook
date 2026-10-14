---
skill: explore-develop
date: 2026-10-12 20:46
project: XAiWebHook
scope: model-plaza-request
status: partial
narrator: NarraFork
---

# Explore Develop 调用记录：model-plaza-request

## 调用背景

- 用户目标：在现有“模型”“分组”命令之外增加“请求”命令，在 `/model-plaza` 页面按模型名模糊匹配请求记录，返回吞吐、延迟和成功率图表截图。
- 项目路径：E:\Desktop\IDEA\XAiWebHook
- 本次范围：Model Plaza outgoing 路由、浏览器采集、Mirai 普通图文与合并转发消息、默认配置、测试和说明。
- 明确约束：不访问 `/model-plaza` 以外的业务页面；不提交真实 token、真实群号或真实公网地址；缺失字段保留模型并显示“暂无数据”；不新增任意脚本执行能力。

## 项目快照

- 技术栈：Kotlin 1.9.22、Gradle、mirai-console 2.16.0、Playwright 1.60.0、SnakeYAML、Kotlin Test。
- 运行入口：`./gradlew.bat test`、`./gradlew.bat build`；outgoing 路由由 `WebHookEventListener` 进入动作执行器。
- 关键模块：`ModelPlazaScraper` 名称查询、`WebPageScreenshotAction` Playwright worker、`ModelPlazaRequestAction` 新动作、`WebHookActionExecutor` 分派、默认 YAML 路由。
- 初始风险：线上 Model Plaza DOM 未能通过本机浏览器手工复核；浏览器工具启动 Chrome 失败。现有 Playwright worker、CLI Bridge 认证和测试 fixture 可复用。

## 项目文档与提示词需求

- 现有文档设计：README 提供配置、outgoing 路由、命令和 Model Plaza 技术说明；`docs/explore-develop/` 保存功能探索与回归记录；默认 YAML 通过中文注释解释配置。
- 文档缺口：原 README 和默认配置只有“模型”“分组”，没有请求查询的触发方式、截图返回规则和缺失字段说明。
- 现有提示词资产：未发现现有提示词资产；本次功能是确定性 DOM 查询，不需要提示词。
- 提示词需求：不适用。请求关键词、选择器、模型名和指标均由配置与页面 DOM 提供。
- 提示词模板草案：不适用；采用安全的变量模板和固定解析规则，不执行任意脚本。
- 评估样例：成功样例为匹配 `gpt` 后读取多个模型；边界样例为模型缺少吞吐、延迟或成功率图表时保留模型并显示“暂无数据”。
- 完善方案：新增路由注释、README 说明和本记录；后续页面结构变化时优先通过动作参数覆盖选择器，并补充本地 fixture。

## 真实用户体验假设

- 目标用户：在已配置 outgoing 路由的群聊中查询 Model Plaza 模型调用状态的管理员或开发者。
- 核心任务：发送 `请求 <关键词>`，在一条普通图文或一条合并转发消息中按模型独立查看吞吐、延迟和成功率图表。
- 成功标准：模糊匹配结果不混淆；每个模型有独立文本块；超过 3 个模型时每个模型独占一个转发节点；缺失数据可识别；无匹配和认证失败有明确提示。
- 易失败点：页面卡片 XPath 变化、搜索结果尚未完成渲染、登录态过期、图表 aria-label 缺失、图片上传失败或模型数量较多导致等待时间增加。

## 探索证据

- 查看内容：现有 `ModelPlazaScraper`、`ModelPlazaAuthManager`、`WebPageScreenshotAction`、`WebHookActionExecutor`、`KeywordExtractor`、Model Plaza 配置和测试。
- 运行观察：本机 NarraFork 浏览器工具无法启动 Chrome，未登录查看线上页面；改为静态读取线上公开前端资源 `index-*.js` 与 `ModelPlazaView-*.js`，并用本地 Playwright（Edge）跑 HTML fixture。
- 前端证据（均来自线上 bundle 静态分析）：
  - `/model-plaza` 路由 `requiresAuth: true`；路由守卫在 `isAuthenticated` 为假时客户端跳转 `/login?redirect=...`。
  - `isAuthenticated = !!token && !!user`，`checkAuth` 同时读取 localStorage 的 `auth_token` 与 `auth_user`；只写 token 会被判定未登录。
  - 页面搜索框为 `v-model` 文本输入，过滤条件同时匹配模型原始名、平台、渠道和分组名，是接口模糊匹配的超集；列表无分页、无虚拟滚动。
  - 模型卡片吞吐显示为 `xx.xx t/s`，延迟为 `NNNms` 或 `N.Ns`；成功率图表根节点 `role="img"`，aria-label 为“近 {有样本时段数} 个时段成功率，最低 {x}%”，时段数会变化。
  - 用户给出的第一张卡片 XPath `//*[@id="app"]/div[2]/div[2]/main/div/div[3]/div[1]/div[2]/div[2]/div` 与卡片模板中“底部区域 → 指标行”的层级一致；右侧百分比元素为其后缀 `/span[3]/span`，实现中按每张卡片使用相对 XPath `./div[2]/div[2]/div/span[3]/span`。

## 关键设计决策

- 认证：不在浏览器中再走一次 CLI Bridge。先用既有接口查询取得并刷新 token，再以 token 方式注入同源 `Authorization` 头和 `auth_token`，并通过同源资料接口 bootstrap 写入 `auth_user`，满足前端登录判定。refresh token 不写入页面，避免页面自行轮换后使插件缓存失效。
- 页面范围：配置的 `page_url` 必须与 `model_plaza.base_url` 同源且路径严格为 `/model-plaza`；运行中主文档导航到其它路径会被路由拦截，每一步之后都校验当前地址，只允许追加查询参数或 hash。跳到 `/login` 视为认证失效，最多刷新 token 重试一次。
- 卡片匹配：每张卡片、每个模型只用一次。依次按卡片标题精确匹配 → 卡片中单独一行的原始模型名精确匹配 → 完整词边界的最长模糊匹配。结果始终使用接口返回的原始模型名输出。
- 等待策略：搜索后先稳定 500ms，再轮询直到任一卡片指标行出现吞吐，最长 `search_wait_ms`（默认 5000ms）；取代原先固定等待。
- 失败边界：搜索框、模型卡片找不到、非法选择器、页面跳转等结构性错误整体失败并回复 `failure_message`；单个模型缺少指标、图表或图表截图超时（最长 10 秒）只把该字段标为“暂无数据”。
- 消息：≤3 个模型合并为一条普通图文；>3 个模型发送一条合并转发，每个模型一个节点；单张图片上传失败只影响该模型的图片。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 原功能没有请求查询动作或路由 | 用户无法查询模型请求状态 | 动作分派和默认 YAML 只有模型、分组 | fixed |
| ED-002 | P1 | 页面动态指标需要同一认证 Page 读取和元素级截图 | 只靠接口无法返回图表 | 浏览器 worker 无 Model Plaza 专用采集入口 | fixed |
| ED-003 | P1 | 多模型结果可能混在同一记录 | 图片与指标对不上模型 | 用户要求每个模型独立展示 | fixed |
| ED-004 | P1 | 只写 `auth_token` 会被前端判为未登录并跳转登录页 | 每次都“查询失败” | bundle 中 `isAuthenticated` 需要 token 与 user | fixed（bootstrap `auth_user`） |
| ED-005 | P1 | 多卡片等待触发 Playwright strict mode、重叠模型名可能复用同一卡片 | 正常多模型查询失败或数据错配 | 内部审查 v2/v3 | fixed（`first()` 等待 + 一对一分配） |
| ED-006 | P1 | 选择器超时、登录跳转被静默降级为“暂无数据” | 用户看到假数据而非错误 | 内部审查 v1/v3 | fixed（结构性错误上抛） |
| ED-007 | P2 | 线上 DOM 未能在当前环境实测 | 默认选择器可能随页面改版失效 | 浏览器工具无法启动；仅 bundle 静态证据 | accepted：选择器带语义兜底且可配置覆盖 |
| ED-008 | P1 | 服务端 302 到登录页时被路由拦截，无法识别为认证失效 | 登录态过期时不会刷新 token，直接失败 | 内部审查 v4 | fixed（记录被拦截的跳转目标并转换为认证失败，刷新后重试一次） |
| ED-009 | P1 | 失败日志直接记录异常消息与堆栈，可能带出响应体中的 token/secret | 日志泄露凭据 | 内部审查 v4 | fixed（原因链脱敏，仅保留堆栈帧） |

## 本次预开发改动

- 改动摘要：新增 `query_model_plaza_requests` 动作与默认关闭的 `请求` outgoing 路由；在既有 Playwright worker 上新增 Model Plaza 采集入口（搜索、卡片匹配、指标读取、成功率图表元素截图）；新增普通图文与合并转发两种投递；更新 README 与默认 YAML 注释。
- 用户可感知结果：发送 `请求 gpt` 后，按模型分块返回名称、吞吐、延迟和成功率图表；超过 3 个模型时每个模型独占一个转发节点；无匹配、缺数据和页面异常各有明确提示。
- 受影响区域：`ModelPlazaRequestAction`（新增）、`WebPageScreenshotAction`、`WebHookActionExecutor`、`webhook_config.yml`、`README.md`，以及新增/扩展的测试。

## 验证结果

- 自动验证（单元）：`./gradlew.bat test` 通过。
- 自动验证（本地浏览器集成，`XAI_WEBHOOK_BROWSER_IT=true`，Edge）：`ModelPlazaBrowserTest` 8 项与相关 `WebPageScreenshotActionTest` 2 项全部通过，覆盖重叠模型名、显示名与原始名、动态指标渲染、地址栏追加查询参数、服务端/客户端登录跳转、非法与缺失选择器、隐藏图表截图超时、截图像素颜色与尺寸。
- 消息结构验证：使用 `mirai-core-mock` 断言 3 个模型为一条普通消息、4 个模型为一条合并转发且每个节点只含一个模型、单张上传失败不影响其它模型、取消异常继续传播。
- 全量回归与构建：见文末“最终门禁”一节，以实际运行结果为准。
- 提示词验证：不适用；本次没有提示词资产或 LLM 生成逻辑。
- 未验证项：未使用真实账号访问线上 `/model-plaza`；原因是本机浏览器工具无法启动 Chrome，且自动化测试不应依赖真实凭据。

## 残留风险

- 线上 DOM 结构和成功率 aria-label 可能变化；默认选择器来自用户提供 XPath 与前端 bundle 静态分析，带语义兜底，但不等于线上已实测。
- bootstrap 使用 `model_plaza.auth.profile_url` 的路径恢复 `auth_user`；该接口在现有 CLI Bridge 登录流程中已被使用，但浏览器内调用未在线上验证。
- 所有匹配模型都没有近期数据时，会等满 `search_wait_ms` 再返回“暂无数据”；匹配项很多时串行截图会增加总耗时。
- 卡片匹配的最后一档模糊匹配只比较卡片标题；若页面既有人类友好的标题、又把原始模型名嵌在其它文字中（不单独成行），该模型会显示“暂无数据”。这是为避免借用其它模型数据而接受的取舍，当前页面模板中原始名单独成行，不受影响。
- 本次新动作的失败日志已做脱敏（token、secret、Authorization、JWT）并只保留堆栈帧；既有的“模型/分组”查询动作仍按原样记录异常消息，不在本次范围内。

## 后续建议

- 在可启动 Edge/Chromium 且具备测试账号的环境中，启用路由后实际发送一次 `请求 <关键词>`，确认默认选择器与登录态恢复；如页面改版，优先用动作参数覆盖选择器并把新 DOM 加入 fixture。
- 如需好友私聊触发，可增加与群路由对称的 `friend_message` 示例，并使用独立冷却键。

## 最终门禁

- 内部审查：v1–v4 共发现 7 个 P1（页面同源、异常降级、多卡片 strict mode、重叠模型名、空名对齐、服务端登录跳转、日志脱敏），均已修复；v5 结论 passed，无 P0–P2。
- 全量回归与构建：`XAI_WEBHOOK_BROWSER_IT=true ./gradlew.bat build --rerun-tasks` 通过，16 个测试类共 199 项，0 跳过、0 失败（含本地 Edge 浏览器集成用例）。
- 插件打包：`./gradlew.bat buildPlugin` 通过，产物 `build/mirai/XAiWebHook-0.2.0.mirai2.jar`。
- 编译警告：仅既有的 Kotlin 参数重复/弃用警告与两处既有“冗余初始化”提示，与本次改动无关。
- 提交前整理：撤回了子任务误改的两处无关内容（一处既有错误提示文本、一处空行），撤回后 `./gradlew.bat test` 再次通过；按逻辑拆为 4 个提交，其中核心功能提交在接入路由之前单独导出快照编译并跑单测通过。
- 未完成项：线上 `/model-plaza` 实机验证（需可用浏览器与测试账号），因此本记录状态保持 `partial`。
