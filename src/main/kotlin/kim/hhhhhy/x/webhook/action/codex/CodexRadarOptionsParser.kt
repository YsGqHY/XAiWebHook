package kim.hhhhhy.x.webhook.action.codex

/** 从 action 参数解析图表外观参数，缺省时回退默认值。 */
internal object CodexRadarOptionsParser {
    fun chartOptions(params: Map<String, Any?>): CodexRadarChartOptions {
        val chart = params["chart"].asMap()
        val defaults = CodexRadarChartOptions()
        return CodexRadarChartOptions(
            title = chart.string("title") ?: defaults.title,
            subtitle = chart.string("subtitle") ?: defaults.subtitle,
            footer = chart.string("footer") ?: defaults.footer,
            widthPx = chart.int("width_px")?.coerceIn(900, 3000) ?: defaults.widthPx
        )
    }

    private fun Any?.asMap(): Map<String, Any?> {
        val raw = this as? Map<*, *> ?: return emptyMap()
        return raw.mapNotNull { (key, value) -> key?.toString()?.let { it to value } }.toMap()
    }

    private fun Map<String, Any?>.string(key: String): String? =
        this[key]?.toString()?.trim()?.ifBlank { null }

    private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
