package kim.hhhhhy.x.webhook.action

import com.openhtmltopdf.java2d.api.BufferedImagePageProcessor
import com.openhtmltopdf.java2d.api.Java2DRendererBuilder
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal object MarkdownImageRenderer {
    private const val WIDTH = 2000
    private const val MIN_HEIGHT = 120
    private const val SCALE = 2.0
    private const val BASE_URI = "https://xai-webhook.local/"

    private val options = MutableDataSet().apply {
        set(Parser.EXTENSIONS, listOf(TablesExtension.create(), TaskListExtension.create()))
        set(HtmlRenderer.SOFT_BREAK, "<br />\n")
    }
    private val parser = Parser.builder(options).build()
    private val htmlRenderer = HtmlRenderer.builder(options).escapeHtml(true).build()

    fun render(markdown: String): List<ByteArray> {
        val html = htmlRenderer.render(parser.parse(markdown))
        val xhtml = toXhtmlDocument(html)
        val pageProcessor = BufferedImagePageProcessor(BufferedImage.TYPE_INT_RGB, SCALE)
        Java2DRendererBuilder()
            .withHtmlContent(xhtml, BASE_URI)
            .useEnvironmentFonts(true)
            .toSinglePage(pageProcessor)
            .runPaged()

        return pageProcessor.pageImages.map { image ->
            ByteArrayOutputStream().use { output ->
                ImageIO.write(image, "png", output)
                output.toByteArray()
            }
        }
    }

    private fun toXhtmlDocument(bodyHtml: String): String {
        val document = Jsoup.parse(
            """
            <!doctype html>
            <html>
            <head>
              <meta charset="UTF-8" />
              <style>${styleSheet()}</style>
            </head>
            <body>
              <article class="markdown-body">$bodyHtml</article>
            </body>
            </html>
            """.trimIndent(),
            BASE_URI,
            org.jsoup.parser.Parser.htmlParser()
        )
        document.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)
        return document.outerHtml()
    }

    private fun styleSheet(): String {
        return """
            @page {
              size: ${WIDTH}px;
              margin: 0;
            }
            * {
              box-sizing: border-box;
            }
            body {
              margin: 0;
              min-height: ${MIN_HEIGHT}px;
              padding: 36px;
              background: #ffffff;
              color: #1f2328;
              font-family: "Microsoft YaHei", "Noto Sans CJK SC", "SimSun", sans-serif;
              font-size: 24px;
              line-height: 1.65;
              overflow-wrap: break-word;
              word-break: break-word;
            }
            .markdown-body {
              width: 100%;
            }
            h1, h2, h3, h4, h5, h6 {
              margin: 0.95em 0 0.45em;
              color: #111827;
              font-weight: 700;
              line-height: 1.25;
            }
            h1 {
              margin-top: 0;
              padding-bottom: 0.28em;
              border-bottom: 2px solid #d8dee4;
              font-size: 38px;
            }
            h2 {
              padding-bottom: 0.22em;
              border-bottom: 1px solid #d8dee4;
              font-size: 32px;
            }
            h3 {
              font-size: 28px;
            }
            h4, h5, h6 {
              font-size: 24px;
            }
            p {
              margin: 0.55em 0;
            }
            a {
              color: #0969da;
              text-decoration: none;
            }
            strong {
              font-weight: 700;
            }
            em {
              font-style: italic;
            }
            blockquote {
              margin: 0.8em 0;
              padding: 0.1em 0 0.1em 0.9em;
              color: #57606a;
              border-left: 6px solid #d0d7de;
            }
            ul, ol {
              margin: 0.55em 0;
              padding-left: 1.55em;
            }
            li {
              margin: 0.22em 0;
              padding-left: 0.12em;
            }
            li > p {
              margin: 0.25em 0;
            }
            code {
              padding: 0.12em 0.32em;
              border-radius: 6px;
              background: #f6f8fa;
              font-family: "Consolas", "JetBrains Mono", "Courier New", monospace;
              font-size: 0.9em;
            }
            pre {
              margin: 0.75em 0;
              padding: 18px 20px;
              border-radius: 12px;
              background: #f6f8fa;
              overflow-wrap: break-word;
              white-space: pre-wrap;
            }
            pre code {
              padding: 0;
              border-radius: 0;
              background: transparent;
              font-size: 21px;
              line-height: 1.5;
            }
            table {
              width: 100%;
              margin: 0.8em 0;
              border-collapse: collapse;
              font-size: 22px;
            }
            th, td {
              padding: 9px 12px;
              border: 1px solid #d0d7de;
              vertical-align: top;
            }
            th {
              background: #f6f8fa;
              font-weight: 700;
            }
            tr:nth-child(even) td {
              background: #fbfbfb;
            }
            hr {
              height: 2px;
              margin: 1.15em 0;
              border: 0;
              background: #d8dee4;
            }
            img {
              max-width: 100%;
            }
            input[type="checkbox"] {
              width: 1em;
              height: 1em;
              margin-right: 0.35em;
              vertical-align: middle;
            }
        """.trimIndent()
    }
}
