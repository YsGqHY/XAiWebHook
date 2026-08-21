package kim.hhhhhy.x.webhook.util

import com.microsoft.playwright.options.Proxy
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig
import io.ktor.http.Url
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets

internal data class HttpProxySpec(
    val url: String,
    val server: String,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?
)

internal object HttpProxySupport {
    /** 空字符串表示直连；非空值必须是带主机的 HTTP(S) 代理 URL。 */
    fun normalize(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return parse(value).url
    }

    fun describe(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return "direct"
        val proxy = parse(value)
        return if (proxy.username == null) proxy.server else "${proxy.server} (authenticated)"
    }

    fun parse(raw: String?): HttpProxySpec {
        val value = raw?.trim().orEmpty()
        require(value.isNotEmpty()) { "proxy URL must not be blank" }
        val uri = runCatching { URI(value) }
            .getOrElse { error -> throw IllegalArgumentException("invalid proxy URL", error) }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "proxy URL scheme must be http or https"
        }
        val host = uri.host?.trim().orEmpty()
        require(host.isNotEmpty()) { "proxy URL host is required" }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
            "proxy URL must not contain a path"
        }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "proxy URL must not contain query or fragment"
        }
        require(uri.port != 0) { "proxy URL port must be between 1 and 65535" }
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        val rawUserInfo = uri.rawUserInfo
        val credentials = rawUserInfo?.split(':', limit = 2).orEmpty()
        val username = credentials.getOrNull(0)?.let(::decode)?.ifBlank { null }
        require(rawUserInfo == null || username != null) { "proxy username must not be blank" }
        val password = credentials.getOrNull(1)?.let(::decode)
        val normalizedUserInfo = rawUserInfo?.let {
            buildString {
                append(username)
                if (credentials.size > 1) append(':').append(password.orEmpty())
            }
        }
        val normalizedServer = URI(scheme, null, host, port, null, null, null).toString()
        val normalizedUrl = URI(scheme, normalizedUserInfo, host, port, null, null, null).toString()
        return HttpProxySpec(
            url = normalizedUrl,
            server = normalizedServer,
            host = host,
            port = port,
            username = username,
            password = password
        )
    }

    fun ktorProxy(raw: String?): ProxyConfig? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return ProxyBuilder.http(Url(parse(value).url))
    }

    fun configureJava(builder: HttpClient.Builder, raw: String?): HttpClient.Builder {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return builder
        val proxy = parse(value)
        builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
        if (proxy.username != null) {
            val username = proxy.username
            val password = proxy.password.orEmpty().toCharArray()
            builder.authenticator(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    return if (requestorType == RequestorType.PROXY) {
                        PasswordAuthentication(username, password)
                    } else {
                        null
                    }
                }
            })
        }
        return builder
    }

    fun playwrightProxy(raw: String?): Proxy? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val proxy = parse(value)
        return Proxy(proxy.server).also { options ->
            proxy.username?.let(options::setUsername)
            proxy.password?.let(options::setPassword)
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(
        value.replace("+", "%2B"),
        StandardCharsets.UTF_8.name()
    )
}
