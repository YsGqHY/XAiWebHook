package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.XAiWebHook
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit

internal object WebPageCliBridgeAuthClient {
    fun login(
        auth: ResolvedBrowserAuth,
        timeoutMillis: Long,
        browserOpener: (URI) -> Unit = SystemBrowserLauncher::open,
        sleeper: (Long) -> Unit = Thread::sleep,
        nowMillis: () -> Long = System::currentTimeMillis,
        onTokenDelivered: (BrowserDeliveredTokenPair) -> Unit = {},
        browserOpenFailureHandler: (URI, Throwable) -> Unit = { uri, error ->
            XAiWebHook.logger.warning(
                "CLI bridge could not open the system browser; open manually while polling continues: " +
                    "$uri (${summarizeError(error)})"
            )
        }
    ): BrowserTokenPair {
        val bridge = auth.spec.cliBridge ?: error("CLI bridge configuration is missing")
        val client = newHttpClient(timeoutMillis)
        val startPayload = requestJson(
            client = client,
            method = "POST",
            url = bridge.startUrl,
            body = "{}",
            token = null,
            timeoutMillis = timeoutMillis,
            operation = "CLI bridge start"
        )
        val bridgeId = startPayload.string("bridge_id")
            ?: error("CLI bridge start response bridge_id is missing")
        val pollSecret = startPayload.string("poll_secret")
            ?: error("CLI bridge start response poll_secret is missing")
        val expiresInSeconds = startPayload.long("expires_in")
            ?: error("CLI bridge start response expires_in is missing")
        require(expiresInSeconds > 0L) { "CLI bridge start response expires_in must be positive" }

        val authorizationUri = buildAuthorizationUri(bridge.browserUrl, bridgeId)
        try {
            browserOpener(authorizationUri)
        } catch (error: Throwable) {
            runCatching { browserOpenFailureHandler(authorizationUri, error) }
        }

        val startedAt = nowMillis()
        val serverDeadline = safeAddMillis(startedAt, TimeUnit.SECONDS.toMillis(expiresInSeconds))
        val configuredDeadline = safeAddMillis(startedAt, bridge.maxWaitMillis)
        val deadline = minOf(serverDeadline, configuredDeadline)
        var lastTransientError: String? = null

        while (nowMillis() < deadline) {
            sleepBeforePoll(bridge.pollIntervalMillis, deadline, sleeper, nowMillis)
            if (nowMillis() >= deadline) break

            val response = try {
                sendJson(
                    client = client,
                    method = "POST",
                    url = bridge.pollUrl,
                    body = """{"bridge_id":${jsonString(bridgeId)},"poll_secret":${jsonString(pollSecret)}}""",
                    token = null,
                    timeoutMillis = timeoutMillis
                )
            } catch (error: Throwable) {
                lastTransientError = summarizeError(error)
                continue
            }

            if (response.status == 429 || response.status >= 500) {
                lastTransientError = "HTTP ${response.status}"
                continue
            }
            val pollPayload = WebPageCredentialAuthClient.parseApiResponseBody(
                status = response.status,
                responseBody = response.body,
                operation = "CLI bridge poll"
            )
            when (pollPayload.string("status")?.lowercase()) {
                "pending" -> continue
                "authorized" -> {
                    val accessToken = pollPayload.string("access_token")
                        ?: error("CLI bridge poll response access_token is missing")
                    val refreshToken = pollPayload.string("refresh_token")
                        ?: error("CLI bridge poll response refresh_token is missing")
                    val expiresIn = pollPayload.long("expires_in")
                        ?: error("CLI bridge poll response expires_in is missing")
                    require(expiresIn > 0L) { "CLI bridge poll response expires_in must be positive" }
                    val delivered = BrowserDeliveredTokenPair(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresAtMillis = safeAddMillis(nowMillis(), TimeUnit.SECONDS.toMillis(expiresIn)),
                        tokenType = pollPayload.string("token_type") ?: "Bearer"
                    )
                    onTokenDelivered(delivered)
                    return complete(
                        auth = auth,
                        delivered = delivered,
                        timeoutMillis = timeoutMillis,
                        client = client
                    )
                }
                null -> error("CLI bridge poll response status is missing")
                else -> error("CLI bridge poll response status is unsupported")
            }
        }

        throw IllegalStateException(
            buildString {
                append("CLI bridge login timed out; start a new authorization")
                if (lastTransientError != null) append(" (last transient error: $lastTransientError)")
            }
        )
    }

    fun complete(
        auth: ResolvedBrowserAuth,
        delivered: BrowserDeliveredTokenPair,
        timeoutMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): BrowserTokenPair {
        require(delivered.expiresAtMillis > nowMillis) {
            "CLI bridge delivered access token has expired"
        }
        return complete(
            auth = auth,
            delivered = delivered,
            timeoutMillis = timeoutMillis,
            client = newHttpClient(timeoutMillis)
        )
    }

    private fun complete(
        auth: ResolvedBrowserAuth,
        delivered: BrowserDeliveredTokenPair,
        timeoutMillis: Long,
        client: HttpClient
    ): BrowserTokenPair {
        val bridge = auth.spec.cliBridge ?: error("CLI bridge profile configuration is missing")
        val user = fetchUser(
            client = client,
            bridge = bridge,
            accessToken = delivered.accessToken,
            tokenType = delivered.tokenType,
            timeoutMillis = timeoutMillis
        )
        return BrowserTokenPair(
            accessToken = delivered.accessToken,
            refreshToken = delivered.refreshToken,
            expiresAtMillis = delivered.expiresAtMillis,
            tokenType = delivered.tokenType,
            user = user
        )
    }

    fun refresh(
        auth: ResolvedBrowserAuth,
        timeoutMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): BrowserTokenPair {
        val bridge = auth.spec.cliBridge ?: error("CLI bridge refresh configuration is missing")
        val previous = auth.tokenPair ?: error("CLI bridge refresh token pair is missing")
        val refreshToken = previous.refreshToken ?: error("CLI bridge refresh token is missing")
        val payload = requestJson(
            client = newHttpClient(timeoutMillis),
            method = "POST",
            url = bridge.refreshUrl,
            body = """{"refresh_token":${jsonString(refreshToken)}}""",
            token = null,
            timeoutMillis = timeoutMillis,
            operation = "CLI bridge refresh"
        )
        val accessToken = payload.string("access_token")
            ?: error("CLI bridge refresh response access_token is missing")
        val rotatedRefreshToken = payload.string("refresh_token")
            ?: error("CLI bridge refresh response refresh_token is missing")
        val expiresIn = payload.long("expires_in")
            ?: error("CLI bridge refresh response expires_in is missing")
        require(expiresIn > 0L) { "CLI bridge refresh response expires_in must be positive" }
        return BrowserTokenPair(
            accessToken = accessToken,
            refreshToken = rotatedRefreshToken,
            expiresAtMillis = safeAddMillis(nowMillis, TimeUnit.SECONDS.toMillis(expiresIn)),
            tokenType = payload.string("token_type") ?: previous.tokenType,
            user = previous.user
        )
    }

    internal fun buildAuthorizationUri(browserUrl: String, bridgeId: String): URI {
        val separator = if (URI(browserUrl).rawQuery == null) "?" else "&"
        val encodedBridgeId = URLEncoder.encode(bridgeId, StandardCharsets.UTF_8.name())
        return URI("$browserUrl${separator}bridge_id=$encodedBridgeId")
    }

    private fun fetchUser(
        client: HttpClient,
        bridge: BrowserCliBridgeAuth,
        accessToken: String,
        tokenType: String,
        timeoutMillis: Long
    ): JsonObject {
        val payload = requestJson(
            client = client,
            method = "GET",
            url = bridge.profileUrl,
            body = null,
            token = "$tokenType $accessToken",
            timeoutMillis = timeoutMillis,
            operation = "CLI bridge profile"
        )
        return payload["user"] as? JsonObject ?: payload
    }

    private fun requestJson(
        client: HttpClient,
        method: String,
        url: String,
        body: String?,
        token: String?,
        timeoutMillis: Long,
        operation: String
    ): JsonObject {
        val response = try {
            sendJson(client, method, url, body, token, timeoutMillis)
        } catch (error: Throwable) {
            throw IllegalStateException("$operation request failed: ${summarizeError(error)}", error)
        }
        return WebPageCredentialAuthClient.parseApiResponseBody(
            status = response.status,
            responseBody = response.body,
            operation = operation
        )
    }

    private fun sendJson(
        client: HttpClient,
        method: String,
        url: String,
        body: String?,
        token: String?,
        timeoutMillis: Long
    ): JsonHttpResponse {
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMillis(timeoutMillis.coerceAtLeast(1L)))
            .header("Accept", "application/json")
        if (token != null) builder.header("Authorization", token)
        val requestBody = body?.let(HttpRequest.BodyPublishers::ofString)
            ?: HttpRequest.BodyPublishers.noBody()
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, requestBody)
        val response = client.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
        return JsonHttpResponse(response.statusCode(), response.body())
    }

    private fun newHttpClient(timeoutMillis: Long): HttpClient {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMillis.coerceAtLeast(1L)))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    private fun sleepBeforePoll(
        intervalMillis: Long,
        deadline: Long,
        sleeper: (Long) -> Unit,
        nowMillis: () -> Long
    ): Unit {
        val remaining = deadline - nowMillis()
        if (remaining <= 0L) return
        val duration = minOf(intervalMillis, remaining)
        try {
            sleeper(duration)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("CLI bridge polling was interrupted", error)
        }
    }

    private fun safeAddMillis(startMillis: Long, durationMillis: Long): Long {
        return if (durationMillis > Long.MAX_VALUE - startMillis) Long.MAX_VALUE else startMillis + durationMillis
    }

    private fun jsonString(value: String): String {
        return kotlinx.serialization.json.Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))
    }

    private fun JsonObject.string(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
    }

    private fun JsonObject.long(key: String): Long? {
        return (this[key] as? JsonPrimitive)?.longOrNull
    }

    private fun summarizeError(error: Throwable): String {
        return (error.message ?: error::class.simpleName ?: "unknown error")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(300)
    }

    private data class JsonHttpResponse(
        val status: Int,
        val body: String
    )
}

internal object SystemBrowserLauncher {
    fun open(uri: URI): Unit {
        val desktopFailure = runCatching {
            check(Desktop.isDesktopSupported()) { "desktop integration is unavailable" }
            val desktop = Desktop.getDesktop()
            check(desktop.isSupported(Desktop.Action.BROWSE)) { "system browser integration is unavailable" }
            desktop.browse(uri)
        }.exceptionOrNull()
        if (desktopFailure == null) return

        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            osName.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
            osName.contains("mac") -> listOf("open", uri.toString())
            else -> listOf("xdg-open", uri.toString())
        }
        try {
            ProcessBuilder(command).start()
        } catch (error: Throwable) {
            error.addSuppressed(desktopFailure)
            throw error
        }
    }
}

internal data class BrowserDeliveredTokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val tokenType: String
)

internal data class BrowserCliBridgeAuth(
    val startUrl: String,
    val browserUrl: String,
    val pollUrl: String,
    val profileUrl: String,
    val refreshUrl: String,
    val pollIntervalMillis: Long,
    val maxWaitMillis: Long,
    val refreshBeforeExpirySeconds: Long,
    val retryCooldownMillis: Long
)
