package kim.hhhhhy.x.webhook.action.codex

import kim.hhhhhy.x.webhook.XAiWebHook
import kim.hhhhhy.x.webhook.action.BrowserTextQueryTimeoutException
import kim.hhhhhy.x.webhook.action.WebPageScreenshotAction
import kim.hhhhhy.x.webhook.api.BrowserTextListRequest
import kim.hhhhhy.x.webhook.api.BrowserTextListResult
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.PluginConfig
import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.util.HttpProxySupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Codex Radar 页面抓取动作。
 *
 * 页面必须在同一个 Playwright Page 中完成导航、目标区块轮询和最终读取；
 * 不再直接请求站点 JSON/API，也不会把占位分数写入缓存。
 */
internal object CodexRadarAction {
    const val DEFAULT_PAGE_URL: String = "https://codexradar.com/"
    const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000L
    const val DEFAULT_MAX_WAIT_MILLIS: Long = 120_000L
    const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 1_000L
    const val DEFAULT_STABLE_POLLS: Int = 2
    const val DEFAULT_CACHE_TTL_MILLIS: Long = 900_000L
    const val DEFAULT_CACHE_REFRESH_INTERVAL_MILLIS: Long = 900_000L

    private const val DEFAULT_WAIT_UNTIL = "domcontentloaded"
    private const val PART_ID = "codex-radar-page"
    private const val ACTION_ID = "codex-radar-page-reader"
    private const val MAX_ITEMS = 256
    private const val MAX_LOGGED_SKIPPED_ROWS = 6
    private const val MAX_CACHE_TTL_MILLIS = 30L * 24L * 60L * 60L * 1000L
    private const val MAX_REFRESH_INTERVAL_MILLIS = 30L * 24L * 60L * 60L * 1000L

    /**
     * 页面目标区块。
     *
     * 站点在 2026-11 重构了首页结构，导致旧选择器全部失效、刷新一直等满超时：
     * - 旧容器 `#intelligence-efficiency` 已完全移除，雷达分改为 `#iq` 下的 `[data-chapter="iq"]`，
     *   以 `.cir-table` 表格呈现：行为模型、列为思考档位，单元格为 `button[data-cir-setting]`；
     * - 旧卡片属性 `data-efficiency-card` 不再存在；
     * - 社区体感分仍在 `#model-ratings`，但改由 `article.mr-card[data-rating-model]` 承载。
     *
     * 之后站点又把雷达分表格拆成多个 `data-cir-panel` 视图（总分/秦始皇/庞贝/鹈鹕/糖果），
     * 真分数只在总分面板里，详见 [RADAR_SCORE_PANEL_XPATH]。
     */
    const val RADAR_CONTAINER_XPATH: String = "//*[@id=\"iq\"]//*[@data-chapter=\"iq\"]"
    const val COMMUNITY_CONTAINER_XPATH: String = "//*[@id=\"model-ratings\"]"

    /**
     * 雷达分表格被站点改成了多 tab 面板：`总分 / 秦始皇 / 庞贝 / 鹈鹕 / 糖果`，
     * 每个面板各有 46 个 `button[data-cir-setting]`，合计 230 个。
     *
     * - `总分`（`[data-cir-panel="total"]`）才是 150 分制的雷达分，`strong` 里是数字；
     * - `秦始皇 / 庞贝 / 鹈鹕` 面板是画作缩略图卡（`button.cir-art-card`），`strong` 里是 100 分制画作分；
     * - `糖果` 面板是单选题对错（`strong` 里是 `✓/×`），按 22.5 分计入总分。
     *
     * 因此必须把条目收敛到总分面板：否则 230 个条目里只有 46 个是真分数，
     * 46 个 `✓/×` 会被当成非法分数，另外 138 个画作分还会与同 model|effort 的真实雷达分重名。
     */
    internal const val RADAR_SCORE_PANEL_XPATH: String =
        "//*[@data-cir-panel=\"total\" or @id=\"cir-panel-total\"]"

    internal const val RADAR_ITEMS_XPATH: String =
        "$RADAR_CONTAINER_XPATH$RADAR_SCORE_PANEL_XPATH//button[@data-cir-setting]"
    private const val COMMUNITY_ITEMS_XPATH =
        "$COMMUNITY_CONTAINER_XPATH//*[@data-rating-model]"

    // 雷达分单元格：读屏文本形如“GPT-6.1 Sol ultra · 132.79 · USD 1.38 · 2229.6 秒”，分数在 strong 里
    private const val RADAR_LABEL_SELECTOR = "span.cir-sr"
    private const val RADAR_STATUS_SELECTOR = "span.cir-cell > strong"

    // 社区分卡片：名称与 /10 平均值分别在这两个元素中
    private const val COMMUNITY_LABEL_SELECTOR = "strong.mr-editor-label"
    private const val COMMUNITY_STATUS_SELECTOR = "strong.mr-value"

    private val STATE_ATTRIBUTES = listOf(
        "data-radar-query-pending",
        "data-radar-state",
        "data-community-state",
        "data-state",
        "data-status",
        "data-ready"
    )

    private val refreshFlights = ConcurrentHashMap<String, CompletableDeferred<CodexRadarSnapshot>>()
    private val refreshScopeLock = Any()
    private val schedulerJobs = mutableListOf<Job>()
    private var refreshGeneration: Long = 0L

    @Volatile
    private var acceptingRefreshes: Boolean = true

    @Volatile
    private var refreshScope: CoroutineScope? = null

    @Volatile
    var lastRefreshError: String? = null
        private set

    @Volatile
    var lastRefreshAt: Long? = null
        private set

    fun fetchSpec(action: ActionConfig, render: (Any?) -> String): CodexRadarFetchSpec {
        val pageUrl = render(action.params["page_url"])
            .trim()
            .ifBlank { DEFAULT_PAGE_URL }
        validatePageUrl(pageUrl)

        val timeoutMillis = parseLong(
            value = render(action.params["timeout_ms"]),
            default = DEFAULT_TIMEOUT_MILLIS,
            minimum = 1_000L,
            maximum = 300_000L,
            field = "timeout_ms"
        )
        val maxWaitMillis = parseLong(
            value = render(action.params["max_wait_ms"]),
            default = DEFAULT_MAX_WAIT_MILLIS,
            minimum = 1_000L,
            maximum = DEFAULT_MAX_WAIT_MILLIS,
            field = "max_wait_ms"
        )
        val pollIntervalMillis = parseLong(
            value = render(action.params["poll_interval_ms"]),
            default = DEFAULT_POLL_INTERVAL_MILLIS,
            minimum = 200L,
            maximum = 10_000L,
            field = "poll_interval_ms"
        )
        val stablePolls = parseLong(
            value = render(action.params["stable_polls"]),
            default = DEFAULT_STABLE_POLLS.toLong(),
            minimum = 1L,
            maximum = 5L,
            field = "stable_polls"
        ).toInt()

        val rawCache = action.params["cache"]
        val cacheMap = rawCache.asMap()
        require(rawCache == null || rawCache is Map<*, *>) {
            "Codex Radar 配置错误：cache 必须是对象"
        }
        val cacheConfigured = rawCache != null
        val cache = CodexRadarCacheSpec(
            enabled = cacheConfigured && parseBoolean(
                value = render(cacheMap["enabled"]),
                default = true
            ),
            file = render(cacheMap["file"])
                .trim()
                .ifBlank { CodexRadarCache.DEFAULT_FILE_NAME },
            ttlMillis = parseLong(
                value = render(cacheMap["ttl_ms"]),
                default = DEFAULT_CACHE_TTL_MILLIS,
                minimum = 0L,
                maximum = MAX_CACHE_TTL_MILLIS,
                field = "cache.ttl_ms"
            ),
            refreshEnabled = parseBoolean(
                value = render(cacheMap["refresh_enabled"]),
                default = true
            ),
            refreshIntervalMillis = parseLong(
                value = render(cacheMap["refresh_interval_ms"]),
                default = DEFAULT_CACHE_REFRESH_INTERVAL_MILLIS,
                minimum = 1_000L,
                maximum = MAX_REFRESH_INTERVAL_MILLIS,
                field = "cache.refresh_interval_ms"
            )
        )

        return CodexRadarFetchSpec(
            pageUrl = pageUrl,
            timeoutMillis = timeoutMillis,
            waitUntil = render(action.params["wait_until"])
                .trim()
                .ifBlank { DEFAULT_WAIT_UNTIL }
                .lowercase(Locale.ROOT),
            maxWaitMillis = maxWaitMillis,
            pollIntervalMillis = pollIntervalMillis,
            stablePolls = stablePolls,
            proxyUrl = HttpProxySupport.normalize(render(action.params["proxy"])),
            cache = cache
        ).also { spec ->
            require(spec.waitUntil in setOf("commit", "domcontentloaded", "load", "networkidle")) {
                "Codex Radar 配置错误：wait_until 必须是 commit、domcontentloaded、load 或 networkidle"
            }
        }
    }

    suspend fun fetchSnapshot(
        spec: CodexRadarFetchSpec,
        context: ExecutionContext,
        cacheDirectory: Path? = null
    ): CodexRadarSnapshot {
        if (!spec.cache.enabled) {
            return fetchFreshSnapshot(spec, context)
        }

        val cachePath = resolveCachePath(spec, cacheDirectory)
        val cacheKey = CodexRadarCache.cacheKey(spec.pageUrl)
        val cached = CodexRadarCache.read(
            path = cachePath,
            expectedPageUrl = spec.pageUrl,
            expectedCacheKey = cacheKey
        )
        if (cached != null) {
            if (cached.isFresh(System.currentTimeMillis(), spec.cache.ttlMillis)) {
                WebHookDebug.log(
                    "[XAiWebHook] [Codex Radar] cache hit：fresh，" +
                        "radar=${cached.snapshot.radarScores.size}，community=${cached.snapshot.communityScores.size}"
                )
                return cached.snapshot
            }

            WebHookDebug.log(
                "[XAiWebHook] [Codex Radar] cache hit：stale，立即返回旧快照并后台刷新；" +
                    "radar=${cached.snapshot.radarScores.size}，community=${cached.snapshot.communityScores.size}"
            )
            if (spec.cache.refreshEnabled) {
                refreshFlight(
                    spec = spec,
                    context = context,
                    cachePath = cachePath,
                    cacheKey = cacheKey
                )
            }
            return cached.snapshot
        }

        WebHookDebug.log("[XAiWebHook] [Codex Radar] cache miss：进入完整页面数据等待")
        return refreshFlight(
            spec = spec,
            context = context,
            cachePath = cachePath,
            cacheKey = cacheKey
        ).await()
    }

    /**
     * WebHook 服务启动后执行一次后台预热，只抓取并写入缓存，不发送任何消息。
     * 启动预热只要求 cache.enabled=true；refresh_enabled 仅控制后续周期刷新。
     */
    fun warmupCaches(config: PluginConfig): Unit {
        val candidates = staticCodexSpecs(config).filter { spec -> spec.cache.enabled }
        if (candidates.isEmpty()) return
        val scope = runCatching { ensureRefreshScope() }.getOrElse { error ->
            CodexRadarLog.warning("Codex Radar 启动缓存预热未启动：${summarize(error)}")
            return
        }
        candidates.forEach { spec ->
            val job = scope.launch {
                runCatching {
                    refreshFlight(
                        spec = spec,
                        context = ExecutionContext(config),
                        cachePath = resolveCachePath(spec, null),
                        cacheKey = CodexRadarCache.cacheKey(spec.pageUrl)
                    ).await()
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    CodexRadarLog.warning(
                        "Codex Radar 启动缓存预热失败：${summarize(error)}；旧缓存不会被覆盖"
                    )
                }
            }
            synchronized(refreshScopeLock) { schedulerJobs += job }
        }
        WebHookDebug.log("[XAiWebHook] [Codex Radar] 已启动 ${candidates.size} 个缓存预热任务")
    }

    /** 在插件配置加载完成后启动定时刷新；包含事件模板的动作会跳过后台调度。 */
    fun startRefreshScheduler(config: PluginConfig): Unit {
        synchronized(refreshScopeLock) {
            acceptingRefreshes = true
            refreshGeneration++
        }
        stopRefreshSchedulerInternal()
        val candidates = staticCodexSpecs(config)
            .filter { spec -> spec.cache.enabled && spec.cache.refreshEnabled }
        if (candidates.isEmpty()) return

        val scope = synchronized(refreshScopeLock) {
            val created = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            refreshScope = created
            created
        }
        candidates.forEach { spec ->
            val job = scope.launch {
                while (isActive) {
                    delay(spec.cache.refreshIntervalMillis)
                    if (!isActive) break
                    runCatching {
                        val cachePath = resolveCachePath(spec, null)
                        refreshFlight(
                            spec = spec,
                            context = ExecutionContext(config),
                            cachePath = cachePath,
                            cacheKey = CodexRadarCache.cacheKey(spec.pageUrl)
                        ).await()
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        CodexRadarLog.warning(
                            "Codex Radar 定时刷新失败：${summarize(error)}；" +
                                "旧缓存不会被覆盖"
                        )
                    }
                }
            }
            synchronized(refreshScopeLock) { schedulerJobs += job }
        }
        WebHookDebug.log("[XAiWebHook] [Codex Radar] 已启动 ${candidates.size} 个缓存定时刷新任务")
    }

    fun restartRefreshScheduler(config: PluginConfig): Unit {
        stopRefreshSchedulerInternal()
        startRefreshScheduler(config)
    }

    internal fun stopRefreshScheduler(): Unit {
        synchronized(refreshScopeLock) {
            acceptingRefreshes = false
            refreshGeneration++
        }
        stopRefreshSchedulerInternal()
        lastRefreshError = null
        lastRefreshAt = null
    }

    fun close(): Unit {
        synchronized(refreshScopeLock) {
            acceptingRefreshes = false
            refreshGeneration++
        }
        stopRefreshSchedulerInternal()
        lastRefreshError = null
        lastRefreshAt = null
    }

    private fun stopRefreshSchedulerInternal(): Unit {
        val scope = synchronized(refreshScopeLock) {
            val previous = refreshScope
            refreshScope = null
            schedulerJobs.forEach(Job::cancel)
            schedulerJobs.clear()
            previous
        }
        scope?.cancel(CancellationException("Codex Radar refresh scope is stopping"))
        refreshFlights.forEach { (key, deferred) ->
            deferred.cancel(CancellationException("Codex Radar refresh is stopping"))
            refreshFlights.remove(key, deferred)
        }
    }

    private fun refreshFlight(
        spec: CodexRadarFetchSpec,
        context: ExecutionContext,
        cachePath: Path,
        cacheKey: String
    ): CompletableDeferred<CodexRadarSnapshot> {
        val flightKey = buildString {
            append(cachePath.toAbsolutePath().normalize())
            append('|').append(cacheKey)
            append("|proxy=").append(spec.proxyUrl)
            append("|timeout=").append(spec.timeoutMillis)
            append("|wait=").append(spec.waitUntil)
            append("|maxWait=").append(spec.maxWaitMillis)
            append("|poll=").append(spec.pollIntervalMillis)
            append("|stable=").append(spec.stablePolls)
            append("|browser=").append(context.config.browser.hashCode())
        }
        val deferred = CompletableDeferred<CodexRadarSnapshot>()
        var existing: CompletableDeferred<CodexRadarSnapshot>? = null
        var generation = 0L
        var rejected = false
        synchronized(refreshScopeLock) {
            existing = refreshFlights[flightKey]
            if (existing == null) {
                if (!acceptingRefreshes) {
                    rejected = true
                } else {
                    generation = refreshGeneration
                    refreshFlights[flightKey] = deferred
                }
            }
        }
        existing?.let { return it }
        if (rejected) {
            deferred.cancel(CancellationException("Codex Radar refreshes are stopped"))
            return deferred
        }

        val scope = try {
            ensureRefreshScope()
        } catch (error: CancellationException) {
            refreshFlights.remove(flightKey, deferred)
            deferred.cancel(error)
            return deferred
        }
        val job = scope.launch {
            lastRefreshAt = System.currentTimeMillis()
            try {
                if (!isRefreshGenerationActive(generation)) {
                    throw CancellationException("Codex Radar refresh generation is obsolete")
                }
                val snapshot = fetchFreshSnapshot(spec, context)
                if (!isRefreshGenerationActive(generation)) {
                    throw CancellationException("Codex Radar refresh generation is obsolete")
                }
                var cacheWriteFailed = false
                runCatching {
                    CodexRadarCache.write(
                        path = cachePath,
                        pageUrl = spec.pageUrl,
                        snapshot = snapshot,
                        // 逐行缓存：本次未加载/无数据的行沿用旧缓存值，不因单条缺失丢数据
                        mergeWithExisting = true
                    )
                }.onFailure { error ->
                    cacheWriteFailed = true
                    lastRefreshError = "缓存写入失败：${summarize(error)}"
                    CodexRadarLog.warning(
                        "Codex Radar 缓存写入失败：${summarize(error)}；旧缓存文件保持不变"
                    )
                }
                if (!cacheWriteFailed) lastRefreshError = null
                deferred.complete(snapshot)
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) {
                    lastRefreshError = summarize(error)
                    deferred.completeExceptionally(timeoutException(spec, null, error))
                } else {
                    deferred.cancel(error)
                }
            } catch (error: Throwable) {
                lastRefreshError = summarize(error)
                CodexRadarLog.warning(
                    "Codex Radar 刷新失败：${summarize(error)}；旧缓存不会被覆盖"
                )
                deferred.completeExceptionally(error)
            } finally {
                refreshFlights.remove(flightKey, deferred)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null && !deferred.isCompleted) {
                deferred.completeExceptionally(cause)
            }
            refreshFlights.remove(flightKey, deferred)
        }
        return deferred
    }

    private fun ensureRefreshScope(): CoroutineScope {
        synchronized(refreshScopeLock) {
            if (!acceptingRefreshes) {
                throw CancellationException("Codex Radar refreshes are stopped")
            }
            val existing = refreshScope
            if (existing != null && existing.isActive) return existing
            return CoroutineScope(SupervisorJob() + Dispatchers.IO).also { refreshScope = it }
        }
    }

    private fun isRefreshGenerationActive(generation: Long): Boolean {
        return synchronized(refreshScopeLock) {
            acceptingRefreshes && refreshGeneration == generation
        }
    }

    private suspend fun fetchFreshSnapshot(
        spec: CodexRadarFetchSpec,
        context: ExecutionContext
    ): CodexRadarSnapshot {
        val evaluator = CodexRadarReadinessEvaluator()
        val results = try {
            WebPageScreenshotAction.queryTextListsUntil(
                action = browserAction(spec),
                context = context.withProxy(spec.proxyUrl),
                requests = browserRequests(),
                maxWaitMillis = spec.maxWaitMillis,
                pollIntervalMillis = spec.pollIntervalMillis,
                stablePolls = spec.stablePolls,
                readiness = evaluator::evaluate
            )
        } catch (error: BrowserTextQueryTimeoutException) {
            val partial = parsePartialSnapshot(error.lastResults)
            if (partial != null) {
                logPartialSnapshot(spec, evaluator.lastStats)
                return partial
            }
            throw timeoutException(spec, evaluator.lastStats, error)
        } catch (error: TimeoutCancellationException) {
            throw timeoutException(spec, evaluator.lastStats, error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val stats = evaluator.lastStats
            if (stats != null) logReadinessStats("Codex Radar 页面读取失败", stats)
            throw CodexRadarException(
                "Codex Radar 页面抓取失败：${summarize(error)}",
                error
            )
        }

        return parseSnapshot(results, partial = false)
    }

    private fun parseSnapshot(
        results: List<BrowserTextListResult>,
        partial: Boolean
    ): CodexRadarSnapshot {
        if (results.size != 2) {
            throw CodexRadarException(
                "Codex Radar 页面结构错误：预期雷达分和社区体感分两个结果，实际得到 ${results.size} 个"
            )
        }
        return try {
            // 逐行解析：单条数据未加载或无数据时只跳过该条，继续处理后面的行。
            // partial=true（页面步骤超时/未就绪）时进一步放宽到"逐表"：
            // 某一整张表都没读出来也照常缓存另一张表的数据，缺失部分由旧缓存按行补齐。
            val radarResult = parseTable("雷达分", partial) {
                CodexRadarParser.parseRadarRows(results[0])
            }
            val communityResult = parseTable("社区体感分", partial) {
                CodexRadarParser.parseCommunityRows(results[1])
            }
            val skipped = radarResult.skipped + communityResult.skipped
            if (radarResult.scores.isEmpty() && communityResult.scores.isEmpty()) {
                throw CodexRadarException(
                    "Codex Radar 本次未读取到任何有效条目，保留旧缓存" +
                        if (skipped.isEmpty()) "" else "（${skipped.joinToString("；") { it.describe() }}）"
                )
            }
            CodexRadarSnapshot(
                radarScores = radarResult.scores,
                communityScores = communityResult.scores,
                generatedAtText = CodexRadarTime.now(),
                partial = partial || skipped.isNotEmpty()
            ).also {
                WebHookDebug.log(
                    "[XAiWebHook] [Codex Radar] ${if (it.partial) "部分" else "完整"}快照解析成功：" +
                        "雷达分=${radarResult.scores.size}/${radarResult.totalRows}，" +
                        "社区体感分=${communityResult.scores.size}/${communityResult.totalRows}"
                )
                if (skipped.isNotEmpty()) {
                    CodexRadarLog.warning(
                        "Codex Radar 有 ${skipped.size} 条数据本次未缓存（已跳过，不影响其它条目）：" +
                            summarizeSkippedRows(skipped)
                    )
                }
            }
        } catch (error: CodexRadarException) {
            throw error
        } catch (error: Throwable) {
            throw CodexRadarException("Codex Radar 最终解析失败：${summarize(error)}", error)
        }
    }

    /**
     * 跳过条目的日志摘要：只逐条列出前 [MAX_LOGGED_SKIPPED_ROWS] 行，其余按原因归并计数。
     * 站点改版时可能出现几十上百行同时被跳过，全量拼接会让单条 W 日志膨胀到无法阅读。
     */
    private fun summarizeSkippedRows(skipped: List<CodexRadarSkippedRow>): String {
        val head = skipped.take(MAX_LOGGED_SKIPPED_ROWS).joinToString("；") { row -> row.describe() }
        if (skipped.size <= MAX_LOGGED_SKIPPED_ROWS) return head
        val restByKind = skipped
            .drop(MAX_LOGGED_SKIPPED_ROWS)
            .groupingBy { row -> "${row.kind}（${row.reason}）" }
            .eachCount()
            .entries
            .joinToString("；") { (key, count) -> "$key 共 $count 条" }
        return "$head；……另外 ${skipped.size - MAX_LOGGED_SKIPPED_ROWS} 条已省略：$restByKind"
    }

    internal fun parsePartialSnapshotForTest(
        results: List<BrowserTextListResult>
    ): CodexRadarSnapshot? = parsePartialSnapshot(results)

    /**
     * 逐表解析：non-lenient 模式下任何问题都直接抛出；
     * lenient（页面步骤超时/未就绪）模式下，整张表读不到时退化为空结果，
     * 让另一张表和旧缓存的行继续生效。
     */
    private fun parseTable(
        kind: String,
        lenient: Boolean,
        parse: () -> CodexRadarParseResult
    ): CodexRadarParseResult {
        return try {
            parse()
        } catch (error: CodexRadarException) {
            if (!lenient) throw error
            CodexRadarLog.warning("Codex Radar $kind 本次未读取到有效数据，使用旧缓存补齐：${summarize(error)}")
            CodexRadarParseResult(scores = emptyList(), skipped = emptyList(), totalRows = 0)
        }
    }

    private fun parsePartialSnapshot(
        results: List<BrowserTextListResult>
    ): CodexRadarSnapshot? {
        // 超时时刻可能只读出部分结果（甚至一条都没有），补齐成两个空表再走逐行解析，
        // 这样能读到的行依旧会写进缓存，读不到的行由旧缓存保留。
        if (results.isEmpty()) return null
        val padded = if (results.size >= 2) results else results + emptyTextListResult()
        return runCatching { parseSnapshot(padded, partial = true) }.getOrNull()
    }

    private fun emptyTextListResult(): BrowserTextListResult = BrowserTextListResult(
        effectiveUrl = "",
        items = emptyList(),
        emptyState = true
    )

    private fun logPartialSnapshot(
        spec: CodexRadarFetchSpec,
        stats: CodexRadarReadinessStats?
    ): Unit {
        stats?.let { logReadinessStats("Codex Radar 完整数据等待超时，改用有效部分数据", it) }
        WebHookDebug.log(
            "[XAiWebHook] [Codex Radar] max_wait_ms=${spec.maxWaitMillis} 到期，" +
                "忽略 —/pending 条目并缓存可用分数"
        )
    }

    private fun timeoutException(
        spec: CodexRadarFetchSpec,
        stats: CodexRadarReadinessStats?,
        cause: Throwable?
    ): CodexRadarException {
        stats?.let { logReadinessStats("Codex Radar 完整数据等待超时", it) }
        val detail = stats?.summary().orEmpty()
        return CodexRadarException(
            "Codex Radar 完整数据等待超时：max_wait_ms=${spec.maxWaitMillis}；$detail",
            cause
        )
    }

    private fun logReadinessStats(prefix: String, stats: CodexRadarReadinessStats): Unit {
        CodexRadarLog.warning("$prefix：${stats.summary()}")
    }

    internal fun browserRequestsForTest(): List<BrowserTextListRequest> = browserRequests()

    private fun browserRequests(): List<BrowserTextListRequest> = listOf(
        BrowserTextListRequest(
            ACTION_ID,
            PART_ID,
            RADAR_ITEMS_XPATH,
            RADAR_LABEL_SELECTOR,
            RADAR_STATUS_SELECTOR,
            null,
            MAX_ITEMS,
            RADAR_CONTAINER_XPATH,
            STATE_ATTRIBUTES,
            STATE_ATTRIBUTES
        ),
        BrowserTextListRequest(
            ACTION_ID,
            PART_ID,
            COMMUNITY_ITEMS_XPATH,
            COMMUNITY_LABEL_SELECTOR,
            COMMUNITY_STATUS_SELECTOR,
            null,
            MAX_ITEMS,
            COMMUNITY_CONTAINER_XPATH,
            STATE_ATTRIBUTES,
            STATE_ATTRIBUTES
        )
    )

    internal fun browserAction(spec: CodexRadarFetchSpec): ActionConfig {
        val part = mapOf(
            "id" to PART_ID,
            "url" to spec.pageUrl,
            "wait_until" to spec.waitUntil,
            "timeout_ms" to spec.timeoutMillis,
            "selector" to "body",
            "steps" to listOf(
                // 两个区块的等待都设为 optional：站点改版或某个区块临时未渲染时，
                // 不再让整次抓取等满超时失败，而是继续按逐行/逐表策略缓存已读到的数据。
                mapOf(
                    "op" to "wait",
                    "selector" to RADAR_CONTAINER_XPATH,
                    "state" to "attached",
                    "optional" to true,
                    "timeout_ms" to spec.maxWaitMillis
                ),
                mapOf(
                    "op" to "wait",
                    "selector" to COMMUNITY_CONTAINER_XPATH,
                    "state" to "attached",
                    "optional" to true,
                    "timeout_ms" to spec.maxWaitMillis
                )
            )
        )
        return ActionConfig(
            id = ACTION_ID,
            type = "send_webpage_screenshot",
            enabled = true,
            params = mapOf(
                "screenshot" to mapOf(
                    "parts" to listOf(part),
                    "timeout_ms" to spec.timeoutMillis
                )
            )
        )
    }

    private fun CodexRadarFetchSpec.cachePath(directory: Path?): Path {
        val baseDirectory = directory ?: pluginConfigDirectory()
        return CodexRadarCache.resolvePath(cache.file, baseDirectory)
    }

    private fun resolveCachePath(spec: CodexRadarFetchSpec, directory: Path?): Path {
        return spec.cachePath(directory)
    }

    private fun pluginConfigDirectory(): Path {
        return runCatching { XAiWebHook.configFolder.toPath() }
            .getOrElse {
                Paths.get("build", "codex-radar-cache").toAbsolutePath().normalize()
            }
    }

    internal fun warmupSpecsForTest(config: PluginConfig): List<CodexRadarFetchSpec> {
        return staticCodexSpecs(config).filter { spec -> spec.cache.enabled }
    }

    private fun staticCodexSpecs(config: PluginConfig): List<CodexRadarFetchSpec> {
        return configuredCodexActions(config)
            .filter { action -> action.enabled && action.type == "send_codex_radar_report" }
            .mapNotNull { action ->
                if (!hasStaticRefreshParameters(action)) {
                    CodexRadarLog.warning(
                        "Codex Radar 缓存任务已跳过 action=${action.id ?: "(inline)"}：" +
                            "page/cache/proxy 参数包含 request/event 模板"
                    )
                    return@mapNotNull null
                }
                runCatching {
                    fetchSpec(action) { value -> value?.toString().orEmpty() }
                }.getOrElse { error ->
                    CodexRadarLog.warning(
                        "Codex Radar 缓存任务已跳过 action=${action.id ?: "(inline)"}：" +
                            summarize(error)
                    )
                    null
                }
            }
    }

    private fun configuredCodexActions(config: PluginConfig): List<ActionConfig> {
        return buildList {
            addAll(config.actions.values)
            addAll(config.incoming.endpoints.flatMap { endpoint -> endpoint.actions })
            addAll(config.outgoing.routes.flatMap { route -> route.actions })
        }.distinct()
    }

    private fun hasStaticRefreshParameters(action: ActionConfig): Boolean {
        val values = listOf(
            action.params["page_url"],
            action.params["timeout_ms"],
            action.params["wait_until"],
            action.params["max_wait_ms"],
            action.params["poll_interval_ms"],
            action.params["stable_polls"],
            action.params["proxy"],
            action.params["cache"]
        )
        return values.none(::containsTemplate)
    }

    private fun containsTemplate(value: Any?): Boolean {
        return when (value) {
            is String -> value.contains("${'$'}{")
            is List<*> -> value.any(::containsTemplate)
            is Map<*, *> -> value.values.any(::containsTemplate)
            else -> false
        }
    }

    private fun parseLong(
        value: String,
        default: Long,
        minimum: Long,
        maximum: Long,
        field: String
    ): Long {
        if (value.isBlank()) return default
        return value.trim().toLongOrNull()?.coerceIn(minimum, maximum)
            ?: throw CodexRadarException(
                "Codex Radar 配置错误：$field 必须是 $minimum..$maximum 的整数"
            )
    }

    private fun parseBoolean(value: String, default: Boolean): Boolean {
        if (value.isBlank()) return default
        return value.equals("true", ignoreCase = true) || value == "1"
    }

    private fun validatePageUrl(value: String): Unit {
        val uri = runCatching { URI(value) }
            .getOrElse { throw CodexRadarException("Codex Radar 配置错误：page_url 不是合法 URL") }
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "Codex Radar 配置错误：page_url 必须使用 http 或 https"
        }
        require(!uri.host.isNullOrBlank()) {
            "Codex Radar 配置错误：page_url 缺少主机名"
        }
    }

    private fun ExecutionContext.withProxy(proxyUrl: String): ExecutionContext {
        if (proxyUrl.isBlank() || proxyUrl == config.browser.proxyUrl) return this
        return copy(config = config.copy(browser = config.browser.copy(proxyUrl = proxyUrl)))
    }

    private fun Any?.asMap(): Map<String, Any?> {
        val raw = this as? Map<*, *> ?: return emptyMap()
        return raw.mapNotNull { (key, value) ->
            key?.toString()?.let { it to value }
        }.toMap()
    }

    private fun summarize(error: Throwable): String {
        return (error.message ?: error::class.simpleName ?: "unknown error")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(500)
    }
}

internal data class CodexRadarFetchSpec(
    val pageUrl: String,
    val timeoutMillis: Long = CodexRadarAction.DEFAULT_TIMEOUT_MILLIS,
    val waitUntil: String = "domcontentloaded",
    val maxWaitMillis: Long = CodexRadarAction.DEFAULT_MAX_WAIT_MILLIS,
    val pollIntervalMillis: Long = CodexRadarAction.DEFAULT_POLL_INTERVAL_MILLIS,
    val stablePolls: Int = CodexRadarAction.DEFAULT_STABLE_POLLS,
    /** 可选浏览器代理；留空时使用 browser.proxy。 */
    val proxyUrl: String = "",
    val cache: CodexRadarCacheSpec = CodexRadarCacheSpec.disabled()
)

internal data class CodexRadarCacheSpec(
    val enabled: Boolean,
    val file: String,
    val ttlMillis: Long,
    val refreshEnabled: Boolean,
    val refreshIntervalMillis: Long
) {
    companion object {
        fun disabled(): CodexRadarCacheSpec = CodexRadarCacheSpec(
            enabled = false,
            file = CodexRadarCache.DEFAULT_FILE_NAME,
            ttlMillis = CodexRadarAction.DEFAULT_CACHE_TTL_MILLIS,
            refreshEnabled = true,
            refreshIntervalMillis = CodexRadarAction.DEFAULT_CACHE_REFRESH_INTERVAL_MILLIS
        )
    }
}

internal data class CodexRadarReadinessStats(
    val totalItems: Int,
    val validItems: Int,
    val placeholderItems: Int,
    val pendingItems: Int,
    val finalMissingItems: Int,
    val invalidItems: Int,
    val radarContainerPresent: Boolean,
    val communityContainerPresent: Boolean,
    val radarReady: Boolean,
    val communityReady: Boolean,
    val stableCounts: Boolean
) {
    fun summary(): String =
        "total=$totalItems valid=$validItems placeholder=$placeholderItems " +
            "pending=$pendingItems final_missing=$finalMissingItems invalid=$invalidItems " +
            "containers=${radarContainerPresent && communityContainerPresent} " +
            "states=radar:$radarReady/community:$communityReady stable_counts=$stableCounts"
}

internal class CodexRadarReadinessEvaluator {
    @Volatile
    var lastStats: CodexRadarReadinessStats? = null
        private set

    private var lastSignature: String? = null

    fun evaluate(
        current: List<BrowserTextListResult>,
        previous: List<BrowserTextListResult>?
    ): Boolean {
        if (current.size != 2) return false
        val radar = inspect(current[0], maximum = 150.0)
        val community = inspect(current[1], maximum = 10.0)
        val stableCounts = previous == null || previous.size == current.size &&
            previous.indices.all { index ->
                previous[index].containerPresent == current[index].containerPresent &&
                    previous[index].items.size == current[index].items.size
            }
        val stats = CodexRadarReadinessStats(
            totalItems = radar.total + community.total,
            validItems = radar.valid + community.valid,
            placeholderItems = radar.placeholder + community.placeholder,
            pendingItems = radar.pending + community.pending,
            finalMissingItems = radar.finalMissing + community.finalMissing,
            invalidItems = radar.invalid + community.invalid,
            radarContainerPresent = current[0].containerPresent,
            communityContainerPresent = current[1].containerPresent,
            radarReady = radar.stateReady,
            communityReady = community.stateReady,
            stableCounts = stableCounts
        )
        lastStats = stats
        val signature = stats.summary()
        if (signature != lastSignature) {
            lastSignature = signature
            WebHookDebug.log("[XAiWebHook] [Codex Radar] readiness：$signature")
        }
        return stats.radarContainerPresent &&
            stats.communityContainerPresent &&
            stats.radarReady &&
            stats.communityReady &&
            stats.stableCounts &&
            radar.valid > 0 &&
            community.valid > 0 &&
            stats.pendingItems == 0 &&
            stats.invalidItems == 0
    }

    private fun inspect(result: BrowserTextListResult, maximum: Double): SectionStats {
        var valid = 0
        var placeholder = 0
        var pending = 0
        var finalMissing = 0
        var invalid = 0
        result.items.forEach { item ->
            val raw = item.statusText?.trim().orEmpty()
            val itemText = item.itemText.orEmpty()
            val placeholderValue = raw in PLACEHOLDER_VALUES || raw.startsWith("未读取")
            // 分数单元格是 ✓/× 说明该行来自对错视图而不是分数视图，属于“本来就没有分数”，
            // 与“数据不足/不支持”同属最终缺分，不能算非法值拖住就绪判定。
            val markerValue = raw in CodexRadarParser.STATUS_MARKERS
            val finalValue = markerValue ||
                itemText.contains("数据不足") ||
                itemText.contains("不支持") ||
                raw == "数据不足" ||
                raw == "不支持"
            val statePending = hasPendingState(result, item)
            when {
                finalValue && !statePending -> finalMissing++
                placeholderValue -> {
                    placeholder++
                    pending++
                }
                statePending -> pending++
                else -> {
                    val number = raw.replace(',', '.').toDoubleOrNull()
                    if (number != null && number.isFinite() && number in 0.0..maximum) {
                        valid++
                    } else {
                        invalid++
                    }
                }
            }
        }
        return SectionStats(
            total = result.items.size,
            valid = valid,
            placeholder = placeholder,
            pending = pending,
            finalMissing = finalMissing,
            invalid = invalid,
            stateReady = stateReady(
                result = result,
                valid = valid,
                pending = pending,
                finalMissing = finalMissing,
                invalid = invalid
            )
        )
    }

    private fun stateReady(
        result: BrowserTextListResult,
        valid: Int,
        pending: Int,
        finalMissing: Int,
        invalid: Int
    ): Boolean {
        val attributes = buildList {
            result.containerAttributes.forEach { (name, value) ->
                if (value != null) add(name to value)
            }
            result.items.forEach { item ->
                item.attributes.forEach { (name, value) ->
                    if (value != null) add(name to value)
                }
            }
        }
        if (attributes.isEmpty()) {
            return pending == 0 && invalid == 0 && (valid > 0 || finalMissing > 0)
        }
        val pendingMarkers = attributes.filter { (name, _) ->
            name.lowercase(Locale.ROOT).contains("pending") ||
                name.lowercase(Locale.ROOT).contains("loading")
        }
        if (pendingMarkers.any { (_, value) -> isTrue(value) || isPendingValue(value) }) return false
        if (pendingMarkers.any { (_, value) -> isFalse(value) }) return true

        val stateMarkers = attributes.filter { (name, _) ->
            val normalized = name.lowercase(Locale.ROOT)
            normalized.contains("state") || normalized.contains("status") || normalized.contains("ready")
        }
        if (stateMarkers.isNotEmpty()) {
            return stateMarkers.any { (_, value) -> isReadyValue(value) }
        }
        return pending == 0 && invalid == 0 && (valid > 0 || finalMissing > 0)
    }

    private fun hasPendingState(result: BrowserTextListResult, item: kim.hhhhhy.x.webhook.api.BrowserTextListItem): Boolean {
        val attributes = buildList {
            result.containerAttributes.forEach { (name, value) -> add(name to value) }
            item.attributes.forEach { (name, value) -> add(name to value) }
        }
        return attributes.any { (name, value) ->
            val normalized = name.lowercase(Locale.ROOT)
            normalized.contains("pending") || normalized.contains("loading") ||
                (normalized.contains("state") && isPendingValue(value)) ||
                (normalized.contains("status") && isPendingValue(value))
        }.let { markerPresent ->
            markerPresent && attributes.any { (name, value) ->
                val normalized = name.lowercase(Locale.ROOT)
                (normalized.contains("pending") || normalized.contains("loading") ||
                    normalized.contains("state") || normalized.contains("status")) &&
                    (isTrue(value) || isPendingValue(value))
            }
        }
    }

    private fun isTrue(value: String?): Boolean = value?.trim()?.lowercase(Locale.ROOT) in TRUE_VALUES
    private fun isFalse(value: String?): Boolean = value?.trim()?.lowercase(Locale.ROOT) in FALSE_VALUES
    private fun isPendingValue(value: String?): Boolean = value?.trim()?.lowercase(Locale.ROOT) in PENDING_VALUES
    private fun isReadyValue(value: String?): Boolean = value?.trim()?.lowercase(Locale.ROOT) in READY_VALUES

    private data class SectionStats(
        val total: Int,
        val valid: Int,
        val placeholder: Int,
        val pending: Int,
        val finalMissing: Int,
        val invalid: Int,
        val stateReady: Boolean
    )

    private companion object {
        val PLACEHOLDER_VALUES = setOf("", "—", "–", "-", "暂无", "未读取", "未读取 · 点击查询")
        val TRUE_VALUES = setOf("true", "1", "yes", "y", "on")
        val FALSE_VALUES = setOf("false", "0", "no", "n", "off")
        val PENDING_VALUES = setOf("pending", "loading", "wait", "waiting", "querying", "requesting", "init", "initializing")
        val READY_VALUES = setOf("ready", "ok", "complete", "completed", "done", "success", "loaded", "true", "1", "yes")
    }
}
