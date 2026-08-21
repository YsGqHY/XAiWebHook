package kim.hhhhhy.x.webhook.util

import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class HttpProxySupportTest {
    @Test
    fun `blank proxy should use direct connection`() {
        assertEquals("", HttpProxySupport.normalize(null))
        assertEquals("", HttpProxySupport.normalize("   "))
        assertEquals("direct", HttpProxySupport.describe(""))
        assertEquals(null, HttpProxySupport.ktorProxy(""))
        assertEquals(null, HttpProxySupport.playwrightProxy(""))
    }

    @Test
    fun `proxy should normalize schemes ports and encoded credentials`() {
        assertEquals("http://proxy.example:80", HttpProxySupport.normalize("HTTP://proxy.example"))
        assertEquals("https://proxy.example:443", HttpProxySupport.normalize("https://proxy.example/"))

        val normalized = HttpProxySupport.normalize(
            "https://user+name:p%40ss%3Aword@proxy.example:8443"
        )
        val proxy = HttpProxySupport.parse(normalized)

        assertEquals("https://proxy.example:8443", proxy.server)
        assertEquals("proxy.example", proxy.host)
        assertEquals(8443, proxy.port)
        assertEquals("user+name", proxy.username)
        assertEquals("p@ss:word", proxy.password)
        assertEquals("https://proxy.example:8443 (authenticated)", HttpProxySupport.describe(normalized))
        assertFalse(HttpProxySupport.describe(normalized).contains("p@ss"))
    }

    @Test
    fun `proxy should convert to playwright and java clients without exposing credentials in server`() {
        val raw = "http://proxy-user:proxy-pass@127.0.0.1:7890"
        val playwright = assertNotNull(HttpProxySupport.playwrightProxy(raw))
        assertEquals("http://127.0.0.1:7890", playwright.server)
        assertEquals("proxy-user", playwright.username)
        assertEquals("proxy-pass", playwright.password)

        val client = HttpProxySupport.configureJava(HttpClient.newBuilder(), raw).build()
        val selected = client.proxy().orElseThrow().select(URI("https://example.invalid")).single()
        val address = selected.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(7890, address.port)
        assertTrue(client.authenticator().isPresent)
    }

    @Test
    fun `invalid proxy urls should be rejected`() {
        assertFailsWith<IllegalArgumentException> { HttpProxySupport.normalize("socks5://127.0.0.1:1080") }
        assertFailsWith<IllegalArgumentException> { HttpProxySupport.normalize("http://127.0.0.1:7890/path") }
        assertFailsWith<IllegalArgumentException> { HttpProxySupport.normalize("http://127.0.0.1:7890?x=1") }
        assertFailsWith<IllegalArgumentException> { HttpProxySupport.normalize("http://:password@127.0.0.1:7890") }
        assertFailsWith<IllegalArgumentException> { HttpProxySupport.normalize("http://127.0.0.1:0") }
    }
}
