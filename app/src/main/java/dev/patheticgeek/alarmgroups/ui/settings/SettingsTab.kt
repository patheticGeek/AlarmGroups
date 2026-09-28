package dev.patheticgeek.alarmgroups.ui.settings

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.patheticgeek.alarmgroups.AppContainer
import dev.patheticgeek.alarmgroups.BuildConfig
import dev.patheticgeek.alarmgroups.data.BackupManager
import dev.patheticgeek.alarmgroups.data.Settings
import dev.patheticgeek.alarmgroups.data.VolumeButtonAction
import dev.patheticgeek.alarmgroups.model.TimeoutAction
import dev.patheticgeek.alarmgroups.ui.components.ChoiceRow
import dev.patheticgeek.alarmgroups.ui.components.ConfirmDialog
import dev.patheticgeek.alarmgroups.ui.components.SoundPickerRow
import dev.patheticgeek.alarmgroups.ui.components.durationLabel
import dev.patheticgeek.alarmgroups.ui.components.ringLengthLabel
import dev.patheticgeek.alarmgroups.ui.edit.GRADUAL_OPTIONS
import dev.patheticgeek.alarmgroups.ui.edit.RING_OPTIONS
import dev.patheticgeek.alarmgroups.ui.edit.SNOOZE_OPTIONS
import dev.patheticgeek.alarmgroups.ui.edit.VolumeRow
import dev.patheticgeek.alarmgroups.ui.health.Check
import dev.patheticgeek.alarmgroups.ui.health.Health
import dev.patheticgeek.alarmgroups.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val backup = BackupManager(c.app, c.repository, c.settings)

    fun testAlarm() = c.scheduler.scheduleTest()

    fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            c.settings.update(transform)
            // The upcoming-notification lead time affects scheduling.
            c.scheduler.rescheduleAll()
        }
    }
}

@Composable
fun SettingsTab(
    vm: SettingsViewModel,
    failing: List<Check>,
    padding: PaddingValues,
    snackbar: SnackbarHostState,
    onHealthChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded by vm.settings.collectAsState()
    val settings = loaded ?: return
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onHealthChanged()
        if (!it) Health.fix(context, Check.NOTIFICATIONS)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching { vm.backup.export(uri) }.fold({ "Backup saved" }, { "Backup failed: ${it.message}" })
            snackbar.showSnackbar(msg)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingImport = uri
    }

    Column(
        Modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Section("Reliability")
        Check.entries.forEach { check ->
            val ok = check !in failing
            ListItem(
                leadingContent = {
                    Icon(
                        when {
                            ok -> Icons.Filled.CheckCircle
                            check.critical -> Icons.Filled.Error
                            else -> Icons.Filled.Warning
                        },
                        contentDescription = if (ok) "OK" else "Needs attention",
                        tint = when {
                            ok -> MaterialTheme.colorScheme.primary
                            check.critical -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.tertiary
                        },
                    )
                },
                headlineContent = { Text(check.title) },
                supportingContent = { Text(check.why) },
                trailingContent = if (ok) {
                    null
                } else {
                    {
                        TextButton(onClick = {
                            if (check == Check.NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                Health.fix(context, check)
                            }
                        }) { Text("Fix") }
                    }
                },
            )
        }

        ListItem(
            modifier = Modifier.clickable {
                vm.testAlarm()
                scope.launch { snackbar.showSnackbar("Test alarm in 10 seconds — lock your phone now") }
            },
            leadingContent = { Icon(Icons.Filled.AlarmOn, contentDescription = null) },
            headlineContent = { Text("Test alarm") },
            supportingContent = {
                Text("Rings in 10 seconds with your default alarm settings. Lock the phone to check it wakes the screen.")
            },
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Appearance")
        ChoiceRow(
            title = "Theme",
            value = settings.themeMode,
            options = ThemeMode.entries,
            label = { it.name.lowercase().replaceFirstChar(Char::uppercase).let { n -> if (n == "System") "Follow system" else n } },
            onSelect = { v -> vm.update { it.copy(themeMode = v) } },
            icon = Icons.Filled.Brightness6,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ListItem(
                modifier = Modifier.clickable { vm.update { it.copy(dynamicColor = !it.dynamicColor) } },
                leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                headlineContent = { Text("Dynamic color") },
                supportingContent = { Text("Use colors from your wallpaper") },
                trailingContent = {
                    Switch(checked = settings.dynamicColor, onCheckedChange = { v -> vm.update { it.copy(dynamicColor = v) } })
                },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Defaults for new alarms")
        SoundPickerRow(settings.defaultRingtoneUri, settings.defaultRingtoneTitle) { uri, title ->
            vm.update { it.copy(defaultRingtoneUri = uri, defaultRingtoneTitle = title) }
        }
        VolumeRow(settings.defaultVolume) { v -> vm.update { it.copy(defaultVolume = v) } }
        ChoiceRow(
            title = "Gradually increase volume",
            value = settings.defaultGradualSeconds,
            options = GRADUAL_OPTIONS,
            label = ::durationLabel,
            onSelect = { v -> vm.update { it.copy(defaultGradualSeconds = v) } },
            icon = Icons.AutoMirrored.Filled.TrendingUp,
        )
        ListItem(
            modifier = Modifier.clickable { vm.update { it.copy(defaultVibrate = !it.defaultVibrate) } },
            leadingContent = { Icon(Icons.Filled.Vibration, contentDescription = null) },
            headlineContent = { Text("Vibrate") },
            trailingContent = {
                Switch(checked = settings.defaultVibrate, onCheckedChange = { v -> vm.update { it.copy(defaultVibrate = v) } })
            },
        )
        ChoiceRow(
            title = "Snooze length",
            value = settings.defaultSnoozeMinutes,
            options = SNOOZE_OPTIONS,
            label = { "$it min" },
            onSelect = { v -> vm.update { it.copy(defaultSnoozeMinutes = v) } },
            icon = Icons.Filled.Snooze,
        )
        ChoiceRow(
            title = "Ring for",
            value = settings.defaultRingMinutes,
            options = RING_OPTIONS,
            label = ::ringLengthLabel,
            onSelect = { v -> vm.update { it.copy(defaultRingMinutes = v) } },
            icon = Icons.Filled.Timer,
        )
        ChoiceRow(
            title = "Then",
            value = settings.defaultTimeoutAction,
            options = TimeoutAction.entries,
            label = { if (it == TimeoutAction.SNOOZE) "Snooze and ring again" else "Stop ringing" },
            onSelect = { v -> vm.update { it.copy(defaultTimeoutAction = v) } },
            icon = Icons.Filled.HourglassBottom,
            enabled = settings.defaultRingMinutes > 0,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Behavior")
        ChoiceRow(
            title = "Upcoming alarm notification",
            value = settings.upcomingMinutes,
            options = listOf(0, 15, 30, 60, 120, 180),
            label = { if (it == 0) "Off" else if (it < 60) "$it min before" else "${it / 60} h before" },
            onSelect = { v -> vm.update { it.copy(upcomingMinutes = v) } },
            icon = Icons.Filled.NotificationsActive,
        )
        ChoiceRow(
            title = "Volume buttons while ringing",
            value = settings.volumeButtons,
            options = VolumeButtonAction.entries,
            label = {
                when (it) {
                    VolumeButtonAction.NOTHING -> "Do nothing"
                    VolumeButtonAction.SNOOZE -> "Snooze"
                    VolumeButtonAction.DISMISS -> "Dismiss"
                }
            },
            onSelect = { v -> vm.update { it.copy(volumeButtons = v) } },
            icon = Icons.AutoMirrored.Filled.VolumeDown,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Backup")
        ListItem(
            modifier = Modifier.clickable { exportLauncher.launch("alarmgroups-backup-${LocalDate.now()}.json") },
            leadingContent = { Icon(Icons.Filled.Upload, contentDescription = null) },
            headlineContent = { Text("Export backup") },
            supportingContent = { Text("Save groups, alarms, overrides and settings to a file") },
        )
        ListItem(
            modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
            headlineContent = { Text("Restore backup") },
            supportingContent = { Text("Replaces everything in the app with the backup") },
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(
            leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
            headlineContent = { Text("Version") },
            supportingContent = { Text(BuildConfig.VERSION_NAME) },
        )
    }

    pendingImport?.let { uri ->
        ConfirmDialog(
            title = "Restore backup?",
            text = "All current groups, alarms and overrides will be replaced by the ones in the backup.",
            confirm = "Restore",
            onDismiss = { pendingImport = null },
            onConfirm = {
                scope.launch {
                    val msg = runCatching { vm.backup.import(uri) }.fold(
                        { "Restored ${it.alarms.size} alarms in ${it.groups.size} groups" },
                        { "Restore failed: ${it.message}" },
                    )
                    snackbar.showSnackbar(msg)
                }
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
