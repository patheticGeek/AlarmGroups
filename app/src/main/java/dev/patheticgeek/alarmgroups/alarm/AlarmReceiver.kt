package dev.patheticgeek.alarmgroups.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.patheticgeek.alarmgroups.AlarmGroupsApp
import dev.patheticgeek.alarmgroups.util.doAsync
import java.time.LocalDate

/** Receives our own AlarmManager broadcasts. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ALARM_ID, -1)
        Log.i(TAG, "onReceive ${intent.action} id=$id")
        when (intent.action) {
            ACTION_FIRE -> {
                // Start the foreground service right away: this is the only moment we're
                // guaranteed to be allowed to, and it keeps the process alive while ringing.
                AlarmService.start(
                    context,
                    id,
                    intent.getLongExtra(EXTRA_TRIGGER_AT, System.currentTimeMillis()),
                    intent.getBooleanExtra(EXTRA_IS_SNOOZE, false),
                )
            }
            ACTION_UPCOMING -> doAsync {
                val c = AlarmGroupsApp.container(context)
                val alarm = c.repository.alarm(id) ?: return@doAsync
                val at = alarm.nextTriggerAt ?: return@doAsync
                val group = alarm.groupId?.let { c.repository.group(it) }
                if (alarm.enabled && at > System.currentTimeMillis()) Notifications.showUpcoming(context, alarm, group, at)
            }
            ACTION_SKIP_UPCOMING -> doAsync {
                Notifications.cancelUpcoming(context, id)
                AlarmGroupsApp.container(context).repository.skipNext(id)
            }
            ACTION_SKIP_GROUP_DAY -> doAsync {
                Notifications.cancelUpcoming(context, id)
                val groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1)
                val day = intent.getLongExtra(EXTRA_EPOCH_DAY, Long.MIN_VALUE)
                if (groupId > 0 && day != Long.MIN_VALUE) {
                    // Rescheduling afterwards clears the other alarms' upcoming notifications too.
                    AlarmGroupsApp.container(context).repository.skipGroupThrough(groupId, LocalDate.ofEpochDay(day))
                }
            }
            ACTION_HEARTBEAT -> doAsync {
                AlarmGroupsApp.container(context).scheduler.rescheduleAll()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE = "dev.patheticgeek.alarmgroups.action.FIRE"
        const val ACTION_UPCOMING = "dev.patheticgeek.alarmgroups.action.UPCOMING"
        const val ACTION_SKIP_UPCOMING = "dev.patheticgeek.alarmgroups.action.SKIP_UPCOMING"
        const val ACTION_SKIP_GROUP_DAY = "dev.patheticgeek.alarmgroups.action.SKIP_GROUP_DAY"
        const val ACTION_HEARTBEAT = "dev.patheticgeek.alarmgroups.action.HEARTBEAT"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_TRIGGER_AT = "trigger_at"
        const val EXTRA_IS_SNOOZE = "is_snooze"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_EPOCH_DAY = "epoch_day"
    }
}

/** Re-registers alarms whenever the system may have dropped them or time has shifted. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("SystemEventsReceiver", "onReceive ${intent.action}")
        val action = intent.action ?: return
        doAsync {
            val scheduler = AlarmGroupsApp.container(context).scheduler
            when (action) {
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_BOOT_COMPLETED,
                -> {
                    scheduler.handleMissedAfterBoot()
                    scheduler.rescheduleAll()
                }
                else -> scheduler.rescheduleAll()
            }
        }
    }
}
