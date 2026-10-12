package kim.hhhhhy.x.webhook.action.codex

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 记录本地抓取时间；图表直接展示快照中的生成时间文本。 */
internal object CodexRadarTime {
    private val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

    fun now(zone: ZoneId = ZoneId.systemDefault()): String =
        OffsetDateTime.now(zone).format(DISPLAY)
}
