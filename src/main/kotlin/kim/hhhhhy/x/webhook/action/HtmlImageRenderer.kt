package kim.hhhhhy.x.webhook.action

import com.openhtmltopdf.java2d.api.BufferedImagePageProcessor
import com.openhtmltopdf.java2d.api.Java2DRendererBuilder
import com.openhtmltopdf.util.XRLog
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.logging.Level
import javax.imageio.ImageIO

/**
 * 把一段完整 HTML 渲染为 PNG。
 *
 * 与 [MarkdownImageRenderer] 的区别：本渲染器不转义 HTML，
 * 直接接受调用方构造的结构与样式，用于生成图表类图片。
 * 底层同为 openhtmltopdf（CSS 2.1），因此布局需使用 table 而非 flex/grid。
 */
internal object HtmlImageRenderer {
    private const val BASE_URI = "https://xai-webhook.local/"
    private const val SCALE = 2.0

    init {
        XRLog.listRegisteredLoggers().forEach { logger -> XRLog.setLevel(logger, Level.WARNING) }
    }

    fun render(html: String): ByteArray {
        val xhtml = toXhtml(html)
        val pageProcessor = BufferedImagePageProcessor(BufferedImage.TYPE_INT_RGB, SCALE)
        Java2DRendererBuilder()
            .withHtmlContent(xhtml, BASE_URI)
            .useEnvironmentFonts(true)
            .toSinglePage(pageProcessor)
            .runPaged()

        val image = pageProcessor.pageImages.firstOrNull()
            ?: error("html render produced no image")
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    /** openhtmltopdf 要求严格 XHTML，这里用 jsoup 规整标签闭合与实体转义。 */
    private fun toXhtml(html: String): String {
        val document = Jsoup.parse(html, BASE_URI, org.jsoup.parser.Parser.htmlParser())
        document.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)
        return document.outerHtml()
    }
}
