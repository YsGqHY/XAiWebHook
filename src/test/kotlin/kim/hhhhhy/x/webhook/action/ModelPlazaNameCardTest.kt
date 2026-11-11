package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.scraper.ModelPricingDetail
import kim.hhhhhy.x.webhook.scraper.ModelPlazaScraper
import kim.hhhhhy.x.webhook.scraper.TokenPrice
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ModelPlazaNameCardTest {

    @Test
    fun `fuzzy match should ignore case for opus`() {
        val candidates = listOf("claude-opus-4-5", "Claude-Opus-5", "CLAUDE-OPUS-4-8", "gpt-5", "gemini-pro")

        val matched = ModelPlazaScraper.fuzzyMatch("opus", candidates)

        assertEquals(listOf("claude-opus-4-5", "Claude-Opus-5", "CLAUDE-OPUS-4-8"), matched)
    }

    @Test
    fun `name card should group models by platform`() {
        val html = ModelPlazaNameCard.render(
            query = "opus",
            entries = listOf(
                ModelNameEntry("claude-opus-4-5", "anthropic"),
                ModelNameEntry("claude-opus-5", "anthropic"),
                ModelNameEntry("gpt-opus-mini", "openai")
            ),
            options = ModelPlazaCardOptions()
        )

        assertTrue(html.contains("Anthropic"))
        assertTrue(html.contains("Openai"))
        // 数量用徽标承载，摘要行给出总数与厂商数
        assertTrue(html.contains("3 个模型"))
        assertTrue(html.contains("来自 2 个厂商"))
        assertEquals(1, Regex(Regex.escape("claude-opus-4-5")).findAll(html).count())
        assertTrue(html.contains("gpt-opus-mini"))
    }

    @Test
    fun `name card should switch to two columns when a platform has many models`() {
        val entries = (1..20).map { ModelNameEntry("model-opus-$it", "anthropic") }

        val html = ModelPlazaNameCard.render("opus", entries, ModelPlazaCardOptions())

        // 双列排布：每个序号单元格只出现一次，共 20 个
        assertEquals(20, Regex("<td class=\"col-index\">").findAll(html).count())
        assertEquals(1, Regex("<td class=\"col-index\">1</td>").findAll(html).count())
        assertEquals(1, Regex("<td class=\"col-index\">11</td>").findAll(html).count())
        assertEquals(1, Regex("<td class=\"col-index\">20</td>").findAll(html).count())
        entries.forEach { assertTrue(html.contains(it.name)) }
    }

    @Test
    fun `name card should keep single column for few models`() {
        val entries = (1..6).map { ModelNameEntry("model-opus-$it", "anthropic") }

        val html = ModelPlazaNameCard.render("opus", entries, ModelPlazaCardOptions())

        assertEquals(6, Regex("<td class=\"col-index\">").findAll(html).count())
        // 单列时不会产生补位的空白单元格
        assertFalse(html.contains("class=\"name-empty\""))
    }

    @Test
    fun `name card should escape html in model names`() {
        val html = ModelPlazaNameCard.render(
            "opus",
            listOf(ModelNameEntry("opus<script>", "anthropic")),
            ModelPlazaCardOptions()
        )

        assertTrue(html.contains("opus&lt;script&gt;"))
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `name card should reject empty entries instead of rendering a blank image`() {
        assertFailsWith<IllegalArgumentException> {
            ModelPlazaNameCard.render("opus", emptyList(), ModelPlazaCardOptions())
        }
    }

    @Test
    fun `name card should share the pricing card visual tokens`() {
        val pricingHtml = ModelPlazaPriceCard.renderModels(
            listOf(
                ModelPricingDetail(
                    modelName = "claude-opus-4-5",
                    badge = "新",
                    official = TokenPrice(5.0, 25.0, 6.25, 0.5),
                    groups = emptyList()
                )
            ),
            ModelPlazaCardOptions()
        )
        val nameHtml = ModelPlazaNameCard.render(
            "opus",
            listOf(ModelNameEntry("claude-opus-4-5", "anthropic")),
            ModelPlazaCardOptions()
        )

        // 两张卡片共用同一套版面骨架 token
        listOf(
            "background: #f6f7f9;",          // 页面底色
            "border-radius: 14px;",          // 卡片圆角
            "letter-spacing: 0.06em;",       // 页眉标题字距
            "font-size: 30px;",              // 大标题字号
            "font-size: 19px;",              // 章节标题字号
            "color: #4b5563;",               // 章节标题颜色
            "font-size: 17px;",              // 次要文字字号
            "background: #f7f9fb;",          // 表头底色
            "border: 1px solid #e5e8ee;",    // 单元格边框
            "size: 1080px 12000px;"          // 页宽与页高
        ).forEach { token ->
            assertTrue(pricingHtml.contains(token), "定价卡片缺少样式 token: $token")
            assertTrue(nameHtml.contains(token), "列表卡片缺少样式 token: $token")
        }

        // 列表卡片不应再引入骨架之外的调色板
        assertFalse(nameHtml.contains("#374151"))
    }

    @Test
    fun `name card table should start with a header row like the pricing card`() {
        val html = ModelPlazaNameCard.render(
            "opus",
            listOf(ModelNameEntry("claude-opus-4-5", "anthropic")),
            ModelPlazaCardOptions()
        )

        assertTrue(html.contains("<th class=\"col-index\">序号</th>"))
        assertTrue(html.contains("<th class=\"col-name\">模型名</th>"))
    }

    @Test
    fun `name card should reuse the shared title block for the query`() {
        val html = ModelPlazaNameCard.render(
            "opus",
            listOf(ModelNameEntry("claude-opus-4-5", "anthropic")),
            ModelPlazaCardOptions()
        )

        assertTrue(html.contains("class=\"model-head\""))
        assertTrue(html.contains("<span class=\"model-name\">opus</span>"))
        assertTrue(html.contains("<span class=\"badge\">1 个模型</span>"))
    }
}
