package kim.hhhhhy.x.webhook.action.codex

import kim.hhhhhy.x.webhook.action.HtmlImageRenderer
import kim.hhhhhy.x.webhook.api.BrowserTextListItem
import kim.hhhhhy.x.webhook.api.BrowserTextListResult
import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.WebHookConfig
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class CodexRadarReportTest {
    @Test
    fun radarDomItemsAreParsedAndPlaceholdersAreIgnored(): Unit {
        val result = result(
            item(1, "GPT-6 Astra ultra", "121.1", "GPT-6 Astra ultra\n121.1\n\$3.35\n13.2分钟"),
            item(2, "GPT-6 Astra low", "—", "GPT-6 Astra low\n—\n未读取 · 点击查询"),
            item(3, "GPT-5.6 Sol high", "84.2", "GPT-5.6 Sol high\n84.2\n数据不足")
        )

        val scores = CodexRadarParser.parseRadar(result)

        assertEquals(2, scores.size)
        assertEquals("GPT-6 Astra", scores[0].model)
        assertEquals("ultra", scores[0].effort)
        assertEquals(121.1, scores[0].value, 1e-9)
        assertEquals("GPT-5.6 Sol", scores[1].model)
        assertEquals("high", scores[1].effort)
    }

    @Test
    fun communityDomItemsParseAverageAndRatingCount(): Unit {
        val result = result(
            item(1, "GPT-6 Astra high", "7.2", "GPT-6 Astra high\n7.2\n60 人评分"),
            item(2, "GPT-6 Luna high", "暂无", "GPT-6 Luna high\n暂无\n0 人评分"),
            item(3, "GPT-6.1 Sol max", "8.4", "GPT-6.1 Sol max\n8.4\n140 人评分")
        )

        val scores = CodexRadarParser.parseCommunity(result)

        assertEquals(2, scores.size)
        assertEquals(7.2, scores[0].value, 1e-9)
        assertEquals(60, scores[0].ratingCount)
        assertEquals("GPT-6.1 Sol", scores[1].model)
        assertEquals("max", scores[1].effort)
    }

    @Test
    fun missingSectionsAndInvalidScoresReturnExplicitErrors(): Unit {
        val radarMissing = assertFailsWith<CodexRadarException> {
            CodexRadarParser.parseRadar(result())
        }
        assertTrue(radarMissing.message.orEmpty().contains("雷达分"))
        assertTrue(radarMissing.message.orEmpty().contains("缺失"))

        val communityInvalid = assertFailsWith<CodexRadarException> {
            CodexRadarParser.parseCommunity(
                result(item(1, "GPT-6 Astra high", "11.0", "GPT-6 Astra high\n11.0"))
            )
        }
        assertTrue(communityInvalid.message.orEmpty().contains("社区体感分"))
        assertTrue(communityInvalid.message.orEmpty().contains("0..10"))

        val nonFinite = assertFailsWith<CodexRadarException> {
            CodexRadarParser.parseRadar(
                result(item(1, "GPT-6 Astra high", "NaN", "GPT-6 Astra high\nNaN"))
            )
        }
        assertTrue(nonFinite.message.orEmpty().contains("有限数值"))
    }

    @Test
    fun browserActionUsesFixedDomTargetsAndTimeout(): Unit {
        val action = ActionConfig(
            id = "codex",
            type = "send_codex_radar_report",
            enabled = true,
            params = mapOf(
                "page_url" to "https://codexradar.com/",
                "timeout_ms" to 45_000,
                "wait_until" to "load"
            )
        )
        val spec = CodexRadarAction.fetchSpec(action) { it?.toString().orEmpty() }
        val browserAction = CodexRadarAction.browserAction(spec)
        val screenshot = browserAction.params["screenshot"].asMap()
        val parts = screenshot["parts"] as List<*>
        val part = parts.single().asMap()
        val steps = part["steps"] as List<*>
        val stepSelectors = steps.map { it.asMap()["selector"].toString() }

        assertEquals("https://codexradar.com/", spec.pageUrl)
        assertEquals(45_000L, spec.timeoutMillis)
        assertEquals("load", spec.waitUntil)
        assertTrue(stepSelectors.any { it.contains("intelligence-efficiency") })
        assertTrue(stepSelectors.any { it.contains("model-ratings") })
        assertEquals(
            "https://codexradar.com/",
            (part["url"] as String)
        )
    }

    @Test
    fun oldApiUrlsAreNotUsedByFetchSpec(): Unit {
        val action = ActionConfig(
            id = "codex",
            type = "send_codex_radar_report",
            enabled = true,
            params = mapOf(
                "efficiency_url" to "legacy-efficiency-value",
                "insights_url" to "legacy-insights-value"
            )
        )

        val spec = CodexRadarAction.fetchSpec(action) { it?.toString().orEmpty() }

        assertEquals(CodexRadarAction.DEFAULT_PAGE_URL, spec.pageUrl)
    }

    @Test
    fun defaultConfigUsesPageScrapingAndAllowsCodexHost(): Unit {
        val root = CodexRadarReportTest::class.java
            .getResourceAsStream("/webhook_config.yml")
            .use { input ->
                requireNotNull(input)
                @Suppress("UNCHECKED_CAST")
                Yaml().load<Map<String, Any?>>(input)
            }
        val config = WebHookConfig.parseConfig(root)
        val route = config.outgoing.routes.single { it.id == "codex-degradation-command" }
        val action = config.actions.getValue("codex-degradation-report")

        assertTrue(route.enabled)
        assertEquals("send_codex_radar_report", action.type)
        assertTrue(config.browser.allowedHosts.contains("codexradar.com"))
        assertEquals("https://codexradar.com/", action.params["page_url"])
        assertEquals(120000, action.params["max_wait_ms"])
        assertEquals(1000, action.params["poll_interval_ms"])
        assertEquals(2, action.params["stable_polls"])
        assertTrue(action.params["cache"] is Map<*, *>)
        assertTrue("efficiency_url" !in action.params)
        assertTrue("insights_url" !in action.params)

        val chart = CodexRadarOptionsParser.chartOptions(action.params)
        assertEquals("Codex Radar 分数概览", chart.title)
        assertEquals(1680, chart.widthPx)
    }

    @Test
    fun timeoutPartialSnapshotFiltersPlaceholdersAndMarksOutput(): Unit {
        val snapshot = requireNotNull(
            CodexRadarAction.parsePartialSnapshotForTest(
                listOf(
                    result(
                        item(1, "GPT-6 Astra high", "121.1", "GPT-6 Astra high\n121.1"),
                        item(2, "GPT-6 Luna high", "—", "GPT-6 Luna high\n—\n读取失败 · 点击重试")
                    ),
                    result(
                        item(1, "GPT-6 Astra high", "7.2", "GPT-6 Astra high\n7.2\n60 人评分"),
                        item(2, "GPT-6 Luna high", "暂无", "GPT-6 Luna high\n暂无")
                    )
                )
            )
        )
        assertTrue(snapshot.partial)
        assertEquals(1, snapshot.radarScores.size)
        assertEquals(1, snapshot.communityScores.size)
        assertTrue(snapshot.summaryText("Codex Radar 分数：").contains("仅展示已读取的有效数据"))

        val html = CodexRadarChart(CodexRadarChartOptions()).render(snapshot)
        assertTrue(html.contains("页面等待超时，仅展示已读取的有效数据"))
        assertTrue(html.contains("121.1"))
        assertTrue(html.contains("7.2"))
    }

    @Test
    fun chartContainsBothNamedScorePanelsAndRendersPng(): Unit {
        val snapshot = CodexRadarSnapshot(
            radarScores = listOf(
                CodexRadarScore("GPT-6 Astra", "ultra", 121.1),
                CodexRadarScore("GPT-5.6 Sol", "high", 84.2)
            ),
            communityScores = listOf(
                CodexRadarScore("GPT-6 Astra", "ultra", 6.5, 44),
                CodexRadarScore("GPT-5.6 Sol", "high", 7.0, 10)
            ),
            generatedAtText = "2026-10-10 12:00"
        )
        val html = CodexRadarChart(CodexRadarChartOptions()).render(snapshot)

        assertTrue(html.contains("雷达分"))
        assertTrue(html.contains("社区体感分"))
        assertTrue(html.contains("雷达分按百分制成绩 × 1.5 计分（满分150分）"))
        assertTrue(html.contains("64道题覆盖软件工程、空间推理、计算机视觉、终端工具使用、系统工程、科研计算与数据分析"))
        assertTrue(html.contains("当前展示滚动7天社区体感分"))
        assertTrue(html.contains("121.1"))
        assertTrue(html.contains("6.5"))
        assertTrue(html.contains("44 人评分"))
        assertTrue(html.contains(".section-note"))
        assertTrue(html.contains("font-size: 13px"))
        assertFalse(html.contains("降智预警"))
        assertFalse(html.contains("建议换"))

        val png = HtmlImageRenderer.render(html)
        assertTrue(png.size > 1_024, "png too small: ${png.size}")
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
    }

    @Test
    fun readinessRejectsPlaceholdersAndPendingButAllowsFinalMissingRows(): Unit {
        val evaluator = CodexRadarReadinessEvaluator()
        val pending = evaluator.evaluate(
            listOf(
                readyResult(
                    item(1, "GPT-6 Astra high", "121.1", "GPT-6 Astra high\n121.1"),
                    item(2, "GPT-6 Luna high", "—", "GPT-6 Luna high\n—")
                ),
                readyResult(
                    item(1, "GPT-6 Astra high", "7.2", "GPT-6 Astra high\n7.2")
                )
            ),
            null
        )
        assertFalse(pending)
        assertTrue(evaluator.lastStats?.placeholderItems == 1)
        assertTrue(evaluator.lastStats?.pendingItems == 1)

        val complete = listOf(
            readyResult(
                item(1, "GPT-6 Astra high", "121.1", "GPT-6 Astra high\n121.1"),
                item(2, "GPT-6 Luna high", "数据不足", "GPT-6 Luna high\n数据不足")
            ),
            readyResult(
                item(1, "GPT-6 Astra high", "7.2", "GPT-6 Astra high\n7.2"),
                item(2, "GPT-6 Luna high", "不支持", "GPT-6 Luna high\n不支持")
            )
        )
        assertTrue(evaluator.evaluate(complete, null))
        assertTrue(evaluator.evaluate(complete, complete))
        assertEquals(2, evaluator.lastStats?.finalMissingItems)

        val unstable = evaluator.evaluate(
            listOf(
                readyResult(
                    item(1, "GPT-6 Astra high", "121.1", "GPT-6 Astra high\n121.1"),
                    item(2, "GPT-6 Luna high", "数据不足", "GPT-6 Luna high\n数据不足"),
                    item(3, "GPT-5.6 Sol high", "84.2", "GPT-5.6 Sol high\n84.2")
                ),
                complete[1]
            ),
            complete
        )
        assertFalse(unstable)
        assertFalse(evaluator.lastStats?.stableCounts ?: true)
    }

    @Test
    fun readinessRequiresAtLeastOneValidScorePerSectionOrCompleteDom(): Unit {
        val evaluator = CodexRadarReadinessEvaluator()
        val noCommunityScore = evaluator.evaluate(
            listOf(
                readyResult(item(1, "GPT-6 Astra high", "121.1", "GPT-6 Astra high\n121.1")),
                readyResult(item(1, "GPT-6 Astra high", "数据不足", "GPT-6 Astra high\n数据不足"))
            ),
            null
        )
        assertFalse(noCommunityScore)

        val noReadyMarker = evaluator.evaluate(
            listOf(
                result(item(1, "GPT-6 Astra high", "121.1", "GPT-6 Astra high\n121.1")),
                result(item(1, "GPT-6 Astra high", "7.2", "GPT-6 Astra high\n7.2"))
            ),
            null
        )
        assertTrue(noReadyMarker)
    }

    @Test
    fun startupWarmupIncludesCacheEnabledActionEvenWhenPeriodicRefreshIsDisabled(): Unit {
        val action = ActionConfig(
            id = "warmup-only",
            type = "send_codex_radar_report",
            enabled = true,
            params = mapOf(
                "page_url" to "https://codexradar.com/",
                "cache" to mapOf(
                    "enabled" to true,
                    "refresh_enabled" to false
                )
            )
        )
        val config = WebHookConfig.parseConfig(
            mapOf(
                "browser" to mapOf(
                    "enabled" to false,
                    "allowed_hosts" to listOf("codexradar.com")
                ),
                "actions" to mapOf(
                    "warmup-only" to mapOf(
                        "type" to action.type,
                        "enabled" to true,
                        "page_url" to "https://codexradar.com/",
                        "cache" to mapOf(
                            "enabled" to true,
                            "refresh_enabled" to false
                        )
                    )
                )
            )
        )
        val specs = CodexRadarAction.warmupSpecsForTest(config)
        assertEquals(1, specs.size)
        assertTrue(specs.single().cache.enabled)
        assertFalse(specs.single().cache.refreshEnabled)
    }

    @Test
    fun fetchSpecBoundsPollingAndKeepsLegacyActionsUncached(): Unit {
        val bounded = CodexRadarAction.fetchSpec(
            ActionConfig(
                id = "codex",
                type = "send_codex_radar_report",
                enabled = true,
                params = mapOf(
                    "max_wait_ms" to 999_999,
                    "poll_interval_ms" to 1,
                    "stable_polls" to 99,
                    "cache" to mapOf("enabled" to true, "ttl_ms" to 9_999_999_999L)
                )
            )
        ) { it?.toString().orEmpty() }
        assertEquals(120_000L, bounded.maxWaitMillis)
        assertEquals(200L, bounded.pollIntervalMillis)
        assertEquals(5, bounded.stablePolls)
        assertTrue(bounded.cache.enabled)
        assertEquals(30L * 24L * 60L * 60L * 1000L, bounded.cache.ttlMillis)

        val legacy = CodexRadarAction.fetchSpec(
            ActionConfig(
                id = "legacy",
                type = "send_codex_radar_report",
                enabled = true,
                params = emptyMap()
            )
        ) { it?.toString().orEmpty() }
        assertFalse(legacy.cache.enabled)
    }

    @Test
    fun cacheRoundTripRejectsWrongKeyAndPreservesOldSnapshotAfterInvalidWrite(): Unit {
        val directory = Files.createTempDirectory("codex-radar-cache-test")
        try {
            val path = directory.resolve("codex-radar-cache.json")
            val old = snapshot("2026-10-10 12:00")
            CodexRadarCache.write(path, "https://codexradar.com/", old, savedAtEpochMillis = 1_000L)
            val loaded = requireNotNull(
                CodexRadarCache.read(path, "https://codexradar.com/", nowEpochMillis = 2_000L)
            )
            assertEquals(old, loaded.snapshot)
            assertTrue(loaded.isFresh(nowEpochMillis = 2_000L, ttlMillis = 1_000L))

            val partial = old.copy(partial = true)
            CodexRadarCache.write(path, "https://codexradar.com/", partial, savedAtEpochMillis = 3_000L)
            assertTrue(requireNotNull(CodexRadarCache.read(path, "https://codexradar.com/")).snapshot.partial)
            assertEquals(null, CodexRadarCache.read(path, "https://other.example/", nowEpochMillis = 2_000L))

            assertFailsWith<IllegalArgumentException> {
                CodexRadarCache.write(
                    path,
                    "https://codexradar.com/",
                    old.copy(radarScores = emptyList())
                )
            }
            assertTrue(requireNotNull(CodexRadarCache.read(path, "https://codexradar.com/")).snapshot.partial)

            path.toFile().writeText("{\"version\":999,\"pageUrl\":\"https://codexradar.com/\"}")
            assertEquals(null, CodexRadarCache.read(path, "https://codexradar.com/"))
            assertFailsWith<IllegalArgumentException> {
                CodexRadarCache.resolvePath("../outside.json", directory)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun snapshot(generatedAtText: String): CodexRadarSnapshot = CodexRadarSnapshot(
        radarScores = listOf(CodexRadarScore("GPT-6 Astra", "high", 121.1)),
        communityScores = listOf(CodexRadarScore("GPT-6 Astra", "high", 7.2, 60)),
        generatedAtText = generatedAtText
    )

    private fun readyResult(vararg items: BrowserTextListItem): BrowserTextListResult =
        result(
            *items,
            containerAttributes = mapOf("data-state" to "ready")
        )

    private fun result(
        vararg items: BrowserTextListItem,
        containerAttributes: Map<String, String?> = emptyMap()
    ): BrowserTextListResult =
        BrowserTextListResult(
            "https://codexradar.com/",
            items.toList(),
            false,
            true,
            containerAttributes
        )

    private fun item(
        index: Int,
        name: String,
        score: String,
        text: String,
        ariaLabel: String? = null,
        attributes: Map<String, String?> = emptyMap()
    ): BrowserTextListItem = BrowserTextListItem(
        index,
        text,
        name,
        score,
        null,
        null,
        ariaLabel,
        attributes
    )

    private fun Any?.asMap(): Map<String, Any?> {
        val raw = this as? Map<*, *> ?: error("expected map, got ${this?.javaClass?.name}")
        return raw.mapNotNull { (key, value) -> key?.toString()?.let { it to value } }.toMap()
    }
}
