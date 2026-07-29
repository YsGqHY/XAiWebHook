package kim.hhhhhy.x.webhook.action.codex

import kim.hhhhhy.x.webhook.action.HtmlImageRenderer
import kim.hhhhhy.x.webhook.config.WebHookConfig
import kotlinx.serialization.json.Json
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class CodexRadarReportTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 静态快照真实结构片段：points 为「模型 × 档位」聚合点。 */
    private val efficiencyPayload = """
        {
          "schema": 2,
          "source_updated_at": "2026-07-29T18:53:22+08:00",
          "points": [
            {"model":"gpt-5.6-sol","effort":"low","iq":81.6964,"passed":61,"valid_tasks":112,
             "average_price_usd":1.994499,"average_minutes":10.5617,"combined_cost_index":0.0593},
            {"model":"gpt-5.6-sol","effort":"high","iq":95.0893,"passed":71,"valid_tasks":112,
             "average_price_usd":5.04037,"average_minutes":22.5613,"combined_cost_index":1.5199},
            {"model":"gpt-5.6-sol","effort":"xhigh","iq":107.1429,"passed":80,"valid_tasks":112,
             "average_price_usd":6.2409,"average_minutes":21.6997,"combined_cost_index":1.6705},
            {"model":"gpt-5.6-sol","effort":"ultra","iq":99.1071,"passed":74,"valid_tasks":112,
             "average_price_usd":25.4048,"average_minutes":52.3298,"combined_cost_index":100.0},
            {"model":"gpt-5.6-terra","effort":"high","iq":70.9821,"passed":53,"valid_tasks":112,
             "average_price_usd":1.3949,"average_minutes":13.1042,"combined_cost_index":0.0798},
            {"model":"gpt-5.6-terra","effort":"ultra","iq":99.1071,"passed":74,"valid_tasks":112,
             "average_price_usd":13.6248,"average_minutes":46.3599,"combined_cost_index":37.0329},
            {"model":"gpt-5.6-luna","effort":"high","iq":72.3214,"passed":54,"valid_tasks":112,
             "average_price_usd":1.1013,"average_minutes":20.4599,"combined_cost_index":0.247},
            {"model":"gpt-5.6-luna","effort":"max","iq":88.3929,"passed":66,"valid_tasks":112,
             "average_price_usd":2.3849,"average_minutes":32.9899,"combined_cost_index":2.2878},
            {"model":"gpt-5.5","effort":"high","iq":80.3571,"passed":60,"valid_tasks":112,
             "average_price_usd":3.8449,"average_minutes":17.6899,"combined_cost_index":0.5507},
            {"model":"gpt-5.5","effort":"xhigh","iq":96.4286,"passed":72,"valid_tasks":112,
             "average_price_usd":5.9549,"average_minutes":24.9199,"combined_cost_index":2.4321}
          ]
        }
    """.trimIndent()

    private val insightsPayload = """
        {
          "schema": 1,
          "generated_at": "2026-07-29T10:55:18+00:00",
          "recommendations": [
            {"key":"daily_development","title":"日常开发","items":[
              {"model":"gpt-5.6-sol","effort":"high","iq":95.09,"average_cost_usd":5.04,
               "average_duration_minutes":22.56,"slot":"value"}]}
          ],
          "degradation_alerts": {
            "rule": "每个模型档位只与自身历史比较",
            "items": [
              {"model":"gpt-5.6-terra","effort":"high","iq":70.98,
               "average_iq_24h":79.5,"average_iq_48h":81.2,"degradation_severity_score":1.4}
            ]
          }
        }
    """.trimIndent()

    private fun snapshot(): RadarSnapshot {
        val efficiency = json.parseToJsonElement(efficiencyPayload)
        val insights = json.parseToJsonElement(insightsPayload)
        return RadarSnapshot(
            tiers = CodexRadarParser.parseTiers(efficiency),
            alerts = CodexRadarParser.parseAlerts(insights),
            recommendations = CodexRadarParser.parseRecommendations(insights),
            sourceUpdatedAt = CodexRadarParser.sourceUpdatedAt(efficiency),
            alertRule = CodexRadarParser.alertRule(insights)
        )
    }

    @Test
    fun parserNormalizesAllThreeEndpointShapes(): Unit {
        val parsed = snapshot()

        assertEquals(10, parsed.tiers.size)
        val solXhigh = parsed.tiers.single { it.key == "gpt-5.6-sol|xhigh" }
        assertEquals(107.1429, solXhigh.iq, 1e-6)
        assertEquals(6.2409, solXhigh.priceUsd!!, 1e-6)
        assertEquals(80, solXhigh.passed)
        assertEquals("2026-07-29T18:53:22+08:00", parsed.sourceUpdatedAt)

        // 降智预警来自对象包装的 items，且差值可由均值回退计算
        val alert = parsed.alerts.single()
        assertEquals("gpt-5.6-terra|high", alert.key)
        assertEquals(8.52, alert.dropFrom24h!!, 1e-6)
        assertEquals(10.22, alert.dropFrom48h!!, 1e-6)
        assertNotNull(parsed.alertRule)

        val recommendation = parsed.recommendations.single()
        assertEquals("日常开发", recommendation.title)
        assertEquals("value", recommendation.items.single().slot)
    }

    @Test
    fun advisorGradesTiersAgainstConfiguredThresholds(): Unit {
        val advisor = CodexRadarAdvisor(CodexRadarAdviceOptions())
        assertEquals(RadarGrade.EXCELLENT, advisor.grade(107.1429))
        assertEquals(RadarGrade.EXCELLENT, advisor.grade(96.0))
        assertEquals(RadarGrade.NORMAL, advisor.grade(95.0893))
        assertEquals(RadarGrade.WATCH, advisor.grade(81.6964))
        assertEquals(RadarGrade.LOW, advisor.grade(72.3214))
    }

    @Test
    fun highestStrategyProducesExpectedAdviceLines(): Unit {
        val advisor = CodexRadarAdvisor(CodexRadarAdviceOptions())
        val report = advisor.build(snapshot(), "07-29 18:53")

        // Sol high=95.09 达标且无预警 -> 正常
        val sol = report.advices.single { it.model == "gpt-5.6-sol" }
        assertEquals(RadarVerdict.NORMAL, sol.verdict)
        assertEquals("GPT-5.6 Sol 智商正常。", sol.advice)

        // Terra high=70.98 未达标且命中降智预警 -> 升到最高档位 ultra
        val terra = report.advices.single { it.model == "gpt-5.6-terra" }
        assertEquals(RadarVerdict.SWITCH, terra.verdict)
        assertEquals("GPT-5.6 Terra：建议换超限思考。", terra.advice)

        // Luna 全档位最高仅 88.39 -> 全档位降智，回退到 max
        val luna = report.advices.single { it.model == "gpt-5.6-luna" }
        assertEquals(RadarVerdict.DEGRADED, luna.verdict)
        assertEquals("gpt-5.6-luna|max", luna.target!!.key)

        // GPT-5.5 high=80.36 未达标，xhigh=96.43 达标 -> 换极高思考
        val gpt55 = report.advices.single { it.model == "gpt-5.5" }
        assertEquals(RadarVerdict.SWITCH, gpt55.verdict)
        assertEquals("GPT-5.5：建议换极高思考。", gpt55.advice)

        // 模型顺序遵循 model_order
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5"),
            report.advices.map { it.model }
        )
        assertTrue(report.adviceText("建议：").startsWith("建议：\nGPT-5.6 Sol 智商正常。"))
    }

    @Test
    fun cheapestStrategyPrefersLowestCombinedCost(): Unit {
        val advisor = CodexRadarAdvisor(
            CodexRadarAdviceOptions(strategy = CodexRadarStrategy.CHEAPEST)
        )
        val terra = advisor.build(snapshot(), "07-29 18:53")
            .advices.single { it.model == "gpt-5.6-terra" }

        // Terra 达标档位只有 ultra，cheapest 与 highest 结果一致
        assertEquals("gpt-5.6-terra|ultra", terra.target!!.key)

        val gpt55 = advisor.build(snapshot(), "07-29 18:53")
            .advices.single { it.model == "gpt-5.5" }
        assertEquals("gpt-5.5|xhigh", gpt55.target!!.key)
    }

    @Test
    fun defaultConfigRegistersCodexRouteAndAction(): Unit {
        val root = CodexRadarReportTest::class.java.getResourceAsStream("/webhook_config.yml").use { input ->
            requireNotNull(input)
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Map<String, Any?>>(input)
        }
        val config = WebHookConfig.parseConfig(root)

        val route = config.outgoing.routes.single { it.id == "codex-degradation-command" }
        assertTrue(route.enabled)
        assertEquals(listOf("降智检测"), route.message.contains)
        assertTrue(route.singleFlight.enabled)
        assertEquals("send_codex_radar_report", route.actions.single().type)

        val action = config.actions.getValue("codex-degradation-report")
        assertEquals("send_codex_radar_report", action.type)
        assertTrue(action.enabled)

        // YAML 中的判定与展示参数必须能被解析器还原
        val advice = CodexRadarOptionsParser.adviceOptions(action.params)
        assertEquals(90.0, advice.targetIq, 1e-9)
        assertEquals(96.0, advice.excellentIq, 1e-9)
        assertEquals(80.0, advice.watchIq, 1e-9)
        assertEquals("high", advice.baselineEffort)
        assertEquals(CodexRadarStrategy.HIGHEST, advice.strategy)
        assertEquals("超限思考", advice.effortLabels.getValue("ultra"))
        assertEquals("极致思考", advice.effortLabels.getValue("max"))
        assertEquals("GPT-5.6 Sol", advice.modelLabels.getValue("gpt-5.6-sol"))

        val chart = CodexRadarOptionsParser.chartOptions(action.params)
        assertEquals("Codex 降智检测", chart.title)
        assertEquals(1680, chart.widthPx)
    }

    /**
     * 使用线上真实结构的 19 点快照做端到端渲染，并把 PNG 写到 build 目录供目视检查。
     * fixture 由 /data/intelligence-efficiency.json 裁剪 history 后生成。
     */
    @Test
    fun fullSnapshotFixtureRendersChartAndAdvice(): Unit {
        val payload = CodexRadarReportTest::class.java
            .getResourceAsStream("/codex/intelligence-efficiency-sample.json")
            .use { requireNotNull(it).readBytes().toString(Charsets.UTF_8) }
        val tiers = CodexRadarParser.parseTiers(json.parseToJsonElement(payload))
        assertEquals(19, tiers.size)
        // Sol 有 6 个档位，Luna 只有 5 个（没有 ultra）
        assertEquals(6, tiers.count { it.model == "gpt-5.6-sol" })
        assertEquals(5, tiers.count { it.model == "gpt-5.6-luna" })

        val advisor = CodexRadarAdvisor(CodexRadarAdviceOptions())
        val report = advisor.build(
            RadarSnapshot(
                tiers = tiers,
                alerts = CodexRadarParser.parseAlerts(json.parseToJsonElement(insightsPayload)),
                recommendations = CodexRadarParser.parseRecommendations(
                    json.parseToJsonElement(insightsPayload)
                ),
                sourceUpdatedAt = "2026-07-29T18:53:22+08:00",
                alertRule = null
            ),
            "07-29 18:53"
        )
        assertEquals(4, report.advices.size)

        val png = HtmlImageRenderer.render(
            CodexRadarChart(advisor, CodexRadarChartOptions()).render(report)
        )
        val output = File("build/codex-radar-chart-test.png")
        output.parentFile?.mkdirs()
        output.writeBytes(png)
        assertTrue(png.size > 10_000, "png suspiciously small: ${png.size}")
        println("advice:\n${report.adviceText("建议：")}")
        println("chart: ${output.absolutePath} (${png.size} bytes)")
    }

    /**
     * 预警 iq 来自 radar-insights 的最新单次采样，档位卡片来自 intelligence-efficiency
     * 的聚合值，线上两者会差零点几。同一张图对同一档位必须只显示一个数字，
     * 以卡片聚合值为准，否则会被当成计算 bug。
     */
    @Test
    fun alertBannerUsesTierIqInsteadOfAlertSampleIq(): Unit {
        // 档位聚合值 70.9821 -> 71.0；预警采样值故意给 65.0 -> 65.0
        val divergent = insightsPayload.replace("\"iq\":70.98", "\"iq\":65.0")
        val advisor = CodexRadarAdvisor(CodexRadarAdviceOptions())
        val base = snapshot()
        val report = advisor.build(
            base.copy(alerts = CodexRadarParser.parseAlerts(json.parseToJsonElement(divergent))),
            "07-29 18:53"
        )

        val alert = report.alerts.single()
        assertEquals(65.0, alert.iq!!, 1e-6)
        assertEquals(70.9821, report.tierOf(alert.key)!!.iq, 1e-6)

        // 表头徽标也含「降智预警」字样，这里只取预警表格的 IQ 单元格
        val html = CodexRadarChart(advisor, CodexRadarChartOptions()).render(report)
        val bannerIq = Regex("""<td class="al-i">([^<]*)</td>""")
            .find(html)?.groupValues?.get(1)?.trim()
        assertEquals("71.0", bannerIq, "banner should show tier iq, not alert sample iq")
    }

    /**
     * 「建议」标签语义是「换到这一档」。NORMAL 判定下 advice.target 等于基准档位，
     * 若照打标签会与分段徽标「智商正常」自相矛盾，因此只有 SWITCH/DEGRADED 才打。
     */
    @Test
    fun recommendedTagOnlyAppearsWhenSwitchIsAdvised(): Unit {
        val advisor = CodexRadarAdvisor(CodexRadarAdviceOptions())
        val report = advisor.build(snapshot(), "07-29 18:53")
        val html = CodexRadarChart(advisor, CodexRadarChartOptions()).render(report)

        // Sol 判定为 NORMAL，其基准档位 high 不应带「建议」标签
        assertEquals(RadarVerdict.NORMAL, report.advices.single { it.model == "gpt-5.6-sol" }.verdict)
        val solSection = html.substringAfter("GPT-5.6 Sol").substringBefore("GPT-5.6 Terra")
        assertFalse(solSection.contains("tag-pick"), "normal model must not carry 建议 tag")

        // Terra 判定为 SWITCH，目标档位 ultra 必须带「建议」标签
        val terra = report.advices.single { it.model == "gpt-5.6-terra" }
        assertEquals(RadarVerdict.SWITCH, terra.verdict)
        val terraSection = html.substringAfter("GPT-5.6 Terra").substringBefore("GPT-5.6 Luna")
        assertTrue(terraSection.contains("tag-pick"), "switch target must carry 建议 tag")

        // Luna 判定为 DEGRADED，回退档位同样需要标出
        val lunaSection = html.substringAfter("GPT-5.6 Luna").substringBefore("GPT-5.5")
        assertTrue(lunaSection.contains("tag-pick"), "degraded fallback must carry 建议 tag")
    }

    /** 页脚站长推荐的 iq 与预警同源同理，也必须回落到档位卡片的聚合值。 */
    @Test
    fun footerRecommendationUsesTierIqInsteadOfInsightsIq(): Unit {
        // 档位聚合值 95.0893 -> 95.1；推荐项采样值故意给 88.0 -> 88.0
        val divergent = insightsPayload.replace("\"iq\":95.09,", "\"iq\":88.0,")
        val advisor = CodexRadarAdvisor(CodexRadarAdviceOptions())
        val report = advisor.build(
            snapshot().copy(
                recommendations = CodexRadarParser.parseRecommendations(
                    json.parseToJsonElement(divergent)
                )
            ),
            "07-29 18:53"
        )

        val item = report.recommendations.single().items.single()
        assertEquals(88.0, item.iq!!, 1e-6)
        assertEquals(95.0893, report.tierOf(item.key)!!.iq, 1e-6)

        val html = CodexRadarChart(advisor, CodexRadarChartOptions()).render(report)
        val footerValue = Regex("""<td class="ft-v">([^<]*)</td>""")
            .find(html)?.groupValues?.get(1)?.trim()
        assertEquals("GPT-5.6 Sol 高度思考 95.1", footerValue)
    }

    @Test
    fun chartHtmlRendersToPngWithoutBrowser(): Unit {
        val advisor = CodexRadarAdvisor(CodexRadarAdviceOptions())
        val report = advisor.build(snapshot(), "07-29 18:53")
        val html = CodexRadarChart(advisor, CodexRadarChartOptions()).render(report)

        assertTrue(html.contains("Codex 降智检测"))
        assertTrue(html.contains("超限思考"))
        // openhtmltopdf 不支持 flex/grid，图表必须是 table 布局
        assertFalse(html.contains("display: flex"))
        assertFalse(html.contains("display: grid"))

        val png = HtmlImageRenderer.render(html)
        assertTrue(png.size > 1024, "png too small: ${png.size}")
        // PNG magic number
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
    }
}
