package com.geek.lockin.domain

import com.geek.lockin.model.Alarm
import com.geek.lockin.model.AlarmGroup
import com.geek.lockin.model.OverrideAction
import com.geek.lockin.model.OverrideEffect
import com.geek.lockin.model.OverrideWithEffects
import com.geek.lockin.model.RepeatRule
import com.geek.lockin.model.RepeatType
import com.geek.lockin.model.ScheduleOverride
import com.geek.lockin.model.UNGROUPED_ID
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What governs an alarm on a particular day. */
sealed interface DayPlan {
    /** Alarm is allowed to ring on this day if [repeat] matches. [override] is set when an override decided it. */
    data class Active(val repeat: RepeatRule, val override: ScheduleOverride?) : DayPlan

    data class Paused(val reason: PauseReason, val override: ScheduleOverride? = null) : DayPlan
}

enum class PauseReason { GROUP_OFF, GROUP_PAUSED, OVERRIDE }

/**
 * Pure scheduling logic: given an alarm, its group and all overrides, works out when it rings next.
 *
 * Precedence for a given day, highest first:
 *  1. The newest enabled override covering that day that has an effect for the alarm's group
 *     (or for "ungrouped"). It either pauses the group or replaces its repeat rule; a repeat
 *     override also switches on a group that is normally off (e.g. "Vacation").
 *  2. The group being switched off or paused through a date.
 *  3. The group's repeat rule, or the alarm's own rule when it isn't in a group.
 */
object ScheduleCalculator {

    /** How far ahead to search. Long enough for any repeat rule, bounded so a dead rule can't spin forever. */
    const val HORIZON_DAYS = 800L

    fun planFor(
        date: LocalDate,
        alarm: Alarm,
        group: AlarmGroup?,
        overrides: List<OverrideWithEffects>,
    ): DayPlan {
        val targetId = group?.id ?: UNGROUPED_ID
        var winner: ScheduleOverride? = null
        var winnerEffect: OverrideEffect? = null
        for (o in overrides) {
            if (!o.override.covers(date)) continue
            val effect = o.effects.firstOrNull { it.targetGroupId == targetId } ?: continue
            val w = winner
            if (w == null || o.override.createdAt > w.createdAt ||
                (o.override.createdAt == w.createdAt && o.override.id > w.id)
            ) {
                winner = o.override
                winnerEffect = effect
            }
        }
        if (winner != null && winnerEffect != null) {
            return when (winnerEffect.action) {
                OverrideAction.PAUSE -> DayPlan.Paused(PauseReason.OVERRIDE, winner)
                OverrideAction.REPEAT -> DayPlan.Active(winnerEffect.repeat, winner)
            }
        }
        if (group == null) return DayPlan.Active(alarm.repeat, null)
        if (!group.enabled) return DayPlan.Paused(PauseReason.GROUP_OFF)
        val pausedThrough = group.pausedThrough
        if (pausedThrough != null && !date.isAfter(pausedThrough)) return DayPlan.Paused(PauseReason.GROUP_PAUSED)
        return DayPlan.Active(group.repeat, null)
    }

    /**
     * The next instant the alarm should ring strictly after [now], or null if it never will.
     *
     * @param honorSnooze if false, a pending snooze is ignored (used to find the underlying schedule).
     * @param honorSkip if false, skip markers are ignored (used to find what "skip next" would skip).
     */
    fun nextTrigger(
        alarm: Alarm,
        group: AlarmGroup?,
        overrides: List<OverrideWithEffects>,
        now: Instant,
        zone: ZoneId,
        honorSnooze: Boolean = true,
        honorSkip: Boolean = true,
    ): Instant? = nextOccurrence(alarm, group, overrides, now, zone, honorSnooze, honorSkip)?.instant

    data class Occurrence(val instant: Instant, val date: LocalDate, val plan: DayPlan.Active?, val snooze: Boolean)

    fun nextOccurrence(
        alarm: Alarm,
        group: AlarmGroup?,
        overrides: List<OverrideWithEffects>,
        now: Instant,
        zone: ZoneId,
        honorSnooze: Boolean = true,
        honorSkip: Boolean = true,
    ): Occurrence? {
        if (!alarm.enabled) return null
        val nowMs = now.toEpochMilli()
        if (honorSnooze) {
            val snooze = alarm.snoozedUntil
            if (snooze != null && snooze > nowMs) {
                val i = Instant.ofEpochMilli(snooze)
                return Occurrence(i, i.atZone(zone).toLocalDate(), null, snooze = true)
            }
        }
        val skipThreshold = if (honorSkip) {
            maxOf(alarm.skipUntil ?: Long.MIN_VALUE, group?.skipUntil ?: Long.MIN_VALUE)
        } else {
            Long.MIN_VALUE
        }
        val time = LocalTime.of(alarm.hour, alarm.minute)
        // Start a day early: in a DST overlap or just after midnight, "yesterday's" slot can't be later
        // than now, but starting early is cheap and keeps the loop obviously correct.
        val start = now.atZone(zone).toLocalDate().minusDays(1)
        for (i in 0..HORIZON_DAYS) {
            val date = start.plusDays(i)
            val plan = planFor(date, alarm, group, overrides)
            if (plan !is DayPlan.Active) continue
            if (!plan.repeat.matches(date)) continue
            // ZonedDateTime.of moves times inside a DST gap forward, so the alarm still rings.
            val instant = ZonedDateTime.of(date, time, zone).toInstant()
            val ms = instant.toEpochMilli()
            if (ms <= nowMs) continue
            if (ms <= skipThreshold) continue
            if (ms == alarm.lastFiredAt) continue
            return Occurrence(instant, date, plan, snooze = false)
        }
        return null
    }

    /** Whether the occurrence of [alarm] on [date] was a one-off, meaning the alarm should switch off after it. */
    fun isOneOff(
        date: LocalDate,
        alarm: Alarm,
        group: AlarmGroup?,
        overrides: List<OverrideWithEffects>,
    ): Boolean {
        val plan = planFor(date, alarm, group, overrides)
        return plan is DayPlan.Active && plan.repeat.type == RepeatType.ONCE
    }
}
