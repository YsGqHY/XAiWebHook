package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.scraper.ModelPlazaScraper
import java.io.File

/**
 * 手工预览工具：用 test/resources/modelplaza 下的真实接口样本渲染卡片 PNG。
 *
 * 运行方式：
 * `./gradlew test --tests '*ModelPlazaCardPreview*'` 之外的场景可改用
 * `./gradlew run -PmainClass=...`；这里保留为 object + main，与 PolymarketImagePreview 一致，
 * 不参与常规测试。
 */
internal object ModelPlazaCardPreview {
    @JvmStatic
    public fun main(args: Array<String>): Unit {
        val platforms = ModelPlazaScraper.parseModelPlazaResponse(fixture("plaza-sample.json"))
        val endpoints = ModelPlazaScraper.parseEndpointResponse(fixture("endpoints-sample.json")).endpoints
        val outputDir = File("build/modelplaza-preview").also { it.mkdirs() }

        val modelDetails = ModelPlazaScraper.modelPricingDetails(platforms, "claude-fable-5-1")
            .map { detail -> detail.copy(groups = detail.groups.map { it.copy(endpoints = endpoints) }) }
        val modelHtml = ModelPlazaPriceCard.renderModels(
            modelDetails,
            ModelPlazaCardOptions(generatedAt = "2026-11-10 23:30")
        )
        File(outputDir, "model-pricing.html").writeText(modelHtml)
        File(outputDir, "model-pricing.png").writeBytes(HtmlImageRenderer.render(modelHtml))
        println("[preview] model pricing card -> ${outputDir.absolutePath}/model-pricing.png")

        val groupDetails = ModelPlazaScraper.groupPricingDetails(platforms, "ccmax-纯文本满血")
        val groupHtml = ModelPlazaPriceCard.renderGroups(
            groupDetails,
            ModelPlazaCardOptions(generatedAt = "2026-11-10 23:30")
        )
        File(outputDir, "group-pricing.html").writeText(groupHtml)
        File(outputDir, "group-pricing.png").writeBytes(HtmlImageRenderer.render(groupHtml))
        println("[preview] group pricing card -> ${outputDir.absolutePath}/group-pricing.png")
    }

    private fun fixture(name: String): String {
        return ModelPlazaCardPreview::class.java
            .getResourceAsStream("/modelplaza/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("missing fixture: $name")
    }
}
