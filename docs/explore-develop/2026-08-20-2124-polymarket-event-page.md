---
skill: explore-develop
date: 2026-08-20 21:24
project: XAiWebHook
scope: polymarket-event-page
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Polymarket 事件页查询

## 调用背景

- 用户目标：在本地测试 Polymarket 市场功能，查询 `https://polymarket.com/zh/event/gpt-6-released-by` 页面信息，并在必要时修复功能问题。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：Polymarket 事件页 URL 识别、Gamma 事件接口、CLOB 历史接口、开放子市场排序、中文摘要、本地实时验证入口和用户文档。
- 明确约束：使用生产配置中的 `http://127.0.0.1:7890`；不隐式直连；不新增依赖；不回退工作区既有改动。

## 项目快照

- 技术栈：Kotlin 1.9.22、Gradle 7.3.3、mirai-console 2.16.0、JVM 目标 11、kotlinx.serialization、SnakeYAML。
- 运行入口：`./gradlew.bat test`、`./gradlew.bat buildPlugin`、`./gradlew.bat polymarketLivePreview`。
- 关键模块：`PolymarketClient`、`PolymarketModels`、`PolymarketFormatter`、`PolymarketSearchAction`、生产与默认 YAML 配置。
- 初始风险：工作区包含未提交的上一阶段代理与 Polymarket 改动；生产配置为未跟踪文件；Gamma/CLOB 为实时外部数据；Windows 控制台默认代码页会损坏中文审计输出。

## 项目文档与提示词需求

- 现有文档设计：`README.md` 提供项目入口，`docs/POLYMARKET_USAGE.md` 提供查询配置与模板参考，`docs/explore-develop/` 保存决策级调用记录，默认 YAML 以中文注释说明配置项。
- 文档缺口：原文档只说明关键词市场搜索，没有事件页 URL 查询、事件子市场输出、实时验证任务或 Gamma 日期字段异常处理说明。
- 现有提示词资产：本功能是确定性的 HTTP API 查询和模板渲染，未发现也不需要 AI/Agent 提示词资产。
- 提示词需求：不适用；不应为确定性市场查询引入提示词或模型推理。
- 提示词模板草案：不适用；输入由命令前缀、官方事件页 URL/关键词和 YAML 配置组成，输出由安全模板 formatter 生成。
- 评估样例：成功样例为 `poly https://polymarket.com/zh/event/gpt-6-released-by`，应返回事件及开放子市场；边界样例为非 Polymarket 域名或多余路径 URL，不得进入事件 API 分支。
- 完善方案：在用户指南中新增事件页 URL、模板变量、日期优先级和 `polymarketLivePreview` 说明；后续继续以单元测试和显式 live 任务维护，不把外部网络纳入默认测试。

## 真实用户体验假设

- 目标用户：在 mirai 群内查询预测市场的普通成员，以及维护代理和路由配置的插件管理员。
- 核心任务：用户粘贴 Polymarket 事件页 URL，机器人返回该事件当前仍开放的各截止日期市场、价格和近期历史。
- 成功标准：事件能通过配置代理访问；结果不漏掉事件子市场；日期与问题文本一致；低价市场不显示为 `0¢`；失败时不静默绕过代理。
- 易失败点：关键词分页不包含目标事件；事件页是多市场聚合而非单市场；Gamma 日期字段可能与问题文本错配；Windows 控制台可能损坏中文输出。

## 探索证据

- 查看内容：Polymarket 客户端、模型/JSON 解码、搜索动作、formatter、配置解析、生产 YAML、现有测试和用户文档。
- 运行观察：经生产代理请求 `/markets?closed=false` 前 300 条时，`GPT-6` 命中 0 条；按事件 slug 请求返回 13 个子市场，其中 7 个开放；选中主市场的 CLOB `prices-history` 返回 1441 个点。
- 关键证据：Gamma 对“2026 年 9 月 30 日前发布”市场返回了错误的 `endDateIso=2026-06-30`；事件根节点 `endDate` 仍为已关闭的 2025 年市场日期；整数美分格式会把 0.35¢ 显示为 0¢。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 当前搜索只分页读取 `/markets`，不识别事件页 URL | 目标事件在前 300 个开放市场中命中 0 条，用户得到“无结果” | 生产代理实时请求与现有 `fetchCandidateMarkets` | fixed |
| ED-002 | P1 | 事件是多子市场集合，但原结果模型只支持一个市场 | 用户无法看到各截止日期的不同概率 | Gamma 事件返回 13 个嵌套市场 | fixed |
| ED-003 | P1 | Gamma 的部分 `endDateIso` 与问题日期错配 | 子市场被错误排序并显示错误截止日期 | 9 月 30 日问题返回 6 月 30 日 API 日期 | fixed |
| ED-004 | P1 | 默认价格格式直接截断为整数美分/概率 | 0.35¢ 被显示为 0¢，二元价格可能不合计 100¢ | CLOB 实时历史与 formatter 输出 | fixed |
| ED-005 | P2 | Gradle/Windows 控制台输出中文时发生代码页损坏 | 实时验证结果不可审计 | 重定向输出含非 UTF-8 字节 | fixed |

## 本次预开发改动

- 改动摘要：增加官方 Polymarket 事件页 URL 解析、Gamma 事件 slug 请求、事件/子市场模型、开放市场排序、事件中文摘要和自定义模板变量。
- 用户可感知结果：`poly <Polymarket 事件页 URL>` 可返回完整开放子市场列表、精确价格、有效截止日期和主市场按日历史。
- 受影响区域：Polymarket 客户端、数据模型、搜索动作、formatter、测试、Gradle 实时验证任务、默认/生产配置注释和用户指南。
- 风险控制：页面 URL 只提取官方 host 下的事件 slug；实际请求主机仍来自 Gamma/CLOB 配置；普通关键词分页搜索保持原行为。

## 验证结果

- 自动验证：Polymarket 与查询行为定向测试通过；`./gradlew.bat test buildPlugin` 成功；`git diff --check` 无错误，仅有既有 LF/CRLF 转换提示。
- 手动验证：`./gradlew.bat polymarketLivePreview` 经 `http://127.0.0.1:7890` 成功；事件 slug 为 `gpt-6-released-by`，13 个子市场中选出 7 个开放市场，主市场为 `will-gpt-6-be-released-by-august-21-2026`，CLOB 历史 1441 点。
- 文档验证：用户指南新增事件页 URL、模板字段、日期回退、实时任务和 UTF-8 输出位置；默认与生产 YAML 路由注释均给出事件页命令示例。
- 提示词验证：不适用；成功样例和非官方 URL 边界样例由确定性单元测试覆盖。
- 未验证项：未启动真实 mirai bot 向群聊发送消息；本环境没有在线 bot 会话，但动作层依赖的解析、请求、排序和 formatter 已分别覆盖。

## 残留风险

- Gamma 本地化标题当前返回“GPT-6由...发布？”，插件不自行改写第三方标题。
- 实时价格和开放市场数量会随市场状态变化，本记录仅代表 2026 年 8 月 20 日的验证结果。
- 普通 `poly GPT-6` 仍使用市场分页搜索；对于聚合事件，粘贴官方事件页 URL 是确定性路径。

## 后续建议

- 如需让普通关键词也覆盖事件，可后续增加独立的 Gamma 事件搜索策略，并设置请求上限和去重规则。
- 在具备测试 bot 的环境中补一次真实群消息端到端验证，确认长事件摘要未触发平台消息长度限制。
