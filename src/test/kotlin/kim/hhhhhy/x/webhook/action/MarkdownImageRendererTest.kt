package kim.hhhhhy.x.webhook.action

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

internal class MarkdownImageRendererTest {
    @Test
    fun renderSupportsChineseMarkdownListsAndBackticks(): Unit {
        val images = MarkdownImageRenderer.render(
            """
            已分段提交完成，工作区干净。

            提交记录：

            - `aa5b1fc 支持 incoming 长变量渲染为图片`
            - `1af085d 更新 incoming 图片渲染说明`

            已遵守 `.gitignore`：被忽略的 `.gradle/`、`.idea/`、`.narrafork/`、`build/` 未提交。

            ```kotlin
            val message = "Markdown 渲染测试"
            println(message)
            ```
            """.trimIndent()
        )

        assertTrue(images.isNotEmpty())
        assertTrue(images.all { it.hasPngHeader() })
    }

    @Test
    fun renderSupportsTablesLinksAndEmphasis(): Unit {
        val images = MarkdownImageRenderer.render(
            """
            # 渲染能力检查

            > 这是一段引用，包含 **加粗**、*斜体* 和 [链接](https://example.invalid)。

            | 项目 | 状态 |
            | --- | --- |
            | 列表 | 已支持 |
            | 表格 | 已支持 |

            1. 第一项
            2. 第二项包含 `inline code`
            """.trimIndent()
        )

        assertTrue(images.isNotEmpty())
        assertTrue(images.all { it.hasPngHeader() })
    }

    @Test
    fun imageHeightGrowsWithContent(): Unit {
        val shortImages = MarkdownImageRenderer.render("只有一行短内容。")
        val longImages = MarkdownImageRenderer.render(
            (1..40).joinToString("\n\n") { "第 $it 段较长的中文内容，用于验证图片高度随内容增加而增大。" }
        )

        val shortHeight = shortImages.single().pngHeight()
        val longHeight = longImages.single().pngHeight()

        // 短内容不应生成固定的大图，应显著小于长内容
        assertTrue(longHeight > shortHeight * 3, "expected long=$longHeight to be much taller than short=$shortHeight")
    }

    private fun ByteArray.hasPngHeader(): Boolean {
        val header = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        return size > header.size && take(header.size).toByteArray().contentEquals(header)
    }

    private fun ByteArray.pngHeight(): Int {
        return ByteArrayInputStream(this).use { input ->
            ImageIO.read(input).height
        }
    }
}
