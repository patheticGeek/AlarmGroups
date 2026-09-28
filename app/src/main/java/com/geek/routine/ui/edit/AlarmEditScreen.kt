package com.geek.routine.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.routine.model.RepeatRule
import com.geek.routine.model.TimeoutAction
import com.geek.routine.ui.components.ChoiceRow
import com.geek.routine.ui.components.ConfirmDialog
import com.geek.routine.ui.components.RepeatEditor
import com.geek.routine.ui.components.SoundPickerRow
import com.geek.routine.ui.components.TimePickerDialog
import com.geek.routine.ui.components.describe
import com.geek.routine.ui.components.durationLabel
import com.geek.routine.ui.components.ringLengthLabel
import com.geek.routine.util.TimeFormat
import kotlin.math.roundToInt

val GRADUAL_OPTIONS = listOf(0, 15, 30, 60, 120, 300, 600)
val SNOOZE_OPTIONS = listOf(1, 3, 5, 10, 15, 20, 30, 45, 60)
val RING_OPTIONS = listOf(1, 3, 5, 10, 15, 20, 30, 60, 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(vm: AlarmEditViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.done) { if (state.done) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
                title = { Text(if (state.isNew) "New alarm" else "Edit alarm") },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                    val invalid = state.draft?.let { it.groupId == null && it.repeat.isEmpty } ?: true
                    TextButton(onClick = vm::save, enabled = !invalid) { Text("Save") }
                },
            )
        },
    ) { padding ->
        val draft = state.draft
        if (draft == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val groups = state.snapshot?.groups.orEmpty()
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                TimeFormat.time(context, draft.hour, draft.minute),
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickTime = true }
                    .padding(vertical = 16.dp),
            )
            Text(
                state.preview?.let { "Rings ${TimeFormat.whenString(context, it)} · ${TimeFormat.until(it)}" }
                    ?: "Won't ring with the current group settings",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (state.preview == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = draft.label,
                onValueChange = { v -> vm.update { it.copy(label = v.take(60)) } },
                label = { Text("Label") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            // Group
            var groupMenu by remember { mutableStateOf(false) }
            val group = draft.groupId?.let { id -> groups.firstOrNull { it.id == id } }
            Box {
                ListItem(
                    modifier = Modifier.clickable { groupMenu = true },
                    leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    headlineContent = { Text("Group") },
                    supportingContent = {
                        Text(
                            group?.let { "${it.name} · ${it.repeat.describe()}${if (!it.enabled) " (off)" else ""}" }
                                ?: "None — this alarm has its own repeat",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
                DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                    DropdownMenuItem(text = { Text("None (own repeat)") }, onClick = {
                        groupMenu = false
                        // Leaving a group: start from the group's schedule so nothing changes unexpectedly.
                        vm.update { it.copy(groupId = null, repeat = group?.repeat ?: it.repeat) }
                    })
                    groups.forEach { g ->
                        DropdownMenuItem(
                            text = { Text("${g.name} · ${g.repeat.describe()}") },
                            onClick = { groupMenu = false; vm.update { it.copy(groupId = g.id) } },
                        )
                    }
                }
            }
            if (draft.groupId == null) {
                Text(
                    "Repeat",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                )
                RepeatEditor(draft.repeat, onChange = { r: RepeatRule -> vm.update { it.copy(repeat = r) } })
                Spacer(Modifier.height(8.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SoundPickerRow(draft.ringtoneUri, draft.ringtoneTitle) { uri, title ->
                vm.update { it.copy(ringtoneUri = uri, ringtoneTitle = title) }
            }
            VolumeRow(draft.volume) { v -> vm.update { it.copy(volume = v) } }
            ChoiceRow(
                title = "Gradually increase volume",
                value = draft.gradualSeconds,
                options = GRADUAL_OPTIONS,
                label = ::durationLabel,
                onSelect = { v -> vm.update { it.copy(gradualSeconds = v) } },
                icon = Icons.AutoMirrored.Filled.TrendingUp,
            )
            ListItem(
                modifier = Modifier.clickable { vm.update { it.copy(vibrate = !it.vibrate) } },
                leadingContent = { Icon(Icons.Filled.Vibration, contentDescription = null) },
                headlineContent = { Text("Vibrate") },
                trailingContent = { Switch(checked = draft.vibrate, onCheckedChange = { v -> vm.update { it.copy(vibrate = v) } }) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ChoiceRow(
                title = "Snooze length",
                value = draft.snoozeMinutes,
                options = SNOOZE_OPTIONS,
                label = { "$it min" },
                onSelect = { v -> vm.update { it.copy(snoozeMinutes = v) } },
                icon = Icons.Filled.Snooze,
            )
            ChoiceRow(
                title = "Ring for",
                value = draft.ringMinutes,
                options = RING_OPTIONS,
                label = ::ringLengthLabel,
                onSelect = { v -> vm.update { it.copy(ringMinutes = v) } },
                icon = Icons.Filled.Timer,
            )
            if (draft.ringMinutes > 0) {
                ChoiceRow(
                    title = "Then",
                    value = draft.timeoutAction,
                    options = TimeoutAction.entries,
                    label = { if (it == TimeoutAction.SNOOZE) "Snooze and ring again" else "Stop ringing" },
                    onSelect = { v -> vm.update { it.copy(timeoutAction = v) } },
                    icon = Icons.Filled.HourglassBottom,
                )
            }
        }
    }

    val draft = state.draft
    if (pickTime && draft != null) {
        TimePickerDialog(
            hour = draft.hour,
            minute = draft.minute,
            onDismiss = { pickTime = false },
            onPick = { h, m -> vm.update { it.copy(hour = h, minute = m) } },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete alarm?",
            text = "This can't be undone.",
            confirm = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = vm::delete,
        )
    }
}

@Composable
fun VolumeRow(volume: Int, onChange: (Int) -> Unit) {
    var v by remember(volume) { mutableStateOf(volume.toFloat()) }
    ListItem(
        leadingContent = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Volume")
                Text("${v.roundToInt()}%", color = MaterialTheme.colorScheme.primary)
            }
        },
        supportingContent = {
            Slider(
                value = v,
                onValueChange = { v = it },
                onValueChangeFinished = { onChange(v.roundToInt()) },
                valueRange = 10f..100f,
                steps = 8,
            )
        },
    )
}
