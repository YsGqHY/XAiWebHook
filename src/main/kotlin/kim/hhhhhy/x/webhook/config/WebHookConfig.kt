package kim.hhhhhy.x.webhook.config

import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.util.HttpProxySupport
import org.yaml.snakeyaml.Yaml
import java.io.File

internal object WebHookConfig {
    private const val MAX_OUTGOING_COOLDOWN_MILLIS = 604_800_000L
    private const val DEFAULT_OUTGOING_COOLDOWN_MESSAGE =
        "指令冷却中，请在 \${cooldown.remainingSeconds} 秒后重试。"
    private const val DEFAULT_SINGLE_FLIGHT_MESSAGE = "上一项任务尚未完成，请等待完成后再试。"

    private val configFile: File by lazy {
        File(XAiWebHook.configFolder, "webhook_config.yml")
    }

    @Volatile
    var current: PluginConfig = PluginConfig.safeDefault()
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun load(): Unit {
        WebHookDebug.log("[XAiWebHook] [配置] 开始加载配置文件...")
        if (!configFile.exists()) {
            WebHookDebug.log("[XAiWebHook] [配置] 配置文件不存在，正在生成默认配置...")
            saveDefault()
        }

        try {
            WebHookDebug.log("[XAiWebHook] [配置] 正在解析 YAML 配置：${configFile.path}")
            val raw: Any? = configFile.inputStream().use { input ->
                Yaml().load(input)
            }
            val root = raw.asMap()
            current = parseConfig(root)
            lastError = null
            XAiWebHook.logger.info(
                "WebHook config loaded: incoming=${current.incoming.endpoints.size}, outgoing=${current.outgoing.routes.size}"
            )
            WebHookDebug.log("[XAiWebHook] [配置] 配置加载成功：incoming=${current.incoming.endpoints.size} 个端点，outgoing=${current.outgoing.routes.size} 条路由")
        } catch (e: Exception) {
            lastError = e.message ?: e::class.qualifiedName
            current = PluginConfig.safeDefault()
            XAiWebHook.logger.error("Failed to load webhook_config.yml", e)
            WebHookDebug.log("[XAiWebHook] [配置] 配置加载失败，已回退安全默认配置：${e.message}")
        }
    }

    fun reload(): Unit = load()

    private fun saveDefault(): Unit {
        configFile.parentFile?.mkdirs()
        val resource = WebHookConfig::class.java.getResourceAsStream("/webhook_config.yml")
        if (resource != null) {
            resource.use { input ->
                configFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            configFile.writeText("server:\n  enabled: false\n")
        }
        XAiWebHook.logger.info("Default webhook_config.yml generated")
        WebHookDebug.log("[XAiWebHook] [配置] 默认配置文件已生成：${configFile.path}")
    }

    internal fun parseConfig(root: Map<String, Any?>): PluginConfig {
        // 先解析 logging 并更新 debug 开关，使后续各段的调试输出立即生效
        val logging = root.map("logging").let { loggingMap ->
            LoggingConfig(
                request = loggingMap.boolean("request", true),
                response = loggingMap.boolean("response", true),
                errorStacktrace = loggingMap.boolean("error_stacktrace", true),
                debug = loggingMap.boolean("debug", false)
            )
        }
        WebHookDebug.update(logging.debug)

        val server = root.map("server").let { serverMap ->
            ServerConfig(
                enabled = serverMap.boolean("enabled", true),
                host = serverMap.string("host", "127.0.0.1"),
                port = serverMap.int("port", 18080).coerceIn(1, 65535),
                basePath = normalizePath(serverMap.string("base_path", "/webhook"))
            )
        }
        WebHookDebug.log("""[XAiWebHook] [配置] 服务器配置
  enabled  : ${server.enabled}
  host     : ${server.host}
  port     : ${server.port}
  basePath : ${server.basePath}""")

        val auth = root.map("auth").let { authMap ->
            AuthConfig(
                type = authMap.string("type", "bearer"),
                tokens = authMap.stringList("tokens"),
                allowEmptyForLocalhost = authMap.boolean("allow_empty_for_localhost", false)
            )
        }
        WebHookDebug.log("""[XAiWebHook] [配置] 鉴权配置
  type                    : ${auth.type}
  tokens                  : ${auth.tokens.size} 个
  allowEmptyForLocalhost  : ${auth.allowEmptyForLocalhost}""")

        val templates = root.map("templates").let { templatesMap ->
            TemplateConfig(
                enableExpressions = templatesMap.boolean("enable_expressions", true),
                strictMissingVariables = templatesMap.boolean("strict_missing_variables", false)
            )
        }

        val browser = root.map("browser").let { browserMap ->
            BrowserConfig(
                enabled = browserMap.boolean("enabled", false),
                engine = browserMap.string("engine", "chromium").lowercase(),
                channel = browserMap.stringOrNull("channel"),
                executablePath = browserMap.stringOrNull("executable_path"),
                proxyUrl = HttpProxySupport.normalize(browserMap.stringOrNull("proxy")),
                headless = browserMap.boolean("headless", true),
                viewportWidth = browserMap.int("viewport_width", 1440).coerceIn(320, 7680),
                viewportHeight = browserMap.int("viewport_height", 1000).coerceIn(240, 4320),
                timeoutMillis = browserMap.long("timeout_ms", 30_000L).coerceIn(1_000L, 300_000L),
                optionalStepTimeoutMillis = browserMap.long("optional_step_timeout_ms", 1_000L)
                    .coerceIn(100L, 30_000L),
                sessionCacheEnabled = browserMap.boolean("session_cache_enabled", false),
                sessionCacheDirectory = browserMap.string("session_cache_dir", "browser-session-cache"),
                allowedHosts = browserMap.stringList("allowed_hosts")
                    .map { it.lowercase().trimEnd('.') }
                    .filter { it.isNotBlank() }
                    .distinct(),
                maxScreenshotBytes = browserMap.long("max_screenshot_bytes", 10_485_760L)
                    .coerceIn(1_024L, 50L * 1_024L * 1_024L)
            )
        }
        WebHookDebug.log("""[XAiWebHook] [配置] 浏览器配置
  enabled       : ${browser.enabled}
  engine        : ${browser.engine}
  channel       : ${browser.channel ?: "(默认)"}
  proxy         : ${HttpProxySupport.describe(browser.proxyUrl)}
  sessionCache  : ${if (browser.sessionCacheEnabled) browser.sessionCacheDirectory else "disabled"}
  allowedHosts  : ${browser.allowedHosts.size} 个""")

        val security = root.map("security").let { securityMap ->
            SecurityConfig(
                allowCommandExecution = securityMap.boolean("allow_command_execution", false),
                maxBodyBytes = securityMap.long("max_body_bytes", 1_048_576L).coerceAtLeast(0L)
            )
        }
        WebHookDebug.log("""[XAiWebHook] [配置] 安全配置
  allowCommandExecution : ${security.allowCommandExecution}
  maxBodyBytes          : ${security.maxBodyBytes}""")

        val actions = root.map("actions").mapNotNull { (key, value) ->
            val actionMap = value.asMap()
            if (actionMap.isEmpty()) {
                null
            } else {
                key to parseAction(actionMap + ("id" to key))
            }
        }.mapNotNull { (key, action) -> action?.let { key to it } }.toMap()

        val incoming = IncomingConfig(
            endpoints = root.map("incoming")
                .list("endpoints")
                .mapIndexedNotNull { index, item -> parseEndpoint(index, item, actions) }
        )

        val outgoing = OutgoingConfig(
            routes = root.map("outgoing")
                .list("routes")
                .mapIndexedNotNull { index, item -> parseRoute(index, item, actions) }
        )

        val modelPlaza = root.map("model_plaza").let { plazaMap ->
            val authMap = plazaMap.map("auth")
            val authConfig = if (authMap.isEmpty()) {
                null
            } else {
                ModelPlazaAuthConfig(
                    startUrl = authMap.string("start_url", "https://hk.geek2api.com/api/v1/auth/cli-bridge/start"),
                    browserUrl = authMap.string("browser_url", "https://hk.geek2api.com/cli-bridge"),
                    pollUrl = authMap.string("poll_url", "https://hk.geek2api.com/api/v1/auth/cli-bridge/poll"),
                    profileUrl = authMap.string("profile_url", "https://hk.geek2api.com/api/v1/user/profile"),
                    refreshUrl = authMap.string("refresh_url", "https://hk.geek2api.com/api/v1/auth/refresh"),
                    pollIntervalMillis = authMap.long("poll_interval_ms", 3_000L).coerceIn(1_000L, 10_000L),
                    maxWaitMillis = authMap.long("max_wait_ms", 300_000L).coerceIn(60_000L, 600_000L),
                    refreshBeforeExpirySeconds = authMap.long("refresh_before_expiry_seconds", 300L).coerceIn(0L, 86400L),
                    retryCooldownMillis = authMap.long("retry_cooldown_ms", 5_000L).coerceIn(0L, 3_600_000L)
                )
            }
            val queriesMap = plazaMap.map("queries")
            val queries = ModelPlazaQueriesConfig(
                models = parseModelPlazaQueryConfig(
                    queriesMap.map("models"),
                    ModelPlazaQueryConfig.modelsDefault()
                ),
                groups = parseModelPlazaQueryConfig(
                    queriesMap.map("groups"),
                    ModelPlazaQueryConfig.groupsDefault()
                )
            )
            ModelPlazaConfig(
                enabled = plazaMap.boolean("enabled", false),
                baseUrl = plazaMap.string("base_url", "https://hk.geek2api.com/model-plaza"),
                timeoutMillis = plazaMap.long("timeout_ms", 30_000L).coerceIn(5_000L, 120_000L),
                proxyUrl = HttpProxySupport.normalize(plazaMap.stringOrNull("proxy")),
                auth = authConfig,
                queries = queries
            )
        }
        WebHookDebug.log("""[XAiWebHook] [配置] Model Plaza 配置
  enabled      : ${modelPlaza.enabled}
  baseUrl      : ${modelPlaza.baseUrl}
  proxy        : ${HttpProxySupport.describe(modelPlaza.proxyUrl)}
  timeoutMillis: ${modelPlaza.timeoutMillis}""")

        val polymarket = root.map("polymarket").let { polyMap ->
            val keywordExtraction = parseQueryKeywordExtraction(polyMap.map("keyword_extraction"))
            val filters = parsePolymarketFilterConfig(polyMap.map("filters"))
            val whitelistMap = polyMap.map("whitelist")
            val whitelist = PolymarketWhitelistConfig(
                keywords = whitelistMap.stringList("keywords")
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                    .ifEmpty { PolymarketWhitelistConfig.DEFAULT_KEYWORDS },
                caseSensitive = whitelistMap.boolean("case_sensitive", false),
                rejectMessage = whitelistMap.string(
                    "reject_message",
                    "仅支持搜索白名单中的大模型相关市场；当前关键词：\${keyword}"
                )
            )

            val responseFormatMap = polyMap.map("response_format")
            val responseFormat = if (responseFormatMap.isEmpty()) {
                null
            } else {
                PolymarketResponseFormatConfig(
                    maxHistoryPoints = responseFormatMap.int("max_history_points", 5).coerceIn(1, 20),
                    dateFormat = responseFormatMap.string("date_format", "yyyy年MM月dd日"),
                    timezone = responseFormatMap.string("timezone", "Asia/Shanghai"),
                    compactNumbers = responseFormatMap.boolean("compact_numbers", true),
                    successTemplate = responseFormatMap.stringOrNull("success_template"),
                    emptyTemplate = responseFormatMap.stringOrNull("empty_template"),
                    errorTemplate = responseFormatMap.stringOrNull("error_template"),
                    outputMode = responseFormatMap.string("output_mode", "image")
                        .lowercase()
                        .takeIf { it == "image" || it == "text" || it == "both" }
                        ?: "image",
                    imageFallbackToText = responseFormatMap.boolean("image_fallback_to_text", true),
                    imageWidthPx = responseFormatMap.int("image_width_px", 1440).coerceIn(900, 2400)
                )
            }

            PolymarketConfig(
                enabled = polyMap.boolean("enabled", false),
                gammaApiBaseUrl = polyMap.string("gamma_api_base_url", "https://gamma-api.polymarket.com"),
                clobApiBaseUrl = polyMap.string("clob_api_base_url", "https://clob.polymarket.com"),
                timeoutMillis = polyMap.long("timeout_ms", 30_000L).coerceIn(5_000L, 120_000L),
                proxyUrl = HttpProxySupport.normalize(polyMap.stringOrNull("proxy")),
                locale = polyMap.string("locale", "zh").takeIf { it.matches(Regex("[A-Za-z-]{2,16}")) } ?: "zh",
                searchFields = polyMap.stringList("search_fields")
                    .map { it.lowercase() }
                    .filter { it == "question" || it == "description" }
                    .distinct()
                    .ifEmpty { listOf("question", "description") },
                commandPrefix = polyMap.string("command_prefix", "poly"),
                enabledGroups = polyMap.longList("enabled_groups"),
                searchPageSize = polyMap.int("search_page_size", 100).coerceIn(1, 500),
                maxSearchPages = polyMap.int("max_search_pages", 3).coerceIn(1, 20),
                whitelist = whitelist,
                keywordExtraction = keywordExtraction,
                filters = filters,
                responseFormat = responseFormat
            )
        }
        WebHookDebug.log("""[XAiWebHook] [配置] Polymarket 配置
  enabled      : ${polymarket.enabled}
  gammaApiBase : ${polymarket.gammaApiBaseUrl}
  clobApiBase  : ${polymarket.clobApiBaseUrl}
  timeoutMillis: ${polymarket.timeoutMillis}
  proxy        : ${HttpProxySupport.describe(polymarket.proxyUrl)}
  locale       : ${polymarket.locale}
  commandPrefix: ${polymarket.commandPrefix}
  enabledGroups: ${polymarket.enabledGroups.size} 个
  searchPages  : ${polymarket.maxSearchPages} × ${polymarket.searchPageSize}
  outputMode   : ${polymarket.responseFormat?.outputMode ?: "image"}
  whitelist    : ${polymarket.whitelist.keywords.size} 个模型名""")

        return PluginConfig(
            server = server,
            auth = auth,
            templates = templates,
            browser = browser,
            incoming = incoming,
            outgoing = outgoing,
            actions = actions,
            security = security,
            logging = logging,
            modelPlaza = modelPlaza,
            polymarket = polymarket
        )
    }

    private fun parseQueryKeywordExtraction(
        map: Map<String, Any?>
    ): QueryKeywordExtractionConfig? {
        if (map.isEmpty()) return null
        return QueryKeywordExtractionConfig(
            removePrefixes = map.stringList("remove_prefixes"),
            pattern = map.stringOrNull("pattern"),
            captureGroup = map.int("capture_group", 1).coerceIn(0, 20),
            trim = map.boolean("trim", true),
            toLowerCase = map.boolean("lowercase", false),
            requirePrefixMatch = map.boolean("require_prefix_match", false)
        )
    }

    private fun parsePolymarketFilterConfig(
        map: Map<String, Any?>
    ): QueryFilterConfig? {
        if (map.isEmpty()) return null
        val lengthMap = map.map("length")
        val length = if (lengthMap.isEmpty()) null else QueryLengthConfig(
            min = lengthMap.int("min", 0).takeIf { it > 0 },
            max = lengthMap.int("max", 0).takeIf { it > 0 },
            rejectMessage = lengthMap.string("reject_message", "关键词长度不符合要求")
        )
        val patternMap = map.map("pattern")
        val pattern = if (patternMap.isEmpty()) null else QueryPatternConfig(
            pattern = patternMap.string("regex", ""),
            rejectMessage = patternMap.string("reject_message", "关键词格式不正确")
        ).takeIf { it.pattern.isNotBlank() }
        if (length == null && pattern == null) return null
        return QueryFilterConfig(
            blacklist = null,
            whitelist = null,
            length = length,
            pattern = pattern
        )
    }

    private fun parseQueryFilterConfig(
        map: Map<String, Any?>
    ): QueryFilterConfig? {
        if (map.isEmpty()) return null
        val blacklistMap = map.map("blacklist")
        val blacklist = if (blacklistMap.isEmpty()) null else QueryBlacklistConfig(
            enabled = blacklistMap.boolean("enabled", true),
            keywords = blacklistMap.stringList("keywords"),
            caseSensitive = blacklistMap.boolean("case_sensitive", false),
            rejectMessage = blacklistMap.string("reject_message", "关键词已被禁止")
        )
        val whitelistMap = map.map("whitelist")
        val whitelist = if (whitelistMap.isEmpty()) null else QueryWhitelistConfig(
            enabled = whitelistMap.boolean("enabled", true),
            keywords = whitelistMap.stringList("keywords"),
            caseSensitive = whitelistMap.boolean("case_sensitive", false),
            rejectMessage = whitelistMap.string("reject_message", "关键词不在允许列表中")
        )
        val lengthMap = map.map("length")
        val length = if (lengthMap.isEmpty()) null else QueryLengthConfig(
            min = lengthMap.int("min", 0).takeIf { it > 0 },
            max = lengthMap.int("max", 0).takeIf { it > 0 },
            rejectMessage = lengthMap.string("reject_message", "关键词长度不符合要求")
        )
        val patternMap = map.map("pattern")
        val pattern = if (patternMap.isEmpty()) null else QueryPatternConfig(
            pattern = patternMap.string("regex", ""),
            rejectMessage = patternMap.string("reject_message", "关键词格式不正确")
        ).takeIf { it.pattern.isNotBlank() }
        return QueryFilterConfig(
            blacklist = blacklist,
            whitelist = whitelist,
            length = length,
            pattern = pattern
        )
    }

    private fun parseModelPlazaQueryConfig(
        map: Map<String, Any?>,
        defaults: ModelPlazaQueryConfig
    ): ModelPlazaQueryConfig {
        val responseMap = map.map("response_format")
        val response = ModelPlazaResponseFormatConfig(
            successTemplate = responseMap.stringOrNull("success_template")
                ?: defaults.responseFormat.successTemplate,
            pendingMessage = responseMap.string("pending_message", defaults.responseFormat.pendingMessage),
            failureMessage = responseMap.string("failure_message", defaults.responseFormat.failureMessage),
            emptyMessage = responseMap.string("empty_message", defaults.responseFormat.emptyMessage)
        )
        val sort = map.string("sort", defaults.sort).lowercase()
            .takeIf { it == "source" || it == "alphabetical" } ?: defaults.sort
        return ModelPlazaQueryConfig(
            keywordExtraction = parseQueryKeywordExtraction(map.map("keyword_extraction"))
                ?: defaults.keywordExtraction,
            filters = parseQueryFilterConfig(map.map("filters")) ?: defaults.filters,
            sort = sort,
            limit = map.int("limit", defaults.limit).coerceIn(0, 100),
            maxRelatedItems = map.int("max_related_items", defaults.maxRelatedItems).coerceIn(0, 100),
            responseFormat = response
        )
    }

    private fun parseEndpoint(index: Int, raw: Any?, globalActions: Map<String, ActionConfig>): IncomingEndpoint? {
        val map = raw.asMap()
        if (map.isEmpty()) return null
        val id = map.string("id", "endpoint-$index")
        return IncomingEndpoint(
            id = id,
            enabled = map.boolean("enabled", true),
            method = map.string("method", "POST").uppercase(),
            path = normalizePath(map.string("path", "/$id")),
            tokens = map.stringList("tokens"),
            actions = parseActionList(map.list("actions"), globalActions),
            singleFlight = parseActionGroupSingleFlight(map.map("single_flight"))
        )
    }

    private fun parseRoute(index: Int, raw: Any?, globalActions: Map<String, ActionConfig>): OutgoingRoute? {
        val map = raw.asMap()
        if (map.isEmpty()) return null
        val id = map.string("id", "route-$index")
        return OutgoingRoute(
            id = id,
            enabled = map.boolean("enabled", true),
            events = map.stringList("events"),
            groups = map.longList("groups"),
            friends = map.longList("friends"),
            senders = map.longList("senders"),
            message = parseMessageMatcher(map.map("message")),
            condition = map.stringOrNull("condition"),
            cooldown = parseOutgoingCooldown(map.map("cooldown")),
            actions = parseActionList(map.list("actions"), globalActions),
            singleFlight = parseActionGroupSingleFlight(map.map("single_flight"))
        )
    }

    private fun parseActionGroupSingleFlight(map: Map<String, Any?>): ActionGroupSingleFlightConfig {
        if (map.isEmpty()) return ActionGroupSingleFlightConfig.disabled()
        val key = map.stringOrNull("key")?.also { value ->
            require(value.length <= 200) { "single_flight.key must contain at most 200 characters" }
            require(!value.contains('\r') && !value.contains('\n')) {
                "single_flight.key must not contain line breaks"
            }
        }
        return ActionGroupSingleFlightConfig(
            enabled = map.boolean("enabled", true),
            key = key,
            notify = map.boolean("notify", true),
            message = map["message"]?.toString() ?: DEFAULT_SINGLE_FLIGHT_MESSAGE
        )
    }

    private fun parseOutgoingCooldown(map: Map<String, Any?>): OutgoingCooldownConfig {
        if (map.isEmpty()) return OutgoingCooldownConfig.disabled()

        val personalMillis = map.long("personal_ms", 0L)
            .coerceIn(0L, MAX_OUTGOING_COOLDOWN_MILLIS)
        val administratorMillis = map.long("administrator_ms", personalMillis)
            .coerceIn(0L, MAX_OUTGOING_COOLDOWN_MILLIS)
        val globalMillis = map.long("global_ms", 0L)
            .coerceIn(0L, MAX_OUTGOING_COOLDOWN_MILLIS)

        return OutgoingCooldownConfig(
            enabled = map.boolean("enabled", true),
            personalMillis = personalMillis,
            administratorMillis = administratorMillis,
            globalMillis = globalMillis,
            notify = map.boolean("notify", true),
            message = map["message"]?.toString() ?: DEFAULT_OUTGOING_COOLDOWN_MESSAGE
        )
    }

    private fun parseActionList(raw: List<Any?>, globalActions: Map<String, ActionConfig>): List<ActionConfig> {
        return raw.mapNotNull { item ->
            when (item) {
                is String -> globalActions[item]
                is Map<*, *> -> {
                    val map = item.asMap()
                    val ref = map.stringOrNull("ref")
                    if (ref != null) globalActions[ref] else parseAction(map)
                }
                else -> null
            }
        }
    }

    private fun parseMessageMatcher(map: Map<String, Any?>): MessageMatcher {
        val compiledRegex = map.stringList("regex").mapNotNull { pattern ->
            runCatching { Regex(pattern) }.getOrElse {
                XAiWebHook.logger.warning("Ignoring invalid outgoing route regex: $pattern")
                null
            }
        }
        return MessageMatcher(
            contains = map.stringList("contains"),
            startsWith = map.stringList("starts_with"),
            endsWith = map.stringList("ends_with"),
            regex = compiledRegex
        )
    }

    private fun parseAction(map: Map<String, Any?>): ActionConfig? {
        val type = map.stringOrNull("type") ?: return null
        return ActionConfig(
            id = map.stringOrNull("id"),
            type = type,
            enabled = map.boolean("enabled", true),
            params = map.filterKeys { it !in setOf("id", "type", "enabled") }
        )
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        return "/" + trimmed.trim('/').trim()
    }
}

internal data class PluginConfig(
    val server: ServerConfig,
    val auth: AuthConfig,
    val templates: TemplateConfig,
    val browser: BrowserConfig,
    val incoming: IncomingConfig,
    val outgoing: OutgoingConfig,
    val actions: Map<String, ActionConfig>,
    val security: SecurityConfig,
    val logging: LoggingConfig,
    val modelPlaza: ModelPlazaConfig,
    val polymarket: PolymarketConfig
) {
    companion object {
        fun safeDefault(): PluginConfig = PluginConfig(
            server = ServerConfig(enabled = false, host = "127.0.0.1", port = 18080, basePath = "/webhook"),
            auth = AuthConfig(type = "bearer", tokens = emptyList(), allowEmptyForLocalhost = false),
            templates = TemplateConfig(enableExpressions = true, strictMissingVariables = false),
            browser = BrowserConfig.safeDefault(),
            incoming = IncomingConfig(endpoints = emptyList()),
            outgoing = OutgoingConfig(routes = emptyList()),
            actions = emptyMap(),
            security = SecurityConfig(allowCommandExecution = false, maxBodyBytes = 1_048_576L),
            logging = LoggingConfig(request = true, response = true, errorStacktrace = true, debug = false),
            modelPlaza = ModelPlazaConfig.safeDefault(),
            polymarket = PolymarketConfig.safeDefault()
        )
    }
}

internal data class ServerConfig(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val basePath: String
)

internal data class AuthConfig(
    val type: String,
    val tokens: List<String>,
    val allowEmptyForLocalhost: Boolean
)

internal data class TemplateConfig(
    val enableExpressions: Boolean,
    val strictMissingVariables: Boolean
)

internal data class BrowserConfig(
    val enabled: Boolean,
    val engine: String,
    val channel: String?,
    val executablePath: String?,
    val headless: Boolean,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val timeoutMillis: Long,
    val optionalStepTimeoutMillis: Long,
    val sessionCacheEnabled: Boolean,
    val sessionCacheDirectory: String,
    val allowedHosts: List<String>,
    val maxScreenshotBytes: Long,
    val proxyUrl: String = ""
) {
    companion object {
        fun safeDefault(): BrowserConfig = BrowserConfig(
            enabled = false,
            engine = "chromium",
            channel = null,
            executablePath = null,
            headless = true,
            viewportWidth = 1440,
            viewportHeight = 1000,
            timeoutMillis = 30_000L,
            optionalStepTimeoutMillis = 1_000L,
            sessionCacheEnabled = false,
            sessionCacheDirectory = "browser-session-cache",
            allowedHosts = emptyList(),
            maxScreenshotBytes = 10_485_760L,
            proxyUrl = ""
        )
    }
}

internal data class IncomingConfig(
    val endpoints: List<IncomingEndpoint>
)

internal data class IncomingEndpoint(
    val id: String,
    val enabled: Boolean,
    val method: String,
    val path: String,
    val tokens: List<String>,
    val actions: List<ActionConfig>,
    val singleFlight: ActionGroupSingleFlightConfig = ActionGroupSingleFlightConfig.disabled()
)

internal data class OutgoingConfig(
    val routes: List<OutgoingRoute>
)

internal data class OutgoingRoute(
    val id: String,
    val enabled: Boolean,
    val events: List<String>,
    val groups: List<Long>,
    val friends: List<Long>,
    val senders: List<Long>,
    val message: MessageMatcher,
    val condition: String?,
    val cooldown: OutgoingCooldownConfig,
    val actions: List<ActionConfig>,
    val singleFlight: ActionGroupSingleFlightConfig = ActionGroupSingleFlightConfig.disabled()
)

internal data class ActionGroupSingleFlightConfig(
    val enabled: Boolean,
    val key: String?,
    val notify: Boolean,
    val message: String
) {
    companion object {
        fun disabled(): ActionGroupSingleFlightConfig = ActionGroupSingleFlightConfig(
            enabled = false,
            key = null,
            notify = false,
            message = "上一项任务尚未完成，请等待完成后再试。"
        )
    }
}

internal data class OutgoingCooldownConfig(
    val enabled: Boolean,
    val personalMillis: Long,
    val administratorMillis: Long,
    val globalMillis: Long,
    val notify: Boolean,
    val message: String
) {
    companion object {
        fun disabled(): OutgoingCooldownConfig = OutgoingCooldownConfig(
            enabled = false,
            personalMillis = 0L,
            administratorMillis = 0L,
            globalMillis = 0L,
            notify = false,
            message = ""
        )
    }
}

internal data class MessageMatcher(
    val contains: List<String>,
    val startsWith: List<String>,
    val endsWith: List<String>,
    val regex: List<Regex>
)

internal data class ActionConfig(
    val id: String?,
    val type: String,
    val enabled: Boolean,
    val params: Map<String, Any?>
)

internal data class SecurityConfig(
    val allowCommandExecution: Boolean,
    val maxBodyBytes: Long
)

internal data class LoggingConfig(
    val request: Boolean,
    val response: Boolean,
    val errorStacktrace: Boolean,
    val debug: Boolean
)

internal data class ModelPlazaConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val timeoutMillis: Long,
    val auth: ModelPlazaAuthConfig?,
    val queries: ModelPlazaQueriesConfig = ModelPlazaQueriesConfig(
        models = ModelPlazaQueryConfig.modelsDefault(),
        groups = ModelPlazaQueryConfig.groupsDefault()
    ),
    val proxyUrl: String = ""
) {
    companion object {
        fun safeDefault(): ModelPlazaConfig = ModelPlazaConfig(
            enabled = false,
            baseUrl = "https://hk.geek2api.com/model-plaza",
            timeoutMillis = 30_000L,
            auth = null,
            queries = ModelPlazaQueriesConfig(
                models = ModelPlazaQueryConfig.modelsDefault(),
                groups = ModelPlazaQueryConfig.groupsDefault()
            ),
            proxyUrl = ""
        )
    }
}

internal data class ModelPlazaQueriesConfig(
    val models: ModelPlazaQueryConfig,
    val groups: ModelPlazaQueryConfig
)

internal data class ModelPlazaQueryConfig(
    val keywordExtraction: QueryKeywordExtractionConfig,
    val filters: QueryFilterConfig?,
    val sort: String,
    val limit: Int,
    val maxRelatedItems: Int,
    val responseFormat: ModelPlazaResponseFormatConfig
) {
    companion object {
        fun modelsDefault(): ModelPlazaQueryConfig = ModelPlazaQueryConfig(
            keywordExtraction = QueryKeywordExtractionConfig(
                removePrefixes = listOf("模型", "分组", "model", "group"),
                pattern = null,
                captureGroup = 1,
                trim = true,
                toLowerCase = false,
                requirePrefixMatch = true
            ),
            filters = null,
            sort = "source",
            limit = 0,
            maxRelatedItems = 0,
            responseFormat = ModelPlazaResponseFormatConfig(
                successTemplate = null,
                pendingMessage = "正在查询分组模型，请稍候...",
                failureMessage = "查询失败，请稍后重试",
                emptyMessage = "未找到包含该关键词的分组"
            )
        )

        fun groupsDefault(): ModelPlazaQueryConfig = modelsDefault().copy(
            responseFormat = ModelPlazaResponseFormatConfig(
                successTemplate = null,
                pendingMessage = "正在查询模型分组，请稍候...",
                failureMessage = "查询失败，请稍后重试",
                emptyMessage = "未找到包含该关键词的模型"
            )
        )
    }
}

internal data class ModelPlazaResponseFormatConfig(
    val successTemplate: String?,
    val pendingMessage: String,
    val failureMessage: String,
    val emptyMessage: String
)

internal data class ModelPlazaAuthConfig(
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

internal data class PolymarketConfig(
    val enabled: Boolean,
    val gammaApiBaseUrl: String,
    val clobApiBaseUrl: String,
    val timeoutMillis: Long,
    val locale: String = "zh",
    val searchFields: List<String> = listOf("question", "description"),
    val commandPrefix: String,
    val enabledGroups: List<Long>,
    val searchPageSize: Int = 100,
    val maxSearchPages: Int = 3,
    val whitelist: PolymarketWhitelistConfig,
    val keywordExtraction: QueryKeywordExtractionConfig?,
    val filters: QueryFilterConfig?,
    val responseFormat: PolymarketResponseFormatConfig?,
    val proxyUrl: String = ""
) {
    companion object {
        fun safeDefault(): PolymarketConfig = PolymarketConfig(
            enabled = false,
            gammaApiBaseUrl = "https://gamma-api.polymarket.com",
            clobApiBaseUrl = "https://clob.polymarket.com",
            timeoutMillis = 30_000L,
            locale = "zh",
            searchFields = listOf("question", "description"),
            commandPrefix = "poly",
            enabledGroups = emptyList(),
            searchPageSize = 100,
            maxSearchPages = 3,
            whitelist = PolymarketWhitelistConfig.default(),
            keywordExtraction = null,
            filters = null,
            responseFormat = null,
            proxyUrl = ""
        )
    }
}

internal data class PolymarketWhitelistConfig(
    val keywords: List<String>,
    val caseSensitive: Boolean,
    val rejectMessage: String
) {
    companion object {
        val DEFAULT_KEYWORDS: List<String> = listOf(
            "GPT",
            "ChatGPT",
            "o1",
            "o3",
            "o4-mini",
            "Claude",
            "Gemini",
            "Gemma",
            "Grok",
            "DeepSeek",
            "Qwen",
            "QwQ",
            "通义千问",
            "Llama",
            "Mistral",
            "Mixtral",
            "Kimi",
            "Moonshot",
            "豆包",
            "Doubao",
            "GLM",
            "ChatGLM",
            "智谱",
            "MiniMax",
            "海螺",
            "Hailuo",
            "ERNIE",
            "文心一言",
            "Baichuan",
            "百川",
            "Yi-Large",
            "Yi-Lightning",
            "零一万物",
            "Hunyuan",
            "混元",
            "阶跃星辰",
            "Step-1",
            "Step-2",
            "Step-3",
            "Phi",
            "Command R",
            "Cohere",
            "Nova"
        )

        fun default(): PolymarketWhitelistConfig = PolymarketWhitelistConfig(
            keywords = DEFAULT_KEYWORDS,
            caseSensitive = false,
            rejectMessage = "仅支持搜索白名单中的大模型相关市场；当前关键词：\${keyword}"
        )
    }
}

internal data class QueryKeywordExtractionConfig(
    val removePrefixes: List<String>,
    val pattern: String?,
    val captureGroup: Int,
    val trim: Boolean,
    val toLowerCase: Boolean,
    val requirePrefixMatch: Boolean
)

internal data class QueryFilterConfig(
    val blacklist: QueryBlacklistConfig?,
    val whitelist: QueryWhitelistConfig?,
    val length: QueryLengthConfig?,
    val pattern: QueryPatternConfig?
)

internal data class QueryBlacklistConfig(
    val enabled: Boolean,
    val keywords: List<String>,
    val caseSensitive: Boolean,
    val rejectMessage: String
)

internal data class QueryWhitelistConfig(
    val enabled: Boolean,
    val keywords: List<String>,
    val caseSensitive: Boolean,
    val rejectMessage: String
)

internal data class QueryLengthConfig(
    val min: Int?,
    val max: Int?,
    val rejectMessage: String
)

internal data class QueryPatternConfig(
    val pattern: String,
    val rejectMessage: String
)

internal data class PolymarketResponseFormatConfig(
    val maxHistoryPoints: Int,
    val dateFormat: String,
    val timezone: String,
    val compactNumbers: Boolean,
    val successTemplate: String?,
    val emptyTemplate: String?,
    val errorTemplate: String?,
    val outputMode: String = "image",
    val imageFallbackToText: Boolean = true,
    val imageWidthPx: Int = 1440
)

internal fun Any?.asMap(): Map<String, Any?> {
    val raw = this as? Map<*, *> ?: return emptyMap()
    return raw.mapNotNull { (key, value) ->
        key?.toString()?.let { it to value }
    }.toMap()
}

private fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key].asMap()

private fun Map<String, Any?>.list(key: String): List<Any?> {
    return (this[key] as? List<*>)?.map { it } ?: emptyList()
}

private fun Map<String, Any?>.string(key: String, default: String): String = stringOrNull(key) ?: default

private fun Map<String, Any?>.stringOrNull(key: String): String? = this[key]?.toString()?.trim()?.ifBlank { null }

private fun Map<String, Any?>.boolean(key: String, default: Boolean): Boolean {
    return when (val value = this[key]) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> default
    }
}

private fun Map<String, Any?>.int(key: String, default: Int): Int {
    return when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: default
        else -> default
    }
}

private fun Map<String, Any?>.long(key: String, default: Long): Long {
    return when (val value = this[key]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: default
        else -> default
    }
}

private fun Map<String, Any?>.stringList(key: String): List<String> {
    return when (val value = this[key]) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim()?.ifBlank { null } }
        is String -> listOf(value).filter { it.isNotBlank() }
        else -> emptyList()
    }
}

private fun Map<String, Any?>.longList(key: String): List<Long> {
    return when (val value = this[key]) {
        is List<*> -> value.mapNotNull { item ->
            when (item) {
                is Number -> item.toLong()
                is String -> item.toLongOrNull()
                else -> null
            }
        }
        is Number -> listOf(value.toLong())
        is String -> value.toLongOrNull()?.let { listOf(it) } ?: emptyList()
        else -> emptyList()
    }
}
