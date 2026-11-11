package kim.hhhhhy.x.webhook.scraper

/**
 * Geek2Api 模型广场的价格，单位统一为“美元 / 百万 Token”。
 *
 * 官方口径见 [ModelPricingDetail.official]；各分组实付价见 [ModelGroupPricing.price]。
 * 站点仅部分模型提供缓存写入价（例如 DeepSeek 系列为 null），因此所有字段都允许为空。
 */
public data class TokenPrice(
    public val inputPrice: Double?,
    public val outputPrice: Double?,
    public val cacheWritePrice: Double?,
    public val cacheReadPrice: Double?
) {
    public val isEmpty: Boolean
        get() = inputPrice == null && outputPrice == null && cacheWritePrice == null && cacheReadPrice == null

    /** 按分组倍率与分时倍率缩放；任一字段为空时保持为空，避免展示不存在的价格。 */
    public fun scaled(factor: Double): TokenPrice = TokenPrice(
        inputPrice = inputPrice?.let { roundPrice(it * factor) },
        outputPrice = outputPrice?.let { roundPrice(it * factor) },
        cacheWritePrice = cacheWritePrice?.let { roundPrice(it * factor) },
        cacheReadPrice = cacheReadPrice?.let { roundPrice(it * factor) }
    )
}

/** 模型广场“调用”页中的单个端点。 */
public data class PlazaEndpoint(
    public val key: String,
    public val method: String,
    public val path: String,
    public val supported: Boolean,
    /** native 表示原生协议，converted 表示由网关协议转换。 */
    public val transport: String,
    public val reasonCodes: List<String>
)

/** 某个模型在某个分组下的实付价。 */
public data class ModelGroupPricing(
    public val groupId: Long,
    public val groupName: String,
    /** 站点下发的分组倍率；用户级覆盖存在时使用覆盖值。 */
    public val rateMultiplier: Double,
    /** 为 true 时站点直接下发最终价，倍率不再参与计算。 */
    public val isFinalPrice: Boolean,
    public val price: TokenPrice,
    public val endpoints: List<PlazaEndpoint> = emptyList()
) {
    /**
     * 仅保留支持的端点，并按“方法 + 路径”去重。
     *
     * 站点对同一路径可能给出多条记录（例如 stream_generate_content 与 generate_content 同路径），
     * 展示时同一个端点只应出现一次。
     */
    public val supportedEndpoints: List<PlazaEndpoint>
        get() = endpoints
            .filter(PlazaEndpoint::supported)
            .distinctBy { "${it.method.uppercase()} ${it.path}" }
}

/** 模型搜索结果：官方定价 + 每个分组的倍率、实付价与可用端点。 */
public data class ModelPricingDetail(
    public val modelName: String,
    public val badge: String?,
    public val official: TokenPrice,
    public val groups: List<ModelGroupPricing>
)

/** 分组搜索结果中的单个模型实付价。 */
public data class GroupModelPricing(
    public val modelName: String,
    public val badge: String?,
    public val official: TokenPrice,
    public val isFinalPrice: Boolean,
    public val price: TokenPrice
)

/** 分组搜索结果：分组倍率 + 该分组下每个模型的实付价。 */
public data class GroupPricingDetail(
    public val groupId: Long,
    public val groupName: String,
    public val rateMultiplier: Double,
    public val models: List<GroupModelPricing>
)

/** 站点价格最多保留 4 位小数，与前端展示口径一致。 */
internal fun roundPrice(value: Double): Double = if (value.isFinite()) kotlin.math.round(value * 10_000.0) / 10_000.0 else value
