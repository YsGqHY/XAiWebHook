package kim.hhhhhy.x.webhook.action.codex

/**
 * 降智判定参数。默认值对齐 codexradar 站点：
 * IQ 阈值取站长推荐规则中的 90 / 96，观察线 80 取自分享卡配色分界。
 */
internal data class CodexRadarAdviceOptions(
    /** 判定「智商正常」的 IQ 下限。 */
    val targetIq: Double = DEFAULT_TARGET_IQ,
    /** 评级「优秀」下限。 */
    val excellentIq: Double = DEFAULT_EXCELLENT_IQ,
    /** 评级「正常」下限，同时是达标线。 */
    val normalIq: Double = DEFAULT_TARGET_IQ,
    /** 评级「观察」下限，低于此值为「偏低」。 */
    val watchIq: Double = DEFAULT_WATCH_IQ,
    /** 作为日常基准的思考档位。 */
    val baselineEffort: String = DEFAULT_BASELINE_EFFORT,
    /** 建议档位选取策略。 */
    val strategy: CodexRadarStrategy = CodexRadarStrategy.HIGHEST,
    /** 思考档位从低到高的顺序。 */
    val effortOrder: List<String> = DEFAULT_EFFORT_ORDER,
    /** 思考档位中文名。 */
    val effortLabels: Map<String, String> = DEFAULT_EFFORT_LABELS,
    /** 思考档位的 OpenAI 官方名，即 reasoning effort 取值，也是 CLI/API 实际传参值。 */
    val officialEffortLabels: Map<String, String> = DEFAULT_OFFICIAL_EFFORT_LABELS,
    /** 模型展示名。 */
    val modelLabels: Map<String, String> = DEFAULT_MODEL_LABELS,
    /** 模型展示顺序。 */
    val modelOrder: List<String> = DEFAULT_MODEL_ORDER,
    val normalTemplate: String = "{model} 智商正常。",
    val switchTemplate: String = "{model}：建议换{effort}（{effort_official}）。",
    val degradedTemplate: String = "{model}：全档位降智，建议换{effort}（{effort_official}）或暂避。",
    val unknownTemplate: String = "{model}：暂无数据。"
) {
    internal companion object {
        const val DEFAULT_TARGET_IQ: Double = 90.0
        const val DEFAULT_EXCELLENT_IQ: Double = 96.0
        const val DEFAULT_WATCH_IQ: Double = 80.0
        const val DEFAULT_BASELINE_EFFORT: String = "high"

        val DEFAULT_EFFORT_ORDER: List<String> =
            listOf("low", "medium", "high", "xhigh", "max", "ultra")

        val DEFAULT_EFFORT_LABELS: Map<String, String> = mapOf(
            "low" to "轻度思考",
            "medium" to "中度思考",
            "high" to "高度思考",
            "xhigh" to "极高思考",
            "max" to "极致思考",
            "ultra" to "超限思考"
        )

        /**
         * OpenAI 官方档位名，与 reasoning effort 取值一致。
         * 该值可直接用于 CLI 的 model_reasoning_effort 与 API 请求参数，
         * 因此在图表和建议里同时给出中文名与官方名，便于直接照抄。
         */
        val DEFAULT_OFFICIAL_EFFORT_LABELS: Map<String, String> = mapOf(
            "low" to "low",
            "medium" to "medium",
            "high" to "high",
            "xhigh" to "xhigh",
            "max" to "max",
            "ultra" to "ultra"
        )

        val DEFAULT_MODEL_LABELS: Map<String, String> = mapOf(
            "gpt-5.6-sol" to "GPT-5.6 Sol",
            "gpt-5.6-terra" to "GPT-5.6 Terra",
            "gpt-5.6-luna" to "GPT-5.6 Luna",
            "gpt-5.5" to "GPT-5.5"
        )

        val DEFAULT_MODEL_ORDER: List<String> =
            listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5")
    }
}
