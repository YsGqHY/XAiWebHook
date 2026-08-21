package kim.hhhhhy.x.webhook.polymarket

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * 把聚合事件及其开放子市场排版为可交给 HtmlImageRenderer 的静态数据卡。
 *
 * openhtmltopdf 仅完整支持 CSS 2.1，因此布局只使用 table 和普通块元素。
 */
internal object PolymarketEventCard {
    private val palette = Palette()

    fun render(
        event: PolymarketEvent,
        markets: List<PolymarketMarket>,
        eventPageUrl: String,
        generatedAt: Instant,
        options: PolymarketEventCardOptions = PolymarketEventCardOptions()
    ): String {
        val zoneId = ZoneId.of(options.timezone)
        val rows = if (markets.isEmpty()) {
            emptyState()
        } else {
            markets.joinToString(separator = "") { market -> marketRow(market) }
        }
        val updatedAt = formatTimestamp(event.updatedAt, zoneId) ?: "未知"
        val generatedAtText = DATE_TIME_FORMATTER.withZone(zoneId).format(generatedAt)
        val totalVolume = formatCurrency(event.volume?.toDoubleOrNull())
        val volume24hr = formatCurrency(event.volume24hr)
        val liquidity = formatCurrency(event.liquidity)

        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="UTF-8" />
              <style>${styleSheet(options.widthPx)}</style>
            </head>
            <body>
              <div class="shell">
                <div class="card">
                  <table class="hero"><tr>
                    <td class="hero-mark"><div class="mark">P</div></td>
                    <td class="hero-copy">
                      <div class="kicker">POLYMARKET / EVENT SNAPSHOT</div>
                      <div class="title">${escape(event.title)}</div>
                      <div class="subtitle">开放预测事件 · 数据更新 $updatedAt</div>
                    </td>
                    <td class="hero-status"><span class="status">实时市场</span></td>
                  </tr></table>

                  <table class="stats"><tr>
                    ${statCell("总交易量", totalVolume, "累计成交")}
                    ${statCell("24 小时交易量", volume24hr, "最近市场活跃度")}
                    ${statCell("流动性", liquidity, "当前可交易深度")}
                    ${statCell("开放子市场", markets.size.toString(), "按截止日期排序")}
                  </tr></table>

                  <div class="section-title">发布截止预测</div>
                  <table class="column-head"><colgroup>${columnGroup()}</colgroup><tr>
                    <td>子市场</td>
                    <td class="align-right">交易量</td>
                    <td class="align-right">当前概率</td>
                    <td class="align-right">趋势</td>
                    <td class="align-right">买入 是</td>
                    <td class="align-right">买入 否</td>
                  </tr></table>
                  <div class="rows">$rows</div>

                  <table class="legend"><tr>
                    <td><span class="legend-dot accent-dot"></span>概率条表示“是”的当前市场概率</td>
                    <td class="legend-right"><span class="legend-dot up-dot"></span>上涨 <span class="legend-dot down-dot"></span>下跌，单位为百分点</td>
                  </tr></table>
                  <div class="footer">数据来源 Gamma API · 生成于 $generatedAtText · ${escape(eventPageUrl)}</div>
                </div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun statCell(label: String, value: String, note: String): String {
        return """
            <td class="stat-cell"><div class="stat">
              <div class="stat-label">${escape(label)}</div>
              <div class="stat-value">${escape(value)}</div>
              <div class="stat-note">${escape(note)}</div>
            </div></td>
        """.trimIndent()
    }

    private fun marketRow(market: PolymarketMarket): String {
        val probability = yesProbability(market)
        val yesAsk = market.bestAsk?.coerceIn(0.0, 1.0) ?: probability
        val noAsk = market.bestBid?.let { 1.0 - it.coerceIn(0.0, 1.0) }
            ?: outcomePrice(market, 1)
            ?: probability?.let { 1.0 - it }
        val probabilityWidth = ((probability ?: 0.0) * 100.0).coerceIn(0.0, 100.0)
        val trend = trendView(market)
        val date = displayDate(market)
        val question = market.question.trim()
        val volume = formatCurrency(market.volume?.toDoubleOrNull())

        return """
            <div class="market-row">
              <table class="market"><colgroup>${columnGroup()}</colgroup><tr>
                <td class="market-main">
                  <div class="market-date">${escape(date)}</div>
                  <div class="market-question">${escape(question)}</div>
                </td>
                <td class="market-volume align-right">
                  <div class="cell-primary">$volume</div>
                  <div class="cell-secondary">累计成交</div>
                </td>
                <td class="market-prob align-right">
                  <div class="probability">${escape(formatProbability(probability))}</div>
                  <div class="prob-track"><div class="prob-fill" style="width:${formatCssNumber(probabilityWidth)}%"></div></div>
                </td>
                <td class="market-trend align-right">
                  <div class="trend ${trend.cssClass}">${escape(trend.value)}</div>
                  <div class="cell-secondary">${escape(trend.period)}</div>
                </td>
                <td class="market-action align-right"><div class="price yes-price">
                  <span class="price-label">买入 是</span><span class="price-value">${formatCents(yesAsk)}</span>
                </div></td>
                <td class="market-action align-right"><div class="price no-price">
                  <span class="price-label">买入 否</span><span class="price-value">${formatCents(noAsk)}</span>
                </div></td>
              </tr></table>
            </div>
        """.trimIndent()
    }

    private fun emptyState(): String {
        return """
            <div class="empty">
              <div class="empty-title">暂无开放子市场</div>
              <div class="empty-note">事件存在，但当前没有可展示的开放预测项。</div>
            </div>
        """.trimIndent()
    }

    private fun columnGroup(): String {
        return """
            <col style="width:34%" />
            <col style="width:12%" />
            <col style="width:17%" />
            <col style="width:13%" />
            <col style="width:12%" />
            <col style="width:12%" />
        """.trimIndent()
    }

    private fun yesProbability(market: PolymarketMarket): Double? {
        return outcomePrice(market, 0)
            ?: market.lastTradePrice?.coerceIn(0.0, 1.0)
            ?: market.bestAsk?.coerceIn(0.0, 1.0)
    }

    private fun outcomePrice(market: PolymarketMarket, index: Int): Double? {
        return market.outcomePrices?.getOrNull(index)?.toDoubleOrNull()?.coerceIn(0.0, 1.0)
    }

    private fun trendView(market: PolymarketMarket): TrendView {
        val (period, change) = when {
            market.oneDayPriceChange != null -> "24 小时" to market.oneDayPriceChange
            market.oneWeekPriceChange != null -> "7 天" to market.oneWeekPriceChange
            else -> return TrendView("暂无变化", "趋势", "trend-flat")
        }
        val points = requireNotNull(change) * 100.0
        return when {
            points > 0.0001 -> TrendView("▲ +${formatPoints(points)}", period, "trend-up")
            points < -0.0001 -> TrendView("▼ -${formatPoints(abs(points))}", period, "trend-down")
            else -> TrendView("● 持平", period, "trend-flat")
        }
    }

    private fun displayDate(market: PolymarketMarket): String {
        val parsed = market.effectiveEndDateIso?.let { value ->
            runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
        }
        return parsed?.format(CHINESE_DATE_FORMATTER)
            ?: market.groupItemTitle?.trim()?.takeIf { it.isNotBlank() }
            ?: "截止日期未知"
    }

    private fun formatProbability(value: Double?): String {
        if (value == null) return "-"
        val percent = value.coerceIn(0.0, 1.0) * 100.0
        if (percent > 0.0 && percent < 1.0) return "<1%"
        return formatDecimal(percent, if (percent < 10.0) 1 else 1) + "%"
    }

    private fun formatCents(value: Double?): String {
        if (value == null) return "-"
        return formatDecimal(value.coerceIn(0.0, 1.0) * 100.0, 2) + "¢"
    }

    private fun formatCurrency(value: Double?): String {
        if (value == null) return "$0"
        val normalized = value.coerceAtLeast(0.0)
        return when {
            normalized >= 1_000_000_000.0 -> "$" + formatDecimal(normalized / 1_000_000_000.0, 2) + "B"
            normalized >= 1_000_000.0 -> "$" + formatDecimal(normalized / 1_000_000.0, 2) + "M"
            normalized >= 1_000.0 -> "$" + formatDecimal(normalized / 1_000.0, 1) + "K"
            else -> "$" + formatDecimal(normalized, 0)
        }
    }

    private fun formatPoints(value: Double): String {
        val scale = if (value < 0.1) 2 else 1
        return formatDecimal(value, scale) + " 个点"
    }

    private fun formatCssNumber(value: Double): String = formatDecimal(value, 3)

    private fun formatDecimal(value: Double, scale: Int): String {
        return BigDecimal.valueOf(value)
            .setScale(scale, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun formatTimestamp(value: String?, zoneId: ZoneId): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val instant = runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: return raw
        return DATE_TIME_FORMATTER.withZone(zoneId).format(instant)
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun styleSheet(widthPx: Int): String = """
        @page { size: ${widthPx.coerceIn(900, 2400)}px; margin: 0; }
        * { box-sizing: border-box; }
        body {
          margin: 0; padding: 30px;
          background: ${palette.page}; color: ${palette.ink1};
          font-family: "Microsoft YaHei", "Noto Sans CJK SC", "Segoe UI", sans-serif;
          font-size: 17px; line-height: 1.4;
          word-wrap: break-word;
        }
        .shell { width: 100%; }
        .card {
          padding: 30px 32px 22px;
          border: 1px solid ${palette.border}; border-radius: 22px;
          background: ${palette.background};
        }
        .hero { width: 100%; border-collapse: collapse; }
        .hero-mark { width: 78px; vertical-align: top; }
        .mark {
          width: 60px; height: 60px; line-height: 60px;
          border-radius: 16px; background: ${palette.accent}; color: ${palette.onAccent};
          font-size: 30px; font-weight: 800; text-align: center;
        }
        .hero-copy { vertical-align: top; }
        .kicker {
          color: ${palette.accent}; font-size: 13px; font-weight: 700;
          letter-spacing: 1.6px;
        }
        .title {
          margin-top: 5px; color: ${palette.ink1};
          font-size: 38px; font-weight: 700; line-height: 1.18;
        }
        .subtitle { margin-top: 7px; color: ${palette.ink3}; font-size: 16px; }
        .hero-status { width: 150px; text-align: right; vertical-align: top; }
        .status {
          padding: 7px 13px; border: 1px solid ${palette.accentBorder};
          border-radius: 9px; background: ${palette.accentSoft}; color: ${palette.accent};
          font-size: 14px; font-weight: 700;
        }
        .stats {
          width: 100%; margin-top: 24px;
          border-collapse: separate; border-spacing: 8px 0; table-layout: fixed;
        }
        .stat-cell { padding: 0; vertical-align: top; }
        .stat {
          padding: 16px 18px; border: 1px solid ${palette.border};
          border-radius: 14px; background: ${palette.panel};
        }
        .stat-label { color: ${palette.ink3}; font-size: 13px; font-weight: 700; }
        .stat-value {
          margin-top: 5px; color: ${palette.ink1}; font-size: 27px; font-weight: 800;
          font-family: "Segoe UI", "Microsoft YaHei", sans-serif;
        }
        .stat-note { margin-top: 3px; color: ${palette.ink3}; font-size: 12px; }
        .section-title {
          margin-top: 28px; padding-bottom: 12px;
          border-bottom: 1px solid ${palette.border};
          color: ${palette.ink1}; font-size: 20px; font-weight: 800;
        }
        .column-head {
          width: 100%; margin-top: 12px; padding: 0 16px;
          border-collapse: collapse; table-layout: fixed;
          color: ${palette.ink3}; font-size: 12px; font-weight: 700;
          letter-spacing: .4px;
        }
        .column-head td { padding: 0 8px 7px; }
        .rows { width: 100%; }
        .market-row {
          margin-top: 9px; border: 1px solid ${palette.border};
          border-radius: 15px; background: ${palette.row};
          page-break-inside: avoid;
        }
        .market { width: 100%; border-collapse: collapse; table-layout: fixed; }
        .market td { padding: 16px 13px; vertical-align: middle; }
        .market-main { padding-left: 20px !important; }
        .market-date { color: ${palette.ink1}; font-size: 19px; font-weight: 800; }
        .market-question { margin-top: 4px; color: ${palette.ink3}; font-size: 12px; line-height: 1.35; }
        .align-right { text-align: right; }
        .cell-primary { color: ${palette.ink2}; font-size: 16px; font-weight: 700; }
        .cell-secondary { margin-top: 4px; color: ${palette.ink3}; font-size: 11px; }
        .probability {
          color: ${palette.ink1}; font-size: 27px; font-weight: 800;
          letter-spacing: -.6px;
        }
        .prob-track {
          width: 100%; height: 7px; margin-top: 7px;
          border-radius: 5px; background: ${palette.track}; overflow: hidden;
        }
        .prob-fill { height: 7px; border-radius: 5px; background: ${palette.accent}; }
        .trend { font-size: 14px; font-weight: 800; white-space: nowrap; }
        .trend-up { color: ${palette.up}; }
        .trend-down { color: ${palette.down}; }
        .trend-flat { color: ${palette.ink3}; }
        .price {
          padding: 11px 9px; border-radius: 11px;
          font-size: 13px; font-weight: 800; white-space: nowrap; text-align: center;
        }
        .yes-price { background: ${palette.upSoft}; color: ${palette.up}; }
        .no-price { background: ${palette.downSoft}; color: ${palette.down}; }
        .price-label { margin-right: 5px; font-size: 11px; }
        .price-value { font-size: 16px; }
        .empty {
          margin-top: 10px; padding: 42px 24px; border: 1px solid ${palette.border};
          border-radius: 15px; background: ${palette.panel}; text-align: center;
        }
        .empty-title { color: ${palette.ink1}; font-size: 20px; font-weight: 800; }
        .empty-note { margin-top: 6px; color: ${palette.ink3}; font-size: 14px; }
        .legend {
          width: 100%; margin-top: 18px; border-collapse: collapse;
          color: ${palette.ink3}; font-size: 12px;
        }
        .legend-right { text-align: right; }
        .legend-dot {
          display: inline-block; width: 8px; height: 8px; margin: 0 5px 0 12px;
          border-radius: 3px; vertical-align: middle;
        }
        .accent-dot { margin-left: 0; background: ${palette.accent}; }
        .up-dot { background: ${palette.up}; }
        .down-dot { background: ${palette.down}; }
        .footer {
          margin-top: 15px; padding-top: 13px; border-top: 1px solid ${palette.border};
          color: ${palette.ink3}; font-size: 11px; text-align: center;
          word-wrap: break-word;
        }
    """.trimIndent()

    private data class TrendView(
        val value: String,
        val period: String,
        val cssClass: String
    )

    private data class Palette(
        val page: String = "#eceff6",
        val background: String = "#fdfdfc",
        val panel: String = "#f5f6fa",
        val row: String = "#fbfbfd",
        val border: String = "#e8e9ef",
        val ink1: String = "#171a24",
        val ink2: String = "#3d4456",
        val ink3: String = "#626c82",
        val accent: String = "#4a46c9",
        val accentSoft: String = "#eeedfb",
        val accentBorder: String = "#d8d5f6",
        val onAccent: String = "#f8f8ff",
        val track: String = "#e4e6ef",
        val up: String = "#287657",
        val upSoft: String = "#e7f5ee",
        val down: String = "#bd464b",
        val downSoft: String = "#fbeaec"
    )

    private val CHINESE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(
        "yyyy年M月d日",
        Locale.SIMPLIFIED_CHINESE
    )
    private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd HH:mm",
        Locale.SIMPLIFIED_CHINESE
    )
}

internal data class PolymarketEventCardOptions(
    val widthPx: Int = 1440,
    val timezone: String = "Asia/Shanghai"
)
