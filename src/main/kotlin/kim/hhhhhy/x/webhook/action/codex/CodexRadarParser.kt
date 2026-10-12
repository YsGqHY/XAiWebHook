package kim.hhhhhy.x.webhook.action.codex

import kim.hhhhhy.x.webhook.api.BrowserTextListItem
import kim.hhhhhy.x.webhook.api.BrowserTextListResult
import java.util.Locale

/**
 * 解析 Codex Radar 动态页面中的结构化 DOM 查询结果。
 *
 * 解析器不依赖站点 API JSON，只接受浏览器从指定卡片/评分行读取到的文本，
 * 因此页面内部 API 字段变化不会直接进入插件的数据模型。
 */
internal object CodexRadarParser {
    private val EFFORT_PATTERN = Regex("(.+?)\\s+(low|medium|high|xhigh|max|ultra)$", RegexOption.IGNORE_CASE)
    private val RATING_COUNT_PATTERN = Regex("(\\d+)\\s*人评分")
    private val PLACEHOLDER_VALUES = setOf(
        "",
        "—",
        "–",
        "-",
        "暂无",
        "不支持",
        "未读取",
        "未读取 · 点击查询",
        "数据不足"
    )

    fun parseRadar(result: BrowserTextListResult): List<CodexRadarScore> {
        return parse(
            kind = "雷达分",
            result = result,
            maximum = 150.0
        )
    }

    fun parseCommunity(result: BrowserTextListResult): List<CodexRadarScore> {
        return parse(
            kind = "社区体感分",
            result = result,
            maximum = 10.0
        )
    }

    private fun parse(
        kind: String,
        result: BrowserTextListResult,
        maximum: Double
    ): List<CodexRadarScore> {
        if (result.items.isEmpty()) {
            throw CodexRadarException(
                "$kind 数据缺失：指定页面区块未匹配到任何可解析条目"
            )
        }

        val scores = result.items.mapNotNull { item ->
            val score = parseScore(kind, item)
                ?: return@mapNotNull null
            val label = item.nameText?.trim().orEmpty()
                .ifBlank { item.ariaLabel?.trim().orEmpty() }
                .ifBlank { item.itemText?.lineSequence()?.firstOrNull()?.trim().orEmpty() }
            val (model, effort) = parseLabel(kind, label)
            val count = RATING_COUNT_PATTERN.find(item.itemText.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            CodexRadarScore(
                model = model,
                effort = effort,
                value = validateScore(kind, score, maximum, label),
                ratingCount = count
            )
        }

        if (scores.isEmpty()) {
            throw CodexRadarException(
                "$kind 数据缺失：匹配到 ${result.items.size} 个条目，但没有有效分数"
            )
        }
        return scores
    }

    private fun parseScore(kind: String, item: BrowserTextListItem): Double? {
        val raw = item.statusText?.trim().orEmpty()
        val normalized = raw.replace(',', '.').trim()
        if (normalized in PLACEHOLDER_VALUES || normalized.startsWith("未读取")) return null
        val value = normalized.toDoubleOrNull()
            ?: throw CodexRadarException(
                "$kind 数据解析失败：条目“${displayLabel(item)}”的分数不是数字：$raw"
            )
        if (!value.isFinite()) {
            throw CodexRadarException(
                "$kind 数据解析失败：条目“${displayLabel(item)}”的分数不是有限数值：$raw"
            )
        }
        return value
    }

    private fun validateScore(
        kind: String,
        value: Double,
        maximum: Double,
        label: String
    ): Double {
        if (value < 0.0 || value > maximum) {
            throw CodexRadarException(
                "$kind 数据校验失败：条目“$label”的分数 $value 超出允许范围 0..$maximum"
            )
        }
        return value
    }

    private fun parseLabel(kind: String, label: String): Pair<String, String> {
        val match = EFFORT_PATTERN.matchEntire(label)
            ?: throw CodexRadarException(
                "$kind 页面结构变化：无法从条目名称“$label”解析模型和思考档位"
            )
        val model = match.groupValues[1].trim().ifBlank {
            throw CodexRadarException("$kind 页面结构变化：条目名称缺少模型名")
        }
        val effort = match.groupValues[2].lowercase(Locale.ROOT)
        return model to effort
    }

    private fun displayLabel(item: BrowserTextListItem): String {
        return item.nameText?.trim()?.ifBlank { null }
            ?: item.ariaLabel?.trim()?.ifBlank { null }
            ?: item.itemText?.lineSequence()?.firstOrNull()?.trim()?.ifBlank { null }
            ?: "未知条目"
    }
}
