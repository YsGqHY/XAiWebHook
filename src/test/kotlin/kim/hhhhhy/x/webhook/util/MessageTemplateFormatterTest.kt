package kim.hhhhhy.x.webhook.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class MessageTemplateFormatterTest {
    @Test
    fun `render should replace nested variables`() {
        val result = MessageTemplateFormatter.render(
            template = "${'$'}{market.name} ${'$'}{count}",
            variables = mapOf(
                "market" to mapOf("name" to "测试市场"),
                "count" to 2
            ),
            config = FormatterConfig()
        )
        assertEquals("测试市场 2", result)
    }

    @Test
    fun `render should support nested foreach and if else`() {
        val result = MessageTemplateFormatter.render(
            template = "${'$'}{foreach relation in relations}${'$'}{relation.name}:${'$'}{if relation.items}${'$'}{foreach item in relation.items}${'$'}{item.name},${'$'}{endforeach}${'$'}{else}空${'$'}{endif};${'$'}{endforeach}",
            variables = mapOf(
                "relations" to listOf(
                    mapOf(
                        "name" to "A",
                        "items" to listOf(mapOf("name" to "x"), mapOf("name" to "y"))
                    ),
                    mapOf("name" to "B", "items" to emptyList<Map<String, Any?>>())
                )
            ),
            config = FormatterConfig()
        )
        assertEquals("A:x,y,;B:空;", result)
    }

    @Test
    fun `render should honor foreach limit`() {
        val result = MessageTemplateFormatter.render(
            template = "${'$'}{foreach item in items | limit:2}${'$'}{item}${'$'}{endforeach}",
            variables = mapOf("items" to listOf("a", "b", "c")),
            config = FormatterConfig()
        )
        assertEquals("ab", result)
    }

    @Test
    fun `render should preserve malformed block`() {
        val template = "before ${'$'}{foreach item in items}${'$'}{item}"
        val result = MessageTemplateFormatter.render(
            template = template,
            variables = mapOf("items" to listOf("a")),
            config = FormatterConfig()
        )
        assertEquals(template, result)
    }

    @Test
    fun `keyword extractor should support strict prefixes`() {
        val config = KeywordExtractionConfig(
            removePrefixes = listOf("poly"),
            trim = true,
            requirePrefixMatch = true
        )
        assertEquals("bitcoin", KeywordExtractor.extract("poly bitcoin", config))
        assertEquals("", KeywordExtractor.extract("普通聊天", config))
    }

    @Test
    fun `filter chain should apply blacklist and length`() {
        val result = FilterChain.validate(
            "blocked",
            FilterConfig(
                blacklist = BlacklistConfig(keywords = listOf("block")),
                length = LengthConfig(min = 2, max = 20)
            )
        )
        assertTrue(result is FilterResult.Reject)
    }

    @Test
    fun `render should protect template syntax inside resolved values`() {
        val context = kim.hhhhhy.x.webhook.model.ExecutionContext(
            config = kim.hhhhhy.x.webhook.config.PluginConfig.safeDefault(),
            event = kim.hhhhhy.x.webhook.model.EventContext(
                type = "group_message",
                botId = 1L,
                groupId = 2L,
                friendId = null,
                senderId = 3L,
                senderName = "sender",
                messageText = "event value",
                timestamp = 0L
            )
        )
        val result = MessageTemplateFormatter.render(
            template = "${'$'}{value}",
            variables = mapOf("value" to "${'$'}{event.messageText}"),
            config = FormatterConfig(),
            context = context
        )
        assertEquals("${'$'}{event.messageText}", result)
    }
}
