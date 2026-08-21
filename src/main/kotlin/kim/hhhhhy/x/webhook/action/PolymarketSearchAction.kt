package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.ActionResult
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.polymarket.PolymarketClient
import kim.hhhhhy.x.webhook.polymarket.PolymarketEventCard
import kim.hhhhhy.x.webhook.polymarket.PolymarketEventCardOptions
import kim.hhhhhy.x.webhook.polymarket.PolymarketFormatter
import kim.hhhhhy.x.webhook.polymarket.PolymarketSearchResult
import kim.hhhhhy.x.webhook.template.TemplateEngine
import kim.hhhhhy.x.webhook.util.KeywordExtractor
import kim.hhhhhy.x.webhook.util.KeywordExtractionConfig
import kim.hhhhhy.x.webhook.util.FilterChain
import kim.hhhhhy.x.webhook.util.FilterConfig
import kim.hhhhhy.x.webhook.util.FilterResult
import kim.hhhhhy.x.webhook.util.WhitelistConfig
import kim.hhhhhy.x.webhook.util.LengthConfig
import kim.hhhhhy.x.webhook.util.PatternConfig
import kim.hhhhhy.x.webhook.util.FormatterConfig
import kim.hhhhhy.x.webhook.util.HttpProxySupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.MessageChainBuilder
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import java.net.URI
import java.time.Instant

internal object PolymarketSearchAction {

    internal enum class PolymarketOutputMode {
        IMAGE,
        TEXT,
        BOTH
    }

    internal data class PolymarketDeliveryOptions(
        val outputMode: PolymarketOutputMode,
        val imageFallbackToText: Boolean,
        val imageWidthPx: Int
    )

    internal enum class PolymarketDeliveryResult {
        IMAGE,
        TEXT,
        BOTH,
        IMAGE_FALLBACK_TEXT,
        NON_EVENT_TEXT
    }

    public suspend fun search(action: ActionConfig, context: ExecutionContext): ActionResult {
        val config = context.config.polymarket
        if (!config.enabled) {
            // 路由已匹配却因总开关未启用而终止，属于配置问题，需要可见提示而非静默
            XAiWebHook.logger.warning(
                "Polymarket route matched but polymarket.enabled is false; set polymarket.enabled: true"
            )
            notifyQuietly(action, context, "Polymarket 搜索未启用：请在 webhook_config.yml 中设置 polymarket.enabled: true 后执行 /xwebhook reload。")
            return ActionResult(
                type = action.type,
                success = false,
                message = "polymarket is disabled in config"
            )
        }
        val eventGroupId = context.event?.groupId
        if (config.enabledGroups.isNotEmpty() && eventGroupId !in config.enabledGroups) {
            XAiWebHook.logger.warning(
                "Polymarket blocked group=$eventGroupId; allowed=${config.enabledGroups}"
            )
            notifyQuietly(action, context, "当前群未在 polymarket.enabled_groups 允许列表中。")
            return ActionResult(
                type = action.type,
                success = false,
                message = "polymarket is not enabled for this group"
            )
        }

        // 从事件消息中提取搜索关键词
        val messageText = context.event?.messageText ?: ""
        val keyword = extractKeyword(action, context, messageText, config)

        if (keyword.isBlank()) {
            XAiWebHook.logger.warning("Polymarket keyword extraction produced empty result: '$messageText'")
            notifyQuietly(action, context, "请在命令后填写搜索关键词，例如：${config.commandPrefix} GPT-6")
            return ActionResult(
                type = action.type,
                success = false,
                message = "failed to extract keyword from message"
            )
        }

        WebHookDebug.log("[Polymarket] 提取到关键词 '$keyword'")

        // 使用 FilterChain 验证关键词
        val filterConfig = buildFilterConfig(config)
        val filterResult = FilterChain.validate(keyword, filterConfig)

        if (filterResult is FilterResult.Reject) {
            WebHookDebug.log("[Polymarket] 关键词 '$keyword' 被过滤器拒绝: ${filterResult.message}")
            val target = resolveTarget(action, context)
            val contact = resolveContact(target)

            contact.sendMessage(PlainText(filterResult.message))

            return ActionResult(
                type = action.type,
                success = false,
                message = "keyword rejected by filter"
            )
        }

        WebHookDebug.log("[Polymarket] 搜索关键词 '$keyword'")

        val target = resolveTarget(action, context)
        val contact = resolveContact(target)
        val pendingMessage = renderString(action.params["pending_message"], context)
        val failureMessage = renderString(action.params["failure_message"], context)
        val proxyUrl = actionProxyUrl(action, context, config.proxyUrl)

        try {
            if (pendingMessage.isNotBlank()) {
                contact.sendMessage(PlainText(pendingMessage))
            }

            val eventReference = parseEventReference(keyword)
            val result = if (eventReference != null) {
                fetchEventResult(config, proxyUrl, eventReference)
            } else {
                fetchKeywordEventResult(config, proxyUrl, keyword) ?: run {
                    // 公共事件搜索无相关命中时，保留原市场分页作为兼容回退。
                    val markets = fetchCandidateMarkets(config, proxyUrl)
                    val matchedMarkets = rankMatches(markets, keyword, config.searchFields)

                    if (matchedMarkets.isEmpty()) {
                        val emptyTemplate = action.params["empty_template"]?.toString()
                            ?: config.responseFormat?.emptyTemplate
                        val message = PolymarketFormatter.formatNoResults(keyword, emptyTemplate)
                        contact.sendMessage(PlainText(message))
                        return ActionResult(action.type, success = true, message = "no results", status = 200)
                    }

                    buildSearchResult(config, proxyUrl, matchedMarkets.first())
                }
            }

            // 文本始终生成，既支持 text/both，也作为图片渲染或上传失败时的可靠回退。
            val formatterConfig = buildFormatterConfig(config)
            val successTemplate = action.params["success_template"]?.toString()
                ?: config.responseFormat?.successTemplate
            val formattedMessage = PolymarketFormatter.formatSearchResult(
                result = result,
                template = successTemplate,
                config = formatterConfig
            )
            val deliveryOptions = deliveryOptions(action, context, config)
            val renderAndUploadImage: suspend () -> Image = {
                val event = requireNotNull(result.event) { "Polymarket image output requires an event result" }
                val markets = result.eventMarkets.ifEmpty { selectEventMarkets(event.markets) }
                require(markets.isNotEmpty()) { "Polymarket event has no markets to render: ${event.title}" }
                val eventPageUrl = result.eventPageUrl ?: eventPageUrl(event.slug, config.locale)
                val bytes = withContext(Dispatchers.IO) {
                    HtmlImageRenderer.render(
                        PolymarketEventCard.render(
                            event = event,
                            markets = markets,
                            eventPageUrl = eventPageUrl,
                            generatedAt = Instant.now(),
                            options = PolymarketEventCardOptions(
                                widthPx = deliveryOptions.imageWidthPx,
                                timezone = config.responseFormat?.timezone ?: "Asia/Shanghai"
                            )
                        )
                    )
                }
                WebHookDebug.log("[Polymarket] 图片渲染完成，bytes=${bytes.size}")
                bytes.toExternalResource("png").use { resource ->
                    contact.uploadImage(resource)
                }
            }
            val delivery = deliverSuccess(
                result = result,
                formattedMessage = formattedMessage,
                options = deliveryOptions,
                renderAndUploadImage = renderAndUploadImage,
                sendText = { text -> contact.sendMessage(PlainText(text)) },
                sendImage = { image -> contact.sendMessage(image) },
                sendBoth = { text, image ->
                    val builder = MessageChainBuilder()
                    if (text.isNotBlank()) {
                        builder.append(PlainText(text))
                        builder.append(PlainText("\n"))
                    }
                    builder.append(image)
                    contact.sendMessage(builder.build())
                },
                onImageFailure = { error ->
                    val recovery = if (deliveryOptions.imageFallbackToText) {
                        "falling back to text"
                    } else {
                        "text fallback is disabled"
                    }
                    XAiWebHook.logger.warning(
                        "Polymarket image delivery failed, $recovery: ${error.message}"
                    )
                }
            )

            WebHookDebug.log(
                "[Polymarket] 搜索成功，返回: ${result.event?.title ?: result.market.question}, " +
                    "delivery=${delivery.name.lowercase()}"
            )
            return ActionResult(
                action.type,
                success = true,
                message = "search completed (${delivery.name.lowercase()})",
                status = 200
            )

        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            XAiWebHook.logger.error("Polymarket search failed: ${error.message}", error)
            if (failureMessage.isNotBlank()) {
                runCatching { contact.sendMessage(PlainText(failureMessage)) }
            } else {
                val errorTemplate = config.responseFormat?.errorTemplate
                val errorMsg = PolymarketFormatter.formatError(keyword, error.message ?: "未知错误", errorTemplate)
                runCatching { contact.sendMessage(PlainText(errorMsg)) }
            }
            return ActionResult(
                type = action.type,
                success = false,
                message = error.message ?: "search failed",
                status = 500
            )
        }
    }

    /** 提前返回场景下尽力发送提示；联系人不可用时只记录日志，不影响动作结果 */
    private suspend fun notifyQuietly(
        action: ActionConfig,
        context: ExecutionContext,
        message: String
    ): Unit {
        runCatching {
            resolveContact(resolveTarget(action, context)).sendMessage(PlainText(message))
        }.onFailure { error ->
            WebHookDebug.log("[Polymarket] 提示发送失败：${error.message}")
        }
    }

    private fun extractKeyword(
        action: ActionConfig,
        context: ExecutionContext,
        messageText: String,
        config: kim.hhhhhy.x.webhook.config.PolymarketConfig
    ): String {
        // 优先使用配置的 keyword（支持模板变量）
        val configuredKeyword = action.params["keyword"]
        if (configuredKeyword != null) {
            return renderString(configuredKeyword, context).trim()
        }

        // 使用 KeywordExtractor 提取
        val extractionConfig = if (config.keywordExtraction != null) {
            KeywordExtractionConfig(
                removePrefixes = config.keywordExtraction.removePrefixes,
                pattern = config.keywordExtraction.pattern,
                captureGroup = config.keywordExtraction.captureGroup,
                trim = config.keywordExtraction.trim,
                toLowerCase = config.keywordExtraction.toLowerCase,
                requirePrefixMatch = config.keywordExtraction.requirePrefixMatch
            )
        } else {
            // 默认配置：移除命令前缀
            val commandPrefix = action.params["command_prefix"]?.toString() ?: config.commandPrefix
            KeywordExtractionConfig(
                removePrefixes = listOf(commandPrefix),
                pattern = null,
                captureGroup = 1,
                trim = true,
                toLowerCase = false,
                requirePrefixMatch = true
            )
        }

        return KeywordExtractor.extract(messageText, extractionConfig)
    }

    internal fun buildFilterConfig(
        config: kim.hhhhhy.x.webhook.config.PolymarketConfig
    ): FilterConfig {
        val lengthConfig = config.filters?.length?.let { filterLength ->
            LengthConfig(
                min = filterLength.min,
                max = filterLength.max,
                rejectMessage = filterLength.rejectMessage
            )
        }
        val patternConfig = config.filters?.pattern?.let { filterPattern ->
            PatternConfig(
                pattern = filterPattern.pattern,
                rejectMessage = filterPattern.rejectMessage
            )
        }

        return FilterConfig(
            blacklist = null,
            whitelist = WhitelistConfig(
                enabled = true,
                keywords = config.whitelist.keywords,
                caseSensitive = config.whitelist.caseSensitive,
                rejectMessage = config.whitelist.rejectMessage
            ),
            length = lengthConfig,
            pattern = patternConfig
        )
    }

    private suspend fun buildSearchResult(
        config: kim.hhhhhy.x.webhook.config.PolymarketConfig,
        proxyUrl: String,
        market: kim.hhhhhy.x.webhook.polymarket.PolymarketMarket
    ): PolymarketSearchResult {
        val assetId = market.clobTokenIds?.firstOrNull()
        val priceHistory = if (assetId != null) {
            PolymarketClient.getPriceHistory(
                clobApiBaseUrl = config.clobApiBaseUrl,
                timeoutMillis = config.timeoutMillis,
                assetId = assetId,
                interval = "1d",
                proxyUrl = proxyUrl
            )
        } else {
            emptyList()
        }
        return PolymarketSearchResult(
            market = market,
            priceHistory = priceHistory
        )
    }

    private suspend fun fetchKeywordEventResult(
        config: kim.hhhhhy.x.webhook.config.PolymarketConfig,
        proxyUrl: String,
        keyword: String
    ): PolymarketSearchResult? {
        val events = try {
            PolymarketClient.searchEvents(
                gammaApiBaseUrl = config.gammaApiBaseUrl,
                timeoutMillis = config.timeoutMillis,
                query = keyword,
                proxyUrl = proxyUrl
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            XAiWebHook.logger.warning(
                "Polymarket event search failed, falling back to market pages: ${error.message}"
            )
            return null
        }
        val event = filterEventMatchesInSearchOrder(events, keyword, config.searchFields).firstOrNull() ?: run {
            WebHookDebug.log("[Polymarket] 事件搜索无相关命中，回退市场分页")
            return null
        }
        val reference = PolymarketEventReference(
            slug = event.slug,
            pageUrl = eventPageUrl(event.slug, config.locale)
        )
        WebHookDebug.log("[Polymarket] 关键词事件命中，slug=${event.slug}")
        return fetchEventResult(config, proxyUrl, reference)
    }

    private suspend fun fetchEventResult(
        config: kim.hhhhhy.x.webhook.config.PolymarketConfig,
        proxyUrl: String,
        reference: PolymarketEventReference
    ): PolymarketSearchResult {
        val event = PolymarketClient.getEventBySlug(
            gammaApiBaseUrl = config.gammaApiBaseUrl,
            timeoutMillis = config.timeoutMillis,
            slug = reference.slug,
            locale = config.locale,
            proxyUrl = proxyUrl
        ) ?: error("未找到 Polymarket 事件：${reference.slug}")
        val markets = selectEventMarkets(event.markets)
        val primaryMarket = markets.firstOrNull()
            ?: error("Polymarket 事件没有可用子市场：${event.title}")
        return buildSearchResult(config, proxyUrl, primaryMarket).copy(
            event = event,
            eventMarkets = markets,
            eventPageUrl = reference.pageUrl
        )
    }

    internal fun selectEventMarkets(
        markets: List<kim.hhhhhy.x.webhook.polymarket.PolymarketMarket>
    ): List<kim.hhhhhy.x.webhook.polymarket.PolymarketMarket> {
        val openMarkets = markets.filter { it.closed != true && it.active != false }
        val candidates = if (openMarkets.isNotEmpty()) openMarkets else markets
        return candidates.sortedWith(
            compareBy<kim.hhhhhy.x.webhook.polymarket.PolymarketMarket> { it.effectiveEndDateIso.isNullOrBlank() }
                .thenBy { it.effectiveEndDateIso.orEmpty() }
                .thenByDescending { it.volume?.toDoubleOrNull() ?: 0.0 }
        )
    }

    internal fun parseEventReference(value: String): PolymarketEventReference? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (uri.host?.lowercase() !in setOf("polymarket.com", "www.polymarket.com")) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val eventIndex = segments.indexOfFirst { it.equals("event", ignoreCase = true) }
        if (eventIndex < 0 || eventIndex + 1 != segments.lastIndex) return null
        if (eventIndex > 1) return null
        val slug = segments[eventIndex + 1]
        if (!slug.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]*"))) return null
        return PolymarketEventReference(
            slug = slug,
            pageUrl = uri.toString().removeSuffix("/")
        )
    }

    private suspend fun fetchCandidateMarkets(
        config: kim.hhhhhy.x.webhook.config.PolymarketConfig,
        proxyUrl: String
    ): List<kim.hhhhhy.x.webhook.polymarket.PolymarketMarket> {
        val pageSize = config.searchPageSize.coerceIn(1, 500)
        val result = LinkedHashMap<String, kim.hhhhhy.x.webhook.polymarket.PolymarketMarket>()
        var page = 0
        var offset = 0
        while (page < config.maxSearchPages.coerceIn(1, 20)) {
            val current = PolymarketClient.searchMarkets(
                gammaApiBaseUrl = config.gammaApiBaseUrl,
                timeoutMillis = config.timeoutMillis,
                limit = pageSize,
                offset = offset,
                locale = config.locale,
                proxyUrl = proxyUrl
            )
            current.forEach { market ->
                val key = market.id.ifBlank { market.question }
                result.putIfAbsent(key, market)
            }
            if (current.size < pageSize) break
            page++
            offset += pageSize
        }
        return result.values.toList()
    }

    internal fun filterEventMatchesInSearchOrder(
        events: List<kim.hhhhhy.x.webhook.polymarket.PolymarketEvent>,
        keyword: String,
        searchFields: List<String>
    ): List<kim.hhhhhy.x.webhook.polymarket.PolymarketEvent> {
        val normalized = normalizeSearchText(keyword)
        if (normalized.isBlank()) return emptyList()
        val matchQuestion = searchFields.isEmpty() || searchFields.contains("question")
        val matchDescription = searchFields.contains("description")

        // public-search 已按与网页相同的相关性排序；这里只过滤无效或无关项，不能再按交易量重排。
        return events.filter { event ->
            if (event.closed == true || event.active == false) return@filter false
            val titleMatches = matchQuestion && containsSearchPhrase(event.title, normalized)
            val slugMatches = matchQuestion && containsSearchPhrase(event.slug, normalized)
            val descriptionMatches = matchDescription && containsSearchPhrase(event.description.orEmpty(), normalized)
            val marketMatches = event.markets.any { market ->
                (matchQuestion && containsSearchPhrase(market.question, normalized)) ||
                    (matchDescription && containsSearchPhrase(market.description.orEmpty(), normalized))
            }
            titleMatches || slugMatches || descriptionMatches || marketMatches
        }
    }

    internal fun eventPageUrl(slug: String, locale: String): String {
        val language = locale.trim().lowercase().substringBefore('-')
            .takeIf { it.matches(Regex("[a-z]{2}")) && it != "en" }
        val localePath = language?.let { "/$it" }.orEmpty()
        return "https://polymarket.com$localePath/event/${slug.trim()}"
    }

    private fun normalizeSearchText(value: String): String {
        return value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun containsSearchPhrase(value: String, normalizedKeyword: String): Boolean {
        val normalizedValue = normalizeSearchText(value)
        return " $normalizedValue ".contains(" $normalizedKeyword ")
    }

    internal fun rankMatches(
        markets: List<kim.hhhhhy.x.webhook.polymarket.PolymarketMarket>,
        keyword: String,
        searchFields: List<String>
    ): List<kim.hhhhhy.x.webhook.polymarket.PolymarketMarket> {
        val normalized = keyword.trim().lowercase()
        val matchQuestion = searchFields.isEmpty() || searchFields.contains("question")
        val matchDescription = searchFields.contains("description")
        return markets.mapNotNull { market ->
            val question = if (matchQuestion) market.question.lowercase() else ""
            val description = if (matchDescription) market.description?.lowercase().orEmpty() else ""
            val score = when {
                matchQuestion && question == normalized -> 1_000
                matchQuestion && question.startsWith(normalized) -> 800
                matchQuestion && question.contains(normalized) -> 600
                matchDescription && description.contains(normalized) -> 300
                else -> 0
            }
            if (score == 0) null else score to market
        }.sortedWith(
            compareByDescending<Pair<Int, kim.hhhhhy.x.webhook.polymarket.PolymarketMarket>> { it.first }
                .thenByDescending { it.second.volume?.toDoubleOrNull() ?: 0.0 }
        ).map { it.second }
    }

    internal fun deliveryOptions(
        action: ActionConfig,
        context: ExecutionContext,
        config: kim.hhhhhy.x.webhook.config.PolymarketConfig
    ): PolymarketDeliveryOptions {
        val responseFormat = config.responseFormat
        val outputModeValue = actionParam(action, "output_mode", context)
            ?: responseFormat?.outputMode
            ?: "image"
        val fallbackValue = actionParam(action, "image_fallback_to_text", context)
        val widthValue = actionParam(action, "image_width_px", context)
        return PolymarketDeliveryOptions(
            outputMode = when (outputModeValue.trim().lowercase()) {
                "text" -> PolymarketOutputMode.TEXT
                "both" -> PolymarketOutputMode.BOTH
                else -> PolymarketOutputMode.IMAGE
            },
            imageFallbackToText = parseBoolean(fallbackValue, responseFormat?.imageFallbackToText ?: true),
            imageWidthPx = (widthValue?.toIntOrNull() ?: responseFormat?.imageWidthPx ?: 1440)
                .coerceIn(900, 2400)
        )
    }

    internal suspend fun <T> deliverSuccess(
        result: PolymarketSearchResult,
        formattedMessage: String,
        options: PolymarketDeliveryOptions,
        renderAndUploadImage: suspend () -> T,
        sendText: suspend (String) -> Unit,
        sendImage: suspend (T) -> Unit,
        sendBoth: suspend (String, T) -> Unit,
        onImageFailure: (Throwable) -> Unit = {}
    ): PolymarketDeliveryResult {
        if (options.outputMode == PolymarketOutputMode.TEXT) {
            sendText(formattedMessage)
            return PolymarketDeliveryResult.TEXT
        }
        if (result.event == null || result.eventMarkets.isEmpty()) {
            sendText(formattedMessage)
            return PolymarketDeliveryResult.NON_EVENT_TEXT
        }
        return try {
            val image = renderAndUploadImage()
            when (options.outputMode) {
                PolymarketOutputMode.IMAGE -> {
                    sendImage(image)
                    PolymarketDeliveryResult.IMAGE
                }
                PolymarketOutputMode.BOTH -> {
                    sendBoth(formattedMessage, image)
                    PolymarketDeliveryResult.BOTH
                }
                PolymarketOutputMode.TEXT -> error("text output should have returned before image rendering")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onImageFailure(error)
            if (!options.imageFallbackToText) throw error
            sendText(formattedMessage)
            PolymarketDeliveryResult.IMAGE_FALLBACK_TEXT
        }
    }

    private fun actionParam(action: ActionConfig, key: String, context: ExecutionContext): String? {
        if (!action.params.containsKey(key)) return null
        return renderString(action.params[key], context).trim()
    }

    private fun parseBoolean(value: String?, defaultValue: Boolean): Boolean {
        return when (value?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> defaultValue
        }
    }

    private fun buildFormatterConfig(
        config: kim.hhhhhy.x.webhook.config.PolymarketConfig
    ): FormatterConfig? {
        val responseFormat = config.responseFormat ?: return null
        return FormatterConfig(
            dateFormat = responseFormat.dateFormat,
            timezone = responseFormat.timezone,
            compactNumbers = responseFormat.compactNumbers,
            priceUnit = "cents",
            maxHistoryPoints = responseFormat.maxHistoryPoints.coerceIn(1, 50)
        )
    }

    internal data class PolymarketEventReference(
        val slug: String,
        val pageUrl: String
    )

    private fun resolveTarget(action: ActionConfig, context: ExecutionContext): ScreenshotTarget {
        val groupId = action.params["group_id"]?.toString()?.toLongOrNull()
            ?: context.event?.groupId
        val friendId = action.params["friend_id"]?.toString()?.toLongOrNull()
            ?: context.event?.friendId

        return when {
            groupId != null -> ScreenshotTarget.Group(groupId)
            friendId != null -> ScreenshotTarget.Friend(friendId)
            else -> error("no valid target: group_id or friend_id required")
        }
    }

    internal fun actionProxyUrl(
        action: ActionConfig,
        context: ExecutionContext,
        configuredProxyUrl: String
    ): String {
        if (!action.params.containsKey("proxy")) return configuredProxyUrl
        return HttpProxySupport.normalize(renderString(action.params["proxy"], context))
    }

    private fun renderString(template: Any?, context: ExecutionContext): String {
        if (template == null) return ""
        val templateStr = template.toString()
        return TemplateEngine.renderString(templateStr, context)
    }

    private fun resolveContact(target: ScreenshotTarget): Contact {
        val bot = XAiWebHook.bot ?: error("no bot instance available")
        return when (target) {
            is ScreenshotTarget.Group -> bot.getGroup(target.id) ?: error("group not found: ${target.id}")
            is ScreenshotTarget.Friend -> bot.getFriend(target.id) ?: error("friend not found: ${target.id}")
        }
    }
}
