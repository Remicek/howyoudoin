package com.devvalor.howyoudoing.core.presentation.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
object TimestampFormatter {

    /** Inbox style: "14:05" today, "Mon" this week, "12.03" otherwise. */
    fun formatChatTimestamp(epochMillis: Long): String {
        val zone = TimeZone.currentSystemDefault()
        val then = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
        val today = Clock.System.now().toLocalDateTime(zone)
        val daysAgo = today.date.toEpochDays() - then.date.toEpochDays()
        return when {
            daysAgo <= 0L -> then.hourMinute()
            daysAgo < 7L -> then.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            else -> "${then.day.pad()}.${then.month.ordinal.plus(1).pad()}"
        }
    }

    fun formatMessageTime(epochMillis: Long): String {
        return Instant.fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .hourMinute()
    }

    /** Conversation separators: "Today", "Yesterday", "Monday" within a week, else "12 March 2026". */
    fun formatDayHeader(epochMillis: Long): String {
        val zone = TimeZone.currentSystemDefault()
        val day = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date
        val today = Clock.System.now().toLocalDateTime(zone).date
        val daysAgo = today.toEpochDays() - day.toEpochDays()
        return when {
            daysAgo == 0L -> "Today"
            daysAgo == 1L -> "Yesterday"
            daysAgo in 2L..6L -> day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            else -> "${day.day} ${day.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${day.year}"
        }
    }

    fun isSameDay(firstMillis: Long, secondMillis: Long): Boolean {
        val zone = TimeZone.currentSystemDefault()
        return Instant.fromEpochMilliseconds(firstMillis).toLocalDateTime(zone).date ==
            Instant.fromEpochMilliseconds(secondMillis).toLocalDateTime(zone).date
    }

    private fun LocalDateTime.hourMinute(): String = "${hour.pad()}:${minute.pad()}"

    private fun Int.pad(): String = toString().padStart(2, '0')
}
