package kim.hhhhhy.x.webhook.config

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class QueryConfigTest {
    @Test
    fun `default yaml should expose query customization`() {
        val root = QueryConfigTest::class.java.getResourceAsStream("/webhook_config.yml").use { input ->
            requireNotNull(input)
            Yaml().load<Map<String, Any?>>(input)
        }
        val config = WebHookConfig.parseConfig(root)
        val polymarketMap = root["polymarket"].asMap()
        val whitelistMap = polymarketMap["whitelist"].asMap()

        assertEquals(false, polymarketMap.containsKey("blacklist"))
        assertEquals(false, whitelistMap.containsKey("enabled"))
        assertTrue(whitelistMap["keywords"] is List<*>)
        assertEquals("zh", config.polymarket.locale)
        assertEquals(listOf("question", "description"), config.polymarket.searchFields)
        assertEquals(100, config.polymarket.searchPageSize)
        assertEquals(3, config.polymarket.maxSearchPages)
        assertEquals("", config.browser.proxyUrl)
        assertEquals("", config.modelPlaza.proxyUrl)
        assertEquals("", config.polymarket.proxyUrl)
        assertTrue(config.polymarket.whitelist.keywords.containsAll(listOf("GPT", "Claude", "Gemini", "DeepSeek", "Qwen")))
        assertEquals(false, config.polymarket.whitelist.caseSensitive)
        assertNotNull(config.modelPlaza.queries.models.responseFormat.successTemplate)
        assertNotNull(config.modelPlaza.queries.groups.responseFormat.successTemplate)
        assertTrue(config.modelPlaza.queries.models.keywordExtraction.requirePrefixMatch)
    }

    @Test
    fun `legacy model plaza config should receive compatible query defaults`() {
        val config = WebHookConfig.parseConfig(
            mapOf(
                "model_plaza" to mapOf(
                    "enabled" to true,
                    "base_url" to "https://example.invalid/model-plaza",
                    "timeout_ms" to 30_000
                ),
                "polymarket" to mapOf("enabled" to false)
            )
        )

        assertEquals("source", config.modelPlaza.queries.models.sort)
        assertEquals(0, config.modelPlaza.queries.models.limit)
        assertEquals("zh", config.polymarket.locale)
        assertEquals(listOf("question", "description"), config.polymarket.searchFields)
        assertEquals(100, config.polymarket.searchPageSize)
    }

    @Test
    fun `invalid search fields should fall back to defaults`() {
        val config = WebHookConfig.parseConfig(
            mapOf(
                "polymarket" to mapOf(
                    "enabled" to true,
                    "search_fields" to listOf("unsupported"),
                    "locale" to "??"
                )
            )
        )

        assertEquals(listOf("question", "description"), config.polymarket.searchFields)
        assertEquals("zh", config.polymarket.locale)
    }

    @Test
    fun `polymarket whitelist should fall back when absent or empty and accept custom names`() {
        val missing = WebHookConfig.parseConfig(
            mapOf("polymarket" to mapOf("enabled" to true))
        ).polymarket
        val empty = WebHookConfig.parseConfig(
            mapOf(
                "polymarket" to mapOf(
                    "enabled" to true,
                    "whitelist" to mapOf("keywords" to emptyList<String>())
                )
            )
        ).polymarket
        val custom = WebHookConfig.parseConfig(
            mapOf(
                "polymarket" to mapOf(
                    "enabled" to true,
                    "whitelist" to mapOf(
                        "keywords" to listOf("CustomLLM", "customllm", "GPT"),
                        "case_sensitive" to true,
                        "reject_message" to "仅允许：\${keyword}"
                    )
                )
            )
        ).polymarket

        assertEquals(PolymarketWhitelistConfig.DEFAULT_KEYWORDS, missing.whitelist.keywords)
        assertEquals(PolymarketWhitelistConfig.DEFAULT_KEYWORDS, empty.whitelist.keywords)
        assertEquals(listOf("CustomLLM", "GPT"), custom.whitelist.keywords)
        assertTrue(custom.whitelist.caseSensitive)
        assertEquals("仅允许：\${keyword}", custom.whitelist.rejectMessage)
    }

    @Test
    fun `polymarket image response options should parse and clamp safely`() {
        val configured = WebHookConfig.parseConfig(
            mapOf(
                "polymarket" to mapOf(
                    "response_format" to mapOf(
                        "output_mode" to "both",
                        "image_fallback_to_text" to false,
                        "image_width_px" to 9999
                    )
                )
            )
        ).polymarket.responseFormat
        requireNotNull(configured)
        assertEquals("both", configured.outputMode)
        assertEquals(false, configured.imageFallbackToText)
        assertEquals(2400, configured.imageWidthPx)

        val invalid = WebHookConfig.parseConfig(
            mapOf(
                "polymarket" to mapOf(
                    "response_format" to mapOf(
                        "output_mode" to "unsupported",
                        "image_width_px" to 100
                    )
                )
            )
        ).polymarket.responseFormat
        requireNotNull(invalid)
        assertEquals("image", invalid.outputMode)
        assertEquals(true, invalid.imageFallbackToText)
        assertEquals(900, invalid.imageWidthPx)
    }

    @Test
    fun `feature proxies should load independently and blank values should stay direct`() {
        val config = WebHookConfig.parseConfig(
            mapOf(
                "browser" to mapOf("proxy" to "http://127.0.0.1:7890"),
                "model_plaza" to mapOf("proxy" to "https://user:pass@proxy.example:8443"),
                "polymarket" to mapOf("proxy" to "   ")
            )
        )

        assertEquals("http://127.0.0.1:7890", config.browser.proxyUrl)
        assertEquals("https://user:pass@proxy.example:8443", config.modelPlaza.proxyUrl)
        assertEquals("", config.polymarket.proxyUrl)
    }

    @Test
    fun `invalid feature proxy should fail configuration loading`() {
        assertFailsWith<IllegalArgumentException> {
            WebHookConfig.parseConfig(
                mapOf("polymarket" to mapOf("proxy" to "socks5://127.0.0.1:1080"))
            )
        }
    }
}
