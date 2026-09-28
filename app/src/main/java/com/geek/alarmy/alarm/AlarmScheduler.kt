package com.geek.alarmy.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import com.geek.alarmy.data.AlarmRepository
import com.geek.alarmy.data.SettingsRepository
import com.geek.alarmy.domain.ScheduleCalculator
import com.geek.alarmy.ui.MainActivity
import com.geek.alarmy.widget.WidgetUpdater
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

/**
 * Keeps AlarmManager in sync with the database.
 *
 * Every alarm gets exactly one pending AlarmManager entry for its next ring, registered with
 * [AlarmManager.setAlarmClock] — the only API that is exempt from Doze and app standby and that
 * the system treats as a user-visible alarm clock.
 */
class AlarmScheduler(
    private val context: Context,
    private val repository: () -> AlarmRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Clock = { Clock.systemDefaultZone() },
) {
    private val am = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** Recomputes and re-registers every alarm. Safe to call any time; idempotent. */
    suspend fun rescheduleAll() = mutex.withLock {
        val repo = repository()
        val snap = repo.snapshot()
        val c = clock()
        val now = c.instant()
        val upcomingMinutes = settings.current().upcomingMinutes
        for (alarm in snap.alarms) {
            val occ = ScheduleCalculator.nextOccurrence(alarm, snap.groupOf(alarm), snap.overrides, now, c.zone)
            val at = occ?.instant?.toEpochMilli()
            if (at == null) {
                cancelAlarm(alarm.id)
            } else {
                setAlarm(alarm.id, at, occ.snooze)
            }
            // Upcoming reminders only make sense for a regular ring, not a snooze.
            val remindAt = if (at != null && !occ.snooze && upcomingMinutes > 0) at - upcomingMinutes * 60_000L else null
            when {
                remindAt == null -> {
                    cancelUpcoming(alarm.id)
                    Notifications.cancelUpcoming(context, alarm.id)
                }
                remindAt <= now.toEpochMilli() -> {
                    cancelUpcoming(alarm.id)
                    Notifications.showUpcoming(context, alarm, at!!)
                }
                else -> {
                    setUpcoming(alarm.id, remindAt)
                    Notifications.cancelUpcoming(context, alarm.id)
                }
            }
            if (alarm.nextTriggerAt != at) repo.setNextTrigger(alarm.id, at)
        }
        ensureHeartbeat()
        WidgetUpdater.requestUpdate(context)
        Log.i(TAG, "Rescheduled ${snap.alarms.size} alarms")
    }

    /**
     * After a reboot, AlarmManager has forgotten everything. Any alarm whose registered time passed
     * while the phone was off either rings now (if only just missed) or gets a "missed" notification.
     */
    suspend fun handleMissedAfterBoot() {
        val repo = repository()
        val snap = repo.snapshot()
        val now = clock().instant().toEpochMilli()
        for (alarm in snap.alarms) {
            val due = alarm.nextTriggerAt ?: continue
            if (!alarm.enabled || due > now) continue
            val wasSnooze = alarm.snoozedUntil == due
            if (!wasSnooze && due == alarm.lastFiredAt) continue
            if (now - due <= MISSED_GRACE_MS) {
                Log.w(TAG, "Alarm ${alarm.id} due ${Instant.ofEpochMilli(due)} missed while off; ringing now")
                AlarmService.start(context, alarm.id, due, wasSnooze)
            } else {
                Log.w(TAG, "Alarm ${alarm.id} due ${Instant.ofEpochMilli(due)} missed while off")
                repo.markMissed(alarm.id, if (wasSnooze) alarm.lastFiredAt ?: due else due)
                Notifications.showMissed(context, alarm)
            }
        }
    }

    /** Drops everything registered for alarms that were deleted. */
    fun cancel(ids: List<Long>) {
        for (id in ids) {
            cancelAlarm(id)
            cancelUpcoming(id)
            Notifications.cancelUpcoming(context, id)
        }
    }

    private fun setAlarm(id: Long, at: Long, snooze: Boolean) {
        val op = firePendingIntent(id, at, snooze)
        try {
            if (canScheduleExact()) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent()), op)
            } else {
                // Without exact-alarm access this may be late; the UI warns about it loudly.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarm denied, falling back to inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    private fun cancelAlarm(id: Long) {
        am.cancel(firePendingIntent(id, 0, false))
    }

    private fun setUpcoming(id: Long, at: Long) {
        val op = upcomingPendingIntent(id)
        if (canScheduleExact()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    private fun cancelUpcoming(id: Long) = am.cancel(upcomingPendingIntent(id))

    /** Periodic self-check that re-registers everything, in case anything was dropped. */
    private fun ensureHeartbeat() {
        val op = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_HEARTBEAT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + HEARTBEAT_MS,
            HEARTBEAT_MS,
            op,
        )
    }

    // The data URI makes each alarm's PendingIntent distinct; extras don't take part in matching.
    private fun firePendingIntent(id: Long, at: Long, snooze: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FIRE)
                .setData("alarmy://alarm/$id".toUri())
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id)
                .putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, at)
                .putExtra(AlarmReceiver.EXTRA_IS_SNOOZE, snooze)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun upcomingPendingIntent(id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_UPCOMING)
                .setData("alarmy://upcoming/$id".toUri())
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "AlarmScheduler"
        /** An alarm missed by less than this while the phone was off rings as soon as it's back. */
        const val MISSED_GRACE_MS = 15 * 60_000L
        private const val HEARTBEAT_MS = 3 * 60 * 60_000L
    }
}
