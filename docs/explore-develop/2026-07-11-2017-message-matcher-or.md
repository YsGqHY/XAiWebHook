---
skill: explore-develop
date: 2026-07-11 20:17
project: E:\Desktop\IDEA\XAiWebHook
scope: message-matcher-or
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Message Matcher OR

## 调用背景

- 用户目标：让 outgoing route 的 `message.contains` 与 `message.starts_with` 按任意满足触发；消息包含“炸了么”，或以“状态检查”/“监控截图”开头时均执行监控截图动作。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：YAML 解析后的 `MessageMatcher`、`OutgoingRouteProcessor` 文本匹配语义、默认与完整示例、README、路由测试和 Mirai 插件包。
- 明确约束：四类消息文本规则按 OR；事件类型、群号、好友号、发送者、聚合后的 message 与 condition 等路由顶层条件继续按 AND；不影响 cooldown 和 single-flight。

## 项目快照

- 技术栈：Kotlin、mirai-console 2.16.0、SnakeYAML、Gradle 7.3.3。
- 运行入口：JDK 14 下运行 Gradle 测试；通过 `buildPlugin` 生成可部署 `.mirai2.jar`。
- 关键模块：`WebHookConfig.kt`、`OutgoingRouteProcessor.kt`、`OutgoingRouteProcessorTest.kt`、默认/完整 YAML 与 README。
- 初始风险：工作区有并行进行的截图 header 任务和其他未提交改动；旧 README 明确规定不同 message 类别之间按 AND，直接改变语义可能影响少量依赖旧行为的配置。

## 项目文档与提示词需求

- 现有文档设计：README 描述 outgoing 字段与匹配关系；默认 YAML 和完整示例提供消息触发配置。
- 文档缺口：默认 YAML 只写了“类内任一命中”，没有突出类间原先为 AND；用户自然将多个文本字段理解为触发词并集，导致配置加载正常但路由始终不匹配。
- 现有提示词资产：未发现与消息路由匹配相关的 AI 提示词资产。
- 提示词需求：不需要生成式提示词；需要明确、可预测的布尔匹配语义和可直接复制的 YAML 示例。
- 提示词模板草案：不适用。
- 评估样例：成功样例为“今天炸了么”“状态检查 渠道”“监控截图”分别命中 contains 或 starts_with；失败样例为“普通聊天消息”不命中任何文本规则。
- 完善方案：统一代码、README、默认 YAML 和完整示例为“message 内部 OR、路由顶层 AND”，并用回归测试固定语义。

## 真实用户体验假设

- 目标用户：通过群聊关键词触发监控截图的机器人管理员和群成员。
- 核心任务：用一段可读 YAML 配置多个同义触发方式，不需要写正则或重复定义多条路由。
- 成功标准：`contains: ["炸了么"]` 与 `starts_with: ["状态检查", "监控截图"]` 任意一项命中即执行；普通消息不触发；群号、事件和 condition 限制仍需同时满足。
- 易失败点：旧版本仍按 AND；替换 YAML 后未 reload；替换 JAR 后未重启；消息前存在空格或 At 时不再满足严格的 starts_with。

## 探索证据

- 查看内容：`parseMessageMatcher`、`OutgoingRoute.matches`、`MessageMatcher` 数据类、现有 cooldown/single-flight 测试、README 和两个 YAML 示例。
- 运行观察：旧实现分别对每个非空类别执行 `none -> return false`，因此 contains 与 starts_with 同时配置时必须两类都命中；“炸了么”只满足 contains，“状态检查”与“监控截图”只满足 starts_with，均会被另一类别否决。
- 关键证据：修改后四类规则先汇总为一个 `messageMatches` OR 表达式；回归测试依次输入三条应触发消息和一条普通消息，动作执行计数严格为 3。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 不同 message 类别按 AND，和用户配置触发词并集的预期冲突 | 三种独立触发词全部不生效 | `OutgoingRoute.matches` 四段独立失败判断 | fixed |
| ED-002 | P2 | 默认 YAML 注释没有完整说明类别组合关系 | 配置可正常加载但行为难以理解 | 默认配置与用户反馈 | fixed |
| ED-003 | P2 | README 的旧 AND 语义与新产品要求冲突 | 运维可能继续使用正则或重复路由绕过 | README outgoing 字段说明 | fixed |

## 本次预开发改动

- 改动摘要：将 `contains`、`starts_with`、`ends_with`、`regex` 所有已配置项合并为 OR；无文本匹配器时继续跳过 message 条件；路由顶层其他条件不变。
- 用户可感知结果：用户给出的 YAML 可以直接表达三个触发入口，无需正则或重复 route。
- 受影响区域：outgoing 文本匹配、默认监控截图 route、完整 CLI Bridge 示例、README 和测试。

## 验证结果

- 自动验证：新增 `messageMatchersUseOrAcrossContainsAndStartsWith` 测试通过；完整 `OutgoingRouteProcessorTest` 测试类通过；`buildPlugin --rerun-tasks` 成功。
- 手动验证：插件内置 `webhook_config.yml` 已核验包含 `contains: ["炸了么"]` 和 `starts_with: ["状态检查", "监控截图"]`。
- 文档验证：已搜索并移除“多类之间同时满足”等旧语义；README 追加用户配置的直接示例。
- 提示词验证：不涉及 AI 提示词；三条成功消息与一条失败消息构成行为样例。
- 未验证项：未获得生产 Mirai 插件目录和群聊环境，无法代替用户替换 JAR、重启进程并发送真实群消息。

## 残留风险

- 这是对既有 message 类别组合语义的行为调整。只配置单一类别的现有 route 不受影响；若某 route 有意依赖多个类别同时满足，需要改用 `condition` 显式表达 AND。
- `starts_with` 对原始 `message.content` 做严格前缀比较；消息前有空格、换行或 At 时不会命中，必要时应增加对应 contains/regex 或规范发送格式。
- 生产环境必须部署新 JAR 并重启 Mirai；仅修改 YAML 或对旧插件执行 `/xwebhook reload` 不会获得新的 OR 实现。
- 后续截图 header 排除任务已完成，并与本项修改一起通过最终测试和插件构建验证。

## 后续建议

- 部署后分别发送“今天炸了么”“状态检查 渠道”“监控截图”和“普通聊天消息”进行一次生产验收。
- 若后续需要更复杂的 AND/OR 分组，优先通过 `condition` 明确表达，避免再次隐式改变基础 matcher 语义。
