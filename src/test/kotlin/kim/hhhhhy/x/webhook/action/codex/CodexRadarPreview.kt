package kim.hhhhhy.x.webhook.action.codex

import kim.hhhhhy.x.webhook.action.HtmlImageRenderer
import kotlinx.coroutines.runBlocking
import java.io.File

/** 手工预览工具：抓取线上真实数据，渲染图表到本地 PNG，便于目视检查排版。 */
internal object CodexRadarPreview {
    @JvmStatic
    fun main(args: Array<String>) {
        val output = File(args.firstOrNull() ?: "build/codex-radar-preview.png")
        runBlocking {
            val snapshot = CodexRadarAction.fetchSnapshot(
                CodexRadarFetchSpec(
                    efficiencyUrl = "https://codexradar.com/data/intelligence-efficiency.json?refresh=1",
                    insightsUrl = "https://codexradar.com/api/radar-insights?refresh=1",
                    // 本机 codexradar.com 仅 IPv6 可达，预览默认走本地代理；可用 -Pproxy= 覆盖
                    proxyUrl = System.getProperty("codex.proxy", "http://127.0.0.1:7890")
                )
            )
            val advisor = CodexRadarAdvisor(CodexRadarAdviceOptions())
            val report = advisor.build(snapshot, CodexRadarTime.now())

            println("tiers=${snapshot.tiers.size} alerts=${snapshot.alerts.size}")
            println(report.adviceText("建议："))

            val html = CodexRadarChart(advisor, CodexRadarChartOptions()).render(report)
            output.parentFile?.mkdirs()
            output.writeBytes(HtmlImageRenderer.render(html))
            println("chart written: ${output.absolutePath}")
        }
        CodexRadarAction.close()
    }
}
