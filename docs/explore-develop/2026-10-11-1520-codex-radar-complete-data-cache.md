---
skill: explore-develop
date: 2026-10-11 15:20
project: XAiWebHook
scope: codex-radar-complete-data-cache
status: partial
narrator: NarraFork
---

# Explore Develop 调用记录：Codex Radar 完整数据与缓存

## 调用背景

- 用户目标：修复 Codex Radar 页面动态 DOM 抓取，使插件只在两个分数区块完整、稳定且非占位后读取和截图，并增加缓存、过期后台刷新及失败保留旧数据。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：Codex Radar action、Playwright 浏览器文本查询、缓存、reload/close 生命周期、配置、测试和预览。
- 明确约束：不再直接请求旧 JSON/API；保持旧 action 类型、路由引用和浏览器 API 调用兼容；不把占位或缺失分数当作 0；不新增任意脚本执行。

## 项目快照

- 技术栈：Kotlin/JVM 11、mirai-console 2.16.0、Ktor、Playwright、SnakeYAML、kotlinx.serialization。
- 运行入口：`./gradlew.bat test`、`./gradlew.bat build`、`./gradlew.bat buildPlugin`、`./gradlew.bat codexRadarPreview`。
- 关键模块：`WebPageScreenshotAction`、`CodexRadarAction`、`CodexRadarCache`、`XAiWebHookBrowserApi`、`WebHookActionExecutor`。
- 初始风险：工作区已有较大的 Codex Radar 重构改动；此前存在旧 API 数据源迁移、浏览器 worker 生命周期和配置文档不同步风险。

## 项目文档与提示词需求

- 现有文档设计：README、默认 YAML、`docs/CODEX_RADAR_USAGE.md` 和浏览器/CLI Bridge 文档，主要面向插件部署者与配置维护者。
- 文档缺口：缺少动态页面尚未完成、缓存命中/过期、旧缓存保留、状态诊断字段和真实预览失败原因的说明；本次已补充。
- 现有提示词资产：未发现现有提示词资产；本功能不依赖 AI/Agent 提示词。
- 提示词需求：不适用；页面抓取采用确定性 DOM 选择器、状态属性和数值范围判定。
- 提示词模板草案：不适用。
- 评估样例：成功样例为有效缓存命中并生成 PNG；边界样例为真实页面仍含 `data-radar-query-pending=true` 和 `—`，必须等待至超时而不是生成不完整报告。
- 完善方案：使用代码级 readiness evaluator 和可观测统计替代自然语言判断；后续若页面增加稳定状态属性，只需在状态属性列表中扩展。

## 真实用户体验假设

- 目标用户：mirai 群管理员和插件配置维护者。
- 核心任务：发送“降智检测”后获得当前雷达分与社区体感分，并在源站暂时不完整时看到旧快照或明确失败提示。
- 成功标准：新数据完整后才生成图片；新鲜缓存快速返回；过期缓存不阻塞用户且后台刷新；刷新失败不破坏旧结果；reload/disable 不留下后台浏览器任务。
- 易失败点：页面异步卡片仍显示 `—`、站点把条目标为 pending、卡片数量增量变化、代理/浏览器失败、缓存文件损坏或页面结构漂移。

## 探索证据

- 查看内容：浏览器 worker 的 Page 生命周期、截图/文本查询、session cache、Codex parser/chart/action、配置解析、插件 enable/disable/reload、已有测试和预览任务。
- 运行观察：真实 `https://codexradar.com/` 页面能加载目标 DOM，但当前会留下带 `data-radar-query-pending=true` 的失败卡片；因此严格 freshness 判定在 120 秒后拒绝生成不完整快照。浏览器 DOM 检查确认无 pending 且全数值完整时可使用无显式状态标记的兼容回退。
- 关键证据：真实页面目标区块存在，雷达卡片和社区评分行可读取；失败卡片明确带 pending 属性和 `—`，说明不能把页面“有卡片”误判为完成。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 页面有卡片但仍在异步读取，旧实现会过早解析/截图 | 报告含占位分或错误数据 | 真实 DOM 的 `data-radar-query-pending=true`、`—` | fixed |
| ED-002 | P1 | 页面查询和截图路径缺少同一 Page 的稳定轮询能力 | 无法等待两类数据同时完成 | `WebPageScreenshotAction` 原有 query 只读取一次 | fixed |
| ED-003 | P1 | 缓存不存在完整快照/过期刷新/旧文件保护 | 首次请求慢，刷新失败可能影响可用性 | 原 action 无 Codex 快照缓存 | fixed |
| ED-004 | P1 | reload/close 与后台刷新存在竞态 | 插件停止后可能重新创建刷新 scope | 原生命周期无代际栅栏 | fixed |
| ED-005 | P2 | 真实源站当前有失败卡片，fresh preview 无法生成完整数据 | 预览不能宣称源站成功 | 预览日志 `placeholder=1 pending=1` | accepted：按需求拒绝伪造，保留缓存命中预览验证 |
| ED-006 | P2 | 浏览器 API 新增 DOM 属性能力可能破坏旧 JVM 构造器 | 已编译调用方可能运行时失败 | public data class 构造器变更 | fixed：保留旧主构造器并添加扩展 secondary constructor |

## 本次预开发改动

- 改动摘要：
  - `WebPageScreenshotAction` 增加同一 Page 的批量 DOM 轮询、稳定轮询次数、可选容器/条目属性读取和超时诊断。
  - `CodexRadarAction` 增加完整性判定：两区块、容器存在、卡片数量稳定、非 pending、至少一条合法分数、范围校验。
  - 新增 `CodexRadarCache`：版本化 JSON envelope、页面 URL/cache key 校验、临时文件替换、缓存 TTL、单飞刷新、后台定时刷新和生命周期代际栅栏。
  - reload/enable/disable 接入刷新 scheduler；旧自定义 action 未配置 `cache` 时仍走无缓存实时路径。
  - 补充配置注释、README、Codex Radar 使用文档、DOM 属性兼容测试、readiness/cache/预览测试。
- 用户可感知结果：页面未完成时不会错误截图；有新鲜缓存时立即返回；过期缓存先展示旧数据并后台刷新；失败消息保持脱敏，详细原因只记录诊断统计。
- 受影响区域：Codex action、浏览器 API/worker、默认配置、插件生命周期、测试和预览任务。

## 验证结果

- 自动验证：
  - `./gradlew.bat test --tests kim.hhhhhy.x.webhook.action.codex.CodexRadarReportTest --tests kim.hhhhhy.x.webhook.action.WebPageScreenshotActionTest`：通过。
  - `./gradlew.bat test`：通过。
  - `./gradlew.bat build`：通过。
  - `./gradlew.bat buildPlugin`：通过。
- 手动验证：
  - 真实 Codex Radar DOM 检查确认 pending 卡片被识别；fresh preview 在 120 秒后按预期失败，不生成伪造缓存。
  - 使用临时有效快照执行 `codexRadarPreview`：命中 `cache=hit/fresh`，成功生成 `build/codex-radar-preview.png`；PNG 已检查，包含“雷达分”和“社区体感分”两个面板。
- 文档验证：README、`docs/CODEX_RADAR_USAGE.md` 和默认 `webhook_config.yml` 已同步 `max_wait_ms`、轮询、缓存、过期刷新和诊断说明。
- 提示词验证：不适用；本功能没有提示词资产。
- 未验证项：真实源站 fresh 成功路径未完成，因为当前源站仍返回 pending/读取失败卡片；这不是插件放宽判定的理由，后续源站数据完整后可直接重跑 `codexRadarPreview -Dcodex.cache.clear=true`。

## 残留风险

- 源站若长期保留 pending/读取失败条目，实时报告会持续超时；这是严格数据完整性策略的预期行为。
- Playwright/Gradle 预览偶发遇到 Gradle lock listener 端口占用，需要先执行 `./gradlew.bat --stop` 重试。
- 页面若改变目标区块 XPath、卡片 class 或评分行 class，需更新固定选择器并补充新的 DOM 回归样例。

## 后续建议

- 在可控 fixture 页面中增加非 IT 的同页轮询测试，覆盖状态属性、卡片增量渲染和取消释放。
- 源站恢复完整数据后记录一次真实 fresh preview，并将稳定 DOM 样本纳入脱敏 fixture。
- 若缓存文件需要跨多个 page_url 共存，可将默认单文件改为按 cache key 分文件，减少不同动作共享文件时的覆盖 churn。
