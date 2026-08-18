package kim.hhhhhy.x.webhook.scraper

import kim.hhhhhy.x.webhook.config.ModelPlazaAuthConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit

@Serializable
internal data class CachedSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val tokenType: String,
    val user: String
)

/**
 * Model Plaza 认证管理器
 * 负责 CLI Bridge 登录、Token 刷新和 Session 缓存
 */
internal object ModelPlazaAuthManager {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var cachedSession: CachedSession? = null
    private var sessionFile: File? = null

    /**
     * 获取有效的访问令牌，如果缓存失效则自动刷新或重新登录
     */
    @Synchronized
    public fun getValidToken(
        authConfig: ModelPlazaAuthConfig,
        cacheDir: File?
    ): String {
        initializeSessionCache(cacheDir)
        if (cachedSession == null) {
            cachedSession = loadSessionFromCache()
        }

        val session = cachedSession
        if (session != null) {
            val refreshThreshold = TimeUnit.SECONDS.toMillis(authConfig.refreshBeforeExpirySeconds)
            val timeUntilExpiry = session.expiresAtMillis - System.currentTimeMillis()
            if (timeUntilExpiry > refreshThreshold) {
                WebHookDebug.log("[ModelPlaza] 使用缓存的访问令牌，用户: ${session.user}")
                return session.accessToken
            }

            WebHookDebug.log("[ModelPlaza] 访问令牌已过期或即将过期，尝试刷新...")
            return try {
                refreshAndCache(authConfig, session.refreshToken)
            } catch (error: Exception) {
                WebHookDebug.log("[ModelPlaza] Token 刷新失败: ${error.message}，将重新登录")
                clearSessionCache()
                performLogin(authConfig)
            }
        }

        WebHookDebug.log("[ModelPlaza] 无有效 Session，启动 CLI Bridge 登录...")
        return performLogin(authConfig)
    }

    /**
     * 业务接口返回 401 时强制刷新一次；刷新凭证失效后才重新发起 CLI Bridge 登录。
     */
    @Synchronized
    public fun refreshAfterUnauthorized(
        authConfig: ModelPlazaAuthConfig,
        cacheDir: File?
    ): String {
        initializeSessionCache(cacheDir)
        if (cachedSession == null) {
            cachedSession = loadSessionFromCache()
        }

        val session = cachedSession
        if (session != null && session.refreshToken.isNotBlank()) {
            return try {
                WebHookDebug.log("[ModelPlaza] 登录态被服务端拒绝，强制刷新 Token...")
                refreshAndCache(authConfig, session.refreshToken)
            } catch (error: Exception) {
                WebHookDebug.log("[ModelPlaza] 强制刷新失败: ${error.message}，将重新登录")
                clearSessionCache()
                performLogin(authConfig)
            }
        }

        clearSessionCache()
        return performLogin(authConfig)
    }

    private fun initializeSessionCache(cacheDir: File?) {
        if (cacheDir == null) return
        cacheDir.mkdirs()
        val targetFile = File(cacheDir, "model-plaza-session.json").absoluteFile
        if (sessionFile?.absoluteFile != targetFile) {
            sessionFile = targetFile
            cachedSession = null
        }
    }

    private fun refreshAndCache(authConfig: ModelPlazaAuthConfig, refreshToken: String): String {
        val refreshed = refreshToken(authConfig, refreshToken)
        cachedSession = refreshed
        saveSessionToCache(refreshed)
        WebHookDebug.log("[ModelPlaza] Token 刷新成功，用户: ${refreshed.user}")
        return refreshed.accessToken
    }

    private fun clearSessionCache() {
        cachedSession = null
        val file = sessionFile ?: return
        if (file.exists() && !file.delete()) {
            WebHookDebug.log("[ModelPlaza] 无法删除失效的 Session 缓存: ${file.absolutePath}")
        }
    }

    private fun performLogin(authConfig: ModelPlazaAuthConfig): String {
        val client = newHttpClient(30_000L)

        // 1. 启动 CLI Bridge 会话
        WebHookDebug.log("[ModelPlaza] 请求 CLI Bridge 会话...")
        val startResponse = sendJson(
            client = client,
            method = "POST",
            url = authConfig.startUrl,
            body = "{}",
            token = null,
            timeoutMillis = 30_000L
        )

        val startPayload = parseJsonResponse(startResponse, "CLI Bridge start")

        val bridgeId = startPayload["bridge_id"]?.jsonPrimitive?.contentOrNull
            ?: error("CLI Bridge start 响应缺少 bridge_id，实际响应: ${startPayload}")
        val pollSecret = startPayload["poll_secret"]?.jsonPrimitive?.contentOrNull
            ?: error("CLI Bridge start 响应缺少 poll_secret，实际响应: ${startPayload}")
        val expiresIn = startPayload["expires_in"]?.jsonPrimitive?.longOrNull
            ?: error("CLI Bridge start 响应缺少 expires_in，实际响应: ${startPayload}")

        // 2. 打开系统浏览器
        val authUrl = buildAuthorizationUri(authConfig.browserUrl, bridgeId)
        WebHookDebug.log("[ModelPlaza] 打开系统浏览器进行授权: $authUrl")
        openBrowser(authUrl)

        // 3. 轮询等待用户授权
        val deadline = System.currentTimeMillis() + minOf(
            TimeUnit.SECONDS.toMillis(expiresIn),
            authConfig.maxWaitMillis
        )

        WebHookDebug.log("[ModelPlaza] 等待用户在浏览器中完成授权...")
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(authConfig.pollIntervalMillis)

            val pollResponse = try {
                sendJson(
                    client = client,
                    method = "POST",
                    url = authConfig.pollUrl,
                    body = """{"bridge_id":"$bridgeId","poll_secret":"$pollSecret"}""",
                    token = null,
                    timeoutMillis = 30_000L
                )
            } catch (e: Exception) {
                continue
            }

            if (pollResponse.status == 200) {
                val pollPayload = parseJsonResponse(pollResponse, "CLI Bridge poll")
                val status = pollPayload["status"]?.jsonPrimitive?.contentOrNull?.lowercase()

                when (status) {
                    "pending" -> continue
                    "authorized" -> {
                        // 4. 获取令牌
                        val accessToken = pollPayload["access_token"]?.jsonPrimitive?.contentOrNull
                            ?: error("CLI Bridge poll 响应缺少 access_token")
                        val refreshToken = pollPayload["refresh_token"]?.jsonPrimitive?.contentOrNull
                            ?: error("CLI Bridge poll 响应缺少 refresh_token")
                        val tokenExpiresIn = pollPayload["expires_in"]?.jsonPrimitive?.longOrNull
                            ?: error("CLI Bridge poll 响应缺少 expires_in")
                        val tokenType = pollPayload["token_type"]?.jsonPrimitive?.contentOrNull ?: "Bearer"

                        // 5. 获取用户信息
                        val user = fetchUserProfile(client, authConfig.profileUrl, accessToken, tokenType)

                        val session = CachedSession(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            expiresAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(tokenExpiresIn),
                            tokenType = tokenType,
                            user = user
                        )

                        cachedSession = session
                        saveSessionToCache(session)

                        WebHookDebug.log("[ModelPlaza] CLI Bridge 登录成功，用户: $user")
                        return accessToken
                    }
                    else -> error("CLI Bridge poll 响应状态未知: $status")
                }
            }
        }

        throw IllegalStateException("CLI Bridge 登录超时，用户未在浏览器中完成授权")
    }

    private fun refreshToken(authConfig: ModelPlazaAuthConfig, refreshToken: String): CachedSession {
        val client = newHttpClient(30_000L)
        val response = sendJson(
            client = client,
            method = "POST",
            url = authConfig.refreshUrl,
            body = """{"refresh_token":"$refreshToken"}""",
            token = null,
            timeoutMillis = 30_000L
        )

        val payload = parseJsonResponse(response, "Token refresh")
        val accessToken = payload["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Token refresh 响应缺少 access_token")
        val newRefreshToken = payload["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Token refresh 响应缺少 refresh_token")
        val expiresIn = payload["expires_in"]?.jsonPrimitive?.longOrNull
            ?: error("Token refresh 响应缺少 expires_in")
        val tokenType = payload["token_type"]?.jsonPrimitive?.contentOrNull ?: "Bearer"

        val oldUser = cachedSession?.user ?: "unknown"
        return CachedSession(
            accessToken = accessToken,
            refreshToken = newRefreshToken,
            expiresAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(expiresIn),
            tokenType = tokenType,
            user = oldUser
        )
    }

    private fun fetchUserProfile(
        client: HttpClient,
        profileUrl: String,
        accessToken: String,
        tokenType: String
    ): String {
        val response = sendJson(
            client = client,
            method = "GET",
            url = profileUrl,
            body = null,
            token = "$tokenType $accessToken",
            timeoutMillis = 30_000L
        )

        val payload = parseJsonResponse(response, "User profile")
        val userObj = payload["user"]?.jsonObject ?: payload
        return userObj["email"]?.jsonPrimitive?.contentOrNull
            ?: userObj["id"]?.jsonPrimitive?.contentOrNull
            ?: "unknown"
    }

    private fun loadSessionFromCache(): CachedSession? {
        val file = sessionFile ?: return null
        if (!file.exists()) return null

        return try {
            val content = file.readText(StandardCharsets.UTF_8)
            json.decodeFromString<CachedSession>(content)
        } catch (e: Exception) {
            WebHookDebug.log("[ModelPlaza] 加载缓存 Session 失败: ${e.message}")
            null
        }
    }

    private fun saveSessionToCache(session: CachedSession) {
        val file = sessionFile ?: return

        try {
            file.writeText(json.encodeToString(session), StandardCharsets.UTF_8)
            WebHookDebug.log("[ModelPlaza] Session 已缓存到: ${file.absolutePath}")
        } catch (e: Exception) {
            WebHookDebug.log("[ModelPlaza] 保存 Session 缓存失败: ${e.message}")
        }
    }

    private fun buildAuthorizationUri(browserUrl: String, bridgeId: String): URI {
        val separator = if (URI(browserUrl).rawQuery == null) "?" else "&"
        val encodedBridgeId = URLEncoder.encode(bridgeId, StandardCharsets.UTF_8.name())
        return URI("$browserUrl${separator}bridge_id=$encodedBridgeId")
    }

    private fun openBrowser(uri: URI) {
        try {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(uri)
                    return
                }
            }
        } catch (e: Exception) {
            // 降级到命令行方式
        }

        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            osName.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
            osName.contains("mac") -> listOf("open", uri.toString())
            else -> listOf("xdg-open", uri.toString())
        }

        try {
            ProcessBuilder(command).start()
        } catch (e: Exception) {
            WebHookDebug.log("[ModelPlaza] 无法打开浏览器，请手动访问: $uri")
        }
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
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Accept", "application/json")

        if (token != null) {
            builder.header("Authorization", token)
        }

        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        return JsonHttpResponse(response.statusCode(), response.body())
    }

    private fun newHttpClient(timeoutMillis: Long): HttpClient {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    private fun parseJsonResponse(response: JsonHttpResponse, operation: String): JsonObject {
        if (response.status !in 200..299) {
            error("$operation 请求失败: HTTP ${response.status}, body: ${response.body.take(200)}")
        }

        return try {
            val root = json.parseToJsonElement(response.body).jsonObject
            // 处理 {"code":0,"data":{...}} 信封格式（Geek2API 统一响应格式）
            val dataField = root["data"]
            if (dataField != null && dataField !is kotlinx.serialization.json.JsonNull) {
                dataField.jsonObject
            } else {
                // 裸 payload 格式，直接返回
                root
            }
        } catch (e: Exception) {
            error("$operation 响应解析失败: ${e.message}，body: ${response.body.take(200)}")
        }
    }

    private data class JsonHttpResponse(
        val status: Int,
        val body: String
    )
}
