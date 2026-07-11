package kim.hhhhhy.x.webhook.model

import kim.hhhhhy.x.webhook.config.PluginConfig

internal data class RequestContext(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: Any?,
    val remoteHost: String?
)

internal data class EventContext(
    val type: String,
    val botId: Long,
    val groupId: Long?,
    val friendId: Long?,
    val senderId: Long,
    val senderName: String,
    val messageText: String,
    val timestamp: Long
)

internal data class CooldownContext(
    val routeId: String,
    val scope: String,
    val remainingMillis: Long,
    val remainingSeconds: Long
)

internal data class ExecutionContext(
    val config: PluginConfig,
    val request: RequestContext? = null,
    val event: EventContext? = null,
    val cooldown: CooldownContext? = null
)

internal data class ActionResult(
    val type: String,
    val success: Boolean,
    val message: String,
    val responseBody: Any? = null,
    val status: Int? = null
)
