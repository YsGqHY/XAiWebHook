---
skill: explore-develop
date: 2026-07-11 19:55
project: E:\Desktop\IDEA\XAiWebHook
scope: stale-mirai-font-timeout
status: partial
narrator: NarraFork
---

# Explore Develop 调用记录：Stale Mirai Font Timeout

## 调用背景

- 用户目标：检查生产日志中 `send_webpage_screenshot` 在 `taking element screenshot` / `waiting for fonts to load...` 阶段耗尽 60000 毫秒的原因。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：生产异常栈、当前源码、默认配置、普通 JAR 与 `.mirai2.jar` 字节码、Mirai 插件打包任务及部署版本差异。
- 明确约束：优先调查，不在证据不足时修改业务源码；保护工作区现有生产配置改动。

## 项目快照

- 技术栈：Kotlin、Playwright Java 1.60.0、mirai-console 2.16.0、Gradle 7.3.3。
- 运行入口：JDK 14；普通构建为 `build`，Mirai 可部署插件包必须通过 `buildPlugin` 生成。
- 关键模块：`WebPageScreenshotAction.kt`、`webhook_config.yml`、`WebPageScreenshotActionTest.kt`、`build/mirai/XAiWebHook-0.1.0.mirai2.jar`。
- 初始风险：日志使用的 JAR 版本未知；默认资源已混入生产定制值；`screenshot.timeout_ms` 和 `font_wait_timeout_ms` 均被设置为 60000，可能把字体故障等待放大到一分钟。

## 项目文档与提示词需求

- 现有文档设计：README 和 CLI Bridge 文档已经说明 Playwright 字体等待、`font_wait_timeout_ms` 的 3000 毫秒默认值、回退字体降级及截图重试。
- 文档缺口：现有构建说明只强调 `build`，没有突出 Mirai 部署必须使用 `buildPlugin` 更新 `.mirai2.jar`，容易出现普通 JAR 已更新但部署包仍为旧版。
- 现有提示词资产：未发现与本问题相关的 AI 提示词资产。
- 提示词需求：不需要生成式提示词；需要通过版本哈希、构建时间、字节码标记和启动日志提供可核验的部署诊断。
- 提示词模板草案：不适用。
- 评估样例：成功样例为部署包包含 `PW_TEST_SCREENSHOT_NO_FONTS_READY` 与 `waitForFontsBestEffort`；失败样例为异常栈仍指向 `captureOnWorker:730/736`、`screenshotError:1206` 且 Call log 停在内部字体等待。
- 完善方案：后续在构建/部署文档中明确区分 `build/libs/*.jar` 和 `build/mirai/*.mirai2.jar`，并记录可部署包 SHA-256。

## 真实用户体验假设

- 目标用户：维护 Mirai 机器人并通过群聊触发 Geek2Api 监控截图的管理员。
- 核心任务：部署字体等待修复后，即使网页字体资源卡住，也应在短暂等待后使用回退字体完成截图，而不是一分钟后失败。
- 成功标准：运行中的插件包确实包含最新字体开关、有界等待和重试逻辑；运行时字体等待设置为 3000 毫秒或 0；替换 JAR 后完整重启 Mirai。
- 易失败点：只运行 `build`、只替换普通 JAR、只执行 `/xwebhook reload`、继续使用 60000 毫秒字体等待、误把页面 selector 或 CLI Bridge 登录当作本次故障。

## 探索证据

- 查看内容：用户提供的完整异常栈、源码行号、Playwright 创建环境、字体有界等待、默认配置、测试断言、Gradle task 列表和两个 JAR 的字节码字符串及行号表。
- 运行观察：旧 `.mirai2.jar` 的 SHA-256 为 `756deb23fd10d6d8478096de098f042b290704b1bc7b54a929eb34dcb0ef0a78`，修改时间为 16:45；其行号表与生产栈的 671、687、730、736、1206 完全一致，且不存在字体禁用开关、有界等待或重试字符串。18:51 的普通 JAR 已包含修复，但不是 Mirai 部署包。
- 关键证据：执行 `buildPlugin --rerun-tasks` 后，新 `.mirai2.jar` 于 19:52 生成，SHA-256 为 `3ec09d095f2d2504b2268e3e621dca5268bb1ab7af0f73a6b99544225013df6c`；字节码包含 `PW_TEST_SCREENSHOT_NO_FONTS_READY`、`waitForFontsBestEffort`、`document.fonts.status` 和重试日志，且不再匹配旧栈行号。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
|----|--------|------|----------|------|----------|
| ED-001 | P1 | 生产运行的是旧 `.mirai2.jar` | 最新字体等待与重试修复完全未生效 | 生产栈与旧 JAR 行号表完全匹配 | fixed locally, deployment pending |
| ED-002 | P1 | 只执行普通 `build` 不会刷新 Mirai 部署包 | `build/libs` 已更新但 `build/mirai` 仍旧 | 文件时间、哈希和字节码差异 | fixed locally |
| ED-003 | P1 | 旧 Playwright 截图内部等待 `document.fonts.ready` 直至 60000 毫秒 | 元素已找到仍截图失败 | 用户 Call log 明确停在 `waiting for fonts to load...` | fixed in new package |
| ED-004 | P2 | 当时资源将 `font_wait_timeout_ms` 设置为 60000 | 新版虽会降级，但字体卡住时仍先等待一分钟 | 默认 YAML、README 和示例不一致 | fixed in final integration |
| ED-005 | P2 | 默认资源混入 production 定制，默认 YAML 回归测试失败 | 默认配置不再是安全、可复用样例 | 定向测试首先在 `session_cache_enabled` 断言失败 | fixed in final integration |

## 本次预开发改动

- 改动摘要：未修改业务源码；执行 `buildPlugin --rerun-tasks` 重新生成包含现有字体等待和重试修复的 `.mirai2.jar`。
- 用户可感知结果：部署新包并重启后，Playwright 内部无界字体等待会被关闭，插件按有界等待记录日志并使用回退字体继续截图。
- 受影响区域：仅本地构建产物 `build/mirai/XAiWebHook-0.1.0.mirai2.jar`；未替换未知位置的生产插件，也未改动用户的 60000 毫秒配置。

## 验证结果

- 自动验证：`./gradlew.bat --no-daemon buildPlugin --rerun-tasks` 成功；新包大小 387381 字节，SHA-256 为 `3ec09d095f2d2504b2268e3e621dca5268bb1ab7af0f73a6b99544225013df6c`。
- 手动验证：通过 `javap` 和类文件字符串确认新包包含字体禁用环境变量、有界字体等待、`document.fonts.status` 检查和截图重试；旧包的行号表与生产栈完全一致。
- 文档验证：README、CLI Bridge 文档和完整示例均建议 `font_wait_timeout_ms: 3000`；该调用发生时默认资源中的 60000 是工作区漂移，最终集成前已恢复为 3000。
- 提示词验证：不涉及 AI 提示词。
- 未验证项：未获得生产 Mirai 的插件目录、运行时 YAML 和认证登录态，无法代替用户完成实际 JAR 替换、进程重启及生产监控页截图；也无法仅凭异常栈定位具体哪个字体 URL 未完成。

## 残留风险

- 若只执行 `/xwebhook reload` 而不重启 Mirai，旧插件类仍由原 classloader 加载，修复不会生效。
- 若生产运行时 YAML 保持 `font_wait_timeout_ms: 60000`，新版会在使用回退字体前最多等待一分钟；建议改为 3000，追求立即降级时可设为 0。
- 该调用期间默认资源含生产化 session、route 和群号改动；最终集成前已恢复安全占位配置、默认关闭状态并通过完整测试。
- 新包已本地生成但尚未确认部署；生产验证应以部署文件 SHA-256 和重启后的新日志为准。

## 后续建议

- 用新生成的 `.mirai2.jar` 替换生产插件，核对 SHA-256 后完整停止并重启 Mirai，而不是只调用 `/xwebhook reload`。
- 将生产运行时 `screenshot.font_wait_timeout_ms` 调整为 3000 或 0；`screenshot.timeout_ms` 可保留较长值用于元素等待，不应拿它规避字体请求挂起。
- 在构建文档或发布流程中固定使用 `buildPlugin`，并在每次部署记录 `.mirai2.jar` 哈希，避免普通 JAR 与部署 JAR 版本漂移。
