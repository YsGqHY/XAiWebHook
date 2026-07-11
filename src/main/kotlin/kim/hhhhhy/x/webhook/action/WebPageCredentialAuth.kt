package kim.hhhhhy.x.webhook.action

import com.microsoft.playwright.APIRequest
import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.options.RequestOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object WebPageCredentialAuthClient {
    fun login(
        apiRequest: APIRequest,
        auth: ResolvedBrowserAuth,
        timeoutMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): BrowserTokenPair {
        val login = auth.spec.login ?: error("credential login configuration is missing")
        val credentials = auth.credentials ?: error("resolved browser credentials are missing")
        val requestContext = apiRequest.newContext(
            APIRequest.NewContextOptions()
                .setFailOnStatusCode(false)
                .setMaxRedirects(0)
                .setTimeout(timeoutMillis.toDouble())
        )
        try {
            val firstResponse = postJson(
                requestContext = requestContext,
                url = login.url,
                body = buildJsonObject {
                    put("email", credentials.email)
                    put("password", credentials.password)
                },
                operation = "credential login",
                timeoutMillis = timeoutMillis
            )
            val tokenResponse = if (firstResponse.boolean("requires_2fa")) {
                val tempToken = firstResponse.string("temp_token")
                    ?: error("credential login requires 2FA but temp_token is missing")
                val totpCode = resolveTotpCode(credentials)
                postJson(
                    requestContext = requestContext,
                    url = login.twoFactorUrl,
                    body = buildJsonObject {
                        put("temp_token", tempToken)
                        put("totp_code", totpCode)
                    },
                    operation = "credential 2FA",
                    timeoutMillis = timeoutMillis
                )
            } else {
                firstResponse
            }
            return parseLoginTokenPair(tokenResponse, nowMillis)
        } finally {
            requestContext.dispose()
        }
    }

    fun refresh(
        apiRequest: APIRequest,
        auth: ResolvedBrowserAuth,
        timeoutMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): BrowserTokenPair {
        val login = auth.spec.login ?: error("credential refresh configuration is missing")
        val previous = auth.tokenPair ?: error("credential refresh token pair is missing")
        val refreshToken = previous.refreshToken
            ?: error("credential refresh token is missing")
        val requestContext = apiRequest.newContext(
            APIRequest.NewContextOptions()
                .setFailOnStatusCode(false)
                .setMaxRedirects(0)
                .setTimeout(timeoutMillis.toDouble())
        )
        try {
            val response = postJson(
                requestContext = requestContext,
                url = login.refreshUrl,
                body = buildJsonObject {
                    put("refresh_token", refreshToken)
                },
                operation = "credential refresh",
                timeoutMillis = timeoutMillis
            )
            return parseRefreshTokenPair(response, previous, nowMillis)
        } finally {
            requestContext.dispose()
        }
    }

    internal fun generateTotp(
        base32Secret: String,
        epochSeconds: Long = Instant.now().epochSecond,
        digits: Int = 6,
        periodSeconds: Long = 30L
    ): String {
        require(digits in 6..8) { "TOTP digits must be between 6 and 8" }
        require(periodSeconds > 0L) { "TOTP period must be positive" }
        val secret = decodeBase32(base32Secret)
        require(secret.isNotEmpty()) { "TOTP secret must not be empty" }
        val counter = epochSeconds / periodSeconds
        val counterBytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val digest = mac.doFinal(counterBytes)
        val offset = digest.last().toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        val modulus = POWERS_OF_TEN[digits]
        return (binary % modulus).toString().padStart(digits, '0')
    }

    private fun resolveTotpCode(credentials: ResolvedBrowserCredentials): String {
        val code = when {
            credentials.totpSecret != null -> generateTotp(credentials.totpSecret)
            credentials.totpCode != null -> credentials.totpCode.trim()
            else -> error(
                "credential login requires 2FA but no totp_secret_env or totp_code_env value is available"
            )
        }
        require(Regex("""^\d{6}$""").matches(code)) {
            "credential 2FA code must contain exactly 6 digits"
        }
        return code
    }

    private fun postJson(
        requestContext: com.microsoft.playwright.APIRequestContext,
        url: String,
        body: JsonObject,
        operation: String,
        timeoutMillis: Long
    ): JsonObject {
        val response = try {
            requestContext.post(
                url,
                RequestOptions.create()
                    .setHeader("Content-Type", "application/json")
                    .setData(body.toString())
                    .setFailOnStatusCode(false)
                    .setMaxRedirects(0)
                    .setTimeout(timeoutMillis.toDouble())
            )
        } catch (error: Throwable) {
            throw IllegalStateException(
                "$operation request failed: ${summarizeError(error)}",
                error
            )
        }
        return response.useResponse { parseApiResponse(it, operation) }
    }

    private fun parseApiResponse(response: APIResponse, operation: String): JsonObject {
        return parseApiResponseBody(response.status(), response.text(), operation)
    }

    internal fun parseApiResponseBody(
        status: Int,
        responseBody: String,
        operation: String
    ): JsonObject {
        val root = runCatching {
            Json.parseToJsonElement(responseBody) as? JsonObject
        }.getOrNull()
        if (status !in 200..299) {
            throw apiFailure(operation, status, root)
        }
        if (root == null) {
            throw IllegalStateException("$operation failed: HTTP $status (invalid JSON response)")
        }
        val code = (root["code"] as? JsonPrimitive)?.longOrNull
        if (code != null) {
            if (code != 0L) {
                throw apiFailure(operation, status, root)
            }
            return root["data"] as? JsonObject
                ?: error("$operation response data is missing")
        }
        return root
    }

    private fun apiFailure(operation: String, status: Int, body: JsonObject?): IllegalStateException {
        val reason = body?.string("reason")
        val message = body?.string("message")
        val detail = when {
            reason != null && message != null -> "$reason: $message"
            reason != null -> reason
            message != null -> message
            else -> null
        }
        return IllegalStateException(
            buildString {
                append("$operation failed: HTTP $status")
                if (detail != null) append(" (${sanitizeDetail(detail)})")
            }
        )
    }

    private fun parseLoginTokenPair(payload: JsonObject, nowMillis: Long): BrowserTokenPair {
        val user = payload["user"] as? JsonObject
            ?: error("credential login response user is missing")
        return parseTokenPair(payload, user, nowMillis, "credential login")
    }

    private fun parseRefreshTokenPair(
        payload: JsonObject,
        previous: BrowserTokenPair,
        nowMillis: Long
    ): BrowserTokenPair {
        return parseTokenPair(payload, previous.user, nowMillis, "credential refresh")
    }

    private fun parseTokenPair(
        payload: JsonObject,
        user: JsonObject,
        nowMillis: Long,
        operation: String
    ): BrowserTokenPair {
        val accessToken = payload.string("access_token")
            ?: error("$operation response access_token is missing")
        val refreshToken = payload.string("refresh_token")
            ?: error("$operation response refresh_token is missing")
        val expiresIn = (payload["expires_in"] as? JsonPrimitive)?.longOrNull
            ?: error("$operation response expires_in is missing")
        require(expiresIn > 0L) { "$operation response expires_in must be positive" }
        val expiresAtMillis = nowMillis + Math.multiplyExact(expiresIn, 1_000L)
        return BrowserTokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = expiresAtMillis,
            tokenType = payload.string("token_type") ?: "Bearer",
            user = user
        )
    }

    private fun decodeBase32(value: String): ByteArray {
        val normalized = value.uppercase()
            .filterNot { it.isWhitespace() || it == '-' || it == '=' }
        require(normalized.isNotEmpty()) { "TOTP secret must not be empty" }
        val output = ArrayList<Byte>((normalized.length * 5) / 8)
        var buffer = 0
        var bits = 0
        normalized.forEach { character ->
            val index = BASE32_ALPHABET.indexOf(character)
            require(index >= 0) { "TOTP secret is not valid Base32" }
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output += ((buffer shr bits) and 0xff).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return output.toByteArray()
    }

    private fun JsonObject.string(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
    }

    private fun JsonObject.boolean(key: String): Boolean {
        return (this[key] as? JsonPrimitive)?.booleanOrNull == true
    }

    private fun sanitizeDetail(value: String): String {
        return value.replace(Regex("\\s+"), " ").trim().take(240)
    }

    private fun summarizeError(error: Throwable): String {
        return (error.message ?: error::class.simpleName ?: "unknown error")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(300)
    }

    private inline fun <T> APIResponse.useResponse(block: (APIResponse) -> T): T {
        return try {
            block(this)
        } finally {
            dispose()
        }
    }

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val POWERS_OF_TEN = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)
}

internal data class BrowserLoginAuth(
    val url: String,
    val email: String?,
    val emailEnv: String?,
    val password: String?,
    val passwordEnv: String?,
    val twoFactorUrl: String,
    val totpSecretEnv: String?,
    val totpCodeEnv: String?,
    val refreshUrl: String,
    val refreshBeforeExpirySeconds: Long,
    val retryCooldownMillis: Long
)

internal data class ResolvedBrowserCredentials(
    val email: String,
    val password: String,
    val totpSecret: String?,
    val totpCode: String?
)

internal data class BrowserTokenPair(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long?,
    val tokenType: String,
    val user: JsonObject
)
