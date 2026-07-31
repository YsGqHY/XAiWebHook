package kim.hhhhhy.x.webhook.action.codex

/**
 * 生成降智检测图表 HTML。
 *
 * 底层渲染器为 openhtmltopdf（CSS 2.1），不支持 flex/grid/CSS 变量，
 * 因此整体使用 table 布局 + 固定列宽实现卡片网格。
 */
internal class CodexRadarChart(
    private val advisor: CodexRadarAdvisor,
    private val options: CodexRadarChartOptions
) {
    fun render(report: RadarReport): String {
        // 各模型档位数量不同（Luna 无 ultra、GPT-5.5 只有两档），
        // table-layout: fixed 会按当行列数均分宽度，导致列宽在行间不一致。
        // 统一按最大档位数补空白单元格，保证所有卡片等宽对齐。
        val columns = report.tiersByModel.values.maxOfOrNull { it.size } ?: 0
        val body = buildString {
            append(header(report))
            append(alertBanner(report))
            report.tiersByModel.forEach { (model, tiers) ->
                val advice = report.advices.firstOrNull { it.model == model }
                append(modelSection(model, tiers, advice, report, columns))
            }
            append(adviceSection(report))
            append(footer(report))
        }

        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="UTF-8" />
              <style>${styleSheet()}</style>
            </head>
            <body><div class="card">$body</div></body>
            </html>
        """.trimIndent()
    }

    private fun header(report: RadarReport): String {
        val updated = report.sourceUpdatedAt?.let { escape(CodexRadarTime.toDisplay(it)) } ?: "-"
        val alertCount = report.alerts.size
        // 圆角需作用在内联 span 上；openhtmltopdf 对 td 的 border-radius 支持不稳定
        val badge = if (alertCount > 0) {
            """<td class="hd-badge"><span class="pill alert">● $alertCount 个降智预警</span></td>"""
        } else {
            """<td class="hd-badge"><span class="pill ok">● 无降智预警</span></td>"""
        }
        return """
            <table class="hd"><tr>
              <td class="hd-main">
                <div class="hd-title">${escape(options.title)}</div>
                <div class="hd-sub">${escape(options.subtitle)}</div>
              </td>
              $badge
            </tr></table>
            <div class="hd-meta">数据更新 $updated · 生成 ${escape(report.generatedAtText)}</div>
        """.trimIndent()
    }

    private fun alertBanner(report: RadarReport): String {
        if (report.alerts.isEmpty()) return ""
        val rows = report.alerts.take(4).joinToString("") { alert ->
            val name = escape(
                advisor.modelLabel(alert.model) + " " + advisor.effortLabel(alert.effort) +
                    " " + advisor.officialEffortLabel(alert.effort)
            )
            val drops = listOfNotNull(
                alert.dropFrom24h?.let { "24h -" + String.format("%.1f", it) },
                alert.dropFrom48h?.let { "48h -" + String.format("%.1f", it) }
            ).joinToString(" / ").ifBlank { "持续下降" }
            // 预警 iq 取自 radar-insights 的最新单次采样，档位卡片取自
            // intelligence-efficiency 的聚合值，两者会有零点几的差异。
            // 同一张图里对同一档位显示两个数字会被当成 bug，故统一优先用卡片值。
            val iq = report.tierOf(alert.key)?.iq ?: alert.iq
            """<tr><td class="al-n">$name</td><td class="al-i">${
                iq?.let { formatIq(it) } ?: "-"
            }</td><td class="al-d">${escape(drops)}</td></tr>"""
        }
        return """<div class="al-wrap"><div class="al-hd">降智预警</div>
            <table class="al">$rows</table></div>""".trimIndent()
    }

    private fun modelSection(
        model: String,
        tiers: List<RadarTier>,
        advice: RadarModelAdvice?,
        report: RadarReport,
        columns: Int
    ): String {
        val label = escape(advisor.modelLabel(model))
        val alertedKeys = report.alerts.map { it.key }.toSet()
        // 「建议」标签语义是「换到这一档」，仅在确实需要切换时才打。
        // NORMAL 判定下 target 等于基准档位，若照打会与「智商正常」徽标自相矛盾。
        val recommendedKey = advice
            ?.takeIf { it.verdict == RadarVerdict.SWITCH || it.verdict == RadarVerdict.DEGRADED }
            ?.target?.key
        val cells = tiers.joinToString("") { tier ->
            tierCell(
                tier = tier,
                grade = report.grades[tier.key],
                alerted = tier.key in alertedKeys,
                recommended = recommendedKey != null && tier.key == recommendedKey
            )
        } + """<td class="cell-void"></td>""".repeat((columns - tiers.size).coerceAtLeast(0))
        val badge = advice?.let { verdictBadge(it) } ?: ""
        return """
            <div class="sec">
              <table class="sec-hd"><tr>
                <td class="sec-name">$label</td>
                <td class="sec-badge">$badge</td>
              </tr></table>
              <table class="grid"><tr>$cells</tr></table>
            </div>
        """.trimIndent()
    }

    private fun tierCell(
        tier: RadarTier,
        grade: RadarGrade?,
        alerted: Boolean,
        recommended: Boolean
    ): String {
        val resolved = grade ?: advisor.grade(tier.iq)
        val classes = buildString {
            append("cell")
            if (alerted) append(" cell-alert")
            if (recommended) append(" cell-pick")
        }
        val mark = when {
            alerted -> """<span class="tag tag-alert">降智</span>"""
            recommended -> """<span class="tag tag-pick">建议</span>"""
            else -> ""
        }
        return """
            <td class="$classes">
              <div class="cell-effort">${escape(advisor.effortLabel(tier.effort))}<span
                class="cell-effort-official">${escape(advisor.officialEffortLabel(tier.effort))}</span>$mark</div>
              <div class="cell-iq" style="color:${resolved.color}">${formatIq(tier.iq)}</div>
              <div class="cell-grade" style="color:${resolved.color}">${escape(resolved.label)}</div>
              <div class="cell-meta">${formatPrice(tier.priceUsd)} · ${formatMinutes(tier.minutes)}</div>
            </td>
        """.trimIndent()
    }

    private fun verdictBadge(advice: RadarModelAdvice): String {
        val (text, css) = when (advice.verdict) {
            RadarVerdict.NORMAL -> "智商正常" to "vb-ok"
            RadarVerdict.SWITCH -> advice.target?.effort.orEmpty().let { effort ->
                "建议换" + advisor.effortLabel(effort) + " " + advisor.officialEffortLabel(effort)
            } to "vb-warn"
            RadarVerdict.DEGRADED -> "全档位降智" to "vb-bad"
            RadarVerdict.UNKNOWN -> "暂无数据" to "vb-none"
        }
        return """<span class="vb $css">${escape(text)}</span>"""
    }

    private fun adviceSection(report: RadarReport): String {
        val rows = report.advices.joinToString("") { advice ->
            val css = when (advice.verdict) {
                RadarVerdict.NORMAL -> "ad-ok"
                RadarVerdict.SWITCH -> "ad-warn"
                RadarVerdict.DEGRADED -> "ad-bad"
                RadarVerdict.UNKNOWN -> "ad-none"
            }
            """<tr><td class="ad-dot $css">●</td><td class="ad-txt">${escape(advice.advice)}</td></tr>"""
        }
        return """
            <div class="ad-wrap">
              <div class="ad-hd">${escape(options.adviceTitle)}</div>
              <table class="ad">$rows</table>
            </div>
        """.trimIndent()
    }

    private fun footer(report: RadarReport): String {
        val recs = report.recommendations.take(4).joinToString("") { group ->
            val items = group.items.take(2).joinToString("，") { item ->
                // 推荐项 iq 与预警同理：来自 radar-insights 的另一次采样，
                // 与档位卡片的聚合值有零点几差异，统一以卡片值为准
                val iq = report.tierOf(item.key)?.iq ?: item.iq
                advisor.modelLabel(item.model) + " " + advisor.effortLabel(item.effort) +
                    " " + advisor.officialEffortLabel(item.effort) +
                    (iq?.let { " " + formatIq(it) } ?: "")
            }
            """<tr><td class="ft-k">${escape(group.title)}</td><td class="ft-v">${escape(items)}</td></tr>"""
        }
        val recBlock = if (recs.isBlank()) "" else """<table class="ft-rec">$recs</table>"""
        return """$recBlock<div class="ft">${escape(options.footer)}</div>"""
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun formatIq(value: Double): String = String.format("%.1f", value)

    private fun formatPrice(value: Double?): String =
        if (value == null) "-" else "$" + String.format("%.1f", value)

    private fun formatMinutes(value: Double?): String =
        if (value == null) "-" else "${Math.round(value)}分"

    private fun styleSheet(): String = """
        @page { size: ${options.widthPx}px; margin: 0; }
        * { box-sizing: border-box; }
        body {
          margin: 0; padding: 30px;
          background: #f4f6fb; color: #101828;
          font-family: "Microsoft YaHei", "Noto Sans CJK SC", "SimHei", sans-serif;
        }
        .card {
          padding: 30px 32px 24px;
          border: 1px solid #dfe4ee; border-radius: 18px; background: #ffffff;
        }
        .hd { width: 100%; border-collapse: collapse; }
        .hd-main { vertical-align: top; }
        .hd-title { color: #0b1324; font-size: 40px; font-weight: 700; line-height: 1.15; }
        .hd-sub { margin-top: 6px; color: #667085; font-size: 18px; letter-spacing: 1px; }
        .hd-badge { width: 240px; text-align: right; vertical-align: top; }
        .pill {
          /* openhtmltopdf 不会把过大的 border-radius 按盒尺寸等比收敛，
             999px 会被画成椭圆，这里用固定小半径保证圆角矩形 */
          padding: 7px 16px; border-radius: 8px;
          font-size: 17px; font-weight: 700; white-space: nowrap;
        }
        .pill.alert { border: 1px solid #fca5a5; background: #fef2f2; color: #dc2626; }
        .pill.ok { border: 1px solid #a7f3d0; background: #ecfdf5; color: #059669; }
        .hd-meta {
          margin-top: 14px; padding-bottom: 14px;
          border-bottom: 2px solid #eef1f6; color: #98a2b3; font-size: 16px;
        }
        .al-wrap {
          margin-top: 18px; padding: 14px 16px;
          border: 1px solid #fcd34d; border-radius: 12px; background: #fffbeb;
        }
        .al-hd { color: #b45309; font-size: 19px; font-weight: 700; }
        .al { width: 100%; margin-top: 8px; border-collapse: collapse; }
        .al td { padding: 5px 0; font-size: 17px; }
        .al-n { color: #92400e; font-weight: 700; }
        .al-i { width: 90px; color: #dc2626; font-weight: 700; text-align: right; }
        .al-d { width: 240px; color: #b45309; text-align: right; }
        /* 每个模型分段套一层浅色分组容器：24 张卡片仅靠间距分隔时归属关系不清 */
        .sec {
          margin-top: 16px; padding: 14px 5px 15px;
          border: 1px solid #eaeef4; border-radius: 14px; background: #fcfdff;
        }
        /* .grid 的 border-spacing 会让卡片内缩 9px，标题同步补 9px 才能与卡片左右对齐 */
        .sec-hd { width: 100%; padding: 0 9px; border-collapse: collapse; }
        .sec-name { color: #1d2939; font-size: 23px; font-weight: 700; }
        .sec-badge { text-align: right; }
        .vb {
          padding: 5px 14px; border-radius: 8px;
          font-size: 17px; font-weight: 700;
        }
        .vb-ok { border: 1px solid #a7f3d0; background: #ecfdf5; color: #047857; }
        .vb-warn { border: 1px solid #fcd34d; background: #fffbeb; color: #b45309; }
        .vb-bad { border: 1px solid #fca5a5; background: #fef2f2; color: #b91c1c; }
        .vb-none { border: 1px solid #e4e7ec; background: #f9fafb; color: #667085; }
        .grid {
          width: 100%; margin-top: 10px;
          border-collapse: separate; border-spacing: 9px 0; table-layout: fixed;
        }
        .cell {
          padding: 12px 12px 11px;
          border: 1px solid #e4e7ec; border-radius: 12px;
          background: #fbfcfe; vertical-align: top;
        }
        .cell-void { border: 0; background: transparent; }
        .cell-alert { border: 2px solid #f87171; background: #fef2f2; }
        .cell-pick { border: 2px solid #34d399; background: #f0fdf4; }
        .cell-effort { color: #475467; font-size: 15px; font-weight: 700; }
        /* 官方名用等宽字体弱化显示，与中文名区分，便于直接照抄为传参值 */
        .cell-effort-official {
          margin-left: 5px; color: #98a2b3;
          font-family: "Consolas", "JetBrains Mono", "Courier New", monospace;
          font-size: 13px; font-weight: 400;
        }
        .cell-iq { margin-top: 4px; font-size: 33px; font-weight: 700; line-height: 1.1; }
        .cell-grade { margin-top: 2px; font-size: 15px; font-weight: 700; }
        .cell-meta { margin-top: 4px; color: #98a2b3; font-size: 14px; }
        .tag {
          margin-left: 5px; padding: 1px 6px;
          border-radius: 5px; font-size: 12px; font-weight: 700;
        }
        .tag-alert { background: #fee2e2; color: #b91c1c; }
        .tag-pick { background: #d1fae5; color: #047857; }
        .ad-wrap {
          margin-top: 24px; padding: 16px 18px;
          border: 1px solid #dbeafe; border-radius: 14px; background: #f5f9ff;
        }
        .ad-hd {
          padding-bottom: 8px; margin-bottom: 4px;
          border-bottom: 1px solid #dbeafe;
          color: #1d4ed8; font-size: 21px; font-weight: 700;
        }
        .ad { width: 100%; border-collapse: collapse; }
        .ad td { padding: 5px 0; font-size: 20px; }
        .ad-dot { width: 24px; font-size: 15px; vertical-align: middle; }
        .ad-txt { color: #1d2939; font-weight: 700; }
        .ad-ok { color: #10b981; }
        .ad-warn { color: #f59e0b; }
        .ad-bad { color: #ef4444; }
        .ad-none { color: #98a2b3; }
        .ft-rec { width: 100%; margin-top: 18px; border-collapse: collapse; }
        .ft-rec td { padding: 5px 0; border-top: 1px solid #eef1f6; font-size: 16px; }
        .ft-k { width: 150px; color: #667085; font-weight: 700; }
        .ft-v { color: #344054; }
        .ft { margin-top: 16px; color: #b0b7c3; font-size: 15px; text-align: center; }
    """.trimIndent()
}

/** 图表外观参数。 */
internal data class CodexRadarChartOptions(
    val title: String = "Codex 降智检测",
    val subtitle: String = "CODEX INTELLIGENCE RADAR · 社区真实任务盲测",
    val adviceTitle: String = "建议",
    val footer: String = "数据来自 Codex 雷达 codexradar.com · 分数由社区盲测汇总，不代表官方结论",
    val widthPx: Int = 1680
)
