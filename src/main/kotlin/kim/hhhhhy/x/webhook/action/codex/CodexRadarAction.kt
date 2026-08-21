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
 * 三个接口的分工（已实测确认）：
 * - /data/intelligence-efficiency.json：静态快照，schema=2，points 为 19 个
 *   「模型 × 思考档位」聚合点，含 iq / 均价 / 均耗时 / 综合成本指数，
 *   体积约 370KB，是智力效率面板的数据源，也是本功能的 IQ 主数据；
 * - /api/radar-insights：轻量接口（约 29KB），提供 recommendations
 *   站长推荐分组与 degradation_alerts 降智预警（每档位与自身历史比较）；
 * - /api/intelligence-efficiency：原始逐题评测矩阵（约 1.9MB），
 *   含 tasks/cells/combos 明细与每次跑分的贡献者记录，本功能默认不拉取。
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

    /** 抓取并归一化三个接口的数据。任一接口失败时按可用数据降级，IQ 主数据缺失才抛错。 */
    suspend fun fetchSnapshot(spec: CodexRadarFetchSpec): RadarSnapshot {
        val efficiency = fetchJson(spec.efficiencyUrl, "intelligence-efficiency", spec.proxyUrl)
        val insights = fetchJson(spec.insightsUrl, "radar-insights", spec.proxyUrl)

        val tiers = CodexRadarParser.parseTiers(efficiency)
        require(tiers.isNotEmpty()) { "no intelligence efficiency data points parsed" }

        WebHookDebug.log(
            "[XAiWebHook] [降智检测] 已解析 ${tiers.size} 个档位数据点，" +
                "降智预警 ${CodexRadarParser.parseAlerts(insights).size} 条"
        )
        return RadarSnapshot(
            tiers = tiers,
            alerts = CodexRadarParser.parseAlerts(insights),
            recommendations = CodexRadarParser.parseRecommendations(insights),
            sourceUpdatedAt = CodexRadarParser.sourceUpdatedAt(efficiency)
                ?: CodexRadarParser.sourceUpdatedAt(insights),
            alertRule = CodexRadarParser.alertRule(insights)
        )
    }

    private suspend fun fetchJson(url: String, tag: String, proxyUrl: String): JsonElement? {
        if (url.isBlank()) return null
        return runCatching {
            WebHookDebug.log("[XAiWebHook] [降智检测] 请求 $tag：$url")
            val text = client(proxyUrl).get(url).bodyAsText()
            // 大响应体解析放到 IO 线程，避免阻塞事件循环
            withContext(Dispatchers.Default) { json.parseToJsonElement(text) }
        }.getOrElse { error ->
            CodexRadarLog.warning("Codex radar fetch failed tag=$tag: ${error.message}")
            WebHookDebug.log("[XAiWebHook] [降智检测] $tag 请求失败：${error.message}")
            null
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

internal data class CodexRadarFetchSpec(
    val efficiencyUrl: String,
    val insightsUrl: String,
    /** HTTP 代理地址，例如 http://127.0.0.1:7890；留空表示直连。 */
    val proxyUrl: String = ""
)
