package kim.hhhhhy.x.webhook.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class KeywordExtractorTest {

    @Test
    fun `removes matched prefix and trims remainder`() {
        val config = KeywordExtractionConfig(removePrefixes = listOf("poly"))
        assertEquals("bitcoin", KeywordExtractor.extract("poly bitcoin", config))
    }

    @Test
    fun `matches prefix even with leading whitespace`() {
        val config = KeywordExtractionConfig(removePrefixes = listOf("poly"))
        val result = KeywordExtractor.extractDetailed("  poly bitcoin", config)
        assertTrue(result.prefixMatched)
        assertEquals("bitcoin", result.keyword)
    }

    @Test
    fun `reports prefix miss without requirePrefixMatch`() {
        val config = KeywordExtractionConfig(removePrefixes = listOf("poly"))
        val result = KeywordExtractor.extractDetailed("hello world", config)
        assertFalse(result.prefixMatched)
        assertNull(result.matchedPrefix)
        // extract 保持旧行为：未开启 requirePrefixMatch 时原样返回
        assertEquals("hello world", KeywordExtractor.extract("hello world", config))
    }

    @Test
    fun `requirePrefixMatch yields empty on miss`() {
        val config = KeywordExtractionConfig(
            removePrefixes = listOf("poly"),
            requirePrefixMatch = true
        )
        assertEquals("", KeywordExtractor.extract("hello world", config))
    }

    @Test
    fun `extractOrNull distinguishes miss and empty remainder`() {
        val config = KeywordExtractionConfig(removePrefixes = listOf("poly"))
        assertNull(KeywordExtractor.extractOrNull("hello world", config))
        // 前缀命中但剩余内容为空
        assertNull(KeywordExtractor.extractOrNull("poly", config))
        assertEquals("bitcoin", KeywordExtractor.extractOrNull("poly bitcoin", config))
    }

    @Test
    fun `records which prefix matched`() {
        val config = KeywordExtractionConfig(removePrefixes = listOf("polymarket", "poly"))
        val result = KeywordExtractor.extractDetailed("polymarket eth", config)
        assertEquals("polymarket", result.matchedPrefix)
        assertEquals("eth", result.keyword)
    }

    @Test
    fun `treats empty prefix list as matched`() {
        val config = KeywordExtractionConfig(removePrefixes = emptyList())
        val result = KeywordExtractor.extractDetailed("anything", config)
        assertTrue(result.prefixMatched)
        assertEquals("anything", result.keyword)
    }

    @Test
    fun `applies regex capture group and lowercase`() {
        val config = KeywordExtractionConfig(
            removePrefixes = listOf("模型"),
            pattern = """^(\S+)""",
            captureGroup = 1,
            toLowerCase = true
        )
        assertEquals("gpt-4", KeywordExtractor.extract("模型 GPT-4 extra", config))
    }

    @Test
    fun `falls back to input when regex is invalid`() {
        val config = KeywordExtractionConfig(
            removePrefixes = listOf("poly"),
            pattern = "([unclosed"
        )
        assertEquals("bitcoin", KeywordExtractor.extract("poly bitcoin", config))
    }
}
