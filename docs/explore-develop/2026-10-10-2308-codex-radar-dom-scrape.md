---
skill: explore-develop
date: 2026-10-10 23:08
project: XAiWebHook
scope: codex-radar-dom-scrape
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Codex Radar DOM 抓取

## 调用背景

- 用户目标：移除旧 Codex Radar API 请求，改为抓取首页指定 DOM 中的雷达分和社区体感分，并合成单张清晰图片。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：Codex Radar 抓取、浏览器 DOM 查询、分数解析、图片渲染、动作兼容、配置、文档和测试。
- 明确约束：不新增任意脚本执行能力；保留现有 `send_codex_radar_report` 调用入口和群/好友消息发送方式。

## 项目快照

- 技术栈：Kotlin、Gradle、mirai-console、Playwright、openhtmltopdf、SnakeYAML、JUnit 5。
- 运行入口：`./gradlew.bat test`、`./gradlew.bat buildPlugin`、`./gradlew.bat codexRadarPreview`。
- 关键模块：[CodexRadarAction.kt](src/main/kotlin/kim/hhhhhy/x/webhook/action/codex/CodexRadarAction.kt)、[CodexRadarParser.kt](src/main/kotlin/kim/hhhhhy/x/webhook/action/codex/CodexRadarParser.kt)、[CodexRadarChart.kt](src/main/kotlin/kim/hhhhhy/x/webhook/action/codex/CodexRadarChart.kt)、[WebPageScreenshotAction.kt](src/main/kotlin/kim/hhhhhy/x/webhook/action/WebPageScreenshotAction.kt)。
- 初始风险：站点分数由前端动态填充，原始 HTML 可能只有占位节点；实时访问依赖浏览器、网络和代理。

## 项目文档与提示词需求

- 现有文档设计：README 提供安装和配置入口，`docs/` 保存功能说明与 explore-develop 决策记录，默认 YAML 保存带中文注释的完整示例。
- 文档缺口：旧 Codex 说明围绕 JSON/API、IQ 和降智预警展开，未说明动态 DOM、浏览器前置条件和两种新量表。
- 现有提示词资产：未发现现有提示词资产；本功能是确定性 DOM 解析、数值校验和图片渲染，不调用 LLM。
- 提示词需求：不适用。输入是页面 DOM 文本，输出是固定数据模型、群聊摘要和 PNG 图片；任意脚本执行不在范围内。
- 提示词模板草案：不适用。
- 评估样例：成功样例为页面动态加载后返回有效雷达分和社区体感分；失败样例为页面超时、目标区块未就绪、占位文本或越界分数，均应返回类别明确的失败信息。
- 完善方案：以 selector/XPath、等待状态、占位过滤和数值范围校验维护此功能，不把页面脚本或旧 API 响应直接暴露给 action。

## 真实用户体验假设

- 目标用户：通过群聊“降智检测”触发 Codex Radar 查询的机器人使用者，以及维护浏览器/代理配置的管理员。
- 核心任务：一次消息中看到雷达分和社区体感分，并能区分两者的量表和评分人数。
- 成功标准：图片同时包含两个明确标题、模型/档位/分数，缺失项显示破折号而不是伪造 0；失败时用户得到脱敏提示，管理员能从日志判断网络、DOM 或数据错误。
- 易失败点：浏览器未启用、`codexradar.com` 未加入允许域名、动态页面尚未填充、部分档位明确不支持或分数占位。

## 探索证据

- 查看内容：旧 Codex action、JSON parser/model、现有浏览器查询链路、默认 YAML、README、Codex 测试和实时首页 DOM。
- 运行观察：无代理时实时 Playwright 导航超时；使用本地代理后页面成功加载，动态 DOM 返回 2 条有效雷达分和 39 条社区体感分，并成功生成 `build/codex-radar-preview.png`。
- 关键证据：页面目标区块分别对应 `//*[@id="intelligence-efficiency"]/div[2]` 和 `//*[@id="model-ratings"]/div[3]`；页面 ready 状态出现后仍可能保留“—/暂无”占位，因此等待条件必须进一步要求有效分数节点。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 旧实现直接依赖两个 Codex Radar JSON/API 地址 | API 失败或 schema 变化会让动作完全不可用，无法得到页面当前展示分数 | 旧 Codex action/parser | fixed |
| ED-002 | P1 | 页面动态数据未加载时，容器存在但数据仍是占位符 | 过早生成空报告或误报成功 | 实时页面和 Playwright 预览 | fixed |
| ED-003 | P1 | 旧图片只展示单一数据体系，未合并社区体感分 | 用户无法同时比较页面中的两类分数 | 旧图表结构与用户需求 | fixed |
| ED-004 | P2 | 浏览器批量 DOM 查询能力不足，只能重复导航读取不同列表 | 两类分数抓取成本高且更容易出现状态不一致 | 现有 `queryTextList` 接口 | fixed |
| ED-005 | P2 | 默认允许域名和配置文案没有覆盖 Codex Radar 页面抓取 | 新动作首次启用容易因 host/浏览器配置失败 | 默认 YAML 与旧 action 注释 | fixed |

## 本次预开发改动

- 改动摘要：新增浏览器批量文本查询；Codex action 改为访问首页并等待两个指定 DOM 区块的有效数字；加入占位过滤、有限数值和量表范围校验；保留 action/route 入口；图片改为上下两个独立矩阵面板；删除旧建议/预警模型、旧 JSON fixture 和旧 API 配置。
- 用户可感知结果：同一条消息中可收到文本摘要和一张同时包含“雷达分”“社区体感分”的图片；缺失数据不会被当作 0；网络或页面结构异常会返回脱敏失败提示。
- 受影响区域：Codex action、浏览器查询共享层、图片渲染、默认配置、README、Codex 使用文档和测试。

## 验证结果

- 自动验证：`./gradlew.bat --no-daemon test --max-workers=1` 通过，161 个测试通过；`./gradlew.bat --no-daemon buildPlugin --max-workers=1` 通过。
- 定向验证：`CodexRadarReportTest` 通过，覆盖两类分数解析、占位过滤、范围/有限数值错误、selector/超时配置、旧配置忽略、图片 HTML 和 PNG 头。
- 手动验证：`./gradlew.bat --no-daemon codexRadarPreview -Dcodex.proxy=http://127.0.0.1:7890 --max-workers=1` 通过，生成 `build/codex-radar-preview.png`；目视确认两个面板、模型/档位、评分人数、量表和页脚均清晰。
- 文档验证：新增 [CODEX_RADAR_USAGE.md](docs/CODEX_RADAR_USAGE.md)，README 增加启用条件、兼容边界和迁移说明；默认 YAML 删除旧 API URL 配置并加入 `codexradar.com`。
- 提示词验证：不适用；该功能没有提示词或 LLM 调用。
- 未验证项：未在真实 mirai 群聊发送图片；无代理实时预览受当前网络环境超时影响，但代理路径已成功完成真实页面抓取和 PNG 生成。

## 残留风险

- 页面 DOM、class、ready 状态和展示文本属于外部站点实现，未来改版仍可能触发结构校验失败；此时会安全失败，不生成误导图片。
- 实时抓取需要 Playwright 浏览器和可用出口；部署主机若无代理或 DNS/IPv6 不通，需要配置 `browser.proxy` 或 action `proxy`。
- 当前站点可能只有少量有效雷达分而有较多社区分，图片会用“—”保留缺失档位，不代表数据为零。

## 后续建议

- 若站点长期调整 DOM，可增加基于页面快照的离线 fixture 和 selector 变更告警。
- 若群聊图片过长，可新增配置项限制模型/档位显示数量，但应保持两类分数的可解释性。
