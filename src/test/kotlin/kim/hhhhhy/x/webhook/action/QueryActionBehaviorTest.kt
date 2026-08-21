package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.ModelPlazaQueryConfig
import kim.hhhhhy.x.webhook.config.PluginConfig
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.polymarket.PolymarketEvent
import kim.hhhhhy.x.webhook.polymarket.PolymarketMarket
import kim.hhhhhy.x.webhook.polymarket.PolymarketSearchResult
import kim.hhhhhy.x.webhook.scraper.GroupModelsRelation
import kim.hhhhhy.x.webhook.util.FilterChain
import kim.hhhhhy.x.webhook.util.FilterResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class QueryActionBehaviorTest {
    private fun action(params: Map<String, Any?>): ActionConfig = ActionConfig(
        id = null,
        type = "query_model_plaza_models",
        enabled = true,
        params = params
    )

    private fun polymarketEventResult(): PolymarketSearchResult {
        val market = PolymarketMarket(
            id = "market-1",
            question = "Will GPT-6 be released by August 31, 2026?",
            outcomePrices = listOf("0.02", "0.98"),
            active = true,
            closed = false
        )
        val event = PolymarketEvent(
            id = "event-1",
            slug = "gpt-6-released-by",
            title = "GPT-6 released by...?",
            markets = listOf(market)
        )
        return PolymarketSearchResult(
            market = market,
            priceHistory = emptyList(),
            event = event,
            eventMarkets = event.markets,
            eventPageUrl = "https://polymarket.com/zh/event/gpt-6-released-by"
        )
    }

    @Test
    fun `action message override should distinguish absent and blank`() {
        val configured = ModelPlazaQueryConfig.modelsDefault().responseFormat

        val untouched = ModelPlazaQueryAction.effectiveResponseFormat(action(emptyMap()), configured)
        assertEquals(configured.pendingMessage, untouched.pendingMessage)

        val silenced = ModelPlazaQueryAction.effectiveResponseFormat(
            action(mapOf("pending_message" to "")),
            configured
        )
        assertEquals("", silenced.pendingMessage)

        val replaced = ModelPlazaQueryAction.effectiveResponseFormat(
            action(mapOf("empty_message" to "无匹配分组")),
            configured
        )
        assertEquals("无匹配分组", replaced.emptyMessage)
    }

    @Test
    fun `model plaza relations should honor sort and limits`() {
        val relations = listOf(
            GroupModelsRelation("beta", listOf("m1", "m2", "m3")),
            GroupModelsRelation("alpha", listOf("m4", "m5"))
        )
        val prepared = ModelPlazaQueryAction.prepareGroupRelations(
            relations = relations,
            action = action(mapOf("sort" to "alphabetical", "limit" to 1, "max_related_items" to 1)),
            config = ModelPlazaQueryConfig.modelsDefault()
        )

        assertEquals(1, prepared.size)
        assertEquals("alpha", prepared.single().groupName)
        assertEquals(listOf("m4"), prepared.single().modelNames)
    }

    @Test
    fun `polymarket ranking should respect search fields`() {
        val markets = listOf(
            PolymarketMarket(id = "1", question = "Bitcoin above 100k?", description = "crypto"),
            PolymarketMarket(id = "2", question = "Unrelated market", description = "mentions bitcoin"),
            PolymarketMarket(id = "3", question = "bitcoin", description = null, volume = "10")
        )

        val bothFields = PolymarketSearchAction.rankMatches(markets, "bitcoin", listOf("question", "description"))
        assertEquals(listOf("3", "1", "2"), bothFields.map { it.id })

        val questionOnly = PolymarketSearchAction.rankMatches(markets, "bitcoin", listOf("question"))
        assertEquals(listOf("3", "1"), questionOnly.map { it.id })
    }

    @Test
    fun `polymarket event filtering should match normalized model name and ignore unrelated results`() {
        val events = listOf(
            PolymarketEvent(
                id = "36307",
                slug = "gpt-6-released-by",
                title = "GPT-6 released by...?",
                volume = "1000",
                active = true,
                closed = false,
                markets = listOf(
                    PolymarketMarket(id = "1", question = "Will GPT-6 be released by August 31, 2026?")
                )
            ),
            PolymarketEvent(
                id = "779016",
                slug = "chatgpt-6-outage-days-in-august-2026",
                title = "# of ChatGPT 6 Outage Days in August 2026?",
                active = true,
                closed = false
            ),
            PolymarketEvent(
                id = "closed",
                slug = "gpt-6-closed",
                title = "GPT 6 closed market",
                active = false,
                closed = true
            )
        )

        val ranked = PolymarketSearchAction.filterEventMatchesInSearchOrder(
            events = events,
            keyword = "GPT 6",
            searchFields = listOf("question", "description")
        )

        assertEquals(listOf("gpt-6-released-by"), ranked.map { it.slug })
        assertEquals(
            "https://polymarket.com/zh/event/gpt-6-released-by",
            PolymarketSearchAction.eventPageUrl("gpt-6-released-by", "zh-CN")
        )
        assertEquals(
            "https://polymarket.com/event/gpt-6-released-by",
            PolymarketSearchAction.eventPageUrl("gpt-6-released-by", "en")
        )
    }

    @Test
    fun `polymarket event search should preserve provider order and choose the closest first result`() {
        val events = listOf(
            PolymarketEvent(
                id = "flash",
                slug = "next-deepseek-flash-released-byptptpt",
                title = "Next DeepSeek Flash released by...?",
                volume = "644.5",
                active = true,
                closed = false
            ),
            PolymarketEvent(
                id = "pro",
                slug = "next-deepseek-pro-released-byptptpt",
                title = "Next DeepSeek Pro released by...?",
                volume = "949.2",
                active = true,
                closed = false
            ),
            PolymarketEvent(
                id = "ipo",
                slug = "deepseek-ipo-byptptpt",
                title = "DeepSeek IPO by...?",
                volume = "14127.3",
                active = true,
                closed = false
            )
        )

        val deepSeekMatches = PolymarketSearchAction.filterEventMatchesInSearchOrder(
            events = events,
            keyword = "deepseek",
            searchFields = listOf("question", "description")
        )
        assertEquals(
            listOf(
                "next-deepseek-flash-released-byptptpt",
                "next-deepseek-pro-released-byptptpt",
                "deepseek-ipo-byptptpt"
            ),
            deepSeekMatches.map { it.slug }
        )

        val proMatches = PolymarketSearchAction.filterEventMatchesInSearchOrder(
            events = events,
            keyword = "deepseek pro",
            searchFields = listOf("question", "description")
        )
        assertEquals("next-deepseek-pro-released-byptptpt", proMatches.firstOrNull()?.slug)
    }

    @Test
    fun `polymarket should enforce model whitelist and ignore legacy blacklist settings`() {
        val config = WebHookConfig.parseConfig(
            mapOf(
                "polymarket" to mapOf(
                    "enabled" to true,
                    "blacklist" to listOf("GPT"),
                    "filters" to mapOf(
                        "blacklist" to mapOf(
                            "enabled" to true,
                            "keywords" to listOf("GPT", "Claude")
                        ),
                        "whitelist" to mapOf(
                            "enabled" to false,
                            "keywords" to listOf("bitcoin")
                        )
                    )
                )
            )
        ).polymarket
        val filter = PolymarketSearchAction.buildFilterConfig(config)

        assertNull(filter.blacklist)
        assertTrue(filter.whitelist?.enabled == true)
        assertIs<FilterResult.Pass>(FilterChain.validate("GPT-6", filter))
        assertIs<FilterResult.Pass>(FilterChain.validate("claude opus 5", filter))
        assertIs<FilterResult.Pass>(
            FilterChain.validate("https://polymarket.com/zh/event/gpt-6-released-by", filter)
        )
        val rejected = assertIs<FilterResult.Reject>(FilterChain.validate("bitcoin above 100k", filter))
        assertTrue(rejected.message.contains("仅支持搜索白名单中的大模型相关市场"))
        assertTrue(rejected.message.contains("bitcoin above 100k"))
    }

    @Test
    fun `polymarket event reference should accept localized official pages only`() {
        val reference = PolymarketSearchAction.parseEventReference(
            "https://polymarket.com/zh/event/gpt-6-released-by"
        )
        assertEquals("gpt-6-released-by", reference?.slug)
        assertEquals(
            "https://polymarket.com/zh/event/gpt-6-released-by",
            reference?.pageUrl
        )
        assertEquals(null, PolymarketSearchAction.parseEventReference("https://example.com/event/gpt-6"))
        assertEquals(null, PolymarketSearchAction.parseEventReference("https://polymarket.com/event/a/b"))
        assertEquals(null, PolymarketSearchAction.parseEventReference("http://polymarket.com/zh/event/gpt_6"))
    }

    @Test
    fun `polymarket event markets should prefer open markets ordered by end date`() {
        val markets = listOf(
            PolymarketMarket(id = "closed", question = "closed", closed = true, endDateIso = "2026-01-01"),
            PolymarketMarket(id = "late", question = "late", closed = false, active = true, endDateIso = "2026-12-31"),
            PolymarketMarket(id = "early", question = "early", closed = false, active = true, endDateIso = "2026-08-31"),
            PolymarketMarket(id = "inactive", question = "inactive", closed = false, active = false, endDateIso = "2026-07-31")
        )

        val selected = PolymarketSearchAction.selectEventMarkets(markets)
        assertEquals(listOf("early", "late"), selected.map { it.id })
    }

    @Test
    fun `event formatter should show event page and child markets`() {
        val event = kim.hhhhhy.x.webhook.polymarket.PolymarketEvent(
            id = "36307",
            slug = "gpt-6-released-by",
            title = "GPT-6 released by...",
            markets = listOf(
                PolymarketMarket(
                    id = "1",
                    question = "GPT-6 by August 31?",
                    outcomes = listOf("Yes", "No"),
                    outcomePrices = listOf("0.0205", "0.9795"),
                    closed = false,
                    active = true,
                    endDateIso = "2026-08-31"
                )
            )
        )
        val output = kim.hhhhhy.x.webhook.polymarket.PolymarketFormatter.formatSearchResult(
            result = kim.hhhhhy.x.webhook.polymarket.PolymarketSearchResult(
                market = event.markets.single(),
                priceHistory = emptyList(),
                event = event,
                eventMarkets = event.markets,
                eventPageUrl = "https://polymarket.com/zh/event/gpt-6-released-by"
            )
        )

        assertTrue(output.contains("GPT-6 released by..."))
        assertTrue(output.contains("https://polymarket.com/zh/event/gpt-6-released-by"))
        assertTrue(output.contains("GPT-6 by August 31?"))
        assertTrue(output.contains("2.05¢"))
        assertTrue(output.contains("97.95¢"))
        assertFalse(output.contains("未找到"))
    }

    @Test
    fun `polymarket delivery options should default to image and honor action overrides`() {
        val context = ExecutionContext(PluginConfig.safeDefault())
        val config = context.config.polymarket

        val defaults = PolymarketSearchAction.deliveryOptions(action(emptyMap()), context, config)
        assertEquals(PolymarketSearchAction.PolymarketOutputMode.IMAGE, defaults.outputMode)
        assertTrue(defaults.imageFallbackToText)
        assertEquals(1440, defaults.imageWidthPx)

        val overridden = PolymarketSearchAction.deliveryOptions(
            action(
                mapOf(
                    "output_mode" to "both",
                    "image_fallback_to_text" to false,
                    "image_width_px" to 9999
                )
            ),
            context,
            config
        )
        assertEquals(PolymarketSearchAction.PolymarketOutputMode.BOTH, overridden.outputMode)
        assertFalse(overridden.imageFallbackToText)
        assertEquals(2400, overridden.imageWidthPx)
    }

    @Test
    fun `polymarket image delivery should send only rendered image by default`() = runBlocking {
        val calls = mutableListOf<String>()
        val delivery = PolymarketSearchAction.deliverSuccess(
            result = polymarketEventResult(),
            formattedMessage = "legacy text",
            options = PolymarketSearchAction.PolymarketDeliveryOptions(
                outputMode = PolymarketSearchAction.PolymarketOutputMode.IMAGE,
                imageFallbackToText = true,
                imageWidthPx = 1440
            ),
            renderAndUploadImage = {
                calls += "render"
                "uploaded-image"
            },
            sendText = { calls += "text:$it" },
            sendImage = { calls += "image:$it" },
            sendBoth = { text, image -> calls += "both:$text:$image" }
        )

        assertEquals(PolymarketSearchAction.PolymarketDeliveryResult.IMAGE, delivery)
        assertEquals(listOf("render", "image:uploaded-image"), calls)
    }

    @Test
    fun `polymarket both mode should send one combined delivery`() = runBlocking {
        val calls = mutableListOf<String>()
        val delivery = PolymarketSearchAction.deliverSuccess(
            result = polymarketEventResult(),
            formattedMessage = "legacy text",
            options = PolymarketSearchAction.PolymarketDeliveryOptions(
                outputMode = PolymarketSearchAction.PolymarketOutputMode.BOTH,
                imageFallbackToText = true,
                imageWidthPx = 1440
            ),
            renderAndUploadImage = {
                calls += "render"
                "uploaded-image"
            },
            sendText = { calls += "text:$it" },
            sendImage = { calls += "image:$it" },
            sendBoth = { text, image -> calls += "both:$text:$image" }
        )

        assertEquals(PolymarketSearchAction.PolymarketDeliveryResult.BOTH, delivery)
        assertEquals(listOf("render", "both:legacy text:uploaded-image"), calls)
    }

    @Test
    fun `polymarket image failure should fall back to original text`() = runBlocking {
        val calls = mutableListOf<String>()
        val failingRender: suspend () -> String = { throw IllegalStateException("upload failed") }
        val delivery = PolymarketSearchAction.deliverSuccess(
            result = polymarketEventResult(),
            formattedMessage = "legacy text",
            options = PolymarketSearchAction.PolymarketDeliveryOptions(
                outputMode = PolymarketSearchAction.PolymarketOutputMode.IMAGE,
                imageFallbackToText = true,
                imageWidthPx = 1440
            ),
            renderAndUploadImage = failingRender,
            sendText = { calls += "text:$it" },
            sendImage = { calls += "image:$it" },
            sendBoth = { text, image -> calls += "both:$text:$image" },
            onImageFailure = { calls += "failure:${it.message}" }
        )

        assertEquals(PolymarketSearchAction.PolymarketDeliveryResult.IMAGE_FALLBACK_TEXT, delivery)
        assertEquals(listOf("failure:upload failed", "text:legacy text"), calls)
    }

    @Test
    fun `polymarket image failure should propagate when text fallback is disabled`() {
        val failingRender: suspend () -> String = { throw IllegalStateException("upload failed") }
        assertFailsWith<IllegalStateException> {
            runBlocking {
                PolymarketSearchAction.deliverSuccess(
                    result = polymarketEventResult(),
                    formattedMessage = "legacy text",
                    options = PolymarketSearchAction.PolymarketDeliveryOptions(
                        outputMode = PolymarketSearchAction.PolymarketOutputMode.IMAGE,
                        imageFallbackToText = false,
                        imageWidthPx = 1440
                    ),
                    renderAndUploadImage = failingRender,
                    sendText = {},
                    sendImage = {},
                    sendBoth = { _, _ -> }
                )
            }
        }
    }

    @Test
    fun `polymarket non event result should stay text in image mode`() = runBlocking {
        val calls = mutableListOf<String>()
        val market = PolymarketMarket(id = "market-only", question = "GPT-6 market")
        val delivery = PolymarketSearchAction.deliverSuccess(
            result = PolymarketSearchResult(market = market, priceHistory = emptyList()),
            formattedMessage = "market text",
            options = PolymarketSearchAction.PolymarketDeliveryOptions(
                outputMode = PolymarketSearchAction.PolymarketOutputMode.IMAGE,
                imageFallbackToText = false,
                imageWidthPx = 1440
            ),
            renderAndUploadImage = {
                calls += "unexpected-render"
                "image"
            },
            sendText = { calls += "text:$it" },
            sendImage = { calls += "image:$it" },
            sendBoth = { text, image -> calls += "both:$text:$image" }
        )

        assertEquals(PolymarketSearchAction.PolymarketDeliveryResult.NON_EVENT_TEXT, delivery)
        assertEquals(listOf("text:market text"), calls)
    }

    @Test
    fun `query actions should inherit override or clear feature proxy independently`() {
        val context = ExecutionContext(PluginConfig.safeDefault())
        val configured = "https://feature-proxy.example:8443"
        val absent = action(emptyMap())
        val overridden = action(mapOf("proxy" to "http://127.0.0.1:7890"))
        val direct = action(mapOf("proxy" to ""))

        assertEquals(configured, ModelPlazaQueryAction.actionProxyUrl(absent, context, configured))
        assertEquals("http://127.0.0.1:7890", ModelPlazaQueryAction.actionProxyUrl(overridden, context, configured))
        assertEquals("", ModelPlazaQueryAction.actionProxyUrl(direct, context, configured))

        assertEquals(configured, PolymarketSearchAction.actionProxyUrl(absent, context, configured))
        assertEquals("http://127.0.0.1:7890", PolymarketSearchAction.actionProxyUrl(overridden, context, configured))
        assertEquals("", PolymarketSearchAction.actionProxyUrl(direct, context, configured))
    }

    @Test
    fun `http request proxy should be action scoped and templated`() {
        val context = ExecutionContext(PluginConfig.safeDefault())
        val direct = ActionConfig(null, "http_request", true, emptyMap())
        val proxied = direct.copy(params = mapOf("proxy" to "https://proxy.example:8443"))

        assertEquals("", WebHookActionExecutor.httpRequestProxyUrl(direct, context))
        assertEquals("https://proxy.example:8443", WebHookActionExecutor.httpRequestProxyUrl(proxied, context))
    }
}
