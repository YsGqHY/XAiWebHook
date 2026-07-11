package kim.hhhhhy.x.webhook.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

internal data class CachedBrowserSession(
    val storageState: String,
    val tokenType: String
)

internal class BrowserSessionCache(
    private val directory: Path
) {
    fun load(sessionKey: String, auth: ResolvedBrowserAuth?): CachedBrowserSession? {
        val path = cachePath(sessionKey)
        if (!Files.isRegularFile(path)) return null
        require(Files.size(path) <= MAX_CACHE_BYTES) { "browser session cache is too large" }

        val envelope = parseObject(Files.readAllBytes(path).toString(StandardCharsets.UTF_8))
        val version = (envelope["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        require(version == CACHE_VERSION) { "browser session cache version is unsupported" }

        val expectedFingerprint = authFingerprint(auth)
        val storedFingerprint = (envelope["auth_fingerprint"] as? JsonPrimitive)?.contentOrNull
        if (storedFingerprint != expectedFingerprint) {
            delete(sessionKey)
            return null
        }

        val storageState = envelope["storage_state"] as? JsonObject
            ?: error("browser session cache storage_state is missing")
        validateStorageState(storageState)
        val tokenType = (envelope["token_type"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.ifBlank { null }
            ?: "Bearer"
        return CachedBrowserSession(storageState.toString(), tokenType)
    }

    fun save(sessionKey: String, auth: ResolvedBrowserAuth?, storageState: String): Unit {
        val storageStateObject = parseObject(storageState)
        validateStorageState(storageStateObject)
        val envelope = buildJsonObject {
            put("version", CACHE_VERSION)
            put("auth_fingerprint", authFingerprint(auth))
            put("saved_at_millis", System.currentTimeMillis())
            put("token_type", auth?.tokenPair?.tokenType ?: "Bearer")
            put("storage_state", storageStateObject)
        }
        val bytes = envelope.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size.toLong() <= MAX_CACHE_BYTES) { "browser session cache is too large" }

        Files.createDirectories(directory)
        val target = cachePath(sessionKey)
        val temporary = Files.createTempFile(directory, target.fileName.toString(), ".tmp")
        try {
            Files.write(temporary, bytes)
            restrictPermissions(temporary)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictPermissions(target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun delete(sessionKey: String): Unit {
        Files.deleteIfExists(cachePath(sessionKey))
    }

    internal fun cachePath(sessionKey: String): Path {
        return directory.resolve(sha256(sessionKey) + ".json")
    }

    private fun parseObject(value: String): JsonObject {
        return runCatching { Json.parseToJsonElement(value) as? JsonObject }
            .getOrNull()
            ?: error("browser session cache is not valid JSON")
    }

    private fun validateStorageState(storageState: JsonObject): Unit {
        require(storageState["cookies"] is JsonArray) {
            "browser session cache cookies must be an array"
        }
        require(storageState["origins"] is JsonArray) {
            "browser session cache origins must be an array"
        }
    }

    private fun authFingerprint(auth: ResolvedBrowserAuth?): String {
        if (auth == null) return sha256("no-auth")
        val material = buildString {
            append(auth.spec.toString())
            when {
                auth.spec.login != null -> append('|').append(auth.credentials.toString())
                auth.spec.cliBridge != null -> Unit
                else -> append('|').append(auth.token.orEmpty())
            }
        }
        return sha256(material)
    }

    private fun restrictPermissions(path: Path): Unit {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }
        runCatching {
            val file = path.toFile()
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val CACHE_VERSION: Int = 1
        const val MAX_CACHE_BYTES: Long = 5L * 1_024L * 1_024L
    }
}

internal fun resolveBrowserSessionCacheDirectory(configured: String, configFolder: Path): Path {
    val configuredPath = Paths.get(configured.trim())
    require(configuredPath.toString().isNotBlank()) { "browser.session_cache_dir must not be blank" }
    if (configuredPath.isAbsolute) return configuredPath.normalize()

    val baseDirectory = configFolder.toAbsolutePath().normalize()
    val resolved = baseDirectory.resolve(configuredPath).normalize()
    require(resolved.startsWith(baseDirectory)) {
        "browser.session_cache_dir must stay inside the plugin config folder when relative"
    }
    return resolved
}
