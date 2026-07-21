package kim.hhhhhy.x.webhook.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kim.hhhhhy.x.webhook.action.WebPageScreenshotAction
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.PluginConfig
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class XAiWebHookBrowserApiTest {
    private val request = BrowserTextListRequest(
        actionId = "monitor",
        partId = "monitor-status",
        itemSelector = "//*[@id=\"app\"]/button",
        nameRelativeSelector = "./div[1]",
        statusRelativeSelector = "./div[5]/div[1]",
        emptySelector = ".empty-state"
    )

    @Test
    fun requestRejectsBlankAndOversizedInputs(): Unit {
        assertFailsWith<IllegalArgumentException> {
            request.copy(actionId = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(statusRelativeSelector = "")
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(maxItems = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(maxItems = 257)
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(itemSelector = "#app\nbutton")
        }
    }

    @Test
    fun statusAttributeRejectsInvalidNames(): Unit {
        val config = PluginConfig.safeDefault()
        listOf("", " ", "bad attribute", "a".repeat(101)).forEach { attribute ->
            assertFailsWith<IllegalArgumentException> {
                runBlocking {
                    XAiWebHookBrowserApi.queryTextList(
                        request = request,
                        config = config,
                        statusAttribute = attribute
                    )
                }
            }
        }
    }

    @Test
    fun resolveActionRequiresNamedEnabledScreenshotAction(): Unit {
        val action = screenshotAction("monitor")
        val config = PluginConfig.safeDefault().copy(actions = mapOf("monitor" to action))

        assertSame(action, XAiWebHookBrowserApi.resolveAction(config, request))
        assertFailsWith<IllegalStateException> {
            XAiWebHookBrowserApi.resolveAction(config, request.copy(actionId = "missing"))
        }
        assertFailsWith<IllegalArgumentException> {
            XAiWebHookBrowserApi.resolveAction(
                config.copy(actions = mapOf("monitor" to action.copy(enabled = false))),
                request
            )
        }
        assertFailsWith<IllegalArgumentException> {
            XAiWebHookBrowserApi.resolveAction(
                config.copy(actions = mapOf("monitor" to action.copy(type = "http_request"))),
                request
            )
        }
    }

    @Test
    fun missingPartFailsBeforeBrowserStartup(): Unit {
        val config = PluginConfig.safeDefault().copy(
            browser = PluginConfig.safeDefault().browser.copy(enabled = true),
            actions = mapOf("monitor" to screenshotAction("monitor"))
        )

        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                XAiWebHookBrowserApi.queryTextList(request.copy(partId = "missing"), config)
            }
        }
    }

    @Test
    fun browserIntegrationReadsRelativeXPathWhenExplicitlyEnabled(): Unit {
        if (System.getenv("XAI_WEBHOOK_BROWSER_IT") != "true") return

        val alphaTimeline = timeline("15 秒前 · 错误 · 30000ms")
        val betaTimeline = timeline("15 秒前 · 正常 · 2402ms")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/monitor") { exchange ->
            exchange.respondHtml(
                """
                <!doctype html>
                <html><body>
                <main id="app">
                  <button id="channel-a" data-channel-id="alpha" aria-label="Alpha channel">
                    <div>Alpha</div><div></div><div></div><div></div>
                    <div><div>错误</div><div>$alphaTimeline</div></div>
                  </button>
                  <button id="channel-b">
                    <div>Beta</div><div></div><div></div><div></div>
                    <div><div>正常</div><div>$betaTimeline</div></div>
                  </button>
                  <button id="channel-c">
                    <div>Gamma</div><div></div><div></div><div></div><div></div>
                  </button>
                </main>
                </body></html>
                """.trimIndent()
            )
        }
        server.createContext("/empty") { exchange ->
            exchange.respondHtml("<html><body><main id=\"app\"><div class=\"empty-state\">暂无渠道</div></main></body></html>")
        }
        server.start()
        val monitorUrl = "http://127.0.0.1:${server.address.port}/monitor"
        val emptyUrl = "http://127.0.0.1:${server.address.port}/empty"
        val base = PluginConfig.safeDefault()
        val config = base.copy(
            browser = base.browser.copy(
                enabled = true,
                engine = "chromium",
                channel = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    "msedge"
                } else {
                    null
                },
                executablePath = null,
                allowedHosts = listOf("127.0.0.1")
            ),
            actions = mapOf(
                "monitor" to screenshotAction("monitor", monitorUrl),
                "empty" to screenshotAction("empty", emptyUrl)
            )
        )

        try {
            val result = runBlocking {
                XAiWebHookBrowserApi.queryTextList(request, config)
            }

            assertEquals(monitorUrl, result.effectiveUrl)
            assertEquals(false, result.emptyState)
            assertEquals(3, result.items.size)
            assertEquals("Alpha", result.items[0].nameText)
            assertEquals("错误", result.items[0].statusText)
            assertEquals("alpha", result.items[0].dataChannelId)
            assertEquals("channel-a", result.items[0].domId)
            assertEquals("Alpha channel", result.items[0].ariaLabel)
            assertEquals("Beta", result.items[1].nameText)
            assertEquals("正常", result.items[1].statusText)
            assertNull(result.items[1].dataChannelId)
            assertEquals("Gamma", result.items[2].nameText)
            assertNull(result.items[2].statusText)
            assertTrue(result.items[0].itemText.orEmpty().contains("Alpha"))

            val titles = runBlocking {
                XAiWebHookBrowserApi.queryTextList(
                    request = request.copy(statusRelativeSelector = "./div[5]/div[2]/div[60]"),
                    config = config,
                    statusAttribute = "title"
                )
            }
            assertEquals("15 秒前 · 错误 · 30000ms", titles.items[0].statusText)
            assertEquals("15 秒前 · 正常 · 2402ms", titles.items[1].statusText)
            assertNull(titles.items[2].statusText)

            val empty = runBlocking {
                XAiWebHookBrowserApi.queryTextList(request.copy(actionId = "empty"), config)
            }
            assertEquals(emptyUrl, empty.effectiveUrl)
            assertTrue(empty.emptyState)
            assertTrue(empty.items.isEmpty())
        } finally {
            WebPageScreenshotAction.reset()
            server.stop(0)
        }
    }

    private fun screenshotAction(id: String, url: String = "https://example.invalid/monitor"): ActionConfig {
        return ActionConfig(
            id = id,
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "screenshot" to mapOf(
                    "parts" to listOf(
                        mapOf(
                            "id" to "monitor-status",
                            "url" to url,
                            "selector" to "#app"
                        )
                    )
                )
            )
        )
    }

    private fun timeline(lastTitle: String): String = buildString {
        repeat(59) { append("<div></div>") }
        append("<div title=\"").append(lastTitle).append("\"></div>")
    }

    private fun HttpExchange.respondHtml(body: String): Unit {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { output -> output.write(bytes) }
    }
}
