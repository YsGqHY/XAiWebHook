# Polymarket 与 Model Plaza 查询配置指南

两个查询功能共用同一套通用组件：关键词提取、过滤器、结果模板。业务字段留在各自配置段，通用层不含硬编码业务文案。

## Polymarket 搜索

### 基础配置

```yaml
polymarket:
  enabled: true
  gamma_api_base_url: "https://gamma-api.polymarket.com"
  clob_api_base_url: "https://clob.polymarket.com"
  timeout_ms: 30000
  # Polymarket 专用 HTTP(S) 代理；留空表示直连
  proxy: "http://127.0.0.1:7890"
  # Gamma 返回语言，默认 zh，可直接得到中文标题与“是/否”
  locale: "zh"
  # 关键词匹配字段，仅支持 question、description
  search_fields: ["question", "description"]
  # 公共事件搜索无命中或失败时的市场分页回退：页大小 1-500，页数 1-20
  search_page_size: 100
  max_search_pages: 3
  command_prefix: "poly"
  enabled_groups: [123456789]
  # 强制白名单，没有 enabled 开关；为空时回退内置默认名单
  whitelist:
    keywords: ["GPT", "Claude", "Gemini", "Grok", "DeepSeek", "Qwen", "Llama", "Mistral", "Kimi", "GLM"]
    case_sensitive: false
    reject_message: "仅支持搜索白名单中的大模型相关市场；当前关键词：${keyword}"
```

`enabled_groups` 为空表示不限制群；非空时动作层会直接拒绝其他群，不依赖路由过滤。

`proxy` 只作用于 Polymarket 的 Gamma/CLOB 请求。代理 CONNECT 或 TLS 链路发生瞬时断开时，插件会丢弃当前客户端、建立新连接并自动重试一次；如果第二次仍失败才返回错误。显式配置代理后不会静默改为直连，避免绕过预期网络路径。动作内也可配置 `proxy` 覆盖功能级代理，显式留空表示该动作直连。

### 关键词提取

```yaml
  keyword_extraction:
    remove_prefixes: ["poly", "polymarket"]
    pattern: "^(?:poly|polymarket)\\s+(.+)$"
    capture_group: 1
    trim: true
    lowercase: false
    require_prefix_match: true
```

未配置时按 `command_prefix` 去前缀，且要求命中前缀，普通聊天文本不会触发搜索。

### 普通关键词事件搜索

`poly GPT-6` 这类普通关键词会先调用 Gamma `/public-search` 搜索开放事件。插件按事件标题、slug、子市场问题和描述做规范化字面相关性过滤，但严格保留 Gamma 返回的相关性顺序，最终选择第一个有效候选，与 Polymarket 网页搜索首项保持一致；不会再按交易量进行本地重排。例如 `poly deepseek` 选择网页首项 DeepSeek Flash，`poly deepseek pro` 选择首个匹配完整短语的 DeepSeek Pro。连字符、空格等分隔符会统一处理，因此 `GPT-6` 与 `GPT 6` 可命中同一事件；只在相关字段实际包含关键词时接受候选，避免把 ChatGPT 宕机等宽泛相关事件误选为 GPT-6 发布事件。

命中事件后，插件再请求 `/events/slug/<slug>` 获取本地化详情和全部子市场。公共事件搜索无相关命中或暂时失败时，才使用 `search_page_size`、`max_search_pages` 控制的原市场分页回退。

### 事件页 URL 查询

可以直接把 Polymarket 事件页 URL 作为关键词：

```text
poly https://polymarket.com/zh/event/gpt-6-released-by
```

支持 `polymarket.com` 与 `www.polymarket.com`，路径可以带语言段（例如 `/zh/event/<slug>`）。插件只提取事件 `slug`，实际请求仍固定发送到配置的 Gamma API，不会把用户输入当作任意请求地址。

事件查询使用 `/events/slug/<slug>`，返回事件下所有开放子市场，并按问题中的截止日期排序；如果事件没有开放子市场才回退到全部子市场。默认摘要包含事件页、开放子市场数量、每个子市场的当前“是/否”价格和主市场的按日历史。

Gamma 个别市场可能返回与问题文本不一致的 `endDateIso`。展示和排序优先使用问题标题中明确的截止日期，无法提取时才回退 API 日期；原始字段仍可通过 `market.endDateIso` 在自定义模板中读取。

### 大模型白名单

Polymarket 使用专用强制白名单，只允许搜索关键词或官方事件页 URL 中包含已配置大模型名称的内容。该白名单没有 `enabled` 开关，`keywords` 缺失或为空时自动回退内置默认名单，不会退化为无限制搜索。

内置名单覆盖 GPT/ChatGPT/o 系列、Claude、Gemini/Gemma、Grok、DeepSeek、Qwen/QwQ、Llama、Mistral/Mixtral、Kimi/Moonshot、GLM/ChatGLM、MiniMax、ERNIE、Hunyuan 等常见模型家族及中英文别名。可通过 YAML 增删模型名称：

```yaml
  whitelist:
    keywords:
      - "GPT"
      - "Claude"
      - "Gemini"
      - "Grok"
      - "DeepSeek"
      - "Qwen"
      - "Llama"
      - "Mistral"
      - "Kimi"
      - "GLM"
      - "CustomLLM"
    case_sensitive: false
    reject_message: "仅支持搜索白名单中的大模型相关市场；当前关键词：${keyword}"
```

匹配采用包含关系，因此 `GPT` 可放行 `GPT-5`、`GPT-6` 和包含该名称的官方事件页 URL。旧的顶层 `blacklist`、`filters.blacklist` 以及 `filters.whitelist` 均不再参与 Polymarket 判断。

### 补充校验

白名单通过后仍可按需配置长度和正则校验：

```yaml
  filters:
    length:
      min: 2
      max: 200
      reject_message: "关键词长度必须在 ${min}-${max} 之间"
    pattern:
      regex: "^[\\p{L}\\p{N} :/?._-]+$"
      reject_message: "关键词格式不正确"
```

### 响应格式

```yaml
  response_format:
    # 聚合事件默认只发送图片；可选 image、text、both
    output_mode: "image"
    # 图片渲染、上传或发送失败时回退原成功文本
    image_fallback_to_text: true
    # 图片 CSS 画布宽度，最终 PNG 以 2 倍倍率输出
    image_width_px: 1440
    max_history_points: 5
    date_format: "yyyy年MM月dd日"
    timezone: "Asia/Shanghai"
    compact_numbers: true
    success_template: |
      ${market.question}
      ${if market.category}分类：${market.category}
      ${endif}总交易量：$${format_number(market.volume)}
      ${foreach point in history | limit:5}
      ${point.date}：是 ${format_price(point.yesPrice)} / 否 ${format_price(point.noPrice)}，${point.probability}%
      ${endforeach}
```

聚合事件默认使用 `output_mode: image`，将事件与开放子市场渲染为 PNG 后上传到当前群或好友；`text` 恢复旧长文本，`both` 在同一条消息内发送文本与图片。图片渲染、上传或消息发送失败时，`image_fallback_to_text: true` 会发送原成功文本而不是只留下“正在搜索”提示；设为 `false` 时按动作失败处理。公共事件搜索无命中而回退到普通单市场时，即使配置为图片也保持文本输出，因为该结果没有可靠的聚合事件页上下文。

事件页文本模板还可使用 `${event.title}`、`${event.slug}`、`${eventPageUrl}` 和 `${markets}`；`${market}` 始终表示主市场。事件摘要中 `markets` 是按日期排序的子市场 Map 列表，可读取 `${market.question}`、`${market.effectiveEndDateIso}` 和 `${market.endDateIso}`。`success_template` 用于 `text`、`both` 及图片失败回退。动作参数 `output_mode`、`image_fallback_to_text`、`image_width_px`、`success_template`、`empty_template` 优先级最高；不配置 `response_format` 时仍默认图片模式。

### 本地实时验证

仓库提供显式实时验证任务，不会被普通 `test` 自动调用：

```bash
./gradlew.bat polymarketLivePreview
```

默认读取 `examples/webhook_config.yml`，以关键词 `GPT-6` 走公共事件搜索，并把 UTF-8 摘要写入 `build/tmp/polymarket-live/event-summary.txt`。可通过 JVM 属性覆盖：

```bash
./gradlew.bat polymarketLivePreview \
  -Dpolymarket.query=Claude \
  -Dpolymarket.config=examples/webhook_config.yml \
  -Dpolymarket.output=build/tmp/polymarket-live/event-summary.txt
```

`polymarket.query` 也可传入官方事件页 URL；旧的 `polymarket.page` 属性仍作为兼容别名。该任务使用配置中的 Polymarket 独立代理，并在终端输出搜索模式、公共搜索候选数、事件 slug、子市场数量、主市场、CLOB 历史点数量和摘要文件位置。

### 本地图片预览

多市场事件可重新排版为原创静态 PNG，不依赖 Polymarket 网页截图：

```bash
./gradlew.bat polymarketImagePreview
```

任务默认以 `GPT-6` 搜索事件，通过 `examples/webhook_config.yml` 中的 Polymarket 独立代理查询 Gamma，并输出到 `build/polymarket-event-preview.png`。可覆盖查询词、配置和图片路径：

```bash
./gradlew.bat polymarketImagePreview \
  -Dpolymarket.query=GPT-6 \
  -Dpolymarket.config=examples/webhook_config.yml \
  -Dpolymarket.image.output=build/polymarket-event-preview.png
```

图片包含事件总交易量、24 小时交易量、流动性、开放子市场数量，以及各子市场的截止日期、累计交易量、当前“是”概率、趋势和买入是/否盘口价格。趋势优先使用 Gamma 的 24 小时价格变化，缺失时回退 7 日变化；盘口缺失时回退 `outcomePrices`。该 Gradle 入口仅把图片写入 `build` 供目视检查；实际 `query_polymarket` 动作使用同一渲染器，并在默认图片模式下上传到 mirai 会话。

## Model Plaza 查询

`model_plaza.queries.models` 控制“模型”查询，`queries.groups` 控制“分组”查询，两者字段一致：

```yaml
model_plaza:
  enabled: true
  queries:
    models:
      keyword_extraction:
        remove_prefixes: ["模型", "分组", "model", "group"]
        require_prefix_match: true
      sort: "source"          # source 保持接口顺序，alphabetical 按名称排序
      limit: 0                # 顶层结果上限，0 不限制
      max_related_items: 0    # 每项关联条目上限，0 不限制
      response_format:
        pending_message: "正在查询分组模型，请稍候..."
        failure_message: "查询失败，请稍后重试"
        empty_message: "未找到包含该关键词的分组"
        success_template: |
          包含“${query}”的分组（共 ${count} 个）：
          ${foreach relation in relations}
          分组：${relation.groupName}
          ${if relation.models}
          ${foreach model in relation.models}- ${model.name}
          ${endforeach}
          ${else}- （无可用模型）
          ${endif}
          ${endforeach}
```

动作参数 `query_pattern`、`prefixes`、`keyword_regex`、`capture_group`、`sort`、`limit`、`max_related_items`、`pending_message`、`failure_message`、`empty_message`、`success_template` 均可覆盖上述配置。提示语显式配置为空串即可关闭该提示。

## 模板语法

所有模板共用同一套安全语法，不支持任意脚本执行：

- 变量：`${a.b.c}`，支持 Map 与列表下标
- 循环：`${foreach item in list}` … `${endforeach}`，可加 `| limit:N`
- 条件：`${if path}` / `${if !path}` / `${else}` / `${endif}`
- 函数：`format_number`、`format_price`、`format_date`
- 循环内提供 `${item._index}` 与 `${item_index}`

标记未闭合时保留模板原文并写调试日志，不会中断动作。变量值中的 `${` 不会被二次解析。

## 验证状态

`./gradlew.bat test` 与 `./gradlew.bat buildPlugin` 均通过，覆盖强制大模型白名单、空名单默认回退、旧黑名单忽略、公共事件搜索参数与解码、搜索首项顺序保持、事件页 URL 白名单、Gamma 事件/市场与盘口趋势字段解析、静态事件卡 PNG 渲染、默认图片/文本/双发模式、图片失败文本回退、非聚合市场文本兼容、CLOB 历史、日期错配回退、价格精度、瞬时网络重试、取消传播及配置回退。`./gradlew.bat polymarketLivePreview` 与 `./gradlew.bat polymarketImagePreview` 已在 2026 年 8 月 20 日通过生产代理以关键词 `GPT-6` 命中 `gpt-6-released-by`，公共搜索返回 3 个事件，详情包含 13 个子市场并筛出 7 个开放市场；图片预览成功输出 2880×2406 PNG 到 `build/polymarket-event-preview.png`，同时确认生产配置加载 43 个白名单模型名。2026 年 8 月 21 日再次通过生产代理验证：`deepseek` 的 20 个公共事件保持 Gamma 原始顺序并选择首项 `next-deepseek-flash-released-byptptpt`，`deepseek pro` 选择 `next-deepseek-pro-released-byptptpt`。mirai 图片上传采用项目现有网页截图与 Codex 图表相同的 `Contact.uploadImage` 路径；真实群聊发送需部署新插件包后在运行实例中最终观察。
