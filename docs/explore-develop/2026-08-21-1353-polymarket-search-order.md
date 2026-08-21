---
skill: explore-develop
date: 2026-08-21 13:53
project: XAiWebHook
scope: polymarket-search-order
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Polymarket 搜索首项选择

## 调用背景

- 用户目标：`poly` 命令应根据 Polymarket 搜索返回内容选择最相近的第一个结果，与网页搜索首项一致；例如 `poly deepseek pro` 应选择 DeepSeek Pro。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：Gamma 公共事件搜索、事件候选过滤与选择、实时预览、行为测试和使用文档。
- 明确约束：保留大模型强制白名单、事件页 URL 查询、市场分页兼容回退、独立代理和图片发送行为；不新增配置或依赖。

## 项目快照

- 技术栈：Kotlin、Gradle、mirai-console、kotlinx.serialization、Java HTTP Client、JUnit 5。
- 运行入口：`polymarketLivePreview`、`test`、`buildPlugin`。
- 关键模块：`PolymarketClient.searchEvents`、`PolymarketSearchAction`、`QueryActionBehaviorTest`。
- 初始风险：工作区包含此前未提交的 Polymarket 与代理功能改动，本次仅在相关文件上增量修改，不还原既有内容。

## 项目文档与提示词需求

- 现有文档设计：`docs/POLYMARKET_USAGE.md` 面向插件维护者说明搜索、白名单、响应和验证；`docs/explore-develop/` 保存决策级调用记录。
- 文档缺口：原文描述为本地“相关性排序”，未说明 Gamma 已提供与网页一致的结果顺序，容易合理化二次重排。
- 现有提示词资产：本功能没有 LLM 或提示词资产，搜索与过滤完全由确定性代码执行。
- 提示词需求：不需要。将首项选择交给提示词会降低可重复性，并可能偏离服务端排序。
- 提示词模板草案：不适用；输入是 Gamma 已排序事件数组，输出必须确定性选择第一个有效字面相关候选。
- 评估样例：成功样例为 `deepseek` 选择 Flash 首项；边界样例为 `deepseek pro` 跳过不含完整短语的 Flash 并选择 Pro，同时忽略关闭或不相关事件。
- 完善方案：在代码注释、测试和使用文档中固定“过滤但不重排”的契约。

## 真实用户体验假设

- 目标用户：在群聊中通过 `poly <模型关键词>` 查询预测市场的成员。
- 核心任务：输入与网页相同的关键词后，收到网页搜索第一项对应的事件卡。
- 成功标准：多候选时不因交易量或本地评分改变服务端顺序；更具体关键词仍能过滤掉前方不匹配项；无相关事件时仍可进入原市场分页回退。
- 易失败点：把所有包含模型名的事件赋予相近分数后按交易量排序，会将高交易量但排名靠后的宽泛事件提前。

## 探索证据

- 查看内容：公共搜索请求与 JSON 解码、动作层事件选择、实时预览程序、现有相关性测试和使用文档。
- 运行观察：修复前生产代理执行 `deepseek`，Gamma 返回 20 个事件，但动作选择第五项 `deepseek-ipo-byptptpt-20260714175933543`。
- 关键证据：同一 Gamma 响应原始前三项依次为 DeepSeek Flash、DeepSeek Pro 和 Chatbot Arena 事件，IPO 位于第五；旧代码在字面分数后按交易量降序再次排序。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 公共搜索结果被本地评分和交易量二次排序 | `poly deepseek` 返回 IPO，而非网页第一项 Flash | 生产代理预览与 `rankEventMatches` | fixed |
| ED-002 | P1 | 方法命名和文档暗示客户端负责重新排名 | 后续维护可能再次破坏服务端顺序 | 方法名与使用文档 | fixed |
| ED-003 | P2 | 测试只覆盖单个有效候选，未覆盖顺序冲突 | 高交易量后置事件可回归为首项 | `QueryActionBehaviorTest` | fixed |

## 本次预开发改动

- 改动摘要：将公共事件候选处理改为仅过滤关闭、无效和字面无关项，严格保留 `/public-search` 原始顺序；方法更名为 `filterEventMatchesInSearchOrder`。
- 用户可感知结果：`poly deepseek` 选择网页第一项 DeepSeek Flash；`poly deepseek pro` 选择 DeepSeek Pro，不再被高交易量 IPO 抢占。
- 受影响区域：Polymarket 动作、文本与图片实时预览、行为测试和使用文档。原市场分页回退仍保留本地相关性排序。

## 验证结果

- 自动验证：`QueryActionBehaviorTest` 定向通过；`./gradlew.bat --no-daemon test buildPlugin` 全量通过。
- 手动验证：生产代理执行 `deepseek` 返回 `next-deepseek-flash-released-byptptpt`；执行 `deepseek pro` 返回 `next-deepseek-pro-released-byptptpt`，并生成包含对应事件标题、子市场和概率的摘要。
- 文档验证：使用文档明确说明公共搜索“相关性过滤但保持 Gamma 顺序”，并给出 DeepSeek 两个样例。
- 提示词验证：不适用；成功和边界样例均由确定性 JUnit 与生产代理预览覆盖。
- 未验证项：未在真实 mirai 群内执行命令；事件选择使用与动作相同的实时预览路径，最终群消息仍需部署插件后观察。

## 残留风险

- 如果 Polymarket 未来更换网页搜索接口或网页在客户端额外合并其他结果类型，Gamma `/public-search` 的 `events` 顺序可能不再完全等同网页；当前证据显示两者一致。
- 关键词过滤要求完整规范化短语连续出现。若用户输入与标题只存在语义相近但非字面相近，仍会回退市场分页，不进行不可验证的模糊推断。

## 后续建议

- 若未来需要诊断搜索差异，可在调试日志中输出前 3 个 Gamma 候选 slug，但默认日志不必增加群内噪声。
