---
skill: explore-develop
date: 2026-08-20 22:25
project: XAiWebHook
scope: polymarket-keyword-event-search
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Polymarket 关键词事件搜索

## 调用背景

- 用户目标：修复 `poly GPT-6` 只扫描 `/markets?closed=false` 前 300 条、无法命中实际存在的聚合事件 `gpt-6-released-by` 的问题。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：Polymarket 普通关键词搜索、事件相关性排序、市场分页回退、实时预览和回归测试。
- 明确约束：保留强制大模型白名单与独立生产代理；无事件命中时继续使用原市场分页；不修改 Model Plaza 通用过滤逻辑；不还原工作区既有变更。

## 项目快照

- 技术栈：Kotlin、Gradle、mirai-console、JDK HttpClient、kotlinx.serialization、JUnit 5。
- 运行入口：`./gradlew.bat test`、`./gradlew.bat buildPlugin`、`./gradlew.bat polymarketLivePreview`。
- 关键模块：`PolymarketClient`、`PolymarketJsonCodec`、`PolymarketSearchAction`、`PolymarketLivePreview`。
- 初始风险：工作区包含本轮之前的代理、白名单和事件页支持变更；目标事件不在默认市场分页前 300 条，继续扩大盲扫页数不能稳定解决问题。

## 项目文档与提示词需求

- 现有文档设计：`docs/POLYMARKET_USAGE.md` 说明 Polymarket 使用方式，默认与示例 YAML 以中文注释维护功能配置，`docs/explore-develop/` 保存探索开发决策记录。
- 文档缺口：实时预览任务仍默认传入事件 URL，只验证直达事件分支，不能复现群聊中的普通关键词路径。
- 现有提示词资产：本功能未发现 LLM 系统提示词或 Agent 提示词；搜索、过滤和排序均为确定性代码。
- 提示词需求：当前无需引入提示词。若未来增加语义重排，输入应限定为白名单通过后的模型关键词和 Gamma 候选事件，输出必须是候选 slug 或明确无匹配，不允许生成候选之外的事件。
- 提示词模板草案：角色为 Polymarket 事件候选重排器；任务是从给定候选中选择与模型关键词最相关且仍开放的事件；输入变量为 `keyword`、`events[].slug/title/markets[].question`；输出结构为 `{ "slug": string|null, "reason": string }`；候选外 slug 和非模型市场必须拒绝；无可靠匹配时返回 `null`。
- 评估样例：成功样例为输入 `GPT-6`，候选含 `gpt-6-released-by` 和 ChatGPT 宕机事件，期望选择 `gpt-6-released-by`；边界样例为输入 `bitcoin`，应在进入候选搜索前被强制模型白名单拒绝。
- 完善方案：本次没有引入提示词，而是采用可测试的规范化字面相关性排序；未来若引入语义层，也必须保留白名单和候选约束作为前置硬边界。

## 真实用户体验假设

- 目标用户：在已启用群内使用 `poly <模型名>` 查询 Polymarket 的机器人用户。
- 核心任务：输入 `poly GPT-6` 后获得 GPT-6 聚合事件、全部开放子市场和主市场价格历史。
- 成功标准：请求先命中公共事件搜索，选择 `gpt-6-released-by`，返回中文事件页和 7 个开放子市场，不再盲扫三页市场后显示无结果。
- 易失败点：聚合事件不在市场分页窗口；公共搜索可能返回语义相近但不含目标模型名的事件；公共搜索接口瞬时失败；预览入口可能错误地绕过关键词分支。

## 探索证据

- 查看内容：Polymarket 客户端、JSON 解码、动作分支、事件页解析、排序、实时预览、Gradle 任务和相关测试。
- 运行观察：用户日志只出现 offset 0、100、200 的三次市场分页。生产代理实测 `/public-search?q=GPT-6&events_status=active` 返回 3 个事件，首个事件 slug 为 `gpt-6-released-by`，并携带 13 个子市场。
- 关键证据：修改后的实时预览输出 `search_mode=keyword`、`public_search_events=3`、`event_slug=gpt-6-released-by`、`event_markets=13`、`selected_markets=7`、`history_points=1441`，且未执行 `/markets` 分页。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 普通关键词仅扫描有限市场分页，不搜索聚合事件 | `poly GPT-6` 对真实存在事件错误返回无结果 | 用户日志和 Gamma 实测 | fixed |
| ED-002 | P1 | 实时预览硬编码事件 URL，绕过关键词搜索 | 验证成功不能证明群聊输入已修复 | `build.gradle.kts` 旧任务参数与首次 `search_mode=url` 输出 | fixed |
| ED-003 | P1 | 公共搜索可能返回 ChatGPT 等语义相关但非字面命中的事件 | 可能向用户返回错误预测主题 | GPT-6 搜索同时返回 3 个事件 | fixed |

## 本次预开发改动

- 改动摘要：新增 Gamma `/public-search` 事件请求和解码；普通关键词先搜索开放事件，再按规范化标题、slug、子市场问题和描述排序；仅接受包含目标模型关键词的事件；无相关事件或公共搜索异常时回退原市场分页。
- 用户可感知结果：`poly GPT-6` 会命中 `gpt-6-released-by`，随后按 slug 获取本地化详情并展示事件汇总，不再依赖该事件是否出现在市场列表前 300 条。
- 受影响区域：Polymarket 客户端、事件 JSON 解码、搜索动作、实时预览任务和 Polymarket 回归测试；Model Plaza 未改动。

## 验证结果

- 自动验证：Polymarket 客户端、JSON 解码和动作行为定向测试通过；全量 `./gradlew.bat test` 通过；`./gradlew.bat buildPlugin` 通过；`git diff --check` 无错误，仅报告既有 LF/CRLF 转换提示。
- 手动验证：通过 `examples/webhook_config.yml` 的 `http://127.0.0.1:7890` 代理运行 `polymarketLivePreview`，关键词模式返回 3 个公共搜索事件并命中 `gpt-6-released-by`；事件有 13 个子市场，筛出 7 个开放市场，主市场历史有 1441 点。
- 文档验证：生成摘要以 UTF-8 写入 `build/tmp/polymarket-live/event-summary.txt`，包含中文事件标题、官方中文事件页、7 个子市场、有效截止日期和历史价格；本次调用记录使用独立新文件，不覆盖历史记录。
- 提示词验证：当前无运行时提示词。确定性成功样例 `GPT-6` 命中目标事件；边界样例覆盖无关公共搜索结果排除、关闭事件排除和 `bitcoin` 白名单拒绝。
- 未验证项：未直接连接真实 mirai 群发送消息；网络、解析、排序、格式化和生产配置代理路径已由实时预览覆盖。

## 残留风险

- Gamma `/public-search` 若不可用或响应格式改变，会记录警告并回退旧市场分页；此时不在分页窗口内的聚合事件仍可能暂时无结果。
- 当前相关性判断采用规范化字面包含，稳定且可预测，但无法命中完全不含用户模型别名的语义别称；强制模型白名单可继续通过补充别名解决。

## 后续建议

- 观察生产日志中的公共搜索失败率；若上游稳定性不足，可增加短时事件搜索缓存，但不应通过无限扩大 `/markets` 页数替代事件接口。
