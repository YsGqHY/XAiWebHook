---
skill: explore-develop
date: 2026-08-20 23:44
project: XAiWebHook
scope: polymarket-event-image
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Polymarket 多市场事件图片

## 调用背景

- 用户目标：查询 Polymarket 多市场事件，并将网页截图中的核心数据重新排版为原创图片；当前仅执行测试与预览，图片输出到 `build`，不接入 mirai 发送。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：Gamma 事件盘口与趋势字段、静态事件卡生成器、PNG 渲染回归、生产代理实时预览和使用文档。
- 明确约束：保留强制大模型白名单、普通关键词事件搜索和 Polymarket 独立代理；不截图 Polymarket 网站；不新增依赖；不改变现有文本响应和机器人动作。

## 项目快照

- 技术栈：Kotlin、Gradle、mirai-console、kotlinx.serialization、openhtmltopdf Java2D、JUnit 5。
- 运行入口：`./gradlew.bat polymarketImagePreview`、`./gradlew.bat test`、`./gradlew.bat buildPlugin`。
- 关键模块：`PolymarketModels`、`PolymarketEventCard`、`HtmlImageRenderer`、`PolymarketImagePreview`。
- 初始风险：工作区包含此前未提交的 Polymarket 搜索、代理和白名单开发内容；openhtmltopdf 仅完整支持 CSS 2.1；Gamma 本地化字段可能与问题文本日期不一致。

## 项目文档与提示词需求

- 现有文档设计：`docs/POLYMARKET_USAGE.md` 连续说明配置、关键词搜索、事件 URL、文本模板和实时验证；`docs/explore-develop/` 保存按时间命名的决策记录。
- 文档缺口：此前只提供文本实时预览，没有说明如何用真实事件数据生成图片，也未描述盘口与趋势字段来源。
- 现有提示词资产：本功能未发现或引入 LLM 提示词；搜索相关性、字段回退、数字格式化和图片布局均为确定性代码。
- 提示词需求：当前不需要提示词。图片内容必须严格来自 Gamma 数据，使用提示词重写标题或生成数字会降低可验证性。
- 提示词模板草案：若未来需要生成一句事件摘要，角色应限定为预测市场事实摘要器；输入为事件标题、开放子市场与真实价格字段；输出只允许一行不带预测建议的事实描述；禁止增加输入不存在的数字、原因或结论；缺失字段时明确省略。
- 评估样例：成功样例为输入 GPT-6 事件的 7 个开放子市场，输出仅描述各截止日期概率跨度；边界样例为趋势字段全部缺失，输出不得推断涨跌，图片应显示“暂无变化”。
- 完善方案：本次维持完全确定性的视觉渲染；未来若加入摘要提示词，应保留原始数据行作为主内容，并用固定 schema 验证输出。

## 真实用户体验假设

- 目标用户：需要在群聊发送图片前，先于本地审阅 Polymarket 查询结果排版的插件维护者。
- 核心任务：执行一个 Gradle 任务，通过现有生产代理查询 `GPT-6`，在 `build` 得到一张可直接检查的多市场事件 PNG。
- 成功标准：图片展示事件层级、真实交易量、7 个开放子市场、日期、概率、趋势、买入是/否价格；无网页导航、广告或其他无关界面；文字不裁切且中文正常。
- 易失败点：直接截图网页会引入页面变化和多余内容；逐市场请求历史会增加延迟和失败面；透明边框不受 CSS 2.1 渲染器支持；粗体中文字体可能出现缺字方框。

## 探索证据

- 查看内容：现有 `HtmlImageRenderer`、Markdown 图片渲染器、Codex 图表卡片、Polymarket 模型/客户端/预览、Gradle 任务和 UI 设计规范。
- 运行观察：生产代理实测事件详情直接提供 `volume24hr`、`liquidity`、`bestBid`、`bestAsk`、`oneDayPriceChange` 和 `oneWeekPriceChange`，无需逐市场请求 CLOB 历史。
- 关键证据：`polymarketImagePreview` 以关键词模式找到 3 个公共搜索候选，命中 `gpt-6-released-by`，解析 13 个子市场并筛出 7 个开放项，输出 2880×2406 PNG。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 现有结果仅为长文本，没有可审阅的多市场图片 | 无法确认未来群聊图片的视觉层级和可读性 | 用户需求与现有 `polymarketLivePreview` | fixed |
| ED-002 | P1 | 页面截图会包含导航等无关界面，并依赖网站 DOM | 输出不稳定，无法保证只显示查询结果 | 用户参考截图与现有数据模型 | fixed |
| ED-003 | P1 | openhtmltopdf 跳过 `rgba()` 边框颜色 | 卡片分组边界未实际绘制 | 首次实时预览 CSS 警告 | fixed |
| ED-004 | P1 | 38px、800 字重的中英混排标题出现中文缺字方框 | 图片标题不可读 | 首次 PNG 目视检查 | fixed |
| ED-005 | P2 | 全量构建首次启动单次 Gradle 守护进程时 UDP 端口占用 | 首次验证命令失败 | `java.net.BindException: Address already in use` | fixed by retry |

## 本次预开发改动

- 改动摘要：扩展 Gamma 事件和市场盘口/趋势字段；新增 CSS 2.1 表格布局的原创静态事件卡；新增确定性 PNG 回归测试和生产代理图片预览 Gradle 任务；更新使用文档。
- 用户可感知结果：运行 `./gradlew.bat polymarketImagePreview` 即可在 `build/polymarket-event-preview.png` 查看 GPT-6 多市场结果图片，不会访问或截图 Polymarket 网页。
- 受影响区域：Polymarket 数据模型与解码、图片卡生成器、测试预览入口、Gradle 验证任务和 Polymarket 使用文档；mirai 发送流程未修改。

## 验证结果

- 自动验证：`PolymarketEventCardTest` 与 `PolymarketJsonCodecTest` 定向测试通过；`./gradlew.bat --no-daemon test buildPlugin` 通过；`git diff --check` 无错误，仅报告工作区既有 LF/CRLF 转换提示。
- 手动验证：通过 `examples/webhook_config.yml` 的生产代理运行 `polymarketImagePreview`，输出 `build/polymarket-event-preview.png`，大小 218018 字节、尺寸 2880×2406；目视确认标题中文正常、7 行无裁切、日期排序正确、概率条和盘口/趋势清晰。
- 文档验证：`docs/POLYMARKET_USAGE.md` 增加图片预览命令、可覆盖参数、字段回退规则和“不接入 mirai”的当前边界。
- 提示词验证：运行时未使用提示词。确定性成功样例覆盖上涨、下跌、小于 1% 概率和英文日期；边界样例覆盖零/缺失交易量、无趋势和盘口回退。
- 未验证项：未向真实 mirai 群发送图片，符合用户本阶段仅输出到 `build` 的限制。

## 残留风险

- 图片渲染依赖运行环境字体；当前 Windows 环境的微软雅黑可正常显示，其他部署环境若缺少 CJK 字体可能需要显式打包字体。
- Gamma 若同时缺失盘口与 `outcomePrices`，对应价格会显示 `-`；这比推测价格更可靠。
- 当前只渲染开放子市场；事件没有开放项时显示明确空状态，不展示已结算市场。

## 后续建议

- 用户确认图片样式后，再将 PNG 字节接入 Polymarket 动作的 mirai 图片上传与发送路径，并为文本/图片响应增加独立配置开关。
