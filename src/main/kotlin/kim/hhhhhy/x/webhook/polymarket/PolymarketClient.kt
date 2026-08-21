package kim.hhhhhy.x.webhook.polymarket

import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.util.HttpProxySupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

internal object PolymarketClient {
    private const val MAX_REQUEST_ATTEMPTS = 2
    private const val RETRY_DELAY_MILLIS = 250L

    private val clientLock = Any()
    private val clients = mutableMapOf<ClientKey, HttpClient>()
    private val inFlightRequests = mutableSetOf<CompletableFuture<*>>()

    private fun createClient(timeoutMillis: Long, proxyUrl: String): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis((timeoutMillis / 2).coerceAtLeast(1L)))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
        return HttpProxySupport.configureJava(builder, proxyUrl).build()
    }

    private fun clientKey(timeoutMillis: Long, proxyUrl: String): ClientKey {
        return ClientKey(timeoutMillis, HttpProxySupport.normalize(proxyUrl))
    }

    private fun clientLocked(key: ClientKey): HttpClient {
        return clients.getOrPut(key) { createClient(key.timeoutMillis, key.proxyUrl) }
    }

    private fun startRequest(
        key: ClientKey,
        request: HttpRequest
    ): CompletableFuture<HttpResponse<String>> = synchronized(clientLock) {
        val future = clientLocked(key).sendAsync(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
        inFlightRequests += future
        future.whenComplete { _, _ ->
            synchronized(clientLock) {
                inFlightRequests.remove(future)
            }
        }
        future
    }

    private suspend fun send(
        key: ClientKey,
        request: HttpRequest
    ): HttpResponse<String> = suspendCancellableCoroutine { continuation ->
        val future = startRequest(key, request)
        continuation.invokeOnCancellation {
            future.cancel(true)
        }
        future.whenComplete { response, error ->
            if (!continuation.isActive) return@whenComplete
            when {
                error != null -> continuation.resumeWith(Result.failure(unwrapCompletionError(error)))
                response != null -> continuation.resumeWith(Result.success(response))
                else -> continuation.resumeWith(
                    Result.failure(IllegalStateException("Polymarket HTTP request completed without a response"))
                )
            }
        }
    }

    public suspend fun searchMarkets(
        gammaApiBaseUrl: String,
        timeoutMillis: Long,
        limit: Int = 100,
        offset: Int = 0,
        locale: String = "zh",
        proxyUrl: String = ""
    ): List<PolymarketMarket> {
        WebHookDebug.log("[Polymarket] 搜索市场，limit=$limit, offset=$offset")
        val key = clientKey(timeoutMillis, proxyUrl)
        val uri = buildUri(
            baseUrl = gammaApiBaseUrl,
            endpointPath = "/markets",
            parameters = listOf(
                "limit" to limit.coerceIn(1, 500).toString(),
                "offset" to offset.coerceAtLeast(0).toString(),
                "closed" to "false",
                "locale" to locale
            )
        )
        val response = get(key, uri, "search markets")
        ensureSuccess(response.statusCode(), "search markets")
        return try {
            val markets = PolymarketJsonCodec.decodeMarkets(
                PolymarketJsonCodec.json.parseToJsonElement(response.body())
            )
            WebHookDebug.log("[Polymarket] 搜索成功，返回 ${markets.size} 个市场")
            markets
        } catch (error: Throwable) {
            throw IllegalStateException("Polymarket 市场响应格式无效", error)
        }
    }

    public suspend fun searchEvents(
        gammaApiBaseUrl: String,
        timeoutMillis: Long,
        query: String,
        limit: Int = 20,
        proxyUrl: String = ""
    ): List<PolymarketEvent> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()
        WebHookDebug.log("[Polymarket] 搜索事件，query='$normalizedQuery', limit=$limit")
        val key = clientKey(timeoutMillis, proxyUrl)
        val uri = buildUri(
            baseUrl = gammaApiBaseUrl,
            endpointPath = "/public-search",
            parameters = listOf(
                "q" to normalizedQuery,
                "events_status" to "active",
                "limit_per_type" to limit.coerceIn(1, 50).toString(),
                "search_profiles" to "false",
                "search_tags" to "false"
            )
        )
        val response = get(key, uri, "search events")
        ensureSuccess(response.statusCode(), "search events")
        return try {
            val events = PolymarketJsonCodec.decodePublicSearchEvents(
                PolymarketJsonCodec.json.parseToJsonElement(response.body())
            )
            WebHookDebug.log("[Polymarket] 事件搜索成功，返回 ${events.size} 个事件")
            events
        } catch (error: Throwable) {
            throw IllegalStateException("Polymarket 事件搜索响应格式无效", error)
        }
    }

    public suspend fun getEventBySlug(
        gammaApiBaseUrl: String,
        timeoutMillis: Long,
        slug: String,
        locale: String = "zh",
        proxyUrl: String = ""
    ): PolymarketEvent? {
        WebHookDebug.log("[Polymarket] 获取事件详情，slug=$slug")
        val key = clientKey(timeoutMillis, proxyUrl)
        val uri = buildUri(
            baseUrl = gammaApiBaseUrl,
            endpointPath = "/events/slug/${encode(slug)}",
            parameters = listOf("locale" to locale)
        )
        val response = get(key, uri, "get event")
        if (response.statusCode() == 404) return null
        ensureSuccess(response.statusCode(), "get event")
        return try {
            PolymarketJsonCodec.decodeEvent(
                PolymarketJsonCodec.json.parseToJsonElement(response.body())
            ) ?: throw IllegalStateException("Polymarket 事件响应缺少 slug 或 title")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalStateException("Polymarket 事件响应格式无效", error)
        }
    }

    public suspend fun getMarket(
        gammaApiBaseUrl: String,
        timeoutMillis: Long,
        marketId: String,
        locale: String = "zh",
        proxyUrl: String = ""
    ): PolymarketMarket? {
        WebHookDebug.log("[Polymarket] 获取市场详情，marketId=$marketId")
        return try {
            val key = clientKey(timeoutMillis, proxyUrl)
            val uri = buildUri(
                baseUrl = gammaApiBaseUrl,
                endpointPath = "/markets/${encode(marketId)}",
                parameters = listOf("locale" to locale)
            )
            val response = get(key, uri, "get market")
            if (response.statusCode() !in 200..299) return null
            PolymarketJsonCodec.decodeMarket(
                PolymarketJsonCodec.json.parseToJsonElement(response.body())
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            XAiWebHook.logger.error("Polymarket get market failed: ${error.message}", error)
            null
        }
    }

    public suspend fun getPriceHistory(
        clobApiBaseUrl: String,
        timeoutMillis: Long,
        assetId: String,
        interval: String = "1d",
        proxyUrl: String = ""
    ): List<PolymarketPricePoint> {
        WebHookDebug.log("[Polymarket] 获取价格历史，assetId=$assetId, interval=$interval")
        val key = clientKey(timeoutMillis, proxyUrl)
        val uri = buildUri(
            baseUrl = clobApiBaseUrl,
            endpointPath = "/prices-history",
            parameters = listOf(
                "market" to assetId,
                "interval" to interval
            )
        )
        val response = get(key, uri, "get price history")
        ensureSuccess(response.statusCode(), "get price history")
        return try {
            val history = PolymarketJsonCodec.decodePriceHistory(
                PolymarketJsonCodec.json.parseToJsonElement(response.body())
            )
            WebHookDebug.log("[Polymarket] 获取价格历史成功，共 ${history.size} 个数据点")
            history
        } catch (error: Throwable) {
            throw IllegalStateException("Polymarket 价格历史响应格式无效", error)
        }
    }

    private suspend fun get(
        key: ClientKey,
        uri: URI,
        operation: String
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(key.timeoutMillis.coerceAtLeast(1L)))
            .header("Accept", "application/json")
            .GET()
            .build()
        return executeWithNetworkRetry(
            operation = operation,
            target = uri,
            proxyUrl = key.proxyUrl,
            onRetry = { invalidateClient(key) }
        ) {
            send(key, request)
        }
    }

    internal suspend fun <T> executeWithNetworkRetry(
        operation: String,
        target: URI,
        proxyUrl: String,
        retryDelayMillis: Long = RETRY_DELAY_MILLIS,
        onRetry: () -> Unit = {},
        request: suspend () -> T
    ): T {
        var attempt = 1
        while (true) {
            try {
                return request()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (attempt >= MAX_REQUEST_ATTEMPTS) {
                    throw transportFailure(operation, target, proxyUrl, attempt, error)
                }
                onRetry()
                if (retryDelayMillis > 0L) delay(retryDelayMillis)
                attempt++
            }
        }
    }

    private fun invalidateClient(key: ClientKey): Unit = synchronized(clientLock) {
        clients.remove(key)
    }

    public fun close(): Unit {
        val pending = synchronized(clientLock) {
            clients.clear()
            inFlightRequests.toList().also { inFlightRequests.clear() }
        }
        pending.forEach { future -> future.cancel(true) }
    }

    private fun buildUri(
        baseUrl: String,
        endpointPath: String,
        parameters: List<Pair<String, String>>
    ): URI {
        val base = runCatching { URI(baseUrl.trim()) }
            .getOrElse { error -> throw IllegalArgumentException("invalid Polymarket base URL", error) }
        require(base.scheme == "http" || base.scheme == "https") {
            "Polymarket base URL must use http or https"
        }
        require(!base.host.isNullOrBlank()) { "Polymarket base URL host is required" }
        require(base.rawQuery == null && base.rawFragment == null) {
            "Polymarket base URL must not contain query or fragment"
        }
        val path = base.rawPath.orEmpty().trimEnd('/') + "/" + endpointPath.trimStart('/')
        val query = parameters.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }
        return URI.create(
            buildString {
                append(base.scheme.lowercase())
                append("://")
                append(base.rawAuthority)
                append(path)
                if (query.isNotEmpty()) append('?').append(query)
            }
        )
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    private fun transportFailure(
        operation: String,
        target: URI,
        proxyUrl: String,
        attempts: Int,
        error: IOException
    ): IllegalStateException {
        val detail = (error.message ?: error::class.simpleName ?: "network error")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(240)
        return IllegalStateException(
            "Polymarket $operation request failed after $attempts attempts: " +
                "host=${target.host}, proxy=${HttpProxySupport.describe(proxyUrl)} ($detail)",
            error
        )
    }

    private fun unwrapCompletionError(error: Throwable): Throwable {
        var current = error
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = requireNotNull(current.cause)
        }
        return current
    }

    private data class ClientKey(
        val timeoutMillis: Long,
        val proxyUrl: String
    )

    private fun ensureSuccess(status: Int, operation: String): Unit {
        if (status !in 200..299) {
            throw IllegalStateException("Polymarket $operation HTTP $status")
        }
    }
}
