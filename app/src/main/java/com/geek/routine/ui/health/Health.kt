package com.geek.routine.ui.health

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import com.geek.routine.alarm.Notifications

enum class Check(val title: String, val why: String, val critical: Boolean) {
    NOTIFICATIONS(
        "Notifications",
        "Needed to show the ringing alarm and its Snooze/Dismiss buttons.",
        critical = true,
    ),
    RINGING_CHANNEL(
        "Ringing alarm notifications",
        "The \"Ringing alarms\" notification category is turned off or silenced.",
        critical = true,
    ),
    EXACT_ALARMS(
        "Alarms & reminders",
        "Without this, Android may deliver alarms minutes late.",
        critical = true,
    ),
    FULL_SCREEN(
        "Full-screen alarms",
        "Lets the alarm take over the lock screen instead of showing as a small notification.",
        critical = true,
    ),
    BATTERY(
        "Unrestricted battery",
        "Stops the system from putting the app to sleep. Recommended for reliability.",
        critical = false,
    ),
}

object Health {
    fun failing(context: Context): List<Check> = Check.entries.filterNot { isOk(context, it) }

    fun isOk(context: Context, check: Check): Boolean = when (check) {
        Check.NOTIFICATIONS -> Notifications.canPost(context)
        Check.RINGING_CHANNEL -> {
            val ch = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(Notifications.CHANNEL_RINGING)
            ch == null || ch.importance >= NotificationManager.IMPORTANCE_HIGH
        }
        Check.EXACT_ALARMS -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        Check.FULL_SCREEN -> Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        Check.BATTERY -> context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens the system screen that fixes [check]. Notification permission itself is requested by the caller. */
    @SuppressLint("BatteryLife") // Alarm clocks are an allowed use case.
    fun fix(context: Context, check: Check) {
        val pkg = "package:${context.packageName}".toUri()
        val intent = when (check) {
            Check.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Check.RINGING_CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_RINGING)
            Check.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            } else {
                null
            }
            Check.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            } else {
                null
            }
            Check.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
        } ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
