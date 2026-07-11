package kim.hhhhhy.x.webhook.listener

import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.EventContext
import net.mamoe.mirai.console.permission.AbstractPermitteeId
import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.console.permission.PermitteeId
import net.mamoe.mirai.contact.isOperator
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.EventHandler
import net.mamoe.mirai.event.ListenerHost
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.content

public object WebHookEventListener : ListenerHost {
    private val processor = OutgoingRouteProcessor()

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
        val permitteeId = AbstractPermitteeId.ExactMember(group.id, sender.id)
        processor.process(
            config = WebHookConfig.current,
            event = context,
            actor = resolveActor(permitteeId, permission.isOperator())
        ) { feedback ->
            subject.sendMessage(PlainText(feedback))
            Unit
        }
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
        val permitteeId = AbstractPermitteeId.ExactFriend(sender.id)
        processor.process(
            config = WebHookConfig.current,
            event = context,
            actor = resolveActor(permitteeId, groupOperator = false)
        ) { feedback ->
            subject.sendMessage(PlainText(feedback))
            Unit
        }
    }

    internal fun clearCooldowns(): Unit = processor.clearCooldowns()

    private fun resolveActor(permitteeId: PermitteeId, groupOperator: Boolean): OutgoingActor {
        val pluginAdministrator = hasPermissionSafely(permitteeId, XAiWebHook.PERMISSION_ADMIN)
        val administrator = groupOperator || pluginAdministrator
        val bypassCooldown = administrator && hasPermissionSafely(
            permitteeId,
            XAiWebHook.PERMISSION_COOLDOWN_BYPASS
        )
        return OutgoingActor(
            administrator = administrator,
            bypassCooldown = bypassCooldown
        )
    }

    private fun hasPermissionSafely(permitteeId: PermitteeId, permission: Permission): Boolean {
        return runCatching { permitteeId.hasPermission(permission) }
            .getOrElse { error ->
                XAiWebHook.logger.warning(
                    "Failed to resolve outgoing permission ${permission.id} for ${permitteeId.asString()}: " +
                        error.message
                )
                false
            }
    }
}
