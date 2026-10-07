package kim.hhhhhy.x.webhook.action.codex

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.util.HttpProxySupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 降智检测数据抓取与报告组装。
 *
 * 数据源分工（字段、数据点数量与体积可能随站点更新）：
 * - /data/intelligence-efficiency.json：智力效率主数据，points/items 为模型 × 思考档位聚合点；
 * - /api/radar-insights：可选洞察数据，提供 recommendations 与 degradation_alerts；
 * - /api/intelligence-efficiency：原始逐题评测明细，本功能默认不拉取。
 */
internal object CodexRadarAction {
    private const val DEFAULT_EFFICIENCY_URL =
        "https://codexradar.com/data/intelligence-efficiency.json?refresh=1"
    private const val DEFAULT_INSIGHTS_URL =
        "https://codexradar.com/api/radar-insights?refresh=1"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val clientLock = Any()

    @Volatile
    private var clientRef: HttpClient? = null

    /** 当前客户端所用代理，代理配置变化时需要重建客户端。 */
    @Volatile
    private var clientProxy: String = ""

    private fun client(proxyUrl: String): HttpClient {
        clientRef?.let { existing ->
            if (clientProxy == proxyUrl) return existing
        }
        return synchronized(clientLock) {
            val existing = clientRef
            if (existing != null && clientProxy == proxyUrl) {
                existing
            } else {
                existing?.close()
                createClient(proxyUrl).also {
                    clientRef = it
                    clientProxy = proxyUrl
                }
            }
        }
    }

    private fun createClient(proxyUrl: String): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        engine {
            proxy = HttpProxySupport.ktorProxy(proxyUrl)
        }
    }

    fun close(): Unit = synchronized(clientLock) {
        clientRef?.close()
        clientRef = null
        clientProxy = ""
    }

    /** 抓取并归一化数据。效率数据为必需项；洞察请求失败时仅缺少预警与推荐。 */
    suspend fun fetchSnapshot(spec: CodexRadarFetchSpec): RadarSnapshot {
        val efficiencyResult = fetchJson(spec.efficiencyUrl, "intelligence-efficiency", spec.proxyUrl)
        val insightsResult = fetchJson(spec.insightsUrl, "radar-insights", spec.proxyUrl)
        val efficiency = efficiencyResult.value
        val insights = insightsResult.value
        val tiers = requireEfficiencyTiers(efficiency, efficiencyResult.failure)
        val alerts = CodexRadarParser.parseAlerts(insights)
        val recommendations = CodexRadarParser.parseRecommendations(insights)

        WebHookDebug.log(
            "[XAiWebHook] [降智检测] 已解析 ${tiers.size} 个档位数据点，" +
                "降智预警 ${alerts.size} 条"
        )
        return RadarSnapshot(
            tiers = tiers,
            alerts = alerts,
            recommendations = recommendations,
            sourceUpdatedAt = CodexRadarParser.sourceUpdatedAt(efficiency)
                ?: CodexRadarParser.sourceUpdatedAt(insights),
            alertRule = CodexRadarParser.alertRule(insights)
        )
    }

    private suspend fun fetchJson(url: String, tag: String, proxyUrl: String): JsonFetchResult {
        if (url.isBlank()) return JsonFetchResult(null, "URL is blank")
        return runCatching {
            WebHookDebug.log("[XAiWebHook] [降智检测] 请求 $tag：$url")
            val response = client(proxyUrl).get(url)
            ensureSuccessfulResponse(response.status.value)
            val text = response.bodyAsText()
            // 大响应体解析放到 IO 线程，避免阻塞事件循环
            val value = withContext(Dispatchers.Default) { json.parseToJsonElement(text) }
            JsonFetchResult(value, null)
        }.getOrElse { error ->
            val failure = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            CodexRadarLog.warning("Codex radar fetch failed tag=$tag: $failure")
            WebHookDebug.log("[XAiWebHook] [降智检测] $tag 请求失败：$failure")
            JsonFetchResult(null, failure)
        }
    }

    /** 非 2xx 响应在读取/解析正文前失败，避免把错误页误判为 JSON 格式错误。 */
    internal fun ensureSuccessfulResponse(statusCode: Int): Unit {
        if (statusCode !in 200..299) {
            throw IllegalStateException("HTTP $statusCode")
        }
    }

    /** 必需主数据不可用与响应成功但 schema 不兼容时，提供不同的内部错误。 */
    internal fun requireEfficiencyTiers(
        efficiency: JsonElement?,
        failure: String?
    ): List<RadarTier> {
        if (efficiency == null) {
            val detail = failure?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            throw IllegalStateException("required intelligence-efficiency fetch failed$detail")
        }
        return CodexRadarParser.parseTiers(efficiency).ifEmpty {
            throw IllegalStateException(
                "intelligence-efficiency response contained no supported data points"
            )
        }
    }

    /** 从 action 参数解析抓取地址。 */
    fun fetchSpec(action: ActionConfig, render: (Any?) -> String): CodexRadarFetchSpec {
        val efficiency = render(action.params["efficiency_url"]).ifBlank { DEFAULT_EFFICIENCY_URL }
        val insights = render(action.params["insights_url"]).ifBlank { DEFAULT_INSIGHTS_URL }
        return CodexRadarFetchSpec(
            efficiencyUrl = efficiency,
            insightsUrl = insights,
            proxyUrl = HttpProxySupport.normalize(render(action.params["proxy"]))
        )
    }
}

private data class JsonFetchResult(
    val value: JsonElement?,
    val failure: String?
)

internal data class CodexRadarFetchSpec(
    val efficiencyUrl: String,
    val insightsUrl: String,
    /** HTTP 代理地址，例如 http://127.0.0.1:7890；留空表示直连。 */
    val proxyUrl: String = ""
)
