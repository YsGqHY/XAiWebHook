package kim.hhhhhy.x.webhook.action

import kim.hhhhhy.x.webhook.config.ModelPlazaQueryConfig
import kim.hhhhhy.x.webhook.config.ModelPlazaResponseFormatConfig
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.scraper.GroupModelsRelation
import kim.hhhhhy.x.webhook.scraper.ModelGroupRelation
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
