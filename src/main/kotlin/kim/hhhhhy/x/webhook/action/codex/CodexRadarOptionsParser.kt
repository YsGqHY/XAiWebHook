package kim.hhhhhy.x.webhook.action.codex

/** 从 action 参数解析降智判定与图表参数，缺省时回退默认值。 */
internal object CodexRadarOptionsParser {
    fun adviceOptions(params: Map<String, Any?>): CodexRadarAdviceOptions {
        val advice = params["advice"].asMap()
        val labels = params["labels"].asMap()
        val defaults = CodexRadarAdviceOptions()

        val targetIq = advice.double("target_iq") ?: defaults.targetIq
        return CodexRadarAdviceOptions(
            targetIq = targetIq,
            excellentIq = advice.double("excellent_iq") ?: defaults.excellentIq,
            // 正常线默认与达标线一致，保持评级与判定口径统一
            normalIq = advice.double("normal_iq") ?: targetIq,
            watchIq = advice.double("watch_iq") ?: defaults.watchIq,
            baselineEffort = advice.string("baseline_effort")?.lowercase()
                ?: defaults.baselineEffort,
            strategy = CodexRadarStrategy.parse(advice.string("strategy")),
            effortOrder = labels.stringList("effort_order").ifEmpty { defaults.effortOrder },
            effortLabels = labels.stringMap("effort").ifEmpty { defaults.effortLabels },
            modelLabels = labels.stringMap("model").ifEmpty { defaults.modelLabels },
            modelOrder = labels.stringList("model_order").ifEmpty { defaults.modelOrder },
            normalTemplate = advice.string("normal_template") ?: defaults.normalTemplate,
            switchTemplate = advice.string("switch_template") ?: defaults.switchTemplate,
            degradedTemplate = advice.string("degraded_template") ?: defaults.degradedTemplate,
            unknownTemplate = advice.string("unknown_template") ?: defaults.unknownTemplate
        )
    }

    fun chartOptions(params: Map<String, Any?>): CodexRadarChartOptions {
        val chart = params["chart"].asMap()
        val defaults = CodexRadarChartOptions()
        return CodexRadarChartOptions(
            title = chart.string("title") ?: defaults.title,
            subtitle = chart.string("subtitle") ?: defaults.subtitle,
            adviceTitle = chart.string("advice_title") ?: defaults.adviceTitle,
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

    private fun Map<String, Any?>.double(key: String): Double? = when (val value = this[key]) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun Map<String, Any?>.stringList(key: String): List<String> {
        val value = this[key] as? List<*> ?: return emptyList()
        return value.mapNotNull { it?.toString()?.trim()?.lowercase()?.ifBlank { null } }
    }

    private fun Map<String, Any?>.stringMap(key: String): Map<String, String> {
        val value = this[key] as? Map<*, *> ?: return emptyMap()
        return value.mapNotNull { (mapKey, mapValue) ->
            val name = mapKey?.toString()?.trim()?.lowercase()?.ifBlank { null }
            val text = mapValue?.toString()?.trim()?.ifBlank { null }
            if (name != null && text != null) name to text else null
        }.toMap()
    }
}
