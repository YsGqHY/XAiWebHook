---
skill: explore-develop
date: 2026-07-11 18:45
project: E:\Desktop\IDEA\XAiWebHook
scope: screenshot-retry
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Screenshot Retry

## 调用背景

- 用户目标：为 `send_webpage_screenshot` 增加可配置的失败重试机制与最大额外重试次数，使浏览器步骤或截图的偶发超时、网络瞬断能够自动恢复。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：截图配置解析、完整 capture 尝试循环、可重试异常分类、single-flight 交互、真实 Edge 回归测试、默认与完整 YAML、README 和 CLI Bridge 接入文档。
- 明确约束：`max_retries` 表示首次失败后的额外重试次数；未配置时保持不重试；只重试浏览器操作超时、浏览器 future 取消、目标关闭和明确网络瞬断；认证、配置、非法 selector、截图尺寸超限等确定性失败不重试；single-flight 锁覆盖整个重试周期；不新增依赖。

## 项目快照

- 技术栈：Kotlin 1.9.22、kotlinx.coroutines、Playwright Java 1.60.0、mirai-console 2.16.0、Gradle 7.3.3。
- 运行入口：JDK 14 下执行 `./gradlew.bat --no-daemon build`；真实浏览器测试通过 `XAI_WEBHOOK_BROWSER_IT=true` 显式启用系统 Edge。
- 关键模块：`WebPageScreenshotAction.kt`、`WebHookConfig.kt`、`WebPageScreenshotActionTest.kt`、默认与完整 YAML、README、CLI Bridge 集成文档。
- 初始风险：工作区包含前序 session cache、single-flight 和字体等待未提交改动；旧运行日志仍可能来自 60 秒字体等待版本；若重试边界按消息或异常类型判断过宽，会把认证、配置或 selector 错误放大为重复失败。

## 项目文档与提示词需求

- 现有文档设计：README 说明截图动作、YAML 字段和排障路径；默认 YAML 为字段提供中文注释；完整 Geek2Api 示例提供可直接复用的 CLI Bridge、session cache、single-flight 和截图配置；桌面接入文档描述真实触发流程。
- 文档缺口：此前没有失败重试字段、额外重试次数语义、默认关闭兼容行为、可重试与不可重试边界，也没有说明 single-flight 在重试期间持续占用。
- 现有提示词资产：未发现与截图重试相关的 AI/Agent 提示词资产；项目模板表达式只负责配置值替换，不参与重试决策。
- 提示词需求：不需要生成式提示词。用户可见失败文案继续由现有 route/fallback 配置控制，重试原因和次数通过日志与状态字段表达。
- 提示词模板草案：不适用。
- 评估样例：成功样例为首次截图等待目标超时，第二次完整 capture 时目标出现并返回 PNG；边界样例为目标始终缺失且 `max_retries: 2`，严格执行 3 次后抛出最终超时。确定性失败样例为截图尺寸超限或非法 selector，首轮失败后立即返回，不进入重试。
- 完善方案：在配置、README 和 CLI Bridge 文档中统一使用“额外重试次数”表述，并把可恢复错误范围写成明确白名单，避免运维将认证或配置错误误认为网络抖动。

## 真实用户体验假设

- 目标用户：通过群聊、好友消息或 incoming endpoint 触发 Geek2Api 监控页截图的机器人管理员。
- 核心任务：偶发页面加载或截图超时时无需手动再次发送命令，插件应在有限次数内自行恢复；持续故障时应在可预测时间内停止并返回原有失败反馈。
- 成功标准：未配置重试的旧 YAML 行为不变；启用后 `max_retries: 2` 最多共尝试 3 次；每次重新执行完整 capture 并创建新 Page；命名 session、Cookie 和 token 继续复用；同一 single-flight 分组在整个周期内只有一个运行中的任务。
- 易失败点：把最大次数理解为总尝试次数；重试认证或配置错误；调用方取消协程后仍继续重试；协程调试恢复出的重复异常包装层遮蔽真正的 Timeout 根因；重试间隙提前释放 single-flight 锁。

## 探索证据

- 查看内容：截图 action 的 `capture`、`parseSpec`、BrowserWorker、step/screenshot 异常包装、single-flight 调用入口、配置数据类、测试、默认与完整示例及两份用户文档。
- 运行观察：本地 HTTP 页首次请求不渲染目标、第二次请求渲染目标时，真实 Edge 测试成功返回 PNG；目标始终缺失且 `max_retries: 2` 时服务器请求计数严格为 3；调用过程保持同一命名 session 并为每次 capture 新建 Page。
- 关键证据：Playwright 超时以 `TimeoutError` 位于 browser step/screenshot 包装异常的 cause chain 中；Kotlin 协程调试可能复制一层同文案 `IllegalStateException`，分类器必须跳过连续操作包装层后再检查根因；`CancellationException` 继承 `IllegalStateException`，需要显式区分浏览器 future 取消与调用方协程取消。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 截图动作没有有限失败重试 | 网络或浏览器偶发超时必须人工重新触发 | `capture` 原先只提交一次 worker 请求 | fixed |
| ED-002 | P1 | 最大次数语义未定义且旧配置兼容要求不明确 | 容易出现少一次或多一次尝试，或无意改变旧 YAML 行为 | 配置模型和文档均无 retry 字段 | fixed |
| ED-003 | P1 | 宽泛重试会覆盖认证、配置、非法 selector 和尺寸超限 | 确定性错误被重复执行，延迟真实失败反馈 | step/screenshot 统一使用包装异常 | fixed |
| ED-004 | P1 | 协程取消和浏览器操作取消共享 `CancellationException` | 外部取消可能被吞掉，或可恢复的 browser future 取消无法重试 | coroutine capture 与异常继承关系 | fixed |
| ED-005 | P1 | 协程调试恢复会生成重复操作包装异常 | 根因 Timeout 被中间 `IllegalStateException` 误判为确定性失败 | 真实 Edge 回归首次失败及调用栈 | fixed |
| ED-006 | P2 | 默认配置、完整示例和排障文档缺少重试说明 | 用户无法安全启用或判断为何未重试 | YAML、README、CLI Bridge 文档 | fixed |
| ED-007 | P1 | 完整 Geek2Api 示例回归测试块在工作过程中再次缺失 | 默认/完整示例字段可能失去自动覆盖 | 测试文件前后核验 | fixed |

## 本次预开发改动

- 改动摘要：为 `WebPageScreenshotSpec` 新增 `retry` 配置并引入 `BrowserScreenshotRetry`；`capture` 在 BrowserWorker 外层按配置循环完整尝试；异常分类跳过协程恢复的重复操作包装层，仅对白名单根因重试；调用方协程取消通过 `currentCoroutineContext().ensureActive()` 立即退出。
- 用户可感知结果：启用 `screenshot.retry` 后，浏览器步骤或截图的短暂超时、目标关闭和明确网络瞬断可自动恢复；持续故障严格按额外次数停止；认证、配置、selector 和尺寸错误不会增加等待。
- 受影响区域：截图配置解析、capture 调度、重试日志与 `lastError`、异常分类、默认与完整 YAML、README、CLI Bridge 文档、单元及真实 Edge 测试。

## 验证结果

- 自动验证：JDK 14 下执行 `./gradlew.bat --no-daemon build --rerun-tasks` 成功；3 个测试类共 53 项测试，0 failures、0 errors；`git diff --check` 无格式错误，仅有工作区既有 LF/CRLF 转换提示。
- 手动验证：使用 `XAI_WEBHOOK_BROWSER_IT=true` 单独运行真实 Edge 重试测试通过。第一组页面首次缺少 `#target`、第二次出现后成功返回 PNG；第二组始终缺少目标且 `max_retries: 2` 时严格收到 3 次 HTTP 请求后失败。
- 文档验证：默认 YAML 和完整示例均包含 `retry.enabled: true`、`max_retries: 2`、`delay_ms: 1000`；README 与 CLI Bridge 文档统一说明额外次数、默认兼容、错误边界和 single-flight 锁范围；YAML 解析测试断言字段值。
- 提示词验证：不涉及 AI 提示词；成功、持续失败和确定性失败样例由配置解析、异常分类和真实浏览器测试覆盖。
- 未验证项：未直接访问生产 `https://hk3.geek2api.com/monitor`，避免依赖真实账号和外部网络；目标关闭、future 取消和非法 selector 使用异常分类单元测试覆盖，未单独构造真实浏览器进程崩溃。

## 残留风险

- Playwright 未来版本可能调整目标关闭、网络错误或取消异常的文案；升级依赖时应重跑分类测试并检查新的 cause chain。
- 当前使用固定 `delay_ms`，不包含指数退避或随机抖动；最大额外次数限制为 10，已避免单任务无限占用 single-flight，但大量不同分组同时失败仍可能集中重试。
- 重试不会主动重建整个 Browser 或命名 session，只会重新提交完整 capture 并创建新 Page；如果故障来自已损坏但尚未被识别为关闭的 BrowserContext，后续尝试可能连续失败并在上限处停止。
- 字体等待修复仍依赖 Playwright 1.60.0 的 `PW_TEST_SCREENSHOT_NO_FONTS_READY=1` 驱动开关；升级 Playwright 时应与重试回归一起验证。

## 后续建议

- 升级 Playwright 或调整 step/screenshot 异常包装时，保留真实 Edge 的首次失败后恢复与严格最大次数回归测试。
- 若后续引入跨 action 通用 HTTP 重试，保持截图 retry 为独立白名单，不要复用会重试认证、配置或业务校验失败的宽泛策略。
