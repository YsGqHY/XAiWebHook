package kim.hhhhhy.x.webhook.action

import com.microsoft.playwright.APIRequest
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.RequestOptions
import com.microsoft.playwright.options.WaitForSelectorState
import com.microsoft.playwright.options.WaitUntilState
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.BrowserConfig
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.template.TemplateEngine
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
import java.net.URI
import java.nio.file.Paths
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object WebPageScreenshotAction {
    private const val MAX_STEPS = 32
    private val NAVIGATION_WAIT_UNTIL = setOf("commit", "domcontentloaded", "load", "networkidle")
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
        return runCatching {
            worker().capture(browserConfig, spec, environment)
        }.onSuccess {
            lastError = null
        }.onFailure { error ->
            lastError = error.message?.take(500) ?: error::class.simpleName
        }.getOrThrow()
    }

    fun reset(): Unit {
        val worker = synchronized(workerLock) {
            workerRef.also { workerRef = null }
        }
        worker?.close()
        lastError = null
    }

    fun close(): Unit = reset()

    internal fun parseSpec(action: ActionConfig, context: ExecutionContext): WebPageScreenshotSpec {
        val rawSteps = action.params["steps"] as? List<*>
            ?: error("steps must be a list")
        require(rawSteps.isNotEmpty()) { "steps must not be empty" }
        require(rawSteps.size <= MAX_STEPS) { "steps must contain at most $MAX_STEPS entries" }

        val steps = rawSteps.mapIndexed { index, raw ->
            val map = raw.asParameterMap()
            require(map.isNotEmpty()) { "step ${index + 1} must be an object" }
            parseStep(index, map, context)
        }
        val screenshotMap = action.params["screenshot"].asParameterMap()
        val screenshotSelector = normalizeSelector(
            renderRequiredString(screenshotMap["selector"], context, "screenshot.selector")
        )
        val screenshotTimeoutMillis = renderLong(
            screenshotMap["timeout_ms"],
            context,
            context.config.browser.timeoutMillis
        ).coerceIn(100L, 300_000L)
        val sessionKey = renderOptionalString(action.params["session_key"], context)
            ?: action.id?.trim()?.ifBlank { null }
        val auth = parseAuth(action.params["auth"].asParameterMap(), context)
        if (auth?.login != null || auth?.cliBridge != null) {
            require(sessionKey != null) {
                "session auth requires session_key or a named action id"
            }
        }

        return WebPageScreenshotSpec(
            sessionKey = sessionKey,
            auth = auth,
            steps = steps,
            screenshotSelector = screenshotSelector,
            screenshotTimeoutMillis = screenshotTimeoutMillis
        )
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

    internal fun normalizeSelector(selector: String): String {
        val normalized = selector.trim()
        require(normalized.isNotEmpty()) { "selector must not be blank" }
        return if (normalized.startsWith("/") || normalized.startsWith("(")) {
            "xpath=$normalized"
        } else {
            normalized
        }
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
                    val entries = linkedMapOf<String, String>().apply {
                        auth.token?.let { token ->
                            put(localStorage.key, localStorage.prefix + token)
                        }
                        putAll(auth.additionalLocalStorage)
                    }
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

    private fun parseAuth(map: Map<String, Any?>, context: ExecutionContext): BrowserAuthSpec? {
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
            cliBridge = cliBridge
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
            val resolvedAuth = spec.auth?.let { resolveAuth(it, environment) }
            val sessionKey = spec.sessionKey
            val session = if (sessionKey == null) {
                createSession(activeBrowser, config, resolvedAuth, null)
            } else {
                val existing = sessions[sessionKey]
                if (existing != null && existing.matches(resolvedAuth)) {
                    ensureSessionFresh(activeBrowser, config, sessionKey, existing)
                } else {
                    existing?.close()
                    createSession(activeBrowser, config, resolvedAuth, sessionKey).also { created ->
                        sessions[sessionKey] = created
                    }
                }
            }

            try {
                val page = session.context.newPage()
                return try {
                    spec.steps.forEach { step -> runStep(page, step, config, environment) }
                    try {
                        val target = page.locator(spec.screenshotSelector)
                        target.waitFor(
                            Locator.WaitForOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(spec.screenshotTimeoutMillis.toDouble())
                        )
                        val bytes = target.screenshot(
                            Locator.ScreenshotOptions().setTimeout(spec.screenshotTimeoutMillis.toDouble())
                        )
                        validateScreenshotSize(bytes.size, config.maxScreenshotBytes)
                        bytes
                    } catch (error: Throwable) {
                        throw screenshotError(page, spec.screenshotSelector, error)
                    }
                } finally {
                    runCatching { page.close() }
                    if (sessionKey != null) {
                        runCatching { syncTokenPairAuth(session) }
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

        private fun ensureBrowser(config: BrowserConfig): Browser {
            if (activeConfig != null && activeConfig != config) {
                closeOnWorker()
            }
            browser?.let { return it }

            val createdPlaywright = Playwright.create(
                Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1"))
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
            val preparedAuth = when {
                auth?.credentials != null -> prepareCredentialAuth(config, auth, sessionKey)
                auth?.spec?.cliBridge != null -> prepareCliBridgeAuth(config, auth, sessionKey)
                auth != null -> bootstrapAuth(config, auth)
                else -> null
            }
            return BrowserSession(
                context = newContext(browser, config, preparedAuth),
                auth = preparedAuth
            )
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
                auth.withTokenPair(tokenPair)
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
                auth.withTokenPair(tokenPair)
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
            session: BrowserSession
        ): BrowserSession {
            val auth = session.auth ?: return session
            val tokenPair = auth.tokenPair ?: return session
            val expiresAtMillis = tokenPair.expiresAtMillis ?: return session
            val refreshBeforeExpirySeconds = auth.spec.login?.refreshBeforeExpirySeconds
                ?: auth.spec.cliBridge?.refreshBeforeExpirySeconds
                ?: return session
            val refreshAtMillis = expiresAtMillis - refreshBeforeExpirySeconds * 1_000L
            if (System.currentTimeMillis() < refreshAtMillis) return session

            val refreshedAuth = try {
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
                    else -> return session
                }
                auth.withTokenPair(refreshed)
            } catch (error: Throwable) {
                sessions.remove(sessionKey)
                session.close()
                throw error
            }
            val replacement = newContext(browser, config, refreshedAuth)
            runCatching { session.context.close() }
            session.context = replacement
            session.auth = refreshedAuth
            return session
        }

        private fun ResolvedBrowserAuth.withTokenPair(tokenPair: BrowserTokenPair): ResolvedBrowserAuth {
            val localStorage = spec.localStorage
                ?: error("session auth requires auth.local_storage")
            val storage = linkedMapOf<String, String>()
            tokenPair.refreshToken?.let { storage[localStorage.refreshTokenKey] = it }
            tokenPair.expiresAtMillis?.let { storage[localStorage.expiresAtKey] = it.toString() }
            storage[localStorage.userKey] = tokenPair.user.toString()
            return copy(
                token = tokenPair.accessToken,
                tokenPair = tokenPair,
                additionalLocalStorage = storage
            )
        }

        private fun newContext(
            browser: Browser,
            config: BrowserConfig,
            auth: ResolvedBrowserAuth?
        ): BrowserContext {
            val options = Browser.NewContextOptions().setViewportSize(config.viewportWidth, config.viewportHeight)
            if (auth?.spec?.localStorage != null) {
                options.setStorageState(buildStorageState(auth))
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
            session.auth = auth.withTokenPair(
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
            val root = runCatching {
                Json.parseToJsonElement(context.storageState()) as? JsonObject
            }.getOrNull() ?: return emptyMap()
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

        private fun screenshotError(page: Page, selector: String, cause: Throwable): IllegalStateException {
            return IllegalStateException(
                "browser screenshot (selector=$selector) failed " +
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
            sessions.values.forEach { session -> session.close() }
            sessions.clear()
            authFailures.clear()
            pendingCliTokens.clear()
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

internal data class WebPageScreenshotSpec(
    val sessionKey: String?,
    val auth: BrowserAuthSpec?,
    val steps: List<BrowserStep>,
    val screenshotSelector: String,
    val screenshotTimeoutMillis: Long
)

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
    val cliBridge: BrowserCliBridgeAuth? = null
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
