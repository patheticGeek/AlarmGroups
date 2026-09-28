package com.geek.routine.ui.components

import com.geek.routine.model.OverrideAction
import com.geek.routine.model.OverrideWithEffects
import com.geek.routine.model.RepeatRule
import com.geek.routine.model.RepeatType
import com.geek.routine.util.TimeFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Days of the week in the user's locale order (e.g. Sunday first in the US). */
fun localeWeek(locale: Locale = Locale.getDefault()): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L until 7L).map { first.plus(it) }
}

fun DayOfWeek.short(locale: Locale = Locale.getDefault()): String = getDisplayName(TextStyle.SHORT, locale)
fun DayOfWeek.narrow(locale: Locale = Locale.getDefault()): String = getDisplayName(TextStyle.NARROW, locale)

fun RepeatRule.describe(): String = when (type) {
    RepeatType.ONCE -> onceDate?.let { "Once on ${TimeFormat.day(it)}" } ?: "Once"
    RepeatType.DAILY -> "Every day"
    RepeatType.WEEKLY -> when (days and RepeatRule.ALL_DAYS) {
        0 -> "No days selected"
        RepeatRule.ALL_DAYS -> "Every day"
        RepeatRule.WEEKDAYS -> "Weekdays"
        RepeatRule.WEEKENDS -> "Weekends"
        else -> localeWeek().filter(::hasDay).joinToString(", ") { it.short() }
    }
    RepeatType.INTERVAL -> buildString {
        append(if (interval <= 1) "Every day" else "Every $interval days")
        anchorDate?.let { append(" from ${TimeFormat.day(it)}") }
    }
}

fun dateRange(start: LocalDate, end: LocalDate): String =
    if (start == end) TimeFormat.day(start) else "${TimeFormat.day(start)} – ${TimeFormat.day(end)}"

fun OverrideWithEffects.describeEffects(groupName: (Long) -> String): String =
    effects.joinToString(" · ") { e ->
        val what = when (e.action) {
            OverrideAction.PAUSE -> "paused"
            OverrideAction.REPEAT -> e.repeat.describe().replaceFirstChar { it.lowercase() }
        }
        "${groupName(e.targetGroupId)}: $what"
    }

fun durationLabel(seconds: Int): String = when {
    seconds <= 0 -> "Off"
    seconds < 60 -> "$seconds seconds"
    seconds == 60 -> "1 minute"
    else -> "${seconds / 60} minutes"
}

fun ringLengthLabel(minutes: Int): String = when {
    minutes <= 0 -> "Until I stop it"
    minutes == 1 -> "1 minute"
    else -> "$minutes minutes"
}
