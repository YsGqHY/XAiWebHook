package kim.hhhhhy.x.webhook.action.codex

/**
 * Codex Radar 页面中的单个“模型 + 思考档位”分数。
 *
 * 雷达分使用站点的 150 分制；社区体感分使用站点的 10 分制。
 */
internal data class CodexRadarScore(
    val model: String,
    val effort: String,
    val value: Double,
    val ratingCount: Int? = null
) {
    val key: String get() = "$model|$effort"
    val label: String get() = "$model $effort"
}

/** 页面抓取后归一化的两类分数快照。 */
internal data class CodexRadarSnapshot(
    val radarScores: List<CodexRadarScore>,
    val communityScores: List<CodexRadarScore>,
    val generatedAtText: String,
    val partial: Boolean = false
) {
    fun summaryText(header: String): String {
        val lines = mutableListOf(header)
        if (partial) {
            lines += "（页面等待超时，仅展示已读取的有效数据，已忽略未完成条目）"
        }
        lines += "雷达分："
        lines += radarScores.map { score ->
            "${score.label}：${format(score.value)}"
        }
        lines += "社区体感分："
        lines += communityScores.map { score ->
            val count = score.ratingCount?.let { "（${it} 人评分）" }.orEmpty()
            "${score.label}：${format(score.value)}/10$count"
        }
        return lines.joinToString("\n")
    }

    private fun format(value: Double): String = String.format("%.1f", value)
}

/** Codex Radar 页面抓取、解析或结构校验失败。 */
internal class CodexRadarException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
