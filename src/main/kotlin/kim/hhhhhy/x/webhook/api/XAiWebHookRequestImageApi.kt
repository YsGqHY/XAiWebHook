package kim.hhhhhy.x.webhook.api

import kim.hhhhhy.x.webhook.config.RequestImageConfigCodec
import kim.hhhhhy.x.webhook.config.RequestImageConfigRuntime
import kim.hhhhhy.x.webhook.config.RequestImageConfigStore
import kim.hhhhhy.x.webhook.config.RequestImageStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 请求图片配置的运行状态。 */
public data class RequestImageConfigStatus(
    /** 运行时 request_image.yml 的路径；从未启动时为 null。 */
    public val filePath: String?,
    /** 有效配置修订号，每次成功应用加 1；从未成功应用时为 0。 */
    public val revision: Long,
    /** 自动文件监控是否在运行。 */
    public val watching: Boolean,
    /** 最近一次成功应用的时间（毫秒时间戳）。 */
    public val lastLoadedAtMillis: Long?,
    /** 最近一次加载/写入错误；最近一次操作成功时为 null。 */
    public val lastError: String?
)

/** 写入、字段更新或重载的结果。失败时 [style]/[revision] 为保持不变的当前有效配置。 */
public data class RequestImageConfigUpdateResult(
    public val success: Boolean,
    public val style: RequestImageStyle,
    public val revision: Long,
    public val error: String?
)

/**
 * 请求记录图片样式的公开读写接口，供依赖插件调用。
 *
 * - 只读接口无锁读取当前有效快照；插件未启动时返回内置默认，已停止时返回最后有效值。
 * - 写接口在 IO 线程执行严格校验、原子持久化后立即应用；插件未启动或已停止时直接失败，不会惰性启动监控。
 */
public object XAiWebHookRequestImageApi {
    /** 当前有效样式快照。 */
    public fun read(): RequestImageStyle = RequestImageConfigRuntime.style()

    /** 当前有效样式的完整 YAML（带注释）；不读取尚未校验的磁盘内容。 */
    public fun readYaml(): String = RequestImageConfigCodec.render(read())

    /** 路径、修订号、监控状态、最近成功时间和最近错误。 */
    public fun status(): RequestImageConfigStatus = RequestImageConfigRuntime.status()

    /** 校验并原子写入完整样式，成功后立即生效。 */
    public suspend fun write(style: RequestImageStyle): RequestImageConfigUpdateResult =
        withStore { it.write(style) }

    /** 以 YAML 文本整体替换配置；与文件加载共用同一解析和校验。 */
    public suspend fun writeYaml(yaml: String): RequestImageConfigUpdateResult =
        withStore { it.writeYaml(yaml) }

    /** 按点分字段路径（如 `margin.left_percent`、`border.color`）原子修改单个字段。 */
    public suspend fun update(key: String, value: String): RequestImageConfigUpdateResult =
        withStore { it.update(key, value) }

    /** 立即读取磁盘配置并安全应用；无效时保留当前有效样式。 */
    public suspend fun reload(): RequestImageConfigUpdateResult =
        withStore { it.reload() }

    /** 可用于 [update] 的字段路径。 */
    public val fieldPaths: List<String>
        get() = RequestImageConfigCodec.FIELD_PATHS.toList()

    private suspend fun withStore(
        block: (RequestImageConfigStore) -> RequestImageConfigUpdateResult
    ): RequestImageConfigUpdateResult = withContext(Dispatchers.IO) {
        val store = RequestImageConfigRuntime.activeStore()
        if (store == null) {
            val state = RequestImageConfigRuntime.currentState()
            RequestImageConfigUpdateResult(
                success = false,
                style = state?.style ?: RequestImageStyle(),
                revision = state?.revision ?: 0L,
                error = "请求图片配置管理器未运行（插件未启动或已停止），未执行写入或重载"
            )
        } else {
            block(store)
        }
    }
}
