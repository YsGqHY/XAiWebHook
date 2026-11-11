package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.scraper.GroupPricingDetail
import kim.hhhhhy.x.webhook.scraper.ModelGroupPricing
import kim.hhhhhy.x.webhook.scraper.ModelPricingDetail
import kim.hhhhhy.x.webhook.scraper.PlazaEndpoint
import kim.hhhhhy.x.webhook.scraper.TokenPrice
import kim.hhhhhy.x.webhook.scraper.roundPrice
import java.util.Locale

/** 输出卡片的可调参数，宽度与站点弹窗接近，便于在手机端查看。 */
internal data class ModelPlazaCardOptions(
    val widthPx: Int = 1080,
    val generatedAt: String? = null
)

/** 一组共享相同受支持端点集合的分组。 */
internal data class EndpointGroupCluster(
    val groupNames: List<String>,
    val endpoints: List<PlazaEndpoint>
)

/**
 * 把模型广场查询结果渲染成一张静态 HTML 卡片。
 *
 * 底层使用 openhtmltopdf（CSS 2.1），因此全部布局都用 table/div，不使用 flex/grid。
 */
internal object ModelPlazaPriceCard {

    fun renderModels(details: List<ModelPricingDetail>, options: ModelPlazaCardOptions): String {
        val body = details.mapIndexed { index, detail ->
            buildString {
                if (index > 0) append("<div class=\"divider\"></div>")
                append(modelSection(detail))
            }
        }.joinToString("")
        return page(body, options, title = details.firstOrNull()?.modelName)
    }

    fun renderGroups(details: List<GroupPricingDetail>, options: ModelPlazaCardOptions): String {
        val body = details.mapIndexed { index, detail ->
            buildString {
                if (index > 0) append("<div class=\"divider\"></div>")
                append(groupSection(detail))
            }
        }.joinToString("")
        return page(body, options, title = details.firstOrNull()?.groupName)
    }

    fun priceTable(price: TokenPrice): String = buildString {
        append("<table class=\"price-table\">")
        append("<tr><th>输入</th><th>输出</th><th>缓存写入</th><th>缓存读取</th></tr>")
        append("<tr>")
        listOf(price.inputPrice, price.outputPrice, price.cacheWritePrice, price.cacheReadPrice).forEach { value ->
            append("<td>").append(priceWithUnit(value)).append("</td>")
        }
        append("</tr></table>")
    }

    fun formatPriceValue(value: Double?): String {
        if (value == null) return "-"
        val rounded = roundPrice(value)
        return if (rounded >= 1.0 || rounded == 0.0) {
            String.format(Locale.US, "%.2f", rounded)
        } else {
            trimZeros(String.format(Locale.US, "%.4f", rounded))
        }
    }

    fun formatRate(rate: Double): String = trimZeros(String.format(Locale.US, "%.4f", roundPrice(rate)))

    fun formatDiscount(rate: Double): String? {
        if (rate <= 0.0 || rate >= 1.0) return null
        val discount = kotlin.math.round(rate * 1000.0) / 100.0
        return if (discount == kotlin.math.floor(discount)) {
            "${discount.toInt()}折"
        } else {
            "${trimZeros(String.format(Locale.US, "%.2f", discount))}折"
        }
    }

    private fun trimZeros(value: String): String {
        if (!value.contains('.')) return value
        val trimmed = value.trimEnd('0').trimEnd('.')
        return trimmed.ifBlank { "0" }
    }

    private fun priceWithUnit(value: Double?): String = "\$${formatPriceValue(value)}/M"

    private fun modelSection(detail: ModelPricingDetail): String = buildString {
        append("<div class=\"model-head\">")
        append("<span class=\"model-name\">").append(escape(detail.modelName)).append("</span>")
        detail.badge?.let { append("<span class=\"badge\">").append(escape(it)).append("</span>") }
        append("</div>")

        append("<div class=\"section-title\">官方定价（美元 / 百万 Token）</div>")
        append(priceTable(detail.official))

        append("<div class=\"section-title\">各分组实付价（共 ")
        append(detail.groups.size)
        append(" 个分组）</div>")
        if (detail.groups.isEmpty()) {
            append("<div class=\"muted\">该模型暂无可调用分组</div>")
        } else {
            append("<table class=\"group-table\">")
            append("<tr><th class=\"col-group\">分组</th><th class=\"col-rate\">倍率</th>")
            append("<th>输入</th><th>输出</th><th>缓存写入</th><th>缓存读取</th></tr>")
            detail.groups.forEach { group ->
                append("<tr>")
                append("<td class=\"col-group\">").append(escape(group.groupName))
                if (group.isFinalPrice) append("<span class=\"tag\">最终价</span>")
                append("</td>")
                append("<td class=\"col-rate\">×").append(formatRate(group.rateMultiplier))
                formatDiscount(group.rateMultiplier)?.let { append("<br /><span class=\"discount\">").append(it).append("</span>") }
                append("</td>")
                listOf(
                    group.price.inputPrice,
                    group.price.outputPrice,
                    group.price.cacheWritePrice,
                    group.price.cacheReadPrice
                ).forEach { value -> append("<td>").append(priceWithUnit(value)).append("</td>") }
                append("</tr>")
            }
            append("</table>")
        }

        append("<div class=\"section-title\">支持端点</div>")
        val clusters = clusterEndpointGroups(detail.groups)
        when {
            // 端点接口未返回数据（例如请求失败或未开启端点查询）
            detail.groups.none { it.endpoints.isNotEmpty() } -> {
                append("<div class=\"muted\">该模型未返回端点信息</div>")
            }
            // 有端点数据，但全部不可用
            clusters.isEmpty() -> {
                append("<div class=\"muted\">该模型的全部端点均不受支持</div>")
            }
            else -> clusters.forEach { cluster ->
                append(endpointBlock(cluster.groupNames, cluster.endpoints))
            }
        }
    }

    /**
     * 把“端点集合完全相同”的分组聚成一块，避免同一份端点清单重复出现多次。
     *
     * 聚合键为受支持端点的“方法 + 路径”有序列表；结果保持分组首次出现的顺序。
     */
    internal fun clusterEndpointGroups(groups: List<ModelGroupPricing>): List<EndpointGroupCluster> {
        val groupNamesByKey = linkedMapOf<String, MutableList<String>>()
        val endpointsByKey = linkedMapOf<String, List<PlazaEndpoint>>()
        groups.forEach { group ->
            val supported = group.supportedEndpoints
            if (supported.isEmpty()) return@forEach
            val key = supported.joinToString("\n") { "${it.method.uppercase()} ${it.path}" }
            endpointsByKey.putIfAbsent(key, supported)
            groupNamesByKey.getOrPut(key) { mutableListOf() }.add(group.groupName)
        }
        return endpointsByKey.map { (key, endpoints) ->
            EndpointGroupCluster(
                groupNames = groupNamesByKey[key].orEmpty().toList(),
                endpoints = endpoints
            )
        }
    }

    private fun groupSection(detail: GroupPricingDetail): String = buildString {
        append("<div class=\"model-head\">")
        append("<span class=\"model-name\">").append(escape(detail.groupName)).append("</span>")
        append("<span class=\"badge\">×").append(formatRate(detail.rateMultiplier)).append("</span>")
        formatDiscount(detail.rateMultiplier)?.let {
            append("<span class=\"badge badge-soft\">").append(it).append("</span>")
        }
        append("</div>")

        append("<div class=\"section-title\">分组内模型实付价（共 ")
        append(detail.models.size)
        append(" 个模型）</div>")
        if (detail.models.isEmpty()) {
            append("<div class=\"muted\">该分组暂无可调用模型</div>")
            return@buildString
        }
        append("<table class=\"group-table\">")
        append("<tr><th class=\"col-model\">模型</th><th>输入</th><th>输出</th><th>缓存写入</th><th>缓存读取</th></tr>")
        detail.models.forEach { model ->
            append("<tr>")
            append("<td class=\"col-model\">").append(escape(model.modelName))
            model.badge?.let { append("<span class=\"tag\">").append(escape(it)).append("</span>") }
            append("</td>")
            listOf(
                model.price.inputPrice,
                model.price.outputPrice,
                model.price.cacheWritePrice,
                model.price.cacheReadPrice
            ).forEach { value -> append("<td>").append(priceWithUnit(value)).append("</td>") }
            append("</tr>")
        }
        append("</table>")
    }

    /** 多个分组名逐行作为小标题，下面按项目符号列出它们共有且去重后的受支持端点。 */
    private fun endpointBlock(groupNames: List<String>, supported: List<PlazaEndpoint>): String = buildString {
        append("<div class=\"endpoint-group\">")
        groupNames.forEach { name ->
            append("<div class=\"endpoint-group-name\">").append(escape(name)).append("</div>")
        }
        append("<ul class=\"endpoint-list\">")
        supported.forEach { endpoint ->
            append("<li>")
            append("<span class=\"endpoint-method\">").append(escape(endpoint.method)).append("</span>")
            append("<span class=\"endpoint-path\">").append(escape(endpoint.path)).append("</span>")
            append("</li>")
        }
        append("</ul>")
        append("</div>")
    }

    private fun page(body: String, options: ModelPlazaCardOptions, title: String?): String {
        return ModelPlazaCardStyle.page(
            body = body,
            headerSub = title,
            footer = options.generatedAt?.let { "生成时间：$it" },
            widthPx = options.widthPx,
            extraStyle = styleSheet()
        )
    }

    /** 定价卡片特有样式；版面骨架由 [ModelPlazaCardStyle] 统一提供。 */
    private fun styleSheet(): String = """
        th.col-group, td.col-group, th.col-model, td.col-model {
          text-align: left;
        }
        th.col-rate, td.col-rate {
          text-align: center;
          white-space: nowrap;
        }
        td.col-group {
          font-weight: 600;
          color: #111827;
        }
        td.col-model {
          font-family: "Consolas", "JetBrains Mono", "Microsoft YaHei", monospace;
        }
        .tag {
          margin-left: 6px;
          padding: 1px 6px;
          font-size: 14px;
          color: #6b7280;
          background: #f3f4f6;
          border-radius: 6px;
        }
        .discount {
          font-size: 15px;
          color: #059669;
        }
        .endpoint-group {
          margin: 10px 0;
          padding: 12px 14px;
          background: #fafbfc;
          border: 1px solid #e9ecf1;
          border-radius: 10px;
        }
        .endpoint-group-name {
          font-size: 18px;
          font-weight: 700;
          color: #111827;
          line-height: 1.35;
        }
        .endpoint-group-name + .endpoint-list {
          margin-top: 6px;
        }
        .endpoint-list {
          margin: 0;
          padding-left: 22px;
          list-style-type: disc;
        }
        .endpoint-list li {
          margin: 4px 0;
          font-size: 18px;
          color: #111827;
        }
        .endpoint-method {
          margin-right: 8px;
          font-size: 15px;
          font-weight: 700;
          color: #b45309;
        }
        .endpoint-path {
          font-family: "Consolas", "JetBrains Mono", monospace;
          color: #111827;
        }
    """.trimIndent()

    private fun escape(value: String): String = ModelPlazaCardStyle.escape(value)
}
