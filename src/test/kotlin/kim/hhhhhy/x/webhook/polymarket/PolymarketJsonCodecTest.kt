package kim.hhhhhy.x.webhook.polymarket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class PolymarketJsonCodecTest {
    @Test
    fun `decode markets should support gamma top level array and string arrays`() {
        val payload = """
            [
              {
                "id": "42",
                "question": "Will this test pass?",
                "description": "fixture",
                "outcomes": "[\"Yes\",\"No\"]",
                "outcomePrices": "[\"0.65\",\"0.35\"]",
                "clobTokenIds": "[\"yes-token\",\"no-token\"]",
                "volume": "1234.5",
                "category": "Tech",
                "endDateIso": "2026-08-21T00:00:00Z",
                "closed": false
              }
            ]
        """.trimIndent()

        val market = PolymarketJsonCodec.decodeMarkets(PolymarketJsonCodec.json.parseToJsonElement(payload)).single()
        assertEquals("42", market.id)
        assertEquals(listOf("Yes", "No"), market.outcomes)
        assertEquals(listOf("0.65", "0.35"), market.outcomePrices)
        assertEquals(listOf("yes-token", "no-token"), market.clobTokenIds)
        assertEquals("2026-08-21T00:00:00Z", market.endDateIso)
    }

    @Test
    fun `decode event should include nested markets and page fields`() {
        val payload = """
            {
              "id": "36307",
              "slug": "gpt-6-released-by",
              "title": "GPT-6 released by...",
              "description": "event description",
              "volume": "12345.6",
              "volume24hr": 987.65,
              "liquidity": "4567.8",
              "openInterest": 3210.5,
              "active": true,
              "closed": false,
              "endDate": "2026-12-31T00:00:00Z",
              "updatedAt": "2026-08-20T15:07:46.309964Z",
              "markets": [
                {
                  "id": "2850825",
                  "slug": "will-gpt-6-be-released-by-august-31-2026-778",
                  "question": "GPT-6 会在 2026 年 8 月 31 日之前发布吗？",
                  "outcomes": "[\"是\",\"否\"]",
                  "outcomePrices": "[\"0.0205\",\"0.9795\"]",
                  "volume24hr": "123.45",
                  "groupItemTitle": "2026 年 8 月 31 日",
                  "bestBid": 0.02,
                  "bestAsk": "0.025",
                  "lastTradePrice": 0.021,
                  "oneHourPriceChange": "0.0005",
                  "oneDayPriceChange": 0.0015,
                  "oneWeekPriceChange": "-0.11",
                  "closed": false,
                  "active": true
                }
              ]
            }
        """.trimIndent()

        val event = PolymarketJsonCodec.decodeEvent(
            PolymarketJsonCodec.json.parseToJsonElement(payload)
        )

        assertNotNull(event)
        assertEquals("gpt-6-released-by", event.slug)
        assertEquals("GPT-6 released by...", event.title)
        assertEquals(987.65, event.volume24hr)
        assertEquals(4567.8, event.liquidity)
        assertEquals(3210.5, event.openInterest)
        assertEquals("2026-08-20T15:07:46.309964Z", event.updatedAt)
        assertEquals(1, event.markets.size)
        val market = event.markets.single()
        assertEquals("will-gpt-6-be-released-by-august-31-2026-778", market.slug)
        assertEquals(listOf("0.0205", "0.9795"), market.outcomePrices)
        assertEquals(123.45, market.volume24hr)
        assertEquals("2026 年 8 月 31 日", market.groupItemTitle)
        assertEquals(0.02, market.bestBid)
        assertEquals(0.025, market.bestAsk)
        assertEquals(0.021, market.lastTradePrice)
        assertEquals(0.0005, market.oneHourPriceChange)
        assertEquals(0.0015, market.oneDayPriceChange)
        assertEquals(-0.11, market.oneWeekPriceChange)
    }

    @Test
    fun `decode public search should read event list and allow null events`() {
        val payload = """
            {
              "events": [
                {
                  "id": "36307",
                  "slug": "gpt-6-released-by",
                  "title": "GPT-6 released by...?",
                  "active": true,
                  "markets": [
                    {
                      "id": "2850825",
                      "question": "Will GPT-6 be released by August 31, 2026?"
                    }
                  ]
                }
              ],
              "pagination": {"hasMore": false, "totalResults": 1}
            }
        """.trimIndent()

        val events = PolymarketJsonCodec.decodePublicSearchEvents(
            PolymarketJsonCodec.json.parseToJsonElement(payload)
        )
        assertEquals("gpt-6-released-by", events.single().slug)
        assertEquals(1, events.single().markets.size)
        assertEquals(
            emptyList(),
            PolymarketJsonCodec.decodePublicSearchEvents(
                PolymarketJsonCodec.json.parseToJsonElement("""{"events":null}""")
            )
        )
    }

    @Test
    fun `market effective date should prefer explicit date in question`() {
        val market = PolymarketMarket(
            id = "2850825",
            question = "GPT-6会在2026年9月30日之前发布吗？",
            endDateIso = "2026-06-30T00:00:00Z"
        )

        assertEquals("2026-09-30", market.questionEndDateIso)
        assertEquals("2026-09-30", market.effectiveEndDateIso)

        val english = PolymarketMarket(
            id = "en",
            question = "Will GPT-6 be released by August 31, 2026?",
            endDateIso = "2026-07-31T00:00:00Z"
        )
        assertEquals("2026-08-31", english.effectiveEndDateIso)
    }

    @Test
    fun `decode price history should read history object`() {
        val payload = """
            {"history":[{"t":1787270400,"p":0.65,"v":123.0}]}
        """.trimIndent()
        val history = PolymarketJsonCodec.decodePriceHistory(
            PolymarketJsonCodec.json.parseToJsonElement(payload)
        )
        assertEquals(1, history.size)
        assertEquals(1787270400L, history.single().timestamp)
        assertEquals(0.65, history.single().price)
        assertNotNull(history.single().volume)
    }

    @Test
    fun `formatter should honor max history points`() {
        val result = PolymarketFormatter.formatSearchResult(
            result = PolymarketSearchResult(
                market = PolymarketMarket(
                    id = "1",
                    question = "Test market",
                    outcomes = listOf("Yes", "No"),
                    outcomePrices = listOf("0.5", "0.5")
                ),
                priceHistory = listOf(
                    PolymarketPricePoint(0L, 0.1),
                    PolymarketPricePoint(86_400L, 0.9)
                )
            ),
            config = kim.hhhhhy.x.webhook.util.FormatterConfig(
                dateFormat = "yyyy-MM-dd",
                timezone = "UTC",
                maxHistoryPoints = 1
            )
        )
        assertEquals(1, Regex("\\n1970-").findAll(result).count())
        assertEquals(true, result.contains("1970-01-02"))
        assertEquals(false, result.contains("1970-01-01"))
    }
}
