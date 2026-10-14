package kim.hhhhhy.x.webhook.action

import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.TimeoutError
import com.sun.net.httpserver.HttpServer
import kim.hhhhhy.x.webhook.api.XAiWebHookRequestImageApi
import kim.hhhhhy.x.webhook.config.RequestImageConfigRuntime
import kim.hhhhhy.x.webhook.config.RequestImageOutputConfig
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import javax.imageio.ImageIO
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.BrowserConfig
import kim.hhhhhy.x.webhook.config.PluginConfig
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ModelPlazaBrowserTest {
    @TempDir
    lateinit var imageConfigDirectory: Path

    @Test
    fun `dynamic overlapping cards should use each models exact percentage XPath`() = runBlocking {
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
            // 直接截取每张卡片的图表和百分比元素，只校验合成尺寸，不比较浏览器抗锯齿颜色。
            for (result in results.take(2)) {
                val card = "[data-model='${result.modelName}']"
                val original = captureFixtureImage(action, context, "$card .sparkline")
                val percentage = captureFixtureImage(action, context, "$card .percentage")
                val image = decode(assertNotNull(result.screenshot))
                assertDimensionsWithMargins(image, original.width + percentage.width + 12,
                    maxOf(original.height + 6, percentage.height))
            }
        }
    }

    private fun decode(bytes: ByteArray): BufferedImage =
        assertNotNull(ByteArrayInputStream(bytes).use { input -> ImageIO.read(input) })

    private fun assertDimensionsWithMargins(image: BufferedImage, contentWidth: Int, contentHeight: Int) {
        assertEquals(contentWidth + ((contentWidth + 19) / 20) * 2, image.width)
        assertEquals(contentHeight + ((contentHeight + 99) / 100) * 2, image.height)
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

    @Test
    fun `percentage outside the sparkline should be captured by its exact card XPath`() = runBlocking {
        withFixture { action, context ->
            val result = WebPageScreenshotAction.collectModelPlazaRequests(
                action, context, listOf("gpt-sibling")
            ).single()
            val original = captureFixtureImage(action, context, "[data-model='gpt-sibling'] .sparkline")
            val percentage = captureFixtureImage(action, context, "[data-model='gpt-sibling'] .percentage")
            val decorated = decode(assertNotNull(result.screenshot))
            assertEquals("10.0 t/s", result.throughputText)
            assertEquals("2s", result.latencyText)
            assertDimensionsWithMargins(decorated, original.width + percentage.width + 12,
                maxOf(original.height + 6, percentage.height))
        }
    }

    @Test
    fun `card relative XPath and absolute XPath overrides should capture the percentage`() = runBlocking {
        withFixture { action, context ->
            val absoluteXPath = "//*[@id=\"app\"]/div[2]/div[2]/main/div/div[3]/div[1]/div[2]/div[2]/div/span[3]/span"
            for (selector in listOf(absoluteXPath, "./div[2]/div[2]/div/span[3]/span")) {
                val result = WebPageScreenshotAction.collectModelPlazaRequests(
                    action.copy(params = action.params + ("percentage_selector" to selector)),
                    context, listOf("gpt-4-turbo")
                ).single()
                val original = captureFixtureImage(action, context, "[data-model='gpt-4-turbo'] .sparkline")
                val percentage = captureFixtureImage(action, context, "[data-model='gpt-4-turbo'] .percentage")
                val decorated = decode(assertNotNull(result.screenshot))
                assertDimensionsWithMargins(decorated, original.width + percentage.width + 12,
                    maxOf(original.height + 6, percentage.height))
            }
        }
    }

    @Test
    fun `missing or invalid percentage selectors should retain the original image`() = runBlocking {
        withFixture { action, context ->
            val original = captureFixtureImage(action, context, "[data-model='gpt-4'] .sparkline")
            for (selector in listOf(".missing", "[")) {
                val result = WebPageScreenshotAction.collectModelPlazaRequests(
                    action.copy(params = action.params + ("percentage_selector" to selector)), context, listOf("gpt-4")
                ).single()
                val screenshot = decode(assertNotNull(result.screenshot))
                assertEquals("81.46 t/s", result.throughputText)
                assertEquals("19.5s", result.latencyText)
                assertDimensionsWithMargins(screenshot, original.width, original.height)
            }
        }
    }

    @Test
    fun `hidden percentage should preserve the original without a fabricated value`() = runBlocking {
        withFixture(html = fixtureHtml().replace("class=\"percentage\"", "class=\"percentage\" hidden")) { action, context ->
            val original = captureFixtureImage(action, context, "[data-model='gpt-4'] .sparkline")
            val result = WebPageScreenshotAction.collectModelPlazaRequests(action, context, listOf("gpt-4")).single()
            val screenshot = decode(assertNotNull(result.screenshot))
            assertDimensionsWithMargins(screenshot, original.width, original.height)
            assertEquals("81.46 t/s", result.throughputText)
        }
    }

    @Test
    fun `oversized original or decorated image should omit only its image and retain metrics`() = runBlocking {
        withFixture { action, context ->
            val decorated = assertNotNull(WebPageScreenshotAction.collectModelPlazaRequests(action, context, listOf("gpt-4"))
                .single().screenshot)
            for (limit in listOf(decorated.size.toLong() - 1L, 1L)) {
                val limitedContext = context.copy(config = context.config.copy(
                    browser = context.config.browser.copy(maxScreenshotBytes = limit)
                ))
                val results = WebPageScreenshotAction.collectModelPlazaRequests(
                    action, limitedContext, listOf("gpt-4", "claude-opus-5")
                )
                assertNull(results[0].screenshot)
                assertEquals("81.46 t/s", results[0].throughputText)
                assertEquals("19.5s", results[0].latencyText)
                assertEquals("12.5 t/s", results[1].throughputText)
                if (limit > 1L) assertNotNull(results[1].screenshot) else assertNull(results[1].screenshot)
            }
        }
    }

    @Test
    fun `valid canvas limit should omit only images and preserve all model metrics`() = runBlocking {
        withFixture { action, context ->
            val updated = XAiWebHookRequestImageApi.write(XAiWebHookRequestImageApi.read().copy(
                output = RequestImageOutputConfig(maxPixels = 1)
            ))
            assertTrue(updated.success, updated.error)
            val results = WebPageScreenshotAction.collectModelPlazaRequests(
                action, context, listOf("gpt-4", "gpt-4-turbo")
            )
            assertTrue(results.all { it.screenshot == null })
            assertEquals("81.46 t/s", results[0].throughputText)
            assertEquals("19.5s", results[0].latencyText)
            assertEquals("50.0 tps", results[1].throughputText)
        }
    }

    @Test
    fun `disabling the percentage should skip even an invalid percentage selector`() = runBlocking {
        withFixture { action, context ->
            val current = XAiWebHookRequestImageApi.read()
            assertTrue(XAiWebHookRequestImageApi.write(current.copy(
                percentage = current.percentage.copy(enabled = false)
            )).success)
            val result = WebPageScreenshotAction.collectModelPlazaRequests(
                action.copy(params = action.params + ("percentage_selector" to "[")), context, listOf("gpt-4")
            ).single()
            val original = captureFixtureImage(action, context, "[data-model='gpt-4'] .sparkline")
            assertDimensionsWithMargins(decode(assertNotNull(result.screenshot)), original.width + 6, original.height + 6)
            assertEquals("81.46 t/s", result.throughputText)
        }
    }

    @Test
    fun `updates while navigating should affect only the next batch of model images`() = runBlocking {
        withFixture(onPageRequest = {
            runBlocking {
                val updated = XAiWebHookRequestImageApi.update("margin.left_percent", "20")
                check(updated.success) { updated.error.orEmpty() }
            }
        }) { action, context ->
            val models = listOf("gpt-4", "gpt-4-turbo")
            val first = WebPageScreenshotAction.collectModelPlazaRequests(action, context, models)
            assertEquals(20.0, XAiWebHookRequestImageApi.read().margin.leftPercent)
            val next = WebPageScreenshotAction.collectModelPlazaRequests(action, context, models)
            for (index in models.indices) {
                val card = "[data-model='${models[index]}']"
                val original = captureFixtureImage(action, context, "$card .sparkline")
                val percentage = captureFixtureImage(action, context, "$card .percentage")
                val contentWidth = original.width + percentage.width + 12
                val contentHeight = maxOf(original.height + 6, percentage.height)
                val firstImage = decode(assertNotNull(first[index].screenshot))
                val nextImage = decode(assertNotNull(next[index].screenshot))
                assertDimensionsWithMargins(firstImage, contentWidth, contentHeight)
                assertEquals(contentWidth + (contentWidth + 4) / 5 + (contentWidth + 19) / 20, nextImage.width)
                assertEquals(firstImage.height, nextImage.height)
                assertTrue(nextImage.width > firstImage.width)
            }
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
        html: String = fixtureHtml(),
        onPageRequest: (() -> Unit)? = null,
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
                onPageRequest?.invoke()
                val responseHtml = if (redirect == Redirect.CLIENT) {
                    "<!doctype html><html><body><script>history.replaceState(null, '', '/login?redirect=/model-plaza')</script></body></html>"
                } else {
                    html
                }
                val bytes = responseHtml.toByteArray(Charsets.UTF_8)
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
            RequestImageConfigRuntime.start(imageConfigDirectory.toFile(), {}, {})
            test(action, context)
        } finally {
            RequestImageConfigRuntime.resetForTest()
            WebPageScreenshotAction.reset()
            server.stop(0)
        }
    }


    private suspend fun captureFixtureImage(
        action: ActionConfig,
        context: ExecutionContext,
        selector: String
    ): BufferedImage {
        val screenshot = action.params["screenshot"] as Map<*, *>
        val part = (screenshot["parts"] as List<*>).single() as Map<*, *>
        val elementAction = action.copy(params = action.params + (
            "screenshot" to mapOf("parts" to listOf(part + ("selector" to selector)))
        ))
        return decode(WebPageScreenshotAction.capture(elementAction, context))
    }

    private fun fixtureHtml(): String = """
        <!doctype html><html><head><style>
          body { font:16px/20px sans-serif; margin:8px; }
          #search { display:block; height:24px; box-sizing:border-box; }
          h3 { font:16px/20px sans-serif; height:20px; margin:0; }
          .performance { height:20px; line-height:20px; }
          .sparkline { position:relative; display:inline-block; width:100px; height:20px; vertical-align:top; }
          .percentage { position:absolute; right:0; top:2px; display:inline-block; width:30px; height:16px;
            color:white; background:transparent; font:10px/16px monospace; text-align:right; }
          .percentage[hidden] { display:none !important; }
        </style></head><body>
          <div id="app"><div></div><div><div></div><div><main><div>
            <div><div><div><input id="search" type="search"></div></div></div>
            <div></div>
            <div>
              <div class="card" data-model="gpt-4-turbo">
                <div><h3>gpt-4-turbo</h3></div>
                <div><div></div><div><div class="performance">
                  <span class="metrics" data-throughput="50.0 tps" data-latency="1250ms">—</span><span></span>
                  <span class="sparkline" role="img" aria-label="近 5 个时段成功率，最低 80.0%" style="background:blue">
                    <span class="percentage">88.8%</span>
                  </span>
                </div></div></div>
              </div>
              <div class="card" data-model="gpt-4">
                <div><h3>gpt-4</h3></div>
                <div><div></div><div><div class="performance">
                  <span class="metrics" data-throughput="81.46 t/s" data-latency="19.5s">—</span><span></span>
                  <span class="sparkline" role="img" aria-label="近 5 个时段成功率，最低 0.0%" style="background:green">
                    <span class="percentage" style="width:36px">99.7%</span>
                  </span>
                </div></div></div>
                <div class="hidden-sparkline" style="display:none;width:100px;height:20px"></div>
              </div>
              <div class="card" data-model="gpt-no-metrics"><div><h3>gpt-no-metrics</h3><p>暂无数据</p></div></div>
              <div class="card" data-model="claude-opus-5">
                <div><h3>Claude Opus 5</h3><p>claude-opus-5</p></div>
                <div><div></div><div><div class="performance">
                  <span class="metrics" data-throughput="12.5 t/s" data-latency="850ms">—</span><span></span>
                  <span class="sparkline" role="img" aria-label="近 3 个时段成功率，最低 50.0%" style="background:red"></span>
                </div></div></div>
              </div>
              <div class="card" data-model="gpt-sibling">
                <div><h3>gpt-sibling</h3></div>
                <div><div></div><div><div class="performance">
                  <span class="metrics">10.0 t/s 2s</span><span></span>
                  <span style="display:inline-flex;gap:6px;align-items:center">
                    <i class="sparkline" role="img" aria-label="近 5 个时段成功率，最低 0.0%" style="background:orange"></i>
                    <span class="percentage" style="position:static;color:green;background:white">99.7%</span>
                  </span>
                </div></div></div>
              </div>
            </div>
          </div></main></div></div></div>
          <script>
            document.querySelector('#search').addEventListener('input', (event) => {
              setTimeout(() => {
                // 异步更新指标和搜索地址，不替换图表或百分比 DOM。
                history.replaceState(null, '', '/model-plaza?q=' + encodeURIComponent(event.target.value));
                document.querySelectorAll('.metrics[data-throughput]').forEach((row) => {
                  row.textContent = row.dataset.throughput + ' ' + row.dataset.latency;
                });
              }, 200);
            });
          </script>
        </body></html>
    """.trimIndent()
}
