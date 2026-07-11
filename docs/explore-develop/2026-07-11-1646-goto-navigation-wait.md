---
skill: explore-develop
date: 2026-07-11 16:46
project: E:\Desktop\IDEA\XAiWebHook
scope: goto-navigation-wait
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：goto-navigation-wait

## 调用背景

- 用户目标：修复 `send_webpage_screenshot` 已到达 `https://hk3.geek2api.com/monitor` 且能读取正确标题，但 `goto` 等待 `DOMContentLoaded` 30 秒后仍误判失败的问题。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：Playwright `goto` 步骤解析与执行、默认 Geek2Api 配置、完整示例、README、浏览器回归测试。
- 明确约束：不能简单吞掉所有导航超时；DNS、连接、TLS、服务器无响应或未提交响应时仍必须失败；保护工作区现有冷却与截图改动。

## 项目快照

- 技术栈：Kotlin 1.9.22、mirai-console 2.16.0、Playwright Java 1.60.0、Gradle 7.3.3、JVM 11 目标。
- 运行入口：`XAI_WEBHOOK_BROWSER_IT=true ./gradlew.bat clean test buildPlugin`。
- 关键模块：`WebPageScreenshotAction.kt`、`WebPageScreenshotActionTest.kt`、默认 `webhook_config.yml`、Geek2Api 完整示例和 README。
- 初始风险：运行日志证明页面 URL 与标题已经正确，但原实现对所有 `goto` 固定使用 `WaitUntilState.DOMCONTENTLOADED`；站点生命周期事件不稳定会在页面实际可用时造成整条动作失败。

## 项目文档与提示词需求

- 现有文档设计：README 说明浏览器动作与排障，默认 YAML 和 `examples/` 提供可执行步骤配置。
- 文档缺口：原文档没有说明 `goto` 的导航等待阶段，也没有区分“网络导航成功”和“业务页面就绪”。
- 现有提示词资产：未发现 AI 提示词资产；浏览器步骤是确定性配置。
- 提示词需求：本功能不需要生成式提示词；新增的是枚举型 `wait_until` 配置。
- 提示词模板草案：不适用。
- 评估样例：成功样例为目标响应已提交、脚本延迟 2 秒阻塞 `DOMContentLoaded`，仍进入后续元素等待并截图；失败样例为服务器 300 毫秒内未提交任何响应，goto 继续抛出超时。
- 完善方案：README 明确 `commit`、`domcontentloaded`、`load`、`networkidle` 的用途，并将业务就绪交给显式 `wait` 和截图选择器。

## 真实用户体验假设

- 目标用户：通过群聊或 incoming 触发 Geek2Api 监控截图的插件管理员和普通使用者。
- 核心任务：页面网络导航成功后等待真实监控卡片加载，最终收到截图。
- 成功标准：SPA 生命周期事件延迟不会让已加载页面误报失败；真正没有响应的目标仍快速、明确失败；旧运行时 YAML 升级 JAR 后无需新增字段即可获得修复。
- 易失败点：直接忽略 `TimeoutError` 会掩盖真实网络失败；仅提高 30 秒超时不能解决不触发或持续重置的生命周期事件；等待 `networkidle` 对持续轮询页面更加不可靠。

## 探索证据

- 查看内容：`parseStep`、`BrowserStep`、`runStep`、`stepError`、现有骨架屏等待测试、默认配置和 README。
- 运行观察：用户日志中的最终 URL 为目标 `/monitor`，标题为正确 Geek2Api 页面，但调用日志明确显示等待 `domcontentloaded` 超时。
- 关键证据：原 `runStep` 在所有 goto 中硬编码 `WaitUntilState.DOMCONTENTLOADED`；动作后续已经存在主元素和真实完成态的显式 `wait`，因此生命周期等待重复且不等同于业务就绪。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | goto 固定等待 `DOMContentLoaded` | 页面已到达仍在 30 秒后失败，用户收不到截图 | 用户堆栈与 `runStep` 硬编码 | fixed |
| ED-002 | P1 | 网络导航与业务就绪混在同一等待条件 | 调大超时仍可能失败，错误定位误导 | 后续已有精确元素等待 | fixed |
| ED-003 | P2 | 错误日志不显示实际 waitUntil | 排障时无法区分 commit/load 等策略 | `stepError` 只输出 URL | fixed |
| ED-004 | P2 | 配置和 README 未暴露导航等待策略 | 用户无法针对不同站点调整 | 原步骤 schema 无字段 | fixed |

## 本次预开发改动

- 改动摘要：为 goto 新增 `wait_until`，支持 `commit`、`domcontentloaded`、`load`、`networkidle`；默认改为 `commit`；执行时映射到 Playwright `WaitUntilState`，错误详情包含实际策略。
- 用户可感知结果：旧 YAML 未配置新字段时也会在服务器提交目标文档后立即进入后续 `wait`，不再因 SPA 的 `DOMContentLoaded` 假超时失败。
- 受影响区域：网页截图步骤解析、Playwright 导航、错误诊断、默认配置、完整示例、README 和浏览器测试。

## 验证结果

- 自动验证：`XAI_WEBHOOK_BROWSER_IT=true JAVA_HOME='C:\Program Files\Java\jdk-14' ./gradlew.bat clean test buildPlugin` 成功。
- 自动验证明细：44 项测试、0 跳过、0 失败、0 错误；`WebPageScreenshotActionTest` 共 32 项。
- 手动验证：新增本地 HTTP 页面在 `<head>` 中加载延迟 2 秒的阻塞脚本，goto 超时设置 1 秒；默认 commit 成功进入 5 秒元素等待并生成 PNG。
- 失败路径验证：本地 HTTP 服务延迟 1.5 秒才发送响应头，goto 超时设置 300 毫秒；动作仍抛出包含 `waitUntil=commit` 和 `Timeout 300ms exceeded` 的错误。
- 文档验证：默认 YAML、完整示例与 README 均显式展示 `wait_until: "commit"`；解析测试确认旧步骤缺省值也是 commit，并拒绝未知枚举值。
- 提示词验证：不适用。
- 未验证项：未直接访问用户生产环境的 hk3 页面和账号会话；相同故障机制已通过可控浏览器场景复现并覆盖。

## 残留风险

- `commit` 只表示目标文档响应已开始，不表示业务数据完成；配置仍必须保留后续真实卡片或合法空状态的 `wait`。
- 用户若显式设置 `wait_until: "domcontentloaded"`、`load` 或 `networkidle`，仍会按其选择严格等待并可能超时。
- 旧 JAR 不认识新行为；需要替换构建产物。仅修改 YAML 不能改变旧代码中硬编码的 `DOMContentLoaded`。

## 后续建议

- 对其他长期轮询或流式页面继续使用 `commit + 显式完成态 wait`，不要用 `networkidle` 表示业务加载完成。
- 若未来出现多次客户端重定向，可增加独立的 `wait_url` 和最终 URL 断言，而不是放宽导航异常处理。
