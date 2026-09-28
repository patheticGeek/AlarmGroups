package com.geek.alarmy.ui.overrides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geek.alarmy.model.RepeatRule
import com.geek.alarmy.model.UNGROUPED_ID
import com.geek.alarmy.ui.components.ConfirmDialog
import com.geek.alarmy.ui.components.DateRangePickerDialog
import com.geek.alarmy.ui.components.RepeatEditor
import com.geek.alarmy.ui.components.dateRange

@Composable
fun OverridesTab(
    state: OverridesUiState,
    vm: OverridesViewModel,
    padding: PaddingValues,
    onEdit: (Long) -> Unit,
) {
    if (state.loading) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.items.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.EventBusy, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("No overrides", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "An override changes groups for a date range — e.g. a vacation that pauses Office and WFH and turns " +
                    "on Vacation, or a week where Office only rings on Wednesday.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        items(state.items, key = { it.data.override.id }) { item ->
            val o = item.data.override
            Card(
                onClick = { onEdit(o.id) },
                colors = CardDefaults.cardColors(
                    containerColor = when (item.phase) {
                        OverridePhase.ACTIVE -> MaterialTheme.colorScheme.secondaryContainer
                        OverridePhase.UPCOMING -> MaterialTheme.colorScheme.surfaceContainerHigh
                        OverridePhase.ENDED -> MaterialTheme.colorScheme.surfaceContainerLow
                    },
                ),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (item.phase) {
                                OverridePhase.ACTIVE -> "Active now"
                                OverridePhase.UPCOMING -> "Upcoming"
                                OverridePhase.ENDED -> "Ended"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(o.name, style = MaterialTheme.typography.titleMedium)
                        Text(dateRange(o.startDate, o.endDate), style = MaterialTheme.typography.bodyMedium)
                        Text(item.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.phase == OverridePhase.ENDED) {
                        IconButton(onClick = { vm.delete(o.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    } else {
                        Switch(checked = o.enabled, onCheckedChange = { vm.setEnabled(o.id, it) })
                    }
                }
            }
        }
        if (state.items.any { it.phase == OverridePhase.ENDED }) {
            item {
                TextButton(onClick = vm::deleteEnded, modifier = Modifier.fillMaxWidth()) { Text("Clear ended overrides") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverrideEditScreen(vm: OverrideEditViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsState()
    var pickRange by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
                title = { Text(if (state.isNew) "New override" else "Edit override") },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                    TextButton(onClick = vm::save, enabled = state.valid) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = vm::setName,
                label = { Text("Name") },
                placeholder = { Text("e.g. Goa trip, Office closed") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            ListItem(
                modifier = Modifier.padding(horizontal = 0.dp),
                leadingContent = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                headlineContent = { Text("Dates") },
                supportingContent = { Text(dateRange(state.start, state.end), color = MaterialTheme.colorScheme.primary) },
                trailingContent = { TextButton(onClick = { pickRange = true }) { Text("Change") } },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("During these dates", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                AssistChip(onClick = vm::pauseEverything, label = { Text("Pause everything") })
            }
            Spacer(Modifier.height(8.dp))
            state.groups.forEach { g ->
                TargetEditor(
                    name = g.name,
                    subtitle = if (g.enabled) null else "Normally off",
                    choice = state.effects[g.id] ?: EffectChoice.None,
                    allowRepeat = true,
                    defaultRule = g.repeat,
                    onChange = { vm.setEffect(g.id, it) },
                )
            }
            TargetEditor(
                name = "Ungrouped alarms",
                subtitle = null,
                choice = state.effects[UNGROUPED_ID] ?: EffectChoice.None,
                allowRepeat = false,
                defaultRule = RepeatRule.Daily,
                onChange = { vm.setEffect(UNGROUPED_ID, it) },
            )
            Text(
                "If overrides overlap, the most recently saved one wins for the groups it changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (pickRange) {
        DateRangePickerDialog(
            start = state.start,
            end = state.end,
            onDismiss = { pickRange = false },
            onPick = vm::setRange,
        )
    }
    if (confirmDelete) {
        ConfirmDialog("Delete override?", "Groups go back to their normal schedule.", "Delete", { confirmDelete = false }, vm::delete)
    }
}

@Composable
private fun TargetEditor(
    name: String,
    subtitle: String?,
    choice: EffectChoice,
    allowRepeat: Boolean,
    defaultRule: RepeatRule,
    onChange: (EffectChoice) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            Spacer(Modifier.height(8.dp))
            val options = buildList {
                add("No change" to EffectChoice.None)
                add("Pause" to EffectChoice.Pause)
                if (allowRepeat) add("Ring on…" to ((choice as? EffectChoice.Repeat) ?: EffectChoice.Repeat(defaultRule)))
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                options.forEachIndexed { i, (label, value) ->
                    SegmentedButton(
                        selected = choice::class == value::class,
                        onClick = { if (choice::class != value::class) onChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
            if (choice is EffectChoice.Repeat) {
                Spacer(Modifier.height(12.dp))
                RepeatEditor(choice.rule, onChange = { onChange(EffectChoice.Repeat(it)) })
            }
        }
    }
}
