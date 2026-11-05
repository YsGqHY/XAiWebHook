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
    // 读屏文本会带“· 132.79 · USD 1.38 · 2229.6 秒”之类的附加信息，这里取“模型 + 档位”前缀。
    private val EFFORT_PREFIX_PATTERN =
        Regex("^(.+?)\\s+(low|medium|high|xhigh|max|ultra)(?=\\s|$)", RegexOption.IGNORE_CASE)
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
        return parseRows(
            kind = "雷达分",
            result = result,
            maximum = 150.0
        ).scores
    }

    fun parseCommunity(result: BrowserTextListResult): List<CodexRadarScore> {
        return parseRows(
            kind = "社区体感分",
            result = result,
            maximum = 10.0
        ).scores
    }

    /** 逐行解析雷达分，保留被跳过的行信息供缓存流程记录。 */
    fun parseRadarRows(result: BrowserTextListResult): CodexRadarParseResult {
        return parseRows(kind = "雷达分", result = result, maximum = 150.0)
    }

    /** 逐行解析社区体感分，保留被跳过的行信息供缓存流程记录。 */
    fun parseCommunityRows(result: BrowserTextListResult): CodexRadarParseResult {
        return parseRows(kind = "社区体感分", result = result, maximum = 10.0)
    }

    /**
     * 以单行数据为最小解析单位：
     * 某一行未加载、占位符、分数非法或名称不可解析时只跳过该行并记录原因，
     * 继续解析后续行；只有整表都没有可用行时才判定为结构性问题并报错。
     */
    private fun parseRows(
        kind: String,
        result: BrowserTextListResult,
        maximum: Double
    ): CodexRadarParseResult {
        if (result.items.isEmpty()) {
            throw CodexRadarException(
                "$kind 数据缺失：指定页面区块未匹配到任何可解析条目"
            )
        }

        val scores = mutableListOf<CodexRadarScore>()
        val skipped = mutableListOf<CodexRadarSkippedRow>()
        result.items.forEachIndexed { index, item ->
            val parsed = parseRow(kind, item, maximum)
            when (parsed) {
                is RowOutcome.Valid -> scores += parsed.score
                is RowOutcome.Skipped -> skipped += CodexRadarSkippedRow(
                    kind = kind,
                    index = index,
                    label = parsed.label,
                    reason = parsed.reason
                )
            }
        }

        if (scores.isEmpty()) {
            val reasons = skipped.joinToString("；") { it.describe() }
            throw CodexRadarException(
                "$kind 数据缺失：匹配到 ${result.items.size} 个条目，但没有有效分数（$reasons）"
            )
        }
        return CodexRadarParseResult(
            scores = scores,
            skipped = skipped,
            totalRows = result.items.size
        )
    }

    private sealed interface RowOutcome {
        data class Valid(val score: CodexRadarScore) : RowOutcome
        data class Skipped(val label: String, val reason: String) : RowOutcome
    }

    private fun parseRow(
        kind: String,
        item: BrowserTextListItem,
        maximum: Double
    ): RowOutcome {
        val label = displayLabel(item)
        return try {
            val score = parseScore(kind, item) ?: return RowOutcome.Skipped(label, "分数未加载")
            val (model, effort) = parseLabel(kind, label)
            val count = RATING_COUNT_PATTERN.find(item.itemText.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            RowOutcome.Valid(
                CodexRadarScore(
                    model = model,
                    effort = effort,
                    value = validateScore(kind, score, maximum, label),
                    ratingCount = count
                )
            )
        } catch (error: CodexRadarException) {
            RowOutcome.Skipped(label, error.message.orEmpty().ifBlank { "解析失败" })
        }
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
        // 先按“模型 + 档位”完整匹配；新版页面单元格的读屏文本会带附加信息
        // （如 “GPT-6.1 Sol ultra · 132.79 · USD 1.38 · 2229.6 秒”），再退化为前缀匹配。
        val match = EFFORT_PATTERN.matchEntire(label) ?: EFFORT_PREFIX_PATTERN.find(label)
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
