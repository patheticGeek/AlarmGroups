package com.geek.routine.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.geek.routine.R
import com.geek.routine.model.Alarm
import com.geek.routine.ui.MainActivity
import com.geek.routine.ui.ringing.RingingActivity
import com.geek.routine.util.TimeFormat

object Notifications {
    const val CHANNEL_RINGING = "ringing_v1"
    const val CHANNEL_UPCOMING = "upcoming_v1"
    const val CHANNEL_MISSED = "missed_v1"
    const val CHANNEL_FALLBACK = "fallback_v1"

    const val ID_RINGING = 1
    const val ID_FALLBACK = 2
    private const val UPCOMING_BASE = 100_000
    private const val MISSED_BASE = 200_000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_RINGING,
                    context.getString(R.string.channel_ringing),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_ringing_desc)
                    // The service plays sound and vibrates itself, with fallbacks.
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(
                    CHANNEL_UPCOMING,
                    context.getString(R.string.channel_upcoming),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_upcoming_desc) },
                NotificationChannel(
                    CHANNEL_MISSED,
                    context.getString(R.string.channel_missed),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.channel_missed_desc) },
                NotificationChannel(
                    CHANNEL_FALLBACK,
                    context.getString(R.string.channel_fallback),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_fallback_desc)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 600, 800, 600, 800)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            ),
        )
    }

    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    fun ringing(context: Context, alarms: List<Alarm>, snoozeMinutes: Int): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, 0,
            Intent(context, RingingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (alarms.isEmpty()) {
            context.getString(R.string.alarm)
        } else {
            alarms.joinToString(" · ") { TimeFormat.time(context, it.hour, it.minute) }
        }
        val text = alarms.mapNotNull { it.label.takeIf(String::isNotBlank) }.joinToString(", ")
            .ifEmpty { context.getString(R.string.alarm) }
        return NotificationCompat.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0, context.getString(R.string.snooze_n_min, snoozeMinutes),
                AlarmService.actionIntent(context, AlarmService.ACTION_SNOOZE),
            )
            .addAction(
                0, context.getString(R.string.dismiss),
                AlarmService.actionIntent(context, AlarmService.ACTION_DISMISS),
            )
            .build()
    }

    /**
     * Last resort when the ringing service can't be started: an insistent (sound repeats until
     * acted on) full-screen notification. Opening it starts the proper ringing service.
     */
    fun showFallbackAlarm(context: Context, alarmId: Long, triggerAt: Long, isSnooze: Boolean) {
        val open = PendingIntent.getActivity(
            context, 1,
            RingingActivity.fallbackIntent(context, alarmId, triggerAt, isSnooze),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_FALLBACK)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.alarm))
            .setContentText(context.getString(R.string.tap_to_open_alarm))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        post(context, ID_FALLBACK, n)
    }

    fun cancelFallback(context: Context) = NotificationManagerCompat.from(context).cancel(ID_FALLBACK)

    fun showUpcoming(context: Context, alarm: Alarm, triggerAt: Long) {
        if (!canPost(context)) return
        val skip = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_SKIP_UPCOMING)
                .setData("routine://skip/${alarm.id}".toUri())
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_UPCOMING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.upcoming_alarm, TimeFormat.time(context, alarm.hour, alarm.minute)))
            .setContentText(alarm.label.ifBlank { context.getString(R.string.alarm) })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setWhen(triggerAt)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter((triggerAt - System.currentTimeMillis()).coerceAtLeast(1_000))
            .addAction(0, context.getString(R.string.skip_this_one), skip)
            .build()
        post(context, UPCOMING_BASE + alarm.id.toInt(), n)
    }

    fun cancelUpcoming(context: Context, alarmId: Long) =
        NotificationManagerCompat.from(context).cancel(UPCOMING_BASE + alarmId.toInt())

    fun showMissed(context: Context, alarm: Alarm) {
        val n = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.missed_alarm, TimeFormat.time(context, alarm.hour, alarm.minute)))
            .setContentText(context.getString(R.string.missed_alarm_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        post(context, MISSED_BASE + alarm.id.toInt(), n)
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, n: Notification) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
