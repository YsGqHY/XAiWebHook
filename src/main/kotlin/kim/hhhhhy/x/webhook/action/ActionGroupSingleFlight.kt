package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.config.ActionGroupSingleFlightConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class ActionGroupSingleFlightRegistry {
    private val active = ConcurrentHashMap<String, Any>()

    fun tryAcquire(
        config: ActionGroupSingleFlightConfig,
        fallbackKey: String
    ): ActionGroupSingleFlightLease? {
        if (!config.enabled) return ActionGroupSingleFlightLease.noop()

        val key = config.key?.let { "shared:$it" } ?: "local:$fallbackKey"
        val token = Any()
        if (active.putIfAbsent(key, token) != null) return null
        return ActionGroupSingleFlightLease {
            active.remove(key, token)
        }
    }
}

internal class ActionGroupSingleFlightLease(
    private val release: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close(): Unit {
        if (closed.compareAndSet(false, true)) {
            release()
        }
    }

    internal companion object {
        fun noop(): ActionGroupSingleFlightLease = ActionGroupSingleFlightLease {}
    }
}

internal object ActionGroupExecutionCoordinator {
    val singleFlight: ActionGroupSingleFlightRegistry = ActionGroupSingleFlightRegistry()
}
