---
skill: explore-develop
date: 2026-07-11 15:56
project: E:\Desktop\IDEA\XAiWebHook
scope: monitor-skeleton-readiness
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：monitor-skeleton-readiness

## 调用背景

- 用户目标：修复 `geek2api-monitor-screenshot` 在监控信息尚未完整加载、页面仍显示灰色骨架卡片时就截图的问题。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：hk3 `/monitor` 首次数据加载完成条件、默认与完整实例 YAML、README、配置回归测试和 Edge 延迟加载集成测试。
- 明确约束：保持 YAML 驱动，不在 Kotlin 中硬编码 Geek2Api 业务选择器；不使用任意 JavaScript；避免只靠固定延时判断加载完成。

## 项目快照

- 技术栈：Kotlin 1.9.22、JVM 11 目标、mirai-console 2.16.0、Playwright 1.60.0、SnakeYAML、Gradle 7.3.3。
- 运行入口：`./gradlew.bat test`、`XAI_WEBHOOK_BROWSER_IT=true ./gradlew.bat test`、`./gradlew.bat clean test buildPlugin`。
- 关键模块：`webhook_config.yml`、Geek2Api CLI Bridge 完整实例、`WebPageScreenshotAction.kt`、`WebPageScreenshotActionTest.kt`、README。
- 初始风险：工作区已有未提交的完整截图与 CLI Bridge 功能；运行时配置不会被新 JAR 自动覆盖；hk3 前端构建哈希和内部 class 未来可能变化。

## 项目文档与提示词需求

- 现有文档设计：默认 YAML 提供逐项中文注释，`examples/` 提供完整可运行配置，README 负责解释认证、截图步骤和故障排查。
- 文档缺口：现有说明只要求等待截图主 `<main>` 可见，没有解释主容器在骨架阶段已经挂载，也没有生产运行时 YAML 的迁移提示。
- 现有提示词资产：未发现 AI 系统提示词或自然语言 Agent 模板；本功能使用受限 YAML 浏览器步骤，不需要新增自然语言提示词。
- 提示词需求：将“页面加载完成”表达为可审计的 DOM 完成态，不允许任意脚本，不把网络空闲或固定延时误当成业务数据完成。
- 提示词模板草案：`goto monitor -> wait main visible -> wait (real monitor card OR empty-state) visible -> screenshot main`。
- 评估样例：成功样例为骨架屏延迟 600 毫秒后替换成真实卡片，截图必须等待替换完成；边界样例为接口成功但列表为空，`.empty-state` 出现后允许截图而不是等待 90 秒超时。
- 完善方案：默认配置、完整实例、README 和测试统一使用同一完成态 XPath；后续 hk3 前端更新时可只调整运行时 YAML，无需修改 Kotlin。

## 真实用户体验假设

- 目标用户：通过群聊、好友消息或 incoming 请求获取 Geek2Api 监控状态截图的插件使用者。
- 核心任务：收到包含实际渠道名称、状态、延迟、成功率等数据的完整截图，而不是误认为系统没有数据的灰色占位图。
- 成功标准：截图前骨架分支已经卸载；有数据时至少第一张真实卡片已可见；无数据时合法空状态已可见；加载失败时明确超时失败而不是发送误导截图。
- 易失败点：主容器过早可见、第三方聊天/支付资源导致 network idle 不可靠、自动刷新倒计时持续变化、监控列表合法为空。

## 探索证据

- 查看内容：用户提供的骨架屏截图、截图步骤解析与执行代码、默认和完整实例 YAML、现有浏览器集成测试。
- 运行观察：访问 hk3 当前生产页面并读取动态分包 `ChannelStatusView`；确认首次请求为 `/channel-monitors`，加载状态下渲染六个 `div.min-h-[280px].animate-pulse`，成功后渲染 `button.group.min-h-[280px]`，空列表时渲染 `.empty-state`。
- 关键证据：现有步骤只等待 `//*[@id="app"]/div[2]/div[2]/main` visible；该 `<main>` 在骨架屏阶段已存在，因此条件必然过早满足。真实卡片与骨架卡片由同一个 Vue 条件分支互斥渲染，真实卡片或空状态出现即可证明首次加载结束。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-301 | P1 | 仅等待截图主容器可见 | 骨架屏被当成最终监控结果发送 | 用户截图、默认 YAML、生产组件 | fixed |
| ED-302 | P1 | 直接等待任意 `.animate-pulse` 消失不可行 | 正常 operational/live 状态圆点也使用该 class，可能永久等待 | 生产 `ChannelStatusView` | rejected，改等真实完成态 |
| ED-303 | P2 | 只等待真实卡片会让合法空列表超时 | 无渠道时无法返回正确空状态截图 | 生产 `EmptyState` 组件 | fixed |
| ED-304 | P2 | 新 JAR 不覆盖现有运行时 YAML | 用户升级后仍可能继续截到骨架屏 | 插件配置复制机制 | documented |

## 本次预开发改动

- 改动摘要：在 `geek2api-monitor-screenshot.steps` 中增加 90 秒完成态等待，XPath 接受第一张真实监控卡片或 `.empty-state`；保留主容器 visible 等待用于确认页面挂载。
- 用户可感知结果：API 较慢时截图会继续等待，不再发送六张灰色占位卡；接口返回空列表时仍能发送合法空状态。
- 受影响区域：默认配置、Geek2Api CLI Bridge 完整实例、README、YAML 结构断言和 Edge 延迟加载集成测试。

## 验证结果

- 自动验证：使用 JDK 14 执行普通测试成功；默认与完整实例测试确认最后一个步骤为 90 秒完成态等待，同时包含真实卡片和空状态选择器。
- 手动验证：读取 hk3 当前生产动态分包，确认骨架、真实卡片、空状态和 `/channel-monitors` 数据流的实际结构。
- 浏览器验证：Edge 集成页面先渲染 `animate-pulse` 骨架，600 毫秒后请求 ready 并替换为真实卡片；截图动作成功且 ready 请求计数为 1，证明截图等待了真实卡片。
- 文档验证：README 已解释骨架屏原因、完成态判定和运行时 YAML 迁移步骤。
- 提示词验证：成功样例等待真实卡片后截图；边界设计允许 `.empty-state` 完成态，不依赖固定延时或 network idle。
- 构建验证：执行 `XAI_WEBHOOK_BROWSER_IT=true ./gradlew.bat clean test buildPlugin` 成功，32 项测试零失败，产物为 `build/mirai/XAiWebHook-0.1.0.mirai2.jar`。
- 未验证项：未使用用户真实登录态再次截取 hk3 线上监控数据；真实账号 token 位于用户运行中的 mirai 进程内，本次未读取或导出。

## 残留风险

- 就绪 XPath依赖 hk3 当前真实卡片 class `group`、`min-h-[280px]` 和空状态 class `empty-state`；若站点前端重构这些 class，需要同步更新运行时 YAML。
- 用户现有生产配置不会因替换 JAR 自动增加新步骤，必须手动合并完成态等待并执行 `/xwebhook reload`。
- 如果 `/channel-monitors` 长时间失败且页面既不产生卡片也不产生空状态，动作会在 90 秒后失败并发送现有脱敏失败消息，这是比发送骨架截图更安全的行为。

## 后续建议

- 生产环境合并本次 XPath 后执行一次真实截图验收，确认渠道卡片内容完整。
- 若 hk3 后续提供稳定的 `data-testid` 或 `aria-busy`，优先改用语义属性替代 Tailwind class 选择器。
