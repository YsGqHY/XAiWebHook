package kim.hhhhhy.x.webhook.config

import kim.hhhhhy.x.webhook.api.RequestImageConfigStatus
import kim.hhhhhy.x.webhook.api.RequestImageConfigUpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 已发布的完整不可变状态；图片合成只读取 [style]，无需持有文件锁。 */
internal data class RequestImageConfigState(
    val style: RequestImageStyle,
    val revision: Long,
    val lastLoadedAtMillis: Long?,
    val lastError: String?
)

/** 磁盘快照；[fingerprint] 按内容计算，用于稳定检测与错误去重。 */
internal sealed class RequestImageDiskSnapshot {
    abstract val fingerprint: String

    object Missing : RequestImageDiskSnapshot() {
        override val fingerprint: String = "missing"
    }

    data class Failed(val reason: String) : RequestImageDiskSnapshot() {
        override val fingerprint: String = "failed:$reason"
    }

    class Content(val bytes: ByteArray, val oversized: Boolean, override val fingerprint: String) :
        RequestImageDiskSnapshot()
}

/**
 * request_image.yml 的串行存储与文件监控。
 *
 * - 首次加载、监控处理、接口写入、字段更新与重载共用 [lock]，有效状态通过 [state] 原子发布。
 * - 监控按内容指纹连续 [stableReads] 次一致才处理；已处理的同一指纹（成功或失败）不重复通知。
 * - 写入先完整校验，再写同目录临时文件、force 并 ATOMIC_MOVE 替换，成功后才发布内存状态。
 * - 回调在锁外调用；关闭后不再发布状态或回调。
 */
internal class RequestImageConfigStore(
    internal val file: File,
    private val onError: (String) -> Unit,
    private val onApplied: (String) -> Unit,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val stableReads: Int = DEFAULT_STABLE_READS,
    initialRevision: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val atomicReplace: (Path, Path) -> Unit = ::defaultAtomicReplace,
    initialState: RequestImageConfigState? = null
) {
    private val lock = ReentrantLock()
    private val state = AtomicReference(
        initialState ?: RequestImageConfigState(RequestImageStyle(), initialRevision, null, null)
    )
    private val startupFallback = if (initialState == null) "使用内置默认样式" else {
        "继续使用修订 ${initialState.revision} 的有效样式"
    }

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var started: Boolean = false

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var watchJob: Job? = null

    /** 最近一次已处理（应用或报告错误）的磁盘内容指纹；仅在 [lock] 内访问。 */
    private var lastProcessedFingerprint: String? = null

    init {
        require(pollIntervalMillis > 0L) { "pollIntervalMillis must be positive" }
        require(stableReads >= 1) { "stableReads must be at least 1" }
        initialState?.style?.validate()
    }

    internal fun snapshot(): RequestImageConfigState = state.get()

    internal val isRunning: Boolean
        get() = started && !closed

    internal val isWatching: Boolean
        get() = isRunning && watchJob?.isActive == true

    internal fun status(): RequestImageConfigStatus {
        val current = state.get()
        return RequestImageConfigStatus(
            filePath = file.path,
            revision = current.revision,
            watching = isWatching,
            lastLoadedAtMillis = current.lastLoadedAtMillis,
            lastError = current.lastError
        )
    }

    /** 同步完成首次加载并启动监控；重复调用无副作用，关闭后不可重启（运行时会新建实例）。 */
    internal fun start(): Unit {
        locked { notes ->
            check(!closed) { "request image config store is closed" }
            if (started) return@locked
            initialLoad(notes)
            started = true
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = newScope
            watchJob = newScope.launch { watchLoop() }
        }
    }

    /** 取消监控；等待正在处理的锁内操作结束后返回，此后不再发布。 */
    internal fun close(): Unit {
        val toCancel = lock.withLock {
            closed = true
            val current = scope
            scope = null
            watchJob = null
            current
        }
        toCancel?.cancel()
    }

    /** 校验并原子持久化完整样式，成功后立即应用。 */
    internal fun write(style: RequestImageStyle): RequestImageConfigUpdateResult = locked { notes ->
        notRunningResult()?.let { return@locked it }
        try {
            style.validate()
        } catch (e: IllegalArgumentException) {
            return@locked reject("配置无效：${e.message}")
        }
        persist(RequestImageConfigCodec.render(style).toByteArray(Charsets.UTF_8), style, "接口写入", notes)
    }

    /** YAML 文本走与文件相同的解析/校验管线；保存原文以保留用户注释。 */
    internal fun writeYaml(yaml: String): RequestImageConfigUpdateResult = locked { notes ->
        notRunningResult()?.let { return@locked it }
        val bytes = yaml.toByteArray(Charsets.UTF_8)
        val style = try {
            RequestImageConfigCodec.parseBytes(bytes)
        } catch (e: IllegalArgumentException) {
            return@locked reject("配置无效：${e.message}")
        }
        persist(bytes, style, "接口写入 YAML", notes)
    }

    /**
     * 锁内读改写单个字段。优先保留磁盘上的有效外部修改；磁盘缺失时使用当前有效样式。
     * 磁盘存在但无效或不可读取时拒绝更新，保留用户尚待修复的内容和注释。
     */
    internal fun update(key: String, value: String): RequestImageConfigUpdateResult = locked { notes ->
        notRunningResult()?.let { return@locked it }
        val updated = try {
            val base = currentDiskStyleForUpdate()
            RequestImageConfigCodec.updateField(base, key, value)
        } catch (e: IllegalArgumentException) {
            return@locked reject("配置无效：${e.message}")
        }
        persist(RequestImageConfigCodec.render(updated).toByteArray(Charsets.UTF_8), updated, "字段更新 ${key.trim()}", notes)
    }

    /** 立即读取磁盘并安全应用；缺失、读失败或无效时保留当前有效样式。 */
    internal fun reload(): RequestImageConfigUpdateResult = locked { notes ->
        notRunningResult()?.let { return@locked it }
        val ok = applySnapshot(readSnapshot(), "手动重载", notes, startup = false)
        result(ok)
    }

    private fun initialLoad(notes: MutableList<Note>): Unit {
        try {
            Files.createDirectories(file.toPath().toAbsolutePath().parent)
        } catch (e: Exception) {
            recordError("无法创建配置目录：${describe(e)}", notes)
            lastProcessedFingerprint = null
            return
        }
        var snapshot = readSnapshot()
        if (snapshot is RequestImageDiskSnapshot.Missing) {
            try {
                atomicWrite(defaultResourceBytes())
                notes += Note(false, "已生成默认 ${file.name}：${file.path}")
            } catch (e: Exception) {
                recordError("生成默认 ${file.name} 失败，$startupFallback：${describe(e)}", notes)
                lastProcessedFingerprint = snapshot.fingerprint
                return
            }
            snapshot = readSnapshot()
        }
        applySnapshot(snapshot, "启动加载", notes, startup = true)
    }

    /**
     * 处理一个磁盘快照并记录其指纹。Content 有效则整体发布；其他情况只更新错误并保留当前样式。
     * 返回是否成功应用。
     */
    private fun applySnapshot(
        snapshot: RequestImageDiskSnapshot,
        source: String,
        notes: MutableList<Note>,
        startup: Boolean
    ): Boolean {
        lastProcessedFingerprint = snapshot.fingerprint
        val keep = if (startup) startupFallback else "继续使用修订 ${state.get().revision} 的有效样式"
        return when (snapshot) {
            is RequestImageDiskSnapshot.Content -> {
                val style = try {
                    if (snapshot.oversized) {
                        throw IllegalArgumentException("配置内容超过 ${RequestImageConfigCodec.MAX_BYTES / 1024} KiB 上限")
                    }
                    RequestImageConfigCodec.parseBytes(snapshot.bytes)
                } catch (e: IllegalArgumentException) {
                    recordError("${file.name} 无效（$source），$keep：${e.message}", notes)
                    return false
                }
                val published = publish(style)
                notes += Note(false, "${file.name} 已应用（$source），修订 ${published.revision}")
                true
            }
            is RequestImageDiskSnapshot.Missing -> {
                recordError("${file.name} 不存在（$source），$keep；恢复文件后会自动加载", notes)
                false
            }
            is RequestImageDiskSnapshot.Failed -> {
                recordError("读取 ${file.name} 失败（$source），$keep：${snapshot.reason}", notes)
                false
            }
        }
    }

    private suspend fun watchLoop(): Unit {
        var candidate: String? = null
        var matches = 0
        var handled = false
        var lastLoopError: String? = null
        while (currentCoroutineContext().isActive) {
            delay(pollIntervalMillis)
            try {
                // 与写入共用锁读取，避免在 Windows 上读句柄与原子替换互相冲突。
                val fingerprint = lock.withLock {
                    if (closed) return
                    readSnapshot().fingerprint
                }
                if (fingerprint == candidate) {
                    if (matches < stableReads) matches++
                } else {
                    candidate = fingerprint
                    matches = 1
                    handled = false
                }
                if (matches >= stableReads && !handled) {
                    handled = processStable(fingerprint)
                }
                lastLoopError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "监控 ${file.name} 时发生异常：${describe(e)}"
                if (message != lastLoopError) {
                    lastLoopError = message
                    safeCall(onError, message)
                }
            }
        }
    }

    /** 返回 true 表示该稳定指纹已处理完毕（含“与上次已处理一致”）；false 表示文件在稳定后又变化，需重新等待。 */
    private fun processStable(stableFingerprint: String): Boolean = locked { notes ->
        if (closed) return@locked true
        if (stableFingerprint == lastProcessedFingerprint) return@locked true
        // 提交前确认磁盘仍为稳定候选，防止陈旧快照覆盖更新的接口写入。
        val snapshot = readSnapshot()
        if (snapshot.fingerprint != stableFingerprint) return@locked false
        applySnapshot(snapshot, "文件变更", notes, startup = false)
        true
    }

    private fun persist(
        bytes: ByteArray,
        style: RequestImageStyle,
        source: String,
        notes: MutableList<Note>
    ): RequestImageConfigUpdateResult {
        if (bytes.size > RequestImageConfigCodec.MAX_BYTES) {
            return reject("配置内容超过 ${RequestImageConfigCodec.MAX_BYTES / 1024} KiB 上限")
        }
        try {
            atomicWrite(bytes)
        } catch (e: Exception) {
            val message = "写入 ${file.name} 失败，磁盘文件与有效样式均未改变：${describe(e)}"
            recordError(message, notes)
            return failureResult(message)
        }
        // 写入内容即为已处理内容，监控不会再次应用同一份文件。
        lastProcessedFingerprint = contentFingerprint(bytes, oversized = false)
        val published = publish(style)
        notes += Note(false, "${file.name} 已应用（$source），修订 ${published.revision}")
        return RequestImageConfigUpdateResult(true, published.style, published.revision, null)
    }

    private fun currentDiskStyleForUpdate(): RequestImageStyle = when (val snapshot = readSnapshot()) {
        is RequestImageDiskSnapshot.Missing -> state.get().style
        is RequestImageDiskSnapshot.Failed -> throw IllegalArgumentException(
            "无法读取 ${file.name}，未执行单字段更新；请先恢复文件：${snapshot.reason}"
        )
        is RequestImageDiskSnapshot.Content -> {
            try {
                if (snapshot.oversized) throw IllegalArgumentException(
                    "配置内容超过 ${RequestImageConfigCodec.MAX_BYTES / 1024} KiB 上限"
                )
                RequestImageConfigCodec.parseBytes(snapshot.bytes)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "磁盘配置无效，未执行单字段更新；请先修复 ${file.name}，或用 write/writeYaml 明确整体替换：${error.message}"
                )
            }
        }
    }

    private fun notRunningResult(): RequestImageConfigUpdateResult? = when {
        closed -> failureResult("请求图片配置管理器已停止，未执行写入或重载")
        !started -> failureResult("请求图片配置管理器尚未启动，未执行写入或重载")
        else -> null
    }

    /** 拒绝候选：只记录最近错误，保留有效样式与修订号。 */
    private fun reject(message: String): RequestImageConfigUpdateResult {
        state.updateAndGet { it.copy(lastError = message) }
        return failureResult(message)
    }

    private fun recordError(message: String, notes: MutableList<Note>): Unit {
        state.updateAndGet { it.copy(lastError = message) }
        notes += Note(true, message)
    }

    private fun publish(style: RequestImageStyle): RequestImageConfigState =
        state.updateAndGet { RequestImageConfigState(style, it.revision + 1, clock(), null) }

    private fun failureResult(message: String): RequestImageConfigUpdateResult {
        val current = state.get()
        return RequestImageConfigUpdateResult(false, current.style, current.revision, message)
    }

    private fun result(success: Boolean): RequestImageConfigUpdateResult {
        val current = state.get()
        return RequestImageConfigUpdateResult(success, current.style, current.revision, if (success) null else current.lastError)
    }

    /** 在锁内执行并收集通知；锁外按顺序回调，期间已关闭则丢弃通知。 */
    private fun <T> locked(block: (MutableList<Note>) -> T): T {
        val notes = ArrayList<Note>()
        var dispatch = false
        val value = lock.withLock {
            val produced = block(notes)
            dispatch = !closed
            produced
        }
        if (dispatch) notes.forEach { safeCall(if (it.error) onError else onApplied, it.message) }
        return value
    }

    internal fun readSnapshot(): RequestImageDiskSnapshot {
        val path = file.toPath()
        return try {
            if (Files.isDirectory(path)) {
                RequestImageDiskSnapshot.Failed("路径是目录而不是文件")
            } else {
                Files.newInputStream(path).use { input ->
                    val bytes = input.readNBytes(RequestImageConfigCodec.MAX_BYTES + 1)
                    val oversized = bytes.size > RequestImageConfigCodec.MAX_BYTES
                    RequestImageDiskSnapshot.Content(bytes, oversized, contentFingerprint(bytes, oversized))
                }
            }
        } catch (e: NoSuchFileException) {
            RequestImageDiskSnapshot.Missing
        } catch (e: IOException) {
            RequestImageDiskSnapshot.Failed(describe(e))
        } catch (e: SecurityException) {
            RequestImageDiskSnapshot.Failed(describe(e))
        }
    }

    private fun atomicWrite(bytes: ByteArray): Unit {
        val target = file.toPath().toAbsolutePath()
        val directory = requireNotNull(target.parent) { "配置文件没有父目录" }
        val temp = Files.createTempFile(directory, ".${file.name}.", ".tmp")
        var moved = false
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            atomicReplace(temp, target)
            moved = true
        } finally {
            if (!moved) {
                // 清理失败不影响结果判定：未移动时本次写入本就失败，目标文件未被修改。
                try {
                    Files.deleteIfExists(temp)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun safeCall(callback: (String) -> Unit, message: String): Unit {
        try {
            callback(message)
        } catch (ignored: Exception) {
            // 日志回调失败不能影响配置状态。
        }
    }

    private data class Note(val error: Boolean, val message: String)

    internal companion object {
        internal const val FILE_NAME: String = "request_image.yml"
        internal const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 1_000L
        internal const val DEFAULT_STABLE_READS: Int = 2

        internal fun contentFingerprint(bytes: ByteArray, oversized: Boolean): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "content:$hex" + if (oversized) ":oversized" else ""
        }

        internal fun defaultResourceBytes(): ByteArray =
            RequestImageConfigStore::class.java.getResourceAsStream("/$FILE_NAME")?.use { it.readBytes() }
                ?: RequestImageConfigCodec.render(RequestImageStyle()).toByteArray(Charsets.UTF_8)

        internal fun describe(error: Throwable): String {
            if (error is AtomicMoveNotSupportedException) return "文件系统不支持原子替换，已拒绝非原子写入"
            val message = error.message?.replace(Regex("[\\r\\n]+"), " ")?.take(200)
            return if (message.isNullOrBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
        }
    }
}

private fun defaultAtomicReplace(source: Path, target: Path): Unit {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/**
 * 插件生命周期内唯一的请求图片配置管理器。不依赖 XAiWebHook 对象初始化，由 onEnable/onDisable 调用。
 * 关闭后只读接口返回最后一次有效快照；写入/重载失败且不会惰性重新启动。
 */
internal object RequestImageConfigRuntime {
    private val lifecycleLock = Any()

    @Volatile
    private var store: RequestImageConfigStore? = null

    @Volatile
    private var lastState: RequestImageConfigState? = null

    @Volatile
    private var lastFilePath: String? = null

    /** 同步完成首次加载并启动 1 秒周期、两次一致读取的文件监控；重复调用会先关闭旧实例。 */
    fun start(directory: File, onError: (String) -> Unit, onApplied: (String) -> Unit): Unit {
        start(
            directory,
            onError,
            onApplied,
            RequestImageConfigStore.DEFAULT_POLL_INTERVAL_MILLIS,
            RequestImageConfigStore.DEFAULT_STABLE_READS
        )
    }

    /** 测试入口：可缩短检查周期；生产调用使用上面的默认语义。 */
    internal fun start(
        directory: File,
        onError: (String) -> Unit,
        onApplied: (String) -> Unit,
        pollIntervalMillis: Long,
        stableReads: Int
    ): Unit = synchronized(lifecycleLock) {
        val previous = store
        previous?.close()
        previous?.let { lastState = it.snapshot() }
        val next = RequestImageConfigStore(
            file = File(directory, RequestImageConfigStore.FILE_NAME),
            onError = onError,
            onApplied = onApplied,
            pollIntervalMillis = pollIntervalMillis,
            stableReads = stableReads,
            initialRevision = lastState?.revision ?: 0L,
            initialState = lastState
        )
        try {
            // 局部实例先完成加载，再发布；期间读取仍是旧有效快照，不短暂切换为默认值。
            next.start()
        } catch (error: Throwable) {
            next.close()
            throw error
        }
        store = next
        lastFilePath = next.file.path
    }

    fun close(): Unit = synchronized(lifecycleLock) {
        val current = store ?: return@synchronized
        current.close()
        lastState = current.snapshot()
        store = null
    }

    /** 运行中的实例；未启动或已关闭时为 null。 */
    internal fun activeStore(): RequestImageConfigStore? = store?.takeIf { it.isRunning }

    /** 当前有效样式快照（无锁读取）；未启动时为内置默认，关闭后为最后有效值。 */
    internal fun style(): RequestImageStyle = currentState()?.style ?: RequestImageStyle()

    internal fun currentState(): RequestImageConfigState? = store?.snapshot() ?: lastState

    internal fun status(): RequestImageConfigStatus {
        store?.let { return it.status() }
        val last = lastState
        return RequestImageConfigStatus(
            filePath = lastFilePath,
            revision = last?.revision ?: 0L,
            watching = false,
            lastLoadedAtMillis = last?.lastLoadedAtMillis,
            lastError = last?.lastError
        )
    }

    /** 仅测试使用：清空跨测试残留的快照。 */
    internal fun resetForTest(): Unit = synchronized(lifecycleLock) {
        store?.close()
        store = null
        lastState = null
        lastFilePath = null
    }
}
