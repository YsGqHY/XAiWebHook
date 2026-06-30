package kim.hhhhhy.x.webhook

import kim.hhhhhy.x.webhook.action.WebHookActionExecutor
import kim.hhhhhy.x.webhook.command.XAiWebHookCommand
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.listener.WebHookEventListener
import kim.hhhhhy.x.webhook.server.WebHookServer
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.command.CommandManager
import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.event.GlobalEventChannel.registerListenerHost

public object XAiWebHook : KotlinPlugin(
    JvmPluginDescription(
        id = "kim.hhhhhy.x.webhook",
        version = "0.1.0",
        name = "XAiWebHook"
    )
) {
    public val bot: Bot?
        get() = Bot.instances.firstOrNull()

    public val PERMISSION_ADMIN: Permission by lazy {
        PermissionService.INSTANCE.register(
            permissionId("admin"),
            "XAiWebHook 管理权限"
        )
    }

    public val PERMISSION_EXECUTE_COMMAND: Permission by lazy {
        PermissionService.INSTANCE.register(
            permissionId("execute-command"),
            "XAiWebHook 命令执行权限"
        )
    }

    public override fun onEnable(): Unit {
        WebHookDebug.log("[XAiWebHook] 插件启动中...")
        WebHookDebug.log("[XAiWebHook] 正在加载配置文件...")
        WebHookConfig.load()
        WebHookDebug.log("[XAiWebHook] 正在注册消息事件监听器...")
        registerListenerHost(WebHookEventListener)
        WebHookDebug.log("[XAiWebHook] 正在注册控制台命令...")
        CommandManager.registerCommand(XAiWebHookCommand)
        WebHookDebug.log("[XAiWebHook] 正在启动 WebHook HTTP 服务...")
        WebHookServer.start(WebHookConfig.current)
        logger.info("XAiWebHook enabled")
        WebHookDebug.log("[XAiWebHook] 插件启动完成")
    }

    public override fun onDisable(): Unit {
        WebHookDebug.log("[XAiWebHook] 插件正在停止...")
        WebHookDebug.log("[XAiWebHook] 正在停止 HTTP 服务...")
        WebHookServer.stop()
        WebHookDebug.log("[XAiWebHook] 正在关闭 HTTP 客户端...")
        WebHookActionExecutor.close()
        logger.info("XAiWebHook disabled")
        WebHookDebug.log("[XAiWebHook] 插件已停止")
    }
}
