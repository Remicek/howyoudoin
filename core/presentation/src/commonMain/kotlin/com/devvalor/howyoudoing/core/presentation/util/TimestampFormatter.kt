package com.devvalor.howyoudoing.core.presentation.util

import howyoudoin.core.presentation.generated.resources.Res
import howyoudoin.core.presentation.generated.resources.date_long
import howyoudoin.core.presentation.generated.resources.day_today
import howyoudoin.core.presentation.generated.resources.day_yesterday
import howyoudoin.core.presentation.generated.resources.month_1
import howyoudoin.core.presentation.generated.resources.month_10
import howyoudoin.core.presentation.generated.resources.month_11
import howyoudoin.core.presentation.generated.resources.month_12
import howyoudoin.core.presentation.generated.resources.month_2
import howyoudoin.core.presentation.generated.resources.month_3
import howyoudoin.core.presentation.generated.resources.month_4
import howyoudoin.core.presentation.generated.resources.month_5
import howyoudoin.core.presentation.generated.resources.month_6
import howyoudoin.core.presentation.generated.resources.month_7
import howyoudoin.core.presentation.generated.resources.month_8
import howyoudoin.core.presentation.generated.resources.month_9
import howyoudoin.core.presentation.generated.resources.weekday_1
import howyoudoin.core.presentation.generated.resources.weekday_2
import howyoudoin.core.presentation.generated.resources.weekday_3
import howyoudoin.core.presentation.generated.resources.weekday_4
import howyoudoin.core.presentation.generated.resources.weekday_5
import howyoudoin.core.presentation.generated.resources.weekday_6
import howyoudoin.core.presentation.generated.resources.weekday_7
import howyoudoin.core.presentation.generated.resources.weekday_short_1
import howyoudoin.core.presentation.generated.resources.weekday_short_2
import howyoudoin.core.presentation.generated.resources.weekday_short_3
import howyoudoin.core.presentation.generated.resources.weekday_short_4
import howyoudoin.core.presentation.generated.resources.weekday_short_5
import howyoudoin.core.presentation.generated.resources.weekday_short_6
import howyoudoin.core.presentation.generated.resources.weekday_short_7
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
object TimestampFormatter {

    /** Inbox style: "14:05" today, "Mon" this week, "12.03" otherwise. */
    fun formatChatTimestamp(epochMillis: Long): UiText {
        val zone = TimeZone.currentSystemDefault()
        val then = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
        val today = Clock.System.now().toLocalDateTime(zone)
        val daysAgo = today.date.toEpochDays() - then.date.toEpochDays()
        return when {
            daysAgo <= 0L -> UiText.DynamicString(then.hourMinute())
            daysAgo < 7L -> UiText.Resource(WEEKDAYS_SHORT.getValue(then.dayOfWeek))
            else -> UiText.DynamicString("${then.day.pad()}.${then.month.ordinal.plus(1).pad()}")
        }
    }

    fun formatMessageTime(epochMillis: Long): String {
        return Instant.fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .hourMinute()
    }

    /** Conversation separators: "Today", "Yesterday", "Monday" within a week, else "12 March 2026" (in the user's language). */
    fun formatDayHeader(epochMillis: Long): UiText {
        val zone = TimeZone.currentSystemDefault()
        val day = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date
        val today = Clock.System.now().toLocalDateTime(zone).date
        val daysAgo = today.toEpochDays() - day.toEpochDays()
        return when {
            daysAgo == 0L -> UiText.Resource(Res.string.day_today)
            daysAgo == 1L -> UiText.Resource(Res.string.day_yesterday)
            daysAgo in 2L..6L -> UiText.Resource(WEEKDAYS.getValue(day.dayOfWeek))
            // Each language orders day, month and year itself (and may need the month's genitive form)
            else -> UiText.Resource(Res.string.date_long, arrayOf(day.day, UiText.Resource(MONTHS[day.month.ordinal]), day.year))
        }
    }

    fun isSameDay(firstMillis: Long, secondMillis: Long): Boolean {
        val zone = TimeZone.currentSystemDefault()
        return Instant.fromEpochMilliseconds(firstMillis).toLocalDateTime(zone).date ==
            Instant.fromEpochMilliseconds(secondMillis).toLocalDateTime(zone).date
    }

    private fun LocalDateTime.hourMinute(): String = "${hour.pad()}:${minute.pad()}"

    private val WEEKDAYS = mapOf(
        DayOfWeek.MONDAY to Res.string.weekday_1, DayOfWeek.TUESDAY to Res.string.weekday_2,
        DayOfWeek.WEDNESDAY to Res.string.weekday_3, DayOfWeek.THURSDAY to Res.string.weekday_4,
        DayOfWeek.FRIDAY to Res.string.weekday_5, DayOfWeek.SATURDAY to Res.string.weekday_6,
        DayOfWeek.SUNDAY to Res.string.weekday_7,
    )

    private val WEEKDAYS_SHORT = mapOf(
        DayOfWeek.MONDAY to Res.string.weekday_short_1, DayOfWeek.TUESDAY to Res.string.weekday_short_2,
        DayOfWeek.WEDNESDAY to Res.string.weekday_short_3, DayOfWeek.THURSDAY to Res.string.weekday_short_4,
        DayOfWeek.FRIDAY to Res.string.weekday_short_5, DayOfWeek.SATURDAY to Res.string.weekday_short_6,
        DayOfWeek.SUNDAY to Res.string.weekday_short_7,
    )

    private val MONTHS = listOf(
        Res.string.month_1, Res.string.month_2, Res.string.month_3, Res.string.month_4, Res.string.month_5,
        Res.string.month_6, Res.string.month_7, Res.string.month_8, Res.string.month_9, Res.string.month_10,
        Res.string.month_11, Res.string.month_12,
    )

    private fun Int.pad(): String = toString().padStart(2, '0')
}
