package kim.hhhhhy.x.webhook.scraper

import kim.hhhhhy.x.webhook.config.ModelPlazaAuthConfig
import kim.hhhhhy.x.webhook.config.ModelPlazaConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ModelPlazaScraperTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fuzzyMatch should return exact match`() {
        val candidates = listOf("gpt-4", "gpt-3.5-turbo", "claude-3-opus")
        val result = ModelPlazaScraper.fuzzyMatch("gpt-4", candidates)
        assertEquals(1, result.size)
        assertEquals("gpt-4", result[0])
    }

    @Test
    fun `fuzzyMatch should return partial matches`() {
        val candidates = listOf("gpt-4", "gpt-3.5-turbo", "claude-3-opus")
        val result = ModelPlazaScraper.fuzzyMatch("gpt", candidates)
        assertEquals(2, result.size)
        assertTrue(result.contains("gpt-4"))
        assertTrue(result.contains("gpt-3.5-turbo"))
    }

    @Test
    fun `fuzzyMatch should be case insensitive`() {
        val candidates = listOf("GPT-4", "Claude-3-Opus", "Gemini-Pro")
        val result = ModelPlazaScraper.fuzzyMatch("gpt", candidates)
        assertEquals(1, result.size)
        assertEquals("GPT-4", result[0])
    }

    @Test
    fun `fuzzyMatch should handle empty pattern`() {
        val candidates = listOf("model-1", "model-2")
        val result = ModelPlazaScraper.fuzzyMatch("", candidates)
        assertEquals(2, result.size)
    }

    @Test
    fun `fuzzyMatch should return empty list when no matches`() {
        val candidates = listOf("gpt-4", "claude-3-opus")
        val result = ModelPlazaScraper.fuzzyMatch("llama", candidates)
        assertEquals(0, result.size)
    }

    @Test
    fun `fuzzyMatch should handle whitespace`() {
        val candidates = listOf("  gpt-4  ", "claude-3-opus")
        val result = ModelPlazaScraper.fuzzyMatch(" gpt ", candidates)
        assertEquals(1, result.size)
    }

    @Test
    fun `ModelInfo should hold model name`() {
        val model = ModelInfo("gpt-4")
        assertEquals("gpt-4", model.name)
    }

    @Test
    fun `GroupInfo should hold group name`() {
        val group = GroupInfo("OpenAI")
        assertEquals("OpenAI", group.name)
    }

    @Test
    fun `ModelGroupRelation should hold model and groups`() {
        val relation = ModelGroupRelation(
            modelName = "gpt-4",
            groupNames = listOf("OpenAI", "Premium")
        )
        assertEquals("gpt-4", relation.modelName)
        assertEquals(2, relation.groupNames.size)
        assertTrue(relation.groupNames.contains("OpenAI"))
        assertTrue(relation.groupNames.contains("Premium"))
    }

    @Test
    fun `ModelPlazaConfig should have safe defaults`() {
        val config = ModelPlazaConfig.safeDefault()
        assertFalse(config.enabled)
        assertEquals("https://hk.geek2api.com/model-plaza", config.baseUrl)
        assertEquals(30_000L, config.timeoutMillis)
        assertNull(config.auth)
    }

    @Test
    fun `ModelPlazaAuthConfig should use CLI Bridge fields`() {
        val authConfig = ModelPlazaAuthConfig(
            startUrl = "https://hk.geek2api.com/api/v1/auth/cli-bridge/start",
            browserUrl = "https://hk.geek2api.com/cli-bridge",
            pollUrl = "https://hk.geek2api.com/api/v1/auth/cli-bridge/poll",
            profileUrl = "https://hk.geek2api.com/api/v1/user/profile",
            refreshUrl = "https://hk.geek2api.com/api/v1/auth/refresh",
            pollIntervalMillis = 3_000L,
            maxWaitMillis = 300_000L,
            refreshBeforeExpirySeconds = 300L,
            retryCooldownMillis = 5_000L
        )
        assertEquals("https://hk.geek2api.com/api/v1/auth/cli-bridge/start", authConfig.startUrl)
        assertEquals("https://hk.geek2api.com/cli-bridge", authConfig.browserUrl)
        assertEquals(3_000L, authConfig.pollIntervalMillis)
        assertEquals(300_000L, authConfig.maxWaitMillis)
        assertEquals(300L, authConfig.refreshBeforeExpirySeconds)
    }

    @Test
    fun `ModelPlazaConfig with auth should not be null`() {
        val authConfig = ModelPlazaAuthConfig(
            startUrl = "https://example.com/start",
            browserUrl = "https://example.com/bridge",
            pollUrl = "https://example.com/poll",
            profileUrl = "https://example.com/profile",
            refreshUrl = "https://example.com/refresh",
            pollIntervalMillis = 3_000L,
            maxWaitMillis = 300_000L,
            refreshBeforeExpirySeconds = 300L,
            retryCooldownMillis = 5_000L
        )
        val config = ModelPlazaConfig(
            enabled = true,
            baseUrl = "https://example.com/model-plaza",
            timeoutMillis = 30_000L,
            auth = authConfig
        )
        assertTrue(config.enabled)
        assertNotNull(config.auth)
        assertEquals(authConfig, config.auth)
    }

    @Test
    fun `parseModelPlazaResponse should decode envelope payload`() {
        val platforms = ModelPlazaScraper.parseModelPlazaResponse(modelPlazaResponse())

        assertEquals(3, platforms.size)
        assertEquals("anthropic", platforms[0].platform)
        assertEquals(listOf("claude-opus-5", "claude-fable-5"), platforms[0].modelNames)
        assertEquals(listOf("Claude - kiro企业版"), platforms[0].groupNames)
    }

    @Test
    fun `modelsByGroup should fuzzy match groups and remove duplicate models`() {
        val platforms = ModelPlazaScraper.parseModelPlazaResponse(modelPlazaResponse())

        val models = ModelPlazaScraper.modelsByGroup(platforms, "claude")

        assertEquals(listOf("claude-opus-5", "claude-fable-5", "claude-haiku-4-5"), models)
    }

    @Test
    fun `groupsByModel should return every matching group without official pricing`() {
        val platforms = ModelPlazaScraper.parseModelPlazaResponse(modelPlazaResponse())

        val groups = ModelPlazaScraper.groupsByModel(platforms, "fable")

        assertEquals(listOf("Claude - kiro企业版", "稳定Claude"), groups)
        assertFalse(groups.contains("官方定价"))
    }

    @Test
    fun `groupModelsByPattern should list models under every fuzzy matched group`() {
        val relations = ModelPlazaScraper.groupModelsByPattern(gptSearchPlatforms(), "gpt")

        assertEquals(listOf("gpt", "gptpro", "gpt-Azure"), relations.map { it.groupName })
        assertEquals(listOf("gpt-4", "gpt-5"), relations[0].modelNames)
        assertEquals(listOf("gpt-4", "gpt-pro"), relations[1].modelNames)
        assertEquals(listOf("gpt-4", "gpt-azure-chat"), relations[2].modelNames)
    }

    @Test
    fun `modelGroupsByPattern should list groups under every fuzzy matched model`() {
        val relations = ModelPlazaScraper.modelGroupsByPattern(gptSearchPlatforms(), "gpt")

        assertEquals(
            listOf("gpt-4", "gpt-5", "gpt-pro", "gpt-azure-chat"),
            relations.map { it.modelName }
        )
        assertEquals(listOf("gpt", "gptpro", "gpt-Azure"), relations[0].groupNames)
        assertEquals(listOf("gpt"), relations[1].groupNames)
        assertEquals(listOf("gptpro"), relations[2].groupNames)
        assertEquals(listOf("gpt-Azure"), relations[3].groupNames)
    }

    @Test
    fun `buildApiEndpoints should preserve selected Geek2Api entrance then fail over`() {
        val endpoints = ModelPlazaScraper.buildApiEndpoints("https://hk3.geek2api.com/model-plaza")

        assertEquals("https://hk3.geek2api.com/api/v1/model-plaza", endpoints[0].toString())
        assertEquals(5, endpoints.size)
        assertEquals(5, endpoints.distinct().size)
    }

    @Test
    fun `cookie cache should reuse Playwright storage state filtering rules`() {
        val stateFile = tempDir.resolve("playwright-storage-state.json")
        Files.writeString(stateFile, browserStorageState(), StandardCharsets.UTF_8)
        val cache = ModelPlazaCookieCache(stateFile)
        val endpoint = URI("https://hk.geek2api.com/api/v1/model-plaza")

        val header = cache.cookieHeader(endpoint, nowMillis = 10_000L)

        assertEquals("api_cookie=api; shared_cookie=shared; host_cookie=host", header)
        val failoverHeader = cache.cookieHeader(
            URI("https://hk2.geek2api.com/api/v1/model-plaza"),
            nowMillis = 10_000L
        )
        assertEquals("api_cookie=api; shared_cookie=shared; other_host=other", failoverHeader)
    }

    @Test
    fun `cookie cache should merge Set-Cookie into the original storage state file`() {
        val stateFile = tempDir.resolve("playwright-storage-state.json")
        Files.writeString(stateFile, browserStorageState(), StandardCharsets.UTF_8)
        val cache = ModelPlazaCookieCache(stateFile)
        val endpoint = URI("https://hk.geek2api.com/api/v1/model-plaza")

        cache.storeResponseCookies(
            endpoint = endpoint,
            setCookieHeaders = listOf(
                "route_cookie=hk; Path=/api; Domain=.geek2api.com; Max-Age=3600; Secure; HttpOnly"
            ),
            nowMillis = 10_000L
        )

        val reloaded = ModelPlazaCookieCache(stateFile)
        assertTrue(reloaded.cookieHeader(endpoint, nowMillis = 11_000L).contains("route_cookie=hk"))
        assertTrue(Files.readString(stateFile, StandardCharsets.UTF_8).contains("auth_token"))

        reloaded.storeResponseCookies(
            endpoint = endpoint,
            setCookieHeaders = listOf(
                "route_cookie=; Path=/api; Domain=.geek2api.com; Max-Age=0; Secure; HttpOnly"
            ),
            nowMillis = 12_000L
        )
        assertFalse(ModelPlazaCookieCache(stateFile).cookieHeader(endpoint, nowMillis = 13_000L).contains("route_cookie="))
    }

    private fun gptSearchPlatforms(): List<ModelPlazaPlatform> {
        return listOf(
            ModelPlazaPlatform(
                platform = "openai",
                modelNames = listOf("gpt-4", "gpt-5"),
                groupNames = listOf("gpt")
            ),
            ModelPlazaPlatform(
                platform = "openai",
                modelNames = listOf("gpt-4", "gpt-pro"),
                groupNames = listOf("gptpro")
            ),
            ModelPlazaPlatform(
                platform = "azure",
                modelNames = listOf("gpt-4", "gpt-azure-chat"),
                groupNames = listOf("gpt-Azure")
            ),
            ModelPlazaPlatform(
                platform = "anthropic",
                modelNames = listOf("claude-opus-5"),
                groupNames = listOf("ClaudeMax")
            )
        )
    }

    private fun browserStorageState(): String {
        return """
            {
              "cookies": [
                {
                  "name": "api_cookie",
                  "value": "api",
                  "domain": ".geek2api.com",
                  "path": "/api",
                  "expires": -1,
                  "httpOnly": true,
                  "secure": true,
                  "sameSite": "Lax"
                },
                {
                  "name": "shared_cookie",
                  "value": "shared",
                  "domain": ".geek2api.com",
                  "path": "/",
                  "expires": -1,
                  "httpOnly": false,
                  "secure": true,
                  "sameSite": "Lax"
                },
                {
                  "name": "host_cookie",
                  "value": "host",
                  "domain": "hk.geek2api.com",
                  "path": "/",
                  "expires": -1,
                  "httpOnly": false,
                  "secure": true,
                  "sameSite": "Lax"
                },
                {
                  "name": "other_host",
                  "value": "other",
                  "domain": "hk2.geek2api.com",
                  "path": "/",
                  "expires": -1,
                  "httpOnly": false,
                  "secure": true,
                  "sameSite": "Lax"
                },
                {
                  "name": "expired_cookie",
                  "value": "expired",
                  "domain": ".geek2api.com",
                  "path": "/",
                  "expires": 5,
                  "httpOnly": false,
                  "secure": true,
                  "sameSite": "Lax"
                }
              ],
              "origins": [
                {
                  "origin": "https://hk.geek2api.com",
                  "localStorage": [
                    {"name": "auth_token", "value": "cached-token"}
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    private fun modelPlazaResponse(): String {
        return """
            {
              "code": 0,
              "message": "ok",
              "data": [
                {
                  "platform": "anthropic",
                  "models": [
                    {"name": "claude-opus-5", "pricing": {"official_pricing": "ignored"}},
                    {"name": "claude-fable-5"}
                  ],
                  "groups": [
                    {"id": 5, "name": "Claude - kiro企业版", "rate_multiplier": 0.8}
                  ]
                },
                {
                  "platform": "anthropic",
                  "models": [
                    {"name": "claude-fable-5"},
                    {"name": "claude-haiku-4-5"}
                  ],
                  "groups": [
                    {"id": 13, "name": "稳定Claude", "rate_multiplier": 0.8}
                  ]
                },
                {
                  "platform": "openai",
                  "models": [
                    {"name": "gpt-5.6-sol"}
                  ],
                  "groups": [
                    {"id": 2, "name": "OpenAI", "rate_multiplier": 1.0}
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
