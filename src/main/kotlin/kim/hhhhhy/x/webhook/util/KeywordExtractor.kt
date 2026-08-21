package kim.hhhhhy.x.webhook.util

/**
 * 关键词提取配置
 */
internal data class KeywordExtractionConfig(
    /** 要移除的前缀列表（按顺序尝试） */
    val removePrefixes: List<String> = emptyList(),
    /** 正则表达式模式（可选） */
    val pattern: String? = null,
    /** 正则捕获组索引 */
    val captureGroup: Int = 1,
    /** 是否 trim 空白 */
    val trim: Boolean = true,
    /** 是否转小写 */
    val toLowerCase: Boolean = false,
    /** 配置了前缀时，是否要求至少命中一个前缀 */
    val requirePrefixMatch: Boolean = false
)

/**
 * 关键词提取结果，区分「前缀未命中」与「命中但内容为空」两种情况
 */
internal data class KeywordExtractionResult(
    /** 提取后的关键词，可能为空串 */
    val keyword: String,
    /** 是否命中前缀；未配置前缀时视为 true */
    val prefixMatched: Boolean,
    /** 命中的前缀，未命中或未配置时为 null */
    val matchedPrefix: String?
) {
    /** 关键词非空且前缀已命中 */
    val isUsable: Boolean get() = prefixMatched && keyword.isNotBlank()
}

/**
 * 关键词提取工具
 */
internal object KeywordExtractor {
    /**
     * 根据配置从消息中提取关键词。
     * 前缀未命中且 requirePrefixMatch 为 true 时返回空串。
     */
    public fun extract(message: String, config: KeywordExtractionConfig): String {
        val result = extractDetailed(message, config)
        if (config.requirePrefixMatch && !result.prefixMatched) return ""
        return result.keyword
    }

    /**
     * 提取关键词并返回可用状态，关键词为空或前缀未命中时返回 null。
     * 适用于「无法提取即视为不触发」的调用方。
     */
    public fun extractOrNull(message: String, config: KeywordExtractionConfig): String? =
        extractDetailed(message, config).takeIf { it.isUsable }?.keyword

    /**
     * 提取关键词并保留前缀匹配状态，供调用方区分失败原因。
     */
    public fun extractDetailed(message: String, config: KeywordExtractionConfig): KeywordExtractionResult {
        // 前缀匹配前先去除首尾空白，避免 " poly x" 这类消息因前导空格漏配
        var result = if (config.trim) message.trim() else message

        // 步骤 1: 移除前缀
        var matchedPrefix: String? = null
        if (config.removePrefixes.isNotEmpty()) {
            for (prefix in config.removePrefixes) {
                if (prefix.isNotEmpty() && result.startsWith(prefix, ignoreCase = true)) {
                    result = result.substring(prefix.length)
                    matchedPrefix = prefix
                    break
                }
            }
        }
        val prefixMatched = config.removePrefixes.isEmpty() || matchedPrefix != null

        // 去前缀后先 trim，使 pattern 作用于干净内容（如 "^(\S+)" 不受前缀后空格影响）
        if (config.trim) {
            result = result.trim()
        }

        // 步骤 2: 正则提取
        if (config.pattern != null) {
            val regex = runCatching { Regex(config.pattern) }.getOrNull()
            if (regex != null) {
                val match = regex.find(result)
                if (match != null && match.groupValues.size > config.captureGroup) {
                    result = match.groupValues[config.captureGroup]
                }
            }
        }

        // 步骤 3: trim
        if (config.trim) {
            result = result.trim()
        }

        // 步骤 4: 转小写
        if (config.toLowerCase) {
            result = result.lowercase()
        }

        return KeywordExtractionResult(
            keyword = result,
            prefixMatched = prefixMatched,
            matchedPrefix = matchedPrefix
        )
    }
}
