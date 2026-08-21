package kim.hhhhhy.x.webhook.polymarket

import kim.hhhhhy.x.webhook.action.HtmlImageRenderer
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PolymarketEventCardTest {
    @Test
    fun `event card should render real market values without unsupported layout`() {
        val event = fixtureEvent()
        val html = PolymarketEventCard.render(
            event = event,
            markets = event.markets,
            eventPageUrl = "https://polymarket.com/zh/event/gpt-6-released-by",
            generatedAt = Instant.parse("2026-08-20T15:30:00Z")
        )

        assertTrue(html.contains("GPT-6由 OpenAI 发布？"))
        assertTrue(html.contains("$1.48M"))
        assertTrue(html.contains("$37.4K"))
        assertTrue(html.contains("2026年8月21日"))
        assertTrue(html.contains("&lt;1%"))
        assertTrue(html.contains("▲ +2 个点"))
        assertTrue(html.contains("▼ -0.2 个点"))
        assertTrue(html.contains("7 天"))
        assertTrue(html.contains("买入 是"))
        assertTrue(html.contains("2.5¢"))
        assertTrue(html.contains("98¢"))
        assertTrue(html.contains("https://polymarket.com/zh/event/gpt-6-released-by"))
        assertFalse(html.contains("display: flex"))
        assertFalse(html.contains("display: grid"))
    }

    @Test
    fun `event card should render png to build directory`() {
        val event = fixtureEvent()
        val png = HtmlImageRenderer.render(
            PolymarketEventCard.render(
                event = event,
                markets = event.markets,
                eventPageUrl = "https://polymarket.com/zh/event/gpt-6-released-by",
                generatedAt = Instant.parse("2026-08-20T15:30:00Z")
            )
        )
        val output = File("build/polymarket-event-card-test.png")
        output.parentFile?.mkdirs()
        output.writeBytes(png)

        assertTrue(png.size > 50_000, "png suspiciously small: ${png.size}")
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(png)))
        assertTrue(image.width >= 2_800, "unexpected image width: ${image.width}")
        assertTrue(image.height >= 1_000, "unexpected image height: ${image.height}")
    }

    private fun fixtureEvent(): PolymarketEvent {
        return PolymarketEvent(
            id = "36307",
            slug = "gpt-6-released-by",
            title = "GPT-6由 OpenAI 发布？",
            volume = "1476781.917224",
            volume24hr = 37445.735015,
            liquidity = 57452.93894,
            openInterest = 230369.132221,
            active = true,
            closed = false,
            updatedAt = "2026-08-20T15:07:46.309964Z",
            markets = listOf(
                PolymarketMarket(
                    id = "day",
                    slug = "gpt-6-august-21",
                    question = "GPT-6会在2026年8月21日之前发布吗？",
                    groupItemTitle = "2026年8月21日",
                    outcomes = listOf("是", "否"),
                    outcomePrices = listOf("0.005", "0.995"),
                    volume = "166114.133",
                    bestBid = 0.001,
                    bestAsk = 0.003,
                    oneDayPriceChange = -0.002,
                    active = true,
                    closed = false
                ),
                PolymarketMarket(
                    id = "month",
                    slug = "gpt-6-august-31",
                    question = "GPT-6 会在 2026 年 8 月 31 日之前发布吗？",
                    outcomes = listOf("是", "否"),
                    outcomePrices = listOf("0.0245", "0.9755"),
                    volume = "234872.30",
                    bestBid = 0.02,
                    bestAsk = 0.025,
                    oneDayPriceChange = 0.02,
                    active = true,
                    closed = false
                ),
                PolymarketMarket(
                    id = "week",
                    slug = "gpt-6-september-15",
                    question = "Will GPT-6 be released by September 15, 2026?",
                    outcomes = listOf("Yes", "No"),
                    outcomePrices = listOf("0.155", "0.845"),
                    volume = "0",
                    bestBid = 0.13,
                    bestAsk = 0.18,
                    oneWeekPriceChange = 0.04,
                    active = true,
                    closed = false
                ),
                PolymarketMarket(
                    id = "flat",
                    slug = "gpt-6-october-31",
                    question = "GPT-6会在2026年10月31日之前发布吗？",
                    outcomes = listOf("是", "否"),
                    outcomePrices = listOf("0.565", "0.435"),
                    volume = null,
                    bestBid = 0.56,
                    bestAsk = 0.57,
                    active = true,
                    closed = false
                )
            )
        )
    }
}
