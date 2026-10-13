package kim.hhhhhy.x.webhook.action

import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.TimeoutError
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.BrowserConfig
import kim.hhhhhy.x.webhook.config.PluginConfig
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ModelPlazaBrowserTest {
    @Test
    fun `dynamic overlapping cards should produce their own metrics and clipped PNGs`() = runBlocking {
        withFixture { action, context ->
            val results = WebPageScreenshotAction.collectModelPlazaRequests(
                action, context, listOf("gpt-4", "gpt-4-turbo", "gpt-no-metrics")
            )
            assertEquals(listOf("gpt-4", "gpt-4-turbo", "gpt-no-metrics"), results.map { it.modelName })
            assertEquals("81.46 t/s", results[0].throughputText)
            assertEquals("19.5s", results[0].latencyText)
            assertEquals("50.0 tps", results[1].throughputText)
            assertEquals("1250ms", results[1].latencyText)
            assertNull(results[2].throughputText)
            assertNull(results[2].latencyText)
            assertNull(results[2].screenshot)
            for (index in 0..1) {
                val bytes = assertNotNull(results[index].screenshot)
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                assertEquals(100, image.width)
                assertTrue(image.height in 20..21)
                val expected = if (index == 0) Color(0, 128, 0) else Color.BLUE
                assertEquals(expected.rgb, image.getRGB(50, 10))
            }
            // fixture 搜索被异步渲染更新，collector 等待后才读取，截图严格为 aria-label 元素区域。
        }
    }

    @Test
    fun `absent matched model should be retained without reusing another card`() = runBlocking {
        withFixture { action, context ->
            val results = WebPageScreenshotAction.collectModelPlazaRequests(
                action, context, listOf("gpt-4", "gpt-missing")
            )
            assertEquals("gpt-missing", results[1].modelName)
            assertNull(results[1].throughputText)
            assertNull(results[1].screenshot)
        }
    }

    @Test
    fun `invalid card selector should fail rather than return empty metrics`() = runBlocking {
        withFixture { action, context ->
            assertFailsWith<PlaywrightException> {
                WebPageScreenshotAction.collectModelPlazaRequests(
                    action.copy(params = action.params + ("model_card_selector" to "[")),
                    context, listOf("gpt-4")
                )
            }
        }
    }

    @Test
    fun `missing search and card selectors should fail explicitly`() = runBlocking {
        withFixture { action, context ->
            assertFailsWith<TimeoutError> {
                WebPageScreenshotAction.collectModelPlazaRequests(
                    shortTimeout(action, "search_selector", "#missing-search"), context, listOf("gpt-4")
                )
            }
            assertFailsWith<TimeoutError> {
                WebPageScreenshotAction.collectModelPlazaRequests(
                    shortTimeout(action, "model_card_selector", "#missing-card"), context, listOf("gpt-4")
                )
            }
        }
    }

    @Test
    fun `display name cards should map back to their raw model names`() = runBlocking {
        withFixture { action, context ->
            val result = WebPageScreenshotAction.collectModelPlazaRequests(
                action, context, listOf("claude-opus-5")
            ).single()
            assertEquals("claude-opus-5", result.modelName)
            assertEquals("12.5 t/s", result.throughputText)
            assertEquals("850ms", result.latencyText)
            assertNotNull(result.screenshot)
        }
    }

    @Test
    fun `server side login redirect should be reported as an authentication failure`() = runBlocking {
        withFixture(redirect = Redirect.SERVER) { action, context ->
            // 无论 302 目标被路由拦截还是页面已落到 /login，都必须转换为可触发 token 刷新的认证失败。
            val error = assertFailsWith<IllegalStateException> {
                WebPageScreenshotAction.collectModelPlazaRequests(action, context, listOf("gpt-4"))
            }
            assertTrue(error.message.orEmpty().contains("redirected to the login page"), error.message)
            assertTrue(ModelPlazaRequestAction.isBrowserAuthFailure(error))
        }
    }

    @Test
    fun `client side login redirect should be reported as an authentication failure`() = runBlocking {
        withFixture(redirect = Redirect.CLIENT) { action, context ->
            val error = assertFailsWith<IllegalStateException> {
                WebPageScreenshotAction.collectModelPlazaRequests(action, context, listOf("gpt-4"))
            }
            assertTrue(error.message.orEmpty().contains("redirected to the login page"))
            assertTrue(ModelPlazaRequestAction.isBrowserAuthFailure(error))
        }
    }

    @Test
    fun `hidden sparkline timeout should keep other fields`() = runBlocking {
        withFixture { action, context ->
            val results = WebPageScreenshotAction.collectModelPlazaRequests(
                shortTimeout(action, "sparkline_selector", ".hidden-sparkline"), context, listOf("gpt-4")
            )
            assertEquals("81.46 t/s", results.single().throughputText)
            assertEquals("19.5s", results.single().latencyText)
            assertNull(results.single().screenshot)
        }
    }

    private fun shortTimeout(action: ActionConfig, key: String, selector: String): ActionConfig {
        val screenshot = action.params["screenshot"] as Map<*, *>
        val part = (screenshot["parts"] as List<*>).single() as Map<*, *>
        return action.copy(params = action.params + mapOf(
            key to selector,
            "screenshot" to mapOf("parts" to listOf(part + ("timeout_ms" to 500)))
        ))
    }

    private enum class Redirect { NONE, SERVER, CLIENT }

    private suspend fun withFixture(
        redirect: Redirect = Redirect.NONE,
        test: suspend (ActionConfig, ExecutionContext) -> Unit
    ): Unit {
        assumeTrue(System.getenv("XAI_WEBHOOK_BROWSER_IT") == "true", "Requires opt-in local Playwright browser")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model-plaza") { exchange ->
            if (redirect == Redirect.SERVER) {
                exchange.responseHeaders.add("Location", "/login")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                // CLIENT 模拟前端路由守卫：未登录时用 history API 改写到 /login，不产生新的文档请求。
                val html = if (redirect == Redirect.CLIENT) {
                    "<!doctype html><html><body><script>history.replaceState(null, '', '/login?redirect=/model-plaza')</script></body></html>"
                } else {
                    fixtureHtml()
                }
                val bytes = html.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.createContext("/login") { exchange ->
            val bytes = "<main>Login page</main>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val context = ExecutionContext(PluginConfig.safeDefault().copy(browser = BrowserConfig.safeDefault().copy(
            enabled = true,
            channel = "msedge",
            timeoutMillis = 5_000L,
            allowedHosts = listOf("127.0.0.1")
        )))
        val action = ActionConfig("request-fixture", "send_webpage_screenshot", true, mapOf(
            "search_pattern" to "gpt",
            "search_selector" to "#search",
            "search_wait_ms" to 600,
            "model_card_selector" to ".card",
            "model_name_selector" to "h3",
            "performance_selector" to ".performance",
            "screenshot" to mapOf("parts" to listOf(mapOf(
                "id" to "main",
                "url" to "http://127.0.0.1:${server.address.port}/model-plaza",
                "wait_until" to "domcontentloaded",
                "selector" to "body",
                "timeout_ms" to 5_000
            )))
        ))
        WebPageScreenshotAction.reset()
        WebPageScreenshotAction.open()
        try {
            test(action, context)
        } finally {
            WebPageScreenshotAction.reset()
            server.stop(0)
        }
    }

    private fun fixtureHtml(): String = """
        <!doctype html><html><body>
          <input id="search" type="search">
          <div class="card"><h3>gpt-4-turbo</h3><div class="performance">—</div>
            <div aria-label="近 5 个时段成功率，最低 80.0%" style="width:100px;height:20px;background:blue"></div>
          </div>
          <div class="card"><h3>gpt-4</h3><div class="performance">—</div>
            <div aria-label="近 5 个时段成功率，最低 0.0%" style="width:100px;height:20px;background:green"></div>
            <div class="hidden-sparkline" style="display:none;width:100px;height:20px"></div>
          </div>
          <div class="card"><h3>gpt-no-metrics</h3><p>暂无数据</p></div>
          <div class="card"><h3>Claude Opus 5</h3><div>claude-opus-5</div><div class="performance">—</div>
            <div role="img" aria-label="近 3 个时段成功率，最低 50.0%" style="width:100px;height:20px;background:red"></div>
          </div>
          <script>
            document.querySelector('#search').addEventListener('input', (event) => {
              setTimeout(() => {
                // 模拟前端把搜索词同步到地址栏：仍在 /model-plaza，只是追加了查询参数。
                history.replaceState(null, '', '/model-plaza?q=' + encodeURIComponent(event.target.value));
                const rows = document.querySelectorAll('.performance');
                rows[0].textContent = '50.0 tps 1250ms';
                rows[1].textContent = '81.46 t/s 19.5s';
                rows[2].textContent = '12.5 t/s 850ms 99.0%';
              }, 200);
            });
          </script>
        </body></html>
    """.trimIndent()
}
