package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.config.ActionConfig
import kim.hhhhhy.x.webhook.config.ModelPlazaQueryConfig
import kim.hhhhhy.x.webhook.config.ModelPlazaResponseFormatConfig
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.scraper.GroupModelsRelation
import kim.hhhhhy.x.webhook.scraper.GroupPricingDetail
import kim.hhhhhy.x.webhook.scraper.ModelGroupRelation
import kim.hhhhhy.x.webhook.scraper.ModelPricingDetail
import kim.hhhhhy.x.webhook.scraper.PlazaEndpoint
import kim.hhhhhy.x.webhook.scraper.TokenPrice
import kim.hhhhhy.x.webhook.util.FormatterConfig
import kim.hhhhhy.x.webhook.util.MessageTemplateFormatter

internal object ModelPlazaFormatter {
    fun formatGroupModels(
        query: String,
        relations: List<GroupModelsRelation>,
        responseFormat: ModelPlazaResponseFormatConfig,
        actionTemplate: String?,
        context: ExecutionContext
    ): String {
        val variables = mapOf(
            "query" to query,
            "count" to relations.size,
            "totalItems" to relations.sumOf { it.modelNames.size },
            "relations" to relations.map { relation ->
                mapOf(
                    "name" to relation.groupName,
                    "group" to relation.groupName,
                    "groupName" to relation.groupName,
                    "models" to relation.modelNames.mapIndexed { index, name ->
                        mapOf("name" to name, "model" to name, "_index" to index)
                    },
                    "modelCount" to relation.modelNames.size
                )
            }
        )
        val template = actionTemplate?.takeIf { it.isNotBlank() } ?: responseFormat.successTemplate
        return if (template.isNullOrBlank()) {
            buildGroupModelsDefault(query, relations)
        } else {
            render(template, variables, context)
        }
    }

    fun formatModelGroups(
        query: String,
        relations: List<ModelGroupRelation>,
        responseFormat: ModelPlazaResponseFormatConfig,
        actionTemplate: String?,
        context: ExecutionContext
    ): String {
        val variables = mapOf(
            "query" to query,
            "count" to relations.size,
            "totalItems" to relations.sumOf { it.groupNames.size },
            "relations" to relations.map { relation ->
                mapOf(
                    "name" to relation.modelName,
                    "model" to relation.modelName,
                    "modelName" to relation.modelName,
                    "groups" to relation.groupNames.mapIndexed { index, name ->
                        mapOf("name" to name, "group" to name, "_index" to index)
                    },
                    "groupCount" to relation.groupNames.size
                )
            }
        )
        val template = actionTemplate?.takeIf { it.isNotBlank() } ?: responseFormat.successTemplate
        return if (template.isNullOrBlank()) {
            buildModelGroupsDefault(query, relations)
        } else {
            render(template, variables, context)
        }
    }

    fun renderMessage(
        template: String,
        query: String,
        context: ExecutionContext
    ): String = render(template, mapOf("query" to query), context)

    fun formatModelPricing(
        query: String,
        details: List<ModelPricingDetail>,
        responseFormat: ModelPlazaResponseFormatConfig,
        action: ActionConfig,
        context: ExecutionContext
    ): String {
        val variables = mapOf(
            "query" to query,
            "count" to details.size,
            "models" to details.map { detail ->
                mapOf(
                    "name" to detail.modelName,
                    "model" to detail.modelName,
                    "badge" to detail.badge,
                    "official" to priceVariables(detail.official),
                    "groups" to detail.groups.map { group ->
                        mapOf(
                            "name" to group.groupName,
                            "group" to group.groupName,
                            "rate" to ModelPlazaPriceCard.formatRate(group.rateMultiplier),
                            "discount" to ModelPlazaPriceCard.formatDiscount(group.rateMultiplier),
                            "isFinalPrice" to group.isFinalPrice,
                            "price" to priceVariables(group.price),
                            "endpoints" to group.supportedEndpoints.map(::endpointVariables)
                        )
                    }
                )
            }
        )
        val template = action.params["success_template"]?.toString()?.takeIf { it.isNotBlank() }
            ?: responseFormat.successTemplate
        return if (template.isNullOrBlank()) {
            buildModelPricingDefault(query, details)
        } else {
            render(template, variables, context)
        }
    }

    fun formatGroupPricing(
        query: String,
        details: List<GroupPricingDetail>,
        responseFormat: ModelPlazaResponseFormatConfig,
        action: ActionConfig,
        context: ExecutionContext
    ): String {
        val variables = mapOf(
            "query" to query,
            "count" to details.size,
            "groups" to details.map { detail ->
                mapOf(
                    "name" to detail.groupName,
                    "group" to detail.groupName,
                    "rate" to ModelPlazaPriceCard.formatRate(detail.rateMultiplier),
                    "discount" to ModelPlazaPriceCard.formatDiscount(detail.rateMultiplier),
                    "models" to detail.models.map { model ->
                        mapOf(
                            "name" to model.modelName,
                            "model" to model.modelName,
                            "badge" to model.badge,
                            "price" to priceVariables(model.price)
                        )
                    }
                )
            }
        )
        val template = action.params["success_template"]?.toString()?.takeIf { it.isNotBlank() }
            ?: responseFormat.successTemplate
        return if (template.isNullOrBlank()) {
            buildGroupPricingDefault(query, details)
        } else {
            render(template, variables, context)
        }
    }

    private fun priceVariables(price: TokenPrice): Map<String, Any?> = mapOf(
        "input" to ModelPlazaPriceCard.formatPriceValue(price.inputPrice),
        "output" to ModelPlazaPriceCard.formatPriceValue(price.outputPrice),
        "cacheWrite" to ModelPlazaPriceCard.formatPriceValue(price.cacheWritePrice),
        "cacheRead" to ModelPlazaPriceCard.formatPriceValue(price.cacheReadPrice)
    )

    private fun endpointVariables(endpoint: PlazaEndpoint): Map<String, Any?> = mapOf(
        "key" to endpoint.key,
        "method" to endpoint.method,
        "path" to endpoint.path,
        "transport" to endpoint.transport
    )

    private fun buildModelPricingDefault(query: String, details: List<ModelPricingDetail>): String = buildString {
        appendLine("包含 '$query' 的模型（共 ${details.size} 个）:")
        details.forEach { detail ->
            appendLine()
            appendLine("模型：${detail.modelName}${detail.badge?.let { " [$it]" } ?: ""}")
            appendLine(
                "官方定价：输入 ${ModelPlazaPriceCard.formatPriceValue(detail.official.inputPrice)}/M" +
                    "，输出 ${ModelPlazaPriceCard.formatPriceValue(detail.official.outputPrice)}/M" +
                    "，缓存写入 ${ModelPlazaPriceCard.formatPriceValue(detail.official.cacheWritePrice)}/M" +
                    "，缓存读取 ${ModelPlazaPriceCard.formatPriceValue(detail.official.cacheReadPrice)}/M"
            )
            if (detail.groups.isEmpty()) {
                appendLine("- （无可用分组）")
            } else {
                detail.groups.forEach { group ->
                    appendLine(
                        "- ${group.groupName} ×${ModelPlazaPriceCard.formatRate(group.rateMultiplier)}" +
                            "：输入 ${ModelPlazaPriceCard.formatPriceValue(group.price.inputPrice)}/M" +
                            "，输出 ${ModelPlazaPriceCard.formatPriceValue(group.price.outputPrice)}/M" +
                            "，缓存读取 ${ModelPlazaPriceCard.formatPriceValue(group.price.cacheReadPrice)}/M"
                    )
                }
                // 端点集合相同的分组合并展示：分组名逐行列出，端点只写一次
                val clusters = ModelPlazaPriceCard.clusterEndpointGroups(detail.groups)
                if (clusters.isNotEmpty()) {
                    appendLine()
                    appendLine("支持端点：")
                    clusters.forEach { cluster ->
                        cluster.groupNames.forEach { appendLine(it) }
                        cluster.endpoints.forEach { appendLine("- ${it.method} ${it.path}") }
                        appendLine()
                    }
                }
            }
        }
    }.trimEnd()

    private fun buildGroupPricingDefault(query: String, details: List<GroupPricingDetail>): String = buildString {
        appendLine("包含 '$query' 的分组（共 ${details.size} 个）:")
        details.forEach { detail ->
            appendLine()
            appendLine("分组：${detail.groupName} ×${ModelPlazaPriceCard.formatRate(detail.rateMultiplier)}")
            if (detail.models.isEmpty()) {
                appendLine("- （无可用模型）")
            } else {
                detail.models.forEach { model ->
                    appendLine(
                        "- ${model.modelName}" +
                            "：输入 ${ModelPlazaPriceCard.formatPriceValue(model.price.inputPrice)}/M" +
                            "，输出 ${ModelPlazaPriceCard.formatPriceValue(model.price.outputPrice)}/M" +
                            "，缓存读取 ${ModelPlazaPriceCard.formatPriceValue(model.price.cacheReadPrice)}/M"
                    )
                }
            }
        }
    }.trimEnd()

    private fun render(
        template: String,
        variables: Map<String, Any?>,
        context: ExecutionContext
    ): String = MessageTemplateFormatter.render(
        template = template,
        variables = variables,
        config = FormatterConfig(),
        context = context
    )

    private fun buildGroupModelsDefault(
        query: String,
        relations: List<GroupModelsRelation>
    ): String = buildString {
        appendLine("包含 '$query' 的分组（共 ${relations.size} 个）:")
        relations.forEachIndexed { index, relation ->
            if (index > 0) appendLine()
            appendLine("分组：${relation.groupName}")
            if (relation.modelNames.isEmpty()) {
                appendLine("- （无可用模型）")
            } else {
                relation.modelNames.forEach { appendLine("- $it") }
            }
        }
    }.trimEnd()

    private fun buildModelGroupsDefault(
        query: String,
        relations: List<ModelGroupRelation>
    ): String = buildString {
        appendLine("包含 '$query' 的模型（共 ${relations.size} 个）:")
        relations.forEachIndexed { index, relation ->
            if (index > 0) appendLine()
            appendLine("模型：${relation.modelName}")
            if (relation.groupNames.isEmpty()) {
                appendLine("- （无可用分组）")
            } else {
                relation.groupNames.forEach { appendLine("- $it") }
            }
        }
    }.trimEnd()
}
