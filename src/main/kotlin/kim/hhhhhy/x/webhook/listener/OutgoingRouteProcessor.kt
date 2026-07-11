package kim.hhhhhy.x.webhook.listener

import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.action.WebHookActionExecutor
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.OutgoingRoute
import kim.hhhhhy.x.webhook.config.PluginConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.CooldownContext
import kim.hhhhhy.x.webhook.model.EventContext
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.template.TemplateEngine

internal data class OutgoingActor(
    val administrator: Boolean,
    val bypassCooldown: Boolean
)

internal enum class OutgoingCooldownScope(val value: String) {
    PERSONAL("personal"),
    ADMINISTRATOR("administrator"),
    GLOBAL("global")
}

internal sealed class OutgoingCooldownDecision {
    object Allowed : OutgoingCooldownDecision()

    data class Blocked(
        val scope: OutgoingCooldownScope,
        val remainingMillis: Long
    ) : OutgoingCooldownDecision()
}

internal class OutgoingCooldownTracker(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val lock = Any()
    private val actorExpirations = mutableMapOf<ActorKey, Long>()
    private val globalExpirations = mutableMapOf<String, Long>()
    private var acquisitions: Long = 0L

    fun acquire(
        route: OutgoingRoute,
        event: EventContext,
        actor: OutgoingActor
    ): OutgoingCooldownDecision {
        val cooldown = route.cooldown
        if (!cooldown.enabled || actor.bypassCooldown) return OutgoingCooldownDecision.Allowed

        val actorDuration = if (actor.administrator) {
            cooldown.administratorMillis
        } else {
            cooldown.personalMillis
        }
        val globalDuration = cooldown.globalMillis
        if (actorDuration <= 0L && globalDuration <= 0L) return OutgoingCooldownDecision.Allowed

        val now = clockMillis()
        return synchronized(lock) {
            val actorScope = if (actor.administrator) {
                OutgoingCooldownScope.ADMINISTRATOR
            } else {
                OutgoingCooldownScope.PERSONAL
            }
            val actorKey = ActorKey(route.id, event.senderId, actorScope)
            val active = mutableListOf<OutgoingCooldownDecision.Blocked>()

            if (actorDuration > 0L) {
                remaining(actorExpirations[actorKey], now).takeIf { it > 0L }?.let { remaining ->
                    active += OutgoingCooldownDecision.Blocked(
                        scope = actorScope,
                        remainingMillis = remaining
                    )
                }
            }
            if (globalDuration > 0L) {
                remaining(globalExpirations[route.id], now).takeIf { it > 0L }?.let { remaining ->
                    active += OutgoingCooldownDecision.Blocked(
                        scope = OutgoingCooldownScope.GLOBAL,
                        remainingMillis = remaining
                    )
                }
            }

            active.maxByOrNull { it.remainingMillis }?.let { return@synchronized it }

            if (actorDuration > 0L) {
                actorExpirations[actorKey] = now + actorDuration
            } else {
                actorExpirations.remove(actorKey)
            }
            if (globalDuration > 0L) {
                globalExpirations[route.id] = now + globalDuration
            } else {
                globalExpirations.remove(route.id)
            }

            acquisitions++
            if (acquisitions % CLEANUP_INTERVAL == 0L) {
                actorExpirations.entries.removeIf { it.value <= now }
                globalExpirations.entries.removeIf { it.value <= now }
            }
            OutgoingCooldownDecision.Allowed
        }
    }

    fun clear(): Unit = synchronized(lock) {
        actorExpirations.clear()
        globalExpirations.clear()
        acquisitions = 0L
    }

    private fun remaining(expiration: Long?, now: Long): Long {
        if (expiration == null || expiration <= now) return 0L
        return expiration - now
    }

    private data class ActorKey(
        val routeId: String,
        val senderId: Long,
        val scope: OutgoingCooldownScope
    )

    private companion object {
        const val CLEANUP_INTERVAL: Long = 256L
    }
}

internal class OutgoingRouteProcessor(
    clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val actionExecutor: suspend (List<ActionConfig>, ExecutionContext) -> Unit = { actions, context ->
        WebHookActionExecutor.execute(actions, context)
        Unit
    }
) {
    private val cooldowns = OutgoingCooldownTracker(clockMillis)

    suspend fun process(
        config: PluginConfig,
        event: EventContext,
        actor: OutgoingActor,
        sendFeedback: suspend (String) -> Unit
    ): Unit {
        val executionContext = ExecutionContext(config = config, event = event)
        val routes = config.outgoing.routes
        WebHookDebug.log("[XAiWebHook] [事件] 开始匹配 ${routes.size} 条 outgoing 路由，事件类型=${event.type}")
        val matched = routes.filter { route ->
            runCatching { route.matches(event, executionContext) }
                .getOrElse { error ->
                    XAiWebHook.logger.error("Outgoing route match failed route=${route.id}", error)
                    false
                }
        }
        if (matched.isEmpty()) {
            WebHookDebug.log("[XAiWebHook] [事件] 无匹配路由，事件已忽略")
        }

        matched.forEach { route ->
            when (val decision = cooldowns.acquire(route, event, actor)) {
                OutgoingCooldownDecision.Allowed -> executeRoute(route, config, event, executionContext)
                is OutgoingCooldownDecision.Blocked -> notifyCooldown(
                    route = route,
                    event = event,
                    executionContext = executionContext,
                    decision = decision,
                    sendFeedback = sendFeedback
                )
            }
        }
    }

    fun clearCooldowns(): Unit = cooldowns.clear()

    private suspend fun executeRoute(
        route: OutgoingRoute,
        config: PluginConfig,
        event: EventContext,
        executionContext: ExecutionContext
    ): Unit {
        if (config.logging.request) {
            XAiWebHook.logger.info("Outgoing webhook route=${route.id} event=${event.type}")
        }
        WebHookDebug.log(
            """[XAiWebHook] [事件] 路由 ${route.id} 已匹配
  动作数   : ${route.actions.size}"""
        )
        runCatching { actionExecutor(route.actions, executionContext) }
            .onFailure { error ->
                XAiWebHook.logger.error("Outgoing route execution failed route=${route.id}", error)
            }
    }

    private suspend fun notifyCooldown(
        route: OutgoingRoute,
        event: EventContext,
        executionContext: ExecutionContext,
        decision: OutgoingCooldownDecision.Blocked,
        sendFeedback: suspend (String) -> Unit
    ): Unit {
        val remainingSeconds = (decision.remainingMillis + 999L) / 1_000L
        WebHookDebug.log(
            "[XAiWebHook] [冷却] 路由 ${route.id} 已阻止 sender=${event.senderId} " +
                "scope=${decision.scope.value} remaining=${decision.remainingMillis}ms"
        )
        if (!route.cooldown.notify) return

        val cooldownContext = CooldownContext(
            routeId = route.id,
            scope = decision.scope.value,
            remainingMillis = decision.remainingMillis,
            remainingSeconds = remainingSeconds
        )
        val message = TemplateEngine.renderString(
            route.cooldown.message,
            executionContext.copy(cooldown = cooldownContext)
        ).trim()
        if (message.isEmpty()) return

        runCatching { sendFeedback(message) }
            .onFailure { error ->
                XAiWebHook.logger.warning(
                    "Failed to send outgoing cooldown message route=${route.id}: ${error.message}"
                )
            }
    }

    private fun OutgoingRoute.matches(event: EventContext, context: ExecutionContext): Boolean {
        if (!enabled) return false
        if (events.isNotEmpty() && event.type !in events) return false
        if (groups.isNotEmpty() && (event.groupId == null || event.groupId !in groups)) return false
        if (friends.isNotEmpty() && (event.friendId == null || event.friendId !in friends)) return false
        if (senders.isNotEmpty() && event.senderId !in senders) return false
        if (message.contains.isNotEmpty() && message.contains.none { event.messageText.contains(it) }) return false
        if (message.startsWith.isNotEmpty() && message.startsWith.none { event.messageText.startsWith(it) }) return false
        if (message.endsWith.isNotEmpty() && message.endsWith.none { event.messageText.endsWith(it) }) return false
        if (message.regex.isNotEmpty() && message.regex.none { pattern ->
                runCatching { pattern.containsMatchIn(event.messageText) }.getOrDefault(false)
            }
        ) return false
        return TemplateEngine.evaluateCondition(condition, context)
    }
}
