---
skill: explore-develop
date: 2026-07-11 18:18
project: E:\Desktop\IDEA\XAiWebHook
scope: playwright-font-timeout
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Playwright Font Timeout

## 调用背景

- 用户目标：检查并修复网页截图在页面与 XPath 已就绪后，仍因 Playwright `waiting for fonts to load...` 耗尽 30 秒截图超时的问题。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：`send_webpage_screenshot` 截图阶段、Playwright 驱动环境、截图配置、真实 Edge 回归测试及排障文档。
- 明确约束：保持现有登录、session cache、single-flight 和页面业务就绪等待不变；不新增依赖；兼容未配置新字段的运行时 YAML。

## 项目快照

- 技术栈：Kotlin 1.9.22、Playwright Java 1.60.0、mirai-console 2.16.0、Gradle 7.3.3。
- 运行入口：JDK 14 下执行 `./gradlew.bat --no-daemon build`；真实浏览器测试通过 `XAI_WEBHOOK_BROWSER_IT=true` 显式启用系统 Edge。
- 关键模块：`WebPageScreenshotAction.kt`、`WebPageScreenshotActionTest.kt`、默认/完整 YAML、README、CLI Bridge 集成文档。
- 初始风险：工作区含前序 session cache 与 single-flight 未提交改动；Playwright 内部字体等待和元素截图共用一个总超时，无法仅靠延长 `screenshot.timeout_ms` 区分页面失败与字体 CDN 挂起。

## 项目文档与提示词需求

- 现有文档设计：README 提供截图步骤、超时和故障排查；默认 YAML 提供逐字段中文注释；CLI Bridge 文档说明登录、缓存和截图防重入。
- 文档缺口：没有解释截图阶段会等待网页字体，也没有提供字体请求长期挂起时的独立超时和降级语义。
- 现有提示词资产：未发现与本问题相关的 AI 提示词；模板表达式不参与字体加载。
- 提示词需求：不需要生成式提示词。用户反馈仍沿用既有固定失败文案，技术细节写入日志和文档。
- 提示词模板草案：不适用。
- 评估样例：成功样例为字体正常加载后截图；边界样例为 WOFF2 响应保持连接不结束，字体等待到期后仍产出 PNG。
- 完善方案：新增 `screenshot.font_wait_timeout_ms` 文档、默认示例与故障排查条目，明确它不替代页面业务就绪 `wait`。

## 真实用户体验假设

- 目标用户：通过群聊或 incoming 触发 Geek2Api 监控页截图的机器人管理员。
- 核心任务：页面数据和截图区域已经可见时，即使字体 CDN、代理或网络请求异常，也应收到可读的监控截图，而不是等待 30 秒后收到失败消息。
- 成功标准：业务元素等待仍严格执行；字体正常时短暂等待获得正确字体，字体挂起时在小范围延迟后使用浏览器回退字体继续截图；错误日志不再停在 Playwright 内部字体等待。
- 易失败点：完全关闭字体等待会在正常慢网络下过早截到回退字体；只增加总截图超时会延长失败时间；清除 CSS 字体会改变页面样式。

## 探索证据

- 查看内容：用户提供的完整异常栈、截图调用实现、Playwright 1.60.0 本地驱动 `coreBundle.js`、Java `Page.waitForFunction` API、现有 Edge 集成测试和配置文档。
- 运行观察：异常 Call log 明确为 `taking element screenshot` 后 `waiting for fonts to load...`；本地 Playwright 驱动在截图前等待 `document.fonts.ready`，仅受驱动环境开关控制。
- 关键证据：页面 URL、标题与 selector 均已正确；旧实现直接调用 `Locator.screenshot(timeout)`，字体等待占用同一个 30 秒预算；阻塞字体 HTTP 回归页可稳定复现该条件。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | Playwright 内部无独立上限地等待 `document.fonts.ready` | 页面已就绪仍截图失败 | 用户异常栈与 1.60.0 驱动源码 | fixed |
| ED-002 | P1 | 字体等待与元素截图共用 `screenshot.timeout_ms` | 增大超时只会让用户等待更久 | `Locator.screenshot` 单一 timeout | fixed |
| ED-003 | P2 | 配置与排障文档没有字体降级说明 | 运维会误判为 XPath、登录或页面加载失败 | README/YAML/CLI Bridge 文档 | fixed |
| ED-004 | P1 | 完整 Geek2Api 示例测试块在本轮工作过程中意外缺失 | 会减少前序缓存和示例覆盖 | 本轮开始时文件内容与后续校验差异 | fixed |

## 本次预开发改动

- 改动摘要：Playwright 驱动关闭内部字体无限等待；插件在目标元素可见后自行检查 `document.fonts.status`，按 `screenshot.font_wait_timeout_ms` 有界等待，超时仅记录调试日志并继续截图。
- 用户可感知结果：字体请求挂起时不再等待整个 30 秒后失败，而是在默认最多 3 秒后使用当前回退字体发送截图。
- 受影响区域：截图规范解析、BrowserWorker 截图前处理、Playwright 启动环境、默认与完整配置、README、CLI Bridge 文档、截图测试。

## 验证结果

- 自动验证：JDK 14 下 `./gradlew.bat --no-daemon build` 成功。
- 手动验证：新增真实 Edge 测试页面，`@font-face` 指向保持 chunked 响应但不结束的本地 WOFF2 接口；确认字体请求已开始且 `send_webpage_screenshot` 仍返回有效 PNG。该测试使用 `XAI_WEBHOOK_BROWSER_IT=true` 单独运行并通过。
- 文档验证：默认 YAML 与完整示例均加入 `font_wait_timeout_ms: 3000`，原 YAML 不配置时由解析器自动采用默认值；完整示例测试已恢复并增加字段断言。
- 提示词验证：不涉及 AI 提示词；成功与边界行为由 PNG 头、字体请求 latch 和解析断言验证。
- 未验证项：未直接访问生产 `https://hk3.geek2api.com/monitor`，避免依赖真实账号和网络状态；本地 Edge 测试精确覆盖相同的未完成字体请求条件。

## 残留风险

- 修复使用 Playwright 1.60.0 驱动内置环境开关来禁用其内部字体等待；项目升级 Playwright 时应通过回归测试确认该开关仍有效。
- 字体等待超时后截图可能使用系统回退字体，文本内容和页面数据不受影响，但字形、字宽或换行可能与最终网页字体略有差异。
- 若页面业务数据本身未完成，既有 `steps.wait` 和 `screenshot.selector` 仍会按配置失败；本修复不会绕过这些正确性检查。

## 后续建议

- 升级 Playwright 依赖时始终运行阻塞字体 Edge 回归测试。
- 若特定页面必须使用最终品牌字体，可提高 `font_wait_timeout_ms`，同时保留有限上限，不建议设为与长业务等待相同的分钟级时长。
