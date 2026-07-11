package kim.hhhhhy.x.webhook.action

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.ActionResult
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.template.IncomingMessageSegment
import kim.hhhhhy.x.webhook.template.TemplateEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.mamoe.mirai.console.command.CommandManager
import net.mamoe.mirai.console.command.ConsoleCommandSender
import net.mamoe.mirai.console.command.descriptor.ExperimentalCommandDescriptors
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.message.code.MiraiCode
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.AtAll
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.MessageChainBuilder
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource

internal object WebHookActionExecutor {
    private val singleExpressionRegex = Regex("""^\$\{(.+)}$""")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val clientLock = Any()

    @Volatile
    private var clientRef: HttpClient? = null

    private fun createClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    // 插件禁用会 close client；再次启用时需要能够重建，避免复用已关闭实例
    private fun client(): HttpClient {
        clientRef?.let { return it }
        return synchronized(clientLock) {
            clientRef ?: createClient().also { clientRef = it }
        }
    }

    suspend fun execute(actions: List<ActionConfig>, context: ExecutionContext): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        for (action in actions) {
            if (!action.enabled) {
                results += ActionResult(action.type, success = true, message = "action disabled")
                continue
            }
            WebHookDebug.log("""[XAiWebHook] [动作] 执行动作
  type     : ${action.type}
  id       : ${action.id ?: "(内联)"}""")
            results += runCatching { executeAction(action, context) }
                .getOrElse { error ->
                    XAiWebHook.logger.error("Action failed: ${action.type}", error)
                    WebHookDebug.log("[XAiWebHook] [动作] 动作执行异常：type=${action.type} 原因=${error.message}")
                    ActionResult(action.type, success = false, message = error.message ?: error::class.simpleName.orEmpty())
                }
        }
        return results
    }

    fun reload(): Unit {
        WebPageScreenshotAction.reset()
    }

    fun close(): Unit {
        synchronized(clientLock) {
            clientRef?.close()
            clientRef = null
        }
        WebPageScreenshotAction.close()
    }

    private suspend fun executeAction(action: ActionConfig, context: ExecutionContext): ActionResult {
        return when (action.type) {
            "send_group_message" -> sendGroupMessage(action, context)
            "send_friend_message" -> sendFriendMessage(action, context)
            "send_webpage_screenshot" -> sendWebpageScreenshot(action, context)
            "http_request" -> httpRequest(action, context)
            "execute_command" -> executeCommand(action, context)
            "reply" -> reply(action, context)
            else -> ActionResult(action.type, success = false, message = "unknown action type")
        }
    }

    private suspend fun sendGroupMessage(action: ActionConfig, context: ExecutionContext): ActionResult {
        val bot = XAiWebHook.bot ?: error("no bot instance available")
        val groupId = renderString(action.params["group_id"], context).toLongOrNull()
            ?: error("group_id is required")
        val messageTemplate = messageTemplate(action)
        val renderedPreviewText = renderString(messageTemplate, context)
        val preview = renderedPreviewText.take(20).let { if (renderedPreviewText.length > 20) "$it..." else it }
        WebHookDebug.log("""[XAiWebHook] [动作] 发送群消息
  groupId  : $groupId
  message  : $preview""")
        val options = incomingMessageOptions(action, context, allowAt = true)
        val group = bot.getGroup(groupId) ?: error("group not found: $groupId")
        group.sendMessage(renderIncomingMessage(messageTemplate, context, group, options))
        WebHookDebug.log("[XAiWebHook] [动作] 群消息发送成功：groupId=$groupId")
        return ActionResult(action.type, success = true, message = "sent group message", status = 200)
    }

    private suspend fun sendFriendMessage(action: ActionConfig, context: ExecutionContext): ActionResult {
        val bot = XAiWebHook.bot ?: error("no bot instance available")
        val friendId = renderString(action.params["friend_id"], context).toLongOrNull()
            ?: error("friend_id is required")
        val messageTemplate = messageTemplate(action)
        val renderedPreviewText = renderString(messageTemplate, context)
        val preview = renderedPreviewText.take(20).let { if (renderedPreviewText.length > 20) "$it..." else it }
        WebHookDebug.log("""[XAiWebHook] [动作] 发送好友消息
  friendId : $friendId
  message  : $preview""")
        val friend = bot.getFriend(friendId) ?: error("friend not found: $friendId")
        val options = incomingMessageOptions(action, context, allowAt = false)
        friend.sendMessage(renderIncomingMessage(messageTemplate, context, friend, options))
        WebHookDebug.log("[XAiWebHook] [动作] 好友消息发送成功：friendId=$friendId")
        return ActionResult(action.type, success = true, message = "sent friend message", status = 200)
    }

    private suspend fun sendWebpageScreenshot(action: ActionConfig, context: ExecutionContext): ActionResult {
        val target = resolveScreenshotTarget(action, context)
        val contact = screenshotContact(target)
        val pendingMessage = renderString(action.params["pending_message"], context)
        val failureMessage = renderString(action.params["failure_message"], context)
        val caption = renderString(action.params["message"], context)

        return try {
            if (pendingMessage.isNotBlank()) {
                contact.sendMessage(PlainText(pendingMessage))
            }
            val bytes = WebPageScreenshotAction.capture(action, context)
            val image = bytes.toExternalResource("png").use { resource ->
                contact.uploadImage(resource)
            }
            if (caption.isBlank()) {
                contact.sendMessage(image)
            } else {
                contact.sendMessage(MessageChainBuilder().append(PlainText(caption)).append(image).build())
            }
            WebHookDebug.log("[XAiWebHook] [动作] 网页截图发送成功：target=$target bytes=${bytes.size}")
            ActionResult(action.type, success = true, message = "sent webpage screenshot", status = 200)
        } catch (error: Throwable) {
            XAiWebHook.logger.error("Webpage screenshot action failed id=${action.id ?: "(inline)"}", error)
            if (failureMessage.isNotBlank()) {
                runCatching { contact.sendMessage(PlainText(failureMessage)) }
                    .onFailure { sendError ->
                        XAiWebHook.logger.warning("Failed to send webpage screenshot failure message: ${sendError.message}")
                    }
            }
            ActionResult(
                type = action.type,
                success = false,
                message = error.message?.take(500) ?: "webpage screenshot failed",
                status = 500
            )
        }
    }

    internal fun resolveScreenshotTarget(action: ActionConfig, context: ExecutionContext): ScreenshotTarget {
        val configuredGroupId = renderString(action.params["group_id"], context).toLongOrNull()
        val configuredFriendId = renderString(action.params["friend_id"], context).toLongOrNull()
        require(configuredGroupId == null || configuredFriendId == null) {
            "only one of group_id or friend_id may be set"
        }
        if (configuredGroupId != null) return ScreenshotTarget.Group(configuredGroupId)
        if (configuredFriendId != null) return ScreenshotTarget.Friend(configuredFriendId)

        context.event?.groupId?.let { return ScreenshotTarget.Group(it) }
        context.event?.friendId?.let { return ScreenshotTarget.Friend(it) }
        error("group_id or friend_id is required when there is no outgoing event target")
    }

    private fun screenshotContact(target: ScreenshotTarget): Contact {
        val bot = XAiWebHook.bot ?: error("no bot instance available")
        return when (target) {
            is ScreenshotTarget.Group -> bot.getGroup(target.id) ?: error("group not found: ${target.id}")
            is ScreenshotTarget.Friend -> bot.getFriend(target.id) ?: error("friend not found: ${target.id}")
        }
    }

    private suspend fun httpRequest(action: ActionConfig, context: ExecutionContext): ActionResult {
        val method = renderString(action.params["method"] ?: "POST", context).uppercase()
        val url = renderString(action.params["url"], context).ifBlank { error("url is required") }
        WebHookDebug.log("[XAiWebHook] [动作] 发起 HTTP 请求：$method $url")
        val headerValues = TemplateEngine.render(action.params["headers"], context).asStringMap()
        val renderedBody = TemplateEngine.render(action.params["body"], context)
        val bodyText = renderedBody?.let { json.encodeToString(JsonElement.serializer(), toJsonElement(it)) }

        val response = client().request(url) {
            this.method = HttpMethod.parse(method)
            headers {
                headerValues.forEach { (key, value) -> append(key, value) }
            }
            if (bodyText != null) {
                setBody(TextContent(bodyText, ContentType.Application.Json))
            }
        }
        val responseText = response.body<String>()
        WebHookDebug.log("""[XAiWebHook] [动作] HTTP 请求完成
  状态码   : ${response.status.value}
  url      : $url""")
        return ActionResult(
            type = action.type,
            success = response.status.value in 200..299,
            message = "http ${response.status.value}",
            responseBody = responseText,
            status = response.status.value
        )
    }

    @OptIn(ExperimentalCommandDescriptors::class)
    private suspend fun executeCommand(action: ActionConfig, context: ExecutionContext): ActionResult {
        if (!context.config.security.allowCommandExecution) {
            return ActionResult(action.type, success = false, message = "command execution disabled")
        }
        val command = renderString(action.params["command"], context).ifBlank {
            error("command is required")
        }
        // 命令执行属高危操作，审计渲染后的最终命令，便于追溯
        XAiWebHook.logger.warning("Executing webhook command action: $command")
        CommandManager.executeCommand(ConsoleCommandSender, PlainText(command))
        return ActionResult(action.type, success = true, message = "command executed")
    }

    private fun reply(action: ActionConfig, context: ExecutionContext): ActionResult {
        val status = renderString(action.params["status"] ?: "200", context).toIntOrNull() ?: 200
        val body = TemplateEngine.render(action.params["body"], context)
        WebHookDebug.log("[XAiWebHook] [动作] 构造自定义响应：status=$status")
        // reply 表示"成功构造了一条响应"，HTTP 状态码由 status 字段携带（允许 4xx/5xx），
        // 不代表业务成败，因此 success 恒为 true，避免污染整体业务成功判定。
        return ActionResult(action.type, success = true, message = "reply", responseBody = body, status = status)
    }

    private suspend fun renderIncomingMessage(
        template: String,
        context: ExecutionContext,
        contact: Contact,
        options: IncomingMessageOptions
    ): Message {
        val segments = TemplateEngine.renderIncomingMessage(template, context)
        if (!options.hasPrefix && !options.parseMiraiCode && segments.size == 1 && segments.first() is IncomingMessageSegment.Text) {
            return PlainText((segments.first() as IncomingMessageSegment.Text).value)
        }

        val builder = MessageChainBuilder()
        appendIncomingPrefix(builder, options)
        segments.forEach { segment ->
            when (segment) {
                is IncomingMessageSegment.Text -> appendIncomingText(builder, segment.value, contact, options.parseMiraiCode)
                is IncomingMessageSegment.MarkdownImage -> uploadMarkdownImages(segment.markdown, contact).forEach { image ->
                    builder.append(image)
                }
            }
        }
        return builder.build()
    }

    private fun appendIncomingPrefix(builder: MessageChainBuilder, options: IncomingMessageOptions): Unit {
        if (options.atAll) {
            builder.append(AtAll)
            builder.append(PlainText(" "))
        }
        options.atTargets.forEach { target ->
            builder.append(At(target))
            builder.append(PlainText(" "))
        }
    }

    private fun appendIncomingText(
        builder: MessageChainBuilder,
        text: String,
        contact: Contact,
        parseMiraiCode: Boolean
    ): Unit {
        if (text.isEmpty()) return
        if (!parseMiraiCode) {
            builder.append(PlainText(text))
            return
        }

        val message = runCatching { MiraiCode.deserializeMiraiCode(text, contact) }
            .getOrElse { error ->
                XAiWebHook.logger.warning("Failed to parse incoming MiraiCode, fallback to plain text: ${error.message}")
                PlainText(text)
            }
        builder.append(message)
    }

    private suspend fun uploadMarkdownImages(markdown: String, contact: Contact): List<Message> {
        return MarkdownImageRenderer.render(markdown).map { bytes ->
            bytes.toExternalResource("png").use { resource ->
                contact.uploadImage(resource)
            }
        }
    }

    private fun renderString(value: Any?, context: ExecutionContext): String {
        return TemplateEngine.renderString(value?.toString().orEmpty(), context)
    }

    private fun messageTemplate(action: ActionConfig): String {
        return action.params["mirai_code"]?.toString() ?: action.params["message"]?.toString().orEmpty()
    }

    private fun incomingMessageOptions(
        action: ActionConfig,
        context: ExecutionContext,
        allowAt: Boolean
    ): IncomingMessageOptions {
        return IncomingMessageOptions(
            atTargets = if (allowAt) renderAtTargets(action, context) else emptyList(),
            atAll = allowAt && renderBoolean(action.params["at_all"], context),
            parseMiraiCode = action.params.containsKey("mirai_code") || renderBoolean(action.params["parse_mirai_code"], context)
        )
    }

    private fun renderAtTargets(action: ActionConfig, context: ExecutionContext): List<Long> {
        val values = listOfNotNull(
            action.params["at"],
            action.params["at_qq"],
            action.params["at_targets"]
        )
        return values.flatMap { renderLongList(it, context) }.distinct()
    }

    private fun renderLongList(value: Any?, context: ExecutionContext): List<Long> {
        return when (val rendered = renderParam(value, context)) {
            is List<*> -> rendered.flatMap { renderLongList(it, context) }
            is Number -> listOf(rendered.toLong()).filter { it > 0L }
            is String -> Regex("""\d+""").findAll(rendered).mapNotNull { match ->
                match.value.toLongOrNull()?.takeIf { it > 0L }
            }.toList()
            else -> emptyList()
        }
    }

    private fun renderBoolean(value: Any?, context: ExecutionContext): Boolean {
        return when (val rendered = renderParam(value, context)) {
            is Boolean -> rendered
            is Number -> rendered.toInt() != 0
            is String -> rendered.equals("true", ignoreCase = true) || rendered == "1"
            else -> false
        }
    }

    private fun renderParam(value: Any?, context: ExecutionContext): Any? {
        return when (value) {
            is String -> {
                val expression = singleExpressionRegex.matchEntire(value.trim())?.groupValues?.get(1)
                if (expression != null) TemplateEngine.evaluateValue(expression, context) else TemplateEngine.renderString(value, context)
            }
            is List<*> -> value.map { renderParam(it, context) }
            is Map<*, *> -> value.mapNotNull { (key, mapValue) ->
                key?.toString()?.let { it to renderParam(mapValue, context) }
            }.toMap()
            else -> value
        }
    }

    private data class IncomingMessageOptions(
        val atTargets: List<Long> = emptyList(),
        val atAll: Boolean = false,
        val parseMiraiCode: Boolean = false
    ) {
        val hasPrefix: Boolean = atTargets.isNotEmpty() || atAll
    }

    private fun Any?.asStringMap(): Map<String, String> {
        val map = this as? Map<*, *> ?: return emptyMap()
        return map.mapNotNull { (key, value) ->
            key?.toString()?.let { it to value.toString() }
        }.toMap()
    }

    internal fun toJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                value.forEach { (key, mapValue) ->
                    if (key != null) put(key.toString(), toJsonElement(mapValue))
                }
            }
            is List<*> -> JsonArray(value.map { toJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }
}

internal sealed class ScreenshotTarget {
    internal data class Group(val id: Long) : ScreenshotTarget()
    internal data class Friend(val id: Long) : ScreenshotTarget()
}
