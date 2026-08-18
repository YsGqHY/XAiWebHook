package kim.hhhhhy.x.webhook.scraper

import kim.hhhhhy.x.webhook.config.ModelPlazaConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpCookie
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

public data class ModelInfo(
    public val name: String
)

public data class GroupInfo(
    public val name: String
)

public data class GroupModelsRelation(
    public val groupName: String,
    public val modelNames: List<String>
)

public data class ModelGroupRelation(
    public val modelName: String,
    public val groupNames: List<String>
)

internal data class ModelPlazaPlatform(
    val platform: String,
    val modelNames: List<String>,
    val groupNames: List<String>
)

internal object ModelPlazaScraper {
    private const val API_PATH = "/api/v1/model-plaza"
    private val json = Json { ignoreUnknownKeys = true }
    private val geek2ApiFailoverHosts = listOf(
        "hk.geek2api.com",
        "hk2.geek2api.com",
        "hk3.geek2api.com",
        "hk4.geek2api.com",
        "hk5.geek2api.com"
    )

    public suspend fun queryGroupModels(
        config: ModelPlazaConfig,
        groupPattern: String
    ): List<GroupModelsRelation> {
        validateConfig(config)
        require(groupPattern.isNotBlank()) { "分组名不能为空" }

        WebHookDebug.log("[ModelPlaza] 通过 API 查询包含 '$groupPattern' 的分组及模型...")
        val relations = groupModelsByPattern(fetchModelPlaza(config), groupPattern)
        WebHookDebug.log("[ModelPlaza] 查询完成，共匹配 ${relations.size} 个分组")
        return relations
    }

    public suspend fun queryModelGroups(
        config: ModelPlazaConfig,
        modelPattern: String
    ): List<ModelGroupRelation> {
        validateConfig(config)
        require(modelPattern.isNotBlank()) { "模型名不能为空" }

        WebHookDebug.log("[ModelPlaza] 通过 API 查询包含 '$modelPattern' 的模型及分组...")
        val relations = modelGroupsByPattern(fetchModelPlaza(config), modelPattern)
        WebHookDebug.log("[ModelPlaza] 查询完成，共匹配 ${relations.size} 个模型")
        return relations
    }

    public suspend fun queryModelsByGroup(
        config: ModelPlazaConfig,
        groupPattern: String
    ): List<ModelInfo> {
        return queryGroupModels(config, groupPattern)
            .asSequence()
            .flatMap { it.modelNames.asSequence() }
            .distinct()
            .map(::ModelInfo)
            .toList()
    }

    public suspend fun queryGroupsByModel(
        config: ModelPlazaConfig,
        modelPattern: String
    ): List<GroupInfo> {
        return queryModelGroups(config, modelPattern)
            .asSequence()
            .flatMap { it.groupNames.asSequence() }
            .distinct()
            .map(::GroupInfo)
            .toList()
    }

    public fun fuzzyMatch(pattern: String, candidates: List<String>): List<String> {
        val normalizedPattern = pattern.trim().lowercase()
        if (normalizedPattern.isEmpty()) return candidates

        return candidates.filter { candidate ->
            candidate.trim().lowercase().contains(normalizedPattern)
        }
    }

    internal fun groupModelsByPattern(
        platforms: List<ModelPlazaPlatform>,
        groupPattern: String
    ): List<GroupModelsRelation> {
        val grouped = linkedMapOf<String, NamedValues>()
        for (platform in platforms) {
            for (groupName in fuzzyMatch(groupPattern, platform.groupNames)) {
                val normalizedName = normalizeName(groupName)
                val relation = grouped.getOrPut(normalizedName) {
                    NamedValues(groupName.trim(), linkedSetOf())
                }
                platform.modelNames
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .forEach(relation.values::add)
            }
        }
        return grouped.values.map { relation ->
            GroupModelsRelation(
                groupName = relation.name,
                modelNames = relation.values.toList()
            )
        }
    }

    internal fun modelGroupsByPattern(
        platforms: List<ModelPlazaPlatform>,
        modelPattern: String
    ): List<ModelGroupRelation> {
        val grouped = linkedMapOf<String, NamedValues>()
        for (platform in platforms) {
            for (modelName in fuzzyMatch(modelPattern, platform.modelNames)) {
                val normalizedName = normalizeName(modelName)
                val relation = grouped.getOrPut(normalizedName) {
                    NamedValues(modelName.trim(), linkedSetOf())
                }
                platform.groupNames
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .forEach(relation.values::add)
            }
        }
        return grouped.values.map { relation ->
            ModelGroupRelation(
                modelName = relation.name,
                groupNames = relation.values.toList()
            )
        }
    }

    internal fun modelsByGroup(
        platforms: List<ModelPlazaPlatform>,
        groupPattern: String
    ): List<String> {
        return groupModelsByPattern(platforms, groupPattern)
            .asSequence()
            .flatMap { it.modelNames.asSequence() }
            .distinct()
            .toList()
    }

    internal fun groupsByModel(
        platforms: List<ModelPlazaPlatform>,
        modelPattern: String
    ): List<String> {
        return modelGroupsByPattern(platforms, modelPattern)
            .asSequence()
            .flatMap { it.groupNames.asSequence() }
            .distinct()
            .toList()
    }

    internal fun parseModelPlazaResponse(body: String): List<ModelPlazaPlatform> {
        val root = try {
            json.parseToJsonElement(body)
        } catch (error: Exception) {
            error("Model Plaza API 响应不是有效 JSON: ${error.message}")
        }

        val payload = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val code = root["code"]?.jsonPrimitive?.contentOrNull
                if (code != null && code != "0") {
                    val message = root["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
                    error("Model Plaza API 返回错误: $message")
                }
                root["data"] as? JsonArray
                    ?: error("Model Plaza API 响应缺少 data 数组")
            }
            else -> error("Model Plaza API 响应格式不受支持")
        }

        return payload.mapNotNull { item ->
            val platformObject = item as? JsonObject ?: return@mapNotNull null
            val platform = platformObject.string("platform").orEmpty()
            val modelNames = platformObject.objectNames("models", "name")
            val groupNames = platformObject.objectNames("groups", "name")
            if (platform.isBlank() && modelNames.isEmpty() && groupNames.isEmpty()) {
                null
            } else {
                ModelPlazaPlatform(
                    platform = platform,
                    modelNames = modelNames,
                    groupNames = groupNames
                )
            }
        }
    }

    internal fun buildApiEndpoints(baseUrl: String): List<URI> {
        val configured = URI(baseUrl.trim())
        require(configured.scheme.equals("http", ignoreCase = true) || configured.scheme.equals("https", ignoreCase = true)) {
            "model_plaza.base_url 仅支持 http/https"
        }
        require(!configured.host.isNullOrBlank()) { "model_plaza.base_url 缺少主机名" }

        val configuredHost = configured.host.lowercase()
        val hosts = if (configuredHost in geek2ApiFailoverHosts) {
            listOf(configuredHost) + geek2ApiFailoverHosts.filterNot { it == configuredHost }
        } else {
            listOf(configuredHost)
        }

        return hosts.map { host ->
            URI(
                configured.scheme,
                configured.userInfo,
                host,
                configured.port,
                API_PATH,
                null,
                null
            )
        }
    }

    private suspend fun fetchModelPlaza(config: ModelPlazaConfig): List<ModelPlazaPlatform> {
        return withContext(Dispatchers.IO) {
            val auth = config.auth ?: error(
                "Model Plaza 需要认证。请配置 model_plaza.auth，并通过 CLI Bridge 在系统浏览器中登录。"
            )
            val cacheDir = getCacheDir()
            val cookieCache = createCookieCache(cacheDir)
            var accessToken = ModelPlazaAuthManager.getValidToken(auth, cacheDir)
            var refreshedAfterUnauthorized = false
            val endpoints = buildApiEndpoints(config.baseUrl)
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            var lastFailure: Throwable? = null

            for (endpoint in endpoints) {
                WebHookDebug.log("[ModelPlaza] 请求接口: $endpoint")
                var response = try {
                    requestModelPlaza(client, endpoint, accessToken, config.timeoutMillis, cookieCache)
                } catch (error: Exception) {
                    if (error is InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw error
                    }
                    lastFailure = error
                    WebHookDebug.log("[ModelPlaza] 线路请求失败 ${endpoint.host}: ${error.message}")
                    continue
                }

                if (response.status == 401 && !refreshedAfterUnauthorized) {
                    WebHookDebug.log("[ModelPlaza] 接口返回 401，刷新登录态后重试一次")
                    accessToken = ModelPlazaAuthManager.refreshAfterUnauthorized(auth, cacheDir)
                    refreshedAfterUnauthorized = true
                    response = requestModelPlaza(client, endpoint, accessToken, config.timeoutMillis, cookieCache)
                }

                when {
                    response.status in 200..299 -> return@withContext parseModelPlazaResponse(response.body)
                    response.status in setOf(502, 503, 504) -> {
                        lastFailure = IllegalStateException("${endpoint.host} 网关错误: HTTP ${response.status}")
                        WebHookDebug.log("[ModelPlaza] ${lastFailure.message}，尝试下一入口")
                    }
                    else -> error(
                        "Model Plaza API 请求失败: HTTP ${response.status}, ${extractErrorMessage(response.body)}"
                    )
                }
            }

            throw IllegalStateException(
                "所有 Model Plaza 入口均不可用${lastFailure?.message?.let { ": $it" }.orEmpty()}",
                lastFailure
            )
        }
    }

    private fun requestModelPlaza(
        client: HttpClient,
        endpoint: URI,
        accessToken: String,
        timeoutMillis: Long,
        cookieCache: ModelPlazaCookieCache?
    ): ModelPlazaHttpResponse {
        val requestBuilder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Accept", "application/json")
            .header("Accept-Language", "zh")
            .header("Authorization", "Bearer $accessToken")
            .header("X-User-UI-Request", "1")
            .header("User-Agent", "XAiWebHook/ModelPlaza")
            .header("Referer", "${endpoint.scheme}://${endpoint.authority}/model-plaza")
            .GET()

        val cookieHeader = cookieCache?.cookieHeader(endpoint).orEmpty()
        if (cookieHeader.isNotBlank()) {
            requestBuilder.header("Cookie", cookieHeader)
            WebHookDebug.log("[ModelPlaza] 已携带原缓存策略中的浏览器 Cookie")
        }

        val response = client.send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
        cookieCache?.storeResponseCookies(endpoint, response.headers().allValues("Set-Cookie"))
        return ModelPlazaHttpResponse(response.statusCode(), response.body())
    }

    private fun createCookieCache(cacheDir: File?): ModelPlazaCookieCache? {
        if (cacheDir == null) return null
        val primary = File(cacheDir, "playwright-storage-state.json")
        val legacy = File(cacheDir, "storage_state.json")
        val stateFile = if (!primary.exists() && legacy.isFile) legacy else primary
        return ModelPlazaCookieCache(stateFile.toPath())
    }

    private fun validateConfig(config: ModelPlazaConfig) {
        require(config.enabled) { "Model Plaza 功能未启用，请设置 model_plaza.enabled: true" }
        require(config.auth != null) {
            "Model Plaza 需要认证。请配置 model_plaza.auth，并通过 CLI Bridge 在系统浏览器中登录。"
        }
    }

    private fun JsonObject.string(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
    }

    private fun JsonObject.objectNames(arrayKey: String, nameKey: String): List<String> {
        val array = this[arrayKey] as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            (item as? JsonObject)?.string(nameKey)
        }.distinct()
    }

    private fun extractErrorMessage(body: String): String {
        return runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject
            root?.string("message")
                ?: (root?.get("error") as? JsonObject)?.string("message")
        }.getOrNull() ?: body.trim().take(200).ifBlank { "未知错误" }
    }

    private fun getCacheDir(): File? {
        return try {
            File(kim.hhhhhy.x.webhook.XAiWebHook.dataFolder, "model-plaza-cache").also(File::mkdirs)
        } catch (error: Exception) {
            WebHookDebug.log("[ModelPlaza] 无法创建缓存目录: ${error.message}")
            null
        }
    }

    private fun normalizeName(value: String): String {
        return value.trim().lowercase()
    }

    private data class NamedValues(
        val name: String,
        val values: LinkedHashSet<String>
    )

    private data class ModelPlazaHttpResponse(
        val status: Int,
        val body: String
    )
}

internal class ModelPlazaCookieCache(
    private val stateFile: Path?
) {
    fun cookieHeader(endpoint: URI, nowMillis: Long = System.currentTimeMillis()): String {
        if (stateFile == null) return ""
        return synchronized(fileLock) {
            try {
                val state = loadState() ?: return@synchronized ""
                val cookies = state["cookies"] as? JsonArray ?: return@synchronized ""
                cookies.mapNotNull { item ->
                    val cookie = item as? JsonObject ?: return@mapNotNull null
                    cookieHeaderEntry(cookie, endpoint, nowMillis)
                }.sortedByDescending(CookieHeaderEntry::pathLength)
                    .joinToString("; ") { it.value }
            } catch (error: Exception) {
                WebHookDebug.log("[ModelPlaza] 读取浏览器 Cookie 缓存失败: ${error.message}")
                ""
            }
        }
    }

    fun storeResponseCookies(
        endpoint: URI,
        setCookieHeaders: List<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): Unit {
        if (stateFile == null || setCookieHeaders.isEmpty()) return
        synchronized(fileLock) {
            try {
                val parsedCookies = setCookieHeaders.flatMap { header ->
                    runCatching { HttpCookie.parse(header).toList() }
                        .onFailure { error ->
                            WebHookDebug.log("[ModelPlaza] 忽略无法解析的 Set-Cookie: ${error.message}")
                        }
                        .getOrDefault(emptyList())
                }
                if (parsedCookies.isEmpty()) return@synchronized

                val state = loadState() ?: emptyStorageState()
                val cookies = (state["cookies"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .toMutableList()
                var changed = false

                for (responseCookie in parsedCookies) {
                    val domain = responseCookie.domain?.trim()?.ifBlank { null } ?: endpoint.host
                    val path = responseCookie.path?.trim()?.ifBlank { null } ?: "/"
                    val matchingCookie = cookies.firstOrNull { existing ->
                        existing.string("name") == responseCookie.name &&
                            existing.string("domain").orEmpty().equals(domain, ignoreCase = true) &&
                            existing.string("path").orEmpty().ifBlank { "/" } == path
                    }
                    val removed = cookies.removeAll { existing ->
                        existing.string("name") == responseCookie.name &&
                            existing.string("domain").orEmpty().equals(domain, ignoreCase = true) &&
                            existing.string("path").orEmpty().ifBlank { "/" } == path
                    }
                    if (responseCookie.maxAge == 0L) {
                        changed = changed || removed
                        continue
                    }

                    val expires = if (responseCookie.maxAge > 0L) {
                        nowMillis / 1000.0 + responseCookie.maxAge
                    } else {
                        -1.0
                    }
                    val sameSite = matchingCookie?.string("sameSite") ?: "Lax"
                    cookies += buildJsonObject {
                        put("name", responseCookie.name)
                        put("value", responseCookie.value)
                        put("domain", domain)
                        put("path", path)
                        put("expires", expires)
                        put("httpOnly", responseCookie.isHttpOnly)
                        put("secure", responseCookie.secure)
                        put("sameSite", sameSite)
                    }
                    changed = true
                }

                if (changed) {
                    writeState(JsonObject(state + ("cookies" to JsonArray(cookies))))
                    WebHookDebug.log("[ModelPlaza] 已按原缓存策略更新浏览器 Cookie")
                }
            } catch (error: Exception) {
                WebHookDebug.log("[ModelPlaza] 更新浏览器 Cookie 缓存失败: ${error.message}")
            }
        }
    }

    private fun cookieHeaderEntry(
        cookie: JsonObject,
        endpoint: URI,
        nowMillis: Long
    ): CookieHeaderEntry? {
        val name = cookie.string("name") ?: return null
        val value = cookie.string("value") ?: return null
        val rawDomain = cookie.string("domain") ?: return null
        val path = cookie.string("path") ?: "/"
        val expires = (cookie["expires"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: -1.0
        val secure = (cookie["secure"] as? JsonPrimitive)
            ?.contentOrNull
            ?.toBooleanStrictOrNull()
            ?: false
        val host = endpoint.host.lowercase()
        val normalizedDomain = rawDomain.trimStart('.').lowercase()
        val domainMatches = if (rawDomain.startsWith('.')) {
            host == normalizedDomain || host.endsWith(".$normalizedDomain")
        } else {
            host == normalizedDomain
        }
        val requestPath = endpoint.path.ifBlank { "/" }
        val pathMatches = requestPath == path || (
            requestPath.startsWith(path) &&
                (path.endsWith('/') || requestPath.getOrNull(path.length) == '/')
            )
        val notExpired = expires <= 0.0 || expires > nowMillis / 1000.0
        val schemeMatches = !secure || endpoint.scheme.equals("https", ignoreCase = true)
        if (!domainMatches || !pathMatches || !notExpired || !schemeMatches) return null
        return CookieHeaderEntry(path.length, "$name=$value")
    }

    private fun loadState(): JsonObject? {
        val path = stateFile ?: return null
        if (!Files.isRegularFile(path)) return null
        require(Files.size(path) <= MAX_CACHE_BYTES) { "Model Plaza storage state 缓存过大" }
        val root = Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)) as? JsonObject
            ?: error("Model Plaza storage state 不是有效 JSON 对象")
        require(root["cookies"] is JsonArray) { "Model Plaza storage state 缺少 cookies 数组" }
        require(root["origins"] is JsonArray) { "Model Plaza storage state 缺少 origins 数组" }
        return root
    }

    private fun emptyStorageState(): JsonObject {
        return buildJsonObject {
            put("cookies", buildJsonArray {})
            put("origins", buildJsonArray {})
        }
    }

    private fun writeState(state: JsonObject): Unit {
        val path = stateFile ?: return
        val bytes = state.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size.toLong() <= MAX_CACHE_BYTES) { "Model Plaza storage state 缓存过大" }
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, path.fileName.toString(), ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun JsonObject.string(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
    }

    private data class CookieHeaderEntry(
        val pathLength: Int,
        val value: String
    )

    private companion object {
        const val MAX_CACHE_BYTES: Long = 5L * 1_024L * 1_024L
        val fileLock: Any = Any()
    }
}
