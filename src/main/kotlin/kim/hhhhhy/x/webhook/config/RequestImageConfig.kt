package kim.hhhhhy.x.webhook.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 请求记录图片样式（request_image.yml 的不可变模型）。
 *
 * 构造器不做校验，便于接口返回“无效 DTO”的失败结果；文件、接口和命令入口统一调用 [validate]。
 */
public data class RequestImageStyle(
    public val version: Int = 1,
    public val border: RequestImageBorderConfig = RequestImageBorderConfig(),
    public val percentage: RequestImagePercentageConfig = RequestImagePercentageConfig(),
    public val margin: RequestImageMarginConfig = RequestImageMarginConfig(),
    public val background: RequestImageBackgroundConfig = RequestImageBackgroundConfig(),
    public val output: RequestImageOutputConfig = RequestImageOutputConfig()
) {
    /** 校验全部字段；失败抛出 [IllegalArgumentException]，消息以字段路径开头。 */
    public fun validate(): Unit {
        if (version != REQUEST_IMAGE_CONFIG_VERSION) {
            requestImageFieldError("version", "仅支持 $REQUEST_IMAGE_CONFIG_VERSION，当前为 $version")
        }
        border.validate("border")
        percentage.validate("percentage")
        margin.validate("margin")
        background.validate("background")
        output.validate("output")
    }
}

/** 原截图外框。 */
public data class RequestImageBorderConfig(
    public val enabled: Boolean = true,
    public val widthPixels: Int = 1,
    public val paddingPixels: Int = 2,
    public val color: String = "#FF3B30"
) {
    /** 边框颜色的 ARGB 值；调用前应已通过 [RequestImageStyle.validate]。 */
    internal fun colorArgb(): Int = parseRequestImageColor(color)

    internal fun validate(prefix: String): Unit {
        requireIntRange("$prefix.width_px", widthPixels, 0, 16)
        requireIntRange("$prefix.padding_px", paddingPixels, 0, 128)
        requireColor("$prefix.color", color)
    }
}

/** 左侧成功率百分比副本。 */
public data class RequestImagePercentageConfig(
    public val enabled: Boolean = true,
    public val gapPixels: Int = 6,
    public val verticalAlign: String = "center"
) {
    internal fun validate(prefix: String): Unit {
        requireIntRange("$prefix.gap_px", gapPixels, 0, 256)
        if (verticalAlign !in REQUEST_IMAGE_VERTICAL_ALIGNS) {
            requestImageFieldError("$prefix.vertical_align", "仅支持 top、center、bottom")
        }
    }
}

/** 四侧外边距；百分数以加外边距之前的完整内容尺寸为基准。 */
public data class RequestImageMarginConfig(
    public val leftPercent: Double = 5.0,
    public val rightPercent: Double = 5.0,
    public val topPercent: Double = 1.0,
    public val bottomPercent: Double = 1.0,
    public val minPixels: Int = 1
) {
    internal fun leftPixels(contentWidth: Long): Long = pixelsFor(contentWidth, leftPercent)
    internal fun rightPixels(contentWidth: Long): Long = pixelsFor(contentWidth, rightPercent)
    internal fun topPixels(contentHeight: Long): Long = pixelsFor(contentHeight, topPercent)
    internal fun bottomPixels(contentHeight: Long): Long = pixelsFor(contentHeight, bottomPercent)

    /** 0% 关闭该侧留白；非零时按十进制精确计算并向上取整，再不少于 [minPixels]。 */
    internal fun pixelsFor(contentSize: Long, percent: Double): Long {
        require(contentSize >= 0L) { "contentSize must not be negative" }
        require(percent.isFinite() && percent >= 0.0) { "percent must be finite and non-negative" }
        if (percent == 0.0) return 0L
        val raw = BigDecimal.valueOf(contentSize)
            .multiply(BigDecimal.valueOf(percent))
            .divide(BigDecimal(100))
            .setScale(0, RoundingMode.CEILING)
            .longValueExact()
        return maxOf(raw, minPixels.toLong())
    }

    internal fun validate(prefix: String): Unit {
        requirePercent("$prefix.left_percent", leftPercent)
        requirePercent("$prefix.right_percent", rightPercent)
        requirePercent("$prefix.top_percent", topPercent)
        requirePercent("$prefix.bottom_percent", bottomPercent)
        requireIntRange("$prefix.min_px", minPixels, 0, 128)
    }
}

/** 外侧留白与内容区底色。 */
public data class RequestImageBackgroundConfig(
    public val marginColor: String = "#FFFFFF",
    public val contentColor: String = REQUEST_IMAGE_AUTO_COLOR
) {
    internal fun marginArgb(): Int = parseRequestImageColor(marginColor)

    /** 固定内容底色的 ARGB；`auto` 返回 null，表示取原截图左上角像素。 */
    internal fun contentArgbOrNull(): Int? =
        if (contentColor == REQUEST_IMAGE_AUTO_COLOR) null else parseRequestImageColor(contentColor)

    internal fun validate(prefix: String): Unit {
        requireColor("$prefix.margin_color", marginColor)
        if (contentColor != REQUEST_IMAGE_AUTO_COLOR) {
            requireColor("$prefix.content_color", contentColor, allowAuto = true)
        }
    }
}

/** 合成输出限制。 */
public data class RequestImageOutputConfig(
    public val maxPixels: Long = 16_000_000L
) {
    internal fun validate(prefix: String): Unit {
        if (maxPixels !in 1L..64_000_000L) {
            requestImageFieldError("$prefix.max_pixels", "必须在 1..64000000 之间，当前为 $maxPixels")
        }
    }
}

internal const val REQUEST_IMAGE_CONFIG_VERSION: Int = 1
internal const val REQUEST_IMAGE_AUTO_COLOR: String = "auto"
internal val REQUEST_IMAGE_VERTICAL_ALIGNS: Set<String> = setOf("top", "center", "bottom")
private val REQUEST_IMAGE_COLOR_PATTERN = Regex("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")

/** 解析 `#RRGGBB`（不透明）或 `#AARRGGBB`（alpha 在前）为 ARGB 整数。 */
internal fun parseRequestImageColor(value: String): Int {
    require(REQUEST_IMAGE_COLOR_PATTERN.matches(value)) { "color must be #RRGGBB or #AARRGGBB" }
    val hex = value.substring(1)
    return if (hex.length == 6) {
        (0xFF000000L or hex.toLong(16)).toInt()
    } else {
        hex.toLong(16).toInt()
    }
}

internal fun requestImageFieldError(path: String, reason: String): Nothing =
    throw IllegalArgumentException("$path: $reason")

private fun requireIntRange(path: String, value: Int, min: Int, max: Int): Unit {
    if (value !in min..max) requestImageFieldError(path, "必须在 $min..$max 之间，当前为 $value")
}

private fun requirePercent(path: String, value: Double): Unit {
    if (!value.isFinite()) requestImageFieldError(path, "必须是有限数字")
    if (value < 0.0 || value > 50.0) requestImageFieldError(path, "必须在 0..50 之间，当前为 $value")
}

private fun requireColor(path: String, value: String, allowAuto: Boolean = false): Unit {
    if (!REQUEST_IMAGE_COLOR_PATTERN.matches(value)) {
        val suffix = if (allowAuto) "，或填写 auto" else ""
        requestImageFieldError(path, "颜色必须是 #RRGGBB 或 #AARRGGBB 格式$suffix")
    }
}

/**
 * request_image.yml 的严格编解码。
 *
 * 只组合 YAML 节点树（SafeConstructor + LoaderOptions），不构造任何 Java 对象；
 * 拒绝重复键、未知字段、显式 null、空根文档、多文档、锚点/别名、自定义标签和非严格数值/布尔。
 * 错误信息只包含字段路径、行列号与简短原因，不回显 YAML 原文。
 */
internal object RequestImageConfigCodec {
    internal const val MAX_BYTES: Int = 64 * 1024
    private const val MAX_NESTING_DEPTH: Int = 8
    private const val MAX_MESSAGE_LENGTH: Int = 160
    private val INTEGER_PATTERN = Regex("[-+]?(0|[1-9][0-9]*)")
    private val DECIMAL_PATTERN = Regex("[-+]?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?")
    private val ROOT_KEYS = setOf("version", "border", "percentage", "margin", "background", "output")

    /** 校验 64 KiB 上限与严格 UTF-8 后解析。 */
    internal fun parseBytes(bytes: ByteArray): RequestImageStyle {
        requireSize(bytes.size.toLong())
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (ignored: CharacterCodingException) {
            throw IllegalArgumentException("配置文件不是有效的 UTF-8 文本")
        }
        return parse(text)
    }

    /** 解析单个 YAML 文档为完整候选样式并全量校验；缺省字段使用内置默认值，不与旧配置合并。 */
    internal fun parse(yaml: String): RequestImageStyle {
        val content = yaml.removePrefix("\uFEFF")
        requireSize(content.toByteArray(StandardCharsets.UTF_8).size.toLong())
        val root = composeSingleDocument(content)
        val style = buildStyle(root)
        style.validate()
        return style
    }

    private fun requireSize(size: Long): Unit {
        require(size <= MAX_BYTES) { "配置内容超过 ${MAX_BYTES / 1024} KiB 上限" }
    }

    private fun newYaml(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 1
            allowRecursiveKeys = false
            nestingDepthLimit = MAX_NESTING_DEPTH
            codePointLimit = MAX_BYTES
        }
        return Yaml(SafeConstructor(options), Representer(DumperOptions()), DumperOptions(), options)
    }

    private fun composeSingleDocument(content: String): Node {
        try {
            val iterator = newYaml().composeAll(StringReader(content)).iterator()
            if (!iterator.hasNext()) throw IllegalArgumentException("配置文件为空，至少需要一个字段")
            val root = iterator.next()
            if (iterator.hasNext()) throw IllegalArgumentException("只允许一个 YAML 文档")
            return root
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: MarkedYAMLException) {
            throw IllegalArgumentException(syntaxMessage(e))
        } catch (e: YAMLException) {
            // 非 Marked 异常（如别名/长度限制）的消息不含原文片段，截断后可安全展示。
            throw IllegalArgumentException("YAML 解析失败：${safeText(e.message ?: e.javaClass.simpleName)}")
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("YAML 解析失败：${e.javaClass.simpleName}")
        }
    }

    private fun syntaxMessage(error: MarkedYAMLException): String {
        val mark = error.problemMark ?: error.contextMark
        val location = mark?.let { "（第 ${it.line + 1} 行第 ${it.column + 1} 列）" }.orEmpty()
        // 只取 problem 描述，不使用 getMessage()，后者会附带原文片段。
        val problem = error.problem ?: error.context ?: "格式错误"
        return "YAML 语法错误$location：${safeText(problem)}"
    }

    private fun safeText(text: String): String {
        val singleLine = text.replace(Regex("[\\r\\n\\t]+"), " ").filter { it >= ' ' }.trim()
        return if (singleLine.length <= MAX_MESSAGE_LENGTH) singleLine else singleLine.take(MAX_MESSAGE_LENGTH) + "…"
    }

    private fun buildStyle(root: Node): RequestImageStyle {
        if (root is ScalarNode && root.tag == Tag.NULL) {
            throw IllegalArgumentException("配置文件为空，至少需要一个字段")
        }
        val fields = mapping(root, "根节点", ROOT_KEYS)
        if (fields.isEmpty()) throw IllegalArgumentException("配置文件为空，至少需要一个字段")
        val defaults = RequestImageStyle()
        return RequestImageStyle(
            version = fields["version"]?.let { int("version", it) } ?: defaults.version,
            border = fields["border"]?.let { border(it) } ?: defaults.border,
            percentage = fields["percentage"]?.let { percentage(it) } ?: defaults.percentage,
            margin = fields["margin"]?.let { margin(it) } ?: defaults.margin,
            background = fields["background"]?.let { background(it) } ?: defaults.background,
            output = fields["output"]?.let { output(it) } ?: defaults.output
        )
    }

    private fun border(node: Node): RequestImageBorderConfig {
        val fields = mapping(node, "border", setOf("enabled", "width_px", "padding_px", "color"))
        val d = RequestImageBorderConfig()
        return RequestImageBorderConfig(
            enabled = fields["enabled"]?.let { bool("border.enabled", it) } ?: d.enabled,
            widthPixels = fields["width_px"]?.let { int("border.width_px", it) } ?: d.widthPixels,
            paddingPixels = fields["padding_px"]?.let { int("border.padding_px", it) } ?: d.paddingPixels,
            color = fields["color"]?.let { string("border.color", it) } ?: d.color
        )
    }

    private fun percentage(node: Node): RequestImagePercentageConfig {
        val fields = mapping(node, "percentage", setOf("enabled", "gap_px", "vertical_align"))
        val d = RequestImagePercentageConfig()
        return RequestImagePercentageConfig(
            enabled = fields["enabled"]?.let { bool("percentage.enabled", it) } ?: d.enabled,
            gapPixels = fields["gap_px"]?.let { int("percentage.gap_px", it) } ?: d.gapPixels,
            verticalAlign = fields["vertical_align"]?.let { string("percentage.vertical_align", it) } ?: d.verticalAlign
        )
    }

    private fun margin(node: Node): RequestImageMarginConfig {
        val keys = setOf("left_percent", "right_percent", "top_percent", "bottom_percent", "min_px")
        val fields = mapping(node, "margin", keys)
        val d = RequestImageMarginConfig()
        return RequestImageMarginConfig(
            leftPercent = fields["left_percent"]?.let { number("margin.left_percent", it) } ?: d.leftPercent,
            rightPercent = fields["right_percent"]?.let { number("margin.right_percent", it) } ?: d.rightPercent,
            topPercent = fields["top_percent"]?.let { number("margin.top_percent", it) } ?: d.topPercent,
            bottomPercent = fields["bottom_percent"]?.let { number("margin.bottom_percent", it) } ?: d.bottomPercent,
            minPixels = fields["min_px"]?.let { int("margin.min_px", it) } ?: d.minPixels
        )
    }

    private fun background(node: Node): RequestImageBackgroundConfig {
        val fields = mapping(node, "background", setOf("margin_color", "content_color"))
        val d = RequestImageBackgroundConfig()
        return RequestImageBackgroundConfig(
            marginColor = fields["margin_color"]?.let { string("background.margin_color", it) } ?: d.marginColor,
            contentColor = fields["content_color"]?.let { string("background.content_color", it) } ?: d.contentColor
        )
    }

    private fun output(node: Node): RequestImageOutputConfig {
        val fields = mapping(node, "output", setOf("max_pixels"))
        val d = RequestImageOutputConfig()
        return RequestImageOutputConfig(
            maxPixels = fields["max_pixels"]?.let { long("output.max_pixels", it) } ?: d.maxPixels
        )
    }

    private fun rejectAnchorOrTag(path: String, node: Node): Unit {
        if (node.anchor != null) requestImageFieldError(path, "不支持 YAML 锚点或别名")
        val tag = node.tag
        // SEQ 允许通过此处，以便后续给出“类型错误”而不是“自定义标签”。
        val standard = tag == Tag.MAP || tag == Tag.SEQ || tag == Tag.STR || tag == Tag.INT ||
            tag == Tag.FLOAT || tag == Tag.BOOL || tag == Tag.NULL
        if (!standard) requestImageFieldError(path, "不支持的 YAML 类型或自定义标签")
    }

    private fun mapping(node: Node, path: String, allowed: Set<String>): Map<String, Node> {
        rejectAnchorOrTag(path, node)
        if (node is ScalarNode && node.tag == Tag.NULL) {
            requestImageFieldError(path, "不能为 null；省略该段即可使用默认值")
        }
        if (node !is MappingNode || node.tag != Tag.MAP) requestImageFieldError(path, "必须是 YAML 映射")
        val result = LinkedHashMap<String, Node>()
        for (tuple in node.value) {
            val keyNode = tuple.keyNode
            if (keyNode.anchor != null) requestImageFieldError(path, "不支持 YAML 锚点或别名")
            if (keyNode !is ScalarNode || keyNode.tag != Tag.STR) {
                requestImageFieldError(path, "字段名必须是字符串")
            }
            val key = keyNode.value
            val childPath = if (path == "根节点") safeKey(key) else "$path.${safeKey(key)}"
            if (key !in allowed) requestImageFieldError(childPath, "未知字段")
            if (result.containsKey(key)) requestImageFieldError(childPath, "重复字段")
            val valueNode = tuple.valueNode
            rejectAnchorOrTag(childPath, valueNode)
            if (valueNode is ScalarNode && valueNode.tag == Tag.NULL) {
                val hint = if (key.endsWith("color")) "；颜色需要加引号，例如 \"#FF3B30\"" else "；省略该字段即可使用默认值"
                requestImageFieldError(childPath, "不能为 null$hint")
            }
            result[key] = valueNode
        }
        return result
    }

    private fun safeKey(key: String): String {
        val clean = safeText(key)
        return if (clean.length <= 64) clean else clean.take(64) + "…"
    }

    private fun scalar(path: String, node: Node, expected: String): ScalarNode {
        if (node !is ScalarNode) requestImageFieldError(path, "必须是$expected")
        return node
    }

    private fun long(path: String, node: Node): Long {
        val scalar = scalar(path, node, "整数")
        if (!scalar.isPlain || scalar.tag != Tag.INT || !INTEGER_PATTERN.matches(scalar.value)) {
            requestImageFieldError(path, "必须是十进制整数（不加引号）")
        }
        return scalar.value.toLongOrNull() ?: requestImageFieldError(path, "整数超出范围")
    }

    private fun int(path: String, node: Node): Int {
        val value = long(path, node)
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) requestImageFieldError(path, "整数超出范围")
        return value.toInt()
    }

    private fun number(path: String, node: Node): Double {
        val scalar = scalar(path, node, "数字")
        val numericTag = scalar.tag == Tag.INT || scalar.tag == Tag.FLOAT
        if (!scalar.isPlain || !numericTag || !DECIMAL_PATTERN.matches(scalar.value)) {
            requestImageFieldError(path, "必须是有限的十进制数字（不加引号，不支持 NaN/Infinity）")
        }
        val value = scalar.value.toDoubleOrNull() ?: requestImageFieldError(path, "必须是有限数字")
        if (!value.isFinite()) requestImageFieldError(path, "必须是有限数字")
        // 归一化 -0.0，保证 data class 相等性与渲染往返一致。
        return value + 0.0
    }

    private fun bool(path: String, node: Node): Boolean {
        val scalar = scalar(path, node, "布尔值")
        if (!scalar.isPlain || scalar.tag != Tag.BOOL || (scalar.value != "true" && scalar.value != "false")) {
            requestImageFieldError(path, "必须是 true 或 false（小写、不加引号）")
        }
        return scalar.value == "true"
    }

    private fun string(path: String, node: Node): String {
        val scalar = scalar(path, node, "字符串")
        if (scalar.tag != Tag.STR) requestImageFieldError(path, "必须是字符串；颜色等文本请加引号")
        return scalar.value
    }

    /** 按点分路径修改单个字段，返回完整新样式（已校验）；未知路径或非法值抛出 [IllegalArgumentException]。 */
    internal fun updateField(style: RequestImageStyle, key: String, rawValue: String): RequestImageStyle {
        val value = rawValue.trim()
        val path = key.trim()
        val updated = when (path) {
            "version" -> style.copy(version = intValue(path, value))
            "border.enabled" -> style.copy(border = style.border.copy(enabled = boolValue(path, value)))
            "border.width_px" -> style.copy(border = style.border.copy(widthPixels = intValue(path, value)))
            "border.padding_px" -> style.copy(border = style.border.copy(paddingPixels = intValue(path, value)))
            "border.color" -> style.copy(border = style.border.copy(color = stringValue(value)))
            "percentage.enabled" -> style.copy(percentage = style.percentage.copy(enabled = boolValue(path, value)))
            "percentage.gap_px" -> style.copy(percentage = style.percentage.copy(gapPixels = intValue(path, value)))
            "percentage.vertical_align" ->
                style.copy(percentage = style.percentage.copy(verticalAlign = stringValue(value)))
            "margin.left_percent" -> style.copy(margin = style.margin.copy(leftPercent = numberValue(path, value)))
            "margin.right_percent" -> style.copy(margin = style.margin.copy(rightPercent = numberValue(path, value)))
            "margin.top_percent" -> style.copy(margin = style.margin.copy(topPercent = numberValue(path, value)))
            "margin.bottom_percent" -> style.copy(margin = style.margin.copy(bottomPercent = numberValue(path, value)))
            "margin.min_px" -> style.copy(margin = style.margin.copy(minPixels = intValue(path, value)))
            "background.margin_color" ->
                style.copy(background = style.background.copy(marginColor = stringValue(value)))
            "background.content_color" ->
                style.copy(background = style.background.copy(contentColor = stringValue(value)))
            "output.max_pixels" -> style.copy(output = style.output.copy(maxPixels = longValue(path, value)))
            else -> requestImageFieldError(safeKey(path).ifEmpty { "<空>" }, "未知字段；可用字段：${FIELD_PATHS.joinToString(", ")}")
        }
        updated.validate()
        return updated
    }

    internal val FIELD_PATHS: List<String> = listOf(
        "version",
        "border.enabled", "border.width_px", "border.padding_px", "border.color",
        "percentage.enabled", "percentage.gap_px", "percentage.vertical_align",
        "margin.left_percent", "margin.right_percent", "margin.top_percent", "margin.bottom_percent", "margin.min_px",
        "background.margin_color", "background.content_color",
        "output.max_pixels"
    )

    private fun longValue(path: String, value: String): Long {
        if (!INTEGER_PATTERN.matches(value)) requestImageFieldError(path, "必须是十进制整数")
        return value.toLongOrNull() ?: requestImageFieldError(path, "整数超出范围")
    }

    private fun intValue(path: String, value: String): Int {
        val parsed = longValue(path, value)
        if (parsed < Int.MIN_VALUE || parsed > Int.MAX_VALUE) requestImageFieldError(path, "整数超出范围")
        return parsed.toInt()
    }

    private fun numberValue(path: String, value: String): Double {
        if (!DECIMAL_PATTERN.matches(value)) requestImageFieldError(path, "必须是有限的十进制数字")
        val parsed = value.toDoubleOrNull() ?: requestImageFieldError(path, "必须是有限数字")
        if (!parsed.isFinite()) requestImageFieldError(path, "必须是有限数字")
        return parsed + 0.0
    }

    private fun boolValue(path: String, value: String): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> requestImageFieldError(path, "必须是 true 或 false")
    }

    /** 允许命令行把颜色写成 "#22C55E" 或 '#22C55E'，去掉一层成对引号。 */
    private fun stringValue(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            if ((first == '"' || first == '\'') && value.last() == first) return value.substring(1, value.length - 1)
        }
        return value
    }

    /**
     * 生成完整、带中文注释的 YAML。默认样式的输出与 `src/main/resources/request_image.yml` 逐字一致，
     * 且 `parse(render(style)) == style`。
     */
    internal fun render(style: RequestImageStyle): String = buildString {
        append("# XAiWebHook 请求记录图片样式配置（独立于 webhook_config.yml）\n")
        append("# 修改保存后约 1~2 秒自动生效，无需重启插件或执行 /xwebhook reload。\n")
        append("# 无效配置不会被应用：继续使用上一份有效样式，并在日志与 /xwebhook imageconfig 中显示错误。\n")
        append("# 字段可按需省略（至少保留一个字段，如 version: 1）；省略时使用下方默认值，不允许未知字段、重复字段或 null。\n")
        append("# 颜色必须加引号，格式为 \"#RRGGBB\"（不透明）或 \"#AARRGGBB\"（alpha 在前，00 为全透明）。\n")
        append("\n")
        append("# 配置格式版本。默认 1，目前仅支持 1。\n")
        append("version: ").append(style.version).append('\n')
        append("\n")
        append("# 原截图外框。\n")
        append("border:\n")
        append("  # 是否绘制外框。默认 true；false 时不绘制线条，也不增加边框内边距。\n")
        append("  enabled: ").append(style.border.enabled).append('\n')
        append("  # 外框线宽（像素）。默认 1，整数 0..16；0 表示不绘制线条。\n")
        append("  width_px: ").append(style.border.widthPixels).append('\n')
        append("  # 原截图与外框之间的内边距（像素）。默认 2，整数 0..128。\n")
        append("  padding_px: ").append(style.border.paddingPixels).append('\n')
        append("  # 外框颜色。默认 \"#FF3B30\"。\n")
        append("  color: ").append(quote(style.border.color)).append('\n')
        append("\n")
        append("# 左侧成功率百分比副本。\n")
        append("percentage:\n")
        append("  # 是否截取并在左侧复制成功率百分比。默认 true；false 时不再截取该元素。\n")
        append("  enabled: ").append(style.percentage.enabled).append('\n')
        append("  # 左侧百分比与原截图外框之间的间距（像素）。默认 6，整数 0..256；仅存在左侧副本时计入。\n")
        append("  gap_px: ").append(style.percentage.gapPixels).append('\n')
        append("  # 百分比竖向对齐方式。默认 \"center\"，可选 \"top\"、\"center\"、\"bottom\"。\n")
        append("  vertical_align: ").append(quote(style.percentage.verticalAlign)).append('\n')
        append("\n")
        append("# 外侧留白。百分数以加留白之前的完整内容尺寸为基准，每侧向上取整。\n")
        append("margin:\n")
        append("  # 左侧留白占内容宽度的百分比。默认 5.0，有限数字 0..50；0 表示关闭该侧留白。\n")
        append("  left_percent: ").append(formatDouble(style.margin.leftPercent)).append('\n')
        append("  # 右侧留白占内容宽度的百分比。默认 5.0，有限数字 0..50；0 表示关闭该侧留白。\n")
        append("  right_percent: ").append(formatDouble(style.margin.rightPercent)).append('\n')
        append("  # 顶部留白占内容高度的百分比。默认 1.0，有限数字 0..50；0 表示关闭该侧留白。\n")
        append("  top_percent: ").append(formatDouble(style.margin.topPercent)).append('\n')
        append("  # 底部留白占内容高度的百分比。默认 1.0，有限数字 0..50；0 表示关闭该侧留白。\n")
        append("  bottom_percent: ").append(formatDouble(style.margin.bottomPercent)).append('\n')
        append("  # 非零比例时每侧的最小留白（像素）。默认 1，整数 0..128。\n")
        append("  min_px: ").append(style.margin.minPixels).append('\n')
        append("\n")
        append("# 背景颜色。\n")
        append("background:\n")
        append("  # 外侧留白颜色。默认白色 \"#FFFFFF\"。\n")
        append("  margin_color: ").append(quote(style.background.marginColor)).append('\n')
        append("  # 内容区新增间隙/内边距的底色。默认 \"auto\"（取原截图左上角像素），也可填写固定颜色。\n")
        append("  content_color: ").append(quote(style.background.contentColor)).append('\n')
        append("\n")
        append("# 输出限制。\n")
        append("output:\n")
        append("  # 合成画布的像素总量上限（宽 x 高），创建画布前检查。默认 16000000，整数 1..64000000。\n")
        append("  # 截图字节上限仍由 webhook_config.yml 的 browser.max_screenshot_bytes 控制。\n")
        append("  max_pixels: ").append(style.output.maxPixels).append('\n')
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 输出能被严格解析器接受且精确往返的十进制，始终带小数点（如 5.0、12.5、0.0001）。 */
    internal fun formatDouble(value: Double): String {
        val plain = BigDecimal.valueOf(value + 0.0).stripTrailingZeros().toPlainString()
        return if (plain.contains('.')) plain else "$plain.0"
    }
}
