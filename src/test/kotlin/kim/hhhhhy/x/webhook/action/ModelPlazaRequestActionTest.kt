package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.PluginConfig
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.model.EventContext
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.mamoe.mirai.message.data.ForwardMessage
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.mock.MockBotFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ModelPlazaRequestActionTest {
    private fun result(name: String, image: ByteArray? = null): ModelPlazaBrowserResult =
        ModelPlazaBrowserResult(name, "81.46 t/s", "19.5s", image)

    @Test
    fun `three models should share one ordinary message chain`() = runBlocking {
        val bot = MockBotFactory.newMockBotBuilder().id(10001).createNoInstanceRegister()
        try {
            val message = ModelPlazaRequestAction.buildMessage(
                bot.addFriend(20001, "tester"),
                listOf(result("model-a"), result("model-b"), result("model-c"))
            )
            val chain = assertIs<MessageChain>(message)
            val text = chain.contentToString()
            assertFalse(chain.any { it is ForwardMessage })
            assertTrue(text.startsWith("model-a\n吞吐: 81.46tok/s 延迟: 19.5s\n"))
            assertTrue(text.contains("\n\nmodel-b\n"))
            assertTrue(text.contains("\n\nmodel-c\n"))
            assertEquals(3, Regex("图片：暂无数据").findAll(text).count())
        } finally {
            bot.close()
        }
    }

    @Test
    fun `four models should each own one forward node`() = runBlocking {
        val bot = MockBotFactory.newMockBotBuilder().id(10002).createNoInstanceRegister()
        try {
            val names = listOf("model-a", "model-b", "model-c", "model-d")
            val forward = assertIs<ForwardMessage>(ModelPlazaRequestAction.buildMessage(
                bot.addGroup(20002, "test group"), names.map { result(it) }
            ))
            assertEquals(4, forward.nodeList.size)
            forward.nodeList.forEachIndexed { index, node ->
                val text = node.messageChain.contentToString()
                assertTrue(text.startsWith(names[index] + "\n"))
                names.filter { it != names[index] }.forEach { assertFalse(text.contains(it)) }
                assertEquals(bot.id, node.senderId)
            }
        } finally {
            bot.close()
        }
    }

    @Test
    fun `one upload failure should not remove models or remaining images`() = runBlocking {
        val bot = MockBotFactory.newMockBotBuilder().id(10003).createNoInstanceRegister()
        try {
            val image = Image.newBuilder("{00112233-4455-6677-8899-AABBCCDDEEFF}.png").build()
            val calls = mutableListOf<Int>()
            val chain = assertIs<MessageChain>(ModelPlazaRequestAction.buildMessage(
                bot.addFriend(20003, "tester"),
                listOf(result("model-a", byteArrayOf(1)), result("model-b", byteArrayOf(2)))
            ) { bytes ->
                calls += bytes.single().toInt()
                if (bytes.single() == 1.toByte()) error("test upload failure")
                image
            })
            assertEquals(listOf(1, 2), calls)
            assertEquals(1, chain.count { it is Image })
            assertTrue(chain.contentToString().contains("model-a"))
            assertTrue(chain.contentToString().contains("model-b"))
            assertTrue(chain.contentToString().contains("图片：暂无数据"))
        } finally {
            bot.close()
        }
    }

    @Test
    fun `upload cancellation should propagate`() = runBlocking {
        val bot = MockBotFactory.newMockBotBuilder().id(10004).createNoInstanceRegister()
        try {
            assertFailsWith<CancellationException> {
                ModelPlazaRequestAction.buildMessage(
                    bot.addFriend(20004, "tester"), listOf(result("model-a", byteArrayOf(1)))
                ) { throw CancellationException("cancelled") }
            }
        } finally {
            bot.close()
        }
    }

    @Test
    fun `empty result names must never fill another model`() {
        val aligned = ModelPlazaRequestAction.alignResults(
            listOf("gpt-4", "gpt-4-turbo"),
            listOf(result(" "), result("gpt-4-turbo"))
        )
        assertEquals("gpt-4", aligned[0].modelName)
        assertNull(aligned[0].throughputText)
        assertEquals("gpt-4-turbo", aligned[1].modelName)
        assertEquals("81.46 t/s", aligned[1].throughputText)
    }

    @Test
    fun `alignment should only accept exact result names`() {
        val aligned = ModelPlazaRequestAction.alignResults(
            listOf("gpt", "gpt-4"), listOf(result("GPT-4"), result("gpt-4-turbo"))
        )
        assertEquals("gpt", aligned[0].modelName)
        assertNull(aligned[0].throughputText)
        assertEquals("gpt-4", aligned[1].modelName)
        assertEquals("81.46 t/s", aligned[1].throughputText)
    }

    @Test
    fun `request keyword extraction should use its own prefixes and ignore group regex`() {
        val config = WebHookConfig.parseConfig(mapOf(
            "model_plaza" to mapOf(
                "queries" to mapOf(
                    "groups" to mapOf("keyword_extraction" to mapOf("pattern" to "^分组\\s+(\\S+)$"))
                )
            )
        ))
        val queryConfig = config.modelPlaza.queries.groups
        fun extract(text: String, params: Map<String, Any?> = emptyMap()): String {
            val context = ExecutionContext(config, event = event(text))
            return ModelPlazaRequestAction.extractPattern(
                ActionConfig(null, "query_model_plaza_requests", true, params), context, queryConfig
            )
        }
        assertEquals("gpt-4", extract("请求 gpt-4"))
        assertEquals("claude opus", extract("request claude opus"))
        assertEquals("", extract("分组 gpt"))
        assertEquals("gpt", extract("查请求 gpt", mapOf("prefixes" to listOf("查请求"))))
    }

    @Test
    fun `runtime location should allow query strings but never leave model plaza`() {
        val base = "https://example.invalid/model-plaza"
        assertTrue(ModelPlazaRequestAction.isModelPlazaLocation("$base?q=gpt#top", base))
        assertTrue(ModelPlazaRequestAction.isModelPlazaLocation("https://example.invalid:443/model-plaza", base))
        assertFalse(ModelPlazaRequestAction.isModelPlazaLocation("https://example.invalid/login?redirect=/model-plaza", base))
        assertFalse(ModelPlazaRequestAction.isModelPlazaLocation("https://example.invalid:8443/model-plaza", base))
        assertFalse(ModelPlazaRequestAction.isModelPlazaLocation("not a url", base))
    }

    @Test
    fun `authentication failure should refresh the token and retry exactly once`() = runBlocking {
        val usedTokens = mutableListOf<String>()
        var refreshCalls = 0
        val result = ModelPlazaRequestAction.collectWithTokenRefresh(
            initialToken = "token-1",
            refreshToken = { refreshCalls++; "token-2" }
        ) { token ->
            usedTokens += token
            if (token == "token-1") {
                throw IllegalStateException(
                    "browser access token was rejected: Model Plaza redirected to the login page instead of /model-plaza"
                )
            }
            "collected"
        }
        assertEquals("collected", result)
        assertEquals(listOf("token-1", "token-2"), usedTokens)
        assertEquals(1, refreshCalls)

        // 刷新后仍然认证失败时不再无限重试，直接把错误交给调用方发送 failure_message。
        var attempts = 0
        assertFailsWith<IllegalStateException> {
            ModelPlazaRequestAction.collectWithTokenRefresh<String>("token-1", { "token-2" }) {
                attempts++
                throw IllegalStateException("auth bootstrap failed: HTTP 401")
            }
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `non authentication failures should not refresh the token`() = runBlocking {
        var refreshCalls = 0
        assertFailsWith<IllegalStateException> {
            ModelPlazaRequestAction.collectWithTokenRefresh<String>("token-1", { refreshCalls++; "token-2" }) {
                throw IllegalStateException("Model Plaza model cards were not rendered before timeout")
            }
        }
        assertEquals(0, refreshCalls)
        assertFailsWith<CancellationException> {
            ModelPlazaRequestAction.collectWithTokenRefresh<String>("token-1", { refreshCalls++; "token-2" }) {
                throw CancellationException("cancelled")
            }
        }
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `failure summaries should redact tokens secrets and authorization values`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.c2lnbmF0dXJlLXZhbHVl"
        val raw = """CLI Bridge start 响应缺少 bridge_id，实际响应: {"poll_secret":"ps-secret-1","access_token":"at-secret-2"}"""
        val redacted = ModelPlazaRequestAction.redactSecrets(raw)
        assertFalse(redacted.contains("ps-secret-1"))
        assertFalse(redacted.contains("at-secret-2"))
        assertTrue(redacted.contains("bridge_id"))

        val header = ModelPlazaRequestAction.redactSecrets("Authorization: Bearer $jwt; refresh_token=rt-secret-3")
        assertFalse(header.contains(jwt))
        assertFalse(header.contains("rt-secret-3"))
        assertFalse(ModelPlazaRequestAction.redactSecrets("token rejected: $jwt").contains(jwt))
        assertEquals("max_tokens=1024", ModelPlazaRequestAction.redactSecrets("max_tokens=1024"))

        val summary = ModelPlazaRequestAction.safeErrorSummary(
            IllegalStateException("Model Plaza API 请求失败", IllegalStateException("""{"refresh_token":"rt-secret-4"}"""))
        )
        assertTrue(summary.startsWith("Model Plaza API 请求失败 <- "))
        assertFalse(summary.contains("rt-secret-4"))
    }

    @Test
    fun `login redirects and unauthorized responses should trigger one token refresh`() {
        assertTrue(ModelPlazaRequestAction.isBrowserAuthFailure(IllegalStateException(
            "browser access token was rejected: Model Plaza redirected to the login page instead of /model-plaza"
        )))
        assertTrue(ModelPlazaRequestAction.isBrowserAuthFailure(
            IllegalStateException("wrapper", IllegalStateException("auth bootstrap failed: HTTP 401"))
        ))
        assertFalse(ModelPlazaRequestAction.isBrowserAuthFailure(IllegalStateException("HTTP 4010 unrelated")))
        assertFalse(ModelPlazaRequestAction.isBrowserAuthFailure(
            IllegalStateException("Model Plaza page navigated outside /model-plaza")
        ))
    }

    @Test
    fun `missing metrics should retain the model and mark each field separately`() {
        assertEquals(
            "model-a\n吞吐: 暂无数据 延迟: 0.512s",
            ModelPlazaRequestAction.formatModelText(ModelPlazaBrowserResult("model-a", null, "512ms", null))
        )
        assertEquals(
            "model-b\n吞吐: 81.46tok/s 延迟: 暂无数据",
            ModelPlazaRequestAction.formatModelText(ModelPlazaBrowserResult("model-b", "81.46 tps", null, null))
        )
    }

    @Test
    fun `empty matches should use a clear configurable message`() {
        val context = ExecutionContext(PluginConfig.safeDefault())
        val defaultAction = ActionConfig(null, "query_model_plaza_requests", true, emptyMap())
        val customAction = defaultAction.copy(params = mapOf("empty_message" to "没有 ${'$'}{query}"))
        assertEquals("未找到包含“gpt”的模型", ModelPlazaRequestAction.emptyMessage(defaultAction, context, "gpt"))
        assertEquals("没有 gpt", ModelPlazaRequestAction.emptyMessage(customAction, context, "gpt"))
    }

    @Test
    fun `page scope should reject other origins paths credentials and fragments`() {
        val base = "https://example.invalid/model-plaza"
        assertTrue(ModelPlazaRequestAction.isModelPlazaPage(base, base))
        assertTrue(ModelPlazaRequestAction.isModelPlazaPage("$base/", base))
        listOf(
            "https://other.invalid/model-plaza",
            "https://example.invalid/login",
            "http://example.invalid/model-plaza",
            "https://user:pass@example.invalid/model-plaza",
            "$base?query=gpt",
            "$base#other"
        ).forEach { assertFalse(ModelPlazaRequestAction.isModelPlazaPage(it, base), it) }
    }

    @Test
    fun `browser action should scope authentication and carry query settings`() {
        val config = WebHookConfig.parseConfig(mapOf(
            "model_plaza" to mapOf(
                "enabled" to true,
                "base_url" to "https://example.invalid/model-plaza",
                "auth" to mapOf("profile_url" to "https://example.invalid/api/v1/user/profile")
            ),
            "browser" to mapOf("enabled" to true, "allowed_hosts" to listOf("example.invalid"))
        ))
        val context = ExecutionContext(config)
        val action = ActionConfig(null, "query_model_plaza_requests", true, mapOf("search_wait_ms" to 100))
        val browserAction = ModelPlazaRequestAction.buildBrowserAction(
            action, context, config.modelPlaza, "gpt", "test-only-token"
        )
        val auth = browserAction.params["auth"] as Map<*, *>
        assertEquals(listOf("example.invalid"), auth["header_hosts"])
        assertFalse(auth.containsKey("cli_bridge"))
        assertEquals("https://example.invalid", (auth["local_storage"] as Map<*, *>)["origin"])
        assertEquals("https://example.invalid/api/v1/user/profile", (auth["bootstrap"] as Map<*, *>)["url"])
        assertEquals("gpt", browserAction.params["search_pattern"])
        assertEquals(100L, browserAction.params["search_wait_ms"])
        // 未显式覆盖时不下发选择器，由采集器统一使用内置默认值。
        listOf("search_selector", "model_card_selector", "model_name_selector", "performance_selector", "sparkline_selector",
            "percentage_selector")
            .forEach { key -> assertFalse(browserAction.params.containsKey(key), key) }
        val parts = ((browserAction.params["screenshot"] as Map<*, *>)["parts"] as List<*>)
        assertEquals(1, parts.size)

        // 生成的配置必须能通过既有浏览器动作校验：token 认证 + 同源 bootstrap + localStorage。
        val spec = WebPageScreenshotAction.parseSpec(browserAction, context)
        assertEquals(listOf("example.invalid"), spec.auth?.headerHosts)
        assertNull(spec.auth?.cliBridge)
        assertEquals("auth_user", spec.auth?.bootstrap?.localStorageKey)
        assertEquals("https://example.invalid", spec.auth?.localStorage?.origin)

        val overridden = ModelPlazaRequestAction.buildBrowserAction(
            action.copy(params = mapOf("model_card_selector" to ".card", "sparkline_selector" to ".chart",
                "percentage_selector" to ".percentage")),
            context, config.modelPlaza, "gpt", "test-only-token"
        )
        assertEquals(".card", overridden.params["model_card_selector"])
        assertEquals(".chart", overridden.params["sparkline_selector"])
        assertEquals(".percentage", overridden.params["percentage_selector"])
        assertFalse(overridden.params.containsKey("search_wait_ms"))

        assertFailsWith<IllegalArgumentException> {
            ModelPlazaRequestAction.buildBrowserAction(
                action.copy(params = mapOf("page_url" to "https://other.invalid/model-plaza")),
                context, config.modelPlaza, "gpt", "test-only-token"
            )
        }
    }

    private fun event(text: String): EventContext = EventContext(
        type = "group_message",
        botId = 10000L,
        groupId = 20000L,
        friendId = null,
        senderId = 30000L,
        senderName = "tester",
        messageText = text,
        timestamp = 0L
    )
}
