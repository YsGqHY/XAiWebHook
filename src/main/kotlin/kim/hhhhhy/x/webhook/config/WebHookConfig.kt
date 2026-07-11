package kim.hhhhhy.x.webhook.config

import kim.hhhhhy.x.webhook.XAiWebHook
import org.yaml.snakeyaml.Yaml
import java.io.File

internal object WebHookConfig {
    private const val MAX_OUTGOING_COOLDOWN_MILLIS = 604_800_000L
    private const val DEFAULT_OUTGOING_COOLDOWN_MESSAGE =
        "指令冷却中，请在 \${cooldown.remainingSeconds} 秒后重试。"

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
                headless = browserMap.boolean("headless", true),
                viewportWidth = browserMap.int("viewport_width", 1440).coerceIn(320, 7680),
                viewportHeight = browserMap.int("viewport_height", 1000).coerceIn(240, 4320),
                timeoutMillis = browserMap.long("timeout_ms", 30_000L).coerceIn(1_000L, 300_000L),
                optionalStepTimeoutMillis = browserMap.long("optional_step_timeout_ms", 1_000L)
                    .coerceIn(100L, 30_000L),
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

        return PluginConfig(
            server = server,
            auth = auth,
            templates = templates,
            browser = browser,
            incoming = incoming,
            outgoing = outgoing,
            actions = actions,
            security = security,
            logging = logging
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
            actions = parseActionList(map.list("actions"), globalActions)
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
            actions = parseActionList(map.list("actions"), globalActions)
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
    val logging: LoggingConfig
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
            logging = LoggingConfig(request = true, response = true, errorStacktrace = true, debug = false)
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
    val allowedHosts: List<String>,
    val maxScreenshotBytes: Long
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
            allowedHosts = emptyList(),
            maxScreenshotBytes = 10_485_760L
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
    val actions: List<ActionConfig>
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
    val actions: List<ActionConfig>
)

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
