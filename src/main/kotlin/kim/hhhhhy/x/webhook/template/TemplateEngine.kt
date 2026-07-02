package kim.hhhhhy.x.webhook.template

import kim.hhhhhy.x.webhook.model.ExecutionContext

internal object TemplateEngine {
    private val expressionRegex = Regex("""\$\{([^}]+)}""")
    private const val MAX_DEPTH = 32
    private const val REQUEST_BODY_MARKDOWN_IMAGE_THRESHOLD = 200

    fun render(value: Any?, context: ExecutionContext): Any? {
        return when (value) {
            is String -> renderString(value, context)
            is Map<*, *> -> value.mapNotNull { (key, mapValue) ->
                key?.toString()?.let { it to render(mapValue, context) }
            }.toMap()
            is List<*> -> value.map { render(it, context) }
            else -> value
        }
    }

    fun renderString(template: String, context: ExecutionContext): String {
        if (!context.config.templates.enableExpressions) return template
        return expressionRegex.replace(template) { match ->
            val expression = match.groupValues[1]
            val value = evaluateValue(expression, context)
            renderReplacementText(value, match.value, context)
        }
    }

    fun renderIncomingMessage(template: String, context: ExecutionContext): List<IncomingMessageSegment> {
        if (!context.config.templates.enableExpressions) return listOf(IncomingMessageSegment.Text(template))

        val segments = mutableListOf<IncomingMessageSegment>()
        var cursor = 0
        expressionRegex.findAll(template).forEach { match ->
            segments.appendText(template.substring(cursor, match.range.first))
            val expression = match.groupValues[1]
            val markdownImage = requestBodyMarkdownImage(expression, context)
            if (markdownImage != null) {
                segments += IncomingMessageSegment.MarkdownImage(markdownImage)
            } else {
                val value = evaluateValue(expression, context)
                segments.appendText(renderReplacementText(value, match.value, context))
            }
            cursor = match.range.last + 1
        }
        segments.appendText(template.substring(cursor))
        return segments.ifEmpty { listOf(IncomingMessageSegment.Text("")) }
    }

    fun evaluateCondition(condition: String?, context: ExecutionContext): Boolean {
        val normalized = condition?.trim()?.ifBlank { null } ?: return true
        return evaluateBoolean(normalized, context)
    }

    fun evaluateValue(expression: String, context: ExecutionContext): Any? =
        evaluateValue(expression, context, 0)

    private fun evaluateValue(expression: String, context: ExecutionContext, depth: Int): Any? {
        if (depth > MAX_DEPTH) return null
        val trimmed = unwrapExpression(expression.trim())
        val defaultParts = splitTopLevel(trimmed, "?:")
        if (defaultParts.size == 2) {
            return evaluateValue(defaultParts[0], context, depth + 1) ?: parseLiteral(defaultParts[1].trim())
        }
        return parseLiteral(trimmed) ?: resolvePath(trimmed, context)
    }

    private fun evaluateBoolean(expression: String, context: ExecutionContext): Boolean =
        evaluateBoolean(expression, context, 0)

    private fun evaluateBoolean(expression: String, context: ExecutionContext, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        val orParts = splitTopLevel(expression, "||")
        if (orParts.size > 1) return orParts.any { evaluateBoolean(it, context, depth + 1) }

        val andParts = splitTopLevel(expression, "&&")
        if (andParts.size > 1) return andParts.all { evaluateBoolean(it, context, depth + 1) }

        var atom = expression.trim()
        if (atom.startsWith("!")) return !evaluateBoolean(atom.removePrefix("!"), context, depth + 1)
        atom = stripOuterParentheses(atom)

        parseFunction(atom)?.let { call ->
            val args = splitArguments(call.arguments)
            return when (call.name) {
                "contains" -> args.size == 2 && evaluateValue(args[0], context, depth + 1).toString().contains(
                    evaluateValue(args[1], context, depth + 1).toString()
                )
                "startsWith" -> args.size == 2 && evaluateValue(args[0], context, depth + 1).toString().startsWith(
                    evaluateValue(args[1], context, depth + 1).toString()
                )
                "endsWith" -> args.size == 2 && evaluateValue(args[0], context, depth + 1).toString().endsWith(
                    evaluateValue(args[1], context, depth + 1).toString()
                )
                else -> false
            }
        }

        val notEquals = splitTopLevel(atom, "!=")
        if (notEquals.size == 2) return evaluateValue(notEquals[0], context, depth + 1).asComparable() != evaluateValue(notEquals[1], context, depth + 1).asComparable()

        val equals = splitTopLevel(atom, "==")
        if (equals.size == 2) return evaluateValue(equals[0], context, depth + 1).asComparable() == evaluateValue(equals[1], context, depth + 1).asComparable()

        return evaluateValue(atom, context, depth + 1).isTruthy()
    }

    private fun resolvePath(path: String, context: ExecutionContext): Any? {
        val normalized = unwrapExpression(path.trim())
        val parts = normalized.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        var current: Any? = when (parts.first()) {
            "request" -> context.request?.let {
                mapOf(
                    "method" to it.method,
                    "path" to it.path,
                    "query" to it.query,
                    "headers" to it.headers,
                    "body" to it.body,
                    "remoteHost" to it.remoteHost
                )
            }
            "event" -> context.event?.let {
                mapOf(
                    "type" to it.type,
                    "botId" to it.botId,
                    "groupId" to it.groupId,
                    "friendId" to it.friendId,
                    "senderId" to it.senderId,
                    "senderName" to it.senderName,
                    "messageText" to it.messageText,
                    "timestamp" to it.timestamp
                )
            }
            else -> null
        }

        parts.drop(1).forEach { key ->
            current = when (val value = current) {
                is Map<*, *> -> value[key]
                else -> null
            }
        }
        return current
    }

    private fun parseLiteral(value: String): Any? {
        val trimmed = value.trim()
        if ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith('\'') && trimmed.endsWith('\''))) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false
        if (trimmed.equals("null", ignoreCase = true)) return null
        trimmed.toLongOrNull()?.let { return it }
        trimmed.toDoubleOrNull()?.let { return it }
        return null
    }

    private fun unwrapExpression(value: String): String {
        return if (value.startsWith("${'$'}{") && value.endsWith("}")) {
            value.substring(2, value.length - 1).trim()
        } else {
            value
        }
    }

    private fun splitTopLevel(value: String, delimiter: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (quote != null) {
                if (char == quote) quote = null
                index++
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0 && value.startsWith(delimiter, index)) {
                result += value.substring(start, index).trim()
                index += delimiter.length
                start = index
                continue
            }
            index++
        }
        if (result.isEmpty()) return listOf(value.trim())
        result += value.substring(start).trim()
        return result
    }

    private fun splitArguments(value: String): List<String> = splitTopLevel(value, ",")

    private fun parseFunction(value: String): FunctionCall? {
        val open = value.indexOf('(')
        if (open <= 0 || !value.endsWith(')')) return null
        val name = value.substring(0, open).trim()
        val args = value.substring(open + 1, value.length - 1)
        return FunctionCall(name = name, arguments = args)
    }

    private fun stripOuterParentheses(value: String): String {
        var result = value.trim()
        while (result.startsWith('(') && result.endsWith(')')) {
            result = result.substring(1, result.length - 1).trim()
        }
        return result
    }

    private fun Any?.asComparable(): String = when (this) {
        is Number -> this.toDouble().toString()
        else -> this?.toString() ?: ""
    }

    private fun Any?.isTruthy(): Boolean = when (this) {
        null -> false
        is Boolean -> this
        is Number -> this.toDouble() != 0.0
        is String -> this.isNotBlank() && !this.equals("false", ignoreCase = true)
        else -> true
    }

    private fun requestBodyMarkdownImage(expression: String, context: ExecutionContext): String? {
        if (context.request == null) return null
        val variable = primaryExpression(expression)
        if (!variable.startsWith("request.body.")) return null
        val value = evaluateValue(variable, context) ?: return null
        val text = value.toString()
        return text.takeIf { it.length > REQUEST_BODY_MARKDOWN_IMAGE_THRESHOLD }
    }

    private fun primaryExpression(expression: String): String {
        val normalized = unwrapExpression(expression.trim())
        val primary = splitTopLevel(normalized, "?:").firstOrNull().orEmpty()
        return stripOuterParentheses(unwrapExpression(primary.trim()))
    }

    private fun renderReplacementText(value: Any?, original: String, context: ExecutionContext): String {
        return when {
            value != null -> value.toString()
            context.config.templates.strictMissingVariables -> original
            else -> ""
        }
    }

    private fun MutableList<IncomingMessageSegment>.appendText(value: String): Unit {
        if (value.isEmpty()) return
        val last = lastOrNull()
        if (last is IncomingMessageSegment.Text) {
            this[lastIndex] = IncomingMessageSegment.Text(last.value + value)
        } else {
            this += IncomingMessageSegment.Text(value)
        }
    }

    private data class FunctionCall(
        val name: String,
        val arguments: String
    )
}

internal sealed class IncomingMessageSegment {
    internal data class Text(val value: String) : IncomingMessageSegment()
    internal data class MarkdownImage(val markdown: String) : IncomingMessageSegment()
}
