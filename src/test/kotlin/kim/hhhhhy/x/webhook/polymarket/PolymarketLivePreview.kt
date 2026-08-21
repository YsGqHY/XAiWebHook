package kim.hhhhhy.x.webhook.polymarket

import kim.hhhhhy.x.webhook.action.PolymarketSearchAction
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.util.FilterChain
import kim.hhhhhy.x.webhook.util.FilterResult
import kim.hhhhhy.x.webhook.util.FormatterConfig as OutputFormatterConfig
import kotlinx.coroutines.runBlocking
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

internal object PolymarketLivePreview {
    @JvmStatic
    public fun main(args: Array<String>): Unit {
        val input = args.getOrElse(0) { "GPT-6" }
        val configPath = Paths.get(args.getOrElse(1) { "examples/webhook_config.yml" })
        val outputPath = Paths.get(args.getOrElse(2) { "build/tmp/polymarket-live/event-summary.txt" })
        val config = loadConfig(configPath)
        val polymarket = config.polymarket
        val filterResult = FilterChain.validate(
            input,
            PolymarketSearchAction.buildFilterConfig(polymarket)
        )
        require(filterResult is FilterResult.Pass) {
            (filterResult as FilterResult.Reject).message
        }

        runBlocking {
            try {
                val directReference = PolymarketSearchAction.parseEventReference(input)
                val searchEvents = if (directReference == null) {
                    PolymarketClient.searchEvents(
                        gammaApiBaseUrl = polymarket.gammaApiBaseUrl,
                        timeoutMillis = polymarket.timeoutMillis,
                        query = input,
                        proxyUrl = polymarket.proxyUrl
                    )
                } else {
                    emptyList()
                }
                val matchedEvent = if (directReference == null) {
                    PolymarketSearchAction.filterEventMatchesInSearchOrder(
                        events = searchEvents,
                        keyword = input,
                        searchFields = polymarket.searchFields
                    ).firstOrNull() ?: error("公共事件搜索没有相关命中: $input")
                } else {
                    null
                }
                val eventSlug = directReference?.slug ?: requireNotNull(matchedEvent).slug
                val eventPageUrl = directReference?.pageUrl
                    ?: PolymarketSearchAction.eventPageUrl(eventSlug, polymarket.locale)
                val event = PolymarketClient.getEventBySlug(
                    gammaApiBaseUrl = polymarket.gammaApiBaseUrl,
                    timeoutMillis = polymarket.timeoutMillis,
                    slug = eventSlug,
                    locale = polymarket.locale,
                    proxyUrl = polymarket.proxyUrl
                ) ?: error("未找到事件: $eventSlug")
                val markets = PolymarketSearchAction.selectEventMarkets(event.markets)
                val primary = markets.firstOrNull() ?: error("事件没有可用子市场: ${event.title}")
                val history = primary.clobTokenIds?.firstOrNull()?.let { token ->
                    PolymarketClient.getPriceHistory(
                        clobApiBaseUrl = polymarket.clobApiBaseUrl,
                        timeoutMillis = polymarket.timeoutMillis,
                        assetId = token,
                        interval = "1d",
                        proxyUrl = polymarket.proxyUrl
                    )
                }.orEmpty()
                val format = polymarket.responseFormat
                val formatterConfig = format?.let {
                    OutputFormatterConfig(
                        dateFormat = it.dateFormat,
                        timezone = it.timezone,
                        compactNumbers = it.compactNumbers,
                        priceUnit = "cents",
                        maxHistoryPoints = it.maxHistoryPoints.coerceIn(1, 50)
                    )
                }
                val output = PolymarketFormatter.formatSearchResult(
                    result = PolymarketSearchResult(
                        market = primary,
                        priceHistory = history,
                        event = event,
                        eventMarkets = markets,
                        eventPageUrl = eventPageUrl
                    ),
                    config = formatterConfig
                )
                outputPath.parent?.let { Files.createDirectories(it) }
                Files.writeString(outputPath, output, StandardCharsets.UTF_8)
                println("search_mode=${if (directReference == null) "keyword" else "url"}")
                println("public_search_events=${searchEvents.size}")
                println("whitelist_keywords=${polymarket.whitelist.keywords.size}")
                println("event_slug=${event.slug}")
                println("event_markets=${event.markets.size}")
                println("selected_markets=${markets.size}")
                println("primary_market=${primary.slug ?: primary.id}")
                println("history_points=${history.size}")
                println("output_file=${outputPath.toAbsolutePath()}")
                println("output_chars=${output.length}")
            } finally {
                PolymarketClient.close()
            }
        }
    }

    private fun loadConfig(path: Path): kim.hhhhhy.x.webhook.config.PluginConfig {
        require(Files.isRegularFile(path)) { "配置文件不存在: $path" }
        val raw = Files.newInputStream(path).use { input -> Yaml().load<Any?>(input) }
        val root = (raw as? Map<*, *>)?.entries?.associate { (key, value) ->
            requireNotNull(key).toString() to value
        } ?: error("配置文件根节点必须是 YAML 对象: $path")
        return WebHookConfig.parseConfig(root)
    }
}
