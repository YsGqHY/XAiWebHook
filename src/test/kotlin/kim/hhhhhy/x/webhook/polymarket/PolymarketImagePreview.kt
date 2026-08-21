package kim.hhhhhy.x.webhook.polymarket

import kim.hhhhhy.x.webhook.action.HtmlImageRenderer
import kim.hhhhhy.x.webhook.action.PolymarketSearchAction
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.util.FilterChain
import kim.hhhhhy.x.webhook.util.FilterResult
import kotlinx.coroutines.runBlocking
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import javax.imageio.ImageIO

/** 手工预览工具：查询真实 Polymarket 事件并将开放子市场重新渲染为本地 PNG。 */
internal object PolymarketImagePreview {
    @JvmStatic
    public fun main(args: Array<String>): Unit {
        val input = args.getOrElse(0) { "GPT-6" }
        val configPath = Paths.get(args.getOrElse(1) { "examples/webhook_config.yml" })
        val outputPath = Paths.get(args.getOrElse(2) { "build/polymarket-event-preview.png" })
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
                require(markets.isNotEmpty()) { "事件没有可展示子市场: ${event.title}" }
                val png = HtmlImageRenderer.render(
                    PolymarketEventCard.render(
                        event = event,
                        markets = markets,
                        eventPageUrl = eventPageUrl,
                        generatedAt = Instant.now(),
                        options = PolymarketEventCardOptions(timezone = polymarket.responseFormat?.timezone ?: "Asia/Shanghai")
                    )
                )
                outputPath.parent?.let { Files.createDirectories(it) }
                Files.write(outputPath, png)
                val image = requireNotNull(ImageIO.read(ByteArrayInputStream(png))) {
                    "图片渲染结果不是有效 PNG"
                }

                println("search_mode=${if (directReference == null) "keyword" else "url"}")
                println("public_search_events=${searchEvents.size}")
                println("whitelist_keywords=${polymarket.whitelist.keywords.size}")
                println("event_slug=${event.slug}")
                println("event_markets=${event.markets.size}")
                println("selected_markets=${markets.size}")
                println("output_file=${outputPath.toAbsolutePath()}")
                println("output_bytes=${png.size}")
                println("output_dimensions=${image.width}x${image.height}")
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
