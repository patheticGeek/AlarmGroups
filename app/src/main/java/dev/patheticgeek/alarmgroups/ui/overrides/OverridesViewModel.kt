package dev.patheticgeek.alarmgroups.ui.overrides

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.patheticgeek.alarmgroups.AppContainer
import dev.patheticgeek.alarmgroups.model.AlarmGroup
import dev.patheticgeek.alarmgroups.model.OverrideAction
import dev.patheticgeek.alarmgroups.model.OverrideEffect
import dev.patheticgeek.alarmgroups.model.OverridePreset
import dev.patheticgeek.alarmgroups.model.OverrideWithEffects
import dev.patheticgeek.alarmgroups.model.PresetEffect
import dev.patheticgeek.alarmgroups.model.PresetWithEffects
import dev.patheticgeek.alarmgroups.model.RepeatRule
import dev.patheticgeek.alarmgroups.model.ScheduleOverride
import dev.patheticgeek.alarmgroups.model.UNGROUPED_ID
import dev.patheticgeek.alarmgroups.ui.components.describe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class OverridePhase { ACTIVE, UPCOMING, ENDED }

data class OverrideItem(val data: OverrideWithEffects, val summary: String, val phase: OverridePhase)
data class PresetItem(val data: PresetWithEffects, val summary: String) {
    val id: Long get() = data.preset.id
    val name: String get() = data.preset.name
}

data class OverridesUiState(
    val loading: Boolean = true,
    val items: List<OverrideItem> = emptyList(),
    val presets: List<PresetItem> = emptyList(),
)

/** "Office: paused · WFH: every day". */
fun summarizeEffects(effects: List<Pair<Long, Pair<OverrideAction, RepeatRule>>>, groupName: (Long) -> String): String =
    effects.joinToString(" · ") { (target, e) ->
        val what = when (e.first) {
            OverrideAction.PAUSE -> "paused"
            OverrideAction.REPEAT -> e.second.describe().replaceFirstChar { it.lowercase() }
        }
        "${groupName(target)}: $what"
    }

class OverridesViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<OverridesUiState> =
        combine(c.repository.observeSnapshot(), c.repository.observePresets()) { snap, presets ->
            val today = LocalDate.now()
            val names = snap.groups.associate { it.id to it.name } + (UNGROUPED_ID to "Ungrouped")
            val name = { id: Long -> names[id] ?: "?" }
            OverridesUiState(
                loading = false,
                items = snap.overrides.map { o ->
                    val phase = when {
                        o.override.endDate.isBefore(today) -> OverridePhase.ENDED
                        o.override.startDate.isAfter(today) -> OverridePhase.UPCOMING
                        else -> OverridePhase.ACTIVE
                    }
                    OverrideItem(o, summarizeEffects(o.effects.map { it.targetGroupId to (it.action to it.repeat) }, name), phase)
                }.sortedWith(compareBy({ it.phase.ordinal }, { it.data.override.startDate })),
                presets = presets.map { p ->
                    PresetItem(p, summarizeEffects(p.effects.map { it.targetGroupId to (it.action to it.repeat) }, name))
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverridesUiState())

    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch { c.repository.setOverrideEnabled(id, enabled) }
    fun delete(id: Long) = viewModelScope.launch { c.repository.deleteOverride(id) }
    fun deleteEnded() = viewModelScope.launch {
        state.value.items.filter { it.phase == OverridePhase.ENDED }.forEach { c.repository.deleteOverride(it.data.override.id) }
    }

    /** Applies a preset; [onApplied] gets the new override's id (for undo). */
    fun applyPreset(id: Long, start: LocalDate, end: LocalDate, onApplied: (Long?) -> Unit) = viewModelScope.launch {
        onApplied(c.repository.applyPreset(id, start, end))
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
    /** Editing a preset (no dates) rather than a dated override. */
    val isPreset: Boolean = false,
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
    /** Set once "Save as preset" succeeded, to show a confirmation. */
    val savedAsPreset: Boolean = false,
) {
    val hasValidEffects: Boolean
        get() = name.isNotBlank() &&
            effects.values.any { it != EffectChoice.None } &&
            effects.values.none { it is EffectChoice.Repeat && it.rule.isEmpty }

    val valid: Boolean get() = hasValidEffects && (isPreset || !end.isBefore(start))

    fun effectList(): List<Triple<Long, OverrideAction, RepeatRule>> = effects.mapNotNull { (target, choice) ->
        when (choice) {
            EffectChoice.None -> null
            EffectChoice.Pause -> Triple(target, OverrideAction.PAUSE, RepeatRule.Daily)
            is EffectChoice.Repeat -> Triple(target, OverrideAction.REPEAT, choice.rule)
        }
    }
}

private fun choiceOf(action: OverrideAction, repeat: RepeatRule): EffectChoice = when (action) {
    OverrideAction.PAUSE -> EffectChoice.Pause
    OverrideAction.REPEAT -> EffectChoice.Repeat(repeat)
}

class OverrideEditViewModel(
    private val c: AppContainer,
    private val itemId: Long,
    private val isPreset: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(OverrideEditState(isPreset = isPreset))
    val state: StateFlow<OverrideEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val snap = c.repository.snapshot()
            val blank = OverrideEditState(loaded = true, isPreset = isPreset, groups = snap.groups)
            _state.value = if (isPreset) {
                val p = c.repository.preset(itemId)
                if (p == null) {
                    blank
                } else {
                    blank.copy(
                        isNew = false,
                        id = p.preset.id,
                        createdAt = p.preset.createdAt,
                        name = p.preset.name,
                        effects = p.effects.associate { it.targetGroupId to choiceOf(it.action, it.repeat) },
                    )
                }
            } else {
                val existing = snap.overrides.firstOrNull { it.override.id == itemId }
                if (existing == null) {
                    blank
                } else {
                    val o = existing.override
                    blank.copy(
                        isNew = false,
                        id = o.id,
                        createdAt = o.createdAt,
                        enabled = o.enabled,
                        name = o.name,
                        start = o.startDate,
                        end = o.endDate,
                        effects = existing.effects.associate { it.targetGroupId to choiceOf(it.action, it.repeat) },
                    )
                }
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
            if (s.isPreset) {
                c.repository.savePreset(
                    OverridePreset(id = s.id, name = s.name.trim(), createdAt = s.createdAt),
                    s.effectList().map { (t, a, r) -> PresetEffect(targetGroupId = t, action = a, repeat = r) },
                )
            } else {
                c.repository.saveOverride(
                    ScheduleOverride(
                        id = s.id,
                        name = s.name.trim(),
                        startDate = s.start,
                        endDate = s.end,
                        enabled = s.enabled,
                        // Editing counts as the newest decision, so it wins any overlap.
                        createdAt = System.currentTimeMillis(),
                    ),
                    s.effectList().map { (t, a, r) -> OverrideEffect(targetGroupId = t, action = a, repeat = r) },
                )
            }
            _state.update { it.copy(done = true) }
        }
    }

    /** Saves this override's group changes as a reusable preset (without its dates). */
    fun saveAsPreset() {
        val s = _state.value
        if (!s.hasValidEffects) return
        viewModelScope.launch {
            c.repository.savePreset(
                OverridePreset(name = s.name.trim()),
                s.effectList().map { (t, a, r) -> PresetEffect(targetGroupId = t, action = a, repeat = r) },
            )
            _state.update { it.copy(savedAsPreset = true) }
        }
    }

    fun presetSavedShown() = _state.update { it.copy(savedAsPreset = false) }

    fun delete() = viewModelScope.launch {
        if (itemId != 0L) {
            if (isPreset) c.repository.deletePreset(itemId) else c.repository.deleteOverride(itemId)
        }
        _state.update { it.copy(done = true) }
    }
}
