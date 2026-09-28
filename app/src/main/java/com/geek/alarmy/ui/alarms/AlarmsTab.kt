package com.geek.alarmy.ui.alarms

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.alarmy.model.Alarm
import com.geek.alarmy.model.AlarmGroup
import com.geek.alarmy.ui.components.DatePickerDialogFor
import com.geek.alarmy.ui.components.describe
import com.geek.alarmy.ui.health.Check
import com.geek.alarmy.util.TimeFormat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

@Composable
fun AlarmsTab(
    state: AlarmsUiState,
    vm: AlarmsViewModel,
    failing: List<Check>,
    ringing: List<Alarm>,
    padding: PaddingValues,
    snackbar: SnackbarHostState,
    onFix: () -> Unit,
    onOpenRinging: () -> Unit,
    onAddAlarm: (groupId: Long?) -> Unit,
    onEditAlarm: (Long) -> Unit,
    onEditGroup: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pauseGroup by remember { mutableStateOf<AlarmGroup?>(null) }
    var deleteGroup by remember { mutableStateOf<AlarmGroup?>(null) }

    if (state.loading) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ringing.isNotEmpty()) {
            item(key = "ringing") { RingingBanner(onOpenRinging) }
        }
        val critical = failing.filter { it.critical }
        if (critical.isNotEmpty()) {
            item(key = "health") { HealthBanner(critical, onFix) }
        }
        item(key = "next") { NextAlarmCard(state.next, state.now) }
        if (state.showStarterGroups) {
            item(key = "starter") {
                StarterGroupsCard(onCreate = vm::createStarterGroups, onDismiss = vm::dismissStarterGroups)
            }
        }
        state.sections.forEach { section ->
            val g = section.group
            // The ungrouped section is only worth showing once it has something in it, or there are no groups.
            if (g == null && section.alarms.isEmpty() && state.sections.size > 1) return@forEach
            item(key = "group-${g?.id ?: "none"}") {
                GroupHeader(
                    section = section,
                    onToggle = { g?.let { vm.setGroupEnabled(it.id, !it.enabled) } },
                    onAdd = { onAddAlarm(g?.id) },
                    onEdit = { g?.let { onEditGroup(it.id) } },
                    onPause = { pauseGroup = g },
                    onResume = { g?.let { vm.pauseGroupThrough(it.id, null) } },
                    onSkip = { g?.let { vm.skipGroup(it.id) } },
                    onClearSkip = { g?.let { vm.clearGroupSkip(it.id) } },
                    onDelete = { deleteGroup = g },
                )
            }
            items(section.alarms, key = { "alarm-${it.alarm.id}" }) { item ->
                AlarmRow(
                    item = item,
                    now = state.now,
                    onClick = { onEditAlarm(item.alarm.id) },
                    onToggle = { vm.setAlarmEnabled(item.alarm.id, it) },
                    onSkip = { vm.skipNext(item.alarm.id) },
                    onClearSkip = { vm.clearSkip(item.alarm.id) },
                    onDelete = {
                        vm.deleteAlarm(item.alarm)
                        scope.launch {
                            val r = snackbar.showSnackbar("Alarm deleted", "Undo", duration = SnackbarDuration.Short)
                            if (r == SnackbarResult.ActionPerformed) vm.restoreAlarm(item.alarm)
                        }
                    },
                )
            }
            if (g != null && section.alarms.isEmpty()) {
                item(key = "empty-${g.id}") {
                    Text(
                        "No alarms in this group yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }

    pauseGroup?.let { g ->
        DatePickerDialogFor(
            initial = LocalDate.now().plusDays(1),
            onDismiss = { pauseGroup = null },
            onPick = { vm.pauseGroupThrough(g.id, it) },
        )
    }
    deleteGroup?.let { g -> DeleteGroupDialog(g, onDismiss = { deleteGroup = null }, onDelete = { vm.deleteGroup(g.id, it) }) }
}

@Composable
private fun RingingBanner(onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.NotificationsActive, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("Alarm ringing — tap to snooze or dismiss", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun HealthBanner(failing: List<Check>, onFix: () -> Unit) {
    Card(
        onClick = onFix,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Alarms may not ring reliably",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Fix: " + failing.joinToString { it.title },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun NextAlarmCard(next: NextAlarm?, now: Instant) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Next alarm", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            if (next == null) {
                Text("None scheduled", style = MaterialTheme.typography.headlineSmall)
            } else {
                Text(TimeFormat.whenString(context, next.at), style = MaterialTheme.typography.headlineSmall)
                Text(
                    listOfNotNull(TimeFormat.until(next.at, now), next.alarm.label.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StarterGroupsCard(onCreate: () -> Unit, onDismiss: () -> Unit) {
    OutlinedCard {
        Column(Modifier.padding(16.dp)) {
            Text("Start with groups?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Create Office, WFH, No work and Vacation groups. Each group has its own repeat days and can be paused " +
                    "or overridden for a date range. You can rename or delete them any time.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("No thanks") }
                Button(onClick = onCreate) { Text("Create groups") }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    section: GroupSection,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onClearSkip: () -> Unit,
    onDelete: () -> Unit,
) {
    val g = section.group
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 16.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = g != null, onClick = onEdit)
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    g?.name ?: "Ungrouped",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    section.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add alarm to ${g?.name ?: "Ungrouped"}") }
            if (g != null) {
                Switch(checked = g.enabled, onCheckedChange = { onToggle() })
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Group options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit group") }, onClick = { menu = false; onEdit() })
                        if (g.pausedThrough != null && !g.pausedThrough.isBefore(LocalDate.now())) {
                            DropdownMenuItem(text = { Text("Resume now") }, onClick = { menu = false; onResume() })
                        } else {
                            DropdownMenuItem(text = { Text("Pause until…") }, onClick = { menu = false; onPause() })
                        }
                        if (section.isSkipping) {
                            DropdownMenuItem(text = { Text("Undo skip") }, onClick = { menu = false; onClearSkip() })
                        } else {
                            DropdownMenuItem(
                                text = { Text("Skip next day") },
                                enabled = section.alarms.any { it.next != null },
                                onClick = { menu = false; onSkip() },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Delete group") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
        section.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        if (section.isSkipping) {
            Text("Skipping next day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun AlarmRow(
    item: AlarmItem,
    now: Instant,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSkip: () -> Unit,
    onClearSkip: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val a = item.alarm
    var menu by remember { mutableStateOf(false) }
    val active = a.enabled && item.next != null
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    TimeFormat.time(context, a.hour, a.minute),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                val sub = buildList {
                    if (a.label.isNotBlank()) add(a.label)
                    if (a.groupId == null) add(a.repeat.describe())
                }.joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusLine(item, now)
            }
            Switch(checked = a.enabled, onCheckedChange = onToggle)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Alarm options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (item.skipping != null) {
                        DropdownMenuItem(text = { Text("Undo skip") }, onClick = { menu = false; onClearSkip() })
                    } else {
                        DropdownMenuItem(
                            text = { Text("Skip next") },
                            enabled = item.next != null && item.snoozedUntil == null,
                            onClick = { menu = false; onSkip() },
                        )
                    }
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun StatusLine(item: AlarmItem, now: Instant) {
    val context = LocalContext.current
    val (text, color) = when {
        !item.alarm.enabled -> "Off" to MaterialTheme.colorScheme.onSurfaceVariant
        item.snoozedUntil != null -> "Snoozed until ${TimeFormat.whenString(context, item.snoozedUntil)}" to MaterialTheme.colorScheme.tertiary
        item.next == null -> "Won't ring — group is off or paused" to MaterialTheme.colorScheme.error
        item.skipping != null ->
            "Skipping ${TimeFormat.whenString(context, item.skipping)} · next ${TimeFormat.whenString(context, item.next)}" to
                MaterialTheme.colorScheme.tertiary
        else -> "${TimeFormat.whenString(context, item.next)} · ${TimeFormat.until(item.next, now)}" to MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.alarm.enabled && item.next != null) {
            Icon(Icons.Filled.Alarm, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2)
    }
}

@Composable
private fun DeleteGroupDialog(group: AlarmGroup, onDismiss: () -> Unit, onDelete: (deleteAlarms: Boolean) -> Unit) {
    var deleteAlarms by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${group.name}\"?") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { deleteAlarms = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !deleteAlarms, onClick = { deleteAlarms = false })
                    Text("Keep its alarms as ungrouped (they keep ringing ${group.repeat.describe().lowercase()})")
                }
                Row(
                    Modifier.fillMaxWidth().clickable { deleteAlarms = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = deleteAlarms, onClick = { deleteAlarms = true })
                    Text("Delete its alarms too")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDelete(deleteAlarms); onDismiss() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
