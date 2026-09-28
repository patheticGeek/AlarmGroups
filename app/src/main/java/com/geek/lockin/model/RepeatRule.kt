package com.geek.lockin.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class RepeatType { ONCE, DAILY, WEEKLY, INTERVAL }

/**
 * How often something rings.
 *
 * - [RepeatType.ONCE]: on [onceDate] if set, otherwise the next time the clock time comes around.
 *   The alarm switches itself off after it rings.
 * - [RepeatType.DAILY]: every day.
 * - [RepeatType.WEEKLY]: on the days in [days] (bit 0 = Monday … bit 6 = Sunday).
 * - [RepeatType.INTERVAL]: every [interval] days starting at [anchorDate].
 */
@Serializable
data class RepeatRule(
    val type: RepeatType = RepeatType.ONCE,
    val days: Int = 0,
    val interval: Int = 1,
    @Serializable(with = LocalDateSerializer::class)
    val anchorDate: LocalDate? = null,
    @Serializable(with = LocalDateSerializer::class)
    val onceDate: LocalDate? = null,
) {
    fun matches(date: LocalDate): Boolean = when (type) {
        RepeatType.ONCE -> onceDate == null || onceDate == date
        RepeatType.DAILY -> true
        RepeatType.WEEKLY -> hasDay(date.dayOfWeek)
        RepeatType.INTERVAL -> {
            val anchor = anchorDate ?: return true
            val diff = ChronoUnit.DAYS.between(anchor, date)
            diff >= 0 && diff % interval.coerceAtLeast(1) == 0L
        }
    }

    fun hasDay(day: DayOfWeek): Boolean = days and dayBit(day) != 0

    fun withDay(day: DayOfWeek, on: Boolean): RepeatRule =
        copy(days = if (on) days or dayBit(day) else days and dayBit(day).inv())

    /** A rule that can never ring (e.g. weekly with no days picked). */
    val isEmpty: Boolean get() = type == RepeatType.WEEKLY && days and ALL_DAYS == 0

    companion object {
        const val WEEKDAYS = 0b0011111
        const val WEEKENDS = 0b1100000
        const val ALL_DAYS = 0b1111111

        fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

        val Once = RepeatRule(RepeatType.ONCE)
        val Daily = RepeatRule(RepeatType.DAILY)
        fun weekly(days: Int) = RepeatRule(RepeatType.WEEKLY, days = days)
        fun weekly(vararg days: DayOfWeek) =
            RepeatRule(RepeatType.WEEKLY, days = days.fold(0) { acc, d -> acc or dayBit(d) })
        fun every(interval: Int, from: LocalDate) =
            RepeatRule(RepeatType.INTERVAL, interval = interval, anchorDate = from)
    }
}
