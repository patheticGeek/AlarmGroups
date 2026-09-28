package dev.patheticgeek.alarmgroups.ui.overrides

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.patheticgeek.alarmgroups.AppContainer
import dev.patheticgeek.alarmgroups.model.AlarmGroup
import dev.patheticgeek.alarmgroups.model.OverrideAction
import dev.patheticgeek.alarmgroups.model.OverrideEffect
import dev.patheticgeek.alarmgroups.model.OverrideWithEffects
import dev.patheticgeek.alarmgroups.model.RepeatRule
import dev.patheticgeek.alarmgroups.model.ScheduleOverride
import dev.patheticgeek.alarmgroups.model.UNGROUPED_ID
import dev.patheticgeek.alarmgroups.ui.components.describeEffects
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class OverridePhase { ACTIVE, UPCOMING, ENDED }

data class OverrideItem(val data: OverrideWithEffects, val summary: String, val phase: OverridePhase)

data class OverridesUiState(val loading: Boolean = true, val items: List<OverrideItem> = emptyList())

class OverridesViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<OverridesUiState> = c.repository.observeSnapshot().map { snap ->
        val today = LocalDate.now()
        val names = snap.groups.associate { it.id to it.name } + (UNGROUPED_ID to "Ungrouped")
        OverridesUiState(
            loading = false,
            items = snap.overrides.map { o ->
                val phase = when {
                    o.override.endDate.isBefore(today) -> OverridePhase.ENDED
                    o.override.startDate.isAfter(today) -> OverridePhase.UPCOMING
                    else -> OverridePhase.ACTIVE
                }
                OverrideItem(o, o.describeEffects { names[it] ?: "?" }, phase)
            }.sortedWith(compareBy({ it.phase.ordinal }, { it.data.override.startDate })),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverridesUiState())

    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch { c.repository.setOverrideEnabled(id, enabled) }
    fun delete(id: Long) = viewModelScope.launch { c.repository.deleteOverride(id) }
    fun deleteEnded() = viewModelScope.launch {
        state.value.items.filter { it.phase == OverridePhase.ENDED }.forEach { c.repository.deleteOverride(it.data.override.id) }
    }
}

/** What an override does to one target (a group or the ungrouped alarms). */
sealed interface EffectChoice {
    data object None : EffectChoice
    data object Pause : EffectChoice
    data class Repeat(val rule: RepeatRule) : EffectChoice
}

data class OverrideEditState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    val name: String = "",
    val start: LocalDate = LocalDate.now(),
    val end: LocalDate = LocalDate.now().plusDays(6),
    val groups: List<AlarmGroup> = emptyList(),
    val effects: Map<Long, EffectChoice> = emptyMap(),
    val done: Boolean = false,
) {
    val valid: Boolean
        get() = name.isNotBlank() && !end.isBefore(start) &&
            effects.values.any { it != EffectChoice.None } &&
            effects.values.none { it is EffectChoice.Repeat && it.rule.isEmpty }
}

class OverrideEditViewModel(private val c: AppContainer, private val overrideId: Long) : ViewModel() {
    private val _state = MutableStateFlow(OverrideEditState())
    val state: StateFlow<OverrideEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val snap = c.repository.snapshot()
            val existing = snap.overrides.firstOrNull { it.override.id == overrideId }
            _state.value = if (existing == null) {
                OverrideEditState(loaded = true, groups = snap.groups)
            } else {
                val o = existing.override
                OverrideEditState(
                    loaded = true,
                    isNew = false,
                    id = o.id,
                    createdAt = o.createdAt,
                    enabled = o.enabled,
                    name = o.name,
                    start = o.startDate,
                    end = o.endDate,
                    groups = snap.groups,
                    effects = existing.effects.associate {
                        it.targetGroupId to when (it.action) {
                            OverrideAction.PAUSE -> EffectChoice.Pause
                            OverrideAction.REPEAT -> EffectChoice.Repeat(it.repeat)
                        }
                    },
                )
            }
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name.take(40)) }
    fun setRange(start: LocalDate, end: LocalDate) = _state.update { it.copy(start = start, end = end) }
    fun setEffect(targetId: Long, choice: EffectChoice) = _state.update { it.copy(effects = it.effects + (targetId to choice)) }

    /** Template: pause every group (and ungrouped alarms). */
    fun pauseEverything() = _state.update { s ->
        s.copy(effects = (s.groups.map { it.id } + UNGROUPED_ID).associateWith { EffectChoice.Pause })
    }

    fun save() {
        val s = _state.value
        if (!s.valid) return
        viewModelScope.launch {
            val o = ScheduleOverride(
                id = s.id,
                name = s.name.trim(),
                startDate = s.start,
                endDate = s.end,
                enabled = s.enabled,
                // Editing counts as the newest decision, so it wins any overlap.
                createdAt = System.currentTimeMillis(),
            )
            val effects = s.effects.mapNotNull { (target, choice) ->
                when (choice) {
                    EffectChoice.None -> null
                    EffectChoice.Pause -> OverrideEffect(targetGroupId = target, action = OverrideAction.PAUSE)
                    is EffectChoice.Repeat -> OverrideEffect(targetGroupId = target, action = OverrideAction.REPEAT, repeat = choice.rule)
                }
            }
            c.repository.saveOverride(o, effects)
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() = viewModelScope.launch {
        if (overrideId != 0L) c.repository.deleteOverride(overrideId)
        _state.update { it.copy(done = true) }
    }
}
