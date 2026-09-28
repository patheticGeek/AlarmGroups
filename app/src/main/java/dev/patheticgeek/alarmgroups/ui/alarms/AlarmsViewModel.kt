package dev.patheticgeek.alarmgroups.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.patheticgeek.alarmgroups.AppContainer
import dev.patheticgeek.alarmgroups.data.Snapshot
import dev.patheticgeek.alarmgroups.domain.ScheduleCalculator
import dev.patheticgeek.alarmgroups.model.Alarm
import dev.patheticgeek.alarmgroups.model.AlarmGroup
import dev.patheticgeek.alarmgroups.model.OverrideAction
import dev.patheticgeek.alarmgroups.model.OverrideWithEffects
import dev.patheticgeek.alarmgroups.model.ScheduleOverride
import dev.patheticgeek.alarmgroups.model.UNGROUPED_ID
import dev.patheticgeek.alarmgroups.ui.components.describe
import dev.patheticgeek.alarmgroups.util.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AlarmItem(
    val alarm: Alarm,
    val next: Instant?,
    /** The occurrence being skipped, if a skip is pending. */
    val skipping: Instant?,
    val snoozedUntil: Instant?,
)

data class GroupSection(
    val group: AlarmGroup?,
    val status: String,
    /** Something noteworthy: an active or upcoming override, a pause, a skip. */
    val notice: String?,
    val isSkipping: Boolean,
    val alarms: List<AlarmItem>,
)

data class NextAlarm(val alarm: Alarm, val at: Instant)

data class AlarmsUiState(
    val loading: Boolean = true,
    val sections: List<GroupSection> = emptyList(),
    val next: NextAlarm? = null,
    val showStarterGroups: Boolean = false,
    val now: Instant = Instant.now(),
)

class AlarmsViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository

    private val ticker = flow {
        while (true) {
            emit(Instant.now())
            delay(30_000)
        }
    }

    val state: StateFlow<AlarmsUiState> =
        combine(repo.observeSnapshot(), c.settings.settings, ticker) { snap, settings, now ->
            build(snap, now, showStarter = !settings.starterGroupsOffered && snap.groups.isEmpty())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmsUiState())

    private fun build(snap: Snapshot, now: Instant, showStarter: Boolean): AlarmsUiState {
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()

        fun item(a: Alarm): AlarmItem {
            val g = snap.groupOf(a)
            val next = ScheduleCalculator.nextTrigger(a, g, snap.overrides, now, zone)
            val unskipped = ScheduleCalculator.nextTrigger(a, g, snap.overrides, now, zone, honorSkip = false)
            val skipping = unskipped?.takeIf { next == null || it < next }
            val snooze = a.snoozedUntil?.takeIf { it > now.toEpochMilli() }?.let(Instant::ofEpochMilli)
            return AlarmItem(a, next, skipping, snooze)
        }

        val sections = snap.groups.map { g ->
            GroupSection(
                group = g,
                status = groupStatus(g, snap.overrides, today),
                notice = groupNotice(g.id, snap.overrides, today),
                isSkipping = (g.skipUntil ?: 0) > now.toEpochMilli(),
                alarms = snap.alarms.filter { it.groupId == g.id }.map(::item),
            )
        }
        val loose = snap.alarms.filter { it.groupId == null || snap.groupOf(it) == null }
        val ungrouped = GroupSection(
            group = null,
            status = if (loose.isEmpty()) "Alarms with their own schedule" else "${loose.size} alarm${if (loose.size == 1) "" else "s"}",
            notice = groupNotice(UNGROUPED_ID, snap.overrides, today),
            isSkipping = false,
            alarms = loose.map(::item),
        )
        val all = sections.flatMap { it.alarms } + ungrouped.alarms
        val next = all.filter { it.next != null }.minByOrNull { it.next!! }?.let { NextAlarm(it.alarm, it.next!!) }
        return AlarmsUiState(
            loading = false,
            sections = sections + ungrouped,
            next = next,
            showStarterGroups = showStarter,
            now = now,
        )
    }

    private fun activeOverride(targetId: Long, overrides: List<OverrideWithEffects>, date: LocalDate) =
        overrides.filter { it.override.covers(date) }
            .mapNotNull { o -> o.effects.firstOrNull { it.targetGroupId == targetId }?.let { o.override to it } }
            .maxWithOrNull(compareBy<Pair<ScheduleOverride, *>>({ it.first.createdAt }, { it.first.id }))

    private fun groupStatus(g: AlarmGroup, overrides: List<OverrideWithEffects>, today: LocalDate): String {
        activeOverride(g.id, overrides, today)?.let { (o, e) ->
            return when (e.action) {
                OverrideAction.PAUSE -> "Paused by ${o.name} through ${TimeFormat.day(o.endDate, today)}"
                OverrideAction.REPEAT -> "${e.repeat.describe()} (${o.name}, through ${TimeFormat.day(o.endDate, today)})"
            }
        }
        val paused = g.pausedThrough
        return when {
            !g.enabled -> "Off"
            paused != null && !paused.isBefore(today) -> "Paused through ${TimeFormat.day(paused, today)}"
            else -> g.repeat.describe()
        }
    }

    /** The next override that will change this group, if any starts in the future. */
    private fun groupNotice(targetId: Long, overrides: List<OverrideWithEffects>, today: LocalDate): String? {
        val upcoming = overrides
            .filter { o -> o.override.enabled && o.override.startDate.isAfter(today) && o.effects.any { it.targetGroupId == targetId } }
            .minByOrNull { it.override.startDate }
            ?: return null
        val effect = upcoming.effects.first { it.targetGroupId == targetId }
        val what = if (effect.action == OverrideAction.PAUSE) "pauses" else "switches to ${effect.repeat.describe().lowercase()}"
        return "${upcoming.override.name} $what from ${TimeFormat.day(upcoming.override.startDate, today)}"
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun setAlarmEnabled(id: Long, enabled: Boolean) = launch { repo.setAlarmEnabled(id, enabled) }
    fun skipNext(id: Long) = launch { repo.skipNext(id) }
    fun clearSkip(id: Long) = launch { repo.clearSkip(id) }
    fun deleteAlarm(alarm: Alarm) = launch { repo.deleteAlarm(alarm.id) }
    fun restoreAlarm(alarm: Alarm) = launch { repo.restoreAlarm(alarm) }

    fun setGroupEnabled(id: Long, enabled: Boolean) = launch { repo.setGroupEnabled(id, enabled) }
    fun pauseGroupThrough(id: Long, date: LocalDate?) = launch { repo.pauseGroupThrough(id, date) }
    fun skipGroup(id: Long) = launch { repo.skipNextGroupDay(id) }
    fun clearGroupSkip(id: Long) = launch { repo.clearGroupSkip(id) }
    fun deleteGroup(id: Long, deleteAlarms: Boolean) = launch { repo.deleteGroup(id, deleteAlarms) }

    fun createStarterGroups() = launch {
        repo.createStarterGroups()
        c.settings.update { it.copy(starterGroupsOffered = true) }
    }

    fun dismissStarterGroups() = launch { c.settings.update { it.copy(starterGroupsOffered = true) } }
}
