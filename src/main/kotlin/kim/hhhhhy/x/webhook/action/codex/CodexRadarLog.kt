package kim.hhhhhy.x.webhook.action.codex

import kim.hhhhhy.x.webhook.XAiWebHook

/**
 * 降智检测的告警日志出口。
 *
 * 抓取逻辑需要能在 mirai console 之外独立运行（单元测试与本地预览任务），
 * 而 [XAiWebHook] 是 KotlinPlugin 单例，脱离 console 运行时访问会抛
 * UninitializedPropertyAccessException。这里做一次兜底降级到 stderr。
 */
internal object CodexRadarLog {
    fun warning(message: String) {
        runCatching { XAiWebHook.logger.warning(message) }
            .onFailure { System.err.println(message) }
    }
}
