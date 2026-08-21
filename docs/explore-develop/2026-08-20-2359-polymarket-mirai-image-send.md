---
skill: explore-develop
date: 2026-08-20 23:59
project: XAiWebHook
scope: polymarket-mirai-image-send
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：Polymarket 聚合事件图片发送

## 调用背景

- 用户目标：将 Polymarket 聚合事件搜索的长文本成功结果改为重新渲染的事件卡 PNG，并通过 mirai 发送图片。
- 项目路径：`E:\Desktop\IDEA\XAiWebHook`
- 本次范围：图片/文本响应模式、mirai 图片上传、失败回退、配置解析与注释、行为回归测试、生产代理预览和插件构建。
- 明确约束：默认发送图片；支持 `image`、`text`、`both`；保留 pending、白名单拒绝、空结果和错误文本；非聚合旧市场结果继续文本发送；不破坏独立代理、强制大模型白名单和公开事件搜索。

## 项目快照

- 技术栈：Kotlin、Gradle、mirai-console、kotlinx.serialization、openhtmltopdf Java2D、JUnit 5。
- 复用能力：`PolymarketEventCard` 生成事件卡 HTML，`HtmlImageRenderer` 生成 PNG，mirai 使用 `ByteArray.toExternalResource("png")` 与 `Contact.uploadImage` 上传。
- 关键入口：`PolymarketSearchAction`、`WebHookConfig`、`QueryActionBehaviorTest`、`QueryConfigTest`、`polymarketImagePreview`。
- 初始问题：聚合事件虽然已有可验证 PNG 预览，但动作成功分支始终发送 `PlainText`，群聊仍显示长文本。

## 真实用户体验假设

- 目标用户：在 mirai 群聊中触发 `poly <大模型名称>` 搜索的群成员和插件维护者。
- 核心任务：搜索成功后直接收到结构化图片卡，而不是阅读多行市场文本；运维可按需切换回文本或双发。
- 成功标准：未配置 `response_format` 时聚合事件默认发送图片；图片链路失败时默认自动发送原成功文本；关闭回退时保留异常；状态和错误消息仍为文本。
- 易失败点：渲染、上传或发送任一环节可能失败；旧市场分页结果不具备事件层级，不能伪造成事件卡；CPU 渲染不能阻塞 mirai 事件线程。

## 探索证据

- 项目已有网页截图和 Codex 图表图片发送范式，群和好友都可通过 `Contact.uploadImage` 发送。
- `PolymarketSearchAction` 原聚合事件成功分支只构造并发送文本，图片预览能力未接入动作。
- `PolymarketEventCard` 已可基于 Gamma 真实事件数据渲染 7 个开放子市场，适合作为唯一图片内容来源。
- 生产代理复跑以 `GPT-6` 命中 `gpt-6-released-by`，搜索返回 3 个候选事件，详情解析 13 个子市场并选择 7 个开放项。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 处理决策 |
|----|--------|------|----------|----------|
| ED-001 | P1 | 聚合事件成功结果固定发送长文本 | 群聊信息密度低且难扫描 | fixed |
| ED-002 | P1 | 没有可配置的图片/文本交付模式 | 无法兼顾默认图片与旧行为兼容 | fixed |
| ED-003 | P1 | 图片链路失败时缺少明确回退策略 | 搜索成功但用户可能收不到结果 | fixed |
| ED-004 | P1 | 非聚合市场结果没有事件卡语义 | 强行图片化会产生误导 | preserved as text |
| ED-005 | P2 | 图片渲染是 CPU/IO 密集工作 | 可能阻塞消息事件处理 | fixed with `Dispatchers.IO` |

## 本次预开发改动

- 聚合事件成功结果按 `response_format.output_mode` 交付：`image` 默认仅图片，`text` 保留原文本，`both` 先文本后图片。
- 图片路径使用现有事件卡与渲染器生成 PNG，再经 `Contact.uploadImage` 发送；渲染在 `Dispatchers.IO` 执行并正确关闭外部资源。
- 图片渲染、上传或发送失败时，`image_fallback_to_text: true` 默认发送原成功文本；设为 `false` 时传播异常。
- `pending_message`、白名单拒绝、空结果、失败提示和非聚合市场分页结果继续发送文本。
- 新增 `image_width_px` 配置，并同步默认 YAML、示例 YAML、使用文档和配置日志。
- 增加默认图片、双发、失败回退、禁用回退、非聚合文本兼容及配置默认值回归测试。

## 验证结果

- 定向测试：`QueryActionBehaviorTest`、`QueryConfigTest`、`PolymarketEventCardTest` 通过。
- 全量验证：`./gradlew.bat --no-daemon test buildPlugin` 通过，插件产物为 `build/mirai/XAiWebHook-0.2.0.mirai2.jar`。
- 生产代理预览：`./gradlew.bat --no-daemon polymarketImagePreview` 通过，输出 `build/polymarket-event-preview.png`，217239 字节、2880×2406；目视确认 7 行完整、无裁切且盘口清晰。
- 差异卫生：`git diff --check` 无错误，仅报告工作区既有 LF/CRLF 转换提示。
- 真实 mirai 边界：本环境未登录并向真实群上传图片；上传代码沿用项目已使用的 `Contact.uploadImage` 路径，最终观察需部署新插件包并 reload 或重启实例。

## 残留风险

- 部署环境缺少中文字体时，图片字形可能与 Windows 预览不同。
- mirai 或 QQ 侧临时拒绝图片上传时，默认会回退长文本；禁用回退后动作会按配置传播失败。
- 旧配置文件不会自动追加注释，但缺省解析仍为 `image`、启用文本回退和 1440 CSS 像素宽度。
