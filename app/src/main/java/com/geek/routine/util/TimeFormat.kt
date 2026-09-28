package com.geek.routine.util

import android.content.Context
import android.text.format.DateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object TimeFormat {
    fun is24h(context: Context) = DateFormat.is24HourFormat(context)

    fun time(context: Context, hour: Int, minute: Int): String {
        val pattern = if (is24h(context)) "HH:mm" else "h:mm a"
        return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    /** "Today", "Tomorrow", "Wed, Oct 7". */
    fun day(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(
            DateTimeFormatter.ofPattern(
                if (date.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy",
                Locale.getDefault(),
            ),
        )
    }

    fun date(date: LocalDate): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

    /** "Tomorrow 7:00 AM". */
    fun whenString(context: Context, instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = instant.atZone(zone)
        return "${day(z.toLocalDate(), LocalDate.now(zone))} ${time(context, z.hour, z.minute)}"
    }

    /** "in 7 h 20 min". */
    fun until(instant: Instant, now: Instant = Instant.now()): String {
        val d = Duration.between(now, instant).plusSeconds(59)
        if (d.isNegative) return "now"
        val days = d.toDays()
        val hours = d.toHours() % 24
        val minutes = d.toMinutes() % 60
        return buildString {
            append("in ")
            if (days > 0) append("$days d ")
            if (hours > 0) append("$hours h ")
            if (days == 0L) append("$minutes min")
        }.trim()
    }
}
