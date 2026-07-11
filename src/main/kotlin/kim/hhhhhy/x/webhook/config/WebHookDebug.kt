package kim.hhhhhy.x.webhook.config

/**
 * Debug 日志开关。由 logging.debug 配置项控制，配置加载时更新 [enabled]。
 * 所有调试用的控制台输出都通过 [log] 打印，关闭时零开销（仅一次布尔判断）。
 */
internal object WebHookDebug {
    @Volatile
    var enabled: Boolean = false
        private set

    fun update(enabled: Boolean) {
        this.enabled = enabled
    }

    fun log(message: String) {
        if (enabled) println(message)
    }
}
