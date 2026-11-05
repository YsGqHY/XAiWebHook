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
    /**
     * 以 model|effort 为行主键把本次结果叠加到旧快照上。
     *
     * 逐行缓存的语义：本次解析到的行覆盖旧值，本次缺失（未加载/无数据/解析失败）
     * 的行沿用旧缓存中的值，避免单条缺失把该行已经缓存过的数据一起抹掉。
     */
    fun mergeWith(previous: CodexRadarSnapshot?): CodexRadarSnapshot {
        if (previous == null) return this
        return copy(
            radarScores = mergeScores(previous.radarScores, radarScores),
            communityScores = mergeScores(previous.communityScores, communityScores)
        )
    }

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

    private companion object {
        /** 旧行保持原顺序，新行追加在其后，保证图表与文本输出顺序稳定。 */
        fun mergeScores(
            previous: List<CodexRadarScore>,
            current: List<CodexRadarScore>
        ): List<CodexRadarScore> {
            if (previous.isEmpty()) return current
            if (current.isEmpty()) return previous
            val currentByKey = current.associateBy { it.key }
            val previousKeys = previous.mapTo(HashSet(previous.size)) { it.key }
            val merged = previous.map { score -> currentByKey[score.key] ?: score }
            return merged + current.filter { it.key !in previousKeys }
        }
    }
}

/** 解析阶段被跳过的一行，用于逐行缓存时说明哪一条没有缓存以及原因。 */
internal data class CodexRadarSkippedRow(
    val kind: String,
    val index: Int,
    val label: String,
    val reason: String
) {
    fun describe(): String = "$kind 第 ${index + 1} 行“$label”已跳过：$reason"
}

/** 逐行解析结果：本次成功缓存的有效行 + 被跳过的行。 */
internal data class CodexRadarParseResult(
    val scores: List<CodexRadarScore>,
    val skipped: List<CodexRadarSkippedRow>,
    val totalRows: Int
) {
    val hasSkipped: Boolean get() = skipped.isNotEmpty()
}

/** Codex Radar 页面抓取、解析或结构校验失败。 */
internal class CodexRadarException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
