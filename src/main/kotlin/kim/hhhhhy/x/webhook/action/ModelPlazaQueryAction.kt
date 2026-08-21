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
import kim.hhhhhy.x.webhook.scraper.ModelGroupRelation
import kim.hhhhhy.x.webhook.scraper.ModelPlazaScraper
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
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.message.data.PlainText

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
                    val relations = prepareGroupRelations(
                        ModelPlazaScraper.queryGroupModels(config, pattern),
                        action,
                        queryConfig
                    )
                    if (relations.isEmpty()) {
                        contact.sendMessage(PlainText(
                            ModelPlazaFormatter.renderMessage(responseFormat.emptyMessage, pattern, context)
                        ))
                    } else {
                        contact.sendMessage(PlainText(
                            ModelPlazaFormatter.formatGroupModels(
                                query = pattern,
                                relations = relations,
                                responseFormat = responseFormat,
                                actionTemplate = action.params["success_template"]?.toString(),
                                context = context
                            )
                        ))
                    }
                    WebHookDebug.log("[ModelPlaza] 模型查询成功，共 ${relations.size} 个分组")
                }
                QueryKind.GROUPS -> {
                    val relations = prepareModelRelations(
                        ModelPlazaScraper.queryModelGroups(config, pattern),
                        action,
                        queryConfig
                    )
                    if (relations.isEmpty()) {
                        contact.sendMessage(PlainText(
                            ModelPlazaFormatter.renderMessage(responseFormat.emptyMessage, pattern, context)
                        ))
                    } else {
                        contact.sendMessage(PlainText(
                            ModelPlazaFormatter.formatModelGroups(
                                query = pattern,
                                relations = relations,
                                responseFormat = responseFormat,
                                actionTemplate = action.params["success_template"]?.toString(),
                                context = context
                            )
                        ))
                    }
                    WebHookDebug.log("[ModelPlaza] 分组查询成功，共 ${relations.size} 个模型")
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

    internal fun prepareGroupRelations(
        relations: List<GroupModelsRelation>,
        action: ActionConfig,
        config: ModelPlazaQueryConfig
    ): List<GroupModelsRelation> {
        val sorted = when (effectiveSort(action, config)) {
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
