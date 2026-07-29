package kim.hhhhhy.x.webhook.action.codex

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 时间显示辅助：把接口的 ISO 时间戳转为本地可读文本。 */
internal object CodexRadarTime {
    private val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun toDisplay(iso: String, zone: ZoneId = ZoneId.systemDefault()): String {
        return runCatching {
            OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DISPLAY)
        }.getOrElse { iso }
    }

    fun now(zone: ZoneId = ZoneId.systemDefault()): String =
        OffsetDateTime.now(zone).format(DISPLAY)
}
