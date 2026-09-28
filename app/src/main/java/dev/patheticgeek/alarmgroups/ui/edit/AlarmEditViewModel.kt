package dev.patheticgeek.alarmgroups.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.patheticgeek.alarmgroups.AppContainer
import dev.patheticgeek.alarmgroups.data.Snapshot
import dev.patheticgeek.alarmgroups.domain.ScheduleCalculator
import dev.patheticgeek.alarmgroups.model.Alarm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

data class AlarmEditState(
    val draft: Alarm? = null,
    val isNew: Boolean = true,
    val snapshot: Snapshot? = null,
    val done: Boolean = false,
) {
    /** When the draft would ring next if saved now. */
    val preview: Instant?
        get() {
            val d = draft ?: return null
            val s = snapshot ?: return null
            return ScheduleCalculator.nextTrigger(
                d.copy(snoozedUntil = null, skipUntil = null),
                d.groupId?.let(s::group),
                s.overrides,
                Instant.now(),
                ZoneId.systemDefault(),
            )
        }
}

class AlarmEditViewModel(
    private val c: AppContainer,
    private val alarmId: Long,
    private val presetGroupId: Long?,
) : ViewModel() {
    private val _state = MutableStateFlow(AlarmEditState())
    val state: StateFlow<AlarmEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val snap = c.repository.snapshot()
            val existing = if (alarmId != 0L) c.repository.alarm(alarmId) else null
            val draft = existing ?: c.settings.current().newAlarm(7, 0, presetGroupId?.takeIf { snap.group(it) != null })
            _state.value = AlarmEditState(draft = draft, isNew = existing == null, snapshot = snap)
        }
    }

    fun update(transform: (Alarm) -> Alarm) = _state.update { s -> s.copy(draft = s.draft?.let(transform)) }

    fun save() {
        val d = _state.value.draft ?: return
        viewModelScope.launch {
            c.repository.saveAlarm(d.copy(enabled = true))
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() {
        if (alarmId == 0L) {
            _state.update { it.copy(done = true) }
            return
        }
        viewModelScope.launch {
            c.repository.deleteAlarm(alarmId)
            _state.update { it.copy(done = true) }
        }
    }
}
