package kim.hhhhhy.x.webhook.api

import kim.hhhhhy.x.webhook.action.WebPageScreenshotAction
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.PluginConfig
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.model.ExecutionContext

public data class BrowserTextListRequest(
    public val actionId: String,
    public val partId: String,
    public val itemSelector: String,
    public val nameRelativeSelector: String? = null,
    public val statusRelativeSelector: String,
    public val emptySelector: String? = null,
    public val maxItems: Int = 128
) {
    private var containerSelectorValue: String? = null
    private var containerAttributesValue: List<String> = emptyList()
    private var itemAttributesValue: List<String> = emptyList()

    public val containerSelector: String?
        get() = containerSelectorValue

    public val containerAttributes: List<String>
        get() = containerAttributesValue

    public val itemAttributes: List<String>
        get() = itemAttributesValue

    /**
     * 扩展构造器：旧的 7 参数 JVM 构造器保持不变，新增 DOM 属性读取能力不破坏已有调用方。
     */
    public constructor(
        actionId: String,
        partId: String,
        itemSelector: String,
        nameRelativeSelector: String?,
        statusRelativeSelector: String,
        emptySelector: String?,
        maxItems: Int,
        containerSelector: String?,
        containerAttributes: List<String>,
        itemAttributes: List<String>
    ) : this(
        actionId = actionId,
        partId = partId,
        itemSelector = itemSelector,
        nameRelativeSelector = nameRelativeSelector,
        statusRelativeSelector = statusRelativeSelector,
        emptySelector = emptySelector,
        maxItems = maxItems
    ) {
        configureAttributes(containerSelector, containerAttributes, itemAttributes)
    }

    init {
        requireIdentifier(actionId, "actionId")
        requireIdentifier(partId, "partId")
        requireSelector(itemSelector, "itemSelector")
        nameRelativeSelector?.let { requireRelativeSelector(it, "nameRelativeSelector") }
        requireRelativeSelector(statusRelativeSelector, "statusRelativeSelector")
        emptySelector?.let { requireSelector(it, "emptySelector") }
        require(maxItems in 1..256) { "maxItems must be between 1 and 256" }
    }

    private fun configureAttributes(
        containerSelector: String?,
        containerAttributes: List<String>,
        itemAttributes: List<String>
    ): Unit {
        containerSelector?.let { requireSelector(it, "containerSelector") }
        requireAttributeNames(containerAttributes, "containerAttributes")
        requireAttributeNames(itemAttributes, "itemAttributes")
        containerSelectorValue = containerSelector
        containerAttributesValue = containerAttributes.toList()
        itemAttributesValue = itemAttributes.toList()
    }

    private companion object {
        private const val MAX_IDENTIFIER_LENGTH = 100
        private const val MAX_SELECTOR_LENGTH = 2_000
        private const val MAX_ATTRIBUTE_NAMES = 16
        private const val MAX_ATTRIBUTE_NAME_LENGTH = 100
        private val ATTRIBUTE_NAME = Regex("[A-Za-z_:][A-Za-z0-9_.:-]*")
        private val RELATIVE_XPATH_ESCAPE_AXIS = Regex(
            "(?:^|/|\\[)(?:ancestor|parent|preceding|following|self)::",
            RegexOption.IGNORE_CASE
        )

        private fun requireIdentifier(value: String, field: String): Unit {
            require(value.isNotBlank()) { "$field must not be blank" }
            require(value.length <= MAX_IDENTIFIER_LENGTH) {
                "$field must be at most $MAX_IDENTIFIER_LENGTH characters"
            }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "$field must not contain control characters"
            }
        }

        private fun requireSelector(value: String, field: String): Unit {
            require(value.isNotBlank()) { "$field must not be blank" }
            require(value.length <= MAX_SELECTOR_LENGTH) {
                "$field must be at most $MAX_SELECTOR_LENGTH characters"
            }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "$field must not contain control characters"
            }
        }

        private fun requireRelativeSelector(value: String, field: String): Unit {
            requireSelector(value, field)
            val normalized = value.trim()
            val xpath = normalized.takeIf { it.startsWith("xpath=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
            val relativeXPath = xpath ?: normalized.takeIf {
                it.startsWith("./") || it.startsWith(".//")
            }
            require(
                when {
                    relativeXPath != null -> {
                        '|' !in relativeXPath &&
                            ".." !in relativeXPath &&
                            !RELATIVE_XPATH_ESCAPE_AXIS.containsMatchIn(relativeXPath)
                    }
                    normalized.startsWith("/") || normalized.startsWith("(") -> false
                    else -> true
                }
            ) { "$field XPath must be relative to the current item" }
        }

        private fun requireAttributeNames(values: List<String>, field: String): Unit {
            require(values.size <= MAX_ATTRIBUTE_NAMES) {
                "$field must contain at most $MAX_ATTRIBUTE_NAMES entries"
            }
            values.forEach { value ->
                require(value.length <= MAX_ATTRIBUTE_NAME_LENGTH && ATTRIBUTE_NAME.matches(value)) {
                    "$field contains an invalid HTML attribute name: $value"
                }
            }
        }
    }
}

public data class BrowserTextListItem(
    public val index: Int,
    public val itemText: String?,
    public val nameText: String?,
    public val statusText: String?,
    public val domId: String?,
    public val dataChannelId: String?,
    public val ariaLabel: String?
) {
    private var attributesValue: Map<String, String?> = emptyMap()

    public val attributes: Map<String, String?>
        get() = attributesValue

    public constructor(
        index: Int,
        itemText: String?,
        nameText: String?,
        statusText: String?,
        domId: String?,
        dataChannelId: String?,
        ariaLabel: String?,
        attributes: Map<String, String?>
    ) : this(index, itemText, nameText, statusText, domId, dataChannelId, ariaLabel) {
        attributesValue = attributes.toMap()
    }
}

public data class BrowserTextListResult(
    public val effectiveUrl: String,
    public val items: List<BrowserTextListItem>,
    public val emptyState: Boolean
) {
    private var containerPresentValue: Boolean = true
    private var containerAttributesValue: Map<String, String?> = emptyMap()

    public val containerPresent: Boolean
        get() = containerPresentValue

    public val containerAttributes: Map<String, String?>
        get() = containerAttributesValue

    public constructor(
        effectiveUrl: String,
        items: List<BrowserTextListItem>,
        emptyState: Boolean,
        containerPresent: Boolean,
        containerAttributes: Map<String, String?>
    ) : this(effectiveUrl, items, emptyState) {
        containerPresentValue = containerPresent
        containerAttributesValue = containerAttributes.toMap()
    }
}

public object XAiWebHookBrowserApi {
    public val API_VERSION: Int = 2

    public suspend fun queryTextList(request: BrowserTextListRequest): BrowserTextListResult {
        return queryTextListInternal(
            request = request,
            config = WebHookConfig.current,
            statusAttribute = null,
            environment = System.getenv()
        )
    }

    public suspend fun queryTextList(
        request: BrowserTextListRequest,
        statusAttribute: String
    ): BrowserTextListResult {
        return queryTextListInternal(
            request = request,
            config = WebHookConfig.current,
            statusAttribute = normalizeAttributeName(statusAttribute),
            environment = System.getenv()
        )
    }

    internal suspend fun queryTextList(
        request: BrowserTextListRequest,
        config: PluginConfig,
        environment: Map<String, String> = System.getenv()
    ): BrowserTextListResult {
        return queryTextListInternal(
            request = request,
            config = config,
            statusAttribute = null,
            environment = environment
        )
    }

    internal suspend fun queryTextList(
        request: BrowserTextListRequest,
        config: PluginConfig,
        statusAttribute: String?,
        environment: Map<String, String> = System.getenv()
    ): BrowserTextListResult {
        return queryTextListInternal(
            request = request,
            config = config,
            statusAttribute = statusAttribute?.let(::normalizeAttributeName),
            environment = environment
        )
    }

    private suspend fun queryTextListInternal(
        request: BrowserTextListRequest,
        config: PluginConfig,
        statusAttribute: String?,
        environment: Map<String, String>
    ): BrowserTextListResult {
        val action = resolveAction(config, request)
        return WebPageScreenshotAction.queryTextList(
            action = action,
            context = ExecutionContext(config),
            request = request,
            statusAttribute = statusAttribute,
            environment = environment
        )
    }

    internal fun resolveAction(config: PluginConfig, request: BrowserTextListRequest): ActionConfig {
        val action = config.actions[request.actionId]
            ?: error("browser action not found: ${request.actionId}")
        require(action.enabled) { "browser action is disabled: ${request.actionId}" }
        require(action.type == "send_webpage_screenshot") {
            "browser action must use type send_webpage_screenshot: ${request.actionId}"
        }
        return action
    }

    private fun normalizeAttributeName(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "statusAttribute must not be blank" }
        require(normalized.length <= MAX_ATTRIBUTE_NAME_LENGTH) {
            "statusAttribute must be at most $MAX_ATTRIBUTE_NAME_LENGTH characters"
        }
        require(ATTRIBUTE_NAME.matches(normalized)) {
            "statusAttribute must be a valid HTML attribute name"
        }
        return normalized
    }

    private const val MAX_ATTRIBUTE_NAME_LENGTH: Int = 100
    private val ATTRIBUTE_NAME: Regex = Regex("[A-Za-z_:][A-Za-z0-9_.:-]*")
}
