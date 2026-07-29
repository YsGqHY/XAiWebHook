package kim.hhhhhy.x.webhook.action.codex

/** 单个「模型 + 思考档位」的聚合数据点，对应站点智力效率面板中的一张卡片。 */
internal data class RadarTier(
    val model: String,
    val effort: String,
    val iq: Double,
    val priceUsd: Double?,
    val minutes: Double?,
    val passed: Int?,
    val validTasks: Int?,
    /** 站点的相对综合成本指数，越小越划算；缺失时按价格与耗时兜底排序。 */
    val combinedCostIndex: Double?
) {
    val key: String get() = "$model|$effort"
}

/** 站点降智预警条目：每个档位只与自身历史比较得出。 */
internal data class RadarDegradationAlert(
    val model: String,
    val effort: String,
    val iq: Double?,
    val averageIq24h: Double?,
    val averageIq48h: Double?,
    val dropFrom24h: Double?,
    val dropFrom48h: Double?,
    val severity: Double?
) {
    val key: String get() = "$model|$effort"
}

/** 站长推荐分组，例如「日常开发」「难题攻坚」。 */
internal data class RadarRecommendation(
    val key: String,
    val title: String,
    val items: List<RadarRecommendationItem>
)

internal data class RadarRecommendationItem(
    val model: String,
    val effort: String,
    val iq: Double?,
    val priceUsd: Double?,
    val minutes: Double?,
    val slot: String?
) {
    val key: String get() = "$model|$effort"
}

/** 三个接口归一化后的完整快照。 */
internal data class RadarSnapshot(
    val tiers: List<RadarTier>,
    val alerts: List<RadarDegradationAlert>,
    val recommendations: List<RadarRecommendation>,
    val sourceUpdatedAt: String?,
    val alertRule: String?
)

/** IQ 评级，阈值来自配置，默认对齐站点分享卡的四档配色。 */
internal enum class RadarGrade(val label: String, val color: String) {
    EXCELLENT("优秀", "#16a34a"),
    NORMAL("正常", "#2563eb"),
    WATCH("观察", "#d97706"),
    LOW("偏低", "#dc2626")
}

/** 单个模型的降智判定结论。 */
internal enum class RadarVerdict {
    /** 基准档位达标且无降智预警。 */
    NORMAL,

    /** 建议切换到更高思考档位。 */
    SWITCH,

    /** 全部档位都无法达标。 */
    DEGRADED,

    /** 缺少数据，无法判定。 */
    UNKNOWN
}

/** 单个模型的建议条目，用于生成文本建议和图表中的建议行。 */
internal data class RadarModelAdvice(
    val model: String,
    val modelLabel: String,
    val verdict: RadarVerdict,
    /** 判定所依据的基准档位（默认 high）。 */
    val baseline: RadarTier?,
    /** 建议切换到的目标档位。 */
    val target: RadarTier?,
    /** 命中该模型的降智预警条目。 */
    val alerts: List<RadarDegradationAlert>,
    /** 面向群聊的单行建议文本。 */
    val advice: String
)

/** 最终报告：文本建议 + 图表所需的全部数据。 */
internal data class RadarReport(
    val advices: List<RadarModelAdvice>,
    val tiersByModel: Map<String, List<RadarTier>>,
    val alerts: List<RadarDegradationAlert>,
    val recommendations: List<RadarRecommendation>,
    val sourceUpdatedAt: String?,
    val generatedAtText: String,
    val grades: Map<String, RadarGrade>
) {
    /** 按 model:effort 键查档位，找不到返回 null。 */
    fun tierOf(key: String): RadarTier? =
        tiersByModel.values.firstNotNullOfOrNull { tiers -> tiers.firstOrNull { it.key == key } }

    /** 群聊文本建议块，形如「建议：\nSol：建议换超限思考。」 */
    fun adviceText(header: String): String {
        val lines = advices.map { it.advice }
        return (listOf(header) + lines).joinToString("\n")
    }
}
