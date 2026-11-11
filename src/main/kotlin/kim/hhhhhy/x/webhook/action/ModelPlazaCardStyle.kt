package kim.hhhhhy.x.webhook.action

/**
 * 模型广场卡片的公共外观。
 *
 * 定价卡片与模型名列表卡片必须看起来是同一套东西，因此版面骨架（页宽、卡片边框、
 * 页眉、大标题、章节标题、表格、页脚）只在这里定义一次；两个渲染器只追加各自特有的
 * 局部样式，避免样式漂移。
 */
internal object ModelPlazaCardStyle {
    const val BRAND_TITLE: String = "Geek2Api 模型广场"

    /** 卡片主体宽度下限/上限，与配置 image_width_px 的取值范围保持一致。 */
    private const val MIN_WIDTH = 720
    private const val MAX_WIDTH = 1600

    /** 组装整页 HTML；页眉副标题与页脚可选。 */
    fun page(
        body: String,
        headerSub: String?,
        footer: String?,
        widthPx: Int,
        extraStyle: String
    ): String = """
        <!doctype html>
        <html>
        <head>
          <meta charset="UTF-8" />
          <style>${baseStyle(widthPx)}
        $extraStyle</style>
        </head>
        <body>
          <div class="page">
            <div class="header">
              <span class="header-title">$BRAND_TITLE</span>
              ${headerSub?.let { "<span class=\"header-sub\">${escape(it)}</span>" } ?: ""}
            </div>
            $body
            ${footer?.let { "<div class=\"footer\">${escape(it)}</div>" } ?: ""}
          </div>
        </body>
        </html>
    """.trimIndent()

    /** 大标题：等宽字体名称 + 可选徽标，定价卡片用于模型名，列表卡片用于查询词。 */
    fun titleBlock(title: String, badge: String?): String = buildString {
        append("<div class=\"model-head\"><span class=\"model-name\">")
        append(escape(title))
        append("</span>")
        badge?.let { append("<span class=\"badge\">").append(escape(it)).append("</span>") }
        append("</div>")
    }

    fun sectionTitle(text: String, suffix: String? = null): String = buildString {
        append("<div class=\"section-title\">")
        append(escape(text))
        suffix?.let { append("<span class=\"count\">").append(escape(it)).append("</span>") }
        append("</div>")
    }

    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun baseStyle(widthPx: Int): String = """
        /* 显式指定页高：只给宽度时 openhtmltopdf 会按默认页高在长卡片中途分页，
           拼接处会出现行错位。这里给足高度，让整张卡片落在同一页，再由渲染器裁到内容高度。 */
        @page { size: ${widthPx.coerceIn(MIN_WIDTH, MAX_WIDTH)}px 12000px; margin: 0; }
        * { box-sizing: border-box; }
        body {
          margin: 0;
          padding: 28px;
          background: #f6f7f9;
          color: #1f2328;
          font-family: "Microsoft YaHei", "Noto Sans CJK SC", "SimSun", sans-serif;
          font-size: 20px;
          line-height: 1.5;
        }
        .page {
          padding: 26px 30px 30px 30px;
          background: #ffffff;
          border: 1px solid #e3e6eb;
          border-radius: 14px;
        }
        .header {
          padding-bottom: 12px;
          margin-bottom: 6px;
          border-bottom: 2px solid #eef0f4;
        }
        .header-title {
          font-size: 20px;
          font-weight: 700;
          color: #6b7280;
          letter-spacing: 0.06em;
        }
        .header-sub {
          margin-left: 12px;
          font-size: 18px;
          color: #9aa3af;
        }
        .model-head {
          margin: 20px 0 4px 0;
        }
        .model-name {
          font-size: 30px;
          font-weight: 700;
          color: #111827;
          font-family: "Consolas", "JetBrains Mono", "Microsoft YaHei", monospace;
        }
        .badge {
          margin-left: 10px;
          padding: 2px 10px;
          font-size: 16px;
          color: #b45309;
          background: #fff7ed;
          border: 1px solid #fed7aa;
          border-radius: 999px;
        }
        .badge-soft {
          color: #2563eb;
          background: #eff6ff;
          border-color: #bfdbfe;
        }
        .section-title {
          margin: 20px 0 8px 0;
          font-size: 19px;
          font-weight: 700;
          color: #4b5563;
        }
        .muted {
          font-size: 17px;
          color: #8b95a1;
        }
        table {
          width: 100%;
          border-collapse: collapse;
          font-size: 18px;
        }
        th, td {
          padding: 9px 12px;
          border: 1px solid #e5e8ee;
          text-align: right;
          vertical-align: middle;
        }
        th {
          background: #f7f9fb;
          color: #4b5563;
          font-weight: 700;
        }
        .divider {
          height: 2px;
          margin: 26px 0 4px 0;
          background: #eef0f4;
        }
        .footer {
          margin-top: 20px;
          font-size: 15px;
          color: #9aa3af;
          text-align: right;
        }
    """.trimIndent()
}
