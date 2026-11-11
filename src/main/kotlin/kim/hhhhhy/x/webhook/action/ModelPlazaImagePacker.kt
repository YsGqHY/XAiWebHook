package kim.hhhhhy.x.webhook.action

/**
 * 把卡片按“区块”切分成多页图片，保证每张图片都不超过字节预算。
 *
 * 背景：OneBot 实现的上传通道对单张图片有上限（SnowLuma 的 highway 上传在 2 MiB 处
 * 报 `highway upload error_code=921`），一旦超出，整条群消息都会发送失败。模型数量多时
 * 卡片高度可达上万像素，单张图片轻易越过上限，因此必须分页。
 *
 * 分页粒度为整块：一个模型（或一个分组）永远落在同一页，表格不会被从中间切断。
 * 每页的具体字节数只能由渲染结果得知，所以这里用“实测 + 对半收缩”的方式收敛，
 * 并用上一页的实测值推算下一页能放几个区块，把渲染次数压到最低。
 */
internal object ModelPlazaImagePacker {

    /** 单张卡片的默认字节预算，留足余量以适配 2 MiB 上限的 OneBot 实现。 */
    const val DEFAULT_MAX_BYTES: Int = 1_200_000

    /** 推算下一页容量时保留的预算比例，避免因区块大小不均而反复收缩。 */
    private const val BUDGET_SAFETY: Double = 0.9

    /**
     * @param sections 每个区块的 HTML（模型卡片为一个模型，分组卡片为一个分组）
     * @param maxBytes 单张图片的字节预算
     * @param renderPage 把若干区块渲染成一张 PNG
     * @return 每页一张 PNG，顺序与 [sections] 一致
     */
    fun paginate(
        sections: List<String>,
        maxBytes: Int,
        renderPage: (List<String>) -> ByteArray
    ): List<ByteArray> {
        if (sections.isEmpty()) return emptyList()

        val budget = maxBytes.coerceAtLeast(1)
        // 只有一块时无论多大都只能整张发出，调用方负责记录告警
        if (sections.size == 1) return listOf(renderPage(sections))

        // 先用首块做一次校准：区块体积差异大，固定首屏数量会让本可一页放下的卡片被拆开。
        val probeBytes = renderPage(listOf(sections.first())).size
        val pages = mutableListOf<ByteArray>()
        var index = 0
        var capacity = nextCapacity(probeBytes, 1, budget)
        while (index < sections.size) {
            var take = capacity.coerceAtMost(sections.size - index)
            var bytes = renderPage(sections.subList(index, index + take))
            while (bytes.size > budget && take > 1) {
                take /= 2
                bytes = renderPage(sections.subList(index, index + take))
            }
            pages += bytes
            index += take
            capacity = nextCapacity(bytes.size, take, budget)
        }
        return pages
    }

    /** 用本页实测的“每区块字节数”推算下一页容量；估算偏保守，收缩分支只会在异常分布时触发。 */
    private fun nextCapacity(bytes: Int, blocks: Int, budget: Int): Int {
        val perBlock = bytes.toDouble() / blocks
        if (perBlock <= 0.0) return blocks
        val room = (budget * BUDGET_SAFETY / perBlock).toInt()
        return room.coerceIn(1, 100)
    }
}
