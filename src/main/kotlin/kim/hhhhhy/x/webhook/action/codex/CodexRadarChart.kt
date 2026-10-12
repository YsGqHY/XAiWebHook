package kim.hhhhhy.x.webhook.action.codex

import kim.hhhhhy.x.webhook.action.HtmlImageRenderer
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

/**
 * 将 Codex Radar 的雷达分和社区体感分排版为单张静态图片 HTML。
 *
 * openhtmltopdf 只支持 CSS 2.1 的稳定子集，因此矩阵使用 table-layout: fixed，
 * 不使用 flex、grid 或 CSS 变量。两种分数保持独立量表，不做等级判断或颜色混合。
 */
internal class CodexRadarChart(
    private val options: CodexRadarChartOptions
) {
    fun render(snapshot: CodexRadarSnapshot): String {
        val models = modelOrder(snapshot)
        val body = buildString {
            append(header(snapshot))
            if (snapshot.partial) {
                append(
                    "<div class=\"partial-notice\">页面等待超时，仅展示已读取的有效数据；“—”或 pending 条目已忽略。</div>"
                )
            }
            append(
                scoreSection(
                    title = "雷达分",
                    scale = "/150",
                    description = "站点雷达效率得分，满分 150",
                    notes = listOf(
                        "雷达分按百分制成绩 × 1.5 计分（满分150分）",
                        "64道题覆盖软件工程、空间推理、计算机视觉、终端工具使用、系统工程、科研计算与数据分析"
                    ),
                    accentClass = "radar",
                    scores = snapshot.radarScores,
                    models = models,
                    showRatingCount = false
                )
            )
            append(
                scoreSection(
                    title = "社区体感分",
                    scale = "/10",
                    description = "社区盲测平均体感，满分 10；附评分人数",
                    notes = listOf(
                        "当前展示滚动7天社区体感分",
                        "同一模型按每位蹬友窗口内最新一次评分计算"
                    ),
                    accentClass = "community",
                    scores = snapshot.communityScores,
                    models = models,
                    showRatingCount = true
                )
            )
            append(footer())
        }

        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="UTF-8" />
              <style>${styleSheet()}</style>
            </head>
            <body><div class="canvas">$body</div></body>
            </html>
        """.trimIndent()
    }

    /** 使用现有 HTML 渲染器生成 PNG，并校验其确实带有 PNG 文件头。 */
    fun renderPng(snapshot: CodexRadarSnapshot): ByteArray {
        val bytes = HtmlImageRenderer.render(render(snapshot))
        check(bytes.startsWithPngSignature()) { "Codex Radar chart renderer produced invalid PNG data" }
        return bytes
    }

    private fun header(snapshot: CodexRadarSnapshot): String {
        val generatedAt = snapshot.generatedAtText.trim().ifBlank { "—" }
        return """
            <table class="header"><tr>
              <td class="header-main">
                <div class="header-title">${escape(options.title)}</div>
                <div class="header-subtitle">${escape(options.subtitle)}</div>
              </td>
              <td class="header-time">
                <div class="header-time-label">生成时间</div>
                <div class="header-time-value">${escape(generatedAt)}</div>
              </td>
            </tr></table>
            <div class="header-rule"></div>
        """.trimIndent()
    }

    private fun scoreSection(
        title: String,
        scale: String,
        description: String,
        notes: List<String>,
        accentClass: String,
        scores: List<CodexRadarScore>,
        models: List<String>,
        showRatingCount: Boolean
    ): String {
        val scoreIndex = indexScores(scores)
        val rows = if (models.isEmpty()) {
            """
                <tr><td class="empty-row" colspan="7">暂无可显示分数</td></tr>
            """.trimIndent()
        } else {
            models.joinToString("") { model ->
                modelRow(
                    model = model,
                    scoreIndex = scoreIndex,
                    showRatingCount = showRatingCount
                )
            }
        }
        val effortHeaders = EFFORT_ORDER.joinToString("") { effort ->
            "<th class=\"effort-heading\"><span>${escape(effort)}</span></th>"
        }

        return """
            <div class="score-panel $accentClass">
              <table class="section-heading"><tr>
                <td class="section-heading-main">${escape(title)}</td>
                <td class="section-scale">${escape(scale)}</td>
              </tr></table>
              <div class="section-description">${escape(description)}</div>
              <div class="section-notes">${notes.joinToString("") { note ->
                  "<div class=\"section-note\">${escape(note)}</div>"
              }}</div>
              <table class="score-matrix">
                <colgroup>
                  <col class="model-column" />
                  <col class="effort-column" /><col class="effort-column" />
                  <col class="effort-column" /><col class="effort-column" />
                  <col class="effort-column" /><col class="effort-column" />
                </colgroup>
                <thead><tr>
                  <th class="model-heading">模型</th>
                  $effortHeaders
                </tr></thead>
                <tbody>$rows</tbody>
              </table>
            </div>
        """.trimIndent()
    }

    private fun modelRow(
        model: String,
        scoreIndex: Map<String, Map<String, CodexRadarScore>>,
        showRatingCount: Boolean
    ): String {
        val scores = scoreIndex[model].orEmpty()
        val cells = EFFORT_ORDER.joinToString("") { effort ->
            scoreCell(scores[effort], showRatingCount)
        }
        return """
            <tr>
              <th class="model-cell"><div class="model-box">${escape(model)}</div></th>
              $cells
            </tr>
        """.trimIndent()
    }

    private fun scoreCell(score: CodexRadarScore?, showRatingCount: Boolean): String {
        val scoreText = score?.let { formatScore(it.value) } ?: "—"
        val scoreClass = if (score?.value?.isFinite() == true) {
            "score-value"
        } else {
            "score-value score-missing"
        }
        val meta = if (showRatingCount) {
            val count = score?.ratingCount
                ?.takeIf { it >= 0 }
                ?.let { String.format(Locale.ROOT, "%,d 人评分", it) }
                ?: "评分人数 —"
            "<div class=\"score-meta\">${escape(count)}</div>"
        } else {
            ""
        }
        return """
            <td class="score-cell"><div class="score-box">
              <div class="$scoreClass">$scoreText</div>
              $meta
            </div></td>
        """.trimIndent()
    }

    private fun footer(): String {
        val customFooter = options.footer.trim().ifBlank { "数据来源：Codex Radar 页面抓取" }
        return """
            <div class="footer">
              <div class="footer-custom">${escape(customFooter)}</div>
              <div class="footer-note">
                数据来源：Codex Radar 页面抓取；雷达分与社区体感分采用不同量表，不可直接比较；“—”表示该模型或档位缺少有效分数，不代表 0。
              </div>
            </div>
        """.trimIndent()
    }

    private fun modelOrder(snapshot: CodexRadarSnapshot): List<String> {
        val models = LinkedHashSet<String>()
        (snapshot.radarScores + snapshot.communityScores).forEach { score ->
            score.model.trim().ifBlank { null }?.let(models::add)
        }
        return models.toList()
    }

    private fun indexScores(scores: List<CodexRadarScore>): Map<String, Map<String, CodexRadarScore>> {
        val indexed = LinkedHashMap<String, MutableMap<String, CodexRadarScore>>()
        scores.forEach { score ->
            val model = score.model.trim()
            val effort = score.effort.trim().lowercase(Locale.ROOT)
            if (model.isBlank() || effort !in EFFORT_ORDER) return@forEach
            val byEffort = indexed.getOrPut(model) { LinkedHashMap() }
            if (!byEffort.containsKey(effort)) {
                byEffort[effort] = score
            }
        }
        return indexed
    }

    private fun formatScore(value: Double): String =
        if (value.isFinite()) String.format(Locale.ROOT, "%.1f", value) else "—"

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun styleSheet(): String = """
        @page { size: ${options.widthPx.coerceIn(900, 3000)}px; margin: 0; }
        * { box-sizing: border-box; }
        body {
          margin: 0; padding: 28px;
          background: #f4eee4; color: #1d2829;
          font-family: "Microsoft YaHei", "Noto Sans CJK SC", "SimHei", sans-serif;
          font-size: 16px;
        }
        .canvas {
          padding: 28px 30px 24px;
          border: 1px solid #e3d8c9; border-radius: 18px;
          background: #fffdf8;
        }
        .header { width: 100%; border-collapse: collapse; table-layout: fixed; }
        .header-main { vertical-align: top; padding-right: 20px; }
        .header-title {
          color: #172526; font-size: 38px; font-weight: 700; line-height: 1.15;
          word-wrap: break-word;
        }
        .header-subtitle {
          margin-top: 8px; color: #627274; font-size: 18px; line-height: 1.35;
          word-wrap: break-word;
        }
        .header-time {
          width: 170px; vertical-align: top; text-align: right;
          font-family: "Consolas", "JetBrains Mono", "Courier New", monospace;
        }
        .header-time-label { color: #8b9898; font-size: 16px; }
        .header-time-value { margin-top: 6px; color: #455657; font-size: 16px; word-wrap: break-word; }
        .header-rule { margin-top: 18px; border-bottom: 2px solid #ece2d5; }
        .score-panel { margin-top: 24px; padding: 18px 18px 16px; border: 1px solid #eadfd1; border-radius: 14px; }
        .score-panel.radar { background: #f6fbfa; border-color: #c5dfdc; }
        .score-panel.community { background: #fff9f1; border-color: #ead3b6; }
        .section-heading { width: 100%; border-collapse: collapse; table-layout: fixed; }
        .section-heading-main { font-size: 26px; font-weight: 700; vertical-align: baseline; }
        .section-scale {
          width: 110px; color: #4c5d5e; font-size: 24px; font-weight: 700;
          text-align: right; vertical-align: baseline;
          font-family: "Consolas", "JetBrains Mono", "Courier New", monospace;
        }
        .radar .section-heading-main, .radar .section-scale { color: #0d6269; }
        .community .section-heading-main, .community .section-scale { color: #a86120; }
        .section-description { margin-top: 5px; color: #6d7a7a; font-size: 16px; line-height: 1.35; }
        .section-notes { margin-top: 4px; text-align: left; }
        .partial-notice { margin: 10px 0 14px; padding: 8px 12px; border: 1px solid #e5b86b; background: #fff8e8; color: #8a5a16; font-size: 14px; line-height: 1.4; text-align: center; }
        .section-note { color: #71807e; font-size: 13px; line-height: 1.4; word-wrap: break-word; }
        .radar .section-note { color: #4e7778; }
        .community .section-note { color: #8d6a4a; }
        .score-matrix {
          width: 100%; margin-top: 12px; border-collapse: collapse;
          table-layout: fixed;
        }
        .model-column { width: 21%; }
        .effort-column { width: 13.16%; }
        .score-matrix th, .score-matrix td { padding: 4px 3px; vertical-align: top; }
        .model-heading, .effort-heading {
          padding: 5px 8px 7px !important; color: #697676; border-bottom: 1px solid #d9d0c4;
          font-size: 16px; font-weight: 700; text-align: left;
        }
        .effort-heading { text-align: center; font-family: "Consolas", "JetBrains Mono", "Courier New", monospace; }
        .model-cell { text-align: left; }
        .model-box {
          min-height: 66px; padding: 14px 12px; border: 1px solid #e2d9cd; border-radius: 9px;
          background: #fffefa; color: #2a393a; font-size: 18px; font-weight: 700;
          line-height: 1.25; word-wrap: break-word;
        }
        .score-box {
          min-height: 66px; padding: 10px 2px 9px; border: 1px solid #d9e1df;
          border-radius: 9px; background: #ffffff; text-align: center;
        }
        .community .score-box { border-color: #e7d8c6; background: #fffefa; }
        .score-value {
          color: #0d6269; font-size: 30px; font-weight: 700; line-height: 1.1;
          font-family: "Consolas", "JetBrains Mono", "Courier New", monospace;
        }
        .community .score-value { color: #a86120; }
        .score-value.score-missing { color: #9ca5a4; }
        .score-meta { margin-top: 6px; color: #6e7b7a; font-size: 16px; line-height: 1.15; word-wrap: break-word; }
        .empty-row { padding: 18px !important; color: #87918f; font-size: 16px; text-align: center; }
        .footer { margin-top: 22px; padding-top: 16px; border-top: 1px solid #e7ddd1; text-align: center; }
        .footer-custom { color: #536363; font-size: 16px; line-height: 1.35; word-wrap: break-word; }
        .footer-note { margin-top: 7px; color: #7c8785; font-size: 16px; line-height: 1.4; word-wrap: break-word; }
    """.trimIndent()

    private fun ByteArray.startsWithPngSignature(): Boolean {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        return size >= signature.size && signature.indices.all { this[it] == signature[it] }
    }

    private companion object {
        val EFFORT_ORDER: List<String> = listOf("low", "medium", "high", "xhigh", "max", "ultra")
    }
}

/** 图表外观参数。 */
internal data class CodexRadarChartOptions(
    val title: String = "Codex Radar 双评分",
    val subtitle: String = "雷达分与社区体感分并列展示",
    val footer: String = "数据来源：Codex Radar 页面抓取",
    val widthPx: Int = 1680
)
