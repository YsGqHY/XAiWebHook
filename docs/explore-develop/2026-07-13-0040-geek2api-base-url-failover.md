---
skill: explore-develop
date: 2026-07-13 00:40
project: E:\Desktop\IDEA\XAiWebHook
scope: geek2api-base-url-failover
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：geek2api-base-url-failover

## 调用背景

- 用户目标：hk3.geek2api.com 服务器已到期，Geek2Api 站点需要改用 hk5，并支持 hk、hk2、hk4、hk5 多域名列表轮询 failover。
- 项目路径：E:\Desktop\IDEA\XAiWebHook
- 本次范围：`send_webpage_screenshot` 的 Geek2Api CLI Bridge 鉴权、localStorage 注入、页面导航、默认配置、完整示例、README 与 CLI Bridge 文档。
- 明确约束：保留旧单 URL 配置兼容；不新增依赖；不把 hk3 作为当前可用候选域名。

## 项目快照

- 技术栈：Kotlin、mirai-console 插件、Gradle Kotlin DSL、SnakeYAML、Playwright Java。
- 运行入口：`./gradlew test`、`./gradlew build`；当前 shell 默认 Java 为 25，需临时使用 `C:/Program Files/Java/jdk-14` 执行 Gradle 7.3。
- 关键模块：`WebPageScreenshotAction.kt`、`webhook_config.yml`、`examples/webhook_config.geek2api-cli-bridge.yml`、`WebPageScreenshotActionTest.kt`、README、CLI Bridge 文档。
- 初始风险：`browser.allowed_hosts` 虽已是列表，但 CLI Bridge URL、localStorage origin 和 goto URL 仍绑定 hk3；只替换成 hk5 会保留单点故障。

## 项目文档与提示词需求

- 现有文档设计：README 提供功能说明和核心 YAML 示例，`docs/CLI_BRIDGE_DESKTOP_INTEGRATION.md` 提供 CLI Bridge 协议说明，`examples/` 提供可复制运行配置。
- 文档缺口：当前可复制示例仍使用 hk3 单域名，且未说明同一站点多 base URL 如何与 CLI Bridge、localStorage、goto 一起切换。
- 现有提示词资产：未发现现有提示词资产；本项目当前改动不涉及 AI/Agent 提示词执行链。
- 提示词需求：本次无需新增提示词；若未来将故障诊断交给 AI，应输入配置片段、最近错误、可用域名列表，并输出安全可执行的 YAML 修正建议。
- 提示词模板草案：角色为 XAiWebHook 配置诊断助手；任务为根据错误和候选域名生成最小 YAML diff；约束为不得输出真实 token，不得建议任意脚本执行；失败兜底为要求用户提供脱敏配置和日志摘要。
- 评估样例：成功样例为输入 hk3 连接失败与 hk5/hk/hk2/hk4 可用，输出新增 `base_urls` 并把固定 URL 改为 hk5；边界样例为输入 token 或账号密码，输出必须脱敏且不得回显凭据。
- 完善方案：本次未实施提示词资产；已通过 README、CLI Bridge 文档和完整 YAML 示例补足多域 failover 配置说明。

## 真实用户体验假设

- 目标用户：维护 mirai 机器人并通过群聊/HTTP 触发 Geek2Api 监控截图的管理员。
- 核心任务：发送“监控截图”后，插件能完成 CLI Bridge 授权或复用缓存，并访问可用 Geek2Api 域名截图回传。
- 成功标准：hk3 不再被访问；hk5 作为主用；当前候选域名网络/导航失败时自动尝试下一个候选；旧单域配置仍可解析。
- 易失败点：仅修改 `allowed_hosts` 不会改变实际 URL；localStorage origin 与 goto 域名不一致会导致页面仍未登录；不同域名复用同一 session key 会污染缓存。

## 探索证据

- 查看内容：默认 `webhook_config.yml`、完整示例、README、CLI Bridge 文档、`WebPageScreenshotAction.kt`、`WebPageCliBridgeAuth.kt`、`WebPageSessionCache.kt`、相关单元测试。
- 运行观察：首次 `./gradlew test` 使用默认 Java 25 时在 Gradle Kotlin DSL 配置阶段失败，异常为 `JavaVersion.parse` 不能解析版本 25；临时切换 JDK 14 后测试和构建通过。
- 关键证据：`allowed_hosts` 已支持多个 host，但 `auth.cli_bridge.*_url`、`auth.local_storage.origin` 和 `steps.goto.url` 均是单一绝对 URL，原先无法因 hk3 失效自动改用其它域名。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | Geek2Api 默认实例把认证、刷新、localStorage 和页面导航绑定到 hk3 | hk3 到期后截图链路整体失败 | `webhook_config.yml` 与示例中的 hk3 URL | fixed |
| ED-002 | P1 | 仅有 allowlist 多域名，缺少同站点 URL 候选与 failover 机制 | 用户需要手工改多个 URL，且下次单点不可用仍中断 | `WebPageScreenshotAction.parseSpec` 与 `runStep` 使用单 URL | fixed |
| ED-003 | P1 | 多域名 origin 共享同一个 session key 会导致 localStorage/cache 跨 origin 混用 | 授权态可能写入或读取错误域名，造成页面未登录 | session/cache key 只按 `session_key` 维护 | fixed |
| ED-004 | P2 | README 与 CLI Bridge 文档仍以 hk3 作为当前可复制基址 | 用户复制文档后继续访问已到期域名 | README、CLI Bridge 文档搜索结果 | fixed |

## 本次预开发改动

- 改动摘要：新增 `send_webpage_screenshot.base_urls`，运行时按候选 base URL 重写同站点 URL，并在可 failover 的请求/导航/截图错误后尝试下一个候选；每个候选域名使用独立 session/cache key；默认与示例配置改为 hk5 主用，hk、hk2、hk4 备用。
- 用户可感知结果：hk3 到期后，默认配置不再访问 hk3；用户可配置四个 Geek2Api 域名，当前候选失败时自动切换，避免单点域名故障直接终止截图任务。
- 受影响区域：网页截图动作解析与执行、CLI Bridge 配置使用方式、默认资源 YAML、完整示例、README、CLI Bridge 文档、网页截图测试。

## 验证结果

- 自动验证：`JAVA_HOME="/c/Program Files/Java/jdk-14" PATH="/c/Program Files/Java/jdk-14/bin:$PATH" ./gradlew test` 通过。
- 自动验证：`JAVA_HOME="/c/Program Files/Java/jdk-14" PATH="/c/Program Files/Java/jdk-14/bin:$PATH" ./gradlew build` 通过。
- 手动验证：未访问真实 Geek2Api 生产域名；本次通过配置解析、URL 重写单元测试和构建验证覆盖多域配置形态，避免依赖真实账号和外部网络。
- 文档验证：README、CLI Bridge 文档、默认 YAML、完整示例均已更新为 hk5 主用与 hk/hk2/hk4 备用；活动文档不再包含 `https://hk3.geek2api.com`。
- 提示词验证：不涉及运行时提示词；仅记录未来诊断提示词需求与成功/边界样例。
- 未验证项：没有执行真实浏览器访问 hk5/hk/hk2/hk4 的端到端截图，因为需要真实 Geek2Api 登录状态和外部网络可用性。

## 残留风险

- 首次授权在某个备用域名完成后，该域名会有独立缓存；严格轮询可能让不同域名逐步建立各自登录态，这是隔离 origin 的必要代价。
- failover 只对网络、5xx、超时和浏览器可重试类错误生效；认证配置错误、401/400、授权超时等确定性错误不会盲目切换，以避免重复打开授权页或隐藏真实配置问题。
- 已部署环境的旧 `webhook_config.yml` 不会被新 JAR 自动覆盖，需要手动同步 `base_urls` 和 hk5 URL 后执行 `/xwebhook reload`。

## 后续建议

- 增加 `/xwebhook status` 中当前截图候选 base URL 和最近 failover 原因的可观测输出。
- 如需进一步降低首次授权成本，可增加 sticky-success 模式，让成功域名在一段时间内优先使用，而不是每次成功后推进轮询游标。
