package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.scraper.GroupModelPricing
import kim.hhhhhy.x.webhook.scraper.GroupPricingDetail
import kim.hhhhhy.x.webhook.scraper.ModelGroupPricing
import kim.hhhhhy.x.webhook.scraper.ModelPricingDetail
import kim.hhhhhy.x.webhook.scraper.PlazaEndpoint
import kim.hhhhhy.x.webhook.scraper.TokenPrice
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ModelPlazaPriceCardTest {

    @Test
    fun `price value should keep two decimals above one dollar`() {
        assertEquals("10.00", ModelPlazaPriceCard.formatPriceValue(10.0))
        assertEquals("12.50", ModelPlazaPriceCard.formatPriceValue(12.5))
        assertEquals("0.25", ModelPlazaPriceCard.formatPriceValue(0.25))
        assertEquals("-", ModelPlazaPriceCard.formatPriceValue(null))
    }

    @Test
    fun `price value should trim trailing zeros below one dollar`() {
        assertEquals("0.1", ModelPlazaPriceCard.formatPriceValue(0.1))
        assertEquals("0.375", ModelPlazaPriceCard.formatPriceValue(0.375))
        assertEquals("0.0875", ModelPlazaPriceCard.formatPriceValue(0.0875))
        assertEquals("0.02", ModelPlazaPriceCard.formatPriceValue(0.02))
    }

    @Test
    fun `rate and discount should follow site display rules`() {
        assertEquals("0.4", ModelPlazaPriceCard.formatRate(0.4))
        assertEquals("1", ModelPlazaPriceCard.formatRate(1.0))
        assertEquals("1.5", ModelPlazaPriceCard.formatRate(1.5))
        assertEquals("3.5折", ModelPlazaPriceCard.formatDiscount(0.35))
        assertEquals("4折", ModelPlazaPriceCard.formatDiscount(0.4))
        assertNull(ModelPlazaPriceCard.formatDiscount(1.0))
        assertNull(ModelPlazaPriceCard.formatDiscount(1.5))
    }

    @Test
    fun `supported endpoints should drop unsupported and duplicate entries`() {
        val group = ModelGroupPricing(
            groupId = 132,
            groupName = "Claude-Cursor低缓存",
            rateMultiplier = 0.4,
            isFinalPrice = false,
            price = TokenPrice(4.0, 20.0, 5.0, 0.1),
            endpoints = listOf(
                endpoint("chat_completions", "/v1/chat/completions", supported = true),
                endpoint("stream_chat_completions", "/v1/chat/completions", supported = true),
                endpoint("messages", "/v1/messages", supported = true, transport = "native"),
                endpoint("generate_content", "/v1beta/models/x:generateContent", supported = false)
            )
        )

        val supported = group.supportedEndpoints

        assertEquals(
            listOf("/v1/chat/completions", "/v1/messages"),
            supported.map { it.path }
        )
        assertEquals(1, supported.count { it.path == "/v1/chat/completions" })
    }

    @Test
    fun `model card should list supported endpoints only under each group`() {
        val html = ModelPlazaPriceCard.renderModels(
            listOf(
                ModelPricingDetail(
                    modelName = "claude-fable-5-1",
                    badge = "新",
                    official = TokenPrice(10.0, 50.0, 12.5, 0.25),
                    groups = listOf(group("ccmax-纯文本满血", 0.35))
                )
            ),
            ModelPlazaCardOptions()
        )

        assertTrue(html.contains("官方定价"))
        assertTrue(html.contains("各分组实付价"))
        assertTrue(html.contains("支持端点"))
        assertTrue(html.contains("ccmax-纯文本满血"))
        assertTrue(html.contains("/v1/messages"))
        assertTrue(html.contains("/v1/chat/completions"))
        // 不支持的端点不应出现在卡片中
        assertFalse(html.contains("generateContent"))
        assertFalse(html.contains("不支持端点"))
        // 同一端点只出现一次
        assertEquals(1, Regex(Regex.escape("/v1/messages")).findAll(html).count())
    }

    @Test
    fun `model card should report missing endpoint data explicitly`() {
        val withoutEndpoints = ModelPlazaPriceCard.renderModels(
            listOf(
                ModelPricingDetail(
                    modelName = "claude-fable-5-1",
                    badge = null,
                    official = TokenPrice(10.0, 50.0, null, 0.25),
                    groups = listOf(group("ClaudeMax", 1.5, endpoints = emptyList()))
                )
            ),
            ModelPlazaCardOptions()
        )

        assertTrue(withoutEndpoints.contains("未返回端点信息"))
    }

    @Test
    fun `group card should apply the group rate to every model`() {
        val html = ModelPlazaPriceCard.renderGroups(
            listOf(
                GroupPricingDetail(
                    groupId = 129,
                    groupName = "ccmax-纯文本满血",
                    rateMultiplier = 0.35,
                    models = listOf(
                        GroupModelPricing(
                            modelName = "claude-fable-5-1",
                            badge = "新",
                            official = TokenPrice(10.0, 50.0, 12.5, 0.25),
                            isFinalPrice = false,
                            price = TokenPrice(3.5, 17.5, 4.375, 0.0875)
                        )
                    )
                )
            ),
            ModelPlazaCardOptions()
        )

        assertTrue(html.contains("ccmax-纯文本满血"))
        assertTrue(html.contains("×0.35"))
        assertTrue(html.contains("\$3.50/M"))
        assertTrue(html.contains("\$0.0875/M"))
    }

    @Test
    fun `groups sharing the same endpoints should merge into one block`() {
        val groups = listOf(
            group("Claude-Cursor低缓存", 0.4),
            group("ClaudeMax", 1.5),
            group("ClaudeMax-满血稳定企业", 1.6),
            group(
                name = "ccmax-纯文本满血",
                rate = 0.35,
                endpoints = listOf(
                    endpoint("messages", "/v1/messages", supported = true, transport = "native")
                )
            )
        )

        val html = ModelPlazaPriceCard.renderModels(
            listOf(
                ModelPricingDetail(
                    modelName = "claude-fable-5-1",
                    badge = "新",
                    official = TokenPrice(10.0, 50.0, 12.5, 0.25),
                    groups = groups
                )
            ),
            ModelPlazaCardOptions()
        )

        val clusters = ModelPlazaPriceCard.clusterEndpointGroups(groups)
        assertEquals(2, clusters.size)
        assertEquals(
            listOf("Claude-Cursor低缓存", "ClaudeMax", "ClaudeMax-满血稳定企业"),
            clusters[0].groupNames
        )
        assertEquals(
            listOf("/v1/chat/completions", "/v1/responses", "/v1/messages"),
            clusters[0].endpoints.map { it.path }
        )
        assertEquals(listOf("ccmax-纯文本满血"), clusters[1].groupNames)

        // 共享的端点清单在卡片中只出现一次
        assertEquals(1, Regex(Regex.escape("/v1/chat/completions")).findAll(html).count())
        assertEquals(1, Regex(Regex.escape("/v1/responses")).findAll(html).count())
        // /v1/messages 同时属于两块聚合结果
        assertEquals(2, Regex(Regex.escape("/v1/messages")).findAll(html).count())
        // 分组名仍然逐个保留
        groups.forEach { assertTrue(html.contains(it.groupName)) }
        // 端点行不再附带转换方式标记
        assertFalse(html.contains("转换"))
        assertFalse(html.contains("原生"))
    }

    @Test
    fun `clustering should ignore groups without supported endpoints`() {
        val groups = listOf(
            group("有端点", 0.4),
            group(
                name = "全部不支持",
                rate = 1.0,
                endpoints = listOf(
                    endpoint("generate_content", "/v1beta/models/x:generateContent", supported = false)
                )
            )
        )

        val clusters = ModelPlazaPriceCard.clusterEndpointGroups(groups)

        assertEquals(1, clusters.size)
        assertEquals(listOf("有端点"), clusters[0].groupNames)
    }

    private fun endpoint(
        key: String,
        path: String,
        supported: Boolean,
        transport: String = "converted"
    ): PlazaEndpoint = PlazaEndpoint(
        key = key,
        method = "POST",
        path = path,
        supported = supported,
        transport = transport,
        reasonCodes = emptyList()
    )

    private fun group(
        name: String,
        rate: Double,
        endpoints: List<PlazaEndpoint> = listOf(
            endpoint("chat_completions", "/v1/chat/completions", supported = true),
            endpoint("responses", "/v1/responses", supported = true),
            endpoint("messages", "/v1/messages", supported = true, transport = "native"),
            endpoint("generate_content", "/v1beta/models/claude-fable-5-1:generateContent", supported = false)
        )
    ): ModelGroupPricing = ModelGroupPricing(
        groupId = 1,
        groupName = name,
        rateMultiplier = rate,
        isFinalPrice = false,
        price = TokenPrice(10.0 * rate, 50.0 * rate, 12.5 * rate, 0.25 * rate),
        endpoints = endpoints
    )
}
