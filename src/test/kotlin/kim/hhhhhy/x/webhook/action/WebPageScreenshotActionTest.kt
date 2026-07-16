package kim.hhhhhy.x.webhook.action

import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.TimeoutError
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.BrowserConfig
import kim.hhhhhy.x.webhook.config.PluginConfig
import kim.hhhhhy.x.webhook.model.EventContext
import kim.hhhhhy.x.webhook.model.ExecutionContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kim.hhhhhy.x.webhook.model.RequestContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.Yaml
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class WebPageScreenshotActionTest {
    private val config = PluginConfig.safeDefault()

    @Test
    fun defaultYamlContainsReusableScreenshotAction(): Unit {
        val root = WebPageScreenshotActionTest::class.java.getResourceAsStream("/webhook_config.yml").use { input ->
            requireNotNull(input)
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Map<String, Any?>>(input)
        }
        val browser = root["browser"] as Map<*, *>
        val actions = root["actions"] as Map<*, *>
        val screenshotAction = actions["geek2api-monitor-screenshot"] as Map<*, *>
        val baseUrls = screenshotAction["base_urls"] as List<*>
        val auth = screenshotAction["auth"] as Map<*, *>
        val cliBridge = auth["cli_bridge"] as Map<*, *>
        val localStorage = auth["local_storage"] as Map<*, *>
        val screenshot = screenshotAction["screenshot"] as Map<*, *>
        val parts = screenshot["parts"] as List<*>
        val keysPart = parts[0] as Map<*, *>
        val monitorPart = parts[1] as Map<*, *>
        val monitorSteps = monitorPart["steps"] as List<*>
        val readyStep = monitorSteps.last() as Map<*, *>
        val layout = screenshot["layout"] as Map<*, *>
        val screenshotRetry = screenshot["retry"] as Map<*, *>

        assertTrue(browser["enabled"] is Boolean)
        assertEquals(false, browser["session_cache_enabled"])
        assertEquals("browser-session-cache", browser["session_cache_dir"])
        assertTrue("hk5.geek2api.com" in (browser["allowed_hosts"] as List<*>))
        assertFalse("hk3.geek2api.com" in (browser["allowed_hosts"] as List<*>))
        assertEquals(
            listOf(
                "https://hk5.geek2api.com",
                "https://hk.geek2api.com",
                "https://hk2.geek2api.com",
                "https://hk4.geek2api.com"
            ),
            baseUrls
        )
        assertEquals("https://hk5.geek2api.com/api/v1/auth/cli-bridge/start", cliBridge["start_url"])
        assertEquals("https://hk5.geek2api.com/cli-bridge", cliBridge["browser_url"])
        assertEquals("https://hk5.geek2api.com/api/v1/auth/cli-bridge/poll", cliBridge["poll_url"])
        assertEquals("https://hk5.geek2api.com/api/v1/user/profile", cliBridge["profile_url"])
        assertEquals("https://hk5.geek2api.com/api/v1/auth/refresh", cliBridge["refresh_url"])
        assertEquals(2500, cliBridge["poll_interval_ms"])
        assertEquals(300000, cliBridge["max_wait_ms"])
        assertEquals(null, auth["login"])
        assertEquals("auth_token", localStorage["key"])
        assertEquals("refresh_token", localStorage["refresh_token_key"])
        assertEquals("token_expires_at", localStorage["expires_at_key"])
        assertEquals("auth_user", localStorage["user_key"])
        assertEquals(null, screenshotAction["steps"])
        assertEquals(2, parts.size)
        assertEquals("keys-overview", keysPart["id"])
        assertEquals("https://hk5.geek2api.com/keys", keysPart["url"])
        assertEquals(
            "//*[@id=\"app\"]/div[2]/div[2]/main/div/div[1]/div/section/div[2]",
            keysPart["selector"]
        )
        assertEquals("monitor-status", monitorPart["id"])
        assertEquals("https://hk5.geek2api.com/monitor", monitorPart["url"])
        assertEquals("wait", readyStep["op"])
        assertEquals("visible", readyStep["state"])
        assertEquals(90000, readyStep["timeout_ms"])
        assertTrue(readyStep["selector"].toString().contains("min-h-[280px]"))
        assertTrue(readyStep["selector"].toString().contains("empty-state"))
        assertEquals(3000, screenshot["delay_before_ms"])
        assertEquals(listOf("header.sticky"), screenshot["hide_selectors"])
        assertEquals(60000, screenshot["font_wait_timeout_ms"])
        assertEquals("center", layout["horizontal_align"])
        assertEquals(0, layout["gap_px"])
        assertEquals(true, screenshotRetry["enabled"])
        assertEquals(2, screenshotRetry["max_retries"])
        assertEquals(1000, screenshotRetry["delay_ms"])
    }

    @Test
    fun completeGeek2ApiExampleIsValidAndReusable(): Unit {
        val example = File("examples/webhook_config.geek2api-cli-bridge.yml")
        assertTrue(example.isFile)
        val root = example.inputStream().use { input ->
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Map<String, Any?>>(input)
        }
        val browser = root["browser"] as Map<*, *>
        val incoming = root["incoming"] as Map<*, *>
        val outgoing = root["outgoing"] as Map<*, *>
        val actions = root["actions"] as Map<*, *>
        val action = actions["geek2api-monitor-screenshot"] as Map<*, *>
        val baseUrls = action["base_urls"] as List<*>
        val auth = action["auth"] as Map<*, *>
        val cliBridge = auth["cli_bridge"] as Map<*, *>
        val localStorage = auth["local_storage"] as Map<*, *>
        val screenshot = action["screenshot"] as Map<*, *>
        val parts = screenshot["parts"] as List<*>
        val keysPart = parts[0] as Map<*, *>
        val monitorPart = parts[1] as Map<*, *>
        val monitorSteps = monitorPart["steps"] as List<*>
        val readyStep = monitorSteps.last() as Map<*, *>
        val layout = screenshot["layout"] as Map<*, *>
        val screenshotRetry = screenshot["retry"] as Map<*, *>

        assertEquals(true, browser["enabled"])
        assertEquals(true, browser["session_cache_enabled"])
        assertEquals("browser-session-cache", browser["session_cache_dir"])
        assertTrue("hk5.geek2api.com" in (browser["allowed_hosts"] as List<*>))
        assertFalse("hk3.geek2api.com" in (browser["allowed_hosts"] as List<*>))
        assertEquals(
            listOf(
                "https://hk5.geek2api.com",
                "https://hk.geek2api.com",
                "https://hk2.geek2api.com",
                "https://hk4.geek2api.com"
            ),
            baseUrls
        )
        assertEquals("https://hk5.geek2api.com/api/v1/auth/cli-bridge/start", cliBridge["start_url"])
        assertEquals("https://hk5.geek2api.com/cli-bridge", cliBridge["browser_url"])
        assertEquals("https://hk5.geek2api.com/api/v1/auth/cli-bridge/poll", cliBridge["poll_url"])
        assertEquals("https://hk5.geek2api.com/api/v1/user/profile", cliBridge["profile_url"])
        assertEquals(120, cliBridge["refresh_before_expiry_seconds"])
        assertEquals(60000, cliBridge["retry_cooldown_ms"])
        assertEquals(null, auth["login"])
        assertEquals("auth_token", localStorage["key"])
        assertEquals("auth_user", localStorage["user_key"])
        assertEquals(null, action["steps"])
        assertEquals(1, (incoming["endpoints"] as List<*>).size)
        assertEquals(2, (outgoing["routes"] as List<*>).size)
        assertEquals(2, parts.size)
        assertEquals("keys-overview", keysPart["id"])
        assertEquals("https://hk5.geek2api.com/keys", keysPart["url"])
        assertEquals("monitor-status", monitorPart["id"])
        assertEquals("https://hk5.geek2api.com/monitor", monitorPart["url"])
        assertTrue(monitorSteps.none { (it as Map<*, *>)["op"] == "fill" })
        assertEquals("wait", readyStep["op"])
        assertEquals(90000, readyStep["timeout_ms"])
        assertTrue(readyStep["selector"].toString().contains("min-h-[280px]"))
        assertTrue(readyStep["selector"].toString().contains("empty-state"))
        assertEquals(3000, screenshot["delay_before_ms"])
        assertEquals(listOf("header.sticky"), screenshot["hide_selectors"])
        assertEquals(3000, screenshot["font_wait_timeout_ms"])
        assertEquals("center", layout["horizontal_align"])
        assertEquals(0, layout["gap_px"])
        assertEquals(true, screenshotRetry["enabled"])
        assertEquals(2, screenshotRetry["max_retries"])
        assertEquals(1000, screenshotRetry["delay_ms"])
    }

    @Test
    fun cliBridgeDesktopIntegrationDocUsesHk5BaseUrl(): Unit {
        val document = File("docs/CLI_BRIDGE_DESKTOP_INTEGRATION.md").readText()

        assertTrue(document.contains("https://hk5.geek2api.com"))
        assertFalse(document.contains("https://hk3.geek2api.com"))
        assertFalse(document.contains("https://www.geek2api.com"))
    }

    @Test
    fun browserSessionCacheRestoresAuthAndPreservesCookies(): Unit {
        val directory = Files.createTempDirectory("xaiwebhook-session-cache")
        val localStorage = BrowserLocalStorageAuth(
            origin = "https://example.invalid",
            key = "auth_token",
            prefix = ""
        )
        val authSpec = BrowserAuthSpec(
            token = null,
            tokenEnv = null,
            headerName = null,
            headerPrefix = "Bearer ",
            headerHosts = emptyList(),
            localStorage = localStorage,
            cookie = null,
            cliBridge = BrowserCliBridgeAuth(
                startUrl = "https://example.invalid/api/v1/auth/cli-bridge/start",
                browserUrl = "https://example.invalid/cli-bridge",
                pollUrl = "https://example.invalid/api/v1/auth/cli-bridge/poll",
                profileUrl = "https://example.invalid/api/v1/user/profile",
                refreshUrl = "https://example.invalid/api/v1/auth/refresh",
                pollIntervalMillis = 2_500L,
                maxWaitMillis = 300_000L,
                refreshBeforeExpirySeconds = 120L,
                retryCooldownMillis = 60_000L
            )
        )
        val unresolved = ResolvedBrowserAuth(spec = authSpec)
        val authenticated = WebPageScreenshotAction.withTokenPair(
            unresolved,
            BrowserTokenPair(
                accessToken = "cached-access",
                refreshToken = "cached-refresh",
                expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
                tokenType = "Bearer",
                user = Json.parseToJsonElement("""{"id":1,"username":"cached-user"}""") as JsonObject
            )
        )
        val storageState = WebPageScreenshotAction.mergeStorageState(
            """{"cookies":[{"name":"session","value":"session-cookie","domain":"example.invalid","path":"/","expires":4102444800,"httpOnly":true,"secure":true,"sameSite":"Lax"}],"origins":[]}""",
            authenticated
        )
        val cache = BrowserSessionCache(directory)
        val sessionKey = "monitor/../../must-not-be-a-path"

        try {
            cache.save(sessionKey, authenticated, storageState)
            val cachePath = cache.cachePath(sessionKey)
            assertTrue(Files.isRegularFile(cachePath))
            assertFalse(cachePath.fileName.toString().contains("monitor"))

            val cached = requireNotNull(cache.load(sessionKey, unresolved))
            assertTrue(cached.storageState.contains("session-cookie"))
            val restored = requireNotNull(WebPageScreenshotAction.restoreCachedSessionAuth(unresolved, cached))
            assertEquals("cached-access", restored.tokenPair?.accessToken)
            assertEquals("cached-refresh", restored.tokenPair?.refreshToken)
            assertEquals("cached-user", restored.tokenPair?.user?.get("username")?.jsonPrimitive?.content)

            val refreshed = WebPageScreenshotAction.withTokenPair(
                restored,
                requireNotNull(restored.tokenPair).copy(accessToken = "refreshed-access")
            )
            val merged = WebPageScreenshotAction.mergeStorageState(cached.storageState, refreshed)
            val values = WebPageScreenshotAction.readStorageStateLocalStorage(merged, localStorage.origin)
            assertEquals("refreshed-access", values[localStorage.key])
            assertTrue(merged.contains("session-cookie"))

            val cookieOnlySessionKey = "cookie-only-session"
            cache.save(cookieOnlySessionKey, null, storageState)
            val cookieOnly = requireNotNull(cache.load(cookieOnlySessionKey, null))
            assertTrue(cookieOnly.storageState.contains("session-cookie"))

            val changedAuth = unresolved.copy(
                spec = authSpec.copy(localStorage = localStorage.copy(key = "different_auth_token"))
            )
            assertEquals(null, cache.load(sessionKey, changedAuth))
            assertFalse(Files.exists(cachePath))

            val relativeDirectory = resolveBrowserSessionCacheDirectory("nested/cache", directory)
            assertTrue(relativeDirectory.startsWith(directory.toAbsolutePath().normalize()))
            assertFailsWith<IllegalArgumentException> {
                resolveBrowserSessionCacheDirectory("../outside", directory)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun normalizeSelectorPrefixesXPath(): Unit {
        assertEquals(
            "xpath=//*[@id=\"app\"]/main",
            WebPageScreenshotAction.normalizeSelector("//*[@id=\"app\"]/main")
        )
        assertEquals("#content", WebPageScreenshotAction.normalizeSelector("#content"))
    }

    @Test
    fun screenshotHideSelectorsBuildSafeTemporaryStyle(): Unit {
        assertEquals(
            "header.sticky { visibility: hidden !important; }\n.chat-widget { visibility: hidden !important; }",
            WebPageScreenshotAction.buildScreenshotStyle(listOf("header.sticky", ".chat-widget"))
        )
        assertEquals(null, WebPageScreenshotAction.buildScreenshotStyle(emptyList()))
        assertEquals(
            "header.sticky",
            WebPageScreenshotAction.normalizeScreenshotHideSelector("  header.sticky  ")
        )
        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.normalizeScreenshotHideSelector("header { display: none; }")
        }
        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.normalizeScreenshotHideSelector("header\nbody")
        }
    }

    @Test
    fun screenshotPartsHonorConfiguredOrderAlignmentAndGap(): Unit {
        fun solidPng(width: Int, height: Int, color: Color): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.color = color
                graphics.fillRect(0, 0, width, height)
            } finally {
                graphics.dispose()
            }
            return ByteArrayOutputStream().use { output ->
                assertTrue(ImageIO.write(image, "png", output))
                output.toByteArray()
            }
        }

        val combined = WebPageScreenshotAction.combineScreenshotsVertically(
            parts = listOf(
                solidPng(6, 2, Color.RED),
                solidPng(4, 3, Color.GREEN)
            ),
            layout = BrowserScreenshotLayout(horizontalAlign = "right", gapPixels = 2)
        )
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(combined)))

        assertEquals(6, image.width)
        assertEquals(7, image.height)
        assertEquals(0xFF0000, image.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(0, image.getRGB(0, 2) ushr 24)
        assertEquals(0, image.getRGB(1, 4) ushr 24)
        assertEquals(0x00FF00, image.getRGB(2, 4) and 0xFFFFFF)
    }

    @Test
    fun validateNavigationUrlUsesExplicitHostAllowlist(): Unit {
        val exact = WebPageScreenshotAction.validateNavigationUrl(
            "https://hk5.geek2api.com/monitor",
            listOf("hk5.geek2api.com")
        )
        assertEquals("hk5.geek2api.com", exact.host)

        val wildcard = WebPageScreenshotAction.validateNavigationUrl(
            "https://api.example.invalid/status",
            listOf("*.example.invalid")
        )
        assertEquals("api.example.invalid", wildcard.host)

        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.validateNavigationUrl(
                "https://example.invalid/monitor",
                listOf("*.example.invalid")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.validateNavigationUrl(
                "file:///C:/secret.txt",
                listOf("example.invalid")
            )
        }
        assertTrue(
            WebPageScreenshotAction.shouldInjectHeader(
                "https://hk5.geek2api.com/api/monitor",
                listOf("hk5.geek2api.com")
            )
        )
        assertFalse(
            WebPageScreenshotAction.shouldInjectHeader(
                "https://cdn.example.invalid/script.js",
                listOf("hk5.geek2api.com")
            )
        )
    }

    @Test
    fun rewriteUrlForBaseUrlPreservesPathAndLeavesExternalHostsUntouched(): Unit {
        val baseUrls = listOf(
            "https://hk5.geek2api.com",
            "https://hk.geek2api.com",
            "https://hk2.geek2api.com",
            "https://hk4.geek2api.com"
        )

        assertEquals(
            "https://hk2.geek2api.com/api/v1/user/profile?verbose=1#top",
            WebPageScreenshotAction.rewriteUrlForBaseUrl(
                "https://hk5.geek2api.com/api/v1/user/profile?verbose=1#top",
                baseUrls,
                "https://hk2.geek2api.com"
            )
        )
        assertEquals(
            "https://cdn.example.invalid/script.js",
            WebPageScreenshotAction.rewriteUrlForBaseUrl(
                "https://cdn.example.invalid/script.js",
                baseUrls,
                "https://hk2.geek2api.com"
            )
        )
    }

    @Test
    fun baseUrlCursorKeepsCurrentUrlUntilFailure(): Unit {
        val cursor = BrowserBaseUrlCursor()
        val baseUrls = listOf(
            "https://hk5.geek2api.com",
            "https://hk.geek2api.com",
            "https://hk2.geek2api.com"
        )

        assertEquals(baseUrls, cursor.orderedCandidates("monitor", baseUrls).filterNotNull())
        assertEquals(baseUrls, cursor.orderedCandidates("monitor", baseUrls).filterNotNull())

        cursor.rememberFailure("monitor", baseUrls, "https://hk5.geek2api.com")
        assertEquals(
            listOf("https://hk.geek2api.com", "https://hk2.geek2api.com", "https://hk5.geek2api.com"),
            cursor.orderedCandidates("monitor", baseUrls).filterNotNull()
        )
        assertEquals(
            listOf("https://hk.geek2api.com", "https://hk2.geek2api.com", "https://hk5.geek2api.com"),
            cursor.orderedCandidates("monitor", baseUrls).filterNotNull()
        )

        cursor.rememberFailure("monitor", baseUrls, "https://hk.geek2api.com")
        assertEquals(
            listOf("https://hk2.geek2api.com", "https://hk5.geek2api.com", "https://hk.geek2api.com"),
            cursor.orderedCandidates("monitor", baseUrls).filterNotNull()
        )
    }

    @Test
    fun parseSpecRendersOrderedScreenshotPartsAndOverrides(): Unit {
        val action = ActionConfig(
            id = "monitor",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "screenshot" to mapOf(
                    "timeout_ms" to 12_000,
                    "delay_before_ms" to 750,
                    "font_wait_timeout_ms" to 1_500,
                    "hide_selectors" to listOf(" header.sticky ", ".chat-widget"),
                    "layout" to mapOf(
                        "horizontal_align" to "right",
                        "gap_px" to 12
                    ),
                    "parts" to listOf(
                        mapOf(
                            "id" to "keys",
                            "url" to "https://example.invalid/keys",
                            "selector" to "//*[@id=\"app\"]/summary",
                            "timeout_ms" to 8_000,
                            "delay_before_ms" to 250,
                            "hide_selectors" to listOf(".keys-only"),
                            "steps" to listOf(
                                mapOf("op" to "wait", "selector" to "#ready", "state" to "visible")
                            )
                        ),
                        mapOf(
                            "id" to "disabled",
                            "enabled" to false
                        ),
                        mapOf(
                            "id" to "monitor",
                            "url" to "https://example.invalid/monitor",
                            "wait_until" to "load",
                            "selector" to "//*[@id=\"app\"]/main",
                            "steps" to listOf(
                                mapOf(
                                    "op" to "fill",
                                    "selector" to "#email",
                                    "value_env" to "TEST_EMAIL",
                                    "optional" to true
                                )
                            )
                        )
                    ),
                    "retry" to mapOf(
                        "enabled" to true,
                        "max_retries" to 3,
                        "delay_ms" to 250
                    )
                )
            )
        )

        val spec = WebPageScreenshotAction.parseSpec(action, ExecutionContext(config))
        val keys = spec.screenshotParts[0]
        val monitor = spec.screenshotParts[1]

        assertEquals("monitor", spec.sessionKey)
        assertEquals(listOf("keys", "monitor"), spec.screenshotParts.map { part -> part.id })
        assertEquals(1, keys.index)
        assertEquals("https://example.invalid/keys", keys.steps[0].url)
        assertEquals("wait", keys.steps[1].op)
        assertEquals("xpath=//*[@id=\"app\"]/summary", keys.selector)
        assertEquals(8_000L, keys.timeoutMillis)
        assertEquals(250L, keys.delayBeforeMillis)
        assertEquals(1_500L, keys.fontWaitTimeoutMillis)
        assertEquals(listOf(".keys-only"), keys.hideSelectors)
        assertEquals(3, monitor.index)
        assertEquals("https://example.invalid/monitor", monitor.steps[0].url)
        assertEquals("load", monitor.steps[0].waitUntil)
        assertTrue(monitor.steps[1].optional)
        assertEquals("TEST_EMAIL", monitor.steps[1].valueEnv)
        assertEquals("xpath=//*[@id=\"app\"]/main", monitor.selector)
        assertEquals(12_000L, monitor.timeoutMillis)
        assertEquals(750L, monitor.delayBeforeMillis)
        assertEquals(1_500L, monitor.fontWaitTimeoutMillis)
        assertEquals(listOf("header.sticky", ".chat-widget"), monitor.hideSelectors)
        assertEquals("right", spec.screenshotLayout.horizontalAlign)
        assertEquals(12, spec.screenshotLayout.gapPixels)
        assertTrue(spec.retry.enabled)
        assertEquals(3, spec.retry.maxRetries)
        assertEquals(250L, spec.retry.delayMillis)
    }

    @Test
    fun legacySingleScreenshotConfigRemainsSupported(): Unit {
        val action = ActionConfig(
            id = "legacy",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "steps" to listOf(mapOf("op" to "goto", "url" to "https://example.invalid/monitor")),
                "screenshot" to mapOf(
                    "selector" to "#content",
                    "timeout_ms" to 5_000,
                    "delay_before_ms" to 125
                )
            )
        )

        val part = WebPageScreenshotAction.parseSpec(action, ExecutionContext(config)).screenshotParts.single()

        assertEquals("main", part.id)
        assertEquals("https://example.invalid/monitor", part.steps.single().url)
        assertEquals("#content", part.selector)
        assertEquals(5_000L, part.timeoutMillis)
        assertEquals(125L, part.delayBeforeMillis)
    }

    @Test
    fun screenshotPartsRejectAmbiguousAndInvalidConfigurations(): Unit {
        val validParts = listOf(
            mapOf(
                "id" to "one",
                "url" to "https://example.invalid/one",
                "selector" to "#one"
            )
        )
        val listAction = ActionConfig(
            id = "list-validation",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf("screenshot" to mapOf("parts" to validParts))
        )

        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.parseSpec(
                listAction.copy(
                    params = listAction.params + (
                        "steps" to listOf(mapOf("op" to "goto", "url" to "https://example.invalid/legacy"))
                    )
                ),
                ExecutionContext(config)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.parseSpec(
                listAction.copy(
                    params = mapOf(
                        "screenshot" to mapOf(
                            "parts" to listOf(mapOf("id" to "missing-navigation", "selector" to "#target"))
                        )
                    )
                ),
                ExecutionContext(config)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.parseSpec(
                listAction.copy(
                    params = mapOf(
                        "steps" to listOf(mapOf("op" to "goto", "url" to "https://example.invalid/monitor")),
                        "screenshot" to mapOf(
                            "selector" to "#target",
                            "prepend_selector" to "#deprecated"
                        )
                    )
                ),
                ExecutionContext(config)
            )
        }
    }

    @Test
    fun screenshotDelayBeforeDefaultsToZeroAndIsBounded(): Unit {
        fun parse(delayBeforeMillis: Long?): Long {
            val screenshot = mutableMapOf<String, Any?>("selector" to "#content")
            if (delayBeforeMillis != null) {
                screenshot["delay_before_ms"] = delayBeforeMillis
            }
            val action = ActionConfig(
                id = "delay-before",
                type = "send_webpage_screenshot",
                enabled = true,
                params = mapOf(
                    "steps" to listOf(mapOf("op" to "goto", "url" to "https://example.invalid/monitor")),
                    "screenshot" to screenshot
                )
            )
            return WebPageScreenshotAction.parseSpec(action, ExecutionContext(config))
                .screenshotParts.single().delayBeforeMillis
        }

        assertEquals(0L, parse(null))
        assertEquals(0L, parse(-1L))
        assertEquals(300_000L, parse(999_999L))
    }

    @Test
    fun screenshotFontWaitIsBoundedAndPlaywrightInternalWaitIsDisabled(): Unit {
        fun parse(fontWaitTimeoutMillis: Long?): WebPageScreenshotSpec {
            val screenshot = mutableMapOf<String, Any?>(
                "selector" to "#content",
                "timeout_ms" to 5_000
            )
            if (fontWaitTimeoutMillis != null) {
                screenshot["font_wait_timeout_ms"] = fontWaitTimeoutMillis
            }
            val action = ActionConfig(
                id = "font-wait",
                type = "send_webpage_screenshot",
                enabled = true,
                params = mapOf(
                    "steps" to listOf(mapOf("op" to "goto", "url" to "https://example.invalid/monitor")),
                    "screenshot" to screenshot
                )
            )
            return WebPageScreenshotAction.parseSpec(action, ExecutionContext(config))
        }

        val defaultSpec = parse(null)
        assertEquals(3_000L, defaultSpec.screenshotParts.single().fontWaitTimeoutMillis)
        assertFalse(defaultSpec.retry.enabled)
        assertEquals(5_000L, parse(8_000L).screenshotParts.single().fontWaitTimeoutMillis)
        assertEquals(0L, parse(-1L).screenshotParts.single().fontWaitTimeoutMillis)
        assertEquals(
            "1",
            WebPageScreenshotAction.playwrightDriverEnvironment()["PW_TEST_SCREENSHOT_NO_FONTS_READY"]
        )
    }

    @Test
    fun screenshotRetryConfigClampsValuesAndRejectsDeterministicFailures(): Unit {
        val action = ActionConfig(
            id = "retry-config",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "steps" to listOf(mapOf("op" to "goto", "url" to "https://example.invalid/monitor")),
                "screenshot" to mapOf(
                    "selector" to "#content",
                    "retry" to mapOf(
                        "max_retries" to 99,
                        "delay_ms" to 999_999
                    )
                )
            )
        )
        val retry = WebPageScreenshotAction.parseSpec(action, ExecutionContext(config)).retry

        assertTrue(retry.enabled)
        assertEquals(10, retry.maxRetries)
        assertEquals(60_000L, retry.delayMillis)
        assertTrue(
            WebPageScreenshotAction.isRetryableCaptureError(
                IllegalStateException(
                    "browser screenshot (selector=#content) failed",
                    TimeoutError("Timeout 1000ms exceeded")
                )
            )
        )
        assertTrue(
            WebPageScreenshotAction.isRetryableCaptureError(
                IllegalStateException(
                    "browser step 1 (goto) failed",
                    PlaywrightException("page.goto: net::ERR_CONNECTION_RESET")
                )
            )
        )
        assertTrue(
            WebPageScreenshotAction.isRetryableCaptureError(
                IllegalStateException(
                    "browser screenshot (selector=#content) failed",
                    CancellationException("target operation was cancelled")
                )
            )
        )
        assertFalse(WebPageScreenshotAction.isRetryableCaptureError(CancellationException("caller cancelled")))
        assertFalse(
            WebPageScreenshotAction.isRetryableCaptureError(
                IllegalStateException(
                    "browser screenshot (selector=???) failed",
                    PlaywrightException("Unexpected token while parsing selector")
                )
            )
        )
        assertFalse(
            WebPageScreenshotAction.isRetryableCaptureError(
                IllegalStateException("CLI bridge login failed", TimeoutError("Timeout"))
            )
        )
        assertFalse(
            WebPageScreenshotAction.isRetryableCaptureError(
                IllegalStateException(
                    "browser screenshot (selector=#content) failed",
                    IllegalArgumentException("screenshot exceeds configured max size")
                )
            )
        )
    }

    @Test
    fun parseSpecValidatesGotoWaitUntil(): Unit {
        val action = ActionConfig(
            id = "navigation-wait",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "steps" to listOf(
                    mapOf(
                        "op" to "goto",
                        "url" to "https://example.invalid/monitor",
                        "wait_until" to "load"
                    )
                ),
                "screenshot" to mapOf("selector" to "#content")
            )
        )

        val spec = WebPageScreenshotAction.parseSpec(action, ExecutionContext(config))
        assertEquals("load", spec.screenshotParts.single().steps.single().waitUntil)

        val invalid = action.copy(
            params = action.params + (
                "steps" to listOf(
                    mapOf(
                        "op" to "goto",
                        "url" to "https://example.invalid/monitor",
                        "wait_until" to "forever"
                    )
                )
            )
        )
        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.parseSpec(invalid, ExecutionContext(config))
        }
    }

    @Test
    fun parseSpecSupportsScopedAccessTokenAuth(): Unit {
        val action = ActionConfig(
            id = "monitor-token",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "base_urls" to listOf("https://api.example.invalid", "https://api2.example.invalid"),
                "auth" to mapOf(
                    "token_env" to "MONITOR_ACCESS_TOKEN",
                    "header_hosts" to listOf("api.example.invalid"),
                    "local_storage" to mapOf(
                        "origin" to "https://api.example.invalid",
                        "key" to "access_token"
                    ),
                    "bootstrap" to mapOf(
                        "url" to "https://api.example.invalid/api/v1/auth/me",
                        "response_path" to "$.data.user",
                        "local_storage_key" to "auth_user",
                        "expected_status" to listOf(200, 204)
                    ),
                    "cookie" to mapOf(
                        "name" to "access_token",
                        "url" to "https://api.example.invalid/"
                    )
                ),
                "steps" to listOf(
                    mapOf("op" to "goto", "url" to "https://api.example.invalid/monitor")
                ),
                "screenshot" to mapOf("selector" to "#content")
            )
        )
        val browserConfig = BrowserConfig.safeDefault().copy(
            allowedHosts = listOf("api.example.invalid", "api2.example.invalid")
        )

        val spec = WebPageScreenshotAction.parseSpec(
            action,
            ExecutionContext(config.copy(browser = browserConfig))
        )

        val auth = requireNotNull(spec.auth)
        assertEquals(listOf("https://api.example.invalid", "https://api2.example.invalid"), spec.baseUrls)
        assertEquals(spec.baseUrls, auth.baseUrls)
        assertEquals("MONITOR_ACCESS_TOKEN", auth.tokenEnv)
        assertEquals("Authorization", auth.headerName)
        assertEquals("Bearer ", auth.headerPrefix)
        assertEquals(listOf("api.example.invalid"), auth.headerHosts)
        assertEquals("https://api.example.invalid", auth.localStorage?.origin)
        assertEquals("access_token", auth.localStorage?.key)
        assertEquals("https://api.example.invalid/api/v1/auth/me", auth.bootstrap?.url)
        assertEquals(listOf("data", "user"), auth.bootstrap?.responsePath)
        assertEquals("auth_user", auth.bootstrap?.localStorageKey)
        assertEquals(setOf(200, 204), auth.bootstrap?.expectedStatuses)
        assertEquals("access_token", auth.cookie?.name)
    }

    @Test
    fun parseSpecSupportsCredentialLoginAndRefresh(): Unit {
        val action = ActionConfig(
            id = "credential-monitor",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "auth" to mapOf(
                    "login" to mapOf(
                        "url" to "https://api.example.invalid/api/v1/auth/login",
                        "email_env" to "TEST_LOGIN_EMAIL",
                        "password_env" to "TEST_LOGIN_PASSWORD",
                        "two_factor_url" to "https://api.example.invalid/api/v1/auth/login/2fa",
                        "totp_secret_env" to "TEST_TOTP_SECRET",
                        "refresh_url" to "https://api.example.invalid/api/v1/auth/refresh",
                        "refresh_before_expiry_seconds" to 45,
                        "retry_cooldown_ms" to 75_000
                    ),
                    "local_storage" to mapOf(
                        "origin" to "https://api.example.invalid",
                        "key" to "auth_token",
                        "refresh_token_key" to "refresh_token",
                        "expires_at_key" to "token_expires_at",
                        "user_key" to "auth_user"
                    )
                ),
                "steps" to listOf(
                    mapOf("op" to "goto", "url" to "https://api.example.invalid/monitor")
                ),
                "screenshot" to mapOf("selector" to "#content")
            )
        )
        val browserConfig = BrowserConfig.safeDefault().copy(
            allowedHosts = listOf("api.example.invalid")
        )

        val spec = WebPageScreenshotAction.parseSpec(
            action,
            ExecutionContext(config.copy(browser = browserConfig))
        )
        val auth = requireNotNull(spec.auth)
        val login = requireNotNull(auth.login)

        assertEquals("credential-monitor", spec.sessionKey)
        assertEquals(null, auth.headerName)
        assertEquals("TEST_LOGIN_EMAIL", login.emailEnv)
        assertEquals("TEST_LOGIN_PASSWORD", login.passwordEnv)
        assertEquals("TEST_TOTP_SECRET", login.totpSecretEnv)
        assertEquals(45L, login.refreshBeforeExpirySeconds)
        assertEquals(75_000L, login.retryCooldownMillis)
        assertEquals("refresh_token", auth.localStorage?.refreshTokenKey)
        assertEquals("token_expires_at", auth.localStorage?.expiresAtKey)
        assertEquals("auth_user", auth.localStorage?.userKey)

        val resolved = WebPageScreenshotAction.resolveAuth(
            auth,
            mapOf(
                "TEST_LOGIN_EMAIL" to " tester@example.invalid ",
                "TEST_LOGIN_PASSWORD" to " password with spaces ",
                "TEST_TOTP_SECRET" to "JBSWY3DPEHPK3PXP"
            )
        )
        assertEquals("tester@example.invalid", resolved.credentials?.email)
        assertEquals(" password with spaces ", resolved.credentials?.password)
        assertEquals("JBSWY3DPEHPK3PXP", resolved.credentials?.totpSecret)
    }

    @Test
    fun parseSpecSupportsCliBridgeLoginAndRefresh(): Unit {
        val action = ActionConfig(
            id = "cli-bridge-monitor",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "auth" to mapOf(
                    "cli_bridge" to mapOf(
                        "start_url" to "https://api.example.invalid/api/v1/auth/cli-bridge/start",
                        "browser_url" to "https://api.example.invalid/cli-bridge",
                        "poll_url" to "https://api.example.invalid/api/v1/auth/cli-bridge/poll",
                        "profile_url" to "https://api.example.invalid/api/v1/user/profile",
                        "refresh_url" to "https://api.example.invalid/api/v1/auth/refresh",
                        "poll_interval_ms" to 2_500,
                        "max_wait_ms" to 300_000,
                        "refresh_before_expiry_seconds" to 120,
                        "retry_cooldown_ms" to 60_000
                    ),
                    "local_storage" to mapOf(
                        "origin" to "https://api.example.invalid",
                        "key" to "auth_token",
                        "refresh_token_key" to "refresh_token",
                        "expires_at_key" to "token_expires_at",
                        "user_key" to "auth_user"
                    )
                ),
                "steps" to listOf(
                    mapOf("op" to "goto", "url" to "https://api.example.invalid/monitor")
                ),
                "screenshot" to mapOf("selector" to "#content")
            )
        )
        val browserConfig = BrowserConfig.safeDefault().copy(
            allowedHosts = listOf("api.example.invalid")
        )

        val spec = WebPageScreenshotAction.parseSpec(
            action,
            ExecutionContext(config.copy(browser = browserConfig))
        )
        val auth = requireNotNull(spec.auth)
        val cliBridge = requireNotNull(auth.cliBridge)
        val resolved = WebPageScreenshotAction.resolveAuth(auth, emptyMap())

        assertEquals("cli-bridge-monitor", spec.sessionKey)
        assertEquals(null, auth.headerName)
        assertEquals("https://api.example.invalid/api/v1/auth/cli-bridge/start", cliBridge.startUrl)
        assertEquals("https://api.example.invalid/cli-bridge", cliBridge.browserUrl)
        assertEquals("https://api.example.invalid/api/v1/auth/cli-bridge/poll", cliBridge.pollUrl)
        assertEquals("https://api.example.invalid/api/v1/user/profile", cliBridge.profileUrl)
        assertEquals(2_500L, cliBridge.pollIntervalMillis)
        assertEquals(300_000L, cliBridge.maxWaitMillis)
        assertEquals(120L, cliBridge.refreshBeforeExpirySeconds)
        assertEquals(null, resolved.token)
        assertEquals(null, resolved.credentials)
    }

    @Test
    fun credentialLoginSupportsInlineFileValuesWithoutTrimmingPassword(): Unit {
        val action = ActionConfig(
            id = "inline-credentials",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "auth" to mapOf(
                    "login" to mapOf(
                        "url" to "https://api.example.invalid/api/v1/auth/login",
                        "email" to " admin@example.invalid ",
                        "password" to " password with spaces ",
                        "two_factor_url" to "https://api.example.invalid/api/v1/auth/login/2fa",
                        "refresh_url" to "https://api.example.invalid/api/v1/auth/refresh"
                    ),
                    "local_storage" to mapOf(
                        "origin" to "https://api.example.invalid",
                        "key" to "auth_token"
                    )
                ),
                "steps" to listOf(
                    mapOf("op" to "goto", "url" to "https://api.example.invalid/monitor")
                ),
                "screenshot" to mapOf("selector" to "#content")
            )
        )
        val browserConfig = BrowserConfig.safeDefault().copy(
            allowedHosts = listOf("api.example.invalid")
        )
        val spec = WebPageScreenshotAction.parseSpec(
            action,
            ExecutionContext(config.copy(browser = browserConfig))
        )
        val auth = requireNotNull(spec.auth)
        val resolved = WebPageScreenshotAction.resolveAuth(auth, emptyMap())

        assertEquals("admin@example.invalid", resolved.credentials?.email)
        assertEquals(" password with spaces ", resolved.credentials?.password)
        assertEquals(null, auth.login?.emailEnv)
        assertEquals(null, auth.login?.passwordEnv)
    }

    @Test
    fun credentialAuthRequiresSessionAndExclusiveAuthSource(): Unit {
        val commonLogin = mapOf(
            "url" to "https://api.example.invalid/api/v1/auth/login",
            "email_env" to "TEST_LOGIN_EMAIL",
            "password_env" to "TEST_LOGIN_PASSWORD",
            "two_factor_url" to "https://api.example.invalid/api/v1/auth/login/2fa",
            "refresh_url" to "https://api.example.invalid/api/v1/auth/refresh"
        )
        val browserConfig = BrowserConfig.safeDefault().copy(
            allowedHosts = listOf("api.example.invalid")
        )
        val context = ExecutionContext(config.copy(browser = browserConfig))
        val withoutSession = ActionConfig(
            id = null,
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "auth" to mapOf(
                    "login" to commonLogin,
                    "local_storage" to mapOf(
                        "origin" to "https://api.example.invalid",
                        "key" to "auth_token"
                    )
                ),
                "steps" to listOf(mapOf("op" to "goto", "url" to "https://api.example.invalid/monitor")),
                "screenshot" to mapOf("selector" to "#content")
            )
        )
        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.parseSpec(withoutSession, context)
        }

        val mixedAuth = withoutSession.copy(
            id = "mixed",
            params = withoutSession.params + (
                "auth" to mapOf(
                    "token" to "secret-token",
                    "login" to commonLogin,
                    "local_storage" to mapOf(
                        "origin" to "https://api.example.invalid",
                        "key" to "auth_token"
                    )
                )
            )
        )
        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.parseSpec(mixedAuth, context)
        }

        val mixedCredentialSources = withoutSession.copy(
            id = "mixed-credential-sources",
            params = withoutSession.params + (
                "auth" to mapOf(
                    "login" to (commonLogin + mapOf(
                        "email" to "admin@example.invalid",
                        "password" to "inline-password"
                    )),
                    "local_storage" to mapOf(
                        "origin" to "https://api.example.invalid",
                        "key" to "auth_token"
                    )
                )
            )
        )
        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.parseSpec(mixedCredentialSources, context)
        }
    }

    @Test
    fun accessTokenAuthRequiresConfiguredEnvironmentVariable(): Unit {
        val auth = BrowserAuthSpec(
            token = null,
            tokenEnv = "MISSING_ACCESS_TOKEN",
            headerName = "Authorization",
            headerPrefix = "Bearer ",
            headerHosts = listOf("example.invalid"),
            localStorage = null,
            cookie = null
        )

        assertFailsWith<IllegalStateException> {
            WebPageScreenshotAction.resolveAuth(auth, emptyMap())
        }
        val resolved = WebPageScreenshotAction.resolveAuth(
            auth,
            mapOf("MISSING_ACCESS_TOKEN" to "  Bearer secret-token  ")
        )
        assertEquals("secret-token", resolved.token)
    }

    @Test
    fun expiredJwtIsRejectedBeforeBrowserNavigation(): Unit {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":1}""".toByteArray())
        val auth = BrowserAuthSpec(
            token = "e30.$payload.signature",
            tokenEnv = null,
            headerName = "Authorization",
            headerPrefix = "Bearer ",
            headerHosts = listOf("example.invalid"),
            localStorage = null,
            cookie = null
        )

        val error = assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.resolveAuth(auth, emptyMap())
        }

        assertTrue(error.message.orEmpty().contains("browser auth token expired at"))
    }

    @Test
    fun storageStateContainsStructuredLocalStorageToken(): Unit {
        val auth = BrowserAuthSpec(
            token = "secret-token",
            tokenEnv = null,
            headerName = null,
            headerPrefix = "",
            headerHosts = emptyList(),
            localStorage = BrowserLocalStorageAuth(
                origin = "https://example.invalid",
                key = "access_token",
                prefix = "Token "
            ),
            cookie = null
        )

        val storageState = WebPageScreenshotAction.buildStorageState(
            WebPageScreenshotAction.resolveAuth(auth, emptyMap()).copy(
                additionalLocalStorage = mapOf(
                    "auth_user" to """{"id":1,"username":"tester"}"""
                )
            )
        )

        assertTrue(storageState.contains("https://example.invalid"))
        assertTrue(storageState.contains("access_token"))
        assertTrue(storageState.contains("Token secret-token"))
        assertTrue(storageState.contains("auth_user"))
        assertTrue(storageState.contains("\\\"username\\\":\\\"tester\\\""))
    }

    @Test
    fun bootstrapResponsePathExtractsOnlyConfiguredUserObject(): Unit {
        val value = WebPageScreenshotAction.extractBootstrapStorageValue(
            """{"code":0,"data":{"user":{"id":1,"username":"tester"}}}""",
            listOf("data", "user")
        )

        assertEquals("""{"id":1,"username":"tester"}""", value)
        val error = assertFailsWith<IllegalStateException> {
            WebPageScreenshotAction.extractBootstrapStorageValue(
                """{"code":0,"data":{}}""",
                listOf("data", "user")
            )
        }
        assertEquals("auth bootstrap response path not found: data.user", error.message)
    }

    @Test
    fun credentialApiSupportsDirectAndWrappedResponsesWithoutLeakingBodies(): Unit {
        val direct = WebPageCredentialAuthClient.parseApiResponseBody(
            status = 200,
            responseBody = """{"access_token":"access-1","refresh_token":"refresh-1"}""",
            operation = "credential login"
        )
        val wrapped = WebPageCredentialAuthClient.parseApiResponseBody(
            status = 200,
            responseBody = """{"code":0,"data":{"access_token":"access-2"},"message":"ok"}""",
            operation = "credential refresh"
        )

        assertEquals("access-1", direct["access_token"]?.jsonPrimitive?.content)
        assertEquals("access-2", wrapped["access_token"]?.jsonPrimitive?.content)
        val error = assertFailsWith<IllegalStateException> {
            WebPageCredentialAuthClient.parseApiResponseBody(
                status = 400,
                responseBody = """{"code":400,"reason":"CAP_VERIFICATION_FAILED","message":"cap verification failed","password":"must-not-leak"}""",
                operation = "credential login"
            )
        }
        assertEquals(
            "credential login failed: HTTP 400 (CAP_VERIFICATION_FAILED: cap verification failed)",
            error.message
        )
        assertFalse(error.message.orEmpty().contains("must-not-leak"))
    }

    @Test
    fun totpGenerationMatchesRfc6238Vector(): Unit {
        assertEquals(
            "287082",
            WebPageCredentialAuthClient.generateTotp(
                base32Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                epochSeconds = 59L
            )
        )
    }

    @Test
    fun credentialStorageStateContainsTokenPairAndUser(): Unit {
        val auth = BrowserAuthSpec(
            token = null,
            tokenEnv = null,
            headerName = null,
            headerPrefix = "Bearer ",
            headerHosts = emptyList(),
            localStorage = BrowserLocalStorageAuth(
                origin = "https://example.invalid",
                key = "auth_token",
                prefix = ""
            ),
            cookie = null,
            login = BrowserLoginAuth(
                url = "https://example.invalid/api/v1/auth/login",
                email = null,
                emailEnv = "EMAIL",
                password = null,
                passwordEnv = "PASSWORD",
                twoFactorUrl = "https://example.invalid/api/v1/auth/login/2fa",
                totpSecretEnv = null,
                totpCodeEnv = null,
                refreshUrl = "https://example.invalid/api/v1/auth/refresh",
                refreshBeforeExpirySeconds = 60L,
                retryCooldownMillis = 60_000L
            )
        )
        val resolved = ResolvedBrowserAuth(
            spec = auth,
            token = "access-1",
            credentials = ResolvedBrowserCredentials("user@example.invalid", "secret", null, null),
            tokenPair = BrowserTokenPair(
                accessToken = "access-1",
                refreshToken = "refresh-1",
                expiresAtMillis = 123_456L,
                tokenType = "Bearer",
                user = Json.parseToJsonElement("""{"id":1,"username":"tester"}""") as JsonObject
            ),
            additionalLocalStorage = mapOf(
                "refresh_token" to "refresh-1",
                "token_expires_at" to "123456",
                "auth_user" to """{"id":1,"username":"tester"}"""
            )
        )

        val storageState = WebPageScreenshotAction.buildStorageState(resolved)

        assertTrue(storageState.contains("auth_token"))
        assertTrue(storageState.contains("access-1"))
        assertTrue(storageState.contains("refresh_token"))
        assertTrue(storageState.contains("refresh-1"))
        assertTrue(storageState.contains("token_expires_at"))
        assertTrue(storageState.contains("auth_user"))
    }

    @Test
    fun parseSpecRejectsFillWithoutExactlyOneValueSource(): Unit {
        val action = ActionConfig(
            id = null,
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "steps" to listOf(
                    mapOf("op" to "fill", "selector" to "#password")
                ),
                "screenshot" to mapOf("selector" to "#content")
            )
        )

        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.parseSpec(action, ExecutionContext(config))
        }
    }

    @Test
    fun fillValueRequiresConfiguredEnvironmentVariable(): Unit {
        val step = BrowserStep(
            index = 1,
            op = "fill",
            selector = "#password",
            url = null,
            value = null,
            valueEnv = "MISSING_PASSWORD",
            optional = false,
            timeoutMillis = null,
            state = "visible"
        )

        assertFailsWith<IllegalStateException> {
            WebPageScreenshotAction.resolveFillValue(step, emptyMap())
        }
        assertEquals(
            "secret",
            WebPageScreenshotAction.resolveFillValue(step, mapOf("MISSING_PASSWORD" to "secret"))
        )
    }

    @Test
    fun screenshotSizeLimitRejectsOversizedImage(): Unit {
        WebPageScreenshotAction.validateScreenshotSize(1024, 1024L)
        assertFailsWith<IllegalArgumentException> {
            WebPageScreenshotAction.validateScreenshotSize(1025, 1024L)
        }
    }

    @Test
    fun screenshotTargetFallsBackToOutgoingGroup(): Unit {
        val action = ActionConfig(
            id = "monitor",
            type = "send_webpage_screenshot",
            enabled = true,
            params = emptyMap()
        )
        val event = EventContext(
            type = "group_message",
            botId = 1L,
            groupId = 200L,
            friendId = null,
            senderId = 300L,
            senderName = "tester",
            messageText = "监控截图",
            timestamp = 1L
        )

        assertEquals(
            ScreenshotTarget.Group(200L),
            WebHookActionExecutor.resolveScreenshotTarget(action, ExecutionContext(config, event = event))
        )
    }

    @Test
    fun screenshotTargetUsesIncomingFriendIdTemplate(): Unit {
        val action = ActionConfig(
            id = "monitor",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf("friend_id" to "${'$'}{request.body.friend_id}")
        )
        val request = RequestContext(
            method = "POST",
            path = "/webpage-screenshot",
            query = emptyMap(),
            headers = emptyMap(),
            body = mapOf("friend_id" to 456L),
            remoteHost = "127.0.0.1"
        )

        assertEquals(
            ScreenshotTarget.Friend(456L),
            WebHookActionExecutor.resolveScreenshotTarget(action, ExecutionContext(config, request = request))
        )
    }

    @Test
    fun screenshotTargetRejectsGroupAndFriendTogether(): Unit {
        val action = ActionConfig(
            id = "monitor",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf("group_id" to 123L, "friend_id" to 456L)
        )

        assertFailsWith<IllegalArgumentException> {
            WebHookActionExecutor.resolveScreenshotTarget(action, ExecutionContext(config))
        }
    }

    @Test
    fun cliBridgeRetainsOneTimeTokensBeforeProfileCompletes(): Unit {
        val profileCalls = AtomicInteger()
        val deliveredRef = AtomicReference<BrowserDeliveredTokenPair?>()
        val manualAuthorizationUrl = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/auth/cli-bridge/start") { exchange ->
            exchange.respondJson(
                200,
                """{"code":0,"data":{"bridge_id":"bridge-retry","poll_secret":"one-time-secret","expires_in":300}}"""
            )
        }
        server.createContext("/api/v1/auth/cli-bridge/poll") { exchange ->
            exchange.respondJson(
                200,
                """{"code":0,"data":{"status":"authorized","access_token":"retained-access","refresh_token":"retained-refresh","expires_in":3600}}"""
            )
        }
        server.createContext("/api/v1/user/profile") { exchange ->
            if (profileCalls.incrementAndGet() == 1) {
                exchange.respondJson(503, """{"code":503,"message":"profile temporarily unavailable"}""")
            } else {
                exchange.respondJson(200, """{"code":0,"data":{"id":9,"email":"retry@example.invalid"}}""")
            }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val resolved = ResolvedBrowserAuth(
            spec = BrowserAuthSpec(
                token = null,
                tokenEnv = null,
                headerName = null,
                headerPrefix = "Bearer ",
                headerHosts = emptyList(),
                localStorage = BrowserLocalStorageAuth(baseUrl, "auth_token", ""),
                cookie = null,
                cliBridge = BrowserCliBridgeAuth(
                    startUrl = "$baseUrl/api/v1/auth/cli-bridge/start",
                    browserUrl = "$baseUrl/cli-bridge",
                    pollUrl = "$baseUrl/api/v1/auth/cli-bridge/poll",
                    profileUrl = "$baseUrl/api/v1/user/profile",
                    refreshUrl = "$baseUrl/api/v1/auth/refresh",
                    pollIntervalMillis = 1L,
                    maxWaitMillis = 10_000L,
                    refreshBeforeExpirySeconds = 120L,
                    retryCooldownMillis = 60_000L
                )
            )
        )

        try {
            val firstError = assertFailsWith<IllegalStateException> {
                WebPageCliBridgeAuthClient.login(
                    auth = resolved,
                    timeoutMillis = 5_000L,
                    browserOpener = { error("desktop unavailable") },
                    sleeper = {},
                    onTokenDelivered = deliveredRef::set,
                    browserOpenFailureHandler = { uri, _ -> manualAuthorizationUrl.set(uri.toString()) }
                )
            }
            assertTrue(firstError.message.orEmpty().contains("CLI bridge profile failed: HTTP 503"))
            assertFalse(firstError.message.orEmpty().contains("one-time-secret"))
            assertEquals("$baseUrl/cli-bridge?bridge_id=bridge-retry", manualAuthorizationUrl.get())
            assertFalse(manualAuthorizationUrl.get().orEmpty().contains("one-time-secret"))

            val delivered = requireNotNull(deliveredRef.get())
            assertEquals("retained-access", delivered.accessToken)
            assertEquals("retained-refresh", delivered.refreshToken)
            val completed = WebPageCliBridgeAuthClient.complete(
                auth = resolved,
                delivered = delivered,
                timeoutMillis = 5_000L
            )
            assertEquals(2, profileCalls.get())
            assertEquals("retry@example.invalid", completed.user["email"]?.jsonPrimitive?.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cliBridgeStartPollProfileAndRefreshFlowDoesNotLeakPollSecret(): Unit {
        val startCalls = AtomicInteger()
        val pollCalls = AtomicInteger()
        val profileCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()
        val lastPollBody = AtomicReference<JsonObject?>()
        val lastRefreshBody = AtomicReference<JsonObject?>()
        val profileAuthorization = AtomicReference<String?>()
        val openedAuthorizationUrl = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/auth/cli-bridge/start") { exchange ->
            startCalls.incrementAndGet()
            exchange.respondJson(
                200,
                """{"code":0,"data":{"bridge_id":"bridge-1","poll_secret":"poll-secret-1","expires_in":300}}"""
            )
        }
        server.createContext("/api/v1/auth/cli-bridge/poll") { exchange ->
            val request = Json.parseToJsonElement(
                exchange.requestBody.bufferedReader().use { it.readText() }
            ) as JsonObject
            lastPollBody.set(request)
            val call = pollCalls.incrementAndGet()
            if (call == 1) {
                exchange.respondJson(200, """{"code":0,"data":{"status":"pending"}}""")
            } else {
                exchange.respondJson(
                    200,
                    """{"code":0,"data":{"status":"authorized","access_token":"bridge-access-1","refresh_token":"bridge-refresh-1","expires_in":3600,"token_type":"Bearer"}}"""
                )
            }
        }
        server.createContext("/api/v1/user/profile") { exchange ->
            profileCalls.incrementAndGet()
            profileAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.respondJson(
                200,
                """{"code":0,"data":{"id":7,"email":"bridge@example.invalid","role":"admin"}}"""
            )
        }
        server.createContext("/api/v1/auth/refresh") { exchange ->
            refreshCalls.incrementAndGet()
            val request = Json.parseToJsonElement(
                exchange.requestBody.bufferedReader().use { it.readText() }
            ) as JsonObject
            lastRefreshBody.set(request)
            exchange.respondJson(
                200,
                """{"code":0,"data":{"access_token":"bridge-access-2","refresh_token":"bridge-refresh-2","expires_in":7200,"token_type":"Bearer"}}"""
            )
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val authSpec = BrowserAuthSpec(
            token = null,
            tokenEnv = null,
            headerName = null,
            headerPrefix = "Bearer ",
            headerHosts = emptyList(),
            localStorage = BrowserLocalStorageAuth(
                origin = baseUrl,
                key = "auth_token",
                prefix = ""
            ),
            cookie = null,
            cliBridge = BrowserCliBridgeAuth(
                startUrl = "$baseUrl/api/v1/auth/cli-bridge/start",
                browserUrl = "$baseUrl/cli-bridge",
                pollUrl = "$baseUrl/api/v1/auth/cli-bridge/poll",
                profileUrl = "$baseUrl/api/v1/user/profile",
                refreshUrl = "$baseUrl/api/v1/auth/refresh",
                pollIntervalMillis = 1L,
                maxWaitMillis = 10_000L,
                refreshBeforeExpirySeconds = 120L,
                retryCooldownMillis = 60_000L
            )
        )
        val resolved = ResolvedBrowserAuth(spec = authSpec)

        try {
            val tokenPair = WebPageCliBridgeAuthClient.login(
                auth = resolved,
                timeoutMillis = 5_000L,
                browserOpener = { uri -> openedAuthorizationUrl.set(uri.toString()) },
                sleeper = {}
            )

            assertEquals(1, startCalls.get())
            assertEquals(2, pollCalls.get())
            assertEquals(1, profileCalls.get())
            assertEquals("bridge-access-1", tokenPair.accessToken)
            assertEquals("bridge-refresh-1", tokenPair.refreshToken)
            assertEquals("bridge@example.invalid", tokenPair.user["email"]?.jsonPrimitive?.content)
            assertEquals("Bearer bridge-access-1", profileAuthorization.get())
            assertEquals(setOf("bridge_id", "poll_secret"), lastPollBody.get()?.keys)
            assertEquals("bridge-1", lastPollBody.get()?.get("bridge_id")?.jsonPrimitive?.content)
            assertEquals("poll-secret-1", lastPollBody.get()?.get("poll_secret")?.jsonPrimitive?.content)
            assertEquals("$baseUrl/cli-bridge?bridge_id=bridge-1", openedAuthorizationUrl.get())
            assertFalse(openedAuthorizationUrl.get().orEmpty().contains("poll-secret-1"))

            val refreshed = WebPageCliBridgeAuthClient.refresh(
                auth = resolved.copy(token = tokenPair.accessToken, tokenPair = tokenPair),
                timeoutMillis = 5_000L,
                nowMillis = 1_000L
            )
            assertEquals(1, refreshCalls.get())
            assertEquals("bridge-refresh-1", lastRefreshBody.get()?.get("refresh_token")?.jsonPrimitive?.content)
            assertEquals("bridge-access-2", refreshed.accessToken)
            assertEquals("bridge-refresh-2", refreshed.refreshToken)
            assertEquals(7_201_000L, refreshed.expiresAtMillis)
            assertEquals(tokenPair.user, refreshed.user)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun screenshotHideSelectorsExcludeFixedHeaderWhenExplicitlyEnabled(): Unit {
        if (System.getenv("XAI_WEBHOOK_BROWSER_IT") != "true") return

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/monitor") { exchange ->
            exchange.respondHtml(
                """
                    <!doctype html>
                    <html>
                    <head>
                      <style>
                        html, body { margin: 0; }
                        #fixed-header {
                          position: fixed;
                          inset: 0 auto auto 0;
                          width: 400px;
                          height: 64px;
                          background: rgb(255, 0, 0);
                          z-index: 100;
                        }
                        #target {
                          width: 400px;
                          height: 300px;
                          background: rgb(0, 255, 0);
                        }
                      </style>
                    </head>
                    <body>
                      <header id="fixed-header" class="sticky">fixed header</header>
                      <main id="target">monitor content</main>
                    </body>
                    </html>
                """.trimIndent()
            )
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val browserConfig = BrowserConfig.safeDefault().copy(
            enabled = true,
            channel = "msedge",
            viewportWidth = 800,
            viewportHeight = 600,
            timeoutMillis = 2_000L,
            allowedHosts = listOf("127.0.0.1")
        )
        val executionContext = ExecutionContext(config.copy(browser = browserConfig))

        fun screenshotAction(id: String, hideHeader: Boolean): ActionConfig {
            val screenshot = mutableMapOf<String, Any?>(
                "selector" to "#target",
                "timeout_ms" to 2_000,
                "font_wait_timeout_ms" to 0
            )
            if (hideHeader) {
                screenshot["hide_selectors"] = listOf("header.sticky")
            }
            return ActionConfig(
                id = id,
                type = "send_webpage_screenshot",
                enabled = true,
                params = mapOf(
                    "steps" to listOf(mapOf("op" to "goto", "url" to "$baseUrl/monitor")),
                    "screenshot" to screenshot
                )
            )
        }

        try {
            val control = runBlocking {
                WebPageScreenshotAction.capture(screenshotAction("header-visible", false), executionContext)
            }
            val hidden = runBlocking {
                WebPageScreenshotAction.capture(screenshotAction("header-hidden", true), executionContext)
            }
            val controlImage = requireNotNull(ImageIO.read(ByteArrayInputStream(control)))
            val hiddenImage = requireNotNull(ImageIO.read(ByteArrayInputStream(hidden)))
            val sampleX = controlImage.width / 2
            val sampleY = 20

            assertEquals(0xFF0000, controlImage.getRGB(sampleX, sampleY) and 0xFFFFFF)
            assertEquals(0x00FF00, hiddenImage.getRGB(sampleX, sampleY) and 0xFFFFFF)
        } finally {
            WebPageScreenshotAction.close()
            server.stop(0)
        }
    }

    @Test
    fun screenshotPartListWaitsBeforeFirstAndFollowingCapturesWhenExplicitlyEnabled(): Unit {
        if (System.getenv("XAI_WEBHOOK_BROWSER_IT") != "true") return

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/monitor") { exchange ->
            exchange.respondHtml(
                """
                    <!doctype html>
                    <html>
                    <head>
                      <style>
                        html, body { margin: 0; }
                        #target { width: 320px; height: 80px; background: rgb(0, 255, 0); }
                      </style>
                    </head>
                    <body>
                      <main id="target">monitor content</main>
                      <script>
                        setTimeout(() => {
                          document.querySelector('#target').style.background = 'rgb(255, 255, 0)';
                        }, 400);
                      </script>
                    </body>
                    </html>
                """.trimIndent()
            )
        }
        server.createContext("/keys") { exchange ->
            exchange.respondHtml(
                """
                    <!doctype html>
                    <html>
                    <head>
                      <style>
                        html, body { margin: 0; }
                        #summary { width: 320px; height: 40px; background: rgb(255, 0, 0); }
                      </style>
                    </head>
                    <body>
                      <section id="summary">keys summary</section>
                      <script>
                        setTimeout(() => {
                          document.querySelector('#summary').style.background = 'rgb(0, 0, 255)';
                        }, 400);
                      </script>
                    </body>
                    </html>
                """.trimIndent()
            )
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val browserConfig = BrowserConfig.safeDefault().copy(
            enabled = true,
            channel = "msedge",
            viewportWidth = 800,
            viewportHeight = 600,
            timeoutMillis = 2_000L,
            allowedHosts = listOf("127.0.0.1")
        )
        val action = ActionConfig(
            id = "part-list",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "screenshot" to mapOf(
                    "parts" to listOf(
                        mapOf(
                            "id" to "keys",
                            "url" to "$baseUrl/keys",
                            "selector" to "#summary"
                        ),
                        mapOf(
                            "id" to "monitor",
                            "url" to "$baseUrl/monitor",
                            "delay_before_ms" to 900,
                            "steps" to listOf(
                                mapOf("op" to "wait", "selector" to "#target", "state" to "visible")
                            ),
                            "selector" to "#target"
                        )
                    ),
                    "layout" to mapOf("horizontal_align" to "center", "gap_px" to 4),
                    "timeout_ms" to 2_000,
                    "delay_before_ms" to 700,
                    "font_wait_timeout_ms" to 0
                )
            )
        )

        try {
            val bytes = runBlocking {
                WebPageScreenshotAction.capture(
                    action,
                    ExecutionContext(config.copy(browser = browserConfig))
                )
            }
            val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))

            assertEquals(320, image.width)
            assertEquals(124, image.height)
            assertEquals(0x0000FF, image.getRGB(160, 20) and 0xFFFFFF)
            assertEquals(0, image.getRGB(160, 42) ushr 24)
            assertEquals(0xFFFF00, image.getRGB(160, 84) and 0xFFFFFF)
        } finally {
            WebPageScreenshotAction.close()
            server.stop(0)
        }
    }

    @Test
    fun screenshotRetrySucceedsAndHonorsMaxRetriesWhenExplicitlyEnabled(): Unit {
        if (System.getenv("XAI_WEBHOOK_BROWSER_IT") != "true") return

        val flakyCalls = AtomicInteger()
        val missingCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/flaky") { exchange ->
            val includeTarget = flakyCalls.incrementAndGet() >= 2
            exchange.respondHtml(
                if (includeTarget) {
                    "<html><body><main id=\"target\">retry succeeded</main></body></html>"
                } else {
                    "<html><body><main>target pending</main></body></html>"
                }
            )
        }
        server.createContext("/always-missing") { exchange ->
            missingCalls.incrementAndGet()
            exchange.respondHtml("<html><body><main>target missing</main></body></html>")
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val browserConfig = BrowserConfig.safeDefault().copy(
            enabled = true,
            channel = "msedge",
            timeoutMillis = 1_000L,
            allowedHosts = listOf("127.0.0.1")
        )
        val executionContext = ExecutionContext(config.copy(browser = browserConfig))

        fun retryAction(id: String, path: String, maxRetries: Int): ActionConfig {
            return ActionConfig(
                id = id,
                type = "send_webpage_screenshot",
                enabled = true,
                params = mapOf(
                    "steps" to listOf(mapOf("op" to "goto", "url" to "$baseUrl$path")),
                    "screenshot" to mapOf(
                        "selector" to "#target",
                        "timeout_ms" to 200,
                        "font_wait_timeout_ms" to 0,
                        "retry" to mapOf(
                            "enabled" to true,
                            "max_retries" to maxRetries,
                            "delay_ms" to 0
                        )
                    )
                )
            )
        }

        try {
            val screenshot = runBlocking {
                WebPageScreenshotAction.capture(
                    retryAction("retry-success", "/flaky", maxRetries = 1),
                    executionContext
                )
            }
            assertTrue(screenshot.hasPngHeader())
            assertEquals(2, flakyCalls.get())

            val error = assertFailsWith<IllegalStateException> {
                runBlocking {
                    WebPageScreenshotAction.capture(
                        retryAction("retry-limit", "/always-missing", maxRetries = 2),
                        executionContext
                    )
                }
            }
            assertTrue(error.message.orEmpty().contains("browser screenshot"))
            assertEquals(3, missingCalls.get())
        } finally {
            WebPageScreenshotAction.close()
            server.stop(0)
        }
    }

    @Test
    fun screenshotContinuesWhenWebFontNeverFinishesWhenExplicitlyEnabled(): Unit {
        if (System.getenv("XAI_WEBHOOK_BROWSER_IT") != "true") return

        val fontStarted = CountDownLatch(1)
        val fontRelease = CountDownLatch(1)
        val serverExecutor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverExecutor
        server.createContext("/blocked-font.woff2") { exchange ->
            fontStarted.countDown()
            try {
                exchange.responseHeaders.add("Content-Type", "font/woff2")
                exchange.sendResponseHeaders(200, 0)
                fontRelease.await(30, TimeUnit.SECONDS)
            } finally {
                runCatching { exchange.responseBody.close() }
                exchange.close()
            }
        }
        server.createContext("/monitor") { exchange ->
            val body = """
                <!doctype html>
                <html>
                <head>
                  <title>Blocked Font Monitor</title>
                  <style>
                    @font-face {
                      font-family: 'BlockedFont';
                      src: url('/blocked-font.woff2') format('woff2');
                      font-display: block;
                    }
                    #target {
                      width: 640px;
                      height: 220px;
                      font: 32px 'BlockedFont', sans-serif;
                    }
                  </style>
                </head>
                <body>
                  <main id="target">字体请求未结束时仍应完成截图</main>
                  <script>document.fonts.load("32px BlockedFont")</script>
                </body>
                </html>
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val browserConfig = BrowserConfig.safeDefault().copy(
            enabled = true,
            channel = "msedge",
            timeoutMillis = 2_000L,
            allowedHosts = listOf("127.0.0.1")
        )
        val executionContext = ExecutionContext(config.copy(browser = browserConfig))
        val action = ActionConfig(
            id = "blocked-font-monitor",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "steps" to listOf(
                    mapOf("op" to "goto", "url" to "$baseUrl/monitor"),
                    mapOf("op" to "wait", "selector" to "#target", "state" to "visible")
                ),
                "screenshot" to mapOf(
                    "selector" to "#target",
                    "timeout_ms" to 2_000,
                    "font_wait_timeout_ms" to 100
                )
            )
        )

        try {
            val screenshot = runBlocking {
                WebPageScreenshotAction.capture(action, executionContext)
            }
            assertTrue(fontStarted.await(1, TimeUnit.SECONDS))
            assertTrue(screenshot.hasPngHeader())
        } finally {
            fontRelease.countDown()
            WebPageScreenshotAction.close()
            server.stop(0)
            serverExecutor.shutdownNow()
        }
    }

    @Test
    fun gotoCommitStillFailsWhenServerDoesNotCommitResponseWhenExplicitlyEnabled(): Unit {
        if (System.getenv("XAI_WEBHOOK_BROWSER_IT") != "true") return

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/monitor") { exchange ->
            Thread.sleep(1_500L)
            runCatching {
                val body = "<html><body><main id=\"target\">late</main></body></html>".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val browserConfig = BrowserConfig.safeDefault().copy(
            enabled = true,
            channel = "msedge",
            allowedHosts = listOf("127.0.0.1"),
            timeoutMillis = 5_000L
        )
        val action = ActionConfig(
            id = "uncommitted-navigation",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "steps" to listOf(
                    mapOf(
                        "op" to "goto",
                        "url" to "$baseUrl/monitor",
                        "timeout_ms" to 300
                    )
                ),
                "screenshot" to mapOf("selector" to "#target", "timeout_ms" to 1_000)
            )
        )

        try {
            val error = assertFailsWith<IllegalStateException> {
                runBlocking {
                    WebPageScreenshotAction.capture(
                        action,
                        ExecutionContext(config.copy(browser = browserConfig))
                    )
                }
            }
            assertTrue(error.message.orEmpty().contains("waitUntil=commit"))
            assertTrue(error.message.orEmpty().contains("Timeout 300ms exceeded"))
        } finally {
            WebPageScreenshotAction.close()
            server.stop(0)
        }
    }

    @Test
    fun gotoCommitContinuesWhenDomContentLoadedIsDelayedWhenExplicitlyEnabled(): Unit {
        if (System.getenv("XAI_WEBHOOK_BROWSER_IT") != "true") return

        val scriptCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow.js") { exchange ->
            scriptCalls.incrementAndGet()
            Thread.sleep(2_000L)
            val body = "window.slowScriptLoaded = true;".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/javascript; charset=UTF-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/monitor") { exchange ->
            val body = """
                <!doctype html>
                <html>
                <head>
                  <title>Committed Monitor</title>
                  <script src="/slow.js"></script>
                </head>
                <body><main id="target">monitor ready</main></body>
                </html>
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val browserConfig = BrowserConfig.safeDefault().copy(
            enabled = true,
            channel = "msedge",
            allowedHosts = listOf("127.0.0.1"),
            timeoutMillis = 5_000L
        )
        val action = ActionConfig(
            id = "commit-navigation",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "steps" to listOf(
                    mapOf(
                        "op" to "goto",
                        "url" to "$baseUrl/monitor",
                        "timeout_ms" to 1_000
                    ),
                    mapOf(
                        "op" to "wait",
                        "selector" to "#target",
                        "state" to "visible",
                        "timeout_ms" to 5_000
                    )
                ),
                "screenshot" to mapOf("selector" to "#target", "timeout_ms" to 5_000)
            )
        )

        try {
            val spec = WebPageScreenshotAction.parseSpec(
                action,
                ExecutionContext(config.copy(browser = browserConfig))
            )
            assertEquals("commit", spec.screenshotParts.single().steps.first().waitUntil)

            val bytes = runBlocking {
                WebPageScreenshotAction.capture(
                    action,
                    ExecutionContext(config.copy(browser = browserConfig))
                )
            }
            assertTrue(bytes.hasPngHeader())
            assertEquals(1, scriptCalls.get())
        } finally {
            WebPageScreenshotAction.close()
            server.stop(0)
        }
    }

    @Test
    fun webpageScreenshotWaitsForRealMonitorCardAfterSkeletonWhenExplicitlyEnabled(): Unit {
        if (System.getenv("XAI_WEBHOOK_BROWSER_IT") != "true") return

        val readyCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ready") { exchange ->
            readyCalls.incrementAndGet()
            exchange.respondJson(200, """{"ready":true}""")
        }
        server.createContext("/monitor") { exchange ->
            val body = """
                <!doctype html>
                <html>
                <head><title>Delayed Monitor</title></head>
                <body>
                  <main id="target">
                    <div id="skeleton" class="p-5 min-h-[280px] animate-pulse">loading</div>
                  </main>
                  <script>
                    setTimeout(async () => {
                      await fetch('/ready')
                      document.getElementById('skeleton').remove()
                      const card = document.createElement('button')
                      card.type = 'button'
                      card.className = 'group min-h-[280px]'
                      card.textContent = 'monitor data loaded'
                      document.getElementById('target').appendChild(card)
                    }, 600)
                  </script>
                </body>
                </html>
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val readySelector = "(//main//button[@type='button' and " +
            "contains(concat(' ', normalize-space(@class), ' '), ' group ') and " +
            "contains(concat(' ', normalize-space(@class), ' '), ' min-h-[280px] ')] | " +
            "//main//div[contains(concat(' ', normalize-space(@class), ' '), ' empty-state ')])[1]"
        val browserConfig = BrowserConfig.safeDefault().copy(
            enabled = true,
            channel = "msedge",
            allowedHosts = listOf("127.0.0.1"),
            timeoutMillis = 5_000L
        )
        val action = ActionConfig(
            id = "delayed-monitor",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "steps" to listOf(
                    mapOf("op" to "goto", "url" to "$baseUrl/monitor"),
                    mapOf("op" to "wait", "selector" to "#target", "state" to "visible"),
                    mapOf(
                        "op" to "wait",
                        "selector" to readySelector,
                        "state" to "visible",
                        "timeout_ms" to 5_000
                    )
                ),
                "screenshot" to mapOf("selector" to "#target", "timeout_ms" to 5_000)
            )
        )

        try {
            val bytes = runBlocking {
                WebPageScreenshotAction.capture(
                    action,
                    ExecutionContext(config.copy(browser = browserConfig))
                )
            }
            assertTrue(bytes.hasPngHeader())
            assertEquals(1, readyCalls.get())
        } finally {
            WebPageScreenshotAction.close()
            server.stop(0)
        }
    }

    @Test
    fun webpageScreenshotActionIntegrationWhenExplicitlyEnabled(): Unit {
        if (System.getenv("XAI_WEBHOOK_BROWSER_IT") != "true") return

        val authorization = AtomicReference<String?>()
        val cookieHeader = AtomicReference<String?>()
        val bootstrapCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/auth/me") { exchange ->
            bootstrapCalls.incrementAndGet()
            val requestAuthorization = exchange.requestHeaders.getFirst("Authorization")
            authorization.set(requestAuthorization)
            val authorized = requestAuthorization == "Bearer secret-token"
            val body = if (authorized) {
                """{"code":0,"data":{"id":1,"username":"tester","role":"admin"},"message":"success"}"""
            } else {
                """{"code":401,"message":"unauthorized"}"""
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(if (authorized) 200 else 401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/monitor") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            cookieHeader.set(exchange.requestHeaders.getFirst("Cookie"))
            val body = """
                <!doctype html>
                <html>
                <head><title>Bootstrap Monitor</title></head>
                <body>
                  <section class="monitor-card">
                    <main id="target" hidden>截图测试</main>
                  </section>
                  <script>
                    try {
                      const token = localStorage.getItem('access_token')
                      const user = JSON.parse(localStorage.getItem('auth_user') || 'null')
                      if (token === 'secret-token' && user && user.username === 'tester') {
                        document.querySelector('.monitor-card').classList.add('authenticated')
                        document.getElementById('target').hidden = false
                      }
                    } catch (_) {}
                  </script>
                </body>
                </html>
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        val browserConfig = BrowserConfig.safeDefault().copy(
            enabled = true,
            channel = "msedge",
            allowedHosts = listOf("127.0.0.1")
        )
        val action = ActionConfig(
            id = "local-bootstrap-monitor",
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "auth" to mapOf(
                    "token" to "secret-token",
                    "header_hosts" to listOf("127.0.0.1"),
                    "local_storage" to mapOf(
                        "origin" to baseUrl,
                        "key" to "access_token"
                    ),
                    "bootstrap" to mapOf(
                        "url" to "$baseUrl/api/v1/auth/me",
                        "response_path" to "data",
                        "local_storage_key" to "auth_user",
                        "expected_status" to listOf(200)
                    ),
                    "cookie" to mapOf(
                        "name" to "access_token",
                        "url" to "$baseUrl/"
                    )
                ),
                "steps" to listOf(
                    mapOf("op" to "goto", "url" to "$baseUrl/monitor"),
                    mapOf("op" to "wait", "selector" to ".authenticated", "state" to "visible")
                ),
                "screenshot" to mapOf("selector" to ".monitor-card")
            )
        )
        val executionContext = ExecutionContext(config.copy(browser = browserConfig))

        try {
            val bytes = runBlocking {
                WebPageScreenshotAction.capture(action, executionContext)
            }
            assertTrue(bytes.hasPngHeader())
            assertEquals(1, bootstrapCalls.get())
            assertEquals("Bearer secret-token", authorization.get())
            assertTrue(cookieHeader.get().orEmpty().contains("access_token=secret-token"))

            @Suppress("UNCHECKED_CAST")
            val authParams = action.params["auth"] as Map<String, Any?>
            val unauthorizedAction = action.copy(
                id = "local-bootstrap-unauthorized",
                params = action.params + ("auth" to (authParams + ("token" to "invalid-token")))
            )
            val unauthorizedError = assertFailsWith<IllegalStateException> {
                runBlocking {
                    WebPageScreenshotAction.capture(unauthorizedAction, executionContext)
                }
            }
            assertEquals("auth bootstrap failed: HTTP 401", unauthorizedError.message)
            assertFalse(unauthorizedError.message.orEmpty().contains("invalid-token"))

            val diagnosticAction = action.copy(
                id = "local-bootstrap-diagnostic",
                params = action.params + (
                    "screenshot" to mapOf(
                        "selector" to ".missing-target",
                        "timeout_ms" to 200
                    )
                )
            )
            val diagnosticError = assertFailsWith<IllegalStateException> {
                runBlocking {
                    WebPageScreenshotAction.capture(diagnosticAction, executionContext)
                }
            }
            assertTrue(diagnosticError.message.orEmpty().contains("url=$baseUrl/monitor"))
            assertTrue(diagnosticError.message.orEmpty().contains("title=\"Bootstrap Monitor\""))
        } finally {
            WebPageScreenshotAction.close()
            server.stop(0)
        }
    }

    @Test
    fun credentialLogin2FARefreshAndCooldownIntegrationWhenExplicitlyEnabled(): Unit {
        if (System.getenv("XAI_WEBHOOK_BROWSER_IT") != "true") return

        val loginCalls = AtomicInteger()
        val twoFactorCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()
        val lastLoginBody = AtomicReference<JsonObject?>()
        val lastTwoFactorBody = AtomicReference<JsonObject?>()
        val lastRefreshBody = AtomicReference<JsonObject?>()
        val monitorAuthorization = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/auth/login") { exchange ->
            loginCalls.incrementAndGet()
            val request = Json.parseToJsonElement(
                exchange.requestBody.bufferedReader().use { it.readText() }
            ) as JsonObject
            lastLoginBody.set(request)
            when (request["email"]?.jsonPrimitive?.content) {
                "twofa@example.invalid" -> exchange.respondJson(
                    200,
                    """{"code":0,"data":{"requires_2fa":true,"temp_token":"temp-1","user_email_masked":"t***@example.invalid"}}"""
                )
                "blocked@example.invalid" -> exchange.respondJson(
                    400,
                    """{"code":400,"reason":"CAP_VERIFICATION_FAILED","message":"cap verification failed"}"""
                )
                else -> exchange.respondJson(
                    200,
                    """{"code":0,"data":{"access_token":"access-1","refresh_token":"refresh-1","expires_in":1,"token_type":"Bearer","user":{"id":1,"username":"tester","role":"admin"}}}"""
                )
            }
        }
        server.createContext("/api/v1/auth/login/2fa") { exchange ->
            twoFactorCalls.incrementAndGet()
            val request = Json.parseToJsonElement(
                exchange.requestBody.bufferedReader().use { it.readText() }
            ) as JsonObject
            lastTwoFactorBody.set(request)
            exchange.respondJson(
                200,
                """{"access_token":"twofa-access","refresh_token":"twofa-refresh","expires_in":3600,"token_type":"Bearer","user":{"id":2,"username":"twofa-user","role":"user"}}"""
            )
        }
        server.createContext("/api/v1/auth/refresh") { exchange ->
            refreshCalls.incrementAndGet()
            val request = Json.parseToJsonElement(
                exchange.requestBody.bufferedReader().use { it.readText() }
            ) as JsonObject
            lastRefreshBody.set(request)
            exchange.respondJson(
                200,
                """{"code":0,"data":{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600,"token_type":"Bearer"}}"""
            )
        }
        server.createContext("/monitor") { exchange ->
            monitorAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = """
                <!doctype html>
                <html>
                <head><title>Credential Monitor</title></head>
                <body>
                  <section class="monitor-card">
                    <main id="target" hidden>凭据登录截图测试</main>
                  </section>
                  <script>
                    try {
                      const access = localStorage.getItem('auth_token')
                      const refresh = localStorage.getItem('refresh_token')
                      const expires = Number(localStorage.getItem('token_expires_at'))
                      const user = JSON.parse(localStorage.getItem('auth_user') || 'null')
                      if (access && refresh && expires > 0 && user && user.username) {
                        document.querySelector('.monitor-card').classList.add('authenticated')
                        document.getElementById('target').hidden = false
                      }
                    } catch (_) {}
                  </script>
                </body>
                </html>
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val sessionCacheDirectory = Files.createTempDirectory("xaiwebhook-browser-session-it")
        val browserConfig = BrowserConfig.safeDefault().copy(
            enabled = true,
            channel = "msedge",
            sessionCacheEnabled = true,
            sessionCacheDirectory = sessionCacheDirectory.toString(),
            allowedHosts = listOf("127.0.0.1")
        )
        val executionContext = ExecutionContext(config.copy(browser = browserConfig))

        fun credentialAction(
            id: String,
            sessionKey: String,
            inlineEmail: String? = null,
            inlinePassword: String? = null
        ): ActionConfig {
            require((inlineEmail == null) == (inlinePassword == null))
            val credentialSources: Map<String, Any?> = if (inlineEmail != null && inlinePassword != null) {
                mapOf("email" to inlineEmail, "password" to inlinePassword)
            } else {
                mapOf(
                    "email_env" to "TEST_LOGIN_EMAIL",
                    "password_env" to "TEST_LOGIN_PASSWORD"
                )
            }
            return ActionConfig(
                id = id,
                type = "send_webpage_screenshot",
                enabled = true,
                params = mapOf(
                    "session_key" to sessionKey,
                    "auth" to mapOf(
                        "login" to (mapOf(
                            "url" to "$baseUrl/api/v1/auth/login",
                            "two_factor_url" to "$baseUrl/api/v1/auth/login/2fa",
                            "totp_code_env" to "TEST_LOGIN_TOTP",
                            "refresh_url" to "$baseUrl/api/v1/auth/refresh",
                            "refresh_before_expiry_seconds" to 60,
                            "retry_cooldown_ms" to 60_000
                        ) + credentialSources),
                        "local_storage" to mapOf(
                            "origin" to baseUrl,
                            "key" to "auth_token",
                            "refresh_token_key" to "refresh_token",
                            "expires_at_key" to "token_expires_at",
                            "user_key" to "auth_user"
                        )
                    ),
                    "steps" to listOf(
                        mapOf("op" to "goto", "url" to "$baseUrl/monitor"),
                        mapOf("op" to "wait", "selector" to ".authenticated", "state" to "visible")
                    ),
                    "screenshot" to mapOf("selector" to ".monitor-card", "timeout_ms" to 2_000)
                )
            )
        }

        try {
            val action = credentialAction(
                id = "credential-normal",
                sessionKey = "credential-normal-session",
                inlineEmail = "normal@example.invalid",
                inlinePassword = "normal-password"
            )
            val first = runBlocking {
                WebPageScreenshotAction.capture(action, executionContext, emptyMap())
            }
            assertTrue(first.hasPngHeader())
            assertEquals(1, loginCalls.get())
            assertEquals(setOf("email", "password"), lastLoginBody.get()?.keys)
            assertEquals(null, monitorAuthorization.get())

            val failingScreenshot = action.copy(
                params = action.params + (
                    "screenshot" to mapOf("selector" to ".missing-target", "timeout_ms" to 200)
                )
            )
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    WebPageScreenshotAction.capture(
                        failingScreenshot,
                        executionContext,
                        emptyMap()
                    )
                }
            }
            assertEquals(1, refreshCalls.get())
            assertEquals("refresh-1", lastRefreshBody.get()?.get("refresh_token")?.jsonPrimitive?.content)

            WebPageScreenshotAction.close()
            val third = runBlocking {
                WebPageScreenshotAction.capture(action, executionContext, emptyMap())
            }
            assertTrue(third.hasPngHeader())
            assertEquals(1, loginCalls.get())
            assertEquals(1, refreshCalls.get())

            val twoFactorEnvironment = mapOf(
                "TEST_LOGIN_EMAIL" to "twofa@example.invalid",
                "TEST_LOGIN_PASSWORD" to "twofa-password",
                "TEST_LOGIN_TOTP" to "123456"
            )
            val twoFactor = runBlocking {
                WebPageScreenshotAction.capture(
                    credentialAction("credential-twofa", "credential-twofa-session"),
                    executionContext,
                    twoFactorEnvironment
                )
            }
            assertTrue(twoFactor.hasPngHeader())
            assertEquals(1, twoFactorCalls.get())
            assertEquals(setOf("temp_token", "totp_code"), lastTwoFactorBody.get()?.keys)
            assertEquals("temp-1", lastTwoFactorBody.get()?.get("temp_token")?.jsonPrimitive?.content)
            assertEquals("123456", lastTwoFactorBody.get()?.get("totp_code")?.jsonPrimitive?.content)

            val blockedEnvironment = mapOf(
                "TEST_LOGIN_EMAIL" to "blocked@example.invalid",
                "TEST_LOGIN_PASSWORD" to "must-not-leak-password"
            )
            val blockedAction = credentialAction("credential-blocked", "credential-blocked-session")
            val callsBeforeBlocked = loginCalls.get()
            val blockedError = assertFailsWith<IllegalStateException> {
                runBlocking {
                    WebPageScreenshotAction.capture(
                        blockedAction,
                        executionContext,
                        blockedEnvironment
                    )
                }
            }
            assertTrue(blockedError.message.orEmpty().contains("CAP_VERIFICATION_FAILED"))
            assertFalse(blockedError.message.orEmpty().contains("blocked@example.invalid"))
            assertFalse(blockedError.message.orEmpty().contains("must-not-leak-password"))
            assertEquals(callsBeforeBlocked + 1, loginCalls.get())

            val cooldownError = assertFailsWith<IllegalStateException> {
                runBlocking {
                    WebPageScreenshotAction.capture(
                        blockedAction,
                        executionContext,
                        blockedEnvironment
                    )
                }
            }
            assertTrue(cooldownError.message.orEmpty().contains("retry cooldown active"))
            assertEquals(callsBeforeBlocked + 1, loginCalls.get())
        } finally {
            WebPageScreenshotAction.close()
            server.stop(0)
            sessionCacheDirectory.toFile().deleteRecursively()
        }
    }

    private fun HttpExchange.respondHtml(bodyText: String): Unit {
        val body = bodyText.toByteArray()
        responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
        sendResponseHeaders(200, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun HttpExchange.respondJson(status: Int, bodyText: String): Unit {
        val body = bodyText.toByteArray()
        responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun ByteArray.hasPngHeader(): Boolean {
        val header = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        return size > header.size && take(header.size).toByteArray().contentEquals(header)
    }
}
