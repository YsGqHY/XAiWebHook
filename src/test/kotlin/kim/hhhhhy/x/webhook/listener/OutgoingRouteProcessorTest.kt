package kim.hhhhhy.x.webhook.listener

import kim.hhhhhy.x.webhook.config.LoggingConfig
import kim.hhhhhy.x.webhook.config.MessageMatcher
import kim.hhhhhy.x.webhook.config.OutgoingConfig
import kim.hhhhhy.x.webhook.config.OutgoingCooldownConfig
import kim.hhhhhy.x.webhook.config.OutgoingRoute
import kim.hhhhhy.x.webhook.config.PluginConfig
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.model.EventContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class OutgoingRouteProcessorTest {
    @Test
    fun defaultAndCompleteExampleExposeParsedCooldowns(): Unit {
        val defaultRoot = OutgoingRouteProcessorTest::class.java.getResourceAsStream("/webhook_config.yml").use { input ->
            requireNotNull(input)
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Map<String, Any?>>(input)
        }
        val defaultConfig = WebHookConfig.parseConfig(defaultRoot)
        val defaultCooldown = defaultConfig.outgoing.routes
            .single { it.id == "geek2api-monitor-command" }
            .cooldown

        assertTrue(defaultCooldown.enabled)
        assertEquals(60_000L, defaultCooldown.personalMillis)
        assertEquals(10_000L, defaultCooldown.administratorMillis)
        assertEquals(5_000L, defaultCooldown.globalMillis)
        assertTrue(defaultCooldown.notify)
        assertTrue(defaultCooldown.message.contains("${'$'}{cooldown.remainingSeconds}"))

        val exampleRoot = File("examples/webhook_config.geek2api-cli-bridge.yml").inputStream().use { input ->
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Map<String, Any?>>(input)
        }
        val exampleConfig = WebHookConfig.parseConfig(exampleRoot)
        val exampleCooldown = exampleConfig.outgoing.routes
            .single { it.id == "geek2api-monitor-group-command" }
            .cooldown

        assertEquals(defaultCooldown, exampleCooldown)
    }

    @Test
    fun parserKeepsMissingCooldownDisabledAndUsesSafeDefaults(): Unit {
        val config = WebHookConfig.parseConfig(
            mapOf(
                "outgoing" to mapOf(
                    "routes" to listOf(
                        mapOf("id" to "without-cooldown"),
                        mapOf(
                            "id" to "with-cooldown",
                            "cooldown" to mapOf(
                                "personal_ms" to 1_500,
                                "global_ms" to -100
                            )
                        ),
                        mapOf(
                            "id" to "silent-cooldown",
                            "cooldown" to mapOf(
                                "personal_ms" to 1_000,
                                "message" to ""
                            )
                        )
                    )
                )
            )
        )

        val withoutCooldown = config.outgoing.routes.single { it.id == "without-cooldown" }.cooldown
        assertFalse(withoutCooldown.enabled)
        assertEquals(0L, withoutCooldown.personalMillis)

        val withCooldown = config.outgoing.routes.single { it.id == "with-cooldown" }.cooldown
        assertTrue(withCooldown.enabled)
        assertEquals(1_500L, withCooldown.personalMillis)
        assertEquals(1_500L, withCooldown.administratorMillis)
        assertEquals(0L, withCooldown.globalMillis)
        assertTrue(withCooldown.notify)
        assertTrue(withCooldown.message.contains("${'$'}{cooldown.remainingSeconds}"))

        val silentCooldown = config.outgoing.routes.single { it.id == "silent-cooldown" }.cooldown
        assertEquals("", silentCooldown.message)
    }

    @Test
    fun personalCooldownBlocksOnlyTheSameSenderAndRendersFeedback(): Unit = runBlocking {
        val clock = AtomicLong(0L)
        val executions = AtomicInteger()
        val feedback = mutableListOf<String>()
        val route = route(
            cooldown = cooldown(
                personalMillis = 2_000L,
                administratorMillis = 500L,
                message = "${'$'}{cooldown.scope}:${'$'}{cooldown.remainingSeconds}:${'$'}{cooldown.routeId}"
            )
        )
        val processor = processor(clock, executions)
        val config = config(route)

        processor.process(config, event(senderId = 100L), NORMAL_ACTOR, feedback::add)
        clock.set(250L)
        processor.process(config, event(senderId = 100L), NORMAL_ACTOR, feedback::add)
        processor.process(config, event(senderId = 200L), NORMAL_ACTOR, feedback::add)
        clock.set(2_000L)
        processor.process(config, event(senderId = 100L), NORMAL_ACTOR, feedback::add)

        assertEquals(3, executions.get())
        assertEquals(listOf("personal:2:monitor-command"), feedback)
    }

    @Test
    fun administratorUsesIndependentShorterCooldown(): Unit = runBlocking {
        val clock = AtomicLong(0L)
        val executions = AtomicInteger()
        val feedback = mutableListOf<String>()
        val route = route(
            cooldown = cooldown(
                personalMillis = 2_000L,
                administratorMillis = 500L,
                message = "${'$'}{cooldown.scope}:${'$'}{cooldown.remainingMillis}"
            )
        )
        val processor = processor(clock, executions)
        val config = config(route)

        processor.process(config, event(senderId = 100L), ADMIN_ACTOR, feedback::add)
        clock.set(100L)
        processor.process(config, event(senderId = 100L), ADMIN_ACTOR, feedback::add)
        clock.set(500L)
        processor.process(config, event(senderId = 100L), ADMIN_ACTOR, feedback::add)

        processor.process(config, event(senderId = 200L), NORMAL_ACTOR, feedback::add)
        clock.set(1_000L)
        processor.process(config, event(senderId = 200L), NORMAL_ACTOR, feedback::add)

        assertEquals(3, executions.get())
        assertEquals(listOf("administrator:400", "personal:1500"), feedback)
    }

    @Test
    fun globalCooldownBlocksDifferentSendersAndReportsGlobalScope(): Unit = runBlocking {
        val clock = AtomicLong(0L)
        val executions = AtomicInteger()
        val feedback = mutableListOf<String>()
        val route = route(
            cooldown = cooldown(
                personalMillis = 0L,
                administratorMillis = 0L,
                globalMillis = 3_000L,
                message = "${'$'}{cooldown.scope}:${'$'}{cooldown.remainingSeconds}"
            )
        )
        val processor = processor(clock, executions)
        val config = config(route)

        processor.process(config, event(senderId = 100L), NORMAL_ACTOR, feedback::add)
        clock.set(1_000L)
        processor.process(config, event(senderId = 200L), ADMIN_ACTOR, feedback::add)
        clock.set(3_000L)
        processor.process(config, event(senderId = 200L), ADMIN_ACTOR, feedback::add)

        assertEquals(2, executions.get())
        assertEquals(listOf("global:2"), feedback)
    }

    @Test
    fun longestActiveCooldownDeterminesTheFeedback(): Unit = runBlocking {
        val clock = AtomicLong(0L)
        val executions = AtomicInteger()
        val feedback = mutableListOf<String>()
        val route = route(
            cooldown = cooldown(
                personalMillis = 10_000L,
                administratorMillis = 2_000L,
                globalMillis = 3_000L,
                message = "${'$'}{cooldown.scope}:${'$'}{cooldown.remainingSeconds}"
            )
        )
        val processor = processor(clock, executions)
        val config = config(route)

        processor.process(config, event(senderId = 100L), NORMAL_ACTOR, feedback::add)
        clock.set(1_000L)
        processor.process(config, event(senderId = 100L), NORMAL_ACTOR, feedback::add)
        processor.process(config, event(senderId = 200L), NORMAL_ACTOR, feedback::add)

        assertEquals(1, executions.get())
        assertEquals(listOf("personal:9", "global:2"), feedback)
    }

    @Test
    fun authorizedAdministratorBypassesWithoutConsumingCooldown(): Unit = runBlocking {
        val clock = AtomicLong(0L)
        val executions = AtomicInteger()
        val feedback = mutableListOf<String>()
        val route = route(
            cooldown = cooldown(
                personalMillis = 10_000L,
                administratorMillis = 10_000L,
                globalMillis = 5_000L,
                message = "${'$'}{cooldown.scope}"
            )
        )
        val processor = processor(clock, executions)
        val config = config(route)

        processor.process(config, event(senderId = 900L), BYPASS_ADMIN_ACTOR, feedback::add)
        processor.process(config, event(senderId = 900L), BYPASS_ADMIN_ACTOR, feedback::add)
        processor.process(config, event(senderId = 100L), NORMAL_ACTOR, feedback::add)
        processor.process(config, event(senderId = 900L), BYPASS_ADMIN_ACTOR, feedback::add)
        processor.process(config, event(senderId = 200L), NORMAL_ACTOR, feedback::add)

        assertEquals(4, executions.get())
        assertEquals(listOf("global"), feedback)
    }

    @Test
    fun clearCooldownsResetsAllRouteState(): Unit = runBlocking {
        val clock = AtomicLong(0L)
        val executions = AtomicInteger()
        val route = route(cooldown = cooldown(personalMillis = 60_000L, globalMillis = 5_000L))
        val processor = processor(clock, executions)
        val config = config(route)

        processor.process(config, event(), NORMAL_ACTOR) {}
        processor.process(config, event(), NORMAL_ACTOR) {}
        processor.clearCooldowns()
        processor.process(config, event(), NORMAL_ACTOR) {}

        assertEquals(2, executions.get())
    }

    @Test
    fun concurrentTriggersAtomicallyAllowOnlyOneExecution(): Unit = runBlocking {
        val clock = AtomicLong(0L)
        val executions = AtomicInteger()
        val blocked = AtomicInteger()
        val route = route(cooldown = cooldown(personalMillis = 60_000L, globalMillis = 5_000L))
        val processor = processor(clock, executions)
        val config = config(route)

        coroutineScope {
            List(32) {
                async(Dispatchers.Default) {
                    processor.process(config, event(), NORMAL_ACTOR) {
                        blocked.incrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, executions.get())
        assertEquals(31, blocked.get())
    }

    private fun processor(clock: AtomicLong, executions: AtomicInteger): OutgoingRouteProcessor {
        return OutgoingRouteProcessor(
            clockMillis = clock::get,
            actionExecutor = { _, _ ->
                executions.incrementAndGet()
                Unit
            }
        )
    }

    private fun config(route: OutgoingRoute): PluginConfig {
        return PluginConfig.safeDefault().copy(
            outgoing = OutgoingConfig(routes = listOf(route)),
            logging = LoggingConfig(
                request = false,
                response = false,
                errorStacktrace = false,
                debug = false
            )
        )
    }

    private fun route(cooldown: OutgoingCooldownConfig): OutgoingRoute {
        return OutgoingRoute(
            id = "monitor-command",
            enabled = true,
            events = listOf("group_message"),
            groups = emptyList(),
            friends = emptyList(),
            senders = emptyList(),
            message = MessageMatcher(
                contains = listOf("监控截图"),
                startsWith = emptyList(),
                endsWith = emptyList(),
                regex = emptyList()
            ),
            condition = null,
            cooldown = cooldown,
            actions = emptyList()
        )
    }

    private fun cooldown(
        personalMillis: Long,
        administratorMillis: Long = personalMillis,
        globalMillis: Long = 0L,
        notify: Boolean = true,
        message: String = "指令冷却中，请等待 ${'$'}{cooldown.remainingSeconds} 秒。"
    ): OutgoingCooldownConfig {
        return OutgoingCooldownConfig(
            enabled = true,
            personalMillis = personalMillis,
            administratorMillis = administratorMillis,
            globalMillis = globalMillis,
            notify = notify,
            message = message
        )
    }

    private fun event(senderId: Long = 100L): EventContext {
        return EventContext(
            type = "group_message",
            botId = 1L,
            groupId = 123L,
            friendId = null,
            senderId = senderId,
            senderName = "tester-$senderId",
            messageText = "监控截图",
            timestamp = 1L
        )
    }

    private companion object {
        val NORMAL_ACTOR = OutgoingActor(administrator = false, bypassCooldown = false)
        val ADMIN_ACTOR = OutgoingActor(administrator = true, bypassCooldown = false)
        val BYPASS_ADMIN_ACTOR = OutgoingActor(administrator = true, bypassCooldown = true)
    }
}
