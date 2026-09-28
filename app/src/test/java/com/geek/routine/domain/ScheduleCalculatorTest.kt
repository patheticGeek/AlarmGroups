package com.geek.routine.domain

import com.geek.routine.model.Alarm
import com.geek.routine.model.AlarmGroup
import com.geek.routine.model.OverrideAction
import com.geek.routine.model.OverrideEffect
import com.geek.routine.model.OverrideWithEffects
import com.geek.routine.model.RepeatRule
import com.geek.routine.model.RepeatType
import com.geek.routine.model.ScheduleOverride
import com.geek.routine.model.UNGROUPED_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleCalculatorTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    // 2026-09-28 is a Monday.
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, z: ZoneId = zone) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), z)

    private fun date(y: Int, mo: Int, d: Int) = LocalDate.of(y, mo, d)

    private fun next(
        alarm: Alarm,
        group: AlarmGroup? = null,
        overrides: List<OverrideWithEffects> = emptyList(),
        now: ZonedDateTime,
    ) = ScheduleCalculator.nextTrigger(alarm, group, overrides, now.toInstant(), now.zone)?.atZone(now.zone)

    private val office = AlarmGroup(id = 1, name = "Office", repeat = RepeatRule.weekly(RepeatRule.WEEKDAYS))
    private val vacation = AlarmGroup(id = 2, name = "Vacation", repeat = RepeatRule.Daily, enabled = false)

    private fun override(
        id: Long,
        start: LocalDate,
        end: LocalDate,
        vararg effects: OverrideEffect,
        createdAt: Long = id,
        enabled: Boolean = true,
    ) = OverrideWithEffects(
        ScheduleOverride(id = id, name = "o$id", startDate = start, endDate = end, createdAt = createdAt, enabled = enabled),
        effects.map { it.copy(overrideId = id) },
    )

    @Test
    fun `once alarm later today rings today`() {
        val a = Alarm(id = 1, hour = 9, minute = 0)
        assertEquals(at(2026, 9, 28, 9, 0), next(a, now = at(2026, 9, 28, 8, 0)))
    }

    @Test
    fun `once alarm already past today rings tomorrow`() {
        val a = Alarm(id = 1, hour = 7, minute = 0)
        assertEquals(at(2026, 9, 29, 7, 0), next(a, now = at(2026, 9, 28, 8, 0)))
    }

    @Test
    fun `alarm at exactly now is not returned`() {
        val a = Alarm(id = 1, hour = 8, minute = 0, repeat = RepeatRule.Daily)
        assertEquals(at(2026, 9, 29, 8, 0), next(a, now = at(2026, 9, 28, 8, 0)))
    }

    @Test
    fun `once alarm with date`() {
        val a = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule(RepeatType.ONCE, onceDate = date(2026, 10, 5)))
        assertEquals(at(2026, 10, 5, 7, 0), next(a, now = at(2026, 9, 28, 8, 0)))
    }

    @Test
    fun `once alarm with past date never rings`() {
        val a = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule(RepeatType.ONCE, onceDate = date(2026, 9, 1)))
        assertNull(next(a, now = at(2026, 9, 28, 8, 0)))
    }

    @Test
    fun `disabled alarm never rings`() {
        val a = Alarm(id = 1, hour = 9, minute = 0, enabled = false, repeat = RepeatRule.Daily)
        assertNull(next(a, now = at(2026, 9, 28, 8, 0)))
    }

    @Test
    fun `weekly skips to next selected day`() {
        val a = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.weekly(MONDAY, WEDNESDAY, FRIDAY))
        // Monday 08:00, Monday's slot passed -> Wednesday.
        assertEquals(at(2026, 9, 30, 7, 0), next(a, now = at(2026, 9, 28, 8, 0)))
        // Friday 08:00 -> next Monday.
        assertEquals(at(2026, 10, 5, 7, 0), next(a, now = at(2026, 10, 2, 8, 0)))
    }

    @Test
    fun `weekly with no days never rings`() {
        val a = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.weekly(0))
        assertNull(next(a, now = at(2026, 9, 28, 8, 0)))
    }

    @Test
    fun `interval every 3 days from anchor`() {
        val a = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.every(3, date(2026, 9, 27)))
        // Anchor Sep 27 -> Sep 30, Oct 3 ...
        assertEquals(at(2026, 9, 30, 7, 0), next(a, now = at(2026, 9, 28, 8, 0)))
        assertEquals(at(2026, 10, 3, 7, 0), next(a, now = at(2026, 9, 30, 7, 0)))
    }

    @Test
    fun `interval with future anchor waits for anchor`() {
        val a = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.every(2, date(2026, 10, 10)))
        assertEquals(at(2026, 10, 10, 7, 0), next(a, now = at(2026, 9, 28, 8, 0)))
    }

    @Test
    fun `grouped alarm follows group repeat, not its own`() {
        val a = Alarm(id = 1, groupId = 1, hour = 7, minute = 0, repeat = RepeatRule.Daily)
        // Friday 08:00: office is weekdays, so next is Monday even though alarm's own rule says daily.
        assertEquals(at(2026, 10, 5, 7, 0), next(a, office, now = at(2026, 10, 2, 8, 0)))
    }

    @Test
    fun `disabled group never rings`() {
        val a = Alarm(id = 1, groupId = 2, hour = 7, minute = 0)
        assertNull(next(a, vacation, now = at(2026, 9, 28, 8, 0)))
    }

    @Test
    fun `paused group resumes day after pausedThrough`() {
        val g = office.copy(pausedThrough = date(2026, 9, 30))
        val a = Alarm(id = 1, groupId = 1, hour = 7, minute = 0)
        assertEquals(at(2026, 10, 1, 7, 0), next(a, g, now = at(2026, 9, 28, 6, 0)))
    }

    @Test
    fun `override pauses a group within its range only`() {
        val a = Alarm(id = 1, groupId = 1, hour = 7, minute = 0)
        val o = override(
            1, date(2026, 9, 28), date(2026, 10, 2),
            OverrideEffect(targetGroupId = 1, action = OverrideAction.PAUSE),
        )
        assertEquals(at(2026, 10, 5, 7, 0), next(a, office, listOf(o), now = at(2026, 9, 28, 6, 0)))
    }

    @Test
    fun `override switches on a disabled group with its own repeat`() {
        val a = Alarm(id = 1, groupId = 2, hour = 9, minute = 30)
        val o = override(
            1, date(2026, 10, 3), date(2026, 10, 10),
            OverrideEffect(targetGroupId = 2, action = OverrideAction.REPEAT, repeat = RepeatRule.Daily),
        )
        val now = at(2026, 9, 28, 6, 0)
        assertEquals(at(2026, 10, 3, 9, 30), next(a, vacation, listOf(o), now = now))
        // After the range ends, the group is off again.
        assertNull(next(a, vacation, listOf(o), now = at(2026, 10, 10, 10, 0)))
    }

    @Test
    fun `cross-group vacation rule pauses office and enables vacation`() {
        val officeAlarm = Alarm(id = 1, groupId = 1, hour = 7, minute = 0)
        val vacAlarm = Alarm(id = 2, groupId = 2, hour = 10, minute = 0)
        val o = override(
            1, date(2026, 9, 29), date(2026, 10, 4),
            OverrideEffect(targetGroupId = 1, action = OverrideAction.PAUSE),
            OverrideEffect(targetGroupId = 2, action = OverrideAction.REPEAT, repeat = RepeatRule.Daily),
        )
        val now = at(2026, 9, 28, 8, 0)
        assertEquals(at(2026, 10, 5, 7, 0), next(officeAlarm, office, listOf(o), now))
        assertEquals(at(2026, 9, 29, 10, 0), next(vacAlarm, vacation, listOf(o), now))
    }

    @Test
    fun `override replacing repeat rule`() {
        // Office normally weekdays; for a week, only Wednesday.
        val a = Alarm(id = 1, groupId = 1, hour = 7, minute = 0)
        val o = override(
            1, date(2026, 9, 28), date(2026, 10, 4),
            OverrideEffect(targetGroupId = 1, action = OverrideAction.REPEAT, repeat = RepeatRule.weekly(WEDNESDAY)),
        )
        assertEquals(at(2026, 9, 30, 7, 0), next(a, office, listOf(o), now = at(2026, 9, 28, 6, 0)))
        assertEquals(at(2026, 10, 5, 7, 0), next(a, office, listOf(o), now = at(2026, 9, 30, 8, 0)))
    }

    @Test
    fun `newest override wins on conflict`() {
        val a = Alarm(id = 1, groupId = 1, hour = 7, minute = 0)
        val pause = override(
            1, date(2026, 9, 28), date(2026, 10, 10),
            OverrideEffect(targetGroupId = 1, action = OverrideAction.PAUSE), createdAt = 100,
        )
        val daily = override(
            2, date(2026, 10, 3), date(2026, 10, 3),
            OverrideEffect(targetGroupId = 1, action = OverrideAction.REPEAT, repeat = RepeatRule.Daily), createdAt = 200,
        )
        // Saturday Oct 3 rings due to the newer override despite the older pause.
        assertEquals(at(2026, 10, 3, 7, 0), next(a, office, listOf(pause, daily), now = at(2026, 9, 28, 6, 0)))
        // Reverse creation order: pause wins everywhere in its range.
        val older = daily.copy(override = daily.override.copy(createdAt = 50))
        assertEquals(at(2026, 10, 12, 7, 0), next(a, office, listOf(pause, older), now = at(2026, 9, 28, 6, 0)))
    }

    @Test
    fun `disabled override is ignored`() {
        val a = Alarm(id = 1, groupId = 1, hour = 7, minute = 0)
        val o = override(
            1, date(2026, 9, 28), date(2026, 10, 2),
            OverrideEffect(targetGroupId = 1, action = OverrideAction.PAUSE), enabled = false,
        )
        assertEquals(at(2026, 9, 28, 7, 0), next(a, office, listOf(o), now = at(2026, 9, 28, 6, 0)))
    }

    @Test
    fun `override on ungrouped target pauses ungrouped alarms only`() {
        val loose = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.Daily)
        val grouped = Alarm(id = 2, groupId = 1, hour = 7, minute = 0)
        val o = override(
            1, date(2026, 9, 28), date(2026, 9, 30),
            OverrideEffect(targetGroupId = UNGROUPED_ID, action = OverrideAction.PAUSE),
        )
        val now = at(2026, 9, 28, 6, 0)
        assertEquals(at(2026, 10, 1, 7, 0), next(loose, null, listOf(o), now))
        assertEquals(at(2026, 9, 28, 7, 0), next(grouped, office, listOf(o), now))
    }

    @Test
    fun `snooze takes priority over schedule and paused group`() {
        val snoozeAt = at(2026, 9, 28, 7, 10).toInstant().toEpochMilli()
        val a = Alarm(id = 1, groupId = 1, hour = 7, minute = 0, snoozedUntil = snoozeAt)
        val paused = office.copy(enabled = false)
        assertEquals(at(2026, 9, 28, 7, 10), next(a, paused, now = at(2026, 9, 28, 7, 1)))
    }

    @Test
    fun `stale snooze is ignored`() {
        val snoozeAt = at(2026, 9, 28, 7, 10).toInstant().toEpochMilli()
        val a = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.Daily, snoozedUntil = snoozeAt)
        assertEquals(at(2026, 9, 29, 7, 0), next(a, now = at(2026, 9, 28, 7, 20)))
    }

    @Test
    fun `alarm skip skips exactly one occurrence`() {
        val skip = at(2026, 9, 28, 7, 0).toInstant().toEpochMilli()
        val a = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.Daily, skipUntil = skip)
        assertEquals(at(2026, 9, 29, 7, 0), next(a, now = at(2026, 9, 28, 6, 0)))
    }

    @Test
    fun `group skip applies to all its alarms`() {
        val skip = at(2026, 9, 28, 23, 59).toInstant().toEpochMilli()
        val g = office.copy(skipUntil = skip)
        val a = Alarm(id = 1, groupId = 1, hour = 7, minute = 0)
        val b = Alarm(id = 2, groupId = 1, hour = 21, minute = 0)
        val now = at(2026, 9, 28, 6, 0)
        assertEquals(at(2026, 9, 29, 7, 0), next(a, g, now = now))
        assertEquals(at(2026, 9, 29, 21, 0), next(b, g, now = now))
    }

    @Test
    fun `same occurrence never fires twice even if clock goes back`() {
        val fired = at(2026, 9, 28, 7, 0).toInstant().toEpochMilli()
        val a = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.Daily, lastFiredAt = fired)
        // Clock was set back to 06:30 after the 07:00 alarm rang.
        assertEquals(at(2026, 9, 29, 7, 0), next(a, now = at(2026, 9, 28, 6, 30)))
    }

    @Test
    fun `alarm in DST gap is moved forward, not dropped`() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-08 02:00 -> 03:00 in New York.
        val a = Alarm(id = 1, hour = 2, minute = 30, repeat = RepeatRule.Daily)
        val result = next(a, now = at(2026, 3, 8, 1, 0, ny))!!
        assertEquals(date(2026, 3, 8), result.toLocalDate())
        assertEquals(3, result.hour)
        assertEquals(30, result.minute)
    }

    @Test
    fun `alarm in DST overlap rings once`() {
        val ny = ZoneId.of("America/New_York")
        // 2026-11-01 01:00-02:00 happens twice in New York.
        val a = Alarm(id = 1, hour = 1, minute = 30, repeat = RepeatRule.Daily)
        val first = next(a, now = at(2026, 11, 1, 0, 0, ny))!!
        val firedMs = first.toInstant().toEpochMilli()
        val after = ScheduleCalculator.nextTrigger(
            a.copy(lastFiredAt = firedMs), null, emptyList(), first.toInstant().plusSeconds(60), ny,
        )!!.atZone(ny)
        assertEquals(date(2026, 11, 2), after.toLocalDate())
    }

    @Test
    fun `one-off detection respects overrides`() {
        val g = office.copy(repeat = RepeatRule.Once)
        val a = Alarm(id = 1, groupId = 1, hour = 7, minute = 0)
        assertTrue(ScheduleCalculator.isOneOff(date(2026, 9, 28), a, g, emptyList()))
        val o = override(
            1, date(2026, 9, 28), date(2026, 9, 28),
            OverrideEffect(targetGroupId = 1, action = OverrideAction.REPEAT, repeat = RepeatRule.Daily),
        )
        assertFalse(ScheduleCalculator.isOneOff(date(2026, 9, 28), a, g, listOf(o)))
    }
}
