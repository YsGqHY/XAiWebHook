package kim.hhhhhy.x.webhook.polymarket

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class PolymarketClientTest {
    @AfterEach
    fun closeClient(): Unit {
        PolymarketClient.close()
    }

    @Test
    fun `jdk client should encode query parameters and parse gamma and clob responses`(): Unit = runBlocking {
        val queries = mutableMapOf<String, String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/markets") { exchange ->
            synchronized(queries) { queries["markets"] = exchange.requestURI.rawQuery }
            exchange.respondJson(
                """
                [{
                  "id":"42",
                  "question":"Will this test pass?",
                  "outcomes":"[\"Yes\",\"No\"]",
                  "outcomePrices":"[\"0.65\",\"0.35\"]",
                  "clobTokenIds":"[\"yes-token\",\"no-token\"]"
                }]
                """.trimIndent()
            )
        }
        server.createContext("/prices-history") { exchange ->
            synchronized(queries) { queries["history"] = exchange.requestURI.rawQuery }
            exchange.respondJson("""{"history":[{"t":1787270400,"p":0.65,"v":123.0}]}""")
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        try {
            val markets = PolymarketClient.searchMarkets(
                gammaApiBaseUrl = baseUrl,
                timeoutMillis = 5_000L,
                limit = 7,
                offset = 3,
                locale = "zh-CN"
            )
            val history = PolymarketClient.getPriceHistory(
                clobApiBaseUrl = baseUrl,
                timeoutMillis = 5_000L,
                assetId = "yes token/+?",
                interval = "1 day"
            )

            assertEquals("42", markets.single().id)
            assertEquals(0.65, history.single().price)
            assertEquals("limit=7&offset=3&closed=false&locale=zh-CN", queries["markets"])
            assertEquals("market=yes%20token%2F%2B%3F&interval=1%20day", queries["history"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `public search should encode model query and parse events`(): Unit = runBlocking {
        var query: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/public-search") { exchange ->
            query = exchange.requestURI.rawQuery
            exchange.respondJson(
                """
                {
                  "events":[
                    {
                      "id":"36307",
                      "slug":"gpt-6-released-by",
                      "title":"GPT-6 released by...?",
                      "active":true,
                      "closed":false,
                      "markets":[
                        {
                          "id":"2850825",
                          "question":"Will GPT-6 be released by August 31, 2026?",
                          "active":true,
                          "closed":false
                        }
                      ]
                    }
                  ],
                  "pagination":{"hasMore":false,"totalResults":1}
                }
                """.trimIndent()
            )
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        try {
            val events = PolymarketClient.searchEvents(
                gammaApiBaseUrl = baseUrl,
                timeoutMillis = 5_000L,
                query = "GPT-6 / Claude",
                limit = 12
            )

            assertEquals("gpt-6-released-by", events.single().slug)
            assertEquals(1, events.single().markets.size)
            assertEquals(
                "q=GPT-6%20%2F%20Claude&events_status=active&limit_per_type=12&search_profiles=false&search_tags=false",
                query
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `event endpoint should encode slug and parse nested markets`(): Unit = runBlocking {
        var query: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/events/slug/gpt-6-released-by") { exchange ->
            query = exchange.requestURI.rawQuery
            exchange.respondJson(
                """
                {
                  "id":"36307",
                  "slug":"gpt-6-released-by",
                  "title":"GPT-6 released by...",
                  "markets":[
                    {
                      "id":"2850825",
                      "slug":"will-gpt-6-be-released-by-august-31-2026-778",
                      "question":"Will GPT-6 be released by August 31, 2026?",
                      "outcomes":"[\"Yes\",\"No\"]",
                      "outcomePrices":"[\"0.0205\",\"0.9795\"]",
                      "closed":false,
                      "active":true
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        try {
            val event = PolymarketClient.getEventBySlug(
                gammaApiBaseUrl = baseUrl,
                timeoutMillis = 5_000L,
                slug = "gpt-6-released-by",
                locale = "zh-CN"
            )
            assertEquals("gpt-6-released-by", event?.slug)
            assertEquals(1, event?.markets?.size)
            assertEquals("locale=zh-CN", query)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `transient network failure should retry once with a fresh client`(): Unit = runBlocking {
        var attempts = 0
        var invalidations = 0

        val result = PolymarketClient.executeWithNetworkRetry(
            operation = "test request",
            target = URI("https://gamma-api.polymarket.com/markets"),
            proxyUrl = "http://user:secret@127.0.0.1:7890",
            retryDelayMillis = 0L,
            onRetry = { invalidations++ }
        ) {
            attempts++
            if (attempts == 1) throw IOException("TLS channel closed")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1, invalidations)
    }

    @Test
    fun `repeated network failure should stop after two attempts and redact proxy credentials`() {
        var attempts = 0
        val error = assertFailsWith<IllegalStateException> {
            runBlocking {
                PolymarketClient.executeWithNetworkRetry(
                    operation = "test request",
                    target = URI("https://gamma-api.polymarket.com/markets"),
                    proxyUrl = "http://user:secret@127.0.0.1:7890",
                    retryDelayMillis = 0L
                ) {
                    attempts++
                    throw IOException("TLS channel closed")
                }
            }
        }

        assertEquals(2, attempts)
        assertTrue(error.message.orEmpty().contains("after 2 attempts"))
        assertTrue(error.message.orEmpty().contains("http://127.0.0.1:7890 (authenticated)"))
        assertFalse(error.message.orEmpty().contains("secret"))
    }

    @Test
    fun `non-network and cancellation failures should not retry`() {
        var deterministicAttempts = 0
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                PolymarketClient.executeWithNetworkRetry(
                    operation = "test request",
                    target = URI("https://gamma-api.polymarket.com/markets"),
                    proxyUrl = "",
                    retryDelayMillis = 0L
                ) {
                    deterministicAttempts++
                    throw IllegalArgumentException("bad request")
                }
            }
        }
        assertEquals(1, deterministicAttempts)

        var cancelledAttempts = 0
        assertFailsWith<CancellationException> {
            runBlocking {
                PolymarketClient.executeWithNetworkRetry(
                    operation = "test request",
                    target = URI("https://gamma-api.polymarket.com/markets"),
                    proxyUrl = "",
                    retryDelayMillis = 0L
                ) {
                    cancelledAttempts++
                    throw CancellationException("stopped")
                }
            }
        }
        assertEquals(1, cancelledAttempts)
    }

    @Test
    fun `close should cancel in-flight requests without retrying`(): Unit = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/markets") { exchange ->
            requests.incrementAndGet()
            requestStarted.countDown()
            releaseResponse.await(10, TimeUnit.SECONDS)
            runCatching { exchange.respondJson("[]") }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val request = async(Dispatchers.IO) {
            PolymarketClient.searchMarkets(
                gammaApiBaseUrl = baseUrl,
                timeoutMillis = 30_000L
            )
        }

        try {
            val started = withContext(Dispatchers.IO) { requestStarted.await(5, TimeUnit.SECONDS) }
            assertTrue(started)
            PolymarketClient.close()
            val failure = runCatching { withTimeout(5_000L) { request.await() } }.exceptionOrNull()
            assertIs<CancellationException>(failure)
            assertFalse(failure is TimeoutCancellationException)
            assertEquals(1, requests.get())
        } finally {
            releaseResponse.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `close should allow a later client to be recreated`(): Unit = runBlocking {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/markets") { exchange ->
            requests.incrementAndGet()
            exchange.respondJson("[]")
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        try {
            PolymarketClient.searchMarkets(baseUrl, 5_000L)
            PolymarketClient.close()
            PolymarketClient.searchMarkets(baseUrl, 5_000L)
            assertEquals(2, requests.get())
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respondJson(body: String): Unit {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { output -> output.write(bytes) }
        close()
    }
}
