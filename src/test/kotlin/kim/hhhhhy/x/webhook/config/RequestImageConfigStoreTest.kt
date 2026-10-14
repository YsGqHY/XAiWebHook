package kim.hhhhhy.x.webhook.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

internal class RequestImageConfigStoreTest {
    @TempDir
    lateinit var directory: Path

    private val errors = CopyOnWriteArrayList<String>()
    private val applied = CopyOnWriteArrayList<String>()
    private val stores = ArrayList<RequestImageConfigStore>()

    private val file: File
        get() = directory.resolve(RequestImageConfigStore.FILE_NAME).toFile()

    @AfterEach
    fun closeStores() {
        stores.forEach { it.close() }
    }

    /** fast=true 使用 20ms 检查周期；否则周期极长，便于确定性地测试接口写入。 */
    private fun store(
        fast: Boolean = true,
        atomicReplace: ((Path, Path) -> Unit)? = null
    ): RequestImageConfigStore {
        val created = if (atomicReplace == null) {
            RequestImageConfigStore(file, { errors += it }, { applied += it }, if (fast) 20L else 600_000L, 2)
        } else {
            RequestImageConfigStore(
                file, { errors += it }, { applied += it }, if (fast) 20L else 600_000L, 2,
                atomicReplace = atomicReplace
            )
        }
        stores += created
        return created
    }

    @Test
    fun `directory creation failure keeps defaults and records an actionable error`() {
        val blockingFile = directory.resolve("blocked")
        Files.write(blockingFile, byteArrayOf(1))
        val created = RequestImageConfigStore(
            blockingFile.resolve("request_image.yml").toFile(), { errors += it }, { applied += it }, 600_000L, 2
        )
        stores += created
        created.start()
        assertEquals(RequestImageStyle(), created.snapshot().style)
        assertEquals(0L, created.snapshot().revision)
        assertTrue(created.snapshot().lastError!!.contains("无法创建配置目录"))
        assertTrue(created.status().watching)
        assertEquals(1, errors.size)
    }

    @Test
    fun `default file generation failure retains defaults and can recover after file restoration`() {
        val created = store(fast = false, atomicReplace = { _, _ -> throw IOException("test write failure") })
        created.start()
        assertFalse(file.exists())
        assertEquals(RequestImageStyle(), created.snapshot().style)
        assertEquals(0L, created.snapshot().revision)
        assertTrue(created.snapshot().lastError!!.contains("生成默认"))
        assertTrue(leftovers().isEmpty())
        externalWrite(widthYaml(3))
        val loaded = created.reload()
        assertTrue(loaded.success, loaded.error)
        assertEquals(3, loaded.style.border.widthPixels)
    }

    private fun waitUntil(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("condition not met within $timeoutMillis ms; errors=$errors applied=$applied")
    }

    /** 外部编辑器写入；Windows 上偶发共享冲突时重试。 */
    private fun externalWrite(text: String) {
        repeat(20) {
            try {
                Files.write(file.toPath(), text.toByteArray(Charsets.UTF_8))
                return
            } catch (e: IOException) {
                Thread.sleep(20)
            }
        }
        fail("external write failed")
    }

    private fun widthYaml(width: Int): String = "border:\n  width_px: $width\n"

    private fun leftovers(): List<String> =
        directory.toFile().list().orEmpty().filter { it != RequestImageConfigStore.FILE_NAME }

    @Test
    fun `start generates default file and applies it synchronously`() {
        val nested = directory.resolve("nested/config").resolve(RequestImageConfigStore.FILE_NAME).toFile()
        val created = RequestImageConfigStore(nested, { errors += it }, { applied += it }, 600_000L, 2)
        stores += created
        created.start()
        assertTrue(nested.isFile)
        assertContentEquals(RequestImageConfigStore.defaultResourceBytes(), nested.readBytes())
        val state = created.snapshot()
        assertEquals(RequestImageStyle(), state.style)
        assertEquals(1L, state.revision)
        assertNull(state.lastError)
        assertNotNull(state.lastLoadedAtMillis)
        assertTrue(created.status().watching)
        assertTrue(applied.any { it.contains("已生成") })
        assertTrue(errors.isEmpty())
        created.start()
        assertEquals(1L, created.snapshot().revision)
    }

    @Test
    fun `invalid startup file falls back to defaults without overwriting it`() {
        externalWrite(widthYaml(99))
        val created = store(fast = false)
        created.start()
        val state = created.snapshot()
        assertEquals(RequestImageStyle(), state.style)
        assertEquals(0L, state.revision)
        assertTrue(state.lastError!!.contains("border.width_px"))
        assertEquals(widthYaml(99), file.readText())
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("内置默认"))
    }

    @Test
    fun `watch applies content changes including same size and same mtime`() {
        val created = store()
        created.start()
        externalWrite(widthYaml(3))
        waitUntil { created.snapshot().style.border.widthPixels == 3 }
        assertEquals(2L, created.snapshot().revision)
        val mtime = file.lastModified()
        externalWrite(widthYaml(4))
        file.setLastModified(mtime)
        waitUntil { created.snapshot().style.border.widthPixels == 4 }
        assertEquals(3L, created.snapshot().revision)
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `watch picks up editor style atomic replacement`() {
        val created = store()
        created.start()
        val temp = directory.resolve("editor-save.tmp")
        Files.write(temp, "margin:\n  left_percent: 8.5\n".toByteArray(Charsets.UTF_8))
        Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        waitUntil { created.snapshot().style.margin.leftPercent == 8.5 }
    }

    @Test
    fun `invalid change keeps last valid style reports once and recovers after repair`() {
        val created = store()
        created.start()
        externalWrite(widthYaml(5))
        waitUntil { created.snapshot().style.border.widthPixels == 5 }
        val validRevision = created.snapshot().revision
        externalWrite("border:\n  width_px: 5\n  colour: \"#000000\"\n")
        waitUntil { errors.size == 1 }
        Thread.sleep(300)
        assertEquals(1, errors.size, "same invalid content must be reported once: $errors")
        assertTrue(errors.single().contains("border.colour"))
        assertTrue(errors.single().contains("修订 $validRevision"))
        assertEquals(validRevision, created.snapshot().revision)
        assertEquals(5, created.snapshot().style.border.widthPixels)
        assertNotNull(created.snapshot().lastError)
        externalWrite(widthYaml(6))
        waitUntil { created.snapshot().style.border.widthPixels == 6 }
        assertNull(created.snapshot().lastError)
        assertEquals(validRevision + 1, created.snapshot().revision)
    }

    @Test
    fun `deleted file keeps style is not recreated and restores`() {
        val created = store()
        created.start()
        externalWrite(widthYaml(7))
        waitUntil { created.snapshot().style.border.widthPixels == 7 }
        Files.delete(file.toPath())
        waitUntil { errors.any { it.contains("不存在") } }
        Thread.sleep(200)
        assertFalse(file.exists())
        assertEquals(1, errors.size)
        assertEquals(7, created.snapshot().style.border.widthPixels)
        externalWrite(widthYaml(7))
        waitUntil { created.snapshot().lastError == null }
        assertEquals(7, created.snapshot().style.border.widthPixels)
    }

    @Test
    fun `oversized file at runtime is rejected and keeps style`() {
        val created = store()
        created.start()
        externalWrite("version: 1\n#" + "x".repeat(RequestImageConfigCodec.MAX_BYTES + 10) + "\n")
        waitUntil { errors.isNotEmpty() }
        assertTrue(errors.single().contains("KiB"))
        assertEquals(RequestImageStyle(), created.snapshot().style)
    }

    @Test
    fun `closed store stops watching and refuses writes`() {
        val created = store()
        created.start()
        val revision = created.snapshot().revision
        val appliedCount = applied.size
        created.close()
        assertFalse(created.status().watching)
        externalWrite(widthYaml(9))
        Thread.sleep(300)
        assertEquals(revision, created.snapshot().revision)
        assertEquals(appliedCount, applied.size)
        val result = created.write(RequestImageStyle(border = RequestImageBorderConfig(widthPixels = 2)))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("已停止"))
        assertFalse(created.reload().success)
        assertFalse(created.update("border.width_px", "2").success)
        assertEquals(widthYaml(9), file.readText())
    }

    @Test
    fun `not started store refuses writes and does not create the file`() {
        val created = store()
        val result = created.write(RequestImageStyle())
        assertFalse(result.success)
        assertTrue(result.error!!.contains("尚未启动"))
        assertFalse(file.exists())
        assertFalse(created.status().watching)
    }

    @Test
    fun `write round trips to disk and watcher does not reapply own write`() {
        val created = store()
        created.start()
        val style = RequestImageStyle(
            border = RequestImageBorderConfig(color = "#22C55E"),
            margin = RequestImageMarginConfig(leftPercent = 8.0, topPercent = 0.0)
        )
        val result = created.write(style)
        assertTrue(result.success, result.error)
        assertEquals(style, result.style)
        assertEquals(2L, result.revision)
        assertEquals(style, RequestImageConfigCodec.parse(file.readText()))
        assertEquals(RequestImageConfigCodec.render(style), file.readText())
        Thread.sleep(300)
        assertEquals(2L, created.snapshot().revision, "watcher must not re-apply the API write")
        assertTrue(leftovers().isEmpty(), "temp files left: ${leftovers()}")
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `invalid write keeps disk and memory unchanged`() {
        val created = store(fast = false)
        created.start()
        val before = file.readBytes()
        val result = created.write(RequestImageStyle(border = RequestImageBorderConfig(widthPixels = 99)))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("border.width_px"))
        assertEquals(RequestImageStyle(), result.style)
        assertEquals(1L, result.revision)
        assertContentEquals(before, file.readBytes())
        assertEquals(RequestImageStyle(), created.snapshot().style)
        assertEquals(1L, created.snapshot().revision)
        assertEquals(result.error, created.snapshot().lastError)

        val yamlResult = created.writeYaml("border:\n  width_px: 2\n  width_px: 3\n")
        assertFalse(yamlResult.success)
        assertTrue(yamlResult.error!!.contains("重复"))
        assertContentEquals(before, file.readBytes())

        val fieldResult = created.update("margin.left_percent", "NaN")
        assertFalse(fieldResult.success)
        assertContentEquals(before, file.readBytes())
        assertFalse(created.update("no.such", "1").success)
        assertEquals(1L, created.snapshot().revision)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `unsupported atomic move fails without non atomic fallback`() {
        externalWrite(RequestImageConfigCodec.render(RequestImageStyle()))
        val created = store(fast = false, atomicReplace = { _, _ -> throw AtomicMoveNotSupportedException("a", "b", "test") })
        created.start()
        val before = file.readBytes()
        val result = created.write(RequestImageStyle(border = RequestImageBorderConfig(widthPixels = 4)))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("原子替换"), result.error)
        assertContentEquals(before, file.readBytes())
        assertEquals(RequestImageStyle(), created.snapshot().style)
        assertEquals(1L, created.snapshot().revision)
        assertTrue(leftovers().isEmpty(), "temp files left: ${leftovers()}")
        assertTrue(errors.any { it.contains("原子替换") })
    }

    @Test
    fun `writeYaml keeps user text and update merges external disk edits`() {
        val created = store(fast = false)
        created.start()
        val yaml = "# 自定义注释\nborder:\n  width_px: 3\n"
        val result = created.writeYaml(yaml)
        assertTrue(result.success, result.error)
        assertEquals(yaml, file.readText())
        // 外部修改尚未被监控处理时，字段更新以磁盘上的有效内容为基准。
        externalWrite("border:\n  width_px: 5\n")
        val updated = created.update("margin.right_percent", "9")
        assertTrue(updated.success, updated.error)
        assertEquals(5, updated.style.border.widthPixels)
        assertEquals(9.0, updated.style.margin.rightPercent)
        assertEquals(updated.style, RequestImageConfigCodec.parse(file.readText()))
    }

    @Test
    fun `update on invalid disk content refuses to overwrite edits awaiting repair`() {
        val created = store(fast = false)
        created.start()
        assertTrue(created.update("border.width_px", "4").success)
        val previous = created.snapshot()
        val editing = "# 用户待修复的注释\nmargin:\n  left_percent: 9\nborder:\n  width_px: 99\n"
        externalWrite(editing)
        val result = created.update("margin.right_percent", "7")
        assertFalse(result.success)
        assertTrue(result.error!!.contains("请先修复"), result.error)
        assertEquals(previous.style, result.style)
        assertEquals(previous.revision, result.revision)
        assertEquals(editing, file.readText())
        // 显式整体替换可修复文件，并保留调用方提供的原文注释及其它字段。
        val repaired = editing.replace("width_px: 99", "width_px: 6")
        assertTrue(created.writeYaml(repaired).success)
        assertEquals(repaired, file.readText())
        assertEquals(9.0, created.snapshot().style.margin.leftPercent)
    }

    @Test
    fun `reload applies valid disk content and keeps style on invalid content`() {
        val created = store(fast = false)
        created.start()
        externalWrite(widthYaml(8))
        val ok = created.reload()
        assertTrue(ok.success, ok.error)
        assertEquals(8, ok.style.border.widthPixels)
        assertEquals(2L, ok.revision)
        externalWrite(widthYaml(100))
        val bad = created.reload()
        assertFalse(bad.success)
        assertTrue(bad.error!!.contains("border.width_px"))
        assertEquals(8, bad.style.border.widthPixels)
        assertEquals(2L, bad.revision)
        Files.delete(file.toPath())
        val missing = created.reload()
        assertFalse(missing.success)
        assertTrue(missing.error!!.contains("不存在"))
        assertFalse(file.exists())
    }

    @Test
    fun `concurrent field updates do not lose writes`() {
        val created = store()
        created.start()
        val updates = listOf(
            "border.width_px" to "3",
            "border.padding_px" to "4",
            "border.color" to "#123456",
            "border.enabled" to "false",
            "percentage.gap_px" to "9",
            "percentage.vertical_align" to "top",
            "percentage.enabled" to "false",
            "margin.left_percent" to "6",
            "margin.right_percent" to "7",
            "margin.top_percent" to "2",
            "margin.bottom_percent" to "3",
            "margin.min_px" to "5",
            "background.margin_color" to "#000000",
            "background.content_color" to "#80FFFFFF",
            "output.max_pixels" to "1000000"
        )
        val pool = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Boolean>()
        try {
            updates.forEach { (key, value) ->
                pool.execute {
                    gate.await()
                    results += created.update(key, value).success
                }
            }
            gate.countDown()
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertEquals(List(updates.size) { true }, results.toList())
        val expected = RequestImageStyle(
            border = RequestImageBorderConfig(false, 3, 4, "#123456"),
            percentage = RequestImagePercentageConfig(false, 9, "top"),
            margin = RequestImageMarginConfig(6.0, 7.0, 2.0, 3.0, 5),
            background = RequestImageBackgroundConfig("#000000", "#80FFFFFF"),
            output = RequestImageOutputConfig(1_000_000L)
        )
        assertEquals(expected, created.snapshot().style)
        assertEquals(1L + updates.size, created.snapshot().revision)
        assertEquals(expected, RequestImageConfigCodec.parse(file.readText()))
        Thread.sleep(200)
        assertEquals(1L + updates.size, created.snapshot().revision)
    }
}
