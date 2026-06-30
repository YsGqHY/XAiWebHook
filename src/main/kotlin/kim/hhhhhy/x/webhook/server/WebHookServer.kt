package kim.hhhhhy.x.webhook.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.action.WebHookActionExecutor
import kim.hhhhhy.x.webhook.action.WebHookActionExecutor.toJsonElement
import kim.hhhhhy.x.webhook.config.IncomingEndpoint
import kim.hhhhhy.x.webhook.config.PluginConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.model.RequestContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

internal object WebHookServer {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var engine: ApplicationEngine? = null

    @Volatile
    private var activeConfig: PluginConfig = PluginConfig.safeDefault()

    @Volatile
    var lastError: String? = null
        private set

    val running: Boolean
        get() = engine != null

    fun start(config: PluginConfig): Unit {
        stop()
        activeConfig = config
        if (!config.server.enabled) {
            XAiWebHook.logger.info("WebHook server disabled")
            WebHookDebug.log("[XAiWebHook] [服务] WebHook HTTP 服务已禁用（server.enabled=false）")
            return
        }
        WebHookDebug.log("[XAiWebHook] [服务] 正在启动 WebHook HTTP 服务 ${config.server.host}:${config.server.port}...")
        val unauthenticated = config.incoming.endpoints.any { endpoint ->
            endpoint.enabled && endpoint.tokens.ifEmpty { config.auth.tokens }.isEmpty()
        }
        if (unauthenticated && !config.auth.allowEmptyForLocalhost) {
            lastError = "enabled incoming endpoints require bearer tokens (or enable allow_empty_for_localhost)"
            XAiWebHook.logger.error(lastError!!)
            WebHookDebug.log("[XAiWebHook] [服务] 启动中止：存在未配置 token 的启用端点，请配置 auth.tokens 或开启 allow_empty_for_localhost")
            return
        }

        // allow_empty_for_localhost 仅在回环地址语义下安全；若绑定到非回环地址却放行无 token
        // 端点，等于把未鉴权接口暴露到网络，必须显式告警提示运维收敛 host 或补充 token。
        if (unauthenticated && config.auth.allowEmptyForLocalhost && !isLoopbackHost(config.server.host)) {
            XAiWebHook.logger.warning(
                "SECURITY: host=${config.server.host} is not loopback but token-less endpoints are allowed via " +
                    "allow_empty_for_localhost; unauthenticated endpoints are reachable from the network. " +
                    "Bind to 127.0.0.1 or configure tokens."
            )
            WebHookDebug.log("[XAiWebHook] [服务] 安全警告：host=${config.server.host} 非回环地址，但存在无 token 端点，未鉴权接口已暴露到网络！")
        }

        try {
            engine = embeddedServer(Netty, host = config.server.host, port = config.server.port) {
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        lastError = cause.message ?: cause::class.qualifiedName
                        XAiWebHook.logger.error("WebHook request failed", cause)
                        call.respondJson(
                            HttpStatusCode.InternalServerError,
                            mapOf("success" to false, "error" to "internal error")
                        )
                    }
                }
                routing {
                    get("/{...}") { handleCall(call) }
                    post("/{...}") { handleCall(call) }
                    put("/{...}") { handleCall(call) }
                    delete("/{...}") { handleCall(call) }
                    patch("/{...}") { handleCall(call) }
                }
            }.start(wait = false)
            lastError = null
            XAiWebHook.logger.info("WebHook server started at ${config.server.host}:${config.server.port}${config.server.basePath}")
            WebHookDebug.log("[XAiWebHook] [服务] WebHook HTTP 服务已启动，监听 ${config.server.host}:${config.server.port}${config.server.basePath}")
        } catch (e: Exception) {
            lastError = e.message ?: e::class.qualifiedName
            engine = null
            XAiWebHook.logger.error("Failed to start WebHook server", e)
            WebHookDebug.log("[XAiWebHook] [服务] HTTP 服务启动失败：${e.message}")
        }
    }

    fun restart(config: PluginConfig): Unit = start(config)

    fun stop(): Unit {
        if (engine != null) {
            WebHookDebug.log("[XAiWebHook] [服务] 正在停止 WebHook HTTP 服务...")
        }
        engine?.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
        engine = null
    }

    private suspend fun handleCall(call: ApplicationCall): Unit {
        // reload 会原子替换 activeConfig，整个请求取一次快照保证配置一致性
        val config = activeConfig
        val method = call.request.httpMethod.value.uppercase()
        val path = call.request.path()
        val remote = call.request.origin.remoteHost
        WebHookDebug.log("""[XAiWebHook] [请求] 收到请求
  method   : $method
  path     : $path
  来自     : $remote""")
        val endpoint = findEndpoint(call, config)
        if (endpoint == null) {
            WebHookDebug.log("[XAiWebHook] [请求] 未匹配到端点，返回 404（method=$method path=$path）")
            call.respondJson(HttpStatusCode.NotFound, mapOf("success" to false, "error" to "route not found"))
            return
        }

        if (!isAuthorized(call, endpoint, config)) {
            WebHookDebug.log("[XAiWebHook] [请求] 鉴权失败，端点=${endpoint.id}，返回 401")
            call.respondJson(HttpStatusCode.Unauthorized, mapOf("success" to false, "error" to "unauthorized"))
            return
        }
        WebHookDebug.log("[XAiWebHook] [请求] 鉴权通过，端点=${endpoint.id}")

        val maxBodyBytes = config.security.maxBodyBytes
        if (maxBodyBytes > 0) {
            val declaredLength = call.request.header("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength > maxBodyBytes) {
                WebHookDebug.log("[XAiWebHook] [请求] 请求体超出限制（Content-Length=$declaredLength > $maxBodyBytes 字节），返回 413")
                call.respondJson(
                    HttpStatusCode.PayloadTooLarge,
                    mapOf("success" to false, "error" to "request body too large")
                )
                return
            }
        }

        val requestContext = try {
            buildRequestContext(call, config)
        } catch (e: BodyTooLargeException) {
            WebHookDebug.log("[XAiWebHook] [请求] 请求体超出限制（${e.limit} 字节），返回 413")
            call.respondJson(
                HttpStatusCode.PayloadTooLarge,
                mapOf("success" to false, "error" to "request body too large")
            )
            return
        }
        if (config.logging.request) {
            XAiWebHook.logger.info("Incoming webhook endpoint=${endpoint.id} path=${requestContext.path}")
        }
        WebHookDebug.log("[XAiWebHook] [请求] 开始执行端点 ${endpoint.id} 的 ${endpoint.actions.size} 个动作...")
        val executionContext = ExecutionContext(config = config, request = requestContext)
        val results = WebHookActionExecutor.execute(endpoint.actions, executionContext)
        // 业务成功判定只看非 reply 动作；reply 仅用于显式响应控制，其状态码不影响成败判断
        val businessResults = results.filterNot { it.type == "reply" }
        val businessSucceeded = businessResults.all { it.success }
        val reply = results.lastOrNull { it.type == "reply" }
        if (!businessSucceeded) {
            val detail = businessResults.filterNot { it.success }
                .joinToString("; ") { "${it.type}: ${it.message}" }
            XAiWebHook.logger.warning("Incoming webhook endpoint=${endpoint.id} actions failed: $detail")
        }
        val resultSummary = mapOf(
            "success" to businessSucceeded,
            "results" to results.map {
                // 对外不暴露失败动作的原始 message，避免泄露机器人内部状态
                val publicMessage = if (it.success) it.message else "action failed"
                mapOf("type" to it.type, "success" to it.success, "message" to publicMessage, "status" to it.status)
            }
        )
        val status: HttpStatusCode
        val body: Any?
        if (businessSucceeded && reply != null) {
            // 业务成功时由 reply 完全接管响应，允许返回自定义 4xx/5xx 状态码与 body
            status = HttpStatusCode.fromValue(reply.status ?: 200)
            body = reply.responseBody ?: resultSummary
        } else {
            status = HttpStatusCode.fromValue(if (businessSucceeded) 200 else 500)
            body = resultSummary
        }
        if (config.logging.response) {
            XAiWebHook.logger.info("Incoming webhook endpoint=${endpoint.id} status=${status.value}")
        }
        WebHookDebug.log("""[XAiWebHook] [请求] 响应完成
  端点     : ${endpoint.id}
  状态码   : ${status.value}
  业务成功 : $businessSucceeded""")
        call.respondJson(status, body)
    }

    private fun isLoopbackHost(host: String): Boolean {
        val normalized = host.trim().lowercase()
        return normalized == "127.0.0.1" ||
            normalized == "localhost" ||
            normalized == "::1" ||
            normalized == "0:0:0:0:0:0:0:1" ||
            normalized.startsWith("127.")
    }

    private fun findEndpoint(call: ApplicationCall, config: PluginConfig): IncomingEndpoint? {
        val method = call.request.httpMethod.value.uppercase()
        val path = call.request.path()
        return config.incoming.endpoints.firstOrNull { endpoint ->
            endpoint.enabled && endpoint.method == method && fullPath(endpoint.path, config) == path
        }
    }

    private fun fullPath(endpointPath: String, config: PluginConfig): String {
        val base = config.server.basePath.trimEnd('/')
        val endpoint = endpointPath.trimStart('/')
        return if (base == "/") "/$endpoint" else "$base/$endpoint"
    }

    private fun isAuthorized(call: ApplicationCall, endpoint: IncomingEndpoint, config: PluginConfig): Boolean {
        if (config.auth.type.lowercase() != "bearer") return false
        val tokens = endpoint.tokens.ifEmpty { config.auth.tokens }
        if (tokens.isEmpty()) {
            val remote = call.request.origin.remoteHost
            // 复用统一的回环判定，保证 ::1 简写、127.x 网段等形式与启动期校验、文档承诺一致
            return config.auth.allowEmptyForLocalhost && isLoopbackHost(remote)
        }
        val header = call.request.header("Authorization") ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val token = header.substring("Bearer ".length).trim()
        if (token.isEmpty()) return false
        return tokens.any { constantTimeEquals(it, token) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }

    private suspend fun buildRequestContext(call: ApplicationCall, config: PluginConfig): RequestContext {
        val bodyText = readBodyLimited(call, config.security.maxBodyBytes)
        val body = if (bodyText.isBlank()) null else runCatching {
            fromJsonElement(json.parseToJsonElement(bodyText))
        }.getOrElse { bodyText }
        val query = call.request.queryParameters.names().associateWith { name ->
            call.request.queryParameters[name].orEmpty()
        }
        val headers = call.request.headers.names().associateWith { name ->
            call.request.headers.getAll(name).orEmpty().joinToString(",")
        }
        return RequestContext(
            method = call.request.httpMethod.value.uppercase(),
            path = call.request.path(),
            query = query,
            headers = headers,
            body = body,
            remoteHost = call.request.origin.remoteHost
        )
    }

    private suspend fun readBodyLimited(call: ApplicationCall, maxBytes: Long): String {
        if (maxBytes <= 0) return call.receiveText()
        val channel = call.receiveChannel()
        val buffer = StringBuilder()
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read == -1) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) throw BodyTooLargeException(maxBytes)
            out.write(chunk, 0, read)
        }
        buffer.append(out.toString(Charsets.UTF_8.name()))
        return buffer.toString()
    }

    private class BodyTooLargeException(val limit: Long) :
        RuntimeException("request body exceeds limit of $limit bytes")

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: Any?): Unit {
        respondText(
            text = json.encodeToString(JsonElement.serializer(), toJsonElement(body)),
            contentType = ContentType.Application.Json,
            status = status
        )
    }

    private fun fromJsonElement(element: JsonElement): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonObject -> element.mapValues { fromJsonElement(it.value) }
            is JsonArray -> element.map { fromJsonElement(it) }
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.boolean
                element.longOrNull != null -> element.long
                // 超出 Long 范围的纯整数（无小数点/指数）若退化为 Double 会丢精度，
                // 保留原始字符串以免静默损失；真正的浮点数才走 Double。
                isIntegerLiteral(element.content) -> element.content
                element.doubleOrNull != null -> element.double
                else -> element.content
            }
        }
    }

    private fun isIntegerLiteral(content: String): Boolean {
        val body = content.removePrefix("-")
        return body.isNotEmpty() && body.all { it.isDigit() }
    }
}
