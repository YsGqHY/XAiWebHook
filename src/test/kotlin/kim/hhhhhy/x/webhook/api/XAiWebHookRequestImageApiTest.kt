package kim.hhhhhy.x.webhook.api

import kim.hhhhhy.x.webhook.action.WebPageScreenshotAction
import kim.hhhhhy.x.webhook.config.RequestImageBorderConfig
import kim.hhhhhy.x.webhook.config.RequestImageConfigCodec
import kim.hhhhhy.x.webhook.config.RequestImageConfigRuntime
import kim.hhhhhy.x.webhook.config.RequestImageMarginConfig
import kim.hhhhhy.x.webhook.config.RequestImageStyle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

internal class XAiWebHookRequestImageApiTest {
    @TempDir
    lateinit var directory: Path

    private val errors = CopyOnWriteArrayList<String>()
    private val applied = CopyOnWriteArrayList<String>()

    private val original: ByteArray = png(100, 100, Color(10, 20, 30))
    private val percentage: ByteArray = png(10, 20, Color(200, 100, 50))

    @BeforeEach
    fun isolate() {
        RequestImageConfigRuntime.resetForTest()
    }

    /** 恢复全局运行时，避免后续测试（如截图动作）读到本测试的非默认样式。 */
    @AfterEach
    fun cleanup() {
        RequestImageConfigRuntime.resetForTest()
    }

    private fun startRuntime(dir: File = directory.toFile(), pollMillis: Long = 600_000L) {
        RequestImageConfigRuntime.start(dir, { errors += it }, { applied += it }, pollMillis, 2)
    }

    private fun decoratedSize(style: RequestImageStyle = XAiWebHookRequestImageApi.read()): Pair<Int, Int> {
        val bytes = WebPageScreenshotAction.decorateModelPlazaScreenshot(original, percentage, style)
        val image = ByteArrayInputStream(bytes).use { requireNotNull(ImageIO.read(it)) }
        return image.width to image.height
    }

    private fun waitUntil(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("condition not met; errors=$errors applied=$applied")
    }

    @Test
    fun `before start reads defaults and writes fail without creating files`() = runBlocking {
        assertEquals(RequestImageStyle(), XAiWebHookRequestImageApi.read())
        assertEquals(RequestImageConfigCodec.render(RequestImageStyle()), XAiWebHookRequestImageApi.readYaml())
        val status = XAiWebHookRequestImageApi.status()
        assertNull(status.filePath)
        assertEquals(0L, status.revision)
        assertFalse(status.watching)
        for (result in listOf(
            XAiWebHookRequestImageApi.write(RequestImageStyle()),
            XAiWebHookRequestImageApi.writeYaml("version: 1\n"),
            XAiWebHookRequestImageApi.update("border.width_px", "2"),
            XAiWebHookRequestImageApi.reload()
        )) {
            assertFalse(result.success)
            assertTrue(result.error!!.contains("未运行"))
            assertEquals(RequestImageStyle(), result.style)
        }
        assertTrue(directory.toFile().list().orEmpty().isEmpty())
        assertFalse(XAiWebHookRequestImageApi.status().watching)
    }

    @Test
    fun `valid write changes later decorated image size and invalid write keeps it`() = runBlocking {
        startRuntime()
        val status = XAiWebHookRequestImageApi.status()
        assertTrue(status.watching)
        assertEquals(File(directory.toFile(), "request_image.yml").path, status.filePath)
        assertNotNull(status.lastLoadedAtMillis)
        // 默认：边框 1 + 内边距 2，间距 6，左右 5%、上下 1%。
        assertEquals(136 to 110, decoratedSize())

        val style = RequestImageStyle(
            border = RequestImageBorderConfig(widthPixels = 4, paddingPixels = 0),
            margin = RequestImageMarginConfig(leftPercent = 20.0, rightPercent = 0.0, topPercent = 0.0, bottomPercent = 0.0)
        )
        val written = XAiWebHookRequestImageApi.write(style)
        assertTrue(written.success, written.error)
        assertEquals(style, written.style)
        assertEquals(style, XAiWebHookRequestImageApi.read())
        assertEquals(written.revision, XAiWebHookRequestImageApi.status().revision)
        // 内容 10 + 6 + (100 + 8) = 124，左侧 ceil(24.8) = 25，其他侧关闭。
        assertEquals(149 to 108, decoratedSize())

        val invalid = XAiWebHookRequestImageApi.write(style.copy(border = RequestImageBorderConfig(widthPixels = 99)))
        assertFalse(invalid.success)
        assertTrue(invalid.error!!.contains("border.width_px"))
        assertEquals(style, invalid.style)
        assertEquals(written.revision, invalid.revision)
        assertEquals(style, XAiWebHookRequestImageApi.read())
        assertEquals(149 to 108, decoratedSize())
        assertEquals(invalid.error, XAiWebHookRequestImageApi.status().lastError)

        val updated = XAiWebHookRequestImageApi.update("margin.right_percent", "10")
        assertTrue(updated.success, updated.error)
        assertEquals(written.revision + 1, updated.revision)
        assertNull(XAiWebHookRequestImageApi.status().lastError)
        // 右侧 ceil(12.4) = 13。
        assertEquals(162 to 108, decoratedSize())
        assertEquals(updated.style, RequestImageConfigCodec.parse(XAiWebHookRequestImageApi.readYaml()))
        assertFalse(XAiWebHookRequestImageApi.update("margin.unknown", "1").success)
        assertEquals(162 to 108, decoratedSize())
    }

    @Test
    fun `writeYaml and reload share the strict pipeline`() = runBlocking {
        startRuntime()
        val ok = XAiWebHookRequestImageApi.writeYaml("margin:\n  left_percent: 0\n  right_percent: 0\n")
        assertTrue(ok.success, ok.error)
        assertEquals(0.0, XAiWebHookRequestImageApi.read().margin.leftPercent)
        val bad = XAiWebHookRequestImageApi.writeYaml("margin:\n  left_percent: .nan\n")
        assertFalse(bad.success)
        assertEquals(0.0, XAiWebHookRequestImageApi.read().margin.leftPercent)

        val file = File(directory.toFile(), "request_image.yml")
        Files.write(file.toPath(), "border:\n  enabled: false\n".toByteArray(Charsets.UTF_8))
        val reloaded = XAiWebHookRequestImageApi.reload()
        assertTrue(reloaded.success, reloaded.error)
        assertFalse(XAiWebHookRequestImageApi.read().border.enabled)
        Files.write(file.toPath(), "border:\n  enabled: maybe\n".toByteArray(Charsets.UTF_8))
        val rejected = XAiWebHookRequestImageApi.reload()
        assertFalse(rejected.success)
        assertTrue(rejected.error!!.contains("border.enabled"))
        assertFalse(XAiWebHookRequestImageApi.read().border.enabled)
        assertEquals(reloaded.revision, XAiWebHookRequestImageApi.status().revision)
    }

    @Test
    fun `file edits are applied automatically through the runtime`() {
        startRuntime(pollMillis = 20L)
        val file = File(directory.toFile(), "request_image.yml")
        Files.write(file.toPath(), "margin:\n  left_percent: 20\n".toByteArray(Charsets.UTF_8))
        waitUntil { XAiWebHookRequestImageApi.read().margin.leftPercent == 20.0 }
        assertTrue(applied.any { it.contains("文件变更") })
    }

    @Test
    fun `close stops watching keeps last snapshot and restart loads the new directory`() = runBlocking {
        startRuntime()
        val written = XAiWebHookRequestImageApi.update("border.width_px", "5")
        assertTrue(written.success, written.error)
        RequestImageConfigRuntime.close()
        val status = XAiWebHookRequestImageApi.status()
        assertFalse(status.watching)
        assertEquals(written.revision, status.revision)
        assertEquals(5, XAiWebHookRequestImageApi.read().border.widthPixels)
        val refused = XAiWebHookRequestImageApi.write(RequestImageStyle())
        assertFalse(refused.success)
        assertEquals(5, refused.style.border.widthPixels)
        RequestImageConfigRuntime.close()

        // 重新启动到隔离目录：加载该目录的默认文件，修订号单调递增。
        val other = directory.resolve("other").toFile()
        startRuntime(other)
        assertEquals(RequestImageStyle(), XAiWebHookRequestImageApi.read())
        assertTrue(XAiWebHookRequestImageApi.status().revision > written.revision)
        assertTrue(XAiWebHookRequestImageApi.status().watching)
        assertEquals(File(other, "request_image.yml").path, XAiWebHookRequestImageApi.status().filePath)
        // 再次 start 会关闭旧实例，不产生重复监控。
        startRuntime(other)
        assertTrue(XAiWebHookRequestImageApi.status().watching)
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `readYaml returns the effective style when the disk file is invalid`() = runBlocking {
        startRuntime()
        val written = XAiWebHookRequestImageApi.update("border.width_px", "5")
        assertTrue(written.success)
        val file = directory.resolve("request_image.yml")
        val invalid = "border:\n  color: not-a-color\n"
        Files.write(file, invalid.toByteArray(Charsets.UTF_8))
        assertFalse(XAiWebHookRequestImageApi.reload().success)
        assertEquals(written.style, RequestImageConfigCodec.parse(XAiWebHookRequestImageApi.readYaml()))
        assertEquals(invalid, Files.readString(file))
    }

    @Test
    fun `restarting with an invalid file preserves the effective style and its revision`() = runBlocking {
        startRuntime()
        val written = XAiWebHookRequestImageApi.update("border.width_px", "5")
        assertTrue(written.success)
        val successfulTime = XAiWebHookRequestImageApi.status().lastLoadedAtMillis
        Files.write(directory.resolve("request_image.yml"), "border:\n  width_px: 99\n".toByteArray(Charsets.UTF_8))
        RequestImageConfigRuntime.close()
        startRuntime()
        assertEquals(written.style, XAiWebHookRequestImageApi.read())
        assertEquals(written.revision, XAiWebHookRequestImageApi.status().revision)
        assertEquals(successfulTime, XAiWebHookRequestImageApi.status().lastLoadedAtMillis)
        assertNotNull(XAiWebHookRequestImageApi.status().lastError)
        assertTrue(errors.last().contains("继续使用修订"))
    }

    @Test
    fun `restart should not publish a default snapshot before the new store finishes loading`() = runBlocking {
        startRuntime()
        val written = XAiWebHookRequestImageApi.update("border.width_px", "5")
        assertTrue(written.success)
        val nextDirectory = directory.resolve("next")
        Files.createDirectories(nextDirectory)
        Files.write(nextDirectory.resolve("request_image.yml"), "border:\n  width_px: 3\n".toByteArray(Charsets.UTF_8))
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val restarting = pool.submit(java.util.concurrent.Callable {
                RequestImageConfigRuntime.start(nextDirectory.toFile(), {}, {
                    entered.countDown()
                    check(proceed.await(5, TimeUnit.SECONDS))
                }, 600_000L, 2)
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(100) { assertEquals(written.style, XAiWebHookRequestImageApi.read()) }
            proceed.countDown()
            restarting.get(5, TimeUnit.SECONDS)
            assertEquals(3, XAiWebHookRequestImageApi.read().border.widthPixels)
            assertEquals(written.revision + 1, XAiWebHookRequestImageApi.status().revision)
        } finally {
            proceed.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `fieldPaths exposes a defensive copy`() {
        val exposed = XAiWebHookRequestImageApi.fieldPaths as MutableList<String>
        exposed.clear()
        assertEquals(16, XAiWebHookRequestImageApi.fieldPaths.size)
        assertTrue("margin.left_percent" in XAiWebHookRequestImageApi.fieldPaths)
    }

    private fun png(width: Int, height: Int, color: Color): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }
}
