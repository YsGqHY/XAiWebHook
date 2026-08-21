package kim.hhhhhy.x.webhook.util

/**
 * 过滤结果
 */
internal sealed class FilterResult {
    /** 通过验证 */
    internal object Pass : FilterResult()
    
    /** 被拒绝 */
    internal data class Reject(val message: String) : FilterResult()
}

/**
 * 黑名单配置
 */
internal data class BlacklistConfig(
    val enabled: Boolean = true,
    val keywords: List<String> = emptyList(),
    val caseSensitive: Boolean = false,
    val rejectMessage: String = "关键词已被禁止"
)

/**
 * 白名单配置
 */
internal data class WhitelistConfig(
    val enabled: Boolean = true,
    val keywords: List<String> = emptyList(),
    val caseSensitive: Boolean = false,
    val rejectMessage: String = "关键词不在允许列表中"
)

/**
 * 长度验证配置
 */
internal data class LengthConfig(
    val min: Int? = null,
    val max: Int? = null,
    val rejectMessage: String = "关键词长度不符合要求"
)

/**
 * 正则模式验证配置
 */
internal data class PatternConfig(
    val pattern: String,
    val rejectMessage: String = "关键词格式不正确"
)

/**
 * 过滤器配置
 */
internal data class FilterConfig(
    val blacklist: BlacklistConfig? = null,
    val whitelist: WhitelistConfig? = null,
    val length: LengthConfig? = null,
    val pattern: PatternConfig? = null
)

/**
 * 过滤器链
 */
internal object FilterChain {
    /**
     * 验证关键词是否通过所有过滤器
     */
    public fun validate(keyword: String, config: FilterConfig): FilterResult {
        // 黑名单过滤
        config.blacklist?.let { blacklist ->
            if (blacklist.enabled) {
                val result = validateBlacklist(keyword, blacklist)
                if (result is FilterResult.Reject) return result
            }
        }

        // 白名单过滤
        config.whitelist?.let { whitelist ->
            if (whitelist.enabled) {
                val result = validateWhitelist(keyword, whitelist)
                if (result is FilterResult.Reject) return result
            }
        }

        // 长度验证
        config.length?.let { length ->
            val result = validateLength(keyword, length)
            if (result is FilterResult.Reject) return result
        }

        // 正则验证
        config.pattern?.let { pattern ->
            val result = validatePattern(keyword, pattern)
            if (result is FilterResult.Reject) return result
        }

        return FilterResult.Pass
    }

    private fun validateBlacklist(keyword: String, config: BlacklistConfig): FilterResult {
        val matched = config.keywords.any { blacklisted ->
            if (config.caseSensitive) {
                keyword.contains(blacklisted)
            } else {
                keyword.contains(blacklisted, ignoreCase = true)
            }
        }

        return if (matched) {
            val message = config.rejectMessage.replace("\${keyword}", keyword)
            FilterResult.Reject(message)
        } else {
            FilterResult.Pass
        }
    }

    private fun validateWhitelist(keyword: String, config: WhitelistConfig): FilterResult {
        if (config.keywords.isEmpty()) return FilterResult.Pass

        val matched = config.keywords.any { allowed ->
            if (config.caseSensitive) {
                keyword.contains(allowed)
            } else {
                keyword.contains(allowed, ignoreCase = true)
            }
        }

        return if (matched) {
            FilterResult.Pass
        } else {
            val message = config.rejectMessage.replace("\${keyword}", keyword)
            FilterResult.Reject(message)
        }
    }

    private fun validateLength(keyword: String, config: LengthConfig): FilterResult {
        val length = keyword.length

        config.min?.let { min ->
            if (length < min) {
                val message = config.rejectMessage
                    .replace("\${keyword}", keyword)
                    .replace("\${min}", min.toString())
                    .replace("\${max}", config.max?.toString() ?: "")
                return FilterResult.Reject(message)
            }
        }

        config.max?.let { max ->
            if (length > max) {
                val message = config.rejectMessage
                    .replace("\${keyword}", keyword)
                    .replace("\${min}", config.min?.toString() ?: "")
                    .replace("\${max}", max.toString())
                return FilterResult.Reject(message)
            }
        }

        return FilterResult.Pass
    }

    private fun validatePattern(keyword: String, config: PatternConfig): FilterResult {
        val regex = runCatching { Regex(config.pattern) }.getOrNull()
            ?: return FilterResult.Pass

        return if (regex.matches(keyword)) {
            FilterResult.Pass
        } else {
            val message = config.rejectMessage.replace("\${keyword}", keyword)
            FilterResult.Reject(message)
        }
    }
}
