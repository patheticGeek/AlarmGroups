package com.geek.alarmy.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.geek.alarmy.data.AlarmDatabase
import com.geek.alarmy.data.AlarmRepository
import com.geek.alarmy.data.SettingsRepository
import com.geek.alarmy.model.Alarm
import com.geek.alarmy.model.AlarmGroup
import com.geek.alarmy.model.RepeatRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Repository + scheduler working together against a real (in-memory) database and shadowed AlarmManager. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AlarmEngineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    // Monday 2026-09-28 06:00.
    private var now: Instant = ZonedDateTime.of(2026, 9, 28, 6, 0, 0, 0, zone).toInstant()
    private val clock = { Clock.fixed(now, zone) }

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var repo: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var am: AlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        am = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java).allowMainThreadQueries().build()
        val settings = SettingsRepository(context, "test-${UUID.randomUUID()}")
        scheduler = AlarmScheduler(context, { repo }, settings, clock)
        repo = AlarmRepository(
            db,
            clock = clock,
            onChanged = { scheduler.rescheduleAll() },
            onRemoved = { scheduler.cancel(it) },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 9, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun scheduledTimes(): List<Long> =
        shadowOf(am).scheduledAlarms.filter { it.alarmClockInfo != null }.map { it.triggerAtTime }.sorted()

    @Test
    fun `without exact alarm access it still schedules, inexactly`() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        repo.saveAlarm(Alarm(hour = 7, minute = 30, repeat = RepeatRule.Daily))
        val alarms = shadowOf(am).scheduledAlarms.filter { it.operation?.isBroadcast == true && it.triggerAtTime == at(28, 7, 30) }
        assertEquals(1, alarms.size)
    }

    @Test
    fun `saving an alarm registers an alarm clock at the right time`() = runBlocking {
        val id = repo.saveAlarm(Alarm(hour = 7, minute = 30, repeat = RepeatRule.Daily))
        assertEquals(listOf(at(28, 7, 30)), scheduledTimes())
        assertEquals(at(28, 7, 30), repo.alarm(id)!!.nextTriggerAt)
    }

    @Test
    fun `disabling and deleting cancel the alarm`() = runBlocking {
        val a = repo.saveAlarm(Alarm(hour = 7, minute = 0, repeat = RepeatRule.Daily))
        val b = repo.saveAlarm(Alarm(hour = 8, minute = 0, repeat = RepeatRule.Daily))
        assertEquals(2, scheduledTimes().size)
        repo.setAlarmEnabled(a, false)
        assertEquals(listOf(at(28, 8, 0)), scheduledTimes())
        repo.deleteAlarm(b)
        assertTrue(scheduledTimes().isEmpty())
    }

    @Test
    fun `turning a group off cancels its alarms and turning it on restores them`() = runBlocking {
        val g = repo.saveGroup(AlarmGroup(name = "Office", repeat = RepeatRule.weekly(RepeatRule.WEEKDAYS)))
        repo.saveAlarm(Alarm(groupId = g, hour = 7, minute = 0))
        assertEquals(listOf(at(28, 7, 0)), scheduledTimes())
        repo.setGroupEnabled(g, false)
        assertTrue(scheduledTimes().isEmpty())
        repo.setGroupEnabled(g, true)
        assertEquals(listOf(at(28, 7, 0)), scheduledTimes())
    }

    @Test
    fun `firing arms a safety snooze, dismissing moves to the next day`() = runBlocking {
        val id = repo.saveAlarm(Alarm(hour = 7, minute = 0, repeat = RepeatRule.Daily, snoozeMinutes = 5))
        now = Instant.ofEpochMilli(at(28, 7, 0))
        repo.onFired(id, at(28, 7, 0), at(28, 7, 5))
        // If nobody acts, it rings again at the safety snooze.
        assertEquals(listOf(at(28, 7, 5)), scheduledTimes())
        now = Instant.ofEpochMilli(at(28, 7, 1))
        repo.dismiss(id)
        assertEquals(listOf(at(29, 7, 0)), scheduledTimes())
        assertTrue(repo.alarm(id)!!.enabled)
    }

    @Test
    fun `snooze reschedules to the snooze time`() = runBlocking {
        val id = repo.saveAlarm(Alarm(hour = 7, minute = 0, repeat = RepeatRule.Daily))
        now = Instant.ofEpochMilli(at(28, 7, 0))
        repo.onFired(id, at(28, 7, 0), at(28, 7, 10))
        repo.snooze(id, at(28, 7, 9))
        assertEquals(listOf(at(28, 7, 9)), scheduledTimes())
    }

    @Test
    fun `dismissing a one-off alarm switches it off`() = runBlocking {
        val id = repo.saveAlarm(Alarm(hour = 7, minute = 0, repeat = RepeatRule.Once))
        now = Instant.ofEpochMilli(at(28, 7, 0))
        repo.onFired(id, at(28, 7, 0), at(28, 7, 10))
        repo.dismiss(id)
        assertFalse(repo.alarm(id)!!.enabled)
        assertTrue(scheduledTimes().isEmpty())
    }

    @Test
    fun `skip next skips one occurrence and undo restores it`() = runBlocking {
        val id = repo.saveAlarm(Alarm(hour = 7, minute = 0, repeat = RepeatRule.Daily))
        repo.skipNext(id)
        assertEquals(listOf(at(29, 7, 0)), scheduledTimes())
        repo.clearSkip(id)
        assertEquals(listOf(at(28, 7, 0)), scheduledTimes())
    }

    @Test
    fun `skip next on a one-off switches it off`() = runBlocking {
        val id = repo.saveAlarm(Alarm(hour = 7, minute = 0, repeat = RepeatRule.Once))
        repo.skipNext(id)
        assertFalse(repo.alarm(id)!!.enabled)
    }

    @Test
    fun `skip group day skips all its alarms that day only`() = runBlocking {
        val g = repo.saveGroup(AlarmGroup(name = "Office", repeat = RepeatRule.Daily))
        repo.saveAlarm(Alarm(groupId = g, hour = 7, minute = 0))
        repo.saveAlarm(Alarm(groupId = g, hour = 22, minute = 0))
        repo.skipNextGroupDay(g)
        assertEquals(listOf(at(29, 7, 0), at(29, 22, 0)), scheduledTimes())
    }

    @Test
    fun `pausing a group through a date resumes the day after`() = runBlocking {
        val g = repo.saveGroup(AlarmGroup(name = "Office", repeat = RepeatRule.Daily))
        repo.saveAlarm(Alarm(groupId = g, hour = 7, minute = 0))
        repo.pauseGroupThrough(g, LocalDate.of(2026, 9, 30))
        assertEquals(listOf(ZonedDateTime.of(2026, 10, 1, 7, 0, 0, 0, zone).toInstant().toEpochMilli()), scheduledTimes())
    }

    @Test
    fun `deleting a group can keep its alarms with the group's schedule`() = runBlocking {
        val g = repo.saveGroup(AlarmGroup(name = "Office", repeat = RepeatRule.weekly(RepeatRule.WEEKDAYS)))
        val id = repo.saveAlarm(Alarm(groupId = g, hour = 7, minute = 0))
        repo.deleteGroup(g, deleteAlarms = false)
        val a = repo.alarm(id)!!
        assertNull(a.groupId)
        assertEquals(RepeatRule.weekly(RepeatRule.WEEKDAYS), a.repeat)
        assertEquals(listOf(at(28, 7, 0)), scheduledTimes())
    }

    @Test
    fun `deleting a group with its alarms cancels them`() = runBlocking {
        val g = repo.saveGroup(AlarmGroup(name = "Office", repeat = RepeatRule.Daily))
        val id = repo.saveAlarm(Alarm(groupId = g, hour = 7, minute = 0))
        repo.deleteGroup(g, deleteAlarms = true)
        assertNull(repo.alarm(id))
        assertTrue(scheduledTimes().isEmpty())
    }

    @Test
    fun `alarm just missed while powered off rings on boot`() = runBlocking {
        val id = repo.saveAlarm(Alarm(hour = 7, minute = 0, repeat = RepeatRule.Daily))
        // Phone was off at 07:00, boots at 07:05; AlarmManager lost everything.
        now = Instant.ofEpochMilli(at(28, 7, 5))
        shadowOf(am).scheduledAlarms.toList().forEach { s -> s.operation?.let(am::cancel) }
        scheduler.handleMissedAfterBoot()
        val started = shadowOf(context as Application).nextStartedService
        assertNotNull("ringing service should start", started)
        assertEquals(AlarmService.ACTION_START, started.action)
        scheduler.rescheduleAll()
        assertEquals(listOf(at(29, 7, 0)), scheduledTimes())
    }

    @Test
    fun `alarm missed long ago while powered off is recorded, not rung`() = runBlocking {
        val id = repo.saveAlarm(Alarm(hour = 7, minute = 0, repeat = RepeatRule.Once))
        now = Instant.ofEpochMilli(at(28, 9, 0))
        scheduler.handleMissedAfterBoot()
        assertNull(shadowOf(context as Application).nextStartedService)
        val a = repo.alarm(id)!!
        assertEquals(at(28, 7, 0), a.lastFiredAt)
        assertFalse("one-off missed alarm is used up", a.enabled)
    }

    @Test
    fun `clock set back does not ring the same occurrence twice`() = runBlocking {
        val id = repo.saveAlarm(Alarm(hour = 7, minute = 0, repeat = RepeatRule.Daily))
        now = Instant.ofEpochMilli(at(28, 7, 0))
        repo.onFired(id, at(28, 7, 0), at(28, 7, 10))
        repo.dismiss(id)
        now = Instant.ofEpochMilli(at(28, 6, 30))
        scheduler.rescheduleAll()
        assertEquals(listOf(at(29, 7, 0)), scheduledTimes())
    }
}
