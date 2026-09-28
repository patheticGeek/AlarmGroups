package com.geek.routine.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geek.routine.AppContainer
import com.geek.routine.model.Alarm
import com.geek.routine.model.AlarmGroup
import com.geek.routine.ui.components.RepeatEditor
import com.geek.routine.util.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupEditState(
    val draft: AlarmGroup? = null,
    val isNew: Boolean = true,
    val alarms: List<Alarm> = emptyList(),
    val done: Boolean = false,
)

class GroupEditViewModel(private val c: AppContainer, private val groupId: Long) : ViewModel() {
    private val _state = MutableStateFlow(GroupEditState())
    val state: StateFlow<GroupEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val snap = c.repository.snapshot()
            val existing = snap.group(groupId)
            _state.value = GroupEditState(
                draft = existing ?: AlarmGroup(name = ""),
                isNew = existing == null,
                alarms = snap.alarms.filter { it.groupId == groupId },
            )
        }
    }

    fun update(transform: (AlarmGroup) -> AlarmGroup) = _state.update { s -> s.copy(draft = s.draft?.let(transform)) }

    fun save() {
        val d = _state.value.draft ?: return
        viewModelScope.launch {
            c.repository.saveGroup(d.copy(name = d.name.trim()))
            _state.update { it.copy(done = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditScreen(vm: GroupEditViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(state.done) { if (state.done) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
                title = { Text(if (state.isNew) "New group" else "Edit group") },
                actions = {
                    val d = state.draft
                    TextButton(onClick = vm::save, enabled = d != null && d.name.isNotBlank() && !d.repeat.isEmpty) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        val draft = state.draft
        if (draft == null) {
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
                value = draft.name,
                onValueChange = { v -> vm.update { it.copy(name = v.take(40)) } },
                label = { Text("Name") },
                placeholder = { Text("e.g. Office, WFH, Vacation") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            ListItem(
                modifier = Modifier.clickable { vm.update { it.copy(enabled = !it.enabled) } },
                headlineContent = { Text("Group is on") },
                supportingContent = {
                    Text("Turn off for groups you only use through overrides, like Vacation.")
                },
                trailingContent = { Switch(checked = draft.enabled, onCheckedChange = { v -> vm.update { it.copy(enabled = v) } }) },
            )
            Text(
                "Repeat",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            RepeatEditor(draft.repeat, onChange = { r -> vm.update { it.copy(repeat = r) } })
            Text(
                "All alarms in this group ring on these days. Use Overrides to change this for a date range.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            if (state.alarms.isNotEmpty()) {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Alarms in this group",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                state.alarms.forEach { a ->
                    ListItem(
                        headlineContent = { Text(TimeFormat.time(context, a.hour, a.minute)) },
                        supportingContent = { Text(a.label.ifBlank { "Alarm" } + if (!a.enabled) " · off" else "") },
                    )
                }
            }
        }
    }
}
