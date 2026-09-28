package com.geek.alarmy.alarm

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.geek.alarmy.AlarmyApp
import com.geek.alarmy.model.Alarm
import com.geek.alarmy.model.TimeoutAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Alarms currently ringing, observed by the ringing screen and the main screen. */
object RingingState {
    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()
    internal fun set(list: List<Alarm>) {
        _alarms.value = list
    }
}

/**
 * Foreground service that owns a ringing alarm: sound, vibration, wake lock, the full-screen
 * notification and the ring timeout.
 *
 * Safety net: as soon as an alarm starts ringing a "safety snooze" is written to the database and
 * scheduled. If this service or the whole process dies before the user acts, the alarm rings again.
 */
class AlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private lateinit var player: AlarmPlayer
    private var wakeLock: PowerManager.WakeLock? = null
    private val ringing = LinkedHashMap<Long, Alarm>()
    private var timeoutJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        player = AlarmPlayer(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarmy:ringing")
            .apply { acquire(WAKELOCK_MS) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground(), whatever the action.
        goForeground()
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_ALARM_ID, -1)
                val at = intent.getLongExtra(EXTRA_TRIGGER_AT, System.currentTimeMillis())
                val snooze = intent.getBooleanExtra(EXTRA_IS_SNOOZE, false)
                scope.launch { mutex.withLock { onAlarmFired(id, at, snooze) } }
            }
            ACTION_SNOOZE -> scope.launch { mutex.withLock { finishAll(TimeoutAction.SNOOZE) } }
            ACTION_DISMISS -> scope.launch { mutex.withLock { finishAll(TimeoutAction.DISMISS) } }
            else -> scope.launch { mutex.withLock { if (ringing.isEmpty()) shutdown() } }
        }
        // If we're killed mid-ring, the system restarts us with the start intent again.
        return START_REDELIVER_INTENT
    }

    private suspend fun onAlarmFired(id: Long, triggerAt: Long, isSnooze: Boolean) {
        val repo = AlarmyApp.container(this).repository
        val alarm = withContext(Dispatchers.IO) { repo.alarm(id) }
        if (alarm == null || !alarm.enabled) {
            Log.w(TAG, "Ignoring fire for missing/disabled alarm $id")
            if (ringing.isEmpty()) shutdown()
            return
        }
        if (isSnooze && alarm.snoozedUntil == null && id !in ringing) {
            Log.w(TAG, "Ignoring stale snooze for alarm $id (already dismissed)")
            if (ringing.isEmpty()) shutdown()
            return
        }
        val occurrence = if (isSnooze) alarm.lastFiredAt ?: triggerAt else triggerAt
        val safety = System.currentTimeMillis() + alarm.snoozeMinutes.coerceAtLeast(1) * 60_000L
        withContext(NonCancellable + Dispatchers.IO) { repo.onFired(id, occurrence, safety) }

        if (id in ringing) {
            // Our own safety snooze came due while still ringing; it's been re-armed above.
            return
        }
        Log.i(TAG, "Ringing alarm $id (snooze=$isSnooze)")
        ringing[id] = alarm
        RingingState.set(ringing.values.toList())
        goForeground()
        player.start(RingSpec(alarm.ringtoneUri, alarm.volume, alarm.gradualSeconds, alarm.vibrate))
        timeoutJob?.cancel()
        if (alarm.ringMinutes > 0) {
            timeoutJob = scope.launch {
                delay(alarm.ringMinutes * 60_000L)
                mutex.withLock {
                    Log.i(TAG, "Ring timeout for alarm $id -> ${alarm.timeoutAction}")
                    finishAll(alarm.timeoutAction)
                }
            }
        }
    }

    private suspend fun finishAll(action: TimeoutAction) {
        val repo = AlarmyApp.container(this).repository
        val alarms = ringing.values.toList()
        player.stop()
        timeoutJob?.cancel()
        withContext(NonCancellable + Dispatchers.IO) {
            for (a in alarms) {
                when (action) {
                    TimeoutAction.SNOOZE ->
                        repo.snooze(a.id, System.currentTimeMillis() + a.snoozeMinutes.coerceAtLeast(1) * 60_000L)
                    TimeoutAction.DISMISS -> repo.dismiss(a.id)
                }
            }
        }
        ringing.clear()
        RingingState.set(emptyList())
        shutdown()
    }

    private fun shutdown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground() {
        val snooze = ringing.values.lastOrNull()?.snoozeMinutes ?: 10
        val notification = Notifications.ringing(this, ringing.values.toList(), snooze)
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_RINGING, notification, type)
        } catch (e: Exception) {
            // systemExempted needs exact-alarm access; fall back to media playback.
            Log.w(TAG, "startForeground($type) failed, retrying as mediaPlayback", e)
            val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, Notifications.ID_RINGING, notification, fallback)
        }
    }

    override fun onDestroy() {
        player.release()
        timeoutJob?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (ringing.isNotEmpty()) RingingState.set(emptyList())
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmService"
        const val ACTION_START = "com.geek.alarmy.action.RING"
        const val ACTION_SNOOZE = "com.geek.alarmy.action.SNOOZE"
        const val ACTION_DISMISS = "com.geek.alarmy.action.DISMISS"
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_TRIGGER_AT = "trigger_at"
        private const val EXTRA_IS_SNOOZE = "is_snooze"
        private const val WAKELOCK_MS = 3 * 60 * 60_000L

        fun startIntent(context: Context, alarmId: Long, triggerAt: Long, isSnooze: Boolean): Intent =
            Intent(context, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_TRIGGER_AT, triggerAt)
                .putExtra(EXTRA_IS_SNOOZE, isSnooze)

        /**
         * Starts ringing. If the system refuses to start the foreground service, falls back to an
         * insistent full-screen notification that plays the alarm sound on its own.
         */
        fun start(context: Context, alarmId: Long, triggerAt: Long, isSnooze: Boolean) {
            try {
                ContextCompat.startForegroundService(context, startIntent(context, alarmId, triggerAt, isSnooze))
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't start ringing service; using fallback notification", e)
                Notifications.showFallbackAlarm(context, alarmId, triggerAt, isSnooze)
            }
        }

        fun actionIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                action.hashCode(),
                Intent(context, AlarmService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun send(context: Context, action: String) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java).setAction(action))
            }.onFailure { Log.e(TAG, "send($action) failed", it) }
        }
    }
}
