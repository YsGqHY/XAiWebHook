package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.config.RequestImageBackgroundConfig
import kim.hhhhhy.x.webhook.config.RequestImageBorderConfig
import kim.hhhhhy.x.webhook.config.RequestImageMarginConfig
import kim.hhhhhy.x.webhook.config.RequestImageOutputConfig
import kim.hhhhhy.x.webhook.config.RequestImagePercentageConfig
import kim.hhhhhy.x.webhook.config.RequestImageStyle
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class ModelPlazaScreenshotDecorationTest {
    @Test
    fun `outer border and left copy should preserve every original pixel`() {
        val original = patternedImage(7, 5)
        val percentage = original.getSubimage(5, 1, 2, 3)
        assertLayout(original, percentage, decorate(original, percentage))
    }

    @Test
    fun `transparent and semitransparent pixels should not blend with the new background`() {
        val original = patternedImage(8, 6)
        original.setRGB(0, 0, 0x00345678)
        original.setRGB(1, 1, 0x80456789.toInt())
        original.setRGB(7, 5, 0x11442288)
        val percentage = original.getSubimage(5, 1, 3, 3)
        val originalBytes = encode(original)
        val percentageBytes = encode(percentage)
        val decorated = decode(WebPageScreenshotAction.decorateModelPlazaScreenshot(originalBytes, percentageBytes))
        assertLayout(decode(originalBytes), decode(percentageBytes), decorated)
    }

    @Test
    fun `a taller percentage should not clip the copy or original frame`() {
        val original = patternedImage(7, 3)
        val percentage = patternedImage(2, 13)
        assertLayout(original, percentage, decorate(original, percentage))
    }

    @Test
    fun `missing percentage should add only margins and preserve the original content`() {
        val original = patternedImage(100, 100)
        val decorated = decode(WebPageScreenshotAction.decorateModelPlazaScreenshot(encode(original), null))
        assertEquals(110, decorated.width)
        assertEquals(102, decorated.height)
        assertPixels(original, decorated, 5, 1)
        assertWhiteMargins(decorated, 5, 1)
    }

    @Test
    fun `margins should be five percent width and one percent height on each side`() {
        val original = patternedImage(480, 194)
        val percentage = patternedImage(8, 16)
        val decorated = decorate(original, percentage)
        assertEquals(550, decorated.width)
        assertEquals(204, decorated.height)
        assertLayout(original, percentage, decorated)
    }

    @Test
    fun `empty or invalid image input should fail explicitly`() {
        val validBytes = encode(patternedImage(2, 3))
        for (invalid in listOf(byteArrayOf(), byteArrayOf(1, 2, 3))) {
            assertFailsWith<IllegalArgumentException> {
                WebPageScreenshotAction.decorateModelPlazaScreenshot(invalid, validBytes)
            }
            assertFailsWith<IllegalArgumentException> {
                WebPageScreenshotAction.decorateModelPlazaScreenshot(validBytes, invalid)
            }
        }
    }

    @Test
    fun `custom border background and asymmetric margins should preserve the source images`() {
        val original = patternedImage(7, 5)
        val percentage = patternedImage(2, 3)
        val style = RequestImageStyle(
            border = RequestImageBorderConfig(widthPixels = 2, paddingPixels = 1, color = "#336699"),
            percentage = RequestImagePercentageConfig(gapPixels = 4, verticalAlign = "bottom"),
            margin = RequestImageMarginConfig(leftPercent = 10.0, rightPercent = 20.0,
                topPercent = 0.0, bottomPercent = 25.0),
            background = RequestImageBackgroundConfig(marginColor = "#FFEEDD", contentColor = "#112233")
        )
        val image = decorate(original, percentage, style)
        assertEquals(25, image.width)
        assertEquals(14, image.height)
        assertEquals(Color(0xFFEEDD).rgb, image.getRGB(0, 0))
        assertEquals(Color(0xFFEEDD).rgb, image.getRGB(24, 13))
        assertEquals(Color(0x112233).rgb, image.getRGB(4, 1))
        assertEquals(Color(0x336699).rgb, image.getRGB(8, 0))
        assertPixels(original, image, 11, 3)
        assertPixels(percentage, image, 2, 8)
    }

    @Test
    fun `disabled percentage should leave the source percentage intact and allow a frame`() {
        val original = patternedImage(7, 5)
        val style = RequestImageStyle(percentage = RequestImagePercentageConfig(enabled = false), margin = zeroMargins())
        // 禁用副本后不解码它，真实采集路径也不再截图百分比元素。
        val image = decode(WebPageScreenshotAction.decorateModelPlazaScreenshot(encode(original), byteArrayOf(1), style))
        assertEquals(13, image.width)
        assertEquals(11, image.height)
        assertPixels(original, image, 3, 3)
        assertEquals(Color(0xFF3B30).rgb, image.getRGB(0, 0))
    }

    @Test
    fun `disabled border should remove its line and inner padding`() {
        val original = patternedImage(7, 5)
        val percentage = patternedImage(2, 3)
        val style = RequestImageStyle(border = RequestImageBorderConfig(enabled = false), margin = zeroMargins())
        val image = decorate(original, percentage, style)
        assertEquals(15, image.width)
        assertEquals(5, image.height)
        assertPixels(original, image, 8, 0)
        assertPixels(percentage, image, 0, 1)
    }

    @Test
    fun `percentage should support top center and bottom alignment`() {
        val original = patternedImage(7, 9)
        val percentage = patternedImage(2, 3)
        for ((alignment, y) in listOf("top" to 0, "center" to 3, "bottom" to 6)) {
            val style = RequestImageStyle(
                border = RequestImageBorderConfig(enabled = false),
                percentage = RequestImagePercentageConfig(gapPixels = 0, verticalAlign = alignment),
                margin = zeroMargins()
            )
            val image = decorate(original, percentage, style)
            assertPixels(percentage, image, 0, y)
            assertPixels(original, image, 2, 0)
        }
    }

    @Test
    fun `alpha colors should preserve transparent pixels and not blend the original`() {
        val original = patternedImage(7, 5)
        original.setRGB(1, 1, 0x80665544.toInt())
        val percentage = patternedImage(2, 3)
        val style = RequestImageStyle(
            border = RequestImageBorderConfig(enabled = false),
            percentage = RequestImagePercentageConfig(gapPixels = 1),
            background = RequestImageBackgroundConfig(marginColor = "#80443322", contentColor = "#00000000")
        )
        val image = decorate(original, percentage, style)
        assertEquals(0x80443322.toInt(), image.getRGB(0, 0))
        assertEquals(0, image.getRGB(3, 3))
        assertPixels(original, image, 4, 1)
    }

    @Test
    fun `canvas pixel limit should reject before allocating the decorated canvas`() {
        val original = encode(patternedImage(7, 5))
        val style = RequestImageStyle(
            border = RequestImageBorderConfig(enabled = false),
            margin = zeroMargins(), output = RequestImageOutputConfig(maxPixels = 34)
        )
        val error = assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.decorateModelPlazaScreenshot(original, null, style)
        }
        assertEquals("请求图片合成画布超过 output.max_pixels (34)", error.message)
    }

    private fun zeroMargins(): RequestImageMarginConfig = RequestImageMarginConfig(
        leftPercent = 0.0, rightPercent = 0.0, topPercent = 0.0, bottomPercent = 0.0
    )

    private fun assertLayout(original: BufferedImage, percentage: BufferedImage, decorated: BufferedImage) {
        val frameWidth = original.width + 6
        val frameHeight = original.height + 6
        val contentWidth = percentage.width + 6 + frameWidth
        val contentHeight = maxOf(frameHeight, percentage.height)
        val horizontalMargin = (contentWidth + 19) / 20
        val verticalMargin = (contentHeight + 99) / 100
        val frameX = horizontalMargin + percentage.width + 6
        val frameY = verticalMargin + (contentHeight - frameHeight) / 2
        assertEquals(contentWidth + horizontalMargin * 2, decorated.width)
        assertEquals(contentHeight + verticalMargin * 2, decorated.height)
        val red = Color(255, 59, 48).rgb
        for (x in frameX until frameX + frameWidth) {
            assertEquals(red, decorated.getRGB(x, frameY), "top border $x")
            assertEquals(red, decorated.getRGB(x, frameY + frameHeight - 1), "bottom border $x")
        }
        for (y in frameY until frameY + frameHeight) {
            assertEquals(red, decorated.getRGB(frameX, y), "left border $y")
            assertEquals(red, decorated.getRGB(frameX + frameWidth - 1, y), "right border $y")
        }
        assertPixels(original, decorated, frameX + 3, frameY + 3)
        assertPixels(percentage, decorated, horizontalMargin, verticalMargin + (contentHeight - percentage.height) / 2)
        assertWhiteMargins(decorated, horizontalMargin, verticalMargin)
    }

    private fun assertWhiteMargins(image: BufferedImage, horizontalMargin: Int, verticalMargin: Int) {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (x < horizontalMargin || x >= image.width - horizontalMargin ||
                    y < verticalMargin || y >= image.height - verticalMargin
                ) assertEquals(Color.WHITE.rgb, image.getRGB(x, y), "margin ($x, $y)")
            }
        }
    }

    private fun assertPixels(expected: BufferedImage, actual: BufferedImage, x: Int, y: Int) {
        for (sourceY in 0 until expected.height) {
            for (sourceX in 0 until expected.width) {
                assertEquals(expected.getRGB(sourceX, sourceY), actual.getRGB(x + sourceX, y + sourceY),
                    "pixel ($sourceX, $sourceY)")
            }
        }
    }

    private fun patternedImage(width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, Color((x * 17) % 256, (y * 29) % 256, (x + y) * 7 % 256).rgb)
            }
        }
        return image
    }

    private fun decorate(
        original: BufferedImage,
        percentage: BufferedImage,
        style: RequestImageStyle = RequestImageStyle()
    ): BufferedImage = decode(WebPageScreenshotAction.decorateModelPlazaScreenshot(encode(original), encode(percentage), style))

    private fun encode(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }

    private fun decode(bytes: ByteArray): BufferedImage = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
}
