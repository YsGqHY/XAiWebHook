---
skill: explore-develop
date: 2026-10-13 23:11
project: XAiWebHook
scope: request-image-config
status: completed
narrator: NarraFork
---

# Explore Develop 调用记录：请求图片独立配置

## 调用背景

用户要求分析“请求”命令图片的构建过程，提取可配置参数，实现独立配置的启动加载、读取、写入和运行时热重载；无效变更必须保留当前有效值并提示错误。

本次只调整请求记录图片。保留之前的边框、百分比副本和留白功能，以及工作区中未提交的改动；不修改主配置的原有失败回退策略，不创建提交或推送。

## 项目快照

- 技术栈：Kotlin 1.9.22、Java 11 目标、mirai-console 2.16.0、Gradle wrapper、SnakeYAML 2.2、Playwright、Java2D/ImageIO。
- 验证入口：`./gradlew.bat test`、`build`、`buildPlugin`；本地浏览器测试通过 `XAI_WEBHOOK_BROWSER_IT=true` 启用 Edge。
- 构建输入：图片合成位于 [src/main/kotlin/kim/hhhhhy/x/webhook/action/WebPageScreenshotAction.kt](src/main/kotlin/kim/hhhhhy/x/webhook/action/WebPageScreenshotAction.kt)。
- 初始风险：样式参数硬编码；主配置 reload 会重启 HTTP，且解析失败时回退默认主配置，不适合直接复用为图片热更新。

## 项目文档与提示词需求

现有 [README.md](README.md) 说明请求查询、截图选择器、群消息分流；默认主 YAML 附中文注释，开发记录保存在本目录。

文档缺口是独立样式文件的格式、默认值、校验范围、读写接口及生效时机。本次新增 README 小节与带逐项中文注释的 [src/main/resources/request_image.yml](src/main/resources/request_image.yml)，同时在主 YAML 中指出样式文件入口。

未发现本功能的提示词资产；图片采用确定性 DOM 截图和 Java2D 合成，不需要新增 LLM 或提示词。成功评估样例为修改左侧留白后新查询图片变宽；失败样例为写入非法颜色/负边距后保留原样式并返回错误。

## 真实用户体验假设

- 管理员要在已运行的机器人中调整样式，希望保存文件后直接发下一条 `请求` 消息查看效果。
- 依赖插件需要通过代码读取有效样式、写入配置，遇到错误能明确知道配置没有应用。
- 成功标准：不重启 HTTP 或浏览器；同一批模型图片样式一致；无效 YAML 不导致查询失败或清空配置；文件修复后自动恢复；已发送历史消息保持原样。

## 构建过程与提取参数

现有流程为：匹配模型卡片、读取指标 → 定位图表/百分比并截图 → 解码 PNG → 计算内容尺寸和留白 → 填背景、复制副本、绘制外框、复制原图 → 编码 PNG、检查大小 → 发送消息。

独立样式文件共有 16 个字段：版本；边框开关、线宽、内边距、颜色；百分比副本开关、间距、竖向对齐；四侧百分数和最小边距；留白及内容背景；画布像素上限。

默认值保持正常截图的现有外观：红色 1px 外框、2px 内边距、6px 副本间距、居中、左右各 5% 与上下各 1% 白色留白。百分数以加外边距前的完整图片内容计算，十进制向上取整；0% 关闭该侧。颜色支持 `#RRGGBB` / `#AARRGGBB`，原图像素使用 `AlphaComposite.Src` 复制，不缩放或重绘文字。

原网页字体、图表布局、浏览器视口/认证/超时及截图选择器仍由原配置控制，不建立第二套来源。PNG 输出格式保持固定；保留现有截图字节限制，并增加画布分配前的像素上限检查。

## 探索证据与取舍

- [src/main/kotlin/kim/hhhhhy/x/webhook/config/WebHookConfig.kt](src/main/kotlin/kim/hhhhhy/x/webhook/config/WebHookConfig.kt) 的主配置加载失败会回退默认配置。图片配置采用独立存储，不改变此既有行为。
- [src/main/kotlin/kim/hhhhhy/x/webhook/command/XAiWebHookCommand.kt](src/main/kotlin/kim/hhhhhy/x/webhook/command/XAiWebHookCommand.kt) 的主 reload 会重启 HTTP；新增图片专用命令避免此副作用。
- 当前浏览器采集会依次生成多模型图片，因此在查询开始保存不可变样式快照，不能每张图片重新读实时配置。
- 选择 1 秒周期、连续两次相同内容的轮询，以兼容 Windows、同大小同修改时间的变更和编辑器原子替换；64 KiB 上限限制读取成本。实际生效通常约 1–2 秒。
- 写入只接受同目录临时文件的原子替换。文件系统不支持时返回失败，避免非原子覆盖导致文件截断。
- 单字段更新在锁内以有效磁盘内容读改写，避免并发更新不同字段时丢失修改；仅磁盘文件缺失时使用当前有效快照。已有无效或不可读文件会拒绝单字段写入，保留用户待修复的编辑。显式 write/writeYaml 可整体替换；write/update 重写规范注释，writeYaml 保留原文。
- 缺失百分比时保持既有原图加留白、不加框的降级；主动关闭副本时仍可独立使用外框。

## 发现的问题

| ID | 严重度 | 问题 | 用户影响 | 证据 | 处理决策 |
| --- | --- | --- | --- | --- | --- |
| ED-001 | P2 | 样式参数硬编码 | 改颜色/留白需要改代码和重新部署 | 原合成函数的固定常量 | fixed：提取独立 YAML |
| ED-002 | P1 | 直接复用主配置重载会重启服务、丢失有效图片参数 | 错误配置中断正常查询 | 主配置加载及管理命令 | fixed：独立整份校验与有效值保留 |
| ED-003 | P1 | 多模型生成期间若逐张读取实时样式，会混用修订 | 同一次查询显示不同边框/留白 | 浏览器串行采集流程 | fixed：查询级不可变快照 |
| ED-004 | P1 | 可配置边距会增加画布分配量 | 参数极端时增加内存压力 | 画布创建先于 PNG 字节检查 | fixed：创建前检查宽高与像素上限 |
| ED-005 | P2 | 单字段更新曾以旧值覆盖无效磁盘文件 | 丢失待修复的编辑 | 独立审查 attempt 1 | fixed：拒绝覆盖并提示先修复或明确整体替换 |
| ED-006 | P2 | 新 Store 加载前发布会暴露短暂默认快照 | 重新启用期间并发查询样式跳变 | 独立审查 attempt 1 | fixed：加载完成后再发布，测试阻塞启动回调验证旧快照可读 |
| ED-007 | P2 | 重新启用且文件无效时可能用同修订号标记默认样式 | 修订号与样式不再一一对应 | 独立审查 attempt 1 | fixed：传入旧有效状态并保留样式、修订号、成功时间 |

## 本次预开发改动

- 新增 [src/main/kotlin/kim/hhhhhy/x/webhook/config/RequestImageConfig.kt](src/main/kotlin/kim/hhhhhy/x/webhook/config/RequestImageConfig.kt)：不可变样式、字段范围、颜色和十进制边距计算、严格 YAML 编解码。
- 新增 [src/main/kotlin/kim/hhhhhy/x/webhook/config/RequestImageConfigStore.kt](src/main/kotlin/kim/hhhhhy/x/webhook/config/RequestImageConfigStore.kt)：同锁读改写、原子持久化、内容指纹稳定检测、错误去重及修复恢复；原子引用发布有效状态。
- 新增 [src/main/kotlin/kim/hhhhhy/x/webhook/api/XAiWebHookRequestImageApi.kt](src/main/kotlin/kim/hhhhhy/x/webhook/api/XAiWebHookRequestImageApi.kt)：只读快照/YAML/状态和协程 write、writeYaml、update、reload 接口。
- 插件启停管理监听协程，新增 image-config、image-config-set、image-config-reload 命令；均继承原 admin 权限，状态显示最近配置错误。
- 图片合成使用配置快照；参数、画布像素和截图字节限制失败不影响其他模型指标。
- 补充配置、文件监控、公开 API、合成和本地浏览器回归；README 说明格式、默认值、读写与回退行为。

## 协作与验证结果

- 实施工作包 `impl-request-image-config`：general，attempt 1，配置模型/Store/API/资源与三类测试，由主线程回收并核对契约后纳入实现基线 B1；子 agent 未执行 Gradle。
- 主线程：负责 renderer、生命周期、管理命令、截图测试、文档和统一集成；文件范围互不冲突。
- 独立审查 `review-request-image-config`：review，attempt 1 对 B1 结论 passed（无 P0/P1、三个 P2）；主线程修正后形成 B2，attempt 2 定向复核结论 passed。两次均只读，已通过 Await 回收；实施 agent 也已回收，无残留子任务。
- 相关测试已实际通过：`XAI_WEBHOOK_BROWSER_IT=true ./gradlew.bat test --tests '*RequestImageConfigTest' --tests '*RequestImageConfigStoreTest' --tests '*XAiWebHookRequestImageApiTest' --tests '*ModelPlazaScreenshotDecorationTest' --tests '*ModelPlazaBrowserTest' --tests '*ModelPlazaRequestActionTest'`，包括实际本地 Edge 浏览器用例。
- 相关测试：6 类共 86 项，0 失败、0 错误、0 跳过。
- 全量验证：`XAI_WEBHOOK_BROWSER_IT=true ./gradlew.bat build --rerun-tasks` 通过，20 类共 262 项，0 失败、0 错误、0 跳过；包含本地 Edge、多模型批次快照一致性、TempDir 真实文件监听、15 个并发字段更新和失败回退。
- 打包：`./gradlew.bat buildPlugin` 通过；[build/mirai/XAiWebHook-0.2.0.mirai2.jar](build/mirai/XAiWebHook-0.2.0.mirai2.jar) 已包含 request_image.yml、公开 API、样式类型和运行时类；包内默认配置字节与源码资源一致。
- 最终门禁：B2 的 reviewer（review，attempt 2）、build verifier（主线程，attempt 1）和打包内容核对均为 passed。所有后台任务已完成并回收；未进行提交或推送。
- 编译提示：既有重复/弃用编译参数及冗余初始化警告；新配置 Store 的 dispatch 也有一处冗余初始化提示，不影响构建和测试。
- 代码与文档核对：字段名称、默认值和公开签名已对齐；默认资源带逐项中文注释；`git diff --check` 当前通过。
- 提示词验证：不适用；没有 LLM 功能或提示词变更。

## 残留风险

- 未访问真实账号的线上 Model Plaza、未在 QQ 生产消息中测试；自动验证采用本地 Edge 和 HTML fixture。
- 外部编辑器不使用配置写接口时，插件无法锁住编辑器自身操作；稳定检测降低半写入应用的概率，只应用读取并通过校验的完整候选。
- 文件系统不支持原子移动时，代码写入会被拒绝；有效内存样式仍可使用，管理员可修复文件或换到支持的目录。
- 样式更新只影响后续生成，不会修改历史图片；一个已开始的查询继续使用其原快照。
- 新进程初次启动没有先前内存状态，无效文件会使用内置默认值；同一进程内重新启用插件时会保留最后有效样式、修订号与成功时间。

## 后续建议

在已部署的 mirai 测试环境中，修改 `margin.left_percent` 后发送一次请求查询，再保存非法颜色并查看配置错误，确认日志与管理员反馈符合运维习惯。主配置本身的失败回退策略未改动，不属于图片配置热重载范围。
