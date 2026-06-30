package kim.hhhhhy.x.webhook.listener

import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.action.WebHookActionExecutor
import kim.hhhhhy.x.webhook.config.OutgoingRoute
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.EventContext
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.template.TemplateEngine
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.EventHandler
import net.mamoe.mirai.event.ListenerHost
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.content

public object WebHookEventListener : ListenerHost {
    @EventHandler
    public suspend fun GroupMessageEvent.onGroupMessage(): Unit {
        val preview = message.content.take(30).let { if (message.content.length > 30) "$it..." else it }
        WebHookDebug.log("""[XAiWebHook] [事件] 收到群消息
  群       : ${group.id}
  发送者   : ${sender.id} (${sender.nameCardOrNick})
  内容     : $preview""")
        val context = EventContext(
            type = "group_message",
            botId = bot.id,
            groupId = group.id,
            friendId = null,
            senderId = sender.id,
            senderName = sender.nameCardOrNick,
            messageText = message.content,
            timestamp = System.currentTimeMillis()
        )
        dispatch(context)
    }

    @EventHandler
    public suspend fun FriendMessageEvent.onFriendMessage(): Unit {
        val preview = message.content.take(30).let { if (message.content.length > 30) "$it..." else it }
        WebHookDebug.log("""[XAiWebHook] [事件] 收到好友消息
  好友     : ${sender.id}
  昵称     : ${sender.nick}
  内容     : $preview""")
        val context = EventContext(
            type = "friend_message",
            botId = bot.id,
            groupId = null,
            friendId = sender.id,
            senderId = sender.id,
            senderName = sender.nick,
            messageText = message.content,
            timestamp = System.currentTimeMillis()
        )
        dispatch(context)
    }

    private suspend fun dispatch(event: EventContext): Unit {
        val config = WebHookConfig.current
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
                if (config.logging.request) {
                    XAiWebHook.logger.info("Outgoing webhook route=${route.id} event=${event.type}")
                }
        WebHookDebug.log("""[XAiWebHook] [事件] 路由 ${route.id} 已匹配
  动作数   : ${route.actions.size}""")
                runCatching { WebHookActionExecutor.execute(route.actions, executionContext) }
                    .onFailure { error ->
                        XAiWebHook.logger.error("Outgoing route execution failed route=${route.id}", error)
                    }
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
