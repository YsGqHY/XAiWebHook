package kim.hhhhhy.x.webhook.action

import com.microsoft.playwright.APIRequest
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.RequestOptions
import com.microsoft.playwright.options.WaitForSelectorState
import com.microsoft.playwright.options.WaitUntilState
import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.BrowserConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.template.TemplateEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Paths
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal object WebPageScreenshotAction {
    private const val MAX_STEPS = 32
    private const val MAX_SCREENSHOT_PARTS = 16
    private const val MAX_SCREENSHOT_PART_ID_LENGTH = 100
    private const val MAX_SCREENSHOT_GAP_PIXELS = 1_000
    private const val MAX_SCREENSHOT_DELAY_BEFORE_MILLIS = 300_000L
    private const val DEFAULT_SCREENSHOT_FONT_WAIT_TIMEOUT_MILLIS = 3_000L
    private const val MAX_SCREENSHOT_HIDE_SELECTORS = 32
    private const val MAX_SCREENSHOT_HIDE_SELECTOR_LENGTH = 500
    private const val DEFAULT_SCREENSHOT_MAX_RETRIES = 2
    private const val MAX_SCREENSHOT_RETRIES = 10
    private const val DEFAULT_SCREENSHOT_RETRY_DELAY_MILLIS = 1_000L
    private const val MAX_SCREENSHOT_RETRY_DELAY_MILLIS = 60_000L
    private const val PLAYWRIGHT_DISABLE_INTERNAL_FONT_WAIT = "PW_TEST_SCREENSHOT_NO_FONTS_READY"
    private val NAVIGATION_WAIT_UNTIL = setOf("commit", "domcontentloaded", "load", "networkidle")
    private val SCREENSHOT_HORIZONTAL_ALIGNMENTS = setOf("left", "center", "right")
    private val workerLock = Any()

    @Volatile
    private var workerRef: BrowserWorker? = null

    @Volatile
    var lastError: String? = null
        private set

    suspend fun capture(
        action: ActionConfig,
        context: ExecutionContext,
        environment: Map<String, String> = System.getenv()
    ): ByteArray {
        val browserConfig = context.config.browser
        check(browserConfig.enabled) { "browser actions are disabled" }
        val spec = parseSpec(action, context)
        var retriesUsed = 0
        while (true) {
            try {
                return worker().capture(browserConfig, spec, environment).also {
                    lastError = null
                }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                val retry = spec.retry
                val shouldRetry = retry.enabled &&
                    retriesUsed < retry.maxRetries &&
                    isRetryableCaptureError(error)
                if (!shouldRetry) {
                    lastError = error.message?.take(500) ?: error::class.simpleName
                    throw error
                }

                val failedAttempt = retriesUsed + 1
                retriesUsed++
                val totalAttempts = retry.maxRetries + 1
                val detail = summarizeRetryError(error)
                lastError = "attempt $failedAttempt/$totalAttempts failed; retrying: $detail".take(500)
                runCatching {
                    XAiWebHook.logger.warning(
                        "Webpage screenshot attempt $failedAttempt/$totalAttempts failed; " +
                            "retrying in ${retry.delayMillis}ms: $detail"
                    )
                }
                WebHookDebug.log(
                    "[XAiWebHook] [截图重试] 第 $failedAttempt/$totalAttempts 次尝试失败，" +
                        "${retry.delayMillis}ms 后重试：$detail"
                )
                if (retry.delayMillis > 0L) {
                    delay(retry.delayMillis)
                }
            }
        }
    }

    fun reset(): Unit {
        val worker = synchronized(workerLock) {
            workerRef.also { workerRef = null }
        }
        worker?.close()
        lastError = null
    }

    fun close(): Unit = reset()

    internal fun playwrightDriverEnvironment(): Map<String, String> = mapOf(
        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1",
        PLAYWRIGHT_DISABLE_INTERNAL_FONT_WAIT to "1"
    )

    internal fun parseSpec(action: ActionConfig, context: ExecutionContext): WebPageScreenshotSpec {
        val baseUrls = parseBaseUrls(action.params["base_urls"], context, context.config.browser.allowedHosts)
        val screenshotMap = action.params["screenshot"].asParameterMap()
        require(screenshotMap.isNotEmpty()) { "screenshot must be an object" }
        require(!screenshotMap.containsKey("prepend_url") && !screenshotMap.containsKey("prepend_selector")) {
            "screenshot.prepend_url/prepend_selector were replaced by screenshot.parts"
        }

        val defaultTimeoutMillis = renderLong(
            screenshotMap["timeout_ms"],
            context,
            context.config.browser.timeoutMillis
        ).coerceIn(100L, 300_000L)
        val defaultDelayBeforeMillis = renderLong(
            screenshotMap["delay_before_ms"],
            context,
            0L
        ).coerceIn(0L, MAX_SCREENSHOT_DELAY_BEFORE_MILLIS)
        val defaultFontWaitTimeoutMillis = renderLong(
            screenshotMap["font_wait_timeout_ms"],
            context,
            minOf(DEFAULT_SCREENSHOT_FONT_WAIT_TIMEOUT_MILLIS, defaultTimeoutMillis)
        ).coerceIn(0L, defaultTimeoutMillis)
        val defaultHideSelectors = parseScreenshotHideSelectors(
            screenshotMap["hide_selectors"],
            context,
            "screenshot.hide_selectors"
        )
        val layoutMap = screenshotMap["layout"].asParameterMap()
        val horizontalAlign = renderOptionalString(layoutMap["horizontal_align"], context)
            ?.lowercase(Locale.ROOT)
            ?: "center"
        require(horizontalAlign in SCREENSHOT_HORIZONTAL_ALIGNMENTS) {
            "screenshot.layout.horizontal_align must be left, center, or right"
        }
        val layout = BrowserScreenshotLayout(
            horizontalAlign = horizontalAlign,
            gapPixels = renderLong(layoutMap["gap_px"], context, 0L)
                .coerceIn(0L, MAX_SCREENSHOT_GAP_PIXELS.toLong())
                .toInt()
        )

        val screenshotParts = if (screenshotMap.containsKey("parts")) {
            val topLevelSteps = action.params["steps"]
            require(topLevelSteps == null || (topLevelSteps is List<*> && topLevelSteps.isEmpty())) {
                "action.steps cannot be combined with screenshot.parts; move steps into each part"
            }
            require(!screenshotMap.containsKey("selector")) {
                "screenshot.selector cannot be combined with screenshot.parts"
            }
            val rawParts = screenshotMap["parts"] as? List<*>
                ?: error("screenshot.parts must be a list")
            require(rawParts.isNotEmpty()) { "screenshot.parts must not be empty" }
            require(rawParts.size <= MAX_SCREENSHOT_PARTS) {
                "screenshot.parts must contain at most $MAX_SCREENSHOT_PARTS entries"
            }
            rawParts.mapIndexedNotNull { index, raw ->
                val map = raw.asParameterMap()
                require(map.isNotEmpty()) { "screenshot.parts[${index + 1}] must be an object" }
                parseScreenshotPart(
                    index = index,
                    map = map,
                    context = context,
                    defaultTimeoutMillis = defaultTimeoutMillis,
                    defaultDelayBeforeMillis = defaultDelayBeforeMillis,
                    defaultFontWaitTimeoutMillis = defaultFontWaitTimeoutMillis,
                    defaultHideSelectors = defaultHideSelectors
                )
            }
        } else {
            val steps = parseBrowserSteps(action.params["steps"], context, "steps")
            require(steps.isNotEmpty()) { "steps must not be empty" }
            listOf(
                BrowserScreenshotPart(
                    index = 1,
                    id = "main",
                    steps = steps,
                    selector = normalizeSelector(
                        renderRequiredString(screenshotMap["selector"], context, "screenshot.selector")
                    ),
                    timeoutMillis = defaultTimeoutMillis,
                    delayBeforeMillis = defaultDelayBeforeMillis,
                    fontWaitTimeoutMillis = defaultFontWaitTimeoutMillis,
                    hideSelectors = defaultHideSelectors
                )
            )
        }
        require(screenshotParts.isNotEmpty()) { "screenshot.parts must contain at least one enabled entry" }
        require(screenshotParts.map { part -> part.id }.distinct().size == screenshotParts.size) {
            "enabled screenshot.parts ids must be unique"
        }

        val retryMap = screenshotMap["retry"].asParameterMap()
        val screenshotRetry = if (retryMap.isEmpty()) {
            BrowserScreenshotRetry.disabled()
        } else {
            BrowserScreenshotRetry(
                enabled = renderBoolean(retryMap["enabled"], context, true),
                maxRetries = renderLong(
                    retryMap["max_retries"],
                    context,
                    DEFAULT_SCREENSHOT_MAX_RETRIES.toLong()
                ).coerceIn(0L, MAX_SCREENSHOT_RETRIES.toLong()).toInt(),
                delayMillis = renderLong(
                    retryMap["delay_ms"],
                    context,
                    DEFAULT_SCREENSHOT_RETRY_DELAY_MILLIS
                ).coerceIn(0L, MAX_SCREENSHOT_RETRY_DELAY_MILLIS)
            )
        }
        val sessionKey = renderOptionalString(action.params["session_key"], context)
            ?: action.id?.trim()?.ifBlank { null }
        val auth = parseAuth(action.params["auth"].asParameterMap(), context, baseUrls)
        if (auth?.login != null || auth?.cliBridge != null) {
            require(sessionKey != null) {
                "session auth requires session_key or a named action id"
            }
        }

        return WebPageScreenshotSpec(
            sessionKey = sessionKey,
            baseUrls = baseUrls,
            auth = auth,
            screenshotParts = screenshotParts,
            screenshotLayout = layout,
            retry = screenshotRetry
        )
    }

    private fun parseScreenshotPart(
        index: Int,
        map: Map<String, Any?>,
        context: ExecutionContext,
        defaultTimeoutMillis: Long,
        defaultDelayBeforeMillis: Long,
        defaultFontWaitTimeoutMillis: Long,
        defaultHideSelectors: List<String>
    ): BrowserScreenshotPart? {
        if (!renderBoolean(map["enabled"], context, true)) return null

        val field = "screenshot.parts[${index + 1}]"
        val id = renderOptionalString(map["id"], context) ?: "part-${index + 1}"
        require(id.length <= MAX_SCREENSHOT_PART_ID_LENGTH && id.none { it == '\r' || it == '\n' }) {
            "$field.id must be at most $MAX_SCREENSHOT_PART_ID_LENGTH characters without newlines"
        }
        val timeoutMillis = renderLong(map["timeout_ms"], context, defaultTimeoutMillis)
            .coerceIn(100L, 300_000L)
        val delayBeforeMillis = renderLong(map["delay_before_ms"], context, defaultDelayBeforeMillis)
            .coerceIn(0L, MAX_SCREENSHOT_DELAY_BEFORE_MILLIS)
        val fontWaitTimeoutMillis = renderLong(
            map["font_wait_timeout_ms"],
            context,
            minOf(defaultFontWaitTimeoutMillis, timeoutMillis)
        ).coerceIn(0L, timeoutMillis)
        val hideSelectors = if (map.containsKey("hide_selectors")) {
            parseScreenshotHideSelectors(map["hide_selectors"], context, "$field.hide_selectors")
        } else {
            defaultHideSelectors
        }
        val url = renderOptionalString(map["url"], context)
        val waitUntil = renderOptionalString(map["wait_until"], context)
            ?.lowercase(Locale.ROOT)
            ?: "commit"
        require(url != null || !map.containsKey("wait_until")) {
            "$field.wait_until requires url"
        }
        require(waitUntil in NAVIGATION_WAIT_UNTIL) {
            "$field.wait_until must be commit, domcontentloaded, load, or networkidle"
        }

        val initialSteps = if (url == null) {
            emptyList()
        } else {
            listOf(
                parseStep(
                    index = 0,
                    map = mapOf(
                        "op" to "goto",
                        "url" to url,
                        "wait_until" to waitUntil,
                        "timeout_ms" to timeoutMillis
                    ),
                    context = context
                )
            )
        }
        val customSteps = parseBrowserSteps(
            value = map["steps"],
            context = context,
            field = "$field.steps",
            indexOffset = initialSteps.size
        )
        val steps = initialSteps + customSteps
        require(steps.any { step -> step.op == "goto" }) {
            "$field must define url or contain a goto step"
        }

        return BrowserScreenshotPart(
            index = index + 1,
            id = id,
            steps = steps,
            selector = normalizeSelector(renderRequiredString(map["selector"], context, "$field.selector")),
            timeoutMillis = timeoutMillis,
            delayBeforeMillis = delayBeforeMillis,
            fontWaitTimeoutMillis = fontWaitTimeoutMillis,
            hideSelectors = hideSelectors
        )
    }

    private fun parseBrowserSteps(
        value: Any?,
        context: ExecutionContext,
        field: String,
        indexOffset: Int = 0
    ): List<BrowserStep> {
        val rawSteps: List<*> = when (value) {
            null -> emptyList<Any?>()
            is List<*> -> value
            else -> error("$field must be a list")
        }
        require(rawSteps.size + indexOffset <= MAX_STEPS) {
            "$field must contain at most ${MAX_STEPS - indexOffset} entries"
        }
        return rawSteps.mapIndexed { index, raw ->
            val map = raw.asParameterMap()
            require(map.isNotEmpty()) { "$field[${index + 1}] must be an object" }
            parseStep(index + indexOffset, map, context)
        }
    }

    private fun parseScreenshotHideSelectors(
        value: Any?,
        context: ExecutionContext,
        field: String
    ): List<String> {
        val selectors = renderStringList(value, context)
            .map(::normalizeScreenshotHideSelector)
            .distinct()
        require(selectors.size <= MAX_SCREENSHOT_HIDE_SELECTORS) {
            "$field must contain at most $MAX_SCREENSHOT_HIDE_SELECTORS entries"
        }
        return selectors
    }

    internal fun validateNavigationUrl(url: String, allowedHosts: List<String>): URI {
        val uri = runCatching { URI(url) }.getOrElse { error("invalid navigation URL") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "navigation URL must use http or https" }
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            ?: error("navigation URL host is required")
        require(allowedHosts.isNotEmpty()) { "browser.allowed_hosts must not be empty" }
        require(allowedHosts.any { pattern -> hostMatches(host, pattern) }) {
            "navigation host is not allowed: $host"
        }
        return uri
    }

    internal fun shouldInjectHeader(url: String, allowedHosts: List<String>): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return allowedHosts.any { pattern -> hostMatches(host, pattern) }
    }

    internal fun rewriteUrlForBaseUrl(url: String, baseUrls: List<String>, baseUrl: String): String {
        if (baseUrls.isEmpty()) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val origin = uriOrigin(uri) ?: return url
        if (origin !in baseUrls) return url
        val baseUri = URI(baseUrl)
        return buildString {
            append(requireNotNull(uriOrigin(baseUri)) { "base_url must be an http or https origin" })
            uri.rawPath?.takeIf { it.isNotEmpty() }?.let { path -> append(path) }
            uri.rawQuery?.let { query -> append('?').append(query) }
            uri.rawFragment?.let { fragment -> append('#').append(fragment) }
        }
    }

    internal fun normalizeSelector(selector: String): String {
        val normalized = selector.trim()
        require(normalized.isNotEmpty()) { "selector must not be blank" }
        return if (normalized.startsWith("/") || normalized.startsWith("(")) {
            "xpath=$normalized"
        } else {
            normalized
        }
    }

    internal fun normalizeScreenshotHideSelector(selector: String): String {
        val normalized = selector.trim()
        require(normalized.isNotEmpty()) { "screenshot.hide_selectors entries must not be blank" }
        require(normalized.length <= MAX_SCREENSHOT_HIDE_SELECTOR_LENGTH) {
            "screenshot.hide_selectors entries must be at most $MAX_SCREENSHOT_HIDE_SELECTOR_LENGTH characters"
        }
        require(normalized.none { it in "{};\r\n\u0000" } && "/*" !in normalized && "*/" !in normalized) {
            "screenshot.hide_selectors contains unsafe CSS syntax"
        }
        return normalized
    }

    internal fun buildScreenshotStyle(hideSelectors: List<String>): String? {
        return hideSelectors.takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { selector -> "$selector { visibility: hidden !important; }" }
    }

    internal fun combineScreenshotsVertically(
        parts: List<ByteArray>,
        layout: BrowserScreenshotLayout = BrowserScreenshotLayout.default()
    ): ByteArray {
        require(parts.isNotEmpty()) { "screenshot parts must not be empty" }
        if (parts.size == 1) return parts.single()

        val images = parts.mapIndexed { index, bytes ->
            ByteArrayInputStream(bytes).use { input ->
                requireNotNull(ImageIO.read(input)) {
                    "screenshot part ${index + 1} is not a supported image"
                }
            }
        }
        val width = images.maxOf { image -> image.width }
        val height = images.fold(0L) { total, image -> total + image.height } +
            layout.gapPixels.toLong() * (images.size - 1)
        require(width > 0 && height in 1L..Int.MAX_VALUE.toLong()) {
            "combined screenshot dimensions are invalid"
        }

        val combined = BufferedImage(width, height.toInt(), BufferedImage.TYPE_INT_ARGB)
        val graphics = combined.createGraphics()
        try {
            var y = 0
            images.forEachIndexed { index, image ->
                val x = when (layout.horizontalAlign) {
                    "left" -> 0
                    "right" -> width - image.width
                    else -> (width - image.width) / 2
                }
                graphics.drawImage(image, x, y, null)
                y += image.height
                if (index < images.lastIndex) y += layout.gapPixels
            }
        } finally {
            graphics.dispose()
        }

        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(combined, "png", output)) { "PNG image writer is unavailable" }
            output.toByteArray()
        }
    }

    internal fun isRetryableCaptureError(error: Throwable): Boolean {
        val chain = throwableChain(error)
        val operationIndex = chain.indexOfFirst { item ->
            val message = item.message.orEmpty()
            message.startsWith("browser step ") || message.startsWith("browser screenshot ")
        }
        if (operationIndex < 0) return false

        val operationCauses = chain.drop(operationIndex + 1).dropWhile { item ->
            val message = item.message.orEmpty()
            message.startsWith("browser step ") || message.startsWith("browser screenshot ")
        }
        if (operationCauses.any {
                it is IllegalArgumentException ||
                    (it is IllegalStateException && it !is CancellationException) ||
                    it is IllegalAccessException
            }
        ) {
            return false
        }
        if (operationCauses.any { it is TimeoutError || it is CancellationException }) return true
        if (operationCauses.none { it is PlaywrightException }) return false

        val combinedMessage = operationCauses
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase(Locale.ROOT)
        return listOf(
            "net::err_",
            "econnreset",
            "etimedout",
            "connection reset",
            "connection refused",
            "connection closed",
            "socket hang up",
            "target closed",
            "has been closed",
            "page closed",
            "browser closed",
            "context closed",
            "frame was detached",
            "execution context was destroyed",
            "cancelled",
            "canceled",
            "crash"
        ).any { marker -> marker in combinedMessage }
    }

    private fun summarizeRetryError(error: Throwable): String {
        return (error.message ?: error::class.simpleName ?: "unknown error")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(300)
    }

    private fun throwableChain(error: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size < 16 && seen.add(current)) {
            chain += current
            current = current.cause
        }
        return chain
    }

    internal fun resolveFillValue(step: BrowserStep, environment: Map<String, String>): String {
        step.value?.let { return it }
        val variable = step.valueEnv ?: error("fill value source is required")
        return environment[variable] ?: error("environment variable is not set: $variable")
    }

    internal fun resolveAuth(auth: BrowserAuthSpec, environment: Map<String, String>): ResolvedBrowserAuth {
        auth.login?.let { login ->
            val email = login.email ?: login.emailEnv?.let { name ->
                environment[name]?.trim()?.ifBlank { null }
                    ?: error("environment variable is not set: $name")
            } ?: error("credential login email source is missing")
            val password = login.password ?: login.passwordEnv?.let { name ->
                environment[name]
                    ?: error("environment variable is not set: $name")
            } ?: error("credential login password source is missing")
            require(password.isNotEmpty()) { "credential login password must not be empty" }
            val totpSecret = login.totpSecretEnv?.let { name ->
                environment[name]?.trim()?.ifBlank { null }
            }
            val totpCode = login.totpCodeEnv?.let { name ->
                environment[name]?.trim()?.ifBlank { null }
            }
            return ResolvedBrowserAuth(
                spec = auth,
                credentials = ResolvedBrowserCredentials(
                    email = email,
                    password = password,
                    totpSecret = totpSecret,
                    totpCode = totpCode
                )
            )
        }
        if (auth.cliBridge != null) {
            return ResolvedBrowserAuth(spec = auth)
        }

        val configuredToken = auth.token ?: auth.tokenEnv?.let(environment::get)
            ?: error("environment variable is not set: ${auth.tokenEnv}")
        val token = normalizeAuthToken(configuredToken, auth.headerPrefix)
        validateJwtExpiration(token)
        return ResolvedBrowserAuth(spec = auth, token = token)
    }

    internal fun normalizeAuthToken(rawToken: String, headerPrefix: String): String {
        val trimmed = rawToken.trim()
        val normalized = if (
            headerPrefix.isNotEmpty() &&
            trimmed.startsWith(headerPrefix, ignoreCase = true)
        ) {
            trimmed.substring(headerPrefix.length).trimStart()
        } else {
            trimmed
        }
        require(normalized.isNotEmpty()) { "browser auth token must not be empty" }
        return normalized
    }

    internal fun buildStorageState(auth: ResolvedBrowserAuth): String {
        val localStorage = auth.spec.localStorage
        return buildJsonObject {
            put("cookies", buildJsonArray {})
            put("origins", buildJsonArray {
                if (localStorage != null) {
                    val entries = authStorageEntries(auth)
                    add(buildJsonObject {
                        put("origin", localStorage.origin)
                        put("localStorage", buildJsonArray {
                            entries.forEach { (name, value) ->
                                add(buildJsonObject {
                                    put("name", name)
                                    put("value", value)
                                })
                            }
                        })
                    })
                }
            })
        }.toString()
    }

    internal fun mergeStorageState(storageState: String, auth: ResolvedBrowserAuth): String {
        val root = runCatching { Json.parseToJsonElement(storageState) as? JsonObject }
            .getOrNull()
            ?: error("browser storage state is not valid JSON")
        val localStorage = auth.spec.localStorage ?: return root.toString()
        val entries = authStorageEntries(auth)
        if (entries.isEmpty()) return root.toString()

        val origins = root["origins"] as? JsonArray ?: buildJsonArray {}
        var merged = false
        val updatedOrigins = buildJsonArray {
            origins.forEach { element ->
                val origin = element as? JsonObject
                val originValue = (origin?.get("origin") as? JsonPrimitive)?.contentOrNull
                if (origin != null && originValue == localStorage.origin) {
                    merged = true
                    add(mergeOriginLocalStorage(origin, entries))
                } else {
                    add(element)
                }
            }
            if (!merged) {
                add(buildOriginStorage(localStorage.origin, entries))
            }
        }
        return JsonObject(root + ("origins" to updatedOrigins)).toString()
    }

    internal fun readStorageStateLocalStorage(storageState: String, origin: String): Map<String, String> {
        val root = runCatching { Json.parseToJsonElement(storageState) as? JsonObject }
            .getOrNull()
            ?: return emptyMap()
        val origins = root["origins"] as? JsonArray ?: return emptyMap()
        val matchingOrigin = origins.mapNotNull { it as? JsonObject }.firstOrNull { item ->
            (item["origin"] as? JsonPrimitive)?.contentOrNull == origin
        } ?: return emptyMap()
        val entries = matchingOrigin["localStorage"] as? JsonArray ?: return emptyMap()
        return entries.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val value = (item["value"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            name to value
        }.toMap()
    }

    internal fun restoreCachedSessionAuth(
        auth: ResolvedBrowserAuth,
        cached: CachedBrowserSession
    ): ResolvedBrowserAuth? {
        if (auth.spec.login == null && auth.spec.cliBridge == null) return null
        val localStorage = auth.spec.localStorage ?: return null
        val values = readStorageStateLocalStorage(cached.storageState, localStorage.origin)
        val accessToken = values[localStorage.key]?.trim()?.ifBlank { null } ?: return null
        val refreshToken = values[localStorage.refreshTokenKey]?.trim()?.ifBlank { null }
        val expiresAtMillis = values[localStorage.expiresAtKey]?.toLongOrNull() ?: return null
        val user = values[localStorage.userKey]?.let { raw ->
            runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        } ?: return null
        return withTokenPair(
            auth,
            BrowserTokenPair(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMillis = expiresAtMillis,
                tokenType = cached.tokenType,
                user = user
            )
        )
    }

    internal fun withTokenPair(auth: ResolvedBrowserAuth, tokenPair: BrowserTokenPair): ResolvedBrowserAuth {
        val localStorage = auth.spec.localStorage
            ?: error("session auth requires auth.local_storage")
        val storage = linkedMapOf<String, String>()
        tokenPair.refreshToken?.let { storage[localStorage.refreshTokenKey] = it }
        tokenPair.expiresAtMillis?.let { storage[localStorage.expiresAtKey] = it.toString() }
        storage[localStorage.userKey] = tokenPair.user.toString()
        return auth.copy(
            token = tokenPair.accessToken,
            tokenPair = tokenPair,
            additionalLocalStorage = storage
        )
    }

    private fun authStorageEntries(auth: ResolvedBrowserAuth): LinkedHashMap<String, String> {
        val localStorage = auth.spec.localStorage ?: return linkedMapOf()
        return linkedMapOf<String, String>().apply {
            auth.token?.let { token ->
                put(localStorage.key, localStorage.prefix + token)
            }
            putAll(auth.additionalLocalStorage)
        }
    }

    private fun mergeOriginLocalStorage(
        origin: JsonObject,
        updates: Map<String, String>
    ): JsonObject {
        val entries = linkedMapOf<String, String>()
        (origin["localStorage"] as? JsonArray).orEmpty().forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val value = (item["value"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            entries[name] = value
        }
        entries.putAll(updates)
        return JsonObject(origin + ("localStorage" to buildLocalStorageArray(entries)))
    }

    private fun buildOriginStorage(origin: String, entries: Map<String, String>): JsonObject {
        return buildJsonObject {
            put("origin", origin)
            put("localStorage", buildLocalStorageArray(entries))
        }
    }

    private fun buildLocalStorageArray(entries: Map<String, String>): JsonArray {
        return buildJsonArray {
            entries.forEach { (name, value) ->
                add(buildJsonObject {
                    put("name", name)
                    put("value", value)
                })
            }
        }
    }

    internal fun extractBootstrapStorageValue(responseBody: String, responsePath: List<String>): String {
        val root = try {
            Json.parseToJsonElement(responseBody)
        } catch (error: Throwable) {
            throw IllegalStateException("auth bootstrap response is not valid JSON", error)
        }
        val value = responsePath.fold(root as JsonElement?) { current, segment ->
            (current as? JsonObject)?.get(segment)
        } ?: throw IllegalStateException(
            "auth bootstrap response path not found: ${responsePath.joinToString(".")}"
        )
        if (value is JsonNull) {
            throw IllegalStateException(
                "auth bootstrap response path is null: ${responsePath.joinToString(".")}"
            )
        }
        return value.toString()
    }

    internal fun validateScreenshotSize(size: Int, maxBytes: Long): Unit {
        require(size.toLong() <= maxBytes) { "screenshot exceeds browser.max_screenshot_bytes" }
    }

    private fun validateJwtExpiration(token: String): Unit {
        val parts = token.split('.')
        if (parts.size != 3) return
        val payload = runCatching {
            val decoded = Base64.getUrlDecoder().decode(parts[1])
            Json.parseToJsonElement(decoded.toString(Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return
        val expiresAt = (payload["exp"] as? JsonPrimitive)?.longOrNull ?: return
        require(expiresAt > Instant.now().epochSecond) {
            "browser auth token expired at ${Instant.ofEpochSecond(expiresAt)}"
        }
    }

    private fun worker(): BrowserWorker {
        workerRef?.let { return it }
        return synchronized(workerLock) {
            workerRef ?: BrowserWorker().also { workerRef = it }
        }
    }

    private fun parseStep(index: Int, map: Map<String, Any?>, context: ExecutionContext): BrowserStep {
        val op = renderRequiredString(map["op"], context, "step ${index + 1}.op").lowercase(Locale.ROOT)
        require(op in setOf("goto", "fill", "click", "wait", "wait_url")) {
            "unsupported browser step op: $op"
        }
        val timeoutMillis = renderOptionalLong(map["timeout_ms"], context)?.coerceIn(100L, 300_000L)
        val optional = renderBoolean(map["optional"], context, false)
        val selector = renderOptionalString(map["selector"], context)?.let(::normalizeSelector)
        val url = renderOptionalString(map["url"], context)
        val value = map["value"]?.let { TemplateEngine.renderString(it.toString(), context) }
        val valueEnv = renderOptionalString(map["value_env"], context)
        val state = renderOptionalString(map["state"], context)?.lowercase(Locale.ROOT) ?: "visible"
        val waitUntil = renderOptionalString(map["wait_until"], context)?.lowercase(Locale.ROOT) ?: "commit"

        when (op) {
            "goto", "wait_url" -> require(!url.isNullOrBlank()) { "step ${index + 1}.url is required" }
            "fill" -> {
                require(!selector.isNullOrBlank()) { "step ${index + 1}.selector is required" }
                require((value == null) xor (valueEnv == null)) {
                    "step ${index + 1} fill requires exactly one of value or value_env"
                }
            }
            "click", "wait" -> require(!selector.isNullOrBlank()) {
                "step ${index + 1}.selector is required"
            }
        }
        if (op == "wait") {
            require(state in setOf("visible", "attached", "hidden", "detached")) {
                "unsupported wait state: $state"
            }
        }
        if (op == "goto") {
            require(waitUntil in NAVIGATION_WAIT_UNTIL) {
                "unsupported goto wait_until: $waitUntil"
            }
        }

        return BrowserStep(
            index = index + 1,
            op = op,
            selector = selector,
            url = url,
            value = value,
            valueEnv = valueEnv,
            optional = optional,
            timeoutMillis = timeoutMillis,
            state = state,
            waitUntil = waitUntil
        )
    }

    private fun parseAuth(
        map: Map<String, Any?>,
        context: ExecutionContext,
        baseUrls: List<String>
    ): BrowserAuthSpec? {
        if (map.isEmpty()) return null

        val token = map["token"]?.let { TemplateEngine.renderString(it.toString(), context) }
        val tokenEnv = renderOptionalString(map["token_env"], context)
        val login = parseLoginAuth(map["login"].asParameterMap(), context)
        val cliBridge = parseCliBridgeAuth(map["cli_bridge"].asParameterMap(), context)
        val hasTokenSource = token != null || tokenEnv != null
        val sourceCount = listOf(hasTokenSource, login != null, cliBridge != null).count { it }
        require(sourceCount == 1) {
            "auth requires exactly one of token/token_env, login, or cli_bridge"
        }
        if (hasTokenSource) {
            require((token == null) xor (tokenEnv == null)) {
                "auth requires exactly one of token or token_env"
            }
        }

        val headerName = if (map.containsKey("header_name")) {
            renderOptionalString(map["header_name"], context)
        } else if (login == null && cliBridge == null) {
            "Authorization"
        } else {
            null
        }
        val headerPrefix = renderPreservedString(map["header_prefix"], context) ?: "Bearer "
        val headerHosts = renderStringList(map["header_hosts"], context)
            .ifEmpty { context.config.browser.allowedHosts }
            .map { it.lowercase(Locale.ROOT).trimEnd('.') }
            .distinct()
        if (headerName != null) {
            require(Regex("""^[!#$%&'*+.^_`|~0-9A-Za-z-]+$""").matches(headerName)) {
                "auth.header_name is invalid"
            }
            require(headerHosts.isNotEmpty()) { "auth.header_hosts must not be empty" }
            require(!headerPrefix.contains('\r') && !headerPrefix.contains('\n')) {
                "auth.header_prefix must not contain line breaks"
            }
        }

        val localStorageMap = map["local_storage"].asParameterMap()
        val localStorage = if (localStorageMap.isEmpty()) {
            null
        } else {
            val origin = normalizeOrigin(
                renderRequiredString(localStorageMap["origin"], context, "auth.local_storage.origin"),
                context.config.browser.allowedHosts
            )
            val key = renderRequiredString(localStorageMap["key"], context, "auth.local_storage.key")
            val prefix = renderPreservedString(localStorageMap["prefix"], context).orEmpty()
            BrowserLocalStorageAuth(
                origin = origin,
                key = key,
                prefix = prefix,
                refreshTokenKey = renderOptionalString(localStorageMap["refresh_token_key"], context)
                    ?: "refresh_token",
                expiresAtKey = renderOptionalString(localStorageMap["expires_at_key"], context)
                    ?: "token_expires_at",
                userKey = renderOptionalString(localStorageMap["user_key"], context)
                    ?: "auth_user"
            )
        }

        val cookieMap = map["cookie"].asParameterMap()
        val cookie = if (cookieMap.isEmpty()) {
            null
        } else {
            val name = renderRequiredString(cookieMap["name"], context, "auth.cookie.name")
            require(Regex("""^[^\s;=]+$""").matches(name)) { "auth.cookie.name is invalid" }
            val url = renderRequiredString(cookieMap["url"], context, "auth.cookie.url")
            validateNavigationUrl(url, context.config.browser.allowedHosts)
            val prefix = renderPreservedString(cookieMap["prefix"], context).orEmpty()
            BrowserCookieAuth(name = name, url = url, prefix = prefix)
        }

        val bootstrapMap = map["bootstrap"].asParameterMap()
        val bootstrap = if (bootstrapMap.isEmpty()) {
            null
        } else {
            require(login == null && cliBridge == null) {
                "auth.bootstrap is only supported with token authentication"
            }
            val url = renderRequiredString(bootstrapMap["url"], context, "auth.bootstrap.url")
            validateNavigationUrl(url, context.config.browser.allowedHosts)
            require(headerName != null) { "auth.bootstrap requires auth.header_name" }
            require(shouldInjectHeader(url, headerHosts)) {
                "auth.bootstrap.url host must be included in auth.header_hosts"
            }
            require(localStorage != null) { "auth.bootstrap requires auth.local_storage" }
            val responsePath = parseResponsePath(
                renderOptionalString(bootstrapMap["response_path"], context) ?: "data"
            )
            val localStorageKey = renderOptionalString(bootstrapMap["local_storage_key"], context)
                ?: "auth_user"
            require(localStorageKey != localStorage.key) {
                "auth.bootstrap.local_storage_key must differ from auth.local_storage.key"
            }
            val expectedStatuses = renderIntList(bootstrapMap["expected_status"], context)
                .ifEmpty { listOf(200) }
                .onEach { status ->
                    require(status in 100..599) { "auth.bootstrap.expected_status contains an invalid status" }
                }
                .toSet()
            BrowserAuthBootstrap(
                url = url,
                responsePath = responsePath,
                localStorageKey = localStorageKey,
                expectedStatuses = expectedStatuses
            )
        }

        if (login != null || cliBridge != null) {
            val sourceName = if (login != null) "auth.login" else "auth.cli_bridge"
            require(localStorage != null) { "$sourceName requires auth.local_storage" }
            require(localStorage.prefix.isEmpty()) {
                "$sourceName requires auth.local_storage.prefix to be empty"
            }
            require(
                setOf(
                    localStorage.key,
                    localStorage.refreshTokenKey,
                    localStorage.expiresAtKey,
                    localStorage.userKey
                ).size == 4
            ) { "$sourceName local_storage keys must be distinct" }
        }
        require(headerName != null || localStorage != null || cookie != null) {
            "auth must enable a header, local_storage, or cookie target"
        }
        return BrowserAuthSpec(
            token = token,
            tokenEnv = tokenEnv,
            headerName = headerName,
            headerPrefix = headerPrefix,
            headerHosts = headerHosts,
            localStorage = localStorage,
            cookie = cookie,
            bootstrap = bootstrap,
            login = login,
            cliBridge = cliBridge,
            baseUrls = baseUrls
        )
    }

    private fun parseLoginAuth(
        map: Map<String, Any?>,
        context: ExecutionContext
    ): BrowserLoginAuth? {
        if (map.isEmpty()) return null
        val url = renderRequiredString(map["url"], context, "auth.login.url")
        val twoFactorUrl = renderRequiredString(
            map["two_factor_url"],
            context,
            "auth.login.two_factor_url"
        )
        val refreshUrl = renderRequiredString(map["refresh_url"], context, "auth.login.refresh_url")
        listOf(url, twoFactorUrl, refreshUrl).forEach { endpoint ->
            validateNavigationUrl(endpoint, context.config.browser.allowedHosts)
        }
        val email = renderOptionalString(map["email"], context)
        val emailEnv = renderOptionalString(map["email_env"], context)
        require((email == null) xor (emailEnv == null)) {
            "auth.login requires exactly one of email or email_env"
        }
        val password = if (map.containsKey("password")) {
            renderPreservedString(map["password"], context)
        } else {
            null
        }
        val passwordEnv = renderOptionalString(map["password_env"], context)
        require((password == null) xor (passwordEnv == null)) {
            "auth.login requires exactly one of password or password_env"
        }
        require(password == null || password.isNotEmpty()) {
            "auth.login.password must not be empty"
        }
        val totpSecretEnv = renderOptionalString(map["totp_secret_env"], context)
        val totpCodeEnv = renderOptionalString(map["totp_code_env"], context)
        require(totpSecretEnv == null || totpCodeEnv == null) {
            "auth.login.totp_secret_env and totp_code_env cannot both be configured"
        }
        return BrowserLoginAuth(
            url = url,
            email = email,
            emailEnv = emailEnv,
            password = password,
            passwordEnv = passwordEnv,
            twoFactorUrl = twoFactorUrl,
            totpSecretEnv = totpSecretEnv,
            totpCodeEnv = totpCodeEnv,
            refreshUrl = refreshUrl,
            refreshBeforeExpirySeconds = renderLong(
                map["refresh_before_expiry_seconds"],
                context,
                60L
            ).coerceIn(0L, 3_600L),
            retryCooldownMillis = renderLong(
                map["retry_cooldown_ms"],
                context,
                60_000L
            ).coerceIn(1_000L, 300_000L)
        )
    }

    private fun parseCliBridgeAuth(
        map: Map<String, Any?>,
        context: ExecutionContext
    ): BrowserCliBridgeAuth? {
        if (map.isEmpty()) return null
        val startUrl = renderRequiredString(map["start_url"], context, "auth.cli_bridge.start_url")
        val browserUrl = renderRequiredString(map["browser_url"], context, "auth.cli_bridge.browser_url")
        val pollUrl = renderRequiredString(map["poll_url"], context, "auth.cli_bridge.poll_url")
        val profileUrl = renderRequiredString(map["profile_url"], context, "auth.cli_bridge.profile_url")
        val refreshUrl = renderRequiredString(map["refresh_url"], context, "auth.cli_bridge.refresh_url")
        listOf(startUrl, browserUrl, pollUrl, profileUrl, refreshUrl).forEach { endpoint ->
            validateNavigationUrl(endpoint, context.config.browser.allowedHosts)
        }
        val browserUri = URI(browserUrl)
        require(browserUri.fragment == null) { "auth.cli_bridge.browser_url must not contain a fragment" }
        require(!browserUri.query.orEmpty().contains("bridge_id=")) {
            "auth.cli_bridge.browser_url must not contain bridge_id"
        }
        return BrowserCliBridgeAuth(
            startUrl = startUrl,
            browserUrl = browserUrl,
            pollUrl = pollUrl,
            profileUrl = profileUrl,
            refreshUrl = refreshUrl,
            pollIntervalMillis = renderLong(
                map["poll_interval_ms"],
                context,
                2_500L
            ).coerceIn(2_000L, 30_000L),
            maxWaitMillis = renderLong(
                map["max_wait_ms"],
                context,
                300_000L
            ).coerceIn(10_000L, 300_000L),
            refreshBeforeExpirySeconds = renderLong(
                map["refresh_before_expiry_seconds"],
                context,
                120L
            ).coerceIn(0L, 3_600L),
            retryCooldownMillis = renderLong(
                map["retry_cooldown_ms"],
                context,
                60_000L
            ).coerceIn(1_000L, 300_000L)
        )
    }

    private fun normalizeOrigin(value: String, allowedHosts: List<String>): String {
        val uri = validateNavigationUrl(value, allowedHosts)
        require(uri.path.isNullOrBlank() || uri.path == "/") { "auth.local_storage.origin must not contain a path" }
        require(uri.query == null && uri.fragment == null) {
            "auth.local_storage.origin must not contain a query or fragment"
        }
        return buildString {
            append(uri.scheme.lowercase(Locale.ROOT))
            append("://")
            append(uri.host.lowercase(Locale.ROOT))
            if (uri.port >= 0) append(":${uri.port}")
        }
    }

    private fun renderPreservedString(value: Any?, context: ExecutionContext): String? {
        if (value == null) return null
        return TemplateEngine.renderString(value.toString(), context)
    }

    private fun renderStringList(value: Any?, context: ExecutionContext): List<String> {
        return when (value) {
            is List<*> -> value.mapNotNull { item -> renderOptionalString(item, context) }
            null -> emptyList()
            else -> listOfNotNull(renderOptionalString(value, context))
        }
    }

    private fun renderIntList(value: Any?, context: ExecutionContext): List<Int> {
        val values = if (value is List<*>) value else listOfNotNull(value)
        return values.mapNotNull { item ->
            when (val rendered = TemplateEngine.render(item, context)) {
                is Number -> rendered.toInt()
                is String -> rendered.trim().toIntOrNull()
                else -> null
            }
        }
    }

    private fun parseResponsePath(value: String): List<String> {
        val normalized = value.trim().removePrefix("\$").trimStart('.')
        val segments = normalized.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "auth.bootstrap.response_path must not be blank" }
        return segments
    }

    private fun parseBaseUrls(value: Any?, context: ExecutionContext, allowedHosts: List<String>): List<String> {
        return renderStringList(value, context)
            .map { candidate -> normalizeOrigin(candidate, allowedHosts) }
            .distinct()
    }

    private fun WebPageScreenshotSpec.withBaseUrl(baseUrl: String?): WebPageScreenshotSpec {
        if (baseUrl == null || baseUrls.isEmpty()) return this
        return copy(
            auth = auth?.withBaseUrl(baseUrl),
            screenshotParts = screenshotParts.map { part -> part.withBaseUrl(baseUrls, baseUrl) }
        )
    }

    private fun BrowserAuthSpec.withBaseUrl(baseUrl: String): BrowserAuthSpec {
        if (baseUrls.isEmpty()) return this
        return copy(
            localStorage = localStorage?.copy(
                origin = rewriteUrlForBaseUrl(localStorage.origin, baseUrls, baseUrl)
            ),
            cookie = cookie?.copy(
                url = rewriteUrlForBaseUrl(cookie.url, baseUrls, baseUrl)
            ),
            bootstrap = bootstrap?.copy(
                url = rewriteUrlForBaseUrl(bootstrap.url, baseUrls, baseUrl)
            ),
            login = login?.copy(
                url = rewriteUrlForBaseUrl(login.url, baseUrls, baseUrl),
                twoFactorUrl = rewriteUrlForBaseUrl(login.twoFactorUrl, baseUrls, baseUrl),
                refreshUrl = rewriteUrlForBaseUrl(login.refreshUrl, baseUrls, baseUrl)
            ),
            cliBridge = cliBridge?.copy(
                startUrl = rewriteUrlForBaseUrl(cliBridge.startUrl, baseUrls, baseUrl),
                browserUrl = rewriteUrlForBaseUrl(cliBridge.browserUrl, baseUrls, baseUrl),
                pollUrl = rewriteUrlForBaseUrl(cliBridge.pollUrl, baseUrls, baseUrl),
                profileUrl = rewriteUrlForBaseUrl(cliBridge.profileUrl, baseUrls, baseUrl),
                refreshUrl = rewriteUrlForBaseUrl(cliBridge.refreshUrl, baseUrls, baseUrl)
            )
        )
    }

    private fun BrowserScreenshotPart.withBaseUrl(
        baseUrls: List<String>,
        baseUrl: String
    ): BrowserScreenshotPart {
        return copy(steps = steps.map { step -> step.withBaseUrl(baseUrls, baseUrl) })
    }

    private fun BrowserStep.withBaseUrl(baseUrls: List<String>, baseUrl: String): BrowserStep {
        return copy(url = url?.let { item -> rewriteUrlForBaseUrl(item, baseUrls, baseUrl) })
    }

    private fun uriOrigin(uri: URI): String? {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        return buildString {
            append(scheme)
            append("://")
            append(host)
            if (uri.port >= 0) append(":${uri.port}")
        }
    }

    private fun hostMatches(host: String, rawPattern: String): Boolean {
        val pattern = rawPattern.lowercase(Locale.ROOT).trim().trimEnd('.')
        return if (pattern.startsWith("*.")) {
            val suffix = pattern.removePrefix("*.")
            host != suffix && host.endsWith(".$suffix")
        } else {
            host == pattern
        }
    }

    private fun renderRequiredString(value: Any?, context: ExecutionContext, field: String): String {
        return renderOptionalString(value, context) ?: error("$field is required")
    }

    private fun renderOptionalString(value: Any?, context: ExecutionContext): String? {
        if (value == null) return null
        return TemplateEngine.renderString(value.toString(), context).trim().ifBlank { null }
    }

    private fun renderBoolean(value: Any?, context: ExecutionContext, default: Boolean): Boolean {
        val rendered = TemplateEngine.render(value, context)
        return when (rendered) {
            null -> default
            is Boolean -> rendered
            is Number -> rendered.toInt() != 0
            is String -> rendered.equals("true", ignoreCase = true) || rendered == "1"
            else -> default
        }
    }

    private fun renderLong(value: Any?, context: ExecutionContext, default: Long): Long {
        return renderOptionalLong(value, context) ?: default
    }

    private fun renderOptionalLong(value: Any?, context: ExecutionContext): Long? {
        val rendered = TemplateEngine.render(value, context)
        return when (rendered) {
            is Number -> rendered.toLong()
            is String -> rendered.toLongOrNull()
            else -> null
        }
    }

    private fun Any?.asParameterMap(): Map<String, Any?> {
        val raw = this as? Map<*, *> ?: return emptyMap()
        return raw.mapNotNull { (key, value) ->
            key?.toString()?.let { it to value }
        }.toMap()
    }

    private class BrowserWorker {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "XAiWebHook-Playwright").apply { isDaemon = true }
        }
        private val sessions = mutableMapOf<String, BrowserSession>()
        private val authFailures = mutableMapOf<String, AuthFailure>()
        private val pendingCliTokens = mutableMapOf<String, PendingCliTokens>()
        private val baseUrlCursor = BrowserBaseUrlCursor()
        private var activeConfig: BrowserConfig? = null
        private var playwright: Playwright? = null
        private var browser: Browser? = null

        suspend fun capture(
            config: BrowserConfig,
            spec: WebPageScreenshotSpec,
            environment: Map<String, String>
        ): ByteArray {
            return submit { captureOnWorker(config, spec, environment) }
        }

        fun close(): Unit {
            if (executor.isShutdown) return
            val closeFuture = executor.submit { closeOnWorker() }
            runCatching { closeFuture.get(30, TimeUnit.SECONDS) }
            executor.shutdownNow()
        }

        private fun captureOnWorker(
            config: BrowserConfig,
            spec: WebPageScreenshotSpec,
            environment: Map<String, String>
        ): ByteArray {
            val activeBrowser = ensureBrowser(config)
            val failures = mutableListOf<Pair<String, Throwable>>()
            orderedBaseUrlCandidates(spec).forEach { baseUrl ->
                val activeSpec = spec.withBaseUrl(baseUrl)
                val effectiveSpec = activeSpec.copy(
                    sessionKey = effectiveSessionKey(activeSpec.sessionKey, baseUrl)
                )
                try {
                    return captureWithResolvedSpec(activeBrowser, config, effectiveSpec, environment)
                } catch (error: Throwable) {
                    if (baseUrl == null) throw error
                    failures += baseUrl to error
                    rememberBaseUrlFailure(spec, baseUrl)
                    WebHookDebug.log(
                        "[XAiWebHook] [browser] base_url $baseUrl failed; " +
                            "trying next candidate: ${summarizeError(error)}"
                    )
                }
            }
            throw allBaseUrlsFailed(failures)
        }

        private fun captureWithResolvedSpec(
            activeBrowser: Browser,
            config: BrowserConfig,
            spec: WebPageScreenshotSpec,
            environment: Map<String, String>
        ): ByteArray {
            val resolvedAuth = spec.auth?.let { resolveAuth(it, environment) }
            val sessionKey = spec.sessionKey
            val session = if (sessionKey == null) {
                createSession(activeBrowser, config, resolvedAuth, null)
            } else {
                val existing = sessions[sessionKey]
                if (existing != null && existing.matches(resolvedAuth)) {
                    ensureSessionFresh(activeBrowser, config, sessionKey, existing, resolvedAuth)
                } else {
                    existing?.close()
                    createSession(activeBrowser, config, resolvedAuth, sessionKey).also { created ->
                        sessions[sessionKey] = created
                    }
                }
            }

            try {
                return try {
                    val capturedParts = spec.screenshotParts.map { part ->
                        val page = session.context.newPage()
                        try {
                            try {
                                part.steps.forEach { step -> runStep(page, step, config, environment) }
                                waitBeforeScreenshot(page, part)
                                captureScreenshotPart(
                                    page = page,
                                    part = part,
                                    maxScreenshotBytes = config.maxScreenshotBytes
                                )
                            } catch (error: Throwable) {
                                throw screenshotError(page, part, error)
                            }
                        } finally {
                            runCatching { page.close() }
                        }
                    }
                    try {
                        combineScreenshotsVertically(capturedParts, spec.screenshotLayout).also { bytes ->
                            validateScreenshotSize(bytes.size, config.maxScreenshotBytes)
                        }
                    } catch (error: Throwable) {
                        throw IllegalStateException(
                            "browser screenshot composition failed: ${summarizeError(error)}",
                            error
                        )
                    }
                } finally {
                    if (sessionKey != null) {
                        runCatching { syncTokenPairAuth(session) }
                        persistSessionSafely(config, sessionKey, session)
                    }
                }
            } catch (error: Throwable) {
                if (sessionKey != null && isClosedContextError(error)) {
                    sessions.remove(sessionKey)?.close()
                }
                throw error
            } finally {
                if (sessionKey == null) {
                    session.close()
                }
            }
        }

        private fun orderedBaseUrlCandidates(spec: WebPageScreenshotSpec): List<String?> {
            return baseUrlCursor.orderedCandidates(spec.sessionKey, spec.baseUrls)
        }

        private fun rememberBaseUrlFailure(spec: WebPageScreenshotSpec, baseUrl: String): Unit {
            baseUrlCursor.rememberFailure(spec.sessionKey, spec.baseUrls, baseUrl)
        }

        private fun effectiveSessionKey(sessionKey: String?, baseUrl: String?): String? {
            return when {
                sessionKey == null -> null
                baseUrl == null -> sessionKey
                else -> "$sessionKey@$baseUrl"
            }
        }

        private fun allBaseUrlsFailed(failures: List<Pair<String, Throwable>>): Throwable {
            val (baseUrl, failure) = failures.lastOrNull()
                ?: return IllegalStateException("all configured base_urls failed")
            return IllegalStateException(
                "all configured base_urls failed; last base_url=$baseUrl: ${summarizeError(failure)}",
                failure
            ).also { aggregate ->
                failures.dropLast(1).forEach { (_, item) -> aggregate.addSuppressed(item) }
            }
        }

        private fun waitBeforeScreenshot(page: Page, part: BrowserScreenshotPart): Unit {
            if (part.delayBeforeMillis <= 0L) return
            WebHookDebug.log(
                "[XAiWebHook] [browser] waiting ${part.delayBeforeMillis}ms before screenshot part ${part.id}"
            )
            page.waitForTimeout(part.delayBeforeMillis.toDouble())
        }

        private fun captureScreenshotPart(
            page: Page,
            part: BrowserScreenshotPart,
            maxScreenshotBytes: Long
        ): ByteArray {
            val target = page.locator(part.selector)
            target.waitFor(
                Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(part.timeoutMillis.toDouble())
            )
            waitForFontsBestEffort(page, part.fontWaitTimeoutMillis)
            val screenshotOptions = Locator.ScreenshotOptions()
                .setTimeout(part.timeoutMillis.toDouble())
            buildScreenshotStyle(part.hideSelectors)?.let { style ->
                screenshotOptions.setStyle(style)
            }
            return target.screenshot(screenshotOptions).also { bytes ->
                validateScreenshotSize(bytes.size, maxScreenshotBytes)
            }
        }

        private fun waitForFontsBestEffort(page: Page, timeoutMillis: Long): Unit {
            if (timeoutMillis <= 0L) return
            try {
                page.waitForFunction(
                    "() => !document.fonts || document.fonts.status === 'loaded'",
                    null,
                    Page.WaitForFunctionOptions().setTimeout(timeoutMillis.toDouble())
                ).dispose()
            } catch (error: TimeoutError) {
                WebHookDebug.log(
                    "[XAiWebHook] [浏览器] 字体在 ${timeoutMillis}ms 内未完成加载，" +
                        "将使用当前已渲染字体继续截图"
                )
            }
        }

        private fun ensureBrowser(config: BrowserConfig): Browser {
            if (activeConfig != null && activeConfig != config) {
                closeOnWorker()
            }
            browser?.let { return it }

            val createdPlaywright = Playwright.create(
                Playwright.CreateOptions().setEnv(playwrightDriverEnvironment())
            )
            val browserType = when (config.engine) {
                "chromium" -> createdPlaywright.chromium()
                "firefox" -> createdPlaywright.firefox()
                "webkit" -> createdPlaywright.webkit()
                else -> {
                    createdPlaywright.close()
                    error("unsupported browser engine: ${config.engine}")
                }
            }
            val launchOptions = BrowserType.LaunchOptions().setHeadless(config.headless)
            if (config.executablePath != null) {
                launchOptions.setExecutablePath(Paths.get(config.executablePath))
            } else if (config.channel != null) {
                require(config.engine == "chromium") { "browser.channel is only supported by chromium" }
                launchOptions.setChannel(config.channel)
            }

            return try {
                browserType.launch(launchOptions).also { launched ->
                    playwright = createdPlaywright
                    browser = launched
                    activeConfig = config
                }
            } catch (error: Throwable) {
                createdPlaywright.close()
                throw error
            }
        }

        private fun createSession(
            browser: Browser,
            config: BrowserConfig,
            auth: ResolvedBrowserAuth?,
            sessionKey: String?
        ): BrowserSession {
            val restored = if (sessionKey != null) {
                restoreSession(config, sessionKey, auth)
            } else {
                null
            }
            val preparedAuth = if (restored != null) {
                restored.auth
            } else {
                when {
                    auth?.credentials != null -> prepareCredentialAuth(config, auth, sessionKey)
                    auth?.spec?.cliBridge != null -> prepareCliBridgeAuth(config, auth, sessionKey)
                    auth != null -> bootstrapAuth(config, auth)
                    else -> null
                }
            }
            return BrowserSession(
                context = newContext(browser, config, preparedAuth, restored?.storageState),
                auth = preparedAuth
            )
        }

        private fun restoreSession(
            config: BrowserConfig,
            sessionKey: String,
            auth: ResolvedBrowserAuth?
        ): RestoredBrowserSession? {
            val cache = try {
                sessionCache(config)
            } catch (error: Throwable) {
                warnSessionCache("browser session cache directory is invalid; a new login will be used", error)
                null
            } ?: return null
            val cached = try {
                cache.load(sessionKey, auth)
            } catch (error: Throwable) {
                runCatching { cache.delete(sessionKey) }
                warnSessionCache("cached browser session could not be read; a new login will be used", error)
                null
            } ?: return null
            if (auth == null || (auth.spec.login == null && auth.spec.cliBridge == null)) {
                return RestoredBrowserSession(auth, cached.storageState)
            }
            val restoredAuth = restoreCachedSessionAuth(auth, cached)
            if (restoredAuth == null) {
                runCatching { cache.delete(sessionKey) }
                return null
            }
            val freshAuth = try {
                refreshSessionAuthIfNeeded(config, restoredAuth)
            } catch (error: Throwable) {
                runCatching { cache.delete(sessionKey) }
                warnSessionCache("cached browser session could not be refreshed; a new login will be used", error)
                return null
            }
            return RestoredBrowserSession(freshAuth, cached.storageState)
        }

        private fun prepareCredentialAuth(
            config: BrowserConfig,
            auth: ResolvedBrowserAuth,
            sessionKey: String?
        ): ResolvedBrowserAuth {
            val key = sessionKey ?: error("credential auth requires a session key")
            val login = auth.spec.login ?: error("credential login configuration is missing")
            val now = System.currentTimeMillis()
            val authFingerprint = auth.hashCode()
            authFailures[key]?.let { failure ->
                if (failure.authFingerprint != authFingerprint) {
                    authFailures.remove(key)
                } else if (failure.retryAtMillis > now) {
                    val remainingSeconds = (failure.retryAtMillis - now + 999L) / 1_000L
                    throw IllegalStateException(
                        "credential login retry cooldown active: ${remainingSeconds}s (${failure.message})"
                    )
                }
                authFailures.remove(key)
            }
            return try {
                val activePlaywright = playwright ?: error("Playwright is not initialized")
                val tokenPair = WebPageCredentialAuthClient.login(
                    apiRequest = activePlaywright.request(),
                    auth = auth,
                    timeoutMillis = config.timeoutMillis
                )
                authFailures.remove(key)
                withTokenPair(auth, tokenPair)
            } catch (error: Throwable) {
                authFailures[key] = AuthFailure(
                    authFingerprint = authFingerprint,
                    retryAtMillis = now + login.retryCooldownMillis,
                    message = summarizeError(error)
                )
                throw error
            }
        }

        private fun prepareCliBridgeAuth(
            config: BrowserConfig,
            auth: ResolvedBrowserAuth,
            sessionKey: String?
        ): ResolvedBrowserAuth {
            val key = sessionKey ?: error("CLI bridge auth requires a session key")
            val bridge = auth.spec.cliBridge ?: error("CLI bridge configuration is missing")
            val now = System.currentTimeMillis()
            val authFingerprint = auth.hashCode()
            authFailures[key]?.let { failure ->
                if (failure.authFingerprint != authFingerprint) {
                    authFailures.remove(key)
                } else if (failure.retryAtMillis > now) {
                    val remainingSeconds = (failure.retryAtMillis - now + 999L) / 1_000L
                    throw IllegalStateException(
                        "CLI bridge retry cooldown active: ${remainingSeconds}s (${failure.message})"
                    )
                }
                authFailures.remove(key)
            }
            val pending = pendingCliTokens[key]?.takeIf { item ->
                item.authSpec == auth.spec && item.tokens.expiresAtMillis > now
            }.also { usable ->
                if (usable == null) pendingCliTokens.remove(key)
            }
            return try {
                val tokenPair = if (pending != null) {
                    WebPageCliBridgeAuthClient.complete(
                        auth = auth,
                        delivered = pending.tokens,
                        timeoutMillis = config.timeoutMillis
                    )
                } else {
                    WebPageCliBridgeAuthClient.login(
                        auth = auth,
                        timeoutMillis = config.timeoutMillis,
                        onTokenDelivered = { delivered ->
                            pendingCliTokens[key] = PendingCliTokens(auth.spec, delivered)
                        }
                    )
                }
                pendingCliTokens.remove(key)
                authFailures.remove(key)
                withTokenPair(auth, tokenPair)
            } catch (error: Throwable) {
                authFailures[key] = AuthFailure(
                    authFingerprint = authFingerprint,
                    retryAtMillis = now + bridge.retryCooldownMillis,
                    message = summarizeError(error)
                )
                throw error
            }
        }

        private fun ensureSessionFresh(
            browser: Browser,
            config: BrowserConfig,
            sessionKey: String,
            session: BrowserSession,
            candidateAuth: ResolvedBrowserAuth?
        ): BrowserSession {
            val auth = session.auth ?: return session
            val refreshedAuth = try {
                refreshSessionAuthIfNeeded(config, auth)
            } catch (error: Throwable) {
                sessions.remove(sessionKey)
                session.close()
                deleteSessionCacheSafely(config, sessionKey)
                warnSessionCache("browser session refresh failed; a new login will be used", error)
                val replacement = createSession(browser, config, candidateAuth, sessionKey)
                sessions[sessionKey] = replacement
                return replacement
            }
            if (refreshedAuth == auth) return session

            val storageState = runCatching { session.context.storageState() }.getOrNull()
            val replacement = newContext(browser, config, refreshedAuth, storageState)
            runCatching { session.context.close() }
            session.context = replacement
            session.auth = refreshedAuth
            persistSessionSafely(config, sessionKey, session)
            return session
        }

        private fun refreshSessionAuthIfNeeded(
            config: BrowserConfig,
            auth: ResolvedBrowserAuth
        ): ResolvedBrowserAuth {
            val tokenPair = auth.tokenPair ?: return auth
            val expiresAtMillis = tokenPair.expiresAtMillis ?: return auth
            val refreshBeforeExpirySeconds = auth.spec.login?.refreshBeforeExpirySeconds
                ?: auth.spec.cliBridge?.refreshBeforeExpirySeconds
                ?: return auth
            val refreshAtMillis = expiresAtMillis - refreshBeforeExpirySeconds * 1_000L
            if (System.currentTimeMillis() < refreshAtMillis) return auth

            val refreshed = when {
                auth.spec.login != null -> {
                    val activePlaywright = playwright ?: error("Playwright is not initialized")
                    WebPageCredentialAuthClient.refresh(
                        apiRequest = activePlaywright.request(),
                        auth = auth,
                        timeoutMillis = config.timeoutMillis
                    )
                }
                auth.spec.cliBridge != null -> WebPageCliBridgeAuthClient.refresh(
                    auth = auth,
                    timeoutMillis = config.timeoutMillis
                )
                else -> return auth
            }
            return withTokenPair(auth, refreshed)
        }

        private fun newContext(
            browser: Browser,
            config: BrowserConfig,
            auth: ResolvedBrowserAuth?,
            storageState: String? = null
        ): BrowserContext {
            val options = Browser.NewContextOptions().setViewportSize(config.viewportWidth, config.viewportHeight)
            val preparedStorageState = when {
                storageState != null && auth != null -> mergeStorageState(storageState, auth)
                storageState != null -> storageState
                auth?.spec?.localStorage != null -> buildStorageState(auth)
                else -> null
            }
            if (preparedStorageState != null) {
                options.setStorageState(preparedStorageState)
            }
            return browser.newContext(options).also { context ->
                context.setDefaultTimeout(config.timeoutMillis.toDouble())
                context.setDefaultNavigationTimeout(config.timeoutMillis.toDouble())
                if (auth != null) {
                    installAuth(context, auth)
                }
            }
        }

        private fun bootstrapAuth(config: BrowserConfig, auth: ResolvedBrowserAuth): ResolvedBrowserAuth {
            val bootstrap = auth.spec.bootstrap ?: return auth
            val headerName = auth.spec.headerName ?: error("auth.bootstrap requires auth.header_name")
            val token = auth.token ?: error("auth.bootstrap token is missing")
            val activePlaywright = playwright ?: error("Playwright is not initialized")
            val requestContext = activePlaywright.request().newContext(
                APIRequest.NewContextOptions()
                    .setFailOnStatusCode(false)
                    .setMaxRedirects(0)
                    .setTimeout(config.timeoutMillis.toDouble())
            )
            try {
                val response = try {
                    requestContext.get(
                        bootstrap.url,
                        RequestOptions.create()
                            .setHeader(headerName, auth.spec.headerPrefix + token)
                            .setFailOnStatusCode(false)
                            .setMaxRedirects(0)
                            .setTimeout(config.timeoutMillis.toDouble())
                    )
                } catch (error: Throwable) {
                    throw IllegalStateException(
                        "auth bootstrap request failed: ${summarizeError(error)}",
                        error
                    )
                }
                try {
                    if (response.status() !in bootstrap.expectedStatuses) {
                        throw IllegalStateException("auth bootstrap failed: HTTP ${response.status()}")
                    }
                    val storageValue = extractBootstrapStorageValue(
                        response.text(),
                        bootstrap.responsePath
                    )
                    return auth.copy(
                        additionalLocalStorage = auth.additionalLocalStorage +
                            (bootstrap.localStorageKey to storageValue)
                    )
                } finally {
                    response.dispose()
                }
            } finally {
                requestContext.dispose()
            }
        }

        private fun syncTokenPairAuth(session: BrowserSession): Unit {
            val auth = session.auth ?: return
            val previous = auth.tokenPair ?: return
            val localStorage = auth.spec.localStorage ?: return
            val values = readContextLocalStorage(session.context, localStorage.origin)
            val accessToken = values[localStorage.key]?.trim()?.ifBlank { null } ?: return
            val refreshToken = values[localStorage.refreshTokenKey]?.trim()?.ifBlank { null }
                ?: previous.refreshToken
            val expiresAtMillis = values[localStorage.expiresAtKey]?.toLongOrNull()
                ?: previous.expiresAtMillis
            val user = values[localStorage.userKey]?.let { raw ->
                runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            } ?: previous.user
            session.auth = withTokenPair(
                auth,
                BrowserTokenPair(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMillis = expiresAtMillis,
                    tokenType = previous.tokenType,
                    user = user
                )
            )
        }

        private fun readContextLocalStorage(context: BrowserContext, origin: String): Map<String, String> {
            return readStorageStateLocalStorage(context.storageState(), origin)
        }

        private fun sessionCache(config: BrowserConfig): BrowserSessionCache? {
            if (!config.sessionCacheEnabled) return null
            val configuredPath = Paths.get(config.sessionCacheDirectory.trim())
            val directory = if (configuredPath.isAbsolute) {
                configuredPath.normalize()
            } else {
                resolveBrowserSessionCacheDirectory(
                    configured = config.sessionCacheDirectory,
                    configFolder = XAiWebHook.configFolder.toPath()
                )
            }
            return BrowserSessionCache(directory)
        }

        private fun persistSessionSafely(
            config: BrowserConfig,
            sessionKey: String,
            session: BrowserSession
        ): Unit {
            val cache = try {
                sessionCache(config)
            } catch (error: Throwable) {
                warnSessionCache("browser session cache directory is invalid", error)
                return
            } ?: return
            try {
                cache.save(sessionKey, session.auth, session.context.storageState())
            } catch (error: Throwable) {
                warnSessionCache("browser session cache could not be saved", error)
            }
        }

        private fun deleteSessionCacheSafely(config: BrowserConfig, sessionKey: String): Unit {
            try {
                sessionCache(config)?.delete(sessionKey)
            } catch (error: Throwable) {
                warnSessionCache("browser session cache could not be deleted", error)
            }
        }

        private fun warnSessionCache(message: String, error: Throwable): Unit {
            val detail = summarizeError(error)
            runCatching { XAiWebHook.logger.warning("$message ($detail)") }
        }

        private fun installAuth(context: BrowserContext, auth: ResolvedBrowserAuth): Unit {
            val token = auth.token
            val headerName = auth.spec.headerName
            if (
                auth.spec.login == null &&
                auth.spec.cliBridge == null &&
                token != null &&
                headerName != null
            ) {
                context.route("**/*") { route -> resumeWithScopedHeader(route, auth, headerName) }
            }
            auth.spec.cookie?.let { cookie ->
                if (token != null) {
                    context.addCookies(
                        listOf(Cookie(cookie.name, cookie.prefix + token).setUrl(cookie.url))
                    )
                }
            }
        }

        private fun resumeWithScopedHeader(
            route: Route,
            auth: ResolvedBrowserAuth,
            headerName: String
        ): Unit {
            if (!shouldInjectHeader(route.request().url(), auth.spec.headerHosts)) {
                route.resume()
                return
            }
            val token = auth.token ?: error("browser auth token is missing")
            val headers = route.request().headers().filterKeys { key ->
                !key.equals(headerName, ignoreCase = true)
            }.toMutableMap()
            headers[headerName] = auth.spec.headerPrefix + token
            route.resume(Route.ResumeOptions().setHeaders(headers))
        }

        private fun runStep(
            page: Page,
            step: BrowserStep,
            config: BrowserConfig,
            environment: Map<String, String>
        ): Unit {
            val timeout = step.timeoutMillis ?: config.timeoutMillis
            try {
                when (step.op) {
                    "goto" -> {
                        val url = step.url.orEmpty()
                        validateNavigationUrl(url, config.allowedHosts)
                        page.navigate(
                            url,
                            Page.NavigateOptions()
                                .setWaitUntil(step.waitUntilState())
                                .setTimeout(timeout.toDouble())
                        )
                        validateNavigationUrl(page.url(), config.allowedHosts)
                    }
                    "fill" -> {
                        val locator = actionableLocator(page, step, config) ?: return
                        val value = resolveFillValue(step, environment)
                        locator.fill(value, Locator.FillOptions().setTimeout(timeout.toDouble()))
                    }
                    "click" -> {
                        val locator = actionableLocator(page, step, config) ?: return
                        locator.click(Locator.ClickOptions().setTimeout(timeout.toDouble()))
                    }
                    "wait" -> {
                        val locator = page.locator(step.selector.orEmpty())
                        locator.waitFor(
                            Locator.WaitForOptions()
                                .setState(step.waitState())
                                .setTimeout(timeout.toDouble())
                        )
                    }
                    "wait_url" -> page.waitForURL(
                        step.url.orEmpty(),
                        Page.WaitForURLOptions().setTimeout(timeout.toDouble())
                    )
                }
            } catch (error: TimeoutError) {
                if (!step.optional) throw stepError(page, step, error)
            } catch (error: Throwable) {
                throw stepError(page, step, error)
            }
        }

        private fun actionableLocator(page: Page, step: BrowserStep, config: BrowserConfig): Locator? {
            val locator = page.locator(step.selector.orEmpty())
            val waitTimeout = if (step.optional) {
                step.timeoutMillis ?: config.optionalStepTimeoutMillis
            } else {
                step.timeoutMillis ?: config.timeoutMillis
            }
            return try {
                locator.waitFor(
                    Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(waitTimeout.toDouble())
                )
                locator
            } catch (error: TimeoutError) {
                if (step.optional) null else throw error
            }
        }

        private fun BrowserStep.waitState(): WaitForSelectorState {
            return when (state) {
                "attached" -> WaitForSelectorState.ATTACHED
                "hidden" -> WaitForSelectorState.HIDDEN
                "detached" -> WaitForSelectorState.DETACHED
                else -> WaitForSelectorState.VISIBLE
            }
        }

        private fun BrowserStep.waitUntilState(): WaitUntilState {
            return when (waitUntil) {
                "domcontentloaded" -> WaitUntilState.DOMCONTENTLOADED
                "load" -> WaitUntilState.LOAD
                "networkidle" -> WaitUntilState.NETWORKIDLE
                else -> WaitUntilState.COMMIT
            }
        }

        private fun stepError(page: Page, step: BrowserStep, cause: Throwable): IllegalStateException {
            val detail = when (step.op) {
                "goto" -> "url=${step.url}, waitUntil=${step.waitUntil}"
                "wait_url" -> "url=${step.url}"
                else -> "selector=${step.selector}"
            }
            return IllegalStateException(
                "browser step ${step.index} (${step.op}, $detail) failed " +
                    "at ${pageDiagnostic(page)}: ${summarizeError(cause)}",
                cause
            )
        }

        private fun screenshotError(
            page: Page,
            part: BrowserScreenshotPart,
            cause: Throwable
        ): IllegalStateException {
            return IllegalStateException(
                "browser screenshot part ${part.index} (id=${part.id}, selector=${part.selector}) failed " +
                    "at ${pageDiagnostic(page)}: ${summarizeError(cause)}",
                cause
            )
        }

        private fun pageDiagnostic(page: Page): String {
            val url = runCatching { page.url() }
                .getOrDefault("(unknown)")
                .substringBefore('#')
                .substringBefore('?')
                .replace(Regex("[\\r\\n]+"), " ")
                .take(300)
            val title = runCatching { page.title() }
                .getOrDefault("(unknown)")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "(empty)" }
                .take(160)
            return "url=$url, title=\"$title\""
        }

        private fun summarizeError(error: Throwable): String {
            return (error.message ?: error::class.simpleName ?: "unknown error")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(500)
        }

        private fun isClosedContextError(error: Throwable): Boolean {
            val message = error.message.orEmpty()
            return message.contains("context or browser has been closed", ignoreCase = true) ||
                message.contains("Target page, context or browser has been closed", ignoreCase = true)
        }

        private data class BrowserSession(
            var context: BrowserContext,
            var auth: ResolvedBrowserAuth?
        ) {
            fun matches(candidate: ResolvedBrowserAuth?): Boolean {
                val current = auth
                if (current == null || candidate == null) return current == null && candidate == null
                if (current.spec != candidate.spec) return false
                return when {
                    candidate.credentials != null -> current.credentials == candidate.credentials
                    candidate.spec.cliBridge != null -> true
                    else -> current.token == candidate.token
                }
            }

            fun close(): Unit {
                runCatching { context.close() }
            }
        }

        private data class RestoredBrowserSession(
            val auth: ResolvedBrowserAuth?,
            val storageState: String
        )

        private data class AuthFailure(
            val authFingerprint: Int,
            val retryAtMillis: Long,
            val message: String
        )

        private data class PendingCliTokens(
            val authSpec: BrowserAuthSpec,
            val tokens: BrowserDeliveredTokenPair
        )

        private fun closeOnWorker(): Unit {
            activeConfig?.let { config ->
                sessions.forEach { (sessionKey, session) ->
                    persistSessionSafely(config, sessionKey, session)
                }
            }
            sessions.values.forEach { session -> session.close() }
            sessions.clear()
            authFailures.clear()
            pendingCliTokens.clear()
            baseUrlCursor.clear()
            runCatching { browser?.close() }
            runCatching { playwright?.close() }
            browser = null
            playwright = null
            activeConfig = null
        }

        private suspend fun <T> submit(block: () -> T): T {
            return suspendCancellableCoroutine { continuation ->
                try {
                    executor.execute {
                        val result = runCatching(block)
                        if (continuation.isActive) {
                            continuation.resumeWith(result)
                        }
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            }
        }
    }
}

internal class BrowserBaseUrlCursor {
    private val cursors = mutableMapOf<String, Int>()

    fun orderedCandidates(sessionKey: String?, baseUrls: List<String>): List<String?> {
        if (baseUrls.isEmpty()) return listOf(null)
        val start = (cursors[cursorKey(sessionKey, baseUrls)] ?: 0).floorMod(baseUrls.size)
        return baseUrls.indices.map { offset -> baseUrls[(start + offset).floorMod(baseUrls.size)] }
    }

    fun rememberFailure(sessionKey: String?, baseUrls: List<String>, failedBaseUrl: String): Unit {
        if (baseUrls.isEmpty()) return
        val index = baseUrls.indexOf(failedBaseUrl)
        if (index >= 0) {
            cursors[cursorKey(sessionKey, baseUrls)] = (index + 1).floorMod(baseUrls.size)
        }
    }

    fun clear(): Unit {
        cursors.clear()
    }

    private fun cursorKey(sessionKey: String?, baseUrls: List<String>): String {
        return listOf(sessionKey.orEmpty(), baseUrls.joinToString("|")).joinToString("|")
    }

    private fun Int.floorMod(divisor: Int): Int {
        return ((this % divisor) + divisor) % divisor
    }
}

internal data class WebPageScreenshotSpec(
    val sessionKey: String?,
    val auth: BrowserAuthSpec?,
    val screenshotParts: List<BrowserScreenshotPart>,
    val screenshotLayout: BrowserScreenshotLayout,
    val retry: BrowserScreenshotRetry,
    val baseUrls: List<String> = emptyList()
)

internal data class BrowserScreenshotPart(
    val index: Int,
    val id: String,
    val steps: List<BrowserStep>,
    val selector: String,
    val timeoutMillis: Long,
    val delayBeforeMillis: Long,
    val fontWaitTimeoutMillis: Long,
    val hideSelectors: List<String>
)

internal data class BrowserScreenshotLayout(
    val horizontalAlign: String,
    val gapPixels: Int
) {
    companion object {
        fun default(): BrowserScreenshotLayout = BrowserScreenshotLayout(
            horizontalAlign = "center",
            gapPixels = 0
        )
    }
}

internal data class BrowserScreenshotRetry(
    val enabled: Boolean,
    val maxRetries: Int,
    val delayMillis: Long
) {
    companion object {
        fun disabled(): BrowserScreenshotRetry = BrowserScreenshotRetry(
            enabled = false,
            maxRetries = 0,
            delayMillis = 0L
        )
    }
}

internal data class BrowserAuthSpec(
    val token: String?,
    val tokenEnv: String?,
    val headerName: String?,
    val headerPrefix: String,
    val headerHosts: List<String>,
    val localStorage: BrowserLocalStorageAuth?,
    val cookie: BrowserCookieAuth?,
    val bootstrap: BrowserAuthBootstrap? = null,
    val login: BrowserLoginAuth? = null,
    val cliBridge: BrowserCliBridgeAuth? = null,
    val baseUrls: List<String> = emptyList()
)

internal data class BrowserAuthBootstrap(
    val url: String,
    val responsePath: List<String>,
    val localStorageKey: String,
    val expectedStatuses: Set<Int>
)

internal data class BrowserLocalStorageAuth(
    val origin: String,
    val key: String,
    val prefix: String,
    val refreshTokenKey: String = "refresh_token",
    val expiresAtKey: String = "token_expires_at",
    val userKey: String = "auth_user"
)

internal data class BrowserCookieAuth(
    val name: String,
    val url: String,
    val prefix: String
)

internal data class ResolvedBrowserAuth(
    val spec: BrowserAuthSpec,
    val token: String? = null,
    val credentials: ResolvedBrowserCredentials? = null,
    val tokenPair: BrowserTokenPair? = null,
    val additionalLocalStorage: Map<String, String> = emptyMap()
)

internal data class BrowserStep(
    val index: Int,
    val op: String,
    val selector: String?,
    val url: String?,
    val value: String?,
    val valueEnv: String?,
    val optional: Boolean,
    val timeoutMillis: Long?,
    val state: String,
    val waitUntil: String = "commit"
)
