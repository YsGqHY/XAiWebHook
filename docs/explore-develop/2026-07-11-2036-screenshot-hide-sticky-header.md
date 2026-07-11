---
skill: explore-develop
date: 2026-07-11 20:36
project: E:\Desktop\IDEA\XAiWebHook
scope: screenshot-hide-sticky-header
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Screenshot Hide Sticky Header

## 调用背景

- 用户目标：排除 Geek2Api 监控截图顶部出现的固定 AppHeader，并验证包含修复的新 Mirai 插件包。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：生产页面布局证据、截图 action 配置与执行、默认/完整 YAML、README、CLI Bridge 文档、单元测试、真实 Edge 像素测试和 `buildPlugin` 产物。
- 明确约束：保留消息匹配 OR、session cache、single-flight、字体等待与重试等现有未提交改动；不依赖易变的固定裁剪像素；不开放任意 JavaScript 或任意 CSS 注入。

## 项目快照

- 技术栈：Kotlin、Playwright Java 1.60.0、mirai-console 2.16.0、Gradle 7.3.3。
- 运行入口：JDK 14 下执行定向测试；真实浏览器测试通过 `XAI_WEBHOOK_BROWSER_IT=true` 使用系统 Edge；Mirai 插件由 `buildPlugin` 生成。
- 关键模块：`WebPageScreenshotAction.kt`、`WebPageScreenshotActionTest.kt`、默认与完整 YAML、README、CLI Bridge 集成文档。
- 初始风险：截图 selector 已指向 `<main>`，但较高元素截图会触发页面滚动，sticky header 仍可能覆盖主元素顶部；无法使用生产登录态直接截图；工作过程中测试文件曾被外部并行编辑覆盖。

## 项目文档与提示词需求

- 现有文档设计：README 说明截图 selector、字体等待和重试；完整示例与 CLI Bridge 文档提供 Geek2Api monitor 配置。
- 文档缺口：没有配置截图时临时隐藏固定/悬浮元素的能力，也未说明 `<main>` 元素截图仍可能被外部 sticky header 覆盖。
- 现有提示词资产：未发现与截图 header 排除相关的 AI 提示词资产。
- 提示词需求：不需要生成式提示词；需要受限、可审计的 CSS selector 列表，插件只生成固定的 `visibility: hidden !important` 样式。
- 提示词模板草案：不适用。
- 评估样例：成功样例为红色 fixed header 覆盖绿色目标，配置 `hide_selectors` 后截图顶部像素由红色恢复为绿色；失败样例为不配置隐藏 selector 时控制截图仍保留红色 header。
- 完善方案：新增 `screenshot.hide_selectors`，默认示例配置 `header.sticky`，并在文档中明确 CSS-only、最多 32 项及截图后自动恢复。

## 真实用户体验假设

- 目标用户：通过群聊或 incoming 触发 Geek2Api 渠道状态截图的机器人管理员。
- 核心任务：收到只包含监控主内容的图片，不让顶部导航覆盖第一行卡片或状态信息。
- 成功标准：目标 `<main>` 的尺寸与布局不变；截图瞬间 AppHeader 不绘制；截图完成后页面和复用 session 不受影响；配置可在上游 class 变化时调整。
- 易失败点：仅修改 XPath 仍受 sticky overlay 影响；按固定像素裁剪会误删响应式内容；使用 `display:none` 会导致布局重排；旧运行时 YAML 不会自动获得新字段。

## 探索证据

- 查看内容：当前截图实现、Playwright `Locator.ScreenshotOptions.setStyle` API、线上 `AppLayout` 与 `AppHeader` 静态资源、默认/完整配置和现有浏览器测试。
- 运行观察：线上 AppLayout 的顶部组件实际渲染为 `header`，类包含 `glass sticky top-0 z-30`，主内容随后渲染为 `<main class="p-4 md:p-6 lg:p-8">`；这与主元素滚动截图时 header 覆盖顶部的现象一致。
- 关键证据：真实 Edge 回归页使用红色 fixed header 覆盖绿色 `#target`。未隐藏时截图坐标 `(width/2, 20)` 为 `0xFF0000`；配置 `hide_selectors: ["header.sticky"]` 后同一坐标为 `0x00FF00`，两个断言同时通过。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 元素截图没有排除目标外部的 sticky/fixed overlay | AppHeader 覆盖监控截图顶部 | AppLayout 结构和用户任务 | fixed |
| ED-002 | P1 | 固定像素裁剪无法适配响应式 header 高度 | 可能误删卡片内容或仍残留 header | 多视口布局与 h-16 header | avoided |
| ED-003 | P2 | 配置缺少受限的截图临时样式能力 | 用户只能修改站点或使用不稳定 selector | ScreenshotOptions 已支持 style | fixed |
| ED-004 | P1 | 完整示例回归测试在工作过程中再次被外部覆盖删除 | 可部署示例失去自动验收 | Gradle 报 no tests found | restored |

## 本次预开发改动

- 改动摘要：新增 `screenshot.hide_selectors` 解析、数量/长度/危险 CSS 语法校验和固定样式生成；截图时通过 Playwright `setStyle` 临时注入 `visibility: hidden !important`。
- 用户可感知结果：默认 Geek2Api 截图会隐藏 `header.sticky`，主内容顶部不再被固定导航覆盖；页面不重排，截图结束后样式自动移除。
- 受影响区域：截图 spec、BrowserWorker 截图选项、默认/完整配置、README、CLI Bridge 文档和测试。

## 验证结果

- 自动验证：配置解析与安全样式两个定向测试通过；完整示例测试通过；真实 Edge fixed-header 像素测试通过；`buildPlugin --rerun-tasks` 成功；`git diff --check` 无格式错误，仅有既有 LF/CRLF 提示。
- 手动验证：公开页面与静态资源确认当前 AppHeader selector 为 `header.sticky`；浏览器截图显示登录页同样使用独立顶部 header，证明隐藏策略按 selector 生效而非裁剪整个页面。
- 文档验证：默认 YAML、完整示例、README 和 CLI Bridge 文档均包含 `hide_selectors: ["header.sticky"]` 及行为说明。
- 提示词验证：不涉及 AI 提示词；成功与失败样例由真实 Edge 图像像素断言覆盖。
- 未验证项：没有生产 CLI Bridge 登录态，未直接对已登录 `/monitor` 截取最终业务图片；本地测试精确复现“目标外 fixed header 覆盖元素截图”的合成行为。

## 残留风险

- 上游 Geek2Api 若移除 `sticky` class 或更换 header 标签，运行时 YAML 需要更新 `hide_selectors`。
- `visibility:hidden` 保留元素占位，适合 fixed/sticky overlay；若未来需要移除参与普通文档流的元素，可能仍留下空白，但不会导致主内容位置跳动。
- 现有生产运行时 YAML 不会被新 JAR 覆盖，必须手动增加 `hide_selectors` 并执行 reload；替换插件代码仍需完整重启 Mirai。
- 任务过程中发现默认资源混入生产化配置；最终提交前已恢复安全占位群号、关闭示例路由与默认磁盘缓存，并在 JDK 14 下通过完整 Gradle 测试。

## 后续建议

- 部署后核对生产 YAML 的截图段包含 `hide_selectors: ["header.sticky"]`，发送一次监控截图并确认第一行卡片完整可见。
- 若还需隐藏客服浮窗，可在列表中增加稳定 CSS selector，但应优先隐藏覆盖目标内容的元素，避免无必要扩大规则。
