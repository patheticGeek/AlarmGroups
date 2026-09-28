package com.geek.routine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geek.routine.RoutineApp
import com.geek.routine.util.doAsync

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
                val c = RoutineApp.container(context)
                val alarm = c.repository.alarm(id) ?: return@doAsync
                val at = alarm.nextTriggerAt ?: return@doAsync
                if (alarm.enabled && at > System.currentTimeMillis()) Notifications.showUpcoming(context, alarm, at)
            }
            ACTION_SKIP_UPCOMING -> doAsync {
                Notifications.cancelUpcoming(context, id)
                RoutineApp.container(context).repository.skipNext(id)
            }
            ACTION_HEARTBEAT -> doAsync {
                RoutineApp.container(context).scheduler.rescheduleAll()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE = "com.geek.routine.action.FIRE"
        const val ACTION_UPCOMING = "com.geek.routine.action.UPCOMING"
        const val ACTION_SKIP_UPCOMING = "com.geek.routine.action.SKIP_UPCOMING"
        const val ACTION_HEARTBEAT = "com.geek.routine.action.HEARTBEAT"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_TRIGGER_AT = "trigger_at"
        const val EXTRA_IS_SNOOZE = "is_snooze"
    }
}

/** Re-registers alarms whenever the system may have dropped them or time has shifted. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("SystemEventsReceiver", "onReceive ${intent.action}")
        val action = intent.action ?: return
        doAsync {
            val scheduler = RoutineApp.container(context).scheduler
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
