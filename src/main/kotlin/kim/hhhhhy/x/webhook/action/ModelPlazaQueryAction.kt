package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.ModelPlazaQueryConfig
import kim.hhhhhy.x.webhook.config.ModelPlazaResponseFormatConfig
import kim.hhhhhy.x.webhook.config.QueryFilterConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.ActionResult
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.scraper.GroupModelsRelation
import kim.hhhhhy.x.webhook.scraper.GroupPricingDetail
import kim.hhhhhy.x.webhook.scraper.ModelGroupRelation
import kim.hhhhhy.x.webhook.scraper.ModelPlazaScraper
import kim.hhhhhy.x.webhook.scraper.ModelPricingDetail
import kim.hhhhhy.x.webhook.template.TemplateEngine
import kim.hhhhhy.x.webhook.util.BlacklistConfig
import kim.hhhhhy.x.webhook.util.FilterChain
import kim.hhhhhy.x.webhook.util.FilterConfig
import kim.hhhhhy.x.webhook.util.FilterResult
import kim.hhhhhy.x.webhook.util.KeywordExtractionConfig
import kim.hhhhhy.x.webhook.util.KeywordExtractor
import kim.hhhhhy.x.webhook.util.HttpProxySupport
import kim.hhhhhy.x.webhook.util.LengthConfig
import kim.hhhhhy.x.webhook.util.PatternConfig
import kim.hhhhhy.x.webhook.util.WhitelistConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.message.data.MessageChainBuilder
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource

internal object ModelPlazaQueryAction {
    public suspend fun queryModels(action: ActionConfig, context: ExecutionContext): ActionResult = execute(
        action = action,
        context = context,
        queryConfig = context.config.modelPlaza.queries.models,
        query = QueryKind.MODELS
    )

    public suspend fun queryGroups(action: ActionConfig, context: ExecutionContext): ActionResult = execute(
        action = action,
        context = context,
        queryConfig = context.config.modelPlaza.queries.groups,
        query = QueryKind.GROUPS
    )

    private suspend fun execute(
        action: ActionConfig,
        context: ExecutionContext,
        queryConfig: ModelPlazaQueryConfig,
        query: QueryKind
    ): ActionResult {
        val configuredConfig = context.config.modelPlaza
        val config = configuredConfig.copy(
            proxyUrl = actionProxyUrl(action, context, configuredConfig.proxyUrl)
        )
        if (!config.enabled) {
            XAiWebHook.logger.warning(
                "Model plaza route matched but model_plaza.enabled is false; set model_plaza.enabled: true"
            )
            notifyQuietly(action, context, "Model Plaza 查询未启用：请在 webhook_config.yml 中设置 model_plaza.enabled: true 后执行 /xwebhook reload。")
            return ActionResult(
                type = action.type,
                success = false,
                message = "model_plaza is disabled in config"
            )
        }

        val messageText = context.event?.messageText ?: ""
        val pattern = extractQueryPattern(action, context, messageText, queryConfig)
        if (pattern.isBlank()) {
            XAiWebHook.logger.warning("Model plaza keyword extraction produced empty result: '$messageText'")
            notifyQuietly(action, context, "请在命令后填写查询关键词，例如：模型 gpt")
            return ActionResult(
                type = action.type,
                success = false,
                message = "failed to extract query pattern from message"
            )
        }

        val target = WebHookActionExecutor.resolveScreenshotTarget(action, context)
        val contact = resolveContact(target)
        val responseFormat = effectiveResponseFormat(action, queryConfig.responseFormat)
        val pendingMessage = ModelPlazaFormatter.renderMessage(responseFormat.pendingMessage, pattern, context)
        val failureMessage = ModelPlazaFormatter.renderMessage(responseFormat.failureMessage, pattern, context)

        val filterResult = FilterChain.validate(pattern, toFilterConfig(queryConfig.filters))
        if (filterResult is FilterResult.Reject) {
            WebHookDebug.log("[ModelPlaza] 关键词 '$pattern' 被过滤器拒绝: ${filterResult.message}")
            contact.sendMessage(PlainText(filterResult.message))
            return ActionResult(action.type, success = false, message = "query rejected by filter", status = 400)
        }

        try {
            if (pendingMessage.isNotBlank()) contact.sendMessage(PlainText(pendingMessage))
            WebHookDebug.log("[ModelPlaza] ${query.logLabel} '$pattern'")

            when (query) {
                QueryKind.MODELS -> {
                    val details = prepareModelPricingDetails(
                        ModelPlazaScraper.queryModelPricingDetails(
                            config = config,
                            modelPattern = pattern,
                            maxModels = effectiveLimit(action, queryConfig),
                            maxGroupsPerModel = effectiveRelatedLimit(action, queryConfig),
                            includeEndpoints = effectiveIncludeEndpoints(action, queryConfig)
                        ),
                        action,
                        queryConfig
                    )
                    if (details.isEmpty()) {
                        contact.sendMessage(PlainText(
                            ModelPlazaFormatter.renderMessage(responseFormat.emptyMessage, pattern, context)
                        ))
                    } else {
                        val text = ModelPlazaFormatter.formatModelPricing(pattern, details, responseFormat, action, context)
                        deliver(
                            contact = contact,
                            text = text,
                            responseFormat = responseFormat,
                            action = action,
                            context = context,
                            sections = ModelPlazaPriceCard.modelSections(details),
                            title = details.firstOrNull()?.modelName,
                            options = cardOptions(action, responseFormat)
                        )
                    }
                    WebHookDebug.log("[ModelPlaza] 模型查询成功，共 ${details.size} 个模型")
                }
                QueryKind.GROUPS -> {
                    val details = prepareGroupPricingDetails(
                        ModelPlazaScraper.queryGroupPricingDetails(
                            config = config,
                            groupPattern = pattern,
                            maxGroups = effectiveLimit(action, queryConfig),
                            maxModelsPerGroup = effectiveRelatedLimit(action, queryConfig)
                        ),
                        action,
                        queryConfig
                    )
                    if (details.isEmpty()) {
                        contact.sendMessage(PlainText(
                            ModelPlazaFormatter.renderMessage(responseFormat.emptyMessage, pattern, context)
                        ))
                    } else {
                        val text = ModelPlazaFormatter.formatGroupPricing(pattern, details, responseFormat, action, context)
                        deliver(
                            contact = contact,
                            text = text,
                            responseFormat = responseFormat,
                            action = action,
                            context = context,
                            sections = ModelPlazaPriceCard.groupSections(details),
                            title = details.firstOrNull()?.groupName,
                            options = cardOptions(action, responseFormat)
                        )
                    }
                    WebHookDebug.log("[ModelPlaza] 分组查询成功，共 ${details.size} 个分组")
                }
            }
            return ActionResult(action.type, success = true, message = "query completed", status = 200)
        } catch (error: Throwable) {
            XAiWebHook.logger.error("Model plaza ${query.logLabel} failed: ${error.message}", error)
            if (failureMessage.isNotBlank()) {
                runCatching { contact.sendMessage(PlainText(failureMessage)) }
            }
            return ActionResult(
                type = action.type,
                success = false,
                message = error.message ?: "query failed",
                status = 500
            )
        }
    }

    /** 提前返回场景下尽力发送提示；联系人不可用时只记录日志 */
    private suspend fun notifyQuietly(
        action: ActionConfig,
        context: ExecutionContext,
        message: String
    ): Unit {
        runCatching {
            resolveContact(WebHookActionExecutor.resolveScreenshotTarget(action, context))
                .sendMessage(PlainText(message))
        }.onFailure { error ->
            WebHookDebug.log("[ModelPlaza] 提示发送失败：${error.message}")
        }
    }

    private fun extractQueryPattern(
        action: ActionConfig,
        context: ExecutionContext,
        messageText: String,
        queryConfig: ModelPlazaQueryConfig
    ): String {
        action.params["query_pattern"]?.let { configuredPattern ->
            return renderString(configuredPattern, context).trim()
        }

        val configuredPrefixes = action.params["prefixes"].toStringList()
        val configuredPattern = action.params["keyword_regex"]?.toString()
        val extraction = queryConfig.keywordExtraction
        return KeywordExtractor.extract(
            message = messageText,
            config = KeywordExtractionConfig(
                removePrefixes = configuredPrefixes.ifEmpty { extraction.removePrefixes },
                pattern = configuredPattern ?: extraction.pattern,
                captureGroup = action.params["capture_group"].toString().toIntOrNull()
                    ?: extraction.captureGroup,
                trim = extraction.trim,
                toLowerCase = extraction.toLowerCase,
                requirePrefixMatch = extraction.requirePrefixMatch
            )
        )
    }

    internal fun effectiveResponseFormat(
        action: ActionConfig,
        configured: ModelPlazaResponseFormatConfig
    ): ModelPlazaResponseFormatConfig = configured.copy(
        pendingMessage = actionMessageOverride(action.params, "pending_message", configured.pendingMessage),
        failureMessage = actionMessageOverride(action.params, "failure_message", configured.failureMessage),
        emptyMessage = actionMessageOverride(action.params, "empty_message", configured.emptyMessage)
    )

    /** action 未声明该键时用配置值；声明为空或 null 时表示显式关闭该提示 */
    internal fun actionMessageOverride(
        params: Map<String, Any?>,
        key: String,
        fallback: String
    ): String {
        if (!params.containsKey(key)) return fallback
        return params[key]?.toString() ?: ""
    }

    private fun toFilterConfig(config: QueryFilterConfig?): FilterConfig {
        if (config == null) return FilterConfig()
        return FilterConfig(
            blacklist = config.blacklist?.let {
                BlacklistConfig(
                    enabled = it.enabled,
                    keywords = it.keywords,
                    caseSensitive = it.caseSensitive,
                    rejectMessage = it.rejectMessage
                )
            },
            whitelist = config.whitelist?.let {
                WhitelistConfig(
                    enabled = it.enabled,
                    keywords = it.keywords,
                    caseSensitive = it.caseSensitive,
                    rejectMessage = it.rejectMessage
                )
            },
            length = config.length?.let {
                LengthConfig(it.min, it.max, it.rejectMessage)
            },
            pattern = config.pattern?.let {
                PatternConfig(it.pattern, it.rejectMessage)
            }
        )
    }

    /**
     * 模型结果只做排序与“每个模型保留多少个分组”的裁剪。
     *
     * 模型数量上限已经在下发查询时生效，避免为不会被展示的模型请求端点。
     */
    internal fun prepareModelPricingDetails(
        details: List<ModelPricingDetail>,
        action: ActionConfig,
        config: ModelPlazaQueryConfig
    ): List<ModelPricingDetail> {
        val sorted = when (effectiveSort(action, config)) {
            "alphabetical" -> details.sortedBy { it.modelName.lowercase() }
            else -> details
        }
        val nestedLimit = effectiveRelatedLimit(action, config)
        return sorted.map { detail ->
            detail.copy(groups = if (nestedLimit > 0) detail.groups.take(nestedLimit) else detail.groups)
        }
    }

    /** 分组结果只做排序与“每个分组保留多少个模型”的裁剪。 */
    internal fun prepareGroupPricingDetails(
        details: List<GroupPricingDetail>,
        action: ActionConfig,
        config: ModelPlazaQueryConfig
    ): List<GroupPricingDetail> {
        val sorted = when (effectiveSort(action, config)) {
            "alphabetical" -> details.sortedBy { it.groupName.lowercase() }
            else -> details
        }
        val nestedLimit = effectiveRelatedLimit(action, config)
        return sorted.map { detail ->
            detail.copy(models = if (nestedLimit > 0) detail.models.take(nestedLimit) else detail.models)
        }
    }

    private fun effectiveIncludeEndpoints(action: ActionConfig, config: ModelPlazaQueryConfig): Boolean {
        if (action.params.containsKey("include_endpoints")) {
            return action.params["include_endpoints"].toString().trim().lowercase() in setOf("true", "1", "yes", "on")
        }
        return config.maxEndpointLookups != 0
    }

    internal fun cardOptions(
        action: ActionConfig,
        responseFormat: ModelPlazaResponseFormatConfig
    ): ModelPlazaCardOptions {
        val width = action.params["image_width_px"]?.toString()?.trim()?.toIntOrNull()
            ?: responseFormat.imageWidthPx
        return ModelPlazaCardOptions(
            widthPx = width.coerceIn(720, 1600),
            generatedAt = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.Instant.now())
        )
    }

    /**
     * 按 output_mode 投递查询结果：image 只发图片，text 只发文本，both 文本 + 图片。
     *
     * 文本始终会被构造，用作图片渲染或上传失败时的回退，避免查询结果直接丢失。
     * 结果按 `image_max_bytes` 预算拆成多页，每页单独一张图片，避免单图越过 OneBot 上传上限。
     */
    private suspend fun deliver(
        contact: Contact,
        text: String,
        responseFormat: ModelPlazaResponseFormatConfig,
        action: ActionConfig,
        context: ExecutionContext,
        sections: List<String>,
        title: String?,
        options: ModelPlazaCardOptions
    ): Unit {
        val outputMode = action.params["output_mode"]?.let { raw ->
            TemplateEngine.renderString(raw.toString(), context).trim().lowercase()
        }?.takeIf { it == "image" || it == "text" || it == "both" } ?: responseFormat.outputMode
        val fallbackToText = action.params["image_fallback_to_text"]?.let { raw ->
            TemplateEngine.renderString(raw.toString(), context).trim().lowercase() in setOf("true", "1", "yes", "on")
        } ?: responseFormat.imageFallbackToText

        if (outputMode == "text") {
            contact.sendMessage(PlainText(text))
            return
        }

        val images = try {
            val pages = withContext(Dispatchers.IO) {
                ModelPlazaImagePacker.paginate(
                    sections = sections,
                    maxBytes = effectiveMaxBytes(action, responseFormat),
                    renderPage = { chunk -> HtmlImageRenderer.render(ModelPlazaPriceCard.renderPage(chunk, options, title)) }
                )
            }
            logPageSizes(pages, sections.size)
            pages.map { bytes ->
                bytes.toExternalResource("png").use { resource -> contact.uploadImage(resource) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!fallbackToText) throw error
            WebHookDebug.log("[ModelPlaza] 图片投递失败，回退为文本：${error.message}")
            contact.sendMessage(PlainText(text))
            return
        }

        if (outputMode == "both") {
            val builder = MessageChainBuilder()
            if (text.isNotBlank()) {
                builder.append(PlainText(text))
                builder.append(PlainText("\n"))
            }
            images.forEach { builder.append(it) }
            contact.sendMessage(builder.build())
        } else {
            if (images.size == 1) {
                contact.sendMessage(images.first())
            } else {
                val builder = MessageChainBuilder()
                images.forEach { builder.append(it) }
                contact.sendMessage(builder.build())
            }
        }
    }

    /** 记录每页字节数，便于判断上传失败时是否因单图过大。 */
    private fun logPageSizes(pages: List<ByteArray>, sectionCount: Int): Unit {
        val detail = pages.mapIndexed { index, bytes -> "${index + 1}:${bytes.size}" }.joinToString(", ")
        WebHookDebug.log("[ModelPlaza] 卡片分页完成，区块=$sectionCount，页数=${pages.size}，字节[$detail]")
        if (pages.size == 1 && sectionCount > 1) {
            WebHookDebug.log("[ModelPlaza] 单页容量告警：整卡仍超过字节预算，可调小 image_width_px 或增大 image_max_bytes")
        }
    }

    /** action 上的 image_max_bytes 优先于 response_format 配置，取值范围与配置解析保持一致。 */
    internal fun effectiveMaxBytes(
        action: ActionConfig,
        responseFormat: ModelPlazaResponseFormatConfig
    ): Int {
        val configured = action.params["image_max_bytes"]?.toString()?.trim()?.toIntOrNull()
            ?: responseFormat.imageMaxBytes
        return configured.coerceIn(200_000, 5_000_000)
    }

    internal fun prepareGroupRelations(
        relations: List<GroupModelsRelation>,
        action: ActionConfig,
        config: ModelPlazaQueryConfig
    ): List<GroupModelsRelation> {        val sorted = when (effectiveSort(action, config)) {
            "alphabetical" -> relations.sortedBy { it.groupName.lowercase() }
            else -> relations
        }
        val limited = takeLimit(sorted, effectiveLimit(action, config))
        val nestedLimit = effectiveRelatedLimit(action, config)
        return limited.map { relation ->
            relation.copy(modelNames = if (nestedLimit > 0) relation.modelNames.take(nestedLimit) else relation.modelNames)
        }
    }

    internal fun prepareModelRelations(
        relations: List<ModelGroupRelation>,
        action: ActionConfig,
        config: ModelPlazaQueryConfig
    ): List<ModelGroupRelation> {
        val sorted = when (effectiveSort(action, config)) {
            "alphabetical" -> relations.sortedBy { it.modelName.lowercase() }
            else -> relations
        }
        val limited = takeLimit(sorted, effectiveLimit(action, config))
        val nestedLimit = effectiveRelatedLimit(action, config)
        return limited.map { relation ->
            relation.copy(groupNames = if (nestedLimit > 0) relation.groupNames.take(nestedLimit) else relation.groupNames)
        }
    }

    private fun effectiveSort(action: ActionConfig, config: ModelPlazaQueryConfig): String {
        val value = action.params["sort"]?.toString()?.lowercase() ?: config.sort
        return if (value == "alphabetical") "alphabetical" else "source"
    }

    private fun effectiveLimit(action: ActionConfig, config: ModelPlazaQueryConfig): Int {
        return action.params["limit"]?.toString()?.toIntOrNull()?.coerceIn(0, 100)
            ?: config.limit
    }

    private fun effectiveRelatedLimit(action: ActionConfig, config: ModelPlazaQueryConfig): Int {
        return action.params["max_related_items"]?.toString()?.toIntOrNull()?.coerceIn(0, 100)
            ?: config.maxRelatedItems
    }

    private fun <T> takeLimit(values: List<T>, limit: Int): List<T> {
        return if (limit > 0) values.take(limit) else values
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
        return TemplateEngine.renderString(template.toString(), context)
    }

    private fun resolveContact(target: ScreenshotTarget): Contact {
        val bot = XAiWebHook.bot ?: error("no bot instance available")
        return when (target) {
            is ScreenshotTarget.Group -> bot.getGroup(target.id) ?: error("group not found: ${target.id}")
            is ScreenshotTarget.Friend -> bot.getFriend(target.id) ?: error("friend not found: ${target.id}")
        }
    }

    private enum class QueryKind(val logLabel: String) {
        MODELS("查询分组模型"),
        GROUPS("查询模型分组")
    }

    private fun Any?.toStringList(): List<String> = when (this) {
        is List<*> -> mapNotNull { it?.toString()?.trim()?.ifBlank { null } }
        null -> emptyList()
        else -> listOf(toString()).filter { it.isNotBlank() }
    }
}
