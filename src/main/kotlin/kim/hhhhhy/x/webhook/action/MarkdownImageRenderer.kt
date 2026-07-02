package kim.hhhhhy.x.webhook.action

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal object MarkdownImageRenderer {
    private const val WIDTH = 900
    private const val PADDING = 36
    private const val MAX_CONTENT_WIDTH = WIDTH - PADDING * 2
    private const val LINE_SPACING = 8
    private const val PARAGRAPH_SPACING = 18
    private const val CODE_BLOCK_PADDING = 16
    private val background = Color(0xFA, 0xFA, 0xF7)
    private val foreground = Color(0x22, 0x22, 0x22)
    private val muted = Color(0x66, 0x66, 0x66)
    private val border = Color(0xD8, 0xD8, 0xD0)
    private val quoteBorder = Color(0x9A, 0xA0, 0xA6)
    private val codeBackground = Color(0xEF, 0xEF, 0xE8)
    private val bodyFont = Font(Font.SANS_SERIF, Font.PLAIN, 24)
    private val boldFont = Font(Font.SANS_SERIF, Font.BOLD, 24)
    private val h1Font = Font(Font.SANS_SERIF, Font.BOLD, 34)
    private val h2Font = Font(Font.SANS_SERIF, Font.BOLD, 30)
    private val h3Font = Font(Font.SANS_SERIF, Font.BOLD, 27)
    private val codeFont = Font(Font.MONOSPACED, Font.PLAIN, 21)

    fun render(markdown: String): ByteArray {
        val blocks = parseBlocks(markdown)
        val measured = measure(blocks)
        val image = BufferedImage(WIDTH, measured.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics().applyQuality()
        try {
            graphics.color = background
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = border
            graphics.stroke = BasicStroke(1.5f)
            graphics.drawRoundRect(10, 10, image.width - 21, image.height - 21, 18, 18)
            draw(graphics, measured.blocks)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    private fun parseBlocks(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        val code = mutableListOf<String>()
        var inCodeBlock = false

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += parseParagraph(paragraph.joinToString(" ").trim())
                paragraph.clear()
            }
        }

        fun flushCode() {
            blocks += Block.Code(code.joinToString("\n"))
            code.clear()
        }

        markdown.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { line ->
            val trimmed = line.trimEnd()
            if (trimmed.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    flushCode()
                    inCodeBlock = false
                } else {
                    flushParagraph()
                    inCodeBlock = true
                }
                return@forEach
            }
            if (inCodeBlock) {
                code += trimmed
                return@forEach
            }
            if (trimmed.isBlank()) {
                flushParagraph()
                return@forEach
            }
            val left = trimmed.trimStart()
            when {
                left.startsWith("### ") -> {
                    flushParagraph()
                    blocks += Block.Heading(3, left.removePrefix("### ").trim())
                }
                left.startsWith("## ") -> {
                    flushParagraph()
                    blocks += Block.Heading(2, left.removePrefix("## ").trim())
                }
                left.startsWith("# ") -> {
                    flushParagraph()
                    blocks += Block.Heading(1, left.removePrefix("# ").trim())
                }
                left.startsWith(">") -> {
                    flushParagraph()
                    blocks += Block.Quote(left.removePrefix(">").trimStart())
                }
                isListItem(left) -> {
                    flushParagraph()
                    blocks += Block.ListItem(left.replace(Regex("^([-*+]|\\d+\\.)\\s+"), "").trim())
                }
                else -> paragraph += trimmed.trim()
            }
        }
        if (inCodeBlock) flushCode()
        flushParagraph()
        return blocks.ifEmpty { listOf(Block.Paragraph("")) }
    }

    private fun parseParagraph(value: String): Block {
        return if (value.startsWith("    ")) Block.Code(value.trimStart()) else Block.Paragraph(value)
    }

    private fun isListItem(value: String): Boolean = value.matches(Regex("^([-*+]|\\d+\\.)\\s+.+"))

    private fun measure(blocks: List<Block>): MeasuredImage {
        val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val graphics = scratch.createGraphics().applyQuality()
        try {
            val measuredBlocks = blocks.map { block -> measureBlock(graphics, block) }
            val contentHeight = measuredBlocks.sumOf { it.height } + (measuredBlocks.size - 1).coerceAtLeast(0) * PARAGRAPH_SPACING
            return MeasuredImage(measuredBlocks, (contentHeight + PADDING * 2).coerceAtLeast(120))
        } finally {
            graphics.dispose()
        }
    }

    private fun measureBlock(graphics: Graphics2D, block: Block): MeasuredBlock {
        return when (block) {
            is Block.Heading -> {
                val font = headingFont(block.level)
                val lines = wrapText(block.text, graphics.metrics(font), MAX_CONTENT_WIDTH)
                MeasuredBlock(block, lines, linesHeight(graphics.metrics(font), lines.size))
            }
            is Block.Paragraph -> {
                val metrics = graphics.metrics(bodyFont)
                val lines = wrapText(block.text, metrics, MAX_CONTENT_WIDTH)
                MeasuredBlock(block, lines, linesHeight(metrics, lines.size))
            }
            is Block.Quote -> {
                val metrics = graphics.metrics(bodyFont)
                val lines = wrapText(block.text, metrics, MAX_CONTENT_WIDTH - 24)
                MeasuredBlock(block, lines, linesHeight(metrics, lines.size))
            }
            is Block.ListItem -> {
                val metrics = graphics.metrics(bodyFont)
                val lines = wrapText(block.text, metrics, MAX_CONTENT_WIDTH - 30)
                MeasuredBlock(block, lines, linesHeight(metrics, lines.size))
            }
            is Block.Code -> {
                val metrics = graphics.metrics(codeFont)
                val lines = block.text.lines().flatMap { line -> wrapText(line.ifEmpty { " " }, metrics, MAX_CONTENT_WIDTH - CODE_BLOCK_PADDING * 2) }
                MeasuredBlock(block, lines, linesHeight(metrics, lines.size) + CODE_BLOCK_PADDING * 2)
            }
        }
    }

    private fun draw(graphics: Graphics2D, blocks: List<MeasuredBlock>) {
        var y = PADDING
        blocks.forEachIndexed { index, block ->
            when (val source = block.source) {
                is Block.Heading -> y = drawTextBlock(graphics, block.lines, headingFont(source.level), foreground, PADDING, y)
                is Block.Paragraph -> y = drawTextBlock(graphics, block.lines, bodyFont, foreground, PADDING, y)
                is Block.Quote -> {
                    graphics.color = quoteBorder
                    graphics.stroke = BasicStroke(5f)
                    graphics.drawLine(PADDING + 2, y + 2, PADDING + 2, y + block.height - 4)
                    y = drawTextBlock(graphics, block.lines, bodyFont, muted, PADDING + 22, y)
                }
                is Block.ListItem -> {
                    graphics.font = boldFont
                    graphics.color = foreground
                    val metrics = graphics.fontMetrics
                    graphics.drawString("-", PADDING, y + metrics.ascent)
                    y = drawTextBlock(graphics, block.lines, bodyFont, foreground, PADDING + 30, y)
                }
                is Block.Code -> {
                    graphics.color = codeBackground
                    graphics.fillRoundRect(PADDING, y, MAX_CONTENT_WIDTH, block.height, 12, 12)
                    graphics.color = border
                    graphics.stroke = BasicStroke(1.2f)
                    graphics.drawRoundRect(PADDING, y, MAX_CONTENT_WIDTH, block.height, 12, 12)
                    y = drawTextBlock(graphics, block.lines, codeFont, foreground, PADDING + CODE_BLOCK_PADDING, y + CODE_BLOCK_PADDING)
                    y += CODE_BLOCK_PADDING
                }
            }
            if (index != blocks.lastIndex) y += PARAGRAPH_SPACING
        }
    }

    private fun drawTextBlock(graphics: Graphics2D, lines: List<String>, font: Font, color: Color, x: Int, y: Int): Int {
        graphics.font = font
        graphics.color = color
        val metrics = graphics.fontMetrics
        var cursorY = y
        lines.forEach { line ->
            graphics.drawString(line, x, cursorY + metrics.ascent)
            cursorY += metrics.height + LINE_SPACING
        }
        return cursorY - LINE_SPACING
    }

    private fun wrapText(text: String, metrics: FontMetrics, maxWidth: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        text.forEachCodePoint { token ->
            val next = current.toString() + token
            if (current.isNotEmpty() && metrics.stringWidth(next) > maxWidth) {
                lines += current.toString()
                current = StringBuilder(token)
            } else {
                current.append(token)
            }
        }
        lines += current.toString()
        return lines
    }

    private fun String.forEachCodePoint(block: (String) -> Unit) {
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            block(String(Character.toChars(codePoint)))
            index += Character.charCount(codePoint)
        }
    }

    private fun linesHeight(metrics: FontMetrics, lineCount: Int): Int {
        return lineCount * metrics.height + (lineCount - 1).coerceAtLeast(0) * LINE_SPACING
    }

    private fun headingFont(level: Int): Font = when (level) {
        1 -> h1Font
        2 -> h2Font
        else -> h3Font
    }

    private fun Graphics2D.applyQuality(): Graphics2D {
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        return this
    }

    private fun Graphics2D.metrics(font: Font): FontMetrics {
        this.font = font
        return fontMetrics
    }

    private sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Paragraph(val text: String) : Block()
        data class Quote(val text: String) : Block()
        data class ListItem(val text: String) : Block()
        data class Code(val text: String) : Block()
    }

    private data class MeasuredBlock(
        val source: Block,
        val lines: List<String>,
        val height: Int
    )

    private data class MeasuredImage(
        val blocks: List<MeasuredBlock>,
        val height: Int
    )
}
