# Codex Radar 分数报告

`send_codex_radar_report` 会通过 Playwright 访问 `https://codexradar.com/`，等待页面动态内容完成后读取：

- `//*[@id="intelligence-efficiency"]/div[2]`：雷达分，站点 150 分制。
- `//*[@id="model-ratings"]/div[3]`：社区体感分，站点 10 分制。

两类数据会合并到同一张 PNG 图片中，图片中分别标注“雷达分”和“社区体感分”，并展示模型、思考档位、分数及可用的评分人数。

## 启用条件

```yaml
browser:
  enabled: true
  allowed_hosts:
    - "codexradar.com"
```

浏览器代理可在 `browser.proxy` 配置；Codex action 的 `proxy` 可单独覆盖该值。页面导航默认超时 60000 毫秒，可用 `timeout_ms` 调整，允许范围为 1000-300000。完整数据等待默认最多 120000 毫秒，`max_wait_ms` 范围为 1000-120000；`poll_interval_ms` 范围为 200-10000，`stable_polls` 范围为 1-5。

## Action 配置

```yaml
actions:
  codex-degradation-report:
    type: "send_codex_radar_report"
    enabled: true
    page_url: "https://codexradar.com/"
    timeout_ms: 60000
    max_wait_ms: 120000
    poll_interval_ms: 1000
    stable_polls: 2
    wait_until: "domcontentloaded"
    proxy: ""
    cache:
      enabled: true
      file: "codex-radar-cache.json"
      ttl_ms: 900000
      refresh_enabled: true
      refresh_interval_ms: 900000
    group_id: "${request.body.group_id}"
    friend_id: "${request.body.friend_id}"
    send_text: true
    send_chart: true
    chart:
      title: "Codex Radar 分数概览"
      subtitle: "雷达分 × 社区体感分 · codexradar.com"
      width_px: 1680
```

动作类型、既有 route/action 引用、群/好友目标参数、pending/failure、`send_text` 和 `send_chart` 的调用方式保持不变；原有“降智检测”路由可以继续使用。

## 完整数据、缓存与刷新

- WebHook 服务启动完成后，对 `cache.enabled=true` 的静态 Codex action 异步预热一次页面数据，只写缓存，不发送消息；`refresh_enabled=false` 也不会跳过这次预热，只会关闭后续定时/过期后台刷新。
- 无缓存首次请求会在同一个 Page 内轮询两个目标区块，最多等待 `max_wait_ms`（默认 120 秒），不会反复导航。
- 完整快照要求雷达区块和社区区块都存在、卡片数量稳定，并且两类各至少有一个有限范围内的数字分数。
- 若等待超时，插件会过滤 `—`、`暂无`、`未读取`、空分数和 pending 条目；只要两类仍各有有效分数，就生成 partial 快照、写缓存并发送带部分数据提示的文本/图片。若任一分区没有有效分数，才返回失败。
- 缓存只保存有效分数快照。未过期缓存直接返回；过期缓存先返回旧快照，再由后台刷新。刷新失败、超时、解析失败或写盘失败都不会覆盖旧缓存。
- 相对 `cache.file` 必须位于插件配置目录内；旧 action 没有 `cache` 区块时默认不使用缓存。日志中的 `total`、`valid`、`placeholder`、`pending`、`final_missing` 和 `stable_counts` 用于定位等待原因。

## 迁移说明

以下旧配置已经不再使用，应删除：

- `efficiency_url`
- `insights_url`
- `advice`
- `labels`
- 基于旧 JSON 的 IQ、降智预警和推荐配置

输出内容由旧的模型降智建议改为页面中的两类分数摘要。插件不再直接请求 Codex Radar 的旧 JSON/API 地址；页面自身的前端脚本仍由浏览器正常加载，以便获得动态 DOM 数据。

## 错误定位

- `browser actions are disabled`：将 `browser.enabled` 改为 `true`。
- `navigation host is not allowed`：把目标主机加入 `browser.allowed_hosts`。
- 页面抓取或完整数据等待超时：检查网络、代理、浏览器可执行文件、`timeout_ms` 和 `max_wait_ms`；无旧缓存时会发送 `failure_message`。
- 日志包含 `placeholder>0` 或 `pending>0`：页面仍在异步查询，插件不会把占位分数当成数据。
- 日志包含 `final_missing>0` 但两类仍有有效分数：说明部分模型没有样本/不支持，该条目不会伪造成 0。
- 日志包含 `stable_counts=false`：卡片仍在增量渲染，需等待下一轮稳定采样。
- 命中旧缓存但刷新失败：这是预期的保留旧数据行为；修复网络或页面结构后下一次定时刷新会再次尝试。
- “雷达分数据缺失”或“社区体感分数据缺失”：检查站点对应区块是否存在、ready/ok 状态标记是否完成，以及页面是否返回了有效数字。
- 分数解析/范围错误：说明页面结构或数据格式发生变化，插件会发送 `failure_message`，具体原因写入控制台日志。
