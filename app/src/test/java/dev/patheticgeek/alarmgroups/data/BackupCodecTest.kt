package dev.patheticgeek.alarmgroups.data

import dev.patheticgeek.alarmgroups.model.Alarm
import dev.patheticgeek.alarmgroups.model.AlarmGroup
import dev.patheticgeek.alarmgroups.model.OverrideAction
import dev.patheticgeek.alarmgroups.model.OverrideEffect
import dev.patheticgeek.alarmgroups.model.OverrideWithEffects
import dev.patheticgeek.alarmgroups.model.RepeatRule
import dev.patheticgeek.alarmgroups.model.ScheduleOverride
import dev.patheticgeek.alarmgroups.model.UNGROUPED_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupCodecTest {
    private val office = AlarmGroup(id = 1, name = "Office", repeat = RepeatRule.every(2, LocalDate.of(2026, 9, 1)))
    private val alarm = Alarm(id = 5, groupId = 1, hour = 7, minute = 15, label = "Gym", snoozedUntil = 123, nextTriggerAt = 456)
    private val vacation = OverrideWithEffects(
        ScheduleOverride(id = 3, name = "Trip", startDate = LocalDate.of(2026, 10, 1), endDate = LocalDate.of(2026, 10, 9)),
        listOf(
            OverrideEffect(id = 1, overrideId = 3, targetGroupId = 1, action = OverrideAction.PAUSE),
            OverrideEffect(id = 2, overrideId = 3, targetGroupId = UNGROUPED_ID, action = OverrideAction.PAUSE),
        ),
    )

    @Test
    fun `round trip keeps data and drops transient state`() {
        val text = BackupCodec.encode(Snapshot(listOf(alarm), listOf(office), listOf(vacation)), Settings(defaultSnoozeMinutes = 7))
        val back = BackupCodec.decode(text)
        assertEquals(listOf(office), back.groups)
        assertEquals(alarm.copy(snoozedUntil = null, nextTriggerAt = null), back.alarms.single())
        assertEquals(vacation, back.overrides.single())
        assertEquals(7, back.settings!!.defaultSnoozeMinutes)
    }

    @Test
    fun `dangling group references are cleaned up`() {
        val text = BackupCodec.encode(Snapshot(listOf(alarm.copy(groupId = 99)), emptyList(), listOf(vacation)), null)
        val back = BackupCodec.decode(text)
        assertNull(back.alarms.single().groupId)
        // Only the ungrouped effect survives.
        assertEquals(listOf(UNGROUPED_ID), back.overrides.single().effects.map { it.targetGroupId })
    }

    @Test
    fun `garbage and foreign files are rejected`() {
        assertThrows(BackupException::class.java) { BackupCodec.decode("not json") }
        assertThrows(BackupException::class.java) { BackupCodec.decode("""{"format":"other","groups":[],"alarms":[],"overrides":[]}""") }
    }

    @Test
    fun `newer backup versions are rejected with a clear message`() {
        val e = assertThrows(BackupException::class.java) {
            BackupCodec.decode("""{"format":"alarmgroups-backup","version":99,"groups":[],"alarms":[],"overrides":[]}""")
        }
        assertTrue(e.message!!.contains("newer version"))
    }

    @Test
    fun `out of range values are clamped`() {
        val text = BackupCodec.encode(Snapshot(listOf(alarm.copy(groupId = null, hour = 30, volume = 0)), emptyList(), emptyList()), null)
        val a = BackupCodec.decode(text).alarms.single()
        assertEquals(23, a.hour)
        assertEquals(10, a.volume)
    }
}
