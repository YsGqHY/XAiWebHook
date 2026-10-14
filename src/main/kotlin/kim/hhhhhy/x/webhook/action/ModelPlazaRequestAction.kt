package kim.hhhhhy.x.webhook.action

import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.ModelPlazaQueryConfig
import kim.hhhhhy.x.webhook.config.QueryFilterConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.ActionResult
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.scraper.ModelPlazaAuthManager
import kim.hhhhhy.x.webhook.scraper.ModelPlazaScraper
import kim.hhhhhy.x.webhook.template.TemplateEngine
import kim.hhhhhy.x.webhook.util.BlacklistConfig
import kim.hhhhhy.x.webhook.util.FilterChain
import kim.hhhhhy.x.webhook.util.FilterConfig
import kim.hhhhhy.x.webhook.util.FilterResult
import kim.hhhhhy.x.webhook.util.KeywordExtractionConfig
import kim.hhhhhy.x.webhook.util.KeywordExtractor
import kim.hhhhhy.x.webhook.util.LengthConfig
import kim.hhhhhy.x.webhook.util.PatternConfig
import kim.hhhhhy.x.webhook.util.WhitelistConfig
import kotlinx.coroutines.CancellationException
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.message.data.ForwardMessageBuilder
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.MessageChainBuilder
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource

internal object ModelPlazaRequestAction {
    private const val DEFAULT_SESSION_KEY = "model-plaza-request"
    private val DEFAULT_REQUEST_PREFIXES = listOf("请求", "request")
    // 页面选择器的默认值统一由浏览器采集器维护；这里只透传用户显式覆盖的值。
    private val SELECTOR_OVERRIDE_KEYS = listOf(
        "search_selector",
        "model_card_selector",
        "model_name_selector",
        "performance_selector",
        "sparkline_selector",
        "percentage_selector"
    )

    private val throughputRegex = Regex(
        "(?i)([-+]?\\d+(?:[.,]\\d+)?)\\s*(?:tokens?|tok|t)\\s*/\\s*s"
    )
    private val latencyMillisRegex = Regex("(?i)([-+]?\\d+(?:[.,]\\d+)?)\\s*ms\\b")
    private val latencySecondsRegex = Regex("(?i)([-+]?\\d+(?:[.,]\\d+)?)\\s*s(?:ec(?:ond)?s?)?\\b")
    private val numberRegex = Regex("[-+]?\\d+(?:[.,]\\d+)?")

    public suspend fun query(action: ActionConfig, context: ExecutionContext): ActionResult {
        val target = runCatching { WebHookActionExecutor.resolveScreenshotTarget(action, context) }
            .getOrElse {
                return ActionResult(action.type, success = false, message = "request target unavailable", status = 400)
            }
        val contact = runCatching { resolveContact(target) }
            .getOrElse {
                return ActionResult(action.type, success = false, message = "request target unavailable", status = 404)
            }
        val queryConfig = context.config.modelPlaza.queries.groups
        val failureMessage = actionMessage(action, "failure_message", queryConfig.responseFormat.failureMessage, context)
        val modelPlaza = context.config.modelPlaza
        if (!modelPlaza.enabled) {
            sendQuietly(contact, "Model Plaza 请求未启用：请在配置中设置 model_plaza.enabled: true。")
            return ActionResult(action.type, success = false, message = "model_plaza is disabled", status = 503)
        }
        if (!context.config.browser.enabled) {
            sendQuietly(contact, "Model Plaza 请求失败：浏览器动作未启用。")
            return ActionResult(action.type, success = false, message = "browser actions are disabled", status = 503)
        }
        if (modelPlaza.auth == null) {
            sendQuietly(contact, "Model Plaza 请求失败：未配置登录认证。")
            return ActionResult(action.type, success = false, message = "model_plaza auth is missing", status = 503)
        }

        val pattern = extractPattern(action, context, queryConfig)
        if (pattern.isBlank()) {
            sendQuietly(contact, "请在命令后填写模型关键词，例如：请求 gpt")
            return ActionResult(action.type, success = false, message = "failed to extract query pattern", status = 400)
        }

        val filterResult = FilterChain.validate(pattern, toFilterConfig(queryConfig.filters))
        if (filterResult is FilterResult.Reject) {
            sendQuietly(contact, filterResult.message)
            return ActionResult(action.type, success = false, message = "query rejected by filter", status = 400)
        }

        val pendingMessage = actionMessage(action, "pending_message", "正在查询模型请求记录，请稍候...", context)
        if (pendingMessage.isNotBlank()) sendQuietly(contact, pendingMessage)
        return try {
            val config = modelPlaza.copy(
                proxyUrl = ModelPlazaQueryAction.actionProxyUrl(action, context, modelPlaza.proxyUrl)
            )
            val modelNames = ModelPlazaScraper.queryModelGroups(config, pattern)
                .asSequence()
                .map { relation -> relation.modelName.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            if (modelNames.isEmpty()) {
                sendQuietly(contact, emptyMessage(action, context, pattern))
                return ActionResult(action.type, success = true, message = "no models found", status = 200)
            }

            val browserContext = context.copy(
                config = context.config.copy(
                    browser = context.config.browser.copy(
                        proxyUrl = config.proxyUrl.ifBlank { context.config.browser.proxyUrl }
                    )
                )
            )
            val auth = requireNotNull(config.auth)
            val initialToken = withContext(Dispatchers.IO) {
                ModelPlazaAuthManager.getValidToken(auth, modelPlazaCacheDir(), config.proxyUrl)
            }
            val collected = collectWithTokenRefresh(
                initialToken = initialToken,
                refreshToken = {
                    withContext(Dispatchers.IO) {
                        ModelPlazaAuthManager.refreshAfterUnauthorized(auth, modelPlazaCacheDir(), config.proxyUrl)
                    }
                }
            ) { accessToken ->
                WebPageScreenshotAction.collectModelPlazaRequests(
                    action = buildBrowserAction(action, context, config, pattern, accessToken),
                    context = browserContext,
                    modelNames = modelNames
                )
            }
            val results = alignResults(modelNames, collected)
            deliver(contact, results)
            WebHookDebug.log("[ModelPlaza] 请求完成，共 ${results.size} 个模型")
            ActionResult(action.type, success = true, message = "request completed", status = 200)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 认证/接口错误可能拼入响应体；日志只记录脱敏后的原因链，并保留堆栈帧（帧不含消息内容）便于排查。
            val summary = safeErrorSummary(error)
            XAiWebHook.logger.warning(
                "Model plaza request failed: $summary",
                IllegalStateException(summary).apply { stackTrace = error.stackTrace }
            )
            if (failureMessage.isNotBlank()) sendQuietly(contact, failureMessage)
            ActionResult(action.type, success = false, message = "model plaza request failed", status = 500)
        }
    }

    /**
     * 用初始 token 执行一次浏览器采集；若页面判定登录失效（401、跳转登录页等），刷新 token 后只重试一次。
     * 非认证错误与协程取消直接抛出，避免无意义的重复访问。
     */
    internal suspend fun <T> collectWithTokenRefresh(
        initialToken: String,
        refreshToken: suspend () -> String,
        collect: suspend (String) -> T
    ): T {
        return try {
            collect(initialToken)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!isBrowserAuthFailure(error)) throw error
            WebHookDebug.log("[ModelPlaza] 页面登录态失效，刷新 token 后重试一次")
            collect(refreshToken())
        }
    }

    private val secretKeyValuePattern = Regex(
        "(?i)(access_token|refresh_token|poll_secret|client_secret|password|secret|token)" +
            "(\"?\\s*[:=]\\s*\"?)([^\"\\s,}&]+)"
    )
    private val authorizationPattern = Regex("(?i)(authorization\"?\\s*[:=]\\s*\"?)([^\",\\n}]+)")
    private val bearerPattern = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+")
    private val jwtPattern = Regex("\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\b")

    /** 屏蔽错误文本中的 token、secret、Authorization 头与 JWT，供日志使用。 */
    internal fun redactSecrets(text: String): String {
        return text
            .replace(authorizationPattern) { match -> match.groupValues[1] + "<redacted>" }
            .replace(bearerPattern, "Bearer <redacted>")
            .replace(secretKeyValuePattern) { match -> match.groupValues[1] + match.groupValues[2] + "<redacted>" }
            .replace(jwtPattern, "<redacted-jwt>")
    }

    /** 沿原因链生成脱敏摘要，最多 6 层、每层 300 字符，避免把完整响应体写进日志。 */
    internal fun safeErrorSummary(error: Throwable): String {
        return generateSequence(error) { it.cause }
            .take(6)
            .map { item -> redactSecrets(item.message?.take(300) ?: item::class.simpleName.orEmpty()) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" <- ")
            .ifBlank { error::class.simpleName ?: "unknown error" }
    }

    /**
     * “请求”命令只使用自己的前缀和正则，避免 queries.groups 中为“分组”命令定制的正则误伤本命令；
     * 仍沿用该配置的 trim/lowercase 规则，与“分组”命令的模型关键词处理保持一致。
     */
    internal fun extractPattern(
        action: ActionConfig,
        context: ExecutionContext,
        queryConfig: ModelPlazaQueryConfig
    ): String {
        if (action.params.containsKey("query_pattern")) {
            return renderString(action.params["query_pattern"], context).trim()
        }
        val extraction = queryConfig.keywordExtraction
        val prefixes = toStringList(action.params["prefixes"]).ifEmpty { DEFAULT_REQUEST_PREFIXES }
        val captureGroup = action.params["capture_group"]?.toString()?.toIntOrNull()?.coerceIn(0, 20) ?: 1
        val requirePrefix = action.params["require_prefix_match"]?.toString()?.toBooleanStrictOrNull() ?: true
        return KeywordExtractor.extract(
            message = context.event?.messageText.orEmpty(),
            config = KeywordExtractionConfig(
                removePrefixes = prefixes,
                pattern = action.params["keyword_regex"]?.toString()?.ifBlank { null },
                captureGroup = captureGroup,
                trim = extraction.trim,
                toLowerCase = extraction.toLowerCase,
                requirePrefixMatch = requirePrefix
            )
        )
    }

    internal fun buildBrowserAction(
        action: ActionConfig,
        context: ExecutionContext,
        config: kim.hhhhhy.x.webhook.config.ModelPlazaConfig,
        pattern: String,
        accessToken: String
    ): ActionConfig {
        val pageUrl = actionParam(action, "page_url", context) ?: config.baseUrl
        require(isModelPlazaPage(pageUrl, config.baseUrl)) {
            "model-plaza request page_url must use the configured Model Plaza origin and /model-plaza path"
        }
        val auth = requireNotNull(config.auth)
        val pageUri = URI(pageUrl)
        val origin = originOf(pageUri)
        val timeoutMillis = (actionParam(action, "timeout_ms", context)?.toLongOrNull() ?: config.timeoutMillis)
            .coerceIn(100L, 300_000L)
        val params = linkedMapOf<String, Any?>(
            "page_url" to pageUrl,
            "search_pattern" to pattern,
            "session_key" to (actionParam(action, "session_key", context) ?: DEFAULT_SESSION_KEY),
            "auth" to mapOf(
                "token" to accessToken,
                "header_name" to "Authorization",
                "header_prefix" to "Bearer ",
                "header_hosts" to listOf(pageUri.host),
                // 前端要求 auth_token 与 auth_user 同时存在才视为已登录；用同一入口的资料接口补齐用户对象，
                // 只复用 API 查询已经取得的 token，不会再打开第二次 CLI Bridge 授权。
                "bootstrap" to mapOf(
                    "url" to URI(origin).resolve(URI(auth.profileUrl).rawPath).toString(),
                    "response_path" to "data",
                    "local_storage_key" to "auth_user"
                ),
                "local_storage" to mapOf(
                    "origin" to origin,
                    "key" to "auth_token",
                    "prefix" to "",
                    "refresh_token_key" to "refresh_token",
                    "expires_at_key" to "token_expires_at",
                    "user_key" to "auth_user"
                )
            ),
            "screenshot" to mapOf(
                "parts" to listOf(
                    mapOf(
                        "id" to "main",
                        "steps" to listOf(
                            mapOf("op" to "goto", "url" to pageUrl, "wait_until" to "domcontentloaded"),
                            mapOf("op" to "wait", "selector" to "main", "state" to "visible", "optional" to true)
                        ),
                        "selector" to "body",
                        "timeout_ms" to timeoutMillis
                    )
                )
            )
        )
        // 未覆盖的选择器与等待时间交给采集器内置默认值，避免默认值在两处维护后出现偏差。
        SELECTOR_OVERRIDE_KEYS.forEach { key ->
            actionParam(action, key, context)?.let { selector -> params[key] = selector }
        }
        actionParam(action, "search_wait_ms", context)?.toLongOrNull()?.let { waitMillis ->
            params["search_wait_ms"] = waitMillis.coerceIn(0L, 120_000L)
        }
        return ActionConfig(
            id = "${action.id ?: DEFAULT_SESSION_KEY}-browser",
            type = "send_webpage_screenshot",
            enabled = true,
            params = params
        )
    }

    internal fun emptyMessage(action: ActionConfig, context: ExecutionContext, query: String): String {
        val template = action.params["empty_message"]?.toString()?.takeIf { it.isNotBlank() }
            ?: "未找到包含“${'$'}{query}”的模型"
        return ModelPlazaFormatter.renderMessage(template, query, context)
    }

    private fun modelPlazaCacheDir(): File {
        return File(XAiWebHook.dataFolder, "model-plaza-cache").also(File::mkdirs)
    }

    internal fun isBrowserAuthFailure(error: Throwable): Boolean {
        val messages = generateSequence(error) { it.cause }
            .take(16)
            .map { it.message.orEmpty().lowercase(Locale.ROOT) }
            .toList()
        return messages.any { message ->
            "access token was rejected" in message ||
                "remained unauthorized" in message ||
                "redirected to the login page" in message ||
                Regex("\\bhttp 401\\b").containsMatchIn(message)
        }
    }

    /** 配置中的页面地址必须与 model_plaza.base_url 同源，且只能是不带查询参数的 /model-plaza。 */
    internal fun isModelPlazaPage(url: String, configuredBaseUrl: String): Boolean {
        val uri = parseModelPlazaUri(url, configuredBaseUrl) ?: return false
        return uri.query == null && uri.fragment == null
    }

    /** 运行中的页面可以追加查询参数或 hash，但不能离开同源的 /model-plaza 路径。 */
    internal fun isModelPlazaLocation(url: String, expectedPageUrl: String): Boolean {
        return parseModelPlazaUri(url, expectedPageUrl) != null
    }

    private fun parseModelPlazaUri(url: String, expectedUrl: String): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val expected = runCatching { URI(expectedUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        val valid = (scheme == "http" || scheme == "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            sameOrigin(uri, expected) &&
            path == "/model-plaza"
        return uri.takeIf { valid }
    }

    private fun sameOrigin(first: URI, second: URI): Boolean {
        return first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    /** 生成与浏览器 location.origin 一致的 origin：默认端口不写出，保证 localStorage 写入正确的源。 */
    private fun originOf(uri: URI): String {
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val defaultPort = if (scheme == "https") 443 else 80
        val port = effectivePort(uri).takeIf { it != defaultPort }?.let { ":$it" }.orEmpty()
        return "$scheme://${uri.host.lowercase(Locale.ROOT)}$port"
    }

    /**
     * 采集器已经把结果绑定到请求的模型名；这里只按模型名精确对齐并补齐缺失项，
     * 名称为空或不在请求列表中的结果一律丢弃，不会借给其它模型。
     */
    internal fun alignResults(
        modelNames: List<String>,
        collected: List<ModelPlazaBrowserResult>
    ): List<ModelPlazaBrowserResult> {
        val byName = linkedMapOf<String, ModelPlazaBrowserResult>()
        collected.forEach { result ->
            val key = result.modelName.trim().lowercase(Locale.ROOT)
            if (key.isNotEmpty()) byName.putIfAbsent(key, result)
        }
        return modelNames.map { modelName ->
            byName[modelName.trim().lowercase(Locale.ROOT)]?.copy(modelName = modelName)
                ?: ModelPlazaBrowserResult(modelName, null, null, null)
        }
    }

    private suspend fun deliver(contact: Contact, results: List<ModelPlazaBrowserResult>): Unit {
        contact.sendMessage(buildMessage(contact, results))
    }

    internal suspend fun buildMessage(
        contact: Contact,
        results: List<ModelPlazaBrowserResult>,
        upload: suspend (ByteArray) -> Image = { bytes ->
            bytes.toExternalResource("png").use { resource -> contact.uploadImage(resource) }
        }
    ): Message {
        require(results.isNotEmpty()) { "request results must not be empty" }
        val blocks = results.map { result ->
            val builder = MessageChainBuilder().append(PlainText(formatModelText(result) + "\n"))
            val bytes = result.screenshot
            val image = if (bytes == null || bytes.isEmpty()) {
                null
            } else {
                try {
                    upload(bytes)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    WebHookDebug.log("[ModelPlaza] 模型图片上传失败，保留该模型的文字结果")
                    null
                }
            }
            builder.append(image ?: PlainText("图片：暂无数据")).build()
        }
        if (blocks.size <= 3) {
            val builder = MessageChainBuilder()
            blocks.forEachIndexed { index, block ->
                if (index > 0) builder.append(PlainText("\n\n"))
                builder.append(block)
            }
            return builder.build()
        }
        val forward = ForwardMessageBuilder(contact)
        val time = (System.currentTimeMillis() / 1000L).toInt()
        blocks.forEach { block ->
            forward.add(contact.bot.id, "XAiWebHook", block, time)
        }
        return forward.build()
    }

    internal fun formatModelText(result: ModelPlazaBrowserResult): String {
        val throughput = normalizeThroughput(result.throughputText)
        val latency = normalizeLatency(result.latencyText)
        return "${result.modelName}\n" +
            "吞吐: ${throughput?.let { "${it}tok/s" } ?: "暂无数据"} " +
            "延迟: ${latency?.let { "${it}s" } ?: "暂无数据"}"
    }

    internal fun normalizeThroughput(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        val number = throughputRegex.find(text)?.groupValues?.getOrNull(1)
            ?: Regex("(?i)([-+]?\\d+(?:[.,]\\d+)?)\\s*tps\\b").find(text)?.groupValues?.getOrNull(1)
            ?: numberRegex.matchEntire(text)?.value
            ?: return null
        return normalizeNumber(number)
    }

    internal fun normalizeLatency(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        val millis = latencyMillisRegex.find(text)?.groupValues?.getOrNull(1)
        if (millis != null) {
            return normalizeNumber(millis)?.toBigDecimal()?.movePointLeft(3)
                ?.stripTrailingZeros()?.toPlainString()
        }
        val seconds = latencySecondsRegex.find(text)?.groupValues?.getOrNull(1)
            ?: numberRegex.matchEntire(text)?.value
            ?: return null
        return normalizeNumber(seconds)
    }

    private fun normalizeNumber(value: String): String? {
        return formatDecimal(parseNumber(value) ?: return null)
    }

    private fun parseNumber(value: String): Double? = value.replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }

    private fun formatDecimal(value: Double): String = BigDecimal.valueOf(value)
        .stripTrailingZeros()
        .toPlainString()

    private fun toFilterConfig(config: QueryFilterConfig?): FilterConfig {
        if (config == null) return FilterConfig()
        return FilterConfig(
            blacklist = config.blacklist?.let {
                BlacklistConfig(it.enabled, it.keywords, it.caseSensitive, it.rejectMessage)
            },
            whitelist = config.whitelist?.let {
                WhitelistConfig(it.enabled, it.keywords, it.caseSensitive, it.rejectMessage)
            },
            length = config.length?.let { LengthConfig(it.min, it.max, it.rejectMessage) },
            pattern = config.pattern?.let { PatternConfig(it.pattern, it.rejectMessage) }
        )
    }

    private fun actionMessage(
        action: ActionConfig,
        key: String,
        fallback: String,
        context: ExecutionContext
    ): String = if (action.params.containsKey(key)) {
        renderString(action.params[key], context)
    } else {
        fallback
    }

    private fun actionParam(action: ActionConfig, key: String, context: ExecutionContext): String? {
        if (!action.params.containsKey(key)) return null
        return renderString(action.params[key], context).trim().ifBlank { null }
    }

    private fun renderString(value: Any?, context: ExecutionContext): String {
        if (value == null) return ""
        return TemplateEngine.renderString(value.toString(), context)
    }

    private fun toStringList(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim()?.ifBlank { null } }
        null -> emptyList()
        else -> listOf(value.toString().trim()).filter { it.isNotBlank() }
    }

    private fun resolveContact(target: ScreenshotTarget): Contact {
        val bot = XAiWebHook.bot ?: error("no bot instance available")
        return when (target) {
            is ScreenshotTarget.Group -> bot.getGroup(target.id) ?: error("group not found")
            is ScreenshotTarget.Friend -> bot.getFriend(target.id) ?: error("friend not found")
        }
    }

    private suspend fun sendQuietly(contact: Contact, message: String): Unit {
        try {
            contact.sendMessage(PlainText(message))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            WebHookDebug.log("[ModelPlaza] 提示发送失败")
        }
    }
}
