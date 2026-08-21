package kim.hhhhhy.x.webhook.polymarket

import kim.hhhhhy.x.webhook.util.FormatterConfig
import kim.hhhhhy.x.webhook.util.MessageTemplateFormatter

internal object PolymarketFormatter {
    public const val DEFAULT_SUCCESS_TEMPLATE: String = ""
    public const val DEFAULT_EMPTY_TEMPLATE: String = "未找到包含 \"\${keyword}\" 的 Polymarket 市场"
    public const val DEFAULT_ERROR_TEMPLATE: String = "搜索 \"\${keyword}\" 时出错：\${error}"
    public const val DEFAULT_BLACKLIST_TEMPLATE: String = "关键词 \"\${keyword}\" 已被禁止搜索"

    public fun formatSearchResult(
        result: PolymarketSearchResult,
        template: String? = null,
        config: FormatterConfig? = null
    ): String {
        val formatterConfig = config ?: FormatterConfig()
        return if (result.event != null) {
            if (template.isNullOrBlank()) {
                formatEventResultDefault(result, formatterConfig)
            } else {
                renderCustomTemplate(result, template, formatterConfig)
            }
        } else if (template.isNullOrBlank()) {
            formatSearchResultDefault(result, formatterConfig)
        } else {
            renderCustomTemplate(result, template, formatterConfig)
        }
    }

    private fun formatSearchResultDefault(
        result: PolymarketSearchResult,
        config: FormatterConfig
    ): String = buildString {
        val market = result.market
        appendLine(market.question)
        if (!market.category.isNullOrBlank()) {
            appendLine("分类：${market.category}")
        }
        appendLine()

        market.volume?.toDoubleOrNull()?.let { currentVolume ->
            appendLine("总交易量：$${MessageTemplateFormatter.formatNumber(currentVolume, config.compactNumbers)}")
        }

        val outcomes = market.outcomes ?: listOf("是", "否")
        val outcomePrices = market.outcomePrices?.map { it.toDoubleOrNull() ?: 0.0 }.orEmpty()
        if (outcomes.size >= 2 && outcomePrices.size >= 2) {
            appendLine()
            appendLine("当前价格：")
            appendLine("  ${outcomes[0]}：${formatMarketPrice(outcomePrices[0], config.priceUnit)}")
            appendLine("  ${outcomes[1]}：${formatMarketPrice(outcomePrices[1], config.priceUnit)}")
            appendLine("  概率：${formatProbability(outcomePrices[0])}")
        }

        val recentPoints = selectRecentHistoryByDate(result.priceHistory, config)
        if (recentPoints.isNotEmpty()) {
            appendLine()
            appendLine("历史数据：")
            recentPoints.forEach { point ->
                val date = MessageTemplateFormatter.formatDate(
                    timestamp = point.timestamp * 1_000L,
                    format = config.dateFormat,
                    timezone = config.timezone
                )
                val yesPrice = point.price
                val noPrice = 1.0 - yesPrice
                appendLine()
                appendLine(date)
                appendLine("  买入${outcomes.getOrElse(0) { "是" }}：${formatMarketPrice(yesPrice, config.priceUnit)}")
                appendLine("  买入${outcomes.getOrElse(1) { "否" }}：${formatMarketPrice(noPrice, config.priceUnit)}")
                appendLine("  ${formatProbability(yesPrice)} 概率")
                point.volume?.takeIf { it > 0 }?.let { volume ->
                    appendLine("  交易量：$${MessageTemplateFormatter.formatNumber(volume, config.compactNumbers)}")
                }
            }
        }

        market.effectiveEndDateIso?.takeIf { it.isNotBlank() }?.let { endDate ->
            appendLine()
            appendLine("结束时间：$endDate")
        }
    }

    private fun formatEventResultDefault(
        result: PolymarketSearchResult,
        config: FormatterConfig
    ): String = buildString {
        val event = requireNotNull(result.event)
        val markets = result.eventMarkets.ifEmpty { event.markets }
        appendLine(event.title)
        result.eventPageUrl?.takeIf { it.isNotBlank() }?.let { appendLine("事件页：$it") }
        event.volume?.toDoubleOrNull()?.let { volume ->
            appendLine("事件交易量：$${MessageTemplateFormatter.formatNumber(volume, config.compactNumbers)}")
        }
        val marketDates = markets.mapNotNull { it.effectiveEndDateIso?.takeIf(String::isNotBlank) }.distinct().sorted()
        when (marketDates.size) {
            1 -> appendLine("子市场结束时间：${marketDates.single()}")
            2 -> appendLine("子市场结束时间：${marketDates.first()} 至 ${marketDates.last()}")
            in 3..Int.MAX_VALUE -> appendLine("子市场结束时间：${marketDates.first()} 至 ${marketDates.last()}")
        }
        appendLine("子市场：${markets.size} 个")

        markets.forEachIndexed { index, market ->
            appendLine()
            appendLine("${index + 1}. ${market.question}")
            market.effectiveEndDateIso?.takeIf { it.isNotBlank() }?.let { appendLine("结束时间：$it") }
            marketPriceSummary(market, config)?.let { appendLine(it) }
        }

        val history = selectRecentHistoryByDate(result.priceHistory, config)
        if (history.isNotEmpty()) {
            appendLine()
            appendLine("主市场历史数据：")
            val outcomes = result.market.outcomes ?: listOf("是", "否")
            history.forEach { point ->
                val date = MessageTemplateFormatter.formatDate(
                    timestamp = point.timestamp * 1_000L,
                    format = config.dateFormat,
                    timezone = config.timezone
                )
                appendLine()
                appendLine(date)
                appendLine("  买入${outcomes.getOrElse(0) { "是" }}：${formatMarketPrice(point.price, config.priceUnit)}")
                appendLine("  买入${outcomes.getOrElse(1) { "否" }}：${formatMarketPrice(1.0 - point.price, config.priceUnit)}")
                appendLine("  ${formatProbability(point.price)} 概率")
            }
        }
    }

    private fun selectRecentHistoryByDate(
        points: List<PolymarketPricePoint>,
        config: FormatterConfig
    ): List<PolymarketPricePoint> {
        val byDate = LinkedHashMap<String, PolymarketPricePoint>()
        points.sortedBy { it.timestamp }.forEach { point ->
            val date = MessageTemplateFormatter.formatDate(
                timestamp = point.timestamp * 1_000L,
                format = config.dateFormat,
                timezone = config.timezone
            )
            byDate[date] = point
        }
        return byDate.values.toList().takeLast(config.maxHistoryPoints.coerceIn(1, 50))
    }

    private fun marketPriceSummary(
        market: PolymarketMarket,
        config: FormatterConfig
    ): String? {
        val outcomes = market.outcomes ?: listOf("是", "否")
        val prices = market.outcomePrices?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
        if (prices.size < 2) return null
        return "当前价格：${outcomes.getOrElse(0) { "是" }} ${formatMarketPrice(prices[0], config.priceUnit)} / " +
            "${outcomes.getOrElse(1) { "否" }} ${formatMarketPrice(prices[1], config.priceUnit)}，" +
            "概率：${formatProbability(prices[0])}"
    }

    private fun formatMarketPrice(price: Double, unit: String): String = when (unit.lowercase()) {
        "cents" -> "${formatDecimal(price * 100.0)}¢"
        "percent", "%" -> formatProbability(price)
        else -> MessageTemplateFormatter.formatPrice(price, unit)
    }

    private fun formatProbability(price: Double): String = "${formatDecimal(price * 100.0)}%"

    private fun formatDecimal(value: Double): String {
        return java.math.BigDecimal.valueOf(value)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun renderCustomTemplate(
        result: PolymarketSearchResult,
        template: String,
        config: FormatterConfig
    ): String {
        val market = result.market
        val outcomes = market.outcomes ?: listOf("是", "否")
        val outcomePrices = market.outcomePrices?.map { it.toDoubleOrNull() ?: 0.0 }.orEmpty()
        val yesPrice = outcomePrices.getOrNull(0)
        val noPrice = outcomePrices.getOrNull(1) ?: yesPrice?.let { 1.0 - it }
        val maxHistoryPoints = config.maxHistoryPoints.coerceIn(1, 50)
        val history = result.priceHistory.takeLast(maxHistoryPoints).mapIndexed { index, point ->
            mapOf(
                "index" to index,
                "timestamp" to point.timestamp,
                "timestampMillis" to point.timestamp * 1_000L,
                "date" to MessageTemplateFormatter.formatDate(
                    timestamp = point.timestamp * 1_000L,
                    format = config.dateFormat,
                    timezone = config.timezone
                ),
                "price" to point.price,
                "yesPrice" to point.price,
                "noPrice" to (1.0 - point.price),
                "probability" to (point.price * 100).toInt(),
                "volume" to point.volume
            )
        }
        val eventMarkets = result.eventMarkets.ifEmpty { result.event?.markets.orEmpty() }
            .map { eventMarket -> marketVariables(eventMarket, config) }
        val eventVariables = result.event?.let { event ->
            mapOf(
                "id" to event.id,
                "slug" to event.slug,
                "title" to event.title,
                "description" to event.description,
                "volume" to event.volume?.toDoubleOrNull(),
                "volumeFormatted" to event.volume?.toDoubleOrNull()?.let {
                    MessageTemplateFormatter.formatNumber(it, config.compactNumbers)
                },
                "active" to event.active,
                "closed" to event.closed,
                "endDate" to event.endDate,
                "markets" to eventMarkets
            )
        }
        val marketVariables = marketVariables(market, config)
        val variables = mapOf(
            "market" to marketVariables,
            "event" to eventVariables,
            "markets" to eventMarkets,
            "eventPageUrl" to result.eventPageUrl,
            "question" to market.question,
            "category" to market.category,
            "volume" to market.volume?.toDoubleOrNull(),
            "endDateIso" to market.endDateIso,
            "effectiveEndDateIso" to market.effectiveEndDateIso,
            "outcomes" to outcomes,
            "outcomePrices" to outcomePrices,
            "yesPrice" to yesPrice,
            "noPrice" to noPrice,
            "probability" to yesPrice?.let { (it * 100).toInt() },
            "priceHistory" to history,
            "history" to history
        )
        return MessageTemplateFormatter.render(template, variables, config)
    }

    private fun marketVariables(
        market: PolymarketMarket,
        config: FormatterConfig
    ): Map<String, Any?> {
        val outcomes = market.outcomes ?: listOf("是", "否")
        val outcomePrices = market.outcomePrices?.map { it.toDoubleOrNull() ?: 0.0 }.orEmpty()
        val volume = market.volume?.toDoubleOrNull()
        val outcomeItems = outcomes.mapIndexed { index, name ->
            mapOf(
                "index" to index,
                "name" to name,
                "price" to outcomePrices.getOrNull(index),
                "probability" to outcomePrices.getOrNull(index)?.let { (it * 100).toInt() }
            )
        }
        val noPrice = outcomePrices.getOrNull(1)
            ?: outcomePrices.getOrNull(0)?.let { 1.0 - it }
        return mapOf(
            "id" to market.id,
            "slug" to market.slug,
            "question" to market.question,
            "description" to market.description,
            "category" to market.category,
            "volume" to volume,
            "volumeFormatted" to volume?.let { MessageTemplateFormatter.formatNumber(it, config.compactNumbers) },
            "endDateIso" to market.endDateIso,
            "effectiveEndDateIso" to market.effectiveEndDateIso,
            "closed" to market.closed,
            "active" to market.active,
            "outcomes" to outcomeItems,
            "outcomePrices" to outcomePrices,
            "yesPrice" to outcomePrices.getOrNull(0),
            "noPrice" to noPrice,
            "probability" to outcomePrices.getOrNull(0)?.let { (it * 100).toInt() }
        )
    }

    public fun formatNoResults(keyword: String, template: String? = null): String {
        val resolvedTemplate = template?.takeIf { it.isNotBlank() } ?: DEFAULT_EMPTY_TEMPLATE
        return MessageTemplateFormatter.render(resolvedTemplate, mapOf("keyword" to keyword), FormatterConfig())
    }

    public fun formatError(keyword: String, error: String, template: String? = null): String {
        val resolvedTemplate = template?.takeIf { it.isNotBlank() } ?: DEFAULT_ERROR_TEMPLATE
        return MessageTemplateFormatter.render(
            resolvedTemplate,
            mapOf("keyword" to keyword, "error" to error),
            FormatterConfig()
        )
    }

    public fun formatBlacklisted(keyword: String, template: String? = null): String {
        val resolvedTemplate = template?.takeIf { it.isNotBlank() } ?: DEFAULT_BLACKLIST_TEMPLATE
        return MessageTemplateFormatter.render(resolvedTemplate, mapOf("keyword" to keyword), FormatterConfig())
    }
}
