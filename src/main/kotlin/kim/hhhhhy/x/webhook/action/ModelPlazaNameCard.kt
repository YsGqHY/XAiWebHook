package kim.hhhhhy.x.webhook.action

/** 模型名列表中的一项。 */
internal data class ModelNameEntry(
    val name: String,
    /** 站点平台标识，例如 anthropic / openai；为空时归入“未标注厂商”。 */
    val platform: String
)

/**
 * 把模糊匹配得到的模型名渲染为一张列表卡片。
 *
 * 用途与 [ModelPlazaPriceCard] 不同：这里只回答“有哪些模型”，因此不展示价格与端点。
 * 版面骨架与定价卡片完全共用 [ModelPlazaCardStyle]，保证两张卡片看起来是同一套设计；
 * 命中较多时按厂商分组并自动分列。
 */
internal object ModelPlazaNameCard {

    /** 单个厂商命中超过该数量时改为双列排布。 */
    private const val TWO_COLUMN_THRESHOLD = 12

    fun render(query: String, entries: List<ModelNameEntry>, options: ModelPlazaCardOptions): String {
        require(entries.isNotEmpty()) { "模型名列表为空时不应生成图片" }
        val groups = entries
            .groupBy { it.platform.trim().ifBlank { UNKNOWN_PLATFORM } }
            .toList()
            .sortedBy { (platform, _) -> platform.lowercase() }
        val body = buildString {
            // 与定价卡片一致：大标题放主体（这里是查询词），数量用徽标承载
            append(ModelPlazaCardStyle.titleBlock(query, "${entries.size} 个模型"))
            append("<div class=\"muted\">模糊匹配，共 ")
            append(entries.size)
            append(" 个模型，来自 ")
            append(groups.size)
            append(" 个厂商</div>")
            groups.forEach { (platform, models) -> append(groupSection(platform, models)) }
        }
        return ModelPlazaCardStyle.page(
            body = body,
            headerSub = "模型名模糊匹配",
            footer = options.generatedAt?.let { "生成时间：$it" },
            widthPx = options.widthPx,
            extraStyle = styleSheet()
        )
    }

    private fun groupSection(platform: String, models: List<ModelNameEntry>): String = buildString {
        append(ModelPlazaCardStyle.sectionTitle(platformLabel(platform), "共 ${models.size} 个"))
        if (models.size > TWO_COLUMN_THRESHOLD) {
            val rows = (models.size + 1) / 2
            val left = models.take(rows)
            val right = models.drop(rows)
            append("<table class=\"name-table\">")
            append("<tr><th class=\"col-index\">序号</th><th class=\"col-name\">模型名</th>")
            append("<th class=\"col-index\">序号</th><th class=\"col-name\">模型名</th></tr>")
            repeat(rows) { index ->
                append("<tr>")
                append(indexCell(index + 1))
                append(nameCell(left.getOrNull(index)?.name))
                append(indexCell(rows + index + 1))
                append(nameCell(right.getOrNull(index)?.name))
                append("</tr>")
            }
            append("</table>")
        } else {
            append("<table class=\"name-table\">")
            append("<tr><th class=\"col-index\">序号</th><th class=\"col-name\">模型名</th></tr>")
            models.forEachIndexed { index, model ->
                append("<tr>")
                append(indexCell(index + 1))
                append(nameCell(model.name))
                append("</tr>")
            }
            append("</table>")
        }
    }

    private fun indexCell(index: Int): String = "<td class=\"col-index\">$index</td>"

    private fun nameCell(name: String?): String {
        if (name == null) return "<td class=\"col-name name-empty\"></td>"
        return "<td class=\"col-name\">${ModelPlazaCardStyle.escape(name)}</td>"
    }

    /** 站点平台标识首字母大写，便于阅读；未识别时原样展示。 */
    private fun platformLabel(platform: String): String {
        if (platform == UNKNOWN_PLATFORM) return platform
        return platform.replaceFirstChar { it.uppercaseChar() }
    }

    /** 列表卡片特有样式；版面骨架由 [ModelPlazaCardStyle] 统一提供。 */
    private fun styleSheet(): String = """
        .count {
          margin-left: 8px;
          font-size: 17px;
          font-weight: 400;
          color: #8b95a1;
        }
        th.col-index, td.col-index {
          width: 56px;
          text-align: right;
          color: #9aa3af;
          font-size: 16px;
          font-weight: 400;
        }
        th.col-index {
          font-weight: 700;
          color: #4b5563;
          font-size: 18px;
        }
        th.col-name, td.col-name {
          text-align: left;
          font-family: "Consolas", "JetBrains Mono", "Microsoft YaHei", monospace;
        }
        td.col-name {
          color: #111827;
        }
        .name-empty {
          border: 1px solid #e5e8ee;
        }
    """.trimIndent()

    private const val UNKNOWN_PLATFORM = "未标注厂商"
}
