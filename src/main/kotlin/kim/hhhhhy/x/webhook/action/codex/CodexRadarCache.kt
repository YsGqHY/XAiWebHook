package kim.hhhhhy.x.webhook.action.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Codex Radar 的分数快照缓存。
 *
 * 缓存只保存已经通过最终数值解析的有效分数；超时后的 partial 快照也只保留有效条目，
 * 不保存 Cookie、Token、占位符或页面原文。
 */
internal object CodexRadarCache {
    const val CURRENT_VERSION: Int = 1
    const val DEFAULT_FILE_NAME: String = "codex-radar-cache.json"
    const val MAX_CACHE_BYTES: Long = 1L * 1024L * 1024L

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = false
    }

    internal data class Entry(
        val snapshot: CodexRadarSnapshot,
        val savedAtEpochMillis: Long
    ) {
        fun isFresh(nowEpochMillis: Long, ttlMillis: Long): Boolean {
            if (ttlMillis < 0L) return false
            val age = (nowEpochMillis - savedAtEpochMillis).coerceAtLeast(0L)
            return age <= ttlMillis
        }
    }

    fun cacheKey(pageUrl: String): String {
        return sha256("codex-radar-v$CURRENT_VERSION|${pageUrl.trim()}")
    }

    fun resolvePath(configured: String, configDirectory: Path): Path {
        val trimmed = configured.trim()
        require(trimmed.isNotEmpty()) { "Codex Radar cache file must not be blank" }
        val configuredPath = Paths.get(trimmed)
        if (configuredPath.isAbsolute) return configuredPath.normalize()
        val baseDirectory = configDirectory.toAbsolutePath().normalize()
        val resolved = baseDirectory.resolve(configuredPath).normalize()
        require(resolved.startsWith(baseDirectory)) {
            "Codex Radar cache file must stay inside the plugin config folder when relative"
        }
        return resolved
    }

    fun read(
        path: Path,
        expectedPageUrl: String,
        expectedCacheKey: String = cacheKey(expectedPageUrl),
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Entry? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized)) return null
        val size = runCatching { Files.size(normalized) }.getOrNull() ?: return null
        if (size <= 0L || size > MAX_CACHE_BYTES) return null
        val envelope = runCatching {
            val text = Files.readString(normalized, StandardCharsets.UTF_8)
            json.decodeFromString<CacheEnvelope>(text)
        }.getOrNull() ?: return null
        if (envelope.version != CURRENT_VERSION) return null
        if (envelope.pageUrl != expectedPageUrl || envelope.cacheKey != expectedCacheKey) return null
        if (envelope.savedAtEpochMillis <= 0L) return null
        if (envelope.savedAtEpochMillis > nowEpochMillis + FUTURE_TIMESTAMP_TOLERANCE_MILLIS) return null
        val snapshot = runCatching { envelope.toSnapshot() }.getOrNull() ?: return null
        return Entry(snapshot = snapshot, savedAtEpochMillis = envelope.savedAtEpochMillis)
    }

    fun write(
        path: Path,
        pageUrl: String,
        snapshot: CodexRadarSnapshot,
        savedAtEpochMillis: Long = System.currentTimeMillis()
    ): Unit {
        validateSnapshot(snapshot)
        require(savedAtEpochMillis > 0L) { "Codex Radar cache timestamp must be positive" }
        val envelope = CacheEnvelope.fromSnapshot(
            pageUrl = pageUrl,
            cacheKey = cacheKey(pageUrl),
            savedAtEpochMillis = savedAtEpochMillis,
            snapshot = snapshot
        )
        val encoded = json.encodeToString(envelope)
        require(encoded.toByteArray(StandardCharsets.UTF_8).size.toLong() <= MAX_CACHE_BYTES) {
            "Codex Radar cache exceeds the maximum size"
        }

        val normalized = path.toAbsolutePath().normalize()
        val parent = normalized.parent ?: error("Codex Radar cache file has no parent directory")
        Files.createDirectories(parent)
        val temporary = parent.resolve(".${normalized.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.writeString(
                temporary,
                encoded,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            try {
                Files.move(
                    temporary,
                    normalized,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary,
                    normalized,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun validateSnapshot(snapshot: CodexRadarSnapshot): Unit {
        require(snapshot.radarScores.isNotEmpty()) { "Codex Radar cache requires radar scores" }
        require(snapshot.communityScores.isNotEmpty()) { "Codex Radar cache requires community scores" }
        require(snapshot.generatedAtText.isNotBlank()) { "Codex Radar cache generatedAtText must not be blank" }
        snapshot.radarScores.forEach { score -> validateScore(score, 150.0) }
        snapshot.communityScores.forEach { score -> validateScore(score, 10.0) }
    }

    private fun validateScore(score: CodexRadarScore, maximum: Double): Unit {
        require(score.model.isNotBlank()) { "Codex Radar cache score model must not be blank" }
        require(score.effort.isNotBlank()) { "Codex Radar cache score effort must not be blank" }
        require(score.value.isFinite() && score.value in 0.0..maximum) {
            "Codex Radar cache score is outside 0..$maximum"
        }
        require(score.ratingCount == null || score.ratingCount >= 0) {
            "Codex Radar cache rating count must not be negative"
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private const val FUTURE_TIMESTAMP_TOLERANCE_MILLIS: Long = 5L * 60L * 1000L

    @Serializable
    private data class CacheEnvelope(
        val version: Int,
        val pageUrl: String,
        val cacheKey: String,
        val savedAtEpochMillis: Long,
        val snapshot: SnapshotEnvelope
    ) {
        fun toSnapshot(): CodexRadarSnapshot {
            val result = snapshot.toSnapshot()
            validateSnapshot(result)
            return result
        }

        companion object {
            fun fromSnapshot(
                pageUrl: String,
                cacheKey: String,
                savedAtEpochMillis: Long,
                snapshot: CodexRadarSnapshot
            ): CacheEnvelope = CacheEnvelope(
                version = CURRENT_VERSION,
                pageUrl = pageUrl,
                cacheKey = cacheKey,
                savedAtEpochMillis = savedAtEpochMillis,
                snapshot = SnapshotEnvelope.fromSnapshot(snapshot)
            )
        }
    }

    @Serializable
    private data class SnapshotEnvelope(
        val radarScores: List<ScoreEnvelope>,
        val communityScores: List<ScoreEnvelope>,
        val generatedAtText: String,
        val partial: Boolean = false
    ) {
        fun toSnapshot(): CodexRadarSnapshot = CodexRadarSnapshot(
            radarScores = radarScores.map(ScoreEnvelope::toScore),
            communityScores = communityScores.map(ScoreEnvelope::toScore),
            generatedAtText = generatedAtText,
            partial = partial
        )

        companion object {
            fun fromSnapshot(snapshot: CodexRadarSnapshot): SnapshotEnvelope = SnapshotEnvelope(
                radarScores = snapshot.radarScores.map(ScoreEnvelope::fromScore),
                communityScores = snapshot.communityScores.map(ScoreEnvelope::fromScore),
                generatedAtText = snapshot.generatedAtText,
                partial = snapshot.partial
            )
        }
    }

    @Serializable
    private data class ScoreEnvelope(
        val model: String,
        val effort: String,
        val value: Double,
        val ratingCount: Int? = null
    ) {
        fun toScore(): CodexRadarScore = CodexRadarScore(
            model = model,
            effort = effort,
            value = value,
            ratingCount = ratingCount
        )

        companion object {
            fun fromScore(score: CodexRadarScore): ScoreEnvelope = ScoreEnvelope(
                model = score.model,
                effort = score.effort,
                value = score.value,
                ratingCount = score.ratingCount
            )
        }
    }
}
