package com.fallbracelet.caregiver

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** The caregiver history filter, the same periods as the web page (relay/web/index.html). */
enum class HistoryMode(val label: String) { DAY("Day"), WEEK("Week"), ALL("All") }

/** Events with [from] <= occurredAt < [until]. */
data class HistoryPeriod(val from: Long, val until: Long, val title: String)

fun localDay(at: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

private fun LocalDate.startMs(zone: ZoneId) = atStartOfDay(zone).toInstant().toEpochMilli()

fun dayName(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))
}

/** [offset] 0 is today or this week, -1 the one before. Weeks run Monday to Sunday. */
fun historyPeriod(mode: HistoryMode, offset: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): HistoryPeriod {
    val today = localDay(now, zone)
    return when (mode) {
        HistoryMode.ALL -> HistoryPeriod(0, Long.MAX_VALUE, "All")
        HistoryMode.DAY -> {
            val day = today.plusDays(offset.toLong())
            HistoryPeriod(day.startMs(zone), day.plusDays(1).startMs(zone), dayName(day, today))
        }
        HistoryMode.WEEK -> {
            val monday = today.minusDays(today.dayOfWeek.value - 1L).plusWeeks(offset.toLong())
            val format = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            val title = when (offset) {
                0 -> "This week"
                -1 -> "Last week"
                else -> "${monday.format(format)} – ${monday.plusDays(6).format(format)}"
            }
            HistoryPeriod(monday.startMs(zone), monday.plusDays(7).startMs(zone), title)
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 d ago". */
fun ago(at: Long, now: Long): String {
    val s = ((now - at) / 1000.0).roundToLong()
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${(s / 60.0).roundToLong()} min ago"
        s < 86400 -> "${(s / 3600.0).roundToLong()} h ago"
        else -> "${(s / 86400.0).roundToLong()} d ago"
    }
}
