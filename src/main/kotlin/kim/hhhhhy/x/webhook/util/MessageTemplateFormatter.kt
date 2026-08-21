package kim.hhhhhy.x.webhook.util

import kim.hhhhhy.x.webhook.config.WebHookDebug
import kim.hhhhhy.x.webhook.model.ExecutionContext
import kim.hhhhhy.x.webhook.template.TemplateEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

public data class FormatterConfig(
    val dateFormat: String = "yyyy年MM月dd日",
    val timezone: String = "Asia/Shanghai",
    val compactNumbers: Boolean = true,
    val priceUnit: String = "cents",
    val maxHistoryPoints: Int = 5
)

internal object MessageTemplateFormatter {
    private const val LITERAL_TEMPLATE_MARKER: String = "\uE000XWEBHOOK_TEMPLATE_MARKER\uE001"

    public fun formatNumber(number: Double, compact: Boolean = true): String {
        return if (compact) {
            when {
                number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000)
                number >= 1_000 -> String.format("%.1fK", number / 1_000)
                else -> String.format("%.0f", number)
            }
        } else {
            String.format("%.0f", number)
        }
    }

    public fun formatPrice(price: Double, unit: String = "cents"): String {
        return when (unit.lowercase()) {
            "cents" -> "${(price * 100).toInt()}¢"
            "dollars", "usd" -> String.format("$%.2f", price)
            "percent", "%" -> "${(price * 100).toInt()}%"
            else -> price.toString()
        }
    }

    public fun formatDate(timestamp: Long, format: String, timezone: String): String {
        val dateFormat = SimpleDateFormat(format, Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timezone)
        }
        return dateFormat.format(Date(timestamp))
    }

    internal fun render(
        template: String,
        variables: Map<String, Any?>,
        config: FormatterConfig,
        context: ExecutionContext? = null
    ): String {
        val parser = TemplateParser(template)
        val nodes = parser.parse()
        if (nodes == null) {
            WebHookDebug.log("[模板] 自定义模板结构无效，已保留原文：${parser.error ?: "未知错误"}")
            return template
        }

        val rendered = renderNodes(nodes, variables, config)
        val resolved = if (context != null) {
            TemplateEngine.renderString(rendered, context)
        } else {
            rendered
        }
        return resolved.replace(LITERAL_TEMPLATE_MARKER, "\${")
    }

    private fun renderNodes(
        nodes: List<TemplateNode>,
        variables: Map<String, Any?>,
        config: FormatterConfig
    ): String = buildString {
        nodes.forEach { node ->
            when (node) {
                is TextNode -> append(node.text)
                is ExpressionNode -> append(renderExpression(node, variables, config))
                is ForEachNode -> append(renderForEach(node, variables, config))
                is IfNode -> {
                    val value = resolveVariable(node.path, variables)
                    val matched = if (node.negated) !isTruthy(value) else isTruthy(value)
                    append(renderNodes(if (matched) node.thenNodes else node.elseNodes, variables, config))
                }
            }
        }
    }

    private fun renderForEach(
        node: ForEachNode,
        variables: Map<String, Any?>,
        config: FormatterConfig
    ): String {
        val items = asList(resolveVariable(node.path, variables))
        val limitedItems = node.limit?.let { items.take(it.coerceAtLeast(0)) } ?: items
        return buildString {
            limitedItems.forEachIndexed { index, item ->
                val scopedVariables = variables.toMutableMap()
                scopedVariables[node.alias] = when (item) {
                    is Map<*, *> -> item.entries
                        .mapNotNull { (key, value) -> key?.toString()?.let { it to value } }
                        .toMap() + ("_index" to index)
                    else -> item
                }
                scopedVariables["${node.alias}_index"] = index
                append(renderNodes(node.children, scopedVariables, config))
            }
        }
    }

    private fun renderExpression(
        node: ExpressionNode,
        variables: Map<String, Any?>,
        config: FormatterConfig
    ): String {
        val expression = node.expression
        val rendered = when {
            expression.startsWith("format_number(") -> {
                val value = resolveFunctionArgument(expression, "format_number", variables)?.toString()?.toDoubleOrNull()
                value?.let { formatNumber(it, config.compactNumbers) }
            }
            expression.startsWith("format_price(") -> {
                val value = resolveFunctionArgument(expression, "format_price", variables)?.toString()?.toDoubleOrNull()
                value?.let { formatPrice(it, config.priceUnit) }
            }
            expression.startsWith("format_date(") -> {
                val value = resolveFunctionArgument(expression, "format_date", variables)?.toString()?.toLongOrNull()
                value?.let { formatDate(it, config.dateFormat, config.timezone) }
            }
            else -> resolveVariable(expression, variables)?.toString()
        }
        return rendered?.toString()?.replace("\${", LITERAL_TEMPLATE_MARKER) ?: node.raw
    }

    private fun resolveFunctionArgument(
        expression: String,
        functionName: String,
        variables: Map<String, Any?>
    ): Any? {
        if (!expression.startsWith("$functionName(")) return null
        val start = expression.indexOf('(')
        val end = expression.lastIndexOf(')')
        if (start == -1 || end <= start) return null
        return resolveVariable(expression.substring(start + 1, end).trim(), variables)
    }

    private fun resolveVariable(path: String, variables: Map<String, Any?>): Any? {
        if (variables.containsKey(path)) return variables[path]
        val parts = path.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        var current: Any? = variables
        for (part in parts) {
            current = when (current) {
                is Map<*, *> -> current.entries.firstOrNull { it.key?.toString() == part }?.value
                is List<*> -> {
                    val list = current
                    part.toIntOrNull()?.let { list.getOrNull(it) }
                }
                is Array<*> -> {
                    val array = current
                    part.toIntOrNull()?.let { array.getOrNull(it) }
                }
                else -> return null
            }
        }
        return current
    }

    private fun asList(value: Any?): List<Any?> = when (value) {
        null -> emptyList()
        is List<*> -> value
        is Iterable<*> -> value.toList()
        is Array<*> -> value.toList()
        else -> emptyList()
    }

    private fun isTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotBlank()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        is Array<*> -> value.isNotEmpty()
        else -> true
    }

    private sealed interface TemplateNode

    private data class TextNode(val text: String) : TemplateNode

    private data class ExpressionNode(
        val expression: String,
        val raw: String
    ) : TemplateNode

    private data class ForEachNode(
        val alias: String,
        val path: String,
        val limit: Int?,
        val children: List<TemplateNode>
    ) : TemplateNode

    private data class IfNode(
        val path: String,
        val negated: Boolean,
        val thenNodes: List<TemplateNode>,
        val elseNodes: List<TemplateNode>
    ) : TemplateNode

    private data class ParseSection(
        val nodes: List<TemplateNode>,
        val terminator: String?
    )

    private class TemplateParser(private val template: String) {
        private val foreachPattern = Regex(
            """^foreach\s+([A-Za-z_][A-Za-z0-9_]*)\s+in\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\s*\|\s*limit\s*:\s*(\d+))?$"""
        )
        private val ifPattern = Regex("""^if\s+(!?)([A-Za-z_][A-Za-z0-9_.]*)$""")
        private var position: Int = 0

        var error: String? = null
            private set

        fun parse(): List<TemplateNode>? {
            val section = parseSection(emptySet()) ?: return null
            if (section.terminator != null) {
                error = "出现意外结束标记 ${section.terminator}"
                return null
            }
            return section.nodes
        }

        private fun parseSection(stopTokens: Set<String>): ParseSection? {
            val nodes = mutableListOf<TemplateNode>()
            while (position < template.length) {
                val markerStart = template.indexOf("\${", position)
                if (markerStart == -1) {
                    nodes += TextNode(template.substring(position))
                    position = template.length
                    break
                }
                if (markerStart > position) {
                    nodes += TextNode(template.substring(position, markerStart))
                }

                val markerEnd = template.indexOf('}', markerStart + 2)
                if (markerEnd == -1) {
                    nodes += TextNode(template.substring(markerStart))
                    position = template.length
                    break
                }

                val raw = template.substring(markerStart, markerEnd + 1)
                val directive = template.substring(markerStart + 2, markerEnd).trim()
                position = markerEnd + 1

                if (directive in stopTokens) {
                    return ParseSection(nodes, directive)
                }

                when {
                    directive.startsWith("foreach ") -> {
                        val match = foreachPattern.matchEntire(directive)
                        if (match == null) {
                            nodes += ExpressionNode(directive, raw)
                            continue
                        }
                        val childSection = parseSection(setOf("endforeach")) ?: return null
                        if (childSection.terminator != "endforeach") {
                            error = "foreach 缺少 endforeach"
                            return null
                        }
                        nodes += ForEachNode(
                            alias = match.groupValues[1],
                            path = match.groupValues[2],
                            limit = match.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull(),
                            children = childSection.nodes
                        )
                    }
                    directive.startsWith("if ") -> {
                        val match = ifPattern.matchEntire(directive)
                        if (match == null) {
                            nodes += ExpressionNode(directive, raw)
                            continue
                        }
                        val thenSection = parseSection(setOf("else", "endif")) ?: return null
                        val elseNodes = if (thenSection.terminator == "else") {
                            val elseSection = parseSection(setOf("endif")) ?: return null
                            if (elseSection.terminator != "endif") {
                                error = "if 的 else 分支缺少 endif"
                                return null
                            }
                            elseSection.nodes
                        } else {
                            emptyList()
                        }
                        if (thenSection.terminator != "else" && thenSection.terminator != "endif") {
                            error = "if 缺少 endif"
                            return null
                        }
                        nodes += IfNode(
                            path = match.groupValues[2],
                            negated = match.groupValues[1] == "!",
                            thenNodes = thenSection.nodes,
                            elseNodes = elseNodes
                        )
                    }
                    directive == "endforeach" || directive == "else" || directive == "endif" -> {
                        error = "出现未配对标记 $directive"
                        return null
                    }
                    else -> nodes += ExpressionNode(directive, raw)
                }
            }

            if (stopTokens.isNotEmpty()) {
                error = "缺少结束标记 ${stopTokens.joinToString(" 或 ")}"
                return null
            }
            return ParseSection(nodes, null)
        }
    }
}
