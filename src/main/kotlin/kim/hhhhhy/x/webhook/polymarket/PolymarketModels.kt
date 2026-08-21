package kim.hhhhhy.x.webhook.polymarket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

internal data class PolymarketMarket(
    val id: String,
    val question: String,
    val description: String? = null,
    val outcomes: List<String>? = null,
    val outcomePrices: List<String>? = null,
    val volume: String? = null,
    val volume24hr: Double? = null,
    val closed: Boolean? = null,
    val icon: String? = null,
    val category: String? = null,
    val endDateIso: String? = null,
    val clobTokenIds: List<String>? = null,
    val slug: String? = null,
    val active: Boolean? = null,
    val groupItemTitle: String? = null,
    val bestBid: Double? = null,
    val bestAsk: Double? = null,
    val lastTradePrice: Double? = null,
    val oneHourPriceChange: Double? = null,
    val oneDayPriceChange: Double? = null,
    val oneWeekPriceChange: Double? = null
) {
    val questionEndDateIso: String? get() = PolymarketDateResolver.fromQuestion(question)
    val effectiveEndDateIso: String? get() = questionEndDateIso ?: endDateIso?.take(10)
}

internal data class PolymarketEvent(
    val id: String,
    val slug: String,
    val title: String,
    val description: String? = null,
    val volume: String? = null,
    val volume24hr: Double? = null,
    val liquidity: Double? = null,
    val openInterest: Double? = null,
    val active: Boolean? = null,
    val closed: Boolean? = null,
    val endDate: String? = null,
    val updatedAt: String? = null,
    val markets: List<PolymarketMarket> = emptyList()
)

internal data class PolymarketPricePoint(
    val timestamp: Long,
    val price: Double,
    val volume: Double? = null
)

internal data class PolymarketSearchResult(
    val market: PolymarketMarket,
    val priceHistory: List<PolymarketPricePoint>,
    val event: PolymarketEvent? = null,
    val eventMarkets: List<PolymarketMarket> = emptyList(),
    val eventPageUrl: String? = null
)

internal object PolymarketDateResolver {
    private val chineseDate = Regex("""(?<!\d)(20\d{2})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日""")
    private val isoDate = Regex("""(?<!\d)(20\d{2})[-/](\d{1,2})[-/](\d{1,2})(?!\d)""")
    private val englishDate = Regex(
        """\b(January|February|March|April|May|June|July|August|September|October|November|December)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,)?\s+(20\d{2})\b""",
        RegexOption.IGNORE_CASE
    )

    fun fromQuestion(question: String): String? {
        chineseDate.find(question)?.let { match ->
            return toIso(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        isoDate.find(question)?.let { match ->
            return toIso(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        englishDate.find(question)?.let { match ->
            val month = monthNumber(match.groupValues[1]) ?: return null
            return toIso(match.groupValues[3], month.toString(), match.groupValues[2])
        }
        return null
    }

    private fun monthNumber(value: String): Int? = when (value.lowercase()) {
        "january" -> 1
        "february" -> 2
        "march" -> 3
        "april" -> 4
        "may" -> 5
        "june" -> 6
        "july" -> 7
        "august" -> 8
        "september" -> 9
        "october" -> 10
        "november" -> 11
        "december" -> 12
        else -> null
    }

    private fun toIso(year: String, month: String, day: String): String? {
        return runCatching {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt()).toString()
        }.getOrNull()
    }
}

internal object PolymarketJsonCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun decodeMarkets(element: JsonElement): List<PolymarketMarket> {
        val array = when {
            element is JsonArray -> element
            element is JsonObject -> element["data"] as? JsonArray
            else -> null
        } ?: error("Gamma markets response must be a JSON array")
        return array.mapNotNull { decodeMarket(it) }
    }

    fun decodeEvent(element: JsonElement): PolymarketEvent? {
        val objectValue = element as? JsonObject ?: return null
        val slug = string(objectValue, "slug")?.trim().orEmpty()
        val title = string(objectValue, "title")?.trim().orEmpty()
        if (slug.isBlank() || title.isBlank()) return null
        return PolymarketEvent(
            id = string(objectValue, "id").orEmpty(),
            slug = slug,
            title = title,
            description = string(objectValue, "description"),
            volume = string(objectValue, "volume"),
            volume24hr = number(objectValue, "volume24hr", "volume_24hr"),
            liquidity = number(objectValue, "liquidity"),
            openInterest = number(objectValue, "openInterest", "open_interest"),
            active = primitive(objectValue, "active")?.booleanOrNull,
            closed = primitive(objectValue, "closed")?.booleanOrNull,
            endDate = string(objectValue, "endDate", "end_date"),
            updatedAt = string(objectValue, "updatedAt", "updated_at"),
            markets = (objectValue["markets"] as? JsonArray)
                ?.mapNotNull { decodeMarket(it) }
                .orEmpty()
        )
    }

    fun decodePublicSearchEvents(element: JsonElement): List<PolymarketEvent> {
        val objectValue = element as? JsonObject
            ?: error("Gamma public search response must be a JSON object")
        val eventsElement = objectValue["events"] ?: return emptyList()
        if (eventsElement is JsonNull) return emptyList()
        val events = eventsElement as? JsonArray
            ?: error("Gamma public search response events must be an array")
        return events.mapNotNull { decodeEvent(it) }
    }

    fun decodeMarket(element: JsonElement): PolymarketMarket? {
        val objectValue = element as? JsonObject ?: return null
        val question = string(objectValue, "question")?.trim().orEmpty()
        if (question.isBlank()) return null
        return PolymarketMarket(
            id = string(objectValue, "id").orEmpty(),
            question = question,
            description = string(objectValue, "description"),
            outcomes = stringArray(objectValue, "outcomes"),
            outcomePrices = stringArray(objectValue, "outcomePrices", "outcome_prices"),
            volume = string(objectValue, "volume"),
            volume24hr = number(objectValue, "volume24hr", "volume_24hr"),
            closed = primitive(objectValue, "closed")?.booleanOrNull,
            icon = string(objectValue, "icon"),
            category = string(objectValue, "category"),
            endDateIso = string(objectValue, "endDateIso", "end_date_iso", "endDate"),
            clobTokenIds = stringArray(objectValue, "clobTokenIds", "clob_token_ids"),
            slug = string(objectValue, "slug"),
            active = primitive(objectValue, "active")?.booleanOrNull,
            groupItemTitle = string(objectValue, "groupItemTitle", "group_item_title"),
            bestBid = number(objectValue, "bestBid", "best_bid"),
            bestAsk = number(objectValue, "bestAsk", "best_ask"),
            lastTradePrice = number(objectValue, "lastTradePrice", "last_trade_price"),
            oneHourPriceChange = number(objectValue, "oneHourPriceChange", "one_hour_price_change"),
            oneDayPriceChange = number(objectValue, "oneDayPriceChange", "one_day_price_change"),
            oneWeekPriceChange = number(objectValue, "oneWeekPriceChange", "one_week_price_change")
        )
    }

    fun decodePriceHistory(element: JsonElement): List<PolymarketPricePoint> {
        val history = when {
            element is JsonArray -> element
            element is JsonObject -> element["history"] as? JsonArray
            else -> null
        } ?: error("CLOB prices history response must contain a history array")
        return history.mapNotNull { pointElement ->
            val point = pointElement as? JsonObject ?: return@mapNotNull null
            val timestamp = number(point, "t")?.toLong() ?: return@mapNotNull null
            val price = number(point, "p") ?: return@mapNotNull null
            PolymarketPricePoint(
                timestamp = timestamp,
                price = price,
                volume = number(point, "v")
            )
        }
    }

    private fun string(objectValue: JsonObject, vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { key -> primitive(objectValue, key)?.contentOrNull }
            .firstOrNull { it.isNotBlank() }
    }

    private fun stringArray(objectValue: JsonObject, vararg keys: String): List<String>? {
        for (key in keys) {
            val value = objectValue[key] ?: continue
            when (value) {
                is JsonArray -> {
                    return value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                }
                is JsonPrimitive -> {
                    val encoded = value.contentOrNull ?: continue
                    val decoded = runCatching { json.parseToJsonElement(encoded) }.getOrNull()
                    if (decoded is JsonArray) {
                        return decoded.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    }
                    if (encoded.isNotBlank()) return encoded.split(',').map { it.trim() }.filter { it.isNotBlank() }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun primitive(objectValue: JsonObject, key: String): JsonPrimitive? {
        return objectValue[key] as? JsonPrimitive
    }

    private fun number(objectValue: JsonObject, vararg keys: String): Double? {
        return keys.asSequence()
            .mapNotNull { key ->
                primitive(objectValue, key)?.doubleOrNull
                    ?: primitive(objectValue, key)?.contentOrNull?.toDoubleOrNull()
            }
            .firstOrNull()
    }
}
