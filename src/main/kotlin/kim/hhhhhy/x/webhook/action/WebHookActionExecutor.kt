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
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.MessageChainBuilder
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource

internal object WebHookActionExecutor {
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

    fun close(): Unit {
        synchronized(clientLock) {
            clientRef?.close()
            clientRef = null
        }
    }

    private suspend fun executeAction(action: ActionConfig, context: ExecutionContext): ActionResult {
        return when (action.type) {
            "send_group_message" -> sendGroupMessage(action, context)
            "send_friend_message" -> sendFriendMessage(action, context)
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
        val messageTemplate = action.params["message"]?.toString().orEmpty()
        val renderedPreviewText = renderString(messageTemplate, context)
        val preview = renderedPreviewText.take(20).let { if (renderedPreviewText.length > 20) "$it..." else it }
        WebHookDebug.log("""[XAiWebHook] [动作] 发送群消息
  groupId  : $groupId
  message  : $preview""")
        val group = bot.getGroup(groupId) ?: error("group not found: $groupId")
        group.sendMessage(renderIncomingMessage(messageTemplate, context, group))
        WebHookDebug.log("[XAiWebHook] [动作] 群消息发送成功：groupId=$groupId")
        return ActionResult(action.type, success = true, message = "sent group message", status = 200)
    }

    private suspend fun sendFriendMessage(action: ActionConfig, context: ExecutionContext): ActionResult {
        val bot = XAiWebHook.bot ?: error("no bot instance available")
        val friendId = renderString(action.params["friend_id"], context).toLongOrNull()
            ?: error("friend_id is required")
        val messageTemplate = action.params["message"]?.toString().orEmpty()
        val renderedPreviewText = renderString(messageTemplate, context)
        val preview = renderedPreviewText.take(20).let { if (renderedPreviewText.length > 20) "$it..." else it }
        WebHookDebug.log("""[XAiWebHook] [动作] 发送好友消息
  friendId : $friendId
  message  : $preview""")
        val friend = bot.getFriend(friendId) ?: error("friend not found: $friendId")
        friend.sendMessage(renderIncomingMessage(messageTemplate, context, friend))
        WebHookDebug.log("[XAiWebHook] [动作] 好友消息发送成功：friendId=$friendId")
        return ActionResult(action.type, success = true, message = "sent friend message", status = 200)
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

    private suspend fun renderIncomingMessage(template: String, context: ExecutionContext, contact: Contact): Message {
        val segments = TemplateEngine.renderIncomingMessage(template, context)
        if (segments.size == 1 && segments.first() is IncomingMessageSegment.Text) {
            return PlainText((segments.first() as IncomingMessageSegment.Text).value)
        }

        val builder = MessageChainBuilder()
        segments.forEach { segment ->
            when (segment) {
                is IncomingMessageSegment.Text -> if (segment.value.isNotEmpty()) builder.append(PlainText(segment.value))
                is IncomingMessageSegment.MarkdownImage -> uploadMarkdownImages(segment.markdown, contact).forEach { image ->
                    builder.append(image)
                }
            }
        }
        return builder.build()
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
