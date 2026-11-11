package kim.hhhhhy.x.webhook.action

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ModelPlazaImagePackerTest {

    /** 用字符串长度模拟区块体积，渲染结果体积 = 区块体积之和。 */
    private fun fakeRender(chunk: List<String>): ByteArray =
        ByteArray(chunk.sumOf { it.length })

    @Test
    fun `预算足够时只有一页且覆盖全部区块`() {
        val sections = (1..10).map { "x".repeat(it * 10) }
        val rendered = mutableListOf<List<String>>()

        val pages = ModelPlazaImagePacker.paginate(sections, maxBytes = 100_000) { chunk ->
            rendered += chunk.toList()
            fakeRender(chunk)
        }

        assertEquals(1, pages.size, "预算足够时应当只有一页")
        assertEquals(sections, rendered.last(), "最后一次渲染应包含全部区块且顺序不变")
        assertEquals(sections.sumOf { it.length }, pages.single().size)
    }

    @Test
    fun `可容纳的卡片不会因校准渲染被拆开`() {
        // 12 个区块共 550 字节，预算 1000 足以整卡放下：不应产生多张图片
        val sections = (1..12).map { "x".repeat(10 + it * 4) }

        val pages = ModelPlazaImagePacker.paginate(sections, maxBytes = 1000) { chunk -> fakeRender(chunk) }

        assertEquals(1, pages.size)
    }

    @Test
    fun `超出预算时拆分且每页都不超额`() {
        val sections = (1..12).map { "x".repeat(100) }
        var lastChunkSum = 0

        val pages = ModelPlazaImagePacker.paginate(sections, maxBytes = 350) { chunk ->
            lastChunkSum = chunk.sumOf { it.length }
            fakeRender(chunk)
        }

        assertTrue(pages.size > 1, "应当拆成多页")
        pages.forEach { assertTrue(it.size <= 350, "每页体积 ${it.size} 不应超过预算 350") }
        assertEquals(sections.sumOf { it.length }, pages.sumOf { it.size }, "所有区块都应被覆盖且不重复")
    }

    @Test
    fun `单区块超出预算时仍然整块渲染`() {
        val sections = listOf("x".repeat(1000))

        val pages = ModelPlazaImagePacker.paginate(sections, maxBytes = 100) { chunk -> fakeRender(chunk) }

        assertEquals(1, pages.size)
        assertEquals(1000, pages.single().size, "单区块无法再拆，只能整块发出")
    }

    @Test
    fun `空区块列表不产生图片`() {
        val pages = ModelPlazaImagePacker.paginate(emptyList(), maxBytes = 100) { chunk -> fakeRender(chunk) }

        assertTrue(pages.isEmpty())
    }

    @Test
    fun `预算非法时不影响收敛`() {
        val sections = (1..5).map { "x".repeat(50) }

        val pages = ModelPlazaImagePacker.paginate(sections, maxBytes = 0) { chunk -> fakeRender(chunk) }

        assertEquals(5, pages.size, "预算为 0 时每页只能容纳一个区块，且不会死循环")
    }
}
