package kim.hhhhhy.x.webhook.command

import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.action.WebHookActionExecutor
import kim.hhhhhy.x.webhook.action.WebPageScreenshotAction
import kim.hhhhhy.x.webhook.api.RequestImageConfigUpdateResult
import kim.hhhhhy.x.webhook.api.XAiWebHookRequestImageApi
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.listener.WebHookEventListener
import kim.hhhhhy.x.webhook.scraper.ModelPlazaScraper
import kim.hhhhhy.x.webhook.server.WebHookServer
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand

public object XAiWebHookCommand : CompositeCommand(
    XAiWebHook,
    "xwebhook",
    description = "XAiWebHook 管理命令",
    parentPermission = XAiWebHook.PERMISSION_ADMIN
) {
    @SubCommand("reload")
    @Description("重载主配置和图片配置，并重启 WebHook 服务")
    public suspend fun CommandSender.reload(): Unit {
        WebHookDebug.log("[XAiWebHook] [命令] 收到 reload 命令，开始重载配置...")
        WebHookConfig.reload()
        val imageConfigResult = XAiWebHookRequestImageApi.reload()
        WebHookActionExecutor.reload()
        WebHookEventListener.clearCooldowns()
        WebHookServer.restart(WebHookConfig.current)
        val config = WebHookConfig.current
        val configError = WebHookConfig.lastError
        val serverError = WebHookServer.lastError
        WebHookDebug.log("""[XAiWebHook] [命令] reload 完成
  incoming 端点 : ${config.incoming.endpoints.size}
  outgoing 路由 : ${config.outgoing.routes.size}
  服务          : ${if (WebHookServer.running) "运行中" else "已停止"}""")
        val feedback = buildString {
            if (configError == null) {
                appendLine("XAiWebHook 配置已重载")
            } else {
                appendLine("XAiWebHook 配置重载失败，已回退安全默认配置")
                appendLine("config error: $configError")
            }
            appendLine("incoming endpoints: ${config.incoming.endpoints.size}")
            appendLine("outgoing routes: ${config.outgoing.routes.size}")
            appendLine("server: ${if (WebHookServer.running) "running" else "stopped"}")
            appendLine("browser: ${browserStatus(config.browser.enabled, config.browser.engine, config.browser.channel)}")
            if (serverError != null) appendLine("server error: $serverError")
            appendLine(imageConfigFeedback(imageConfigResult))
        }
        sendMessage(feedback.trimEnd())
    }

    @SubCommand("status")
    @Description("查看 WebHook 服务状态")
    public suspend fun CommandSender.status(): Unit {
        WebHookDebug.log("[XAiWebHook] [命令] 收到 status 命令查询")
        val config = WebHookConfig.current
        val imageConfigStatus = XAiWebHookRequestImageApi.status()
        val status = buildString {
            appendLine("XAiWebHook 状态:")
            appendLine("server: ${if (WebHookServer.running) "running" else "stopped"}")
            appendLine("listen: ${config.server.host}:${config.server.port}${config.server.basePath}")
            appendLine("incoming endpoints: ${config.incoming.endpoints.size}")
            appendLine("outgoing routes: ${config.outgoing.routes.size}")
            appendLine("browser: ${browserStatus(config.browser.enabled, config.browser.engine, config.browser.channel)}")
            appendLine("model_plaza: ${if (config.modelPlaza.enabled) "enabled" else "disabled"}")
            appendLine("config error: ${WebHookConfig.lastError ?: "none"}")
            appendLine("server error: ${WebHookServer.lastError ?: "none"}")
            appendLine("browser error: ${WebPageScreenshotAction.lastError ?: "none"}")
            appendLine("browser query error: ${WebPageScreenshotAction.lastQueryError ?: "none"}")
            appendLine("request image config: revision=${imageConfigStatus.revision}, watching=${imageConfigStatus.watching}")
            appendLine("request image config error: ${imageConfigStatus.lastError ?: "none"}")
        }
        sendMessage(status)
    }

    @SubCommand("image-config")
    @Description("查看请求图片的有效配置与自动加载状态")
    public suspend fun CommandSender.imageConfig(): Unit {
        val status = XAiWebHookRequestImageApi.status()
        sendMessage(buildString {
            appendLine("请求图片配置（后续生成的图片生效）:")
            appendLine("file: ${status.filePath ?: "未启动"}")
            appendLine("revision: ${status.revision}")
            appendLine("watching: ${status.watching}")
            appendLine("last loaded: ${status.lastLoadedAtMillis ?: "none"}")
            appendLine("error: ${status.lastError ?: "none"}")
            appendLine(XAiWebHookRequestImageApi.readYaml())
        }.trimEnd())
    }

    @SubCommand("image-config-set")
    @Description("校验、保存并立即应用一项图片配置，例如 margin.left_percent 8")
    public suspend fun CommandSender.setImageConfig(key: String, value: String): Unit {
        sendMessage(imageConfigFeedback(XAiWebHookRequestImageApi.update(key, value)))
    }

    @SubCommand("image-config-reload")
    @Description("只重载 request_image.yml，不重启 HTTP 或浏览器")
    public suspend fun CommandSender.reloadImageConfig(): Unit {
        sendMessage(imageConfigFeedback(XAiWebHookRequestImageApi.reload()))
    }

    private fun imageConfigFeedback(result: RequestImageConfigUpdateResult): String {
        return if (result.success) {
            "请求图片配置已应用（revision=${result.revision}），后续生成的图片生效"
        } else {
            "请求图片配置更新失败，保留当前有效值（revision=${result.revision}）：${result.error ?: "未知错误"}"
        }
    }

    @SubCommand("模型", "model")
    @Description("匹配名称包含关键词的所有分组，并分别列出模型")
    public suspend fun CommandSender.queryModels(groupPattern: String): Unit {
        WebHookDebug.log("[XAiWebHook] [命令] 收到模型查询命令，分组: $groupPattern")
        val config = WebHookConfig.current

        if (!config.modelPlaza.enabled) {
            sendMessage("Model Plaza 功能未启用，请在配置文件中设置 model_plaza.enabled: true")
            return
        }

        sendMessage("正在查询分组 '$groupPattern' 下的模型，请稍候...")

        try {
            val relations = ModelPlazaScraper.queryGroupModels(config.modelPlaza, groupPattern)
            if (relations.isEmpty()) {
                sendMessage("未找到包含该关键词的分组")
            } else {
                val result = buildString {
                    appendLine("包含 '$groupPattern' 的分组（共 ${relations.size} 个）:")
                    relations.forEachIndexed { index, relation ->
                        if (index > 0) appendLine()
                        appendLine("分组：${relation.groupName}")
                        if (relation.modelNames.isEmpty()) {
                            appendLine("- （无可用模型）")
                        } else {
                            relation.modelNames.forEach { modelName -> appendLine("- $modelName") }
                        }
                    }
                }
                sendMessage(result.trimEnd())
            }
        } catch (e: Exception) {
            WebHookDebug.log("[XAiWebHook] [命令] 模型查询失败: ${e.message}")
            sendMessage("查询失败: ${e.message}")
        }
    }

    @SubCommand("分组", "group")
    @Description("匹配名称包含关键词的所有模型，并分别列出分组")
    public suspend fun CommandSender.queryGroups(modelPattern: String): Unit {
        WebHookDebug.log("[XAiWebHook] [命令] 收到分组查询命令，模型: $modelPattern")
        val config = WebHookConfig.current

        if (!config.modelPlaza.enabled) {
            sendMessage("Model Plaza 功能未启用，请在配置文件中设置 model_plaza.enabled: true")
            return
        }

        sendMessage("正在查询模型 '$modelPattern' 所属的分组，请稍候...")

        try {
            val relations = ModelPlazaScraper.queryModelGroups(config.modelPlaza, modelPattern)
            if (relations.isEmpty()) {
                sendMessage("未找到包含该关键词的模型")
            } else {
                val result = buildString {
                    appendLine("包含 '$modelPattern' 的模型（共 ${relations.size} 个）:")
                    relations.forEachIndexed { index, relation ->
                        if (index > 0) appendLine()
                        appendLine("模型：${relation.modelName}")
                        if (relation.groupNames.isEmpty()) {
                            appendLine("- （无可用分组）")
                        } else {
                            relation.groupNames.forEach { groupName -> appendLine("- $groupName") }
                        }
                    }
                }
                sendMessage(result.trimEnd())
            }
        } catch (e: Exception) {
            WebHookDebug.log("[XAiWebHook] [命令] 分组查询失败: ${e.message}")
            sendMessage("查询失败: ${e.message}")
        }
    }

    private fun browserStatus(enabled: Boolean, engine: String, channel: String?): String {
        if (!enabled) return "disabled"
        return buildString {
            append("enabled, engine=")
            append(engine)
            if (channel != null) {
                append(", channel=")
                append(channel)
            }
        }
    }
}
