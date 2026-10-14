package kim.hhhhhy.x.webhook.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class RequestImageConfigTest {
    private fun resourceText(): String =
        requireNotNull(RequestImageConfigTest::class.java.getResourceAsStream("/request_image.yml")) {
            "request_image.yml resource is missing"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun fails(yaml: String): String =
        requireNotNull(assertFailsWith<IllegalArgumentException> { RequestImageConfigCodec.parse(yaml) }.message)

    private fun assertFailsAt(path: String, yaml: String): String {
        val message = fails(yaml)
        assertTrue(message.startsWith("$path:"), "expected error at $path but was: $message")
        return message
    }

    @Test
    fun `default resource matches default model and default render`() {
        val resource = resourceText()
        assertEquals(RequestImageStyle(), RequestImageConfigCodec.parse(resource))
        assertEquals(resource.replace("\r\n", "\n"), RequestImageConfigCodec.render(RequestImageStyle()))
        assertEquals(resource.toByteArray(Charsets.UTF_8).toList(), RequestImageConfigStore.defaultResourceBytes().toList())
        RequestImageStyle().validate()
    }

    @Test
    fun `default values follow the plan`() {
        val style = RequestImageStyle()
        assertEquals(1, style.version)
        assertEquals(RequestImageBorderConfig(true, 1, 2, "#FF3B30"), style.border)
        assertEquals(RequestImagePercentageConfig(true, 6, "center"), style.percentage)
        assertEquals(RequestImageMarginConfig(5.0, 5.0, 1.0, 1.0, 1), style.margin)
        assertEquals(RequestImageBackgroundConfig("#FFFFFF", "auto"), style.background)
        assertEquals(RequestImageOutputConfig(16_000_000L), style.output)
    }

    @Test
    fun `custom style round trips through render and parse`() {
        val style = RequestImageStyle(
            border = RequestImageBorderConfig(false, 0, 128, "#8022C55E"),
            percentage = RequestImagePercentageConfig(false, 256, "bottom"),
            margin = RequestImageMarginConfig(0.0, 12.5, 0.0001, 50.0, 0),
            background = RequestImageBackgroundConfig("#00000000", "#123abc"),
            output = RequestImageOutputConfig(64_000_000L)
        )
        assertEquals(style, RequestImageConfigCodec.parse(RequestImageConfigCodec.render(style)))
        assertEquals("0.0001", RequestImageConfigCodec.formatDouble(0.0001))
        assertEquals("5.0", RequestImageConfigCodec.formatDouble(5.0))
        assertEquals("0.0", RequestImageConfigCodec.formatDouble(-0.0))
    }

    @Test
    fun `missing fields use built-in defaults without merging`() {
        val style = RequestImageConfigCodec.parse("border:\n  width_px: 3\nmargin: {}\n")
        assertEquals(RequestImageStyle(border = RequestImageBorderConfig(widthPixels = 3)), style)
        assertEquals(RequestImageStyle(), RequestImageConfigCodec.parse("version: 1\n"))
        // 整数可用于百分数，-0 归一化。
        val margin = RequestImageConfigCodec.parse("margin:\n  left_percent: 8\n  right_percent: -0.0\n  top_percent: 1.5e1\n").margin
        assertEquals(8.0, margin.leftPercent)
        assertEquals(0.0, margin.rightPercent)
        assertEquals(15.0, margin.topPercent)
    }

    @Test
    fun `empty and multi document inputs are rejected`() {
        for (yaml in listOf("", "   \n", "# only comment\n", "---\n", "{}\n", "~\n", "null\n")) {
            assertTrue(fails(yaml).contains("为空"), "should reject empty root: [$yaml]")
        }
        assertTrue(fails("version: 1\n---\nversion: 1\n").contains("只允许一个"))
        assertTrue(fails("- 1\n- 2\n").contains("映射"))
        assertTrue(fails("just text\n").contains("映射"))
    }

    @Test
    fun `duplicate unknown and null fields report paths`() {
        assertFailsAt("version", "version: 1\nversion: 1\n").also { assertTrue(it.contains("重复")) }
        assertFailsAt("border.width_px", "border:\n  width_px: 1\n  width_px: 2\n")
        assertFailsAt("extra", "extra: 1\n").also { assertTrue(it.contains("未知字段")) }
        assertFailsAt("margin.left", "margin:\n  left: 1\n")
        assertFailsAt("border", "border:\n")
        assertFailsAt("border.width_px", "border:\n  width_px: null\n")
        assertFailsAt("border.color", "border:\n  color: #22C55E\n").also { assertTrue(it.contains("引号")) }
        assertFailsAt("border", "border: 1\n")
        assertFailsAt("根节点", "1: 2\n")
        assertFailsAt("根节点", "<<: {}\n")
    }

    @Test
    fun `integers and booleans are strict and never coerced`() {
        for (bad in listOf("\"1\"", "1.0", "0x1", "1_0", "01", "true", "1e1", "[1]", "{a: 1}")) {
            assertFailsAt("border.width_px", "border:\n  width_px: $bad\n")
        }
        for (bad in listOf("yes", "on", "\"true\"", "True", "TRUE", "1")) {
            assertFailsAt("border.enabled", "border:\n  enabled: $bad\n")
        }
        assertFailsAt("output.max_pixels", "output:\n  max_pixels: 99999999999999999999\n")
        assertFailsAt("border.color", "border:\n  color: 123\n")
        assertFailsAt("percentage.vertical_align", "percentage:\n  vertical_align: [top]\n")
    }

    @Test
    fun `non finite numbers are rejected`() {
        for (bad in listOf(".nan", ".NaN", ".inf", "-.inf", "+.inf", "1e400", "\"5\"", "5.", ".5", "1:30")) {
            assertFailsAt("margin.left_percent", "margin:\n  left_percent: $bad\n")
        }
    }

    @Test
    fun `ranges version and enum are validated`() {
        assertFailsAt("version", "version: 2\n")
        assertFailsAt("border.width_px", "border:\n  width_px: 17\n")
        assertFailsAt("border.width_px", "border:\n  width_px: -1\n")
        assertFailsAt("border.padding_px", "border:\n  padding_px: 129\n")
        assertFailsAt("percentage.gap_px", "percentage:\n  gap_px: 257\n")
        assertFailsAt("percentage.vertical_align", "percentage:\n  vertical_align: \"middle\"\n")
        assertFailsAt("margin.right_percent", "margin:\n  right_percent: 50.01\n")
        assertFailsAt("margin.top_percent", "margin:\n  top_percent: -1\n")
        assertFailsAt("margin.min_px", "margin:\n  min_px: 129\n")
        assertFailsAt("output.max_pixels", "output:\n  max_pixels: 0\n")
        assertFailsAt("output.max_pixels", "output:\n  max_pixels: 64000001\n")
        assertEquals("top", RequestImageConfigCodec.parse("percentage:\n  vertical_align: top\n").percentage.verticalAlign)
    }

    @Test
    fun `colors accept rgb and argb only`() {
        assertEquals(0xFFFF3B30.toInt(), parseRequestImageColor("#FF3B30"))
        assertEquals(0x8022C55E.toInt(), parseRequestImageColor("#8022C55E"))
        assertEquals(0x00000000, parseRequestImageColor("#00000000"))
        assertEquals(0xFFABCDEF.toInt(), parseRequestImageColor("#abcdef"))
        for (bad in listOf("\"red\"", "\"FF3B30\"", "\"#FFF\"", "\"#FF3B3\"", "\"#GG3B30\"", "\"#FF3B3000AA\"", "\"auto\"")) {
            assertFailsAt("border.color", "border:\n  color: $bad\n")
        }
        assertFailsAt("background.margin_color", "background:\n  margin_color: \"auto\"\n")
        assertNull(RequestImageConfigCodec.parse("background:\n  content_color: \"auto\"\n").background.contentArgbOrNull())
        assertEquals(
            0x40FFFFFF,
            RequestImageConfigCodec.parse("background:\n  content_color: \"#40FFFFFF\"\n").background.contentArgbOrNull()
        )
        assertFailsAt("background.content_color", "background:\n  content_color: \"transparent\"\n")
    }

    @Test
    fun `anchors aliases custom tags and deep nesting are rejected`() {
        assertTrue(fails("border: &b\n  width_px: 1\npercentage: *b\n").contains("锚点"))
        assertTrue(fails("version: &v 1\n").contains("锚点"))
        fails("border: !!python/object:os.system {}\n")
        assertFailsAt("border.color", "border:\n  color: !custom \"#FF3B30\"\n")
        assertFailsAt("border.width_px", "border:\n  width_px: !!int \"1\"\n")
        fails("border: !!set {a, b}\n")
        val deep = "margin:\n  left_percent: " + "[".repeat(40) + "0" + "]".repeat(40) + "\n"
        val deepError = fails(deep)
        assertTrue(deepError.contains("depth", ignoreCase = true), deepError)
    }

    @Test
    fun `size and encoding limits apply`() {
        val big = "version: 1\n" + "#" + "x".repeat(RequestImageConfigCodec.MAX_BYTES) + "\n"
        assertTrue(fails(big).contains("KiB"))
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                RequestImageConfigCodec.parseBytes(big.toByteArray(Charsets.UTF_8))
            }.message!!.contains("KiB")
        )
        val invalidUtf8 = "version: 1\n# ".toByteArray(Charsets.UTF_8) + byteArrayOf(0xC3.toByte(), 0x28)
        assertTrue(
            assertFailsWith<IllegalArgumentException> { RequestImageConfigCodec.parseBytes(invalidUtf8) }
                .message!!.contains("UTF-8")
        )
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "version: 1\n".toByteArray()
        assertEquals(RequestImageStyle(), RequestImageConfigCodec.parseBytes(withBom))
    }

    @Test
    fun `syntax errors give location without echoing file content`() {
        val message = fails("border:\n  color: \"SECRET_TOKEN_VALUE\n")
        assertFalse(message.contains("SECRET_TOKEN_VALUE"), message)
        val message2 = fails("border:\n  width_px: 1\n color: SECRET_TOKEN_VALUE\n")
        assertFalse(message2.contains("SECRET_TOKEN_VALUE"), message2)
        assertTrue(message2.contains("行"), message2)
        assertFalse(message2.contains('\n'))
    }

    @Test
    fun `margin pixels use ceiling with minimum and zero disables the side`() {
        val margin = RequestImageMarginConfig()
        assertEquals(24L, margin.leftPixels(480L))
        assertEquals(25L, margin.rightPixels(481L))
        assertEquals(1L, margin.topPixels(100L))
        assertEquals(2L, margin.bottomPixels(101L))
        assertEquals(1L, margin.topPixels(1L))
        assertEquals(0L, margin.copy(leftPercent = 0.0).leftPixels(1000L))
        assertEquals(10L, margin.copy(minPixels = 10).leftPixels(20L))
        assertEquals(0L, margin.copy(leftPercent = 0.0, minPixels = 128).leftPixels(1000L))
        // 0.1 * 30 在浮点下为 3.0000000000000004，十进制计算不应多出 1 像素。
        assertEquals(3L, margin.copy(leftPercent = 10.0).pixelsFor(30L, 10.0))
        assertEquals(7L, margin.pixelsFor(70L, 10.0))
    }

    @Test
    fun `invalid dto can be constructed and fails validate with path`() {
        val style = RequestImageStyle(border = RequestImageBorderConfig(widthPixels = 99))
        val error = assertFailsWith<IllegalArgumentException> { style.validate() }
        assertTrue(error.message!!.startsWith("border.width_px:"))
        assertFailsWith<IllegalArgumentException> {
            RequestImageStyle(margin = RequestImageMarginConfig(leftPercent = Double.NaN)).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            RequestImageStyle(margin = RequestImageMarginConfig(leftPercent = Double.POSITIVE_INFINITY)).validate()
        }
        assertFailsWith<IllegalArgumentException> { RequestImageStyle(version = 0).validate() }
    }

    @Test
    fun `field update validates strictly`() {
        val base = RequestImageStyle()
        assertEquals(8.0, RequestImageConfigCodec.updateField(base, "margin.left_percent", "8").margin.leftPercent)
        assertEquals("#22C55E", RequestImageConfigCodec.updateField(base, "border.color", "#22C55E").border.color)
        assertEquals("#22C55E", RequestImageConfigCodec.updateField(base, "border.color", "\"#22C55E\"").border.color)
        assertFalse(RequestImageConfigCodec.updateField(base, " percentage.enabled ", "false").percentage.enabled)
        assertEquals(1234L, RequestImageConfigCodec.updateField(base, "output.max_pixels", "1234").output.maxPixels)
        for ((key, value) in listOf(
            "margin.left_percent" to "NaN",
            "margin.left_percent" to "60",
            "border.width_px" to "1.5",
            "border.enabled" to "yes",
            "border.color" to "red",
            "percentage.vertical_align" to "middle",
            "unknown.field" to "1",
            "" to "1"
        )) {
            assertFailsWith<IllegalArgumentException>("$key=$value") {
                RequestImageConfigCodec.updateField(base, key, value)
            }
        }
        assertEquals(16, RequestImageConfigCodec.FIELD_PATHS.size)
    }
}
