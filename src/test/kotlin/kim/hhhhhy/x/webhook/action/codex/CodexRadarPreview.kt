package kim.hhhhhy.x.webhook.action.codex

import kim.hhhhhy.x.webhook.action.HtmlImageRenderer
import kim.hhhhhy.x.webhook.action.WebPageScreenshotAction
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kotlinx.coroutines.runBlocking
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** 手工预览工具：通过浏览器抓取线上页面，并把双分数图表写到本地 PNG。 */
internal object CodexRadarPreview {
    @JvmStatic
    fun main(args: Array<String>) {
        val output = File(args.firstOrNull() ?: "build/codex-radar-preview.png")
        val cacheDirectory = Paths.get(
            System.getProperty("codex.cache.dir") ?: "build/codex-radar-preview-cache"
        ).toAbsolutePath().normalize()
        val config = loadDefaultConfig()
        val baseAction = config.actions["codex-degradation-report"]
            ?: error("default config does not define codex-degradation-report")
        val action = baseAction.copy(
            params = baseAction.params.toMutableMap().apply {
                System.getProperty("codex.proxy")?.let { proxy -> put("proxy", proxy) }
                System.getProperty("codex.cache.file")?.let { file ->
                    val original = (get("cache") as? Map<*, *>)
                        .orEmpty()
                        .mapNotNull { (key, value) -> key?.toString()?.let { it to value } }
                        .toMap()
                        .toMutableMap()
                    original["file"] = file
                    put("cache", original)
                }
            }
        )
        val context = ExecutionContext(config)

        runBlocking {
            val spec = CodexRadarAction.fetchSpec(action) { value -> value?.toString().orEmpty() }
            val cachePath = CodexRadarCache.resolvePath(spec.cache.file, cacheDirectory)
            if (System.getProperty("codex.cache.clear").equals("true", ignoreCase = true)) {
                Files.deleteIfExists(cachePath)
                println("cache=cleared path=${cachePath.toAbsolutePath()}")
            }
            val before = if (spec.cache.enabled) {
                CodexRadarCache.read(cachePath, spec.pageUrl)
            } else {
                null
            }
            val cacheState = when {
                !spec.cache.enabled -> "disabled"
                before == null -> "miss"
                before.isFresh(System.currentTimeMillis(), spec.cache.ttlMillis) -> "hit/fresh"
                else -> "stale"
            }
            println("cache=$cacheState path=${cachePath.toAbsolutePath()}")

            val snapshot = CodexRadarAction.fetchSnapshot(spec, context, cacheDirectory)
            val after = if (spec.cache.enabled) CodexRadarCache.read(cachePath, spec.pageUrl) else null
            if (before != null && after != null && after.savedAtEpochMillis > before.savedAtEpochMillis) {
                println("cache=fresh refresh savedAt=${after.savedAtEpochMillis}")
            }
            CodexRadarAction.lastRefreshError?.let { println("cacheRefreshError=$it") }
            val chart = CodexRadarChart(CodexRadarOptionsParser.chartOptions(action.params))
            val html = chart.render(snapshot)
            output.parentFile?.mkdirs()
            output.writeBytes(HtmlImageRenderer.render(html))
            println(
                "radar=${snapshot.radarScores.size} community=${snapshot.communityScores.size} " +
                    "chart=${output.absolutePath}"
            )
        }
        CodexRadarAction.close()
        WebPageScreenshotAction.close()
    }

    private fun loadDefaultConfig() = CodexRadarReportTest::class.java
        .getResourceAsStream("/webhook_config.yml")
        .use { input ->
            requireNotNull(input)
            @Suppress("UNCHECKED_CAST")
            val root = Yaml().load<Map<String, Any?>>(input)
            WebHookConfig.parseConfig(root)
        }
}
