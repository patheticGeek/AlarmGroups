package com.geek.routine.ui.ringing

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.geek.routine.RoutineApp
import com.geek.routine.R
import com.geek.routine.alarm.AlarmService
import com.geek.routine.alarm.Notifications
import com.geek.routine.alarm.RingingState
import com.geek.routine.data.VolumeButtonAction
import com.geek.routine.model.Alarm
import com.geek.routine.ui.theme.RoutineTheme
import com.geek.routine.ui.theme.ThemeMode
import com.geek.routine.util.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Full-screen alarm UI, shown over the lock screen. */
class RingingActivity : ComponentActivity() {
    private var volumeAction = VolumeButtonAction.NOTHING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        handleFallback(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit // Must snooze or dismiss.
        })

        val container = RoutineApp.container(this)
        lifecycleScope.launch { volumeAction = container.settings.current().volumeButtons }
        // Close once nothing is ringing any more (after the service has had a chance to start).
        lifecycleScope.launch {
            if (RingingState.alarms.value.isEmpty()) {
                delay(STARTUP_GRACE_MS)
                if (RingingState.alarms.value.isEmpty()) finish()
            }
            RingingState.alarms.first { it.isEmpty() }
            finish()
        }

        setContent {
            val settings by container.settings.settings.collectAsState(initial = null)
            RoutineTheme(
                themeMode = settings?.themeMode ?: ThemeMode.SYSTEM,
                dynamicColor = settings?.dynamicColor ?: true,
            ) {
                val alarms by RingingState.alarms.collectAsState()
                RingingScreen(
                    alarms = alarms,
                    onSnooze = { act(AlarmService.ACTION_SNOOZE) },
                    onDismiss = { act(AlarmService.ACTION_DISMISS) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleFallback(intent)
    }

    /** Opened from the fallback notification: start the real ringing service now that we're in the foreground. */
    private fun handleFallback(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_ALARM_ID, -1) ?: -1
        if (id < 0) return
        Notifications.cancelFallback(this)
        AlarmService.start(
            this, id,
            intent!!.getLongExtra(EXTRA_TRIGGER_AT, System.currentTimeMillis()),
            intent.getBooleanExtra(EXTRA_IS_SNOOZE, false),
        )
        intent.removeExtra(EXTRA_ALARM_ID)
    }

    private fun act(action: String) {
        AlarmService.send(this, action)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (volumeAction) {
                VolumeButtonAction.NOTHING -> return true // Swallow; don't let it silence the alarm.
                VolumeButtonAction.SNOOZE -> act(AlarmService.ACTION_SNOOZE)
                VolumeButtonAction.DISMISS -> act(AlarmService.ACTION_DISMISS)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val STARTUP_GRACE_MS = 4_000L
        private const val EXTRA_ALARM_ID = "fallback_alarm_id"
        private const val EXTRA_TRIGGER_AT = "fallback_trigger_at"
        private const val EXTRA_IS_SNOOZE = "fallback_is_snooze"

        fun fallbackIntent(context: Context, alarmId: Long, triggerAt: Long, isSnooze: Boolean): Intent =
            Intent(context, RingingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_TRIGGER_AT, triggerAt)
                .putExtra(EXTRA_IS_SNOOZE, isSnooze)
    }
}

@Composable
private fun RingingScreen(alarms: List<Alarm>, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000)
        }
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "scale",
    )
    val snoozeMinutes = alarms.lastOrNull()?.snoozeMinutes ?: 10
    val labels = alarms.mapNotNull { it.label.takeIf(String::isNotBlank) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))
                Icon(
                    Icons.Filled.Alarm,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp).scale(scale),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    TimeFormat.time(context, now.hour, now.minute),
                    fontSize = 80.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (labels.isEmpty()) stringResource(R.string.alarm) else labels.joinToString("\n"),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Icon(Icons.Filled.Snooze, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.snooze_n_min, snoozeMinutes), style = MaterialTheme.typography.titleLarge)
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text(stringResource(R.string.dismiss), style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
