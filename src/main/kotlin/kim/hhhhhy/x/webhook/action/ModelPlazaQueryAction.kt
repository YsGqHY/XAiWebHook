package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.ActionResult
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.scraper.ModelPlazaScraper
import kim.hhhhhy.x.webhook.template.TemplateEngine
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.message.data.PlainText

internal object ModelPlazaQueryAction {

    public suspend fun queryModels(action: ActionConfig, context: ExecutionContext): ActionResult {
        val config = context.config
        if (!config.modelPlaza.enabled) {
            return ActionResult(
                type = action.type,
                success = false,
                message = "model_plaza is disabled in config"
            )
        }

        // 从事件消息中提取查询关键词
        val messageText = context.event?.messageText ?: ""
        val groupPattern = extractQueryPattern(action, context, messageText, "query_pattern")

        if (groupPattern.isBlank()) {
            WebHookDebug.log("[ModelPlaza] 未能提取查询模式，消息: $messageText")
            return ActionResult(
                type = action.type,
                success = false,
                message = "failed to extract query pattern from message"
            )
        }

        WebHookDebug.log("[ModelPlaza] 查询分组 '$groupPattern' 下的模型")

        val target = WebHookActionExecutor.resolveScreenshotTarget(action, context)
        val contact = resolveContact(target)
        val pendingMessage = renderString(action.params["pending_message"], context)
        val failureMessage = renderString(action.params["failure_message"], context)

        try {
            if (pendingMessage.isNotBlank()) {
                contact.sendMessage(PlainText(pendingMessage))
            }

            val relations = ModelPlazaScraper.queryGroupModels(config.modelPlaza, groupPattern)

            if (relations.isEmpty()) {
                val emptyMessage = renderString(action.params["empty_message"], context)
                    .ifBlank { "未找到包含该关键词的分组" }
                contact.sendMessage(PlainText(emptyMessage))
            } else {
                val result = buildString {
                    appendLine("包含 '$groupPattern' 的分组（共 ${relations.size} 个）:")
                    relations.forEachIndexed { index, relation ->
                        if (index > 0) appendLine()
                        appendLine("分组：${relation.groupName}")
                        if (relation.modelNames.isEmpty()) {
                            appendLine("- （无可用模型）")
                        } else {
                            relation.modelNames.forEach { modelName ->
                                appendLine("- $modelName")
                            }
                        }
                    }
                }
                contact.sendMessage(PlainText(result.trimEnd()))
            }

            val modelCount = relations.sumOf { it.modelNames.size }
            WebHookDebug.log("[ModelPlaza] 模型查询成功，共 ${relations.size} 个分组、$modelCount 个分组模型关系")
            return ActionResult(action.type, success = true, message = "query completed", status = 200)

        } catch (error: Throwable) {
            XAiWebHook.logger.error("Model plaza query models failed: ${error.message}", error)
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

    public suspend fun queryGroups(action: ActionConfig, context: ExecutionContext): ActionResult {
        val config = context.config
        if (!config.modelPlaza.enabled) {
            return ActionResult(
                type = action.type,
                success = false,
                message = "model_plaza is disabled in config"
            )
        }

        // 从事件消息中提取查询关键词
        val messageText = context.event?.messageText ?: ""
        val modelPattern = extractQueryPattern(action, context, messageText, "query_pattern")

        if (modelPattern.isBlank()) {
            WebHookDebug.log("[ModelPlaza] 未能提取查询模式，消息: $messageText")
            return ActionResult(
                type = action.type,
                success = false,
                message = "failed to extract query pattern from message"
            )
        }

        WebHookDebug.log("[ModelPlaza] 查询模型 '$modelPattern' 所属的分组")

        val target = WebHookActionExecutor.resolveScreenshotTarget(action, context)
        val contact = resolveContact(target)
        val pendingMessage = renderString(action.params["pending_message"], context)
        val failureMessage = renderString(action.params["failure_message"], context)

        try {
            if (pendingMessage.isNotBlank()) {
                contact.sendMessage(PlainText(pendingMessage))
            }

            val relations = ModelPlazaScraper.queryModelGroups(config.modelPlaza, modelPattern)

            if (relations.isEmpty()) {
                val emptyMessage = renderString(action.params["empty_message"], context)
                    .ifBlank { "未找到包含该关键词的模型" }
                contact.sendMessage(PlainText(emptyMessage))
            } else {
                val result = buildString {
                    appendLine("包含 '$modelPattern' 的模型（共 ${relations.size} 个）:")
                    relations.forEachIndexed { index, relation ->
                        if (index > 0) appendLine()
                        appendLine("模型：${relation.modelName}")
                        if (relation.groupNames.isEmpty()) {
                            appendLine("- （无可用分组）")
                        } else {
                            relation.groupNames.forEach { groupName ->
                                appendLine("- $groupName")
                            }
                        }
                    }
                }
                contact.sendMessage(PlainText(result.trimEnd()))
            }

            val groupCount = relations.sumOf { it.groupNames.size }
            WebHookDebug.log("[ModelPlaza] 分组查询成功，共 ${relations.size} 个模型、$groupCount 个模型分组关系")
            return ActionResult(action.type, success = true, message = "query completed", status = 200)

        } catch (error: Throwable) {
            XAiWebHook.logger.error("Model plaza query groups failed: ${error.message}", error)
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

    private fun extractQueryPattern(
        action: ActionConfig,
        context: ExecutionContext,
        messageText: String,
        paramKey: String
    ): String {
        // 优先使用配置的 query_pattern（支持模板变量）
        val configuredPattern = action.params[paramKey]
        if (configuredPattern != null) {
            return renderString(configuredPattern, context).trim()
        }

        // 如果未配置，尝试从消息文本中自动提取
        // 支持格式：
        // "模型 OpenAI" -> "OpenAI"
        // "分组 gpt-4" -> "gpt-4"
        val trimmed = messageText.trim()
        val prefixes = listOf("模型", "分组", "model", "group")

        for (prefix in prefixes) {
            if (trimmed.startsWith(prefix)) {
                val pattern = trimmed.substring(prefix.length).trim()
                if (pattern.isNotBlank()) {
                    return pattern
                }
            }
        }

        return ""
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
