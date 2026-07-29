package kim.hhhhhy.x.webhook.action.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 归一化 codexradar 三个接口的返回结构。
 *
 * 站点前端对同一语义使用了多组字段名（例如 iq / current_iq，
 * average_price_usd / average_cost_usd），这里按候选列表逐个回退，
 * 避免上游微调字段名后整个功能不可用。
 */
internal object CodexRadarParser {
    private val IQ_KEYS = listOf("iq", "current_iq", "latest_iq")
    private val PRICE_KEYS = listOf("average_price_usd", "average_cost_usd", "price_usd", "price")
    private val MINUTE_KEYS = listOf("average_minutes", "average_duration_minutes", "minutes")

    /** 解析 /data/intelligence-efficiency.json 的 points 列表（智力效率面板数据源）。 */
    fun parseTiers(root: JsonElement?): List<RadarTier> {
        val obj = root as? JsonObject ?: return emptyList()
        val points = obj.array("points") ?: obj.array("items") ?: return emptyList()
        return points.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val model = item.text("model")?.lowercase() ?: return@mapNotNull null
            val effort = item.text("effort")?.lowercase() ?: return@mapNotNull null
            val iq = item.number(IQ_KEYS) ?: return@mapNotNull null
            RadarTier(
                model = model,
                effort = effort,
                iq = iq,
                priceUsd = item.number(PRICE_KEYS),
                minutes = item.number(MINUTE_KEYS),
                passed = item.number(listOf("passed"))?.toInt(),
                validTasks = item.number(listOf("valid_tasks", "samples"))?.toInt(),
                combinedCostIndex = item.number(listOf("combined_cost_index"))
            )
        }
    }

    fun sourceUpdatedAt(root: JsonElement?): String? {
        val obj = root as? JsonObject ?: return null
        return obj.text("source_updated_at") ?: obj.text("generated_at")
    }

    /** 解析 /api/radar-insights 的 degradation_alerts；兼容对象包装与裸数组两种形态。 */
    fun parseAlerts(root: JsonElement?): List<RadarDegradationAlert> {
        val obj = root as? JsonObject ?: return emptyList()
        val raw = obj["degradation_alerts"] ?: obj["alerts"] ?: obj["degradation"]
        val items = when (raw) {
            is JsonArray -> raw
            is JsonObject -> raw.array("items") ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val model = item.text("model")?.lowercase() ?: return@mapNotNull null
            val effort = item.text("effort")?.lowercase() ?: return@mapNotNull null
            val iq = item.number(IQ_KEYS)
            val avg24 = item.number(listOf("average_iq_24h", "avg_iq_24h", "iq_24h_average"))
            val avg48 = item.number(listOf("average_iq_48h", "avg_iq_48h", "iq_48h_average"))
            RadarDegradationAlert(
                model = model,
                effort = effort,
                iq = iq,
                averageIq24h = avg24,
                averageIq48h = avg48,
                // 站点既可能直接给出差值，也可能只给均值，这里两种都支持
                dropFrom24h = item.number(listOf("from_24h_average_iq", "drop_from_24h"))
                    ?: diff(avg24, iq),
                dropFrom48h = item.number(listOf("from_48h_average_iq", "drop_from_48h"))
                    ?: diff(avg48, iq),
                severity = item.number(listOf("degradation_severity_score", "severity"))
            )
        }
    }

    fun alertRule(root: JsonElement?): String? {
        val obj = root as? JsonObject ?: return null
        val alerts = obj["degradation_alerts"] as? JsonObject ?: return null
        return alerts.text("rule")
    }

    /** 解析 /api/radar-insights 的 recommendations（站长推荐面板）。 */
    fun parseRecommendations(root: JsonElement?): List<RadarRecommendation> {
        val obj = root as? JsonObject ?: return emptyList()
        val groups = obj.array("recommendations") ?: return emptyList()
        return groups.mapNotNull { element ->
            val group = element as? JsonObject ?: return@mapNotNull null
            val key = group.text("key") ?: return@mapNotNull null
            val items = (group.array("items") ?: group.array("models")).orEmpty()
            RadarRecommendation(
                key = key,
                title = group.text("title") ?: key,
                items = items.mapNotNull(::parseRecommendationItem)
            )
        }
    }

    /**
     * 单个推荐项的解析。
     *
     * 抽成独立函数而非内联 lambda：嵌套两层 mapNotNull 时
     * return@mapNotNull 会指向同名标签，语义对读者不明确，
     * 且后续调整嵌套层级可能静默改变行为。
     */
    private fun parseRecommendationItem(element: JsonElement): RadarRecommendationItem? {
        val item = element as? JsonObject ?: return null
        val model = item.text("model")?.lowercase() ?: return null
        return RadarRecommendationItem(
            model = model,
            effort = item.text("effort")?.lowercase().orEmpty(),
            iq = item.number(IQ_KEYS),
            priceUsd = item.number(PRICE_KEYS),
            minutes = item.number(MINUTE_KEYS),
            slot = item.text("slot")
        )
    }

    private fun diff(average: Double?, current: Double?): Double? {
        if (average == null || current == null) return null
        val delta = average - current
        return if (delta > 0.0) delta else null
    }

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.text(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (!primitive.isString && primitive.content == "null") return null
        return primitive.content.trim().ifBlank { null }
    }

    private fun JsonObject.number(keys: List<String>): Double? {
        keys.forEach { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@forEach
            // 布尔量不参与数值回退，避免 true 被当作 1 使用
            if (primitive.content == "true" || primitive.content == "false") return@forEach
            primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
        }
        return null
    }
}
