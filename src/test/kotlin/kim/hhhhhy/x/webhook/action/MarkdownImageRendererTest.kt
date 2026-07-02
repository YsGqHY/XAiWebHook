package kim.hhhhhy.x.webhook.action

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

    private fun ByteArray.hasPngHeader(): Boolean {
        val header = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        return size > header.size && take(header.size).toByteArray().contentEquals(header)
    }
}
