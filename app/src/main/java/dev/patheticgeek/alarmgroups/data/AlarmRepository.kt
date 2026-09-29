package dev.patheticgeek.alarmgroups.data

import androidx.room.withTransaction
import dev.patheticgeek.alarmgroups.domain.ScheduleCalculator
import dev.patheticgeek.alarmgroups.model.Alarm
import dev.patheticgeek.alarmgroups.model.AlarmGroup
import dev.patheticgeek.alarmgroups.model.OverrideEffect
import dev.patheticgeek.alarmgroups.model.OverridePreset
import dev.patheticgeek.alarmgroups.model.OverrideWithEffects
import dev.patheticgeek.alarmgroups.model.PresetEffect
import dev.patheticgeek.alarmgroups.model.PresetWithEffects
import dev.patheticgeek.alarmgroups.model.RepeatRule
import dev.patheticgeek.alarmgroups.model.RepeatType
import dev.patheticgeek.alarmgroups.model.ScheduleOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Everything the scheduler needs, read in one go. */
data class Snapshot(
    val alarms: List<Alarm>,
    val groups: List<AlarmGroup>,
    val overrides: List<OverrideWithEffects>,
) {
    private val groupsById = groups.associateBy { it.id }
    fun groupOf(alarm: Alarm): AlarmGroup? = alarm.groupId?.let(groupsById::get)
    fun group(id: Long): AlarmGroup? = groupsById[id]
}

/**
 * Single entry point for changing alarm data. Every mutation calls [onChanged] afterwards so
 * AlarmManager is always brought back in sync with the database.
 */
class AlarmRepository(
    private val db: AlarmDatabase,
    // A provider, not a Clock: the system zone must be re-read after a timezone change.
    private val clock: () -> Clock = { Clock.systemDefaultZone() },
    private val onChanged: suspend () -> Unit = {},
    /** Called with ids of alarms that no longer exist, so their AlarmManager entries can be cancelled. */
    private val onRemoved: suspend (List<Long>) -> Unit = {},
) {
    private val alarms = db.alarmDao()
    private val groups = db.groupDao()
    private val overrides = db.overrideDao()
    private val presets = db.presetDao()

    fun observeSnapshot(): Flow<Snapshot> =
        combine(alarms.observeAll(), groups.observeAll(), overrides.observeAll(), ::Snapshot)

    fun observeGroups(): Flow<List<AlarmGroup>> = groups.observeAll()
    fun observeAlarm(id: Long): Flow<Alarm?> = alarms.observe(id)

    suspend fun snapshot(): Snapshot = db.withTransaction {
        Snapshot(alarms.getAll(), groups.getAll(), overrides.getAll())
    }

    suspend fun alarm(id: Long): Alarm? = alarms.get(id)
    suspend fun group(id: Long): AlarmGroup? = groups.get(id)
    suspend fun override(id: Long): OverrideWithEffects? = overrides.get(id)

    // ---- Alarms ----

    /** Inserts or updates. Editing an alarm drops any pending snooze or skip, which would refer to the old time. */
    suspend fun saveAlarm(alarm: Alarm): Long {
        val cleaned = alarm.copy(snoozedUntil = null, skipUntil = null)
        val id = if (alarm.id == 0L) alarms.insert(cleaned) else cleaned.id.also { alarms.update(cleaned) }
        onChanged()
        return id
    }

    suspend fun setAlarmEnabled(id: Long, enabled: Boolean) {
        val a = alarms.get(id) ?: return
        alarms.update(a.copy(enabled = enabled, snoozedUntil = null, skipUntil = null))
        onChanged()
    }

    suspend fun deleteAlarm(id: Long) {
        val a = alarms.get(id) ?: return
        alarms.delete(a)
        onRemoved(listOf(id))
        onChanged()
    }

    /** Re-inserts a deleted alarm with its original id (for "undo"). */
    suspend fun restoreAlarm(alarm: Alarm) {
        alarms.insertAll(listOf(alarm))
        onChanged()
    }

    /** Skips the next scheduled ring of the alarm. */
    suspend fun skipNext(id: Long) {
        val snap = snapshot()
        val a = snap.alarms.firstOrNull { it.id == id } ?: return
        val occ = ScheduleCalculator.nextOccurrence(
            a.copy(snoozedUntil = null), snap.groupOf(a), snap.overrides, clock().instant(), clock().zone,
        ) ?: return
        // Skipping a one-off would just push it to the next day; switch it off instead.
        val oneOff = occ.plan?.repeat?.type == RepeatType.ONCE
        alarms.update(
            if (oneOff) {
                a.copy(enabled = false, snoozedUntil = null, skipUntil = null)
            } else {
                a.copy(skipUntil = occ.instant.toEpochMilli(), snoozedUntil = null)
            },
        )
        onChanged()
    }

    suspend fun clearSkip(id: Long) {
        val a = alarms.get(id) ?: return
        alarms.update(a.copy(skipUntil = null))
        onChanged()
    }

    // ---- Ringing ----

    /**
     * Called the moment an alarm starts ringing. Records the occurrence and arms a safety snooze so
     * that if the ringing service is killed before the user acts, the alarm comes back.
     */
    suspend fun onFired(id: Long, occurrence: Long, safetySnoozeUntil: Long) {
        val a = alarms.get(id) ?: return
        alarms.update(a.copy(lastFiredAt = occurrence, snoozedUntil = safetySnoozeUntil))
        onChanged()
    }

    suspend fun snooze(id: Long, until: Long) {
        val a = alarms.get(id) ?: return
        alarms.update(a.copy(snoozedUntil = until))
        onChanged()
    }

    /** Stops the alarm. One-off alarms switch themselves off. */
    suspend fun dismiss(id: Long) {
        val snap = snapshot()
        val a = snap.alarms.firstOrNull { it.id == id } ?: return
        val fired = a.lastFiredAt
        val oneOff = fired != null && ScheduleCalculator.isOneOff(
            Instant.ofEpochMilli(fired).atZone(clock().zone).toLocalDate(), a, snap.groupOf(a), snap.overrides,
        )
        alarms.update(a.copy(snoozedUntil = null, enabled = a.enabled && !oneOff))
        onChanged()
    }

    /** Records a missed occurrence (device was off) so it isn't reported again. */
    suspend fun markMissed(id: Long, occurrence: Long) {
        val snap = snapshot()
        val a = snap.alarms.firstOrNull { it.id == id } ?: return
        val oneOff = ScheduleCalculator.isOneOff(
            Instant.ofEpochMilli(occurrence).atZone(clock().zone).toLocalDate(), a, snap.groupOf(a), snap.overrides,
        )
        alarms.update(a.copy(lastFiredAt = occurrence, snoozedUntil = null, enabled = a.enabled && !oneOff))
        onChanged()
    }

    /** Stores what was handed to AlarmManager. Deliberately does not call [onChanged]. */
    suspend fun setNextTrigger(id: Long, at: Long?) = alarms.setNextTrigger(id, at)

    // ---- Groups ----

    suspend fun saveGroup(group: AlarmGroup): Long {
        val id = if (group.id == 0L) {
            groups.insert(group.copy(sortOrder = groups.maxSortOrder() + 1))
        } else {
            group.id.also { groups.update(group) }
        }
        onChanged()
        return id
    }

    suspend fun setGroupEnabled(id: Long, enabled: Boolean) {
        val g = groups.get(id) ?: return
        groups.update(g.copy(enabled = enabled, pausedThrough = null, skipUntil = null))
        onChanged()
    }

    /** Pauses the group up to and including [through]; null resumes it. */
    suspend fun pauseGroupThrough(id: Long, through: LocalDate?) {
        val g = groups.get(id) ?: return
        groups.update(g.copy(pausedThrough = through))
        onChanged()
    }

    /** Skips every alarm in the group on the next day the group would ring. */
    suspend fun skipNextGroupDay(id: Long) {
        val snap = snapshot()
        val g = snap.group(id) ?: return
        val now = clock().instant()
        val next = snap.alarms.filter { it.groupId == id }
            .mapNotNull {
                ScheduleCalculator.nextTrigger(it.copy(snoozedUntil = null), g, snap.overrides, now, clock().zone)
            }
            .minOrNull() ?: return
        skipGroupThrough(id, next.atZone(clock().zone).toLocalDate())
    }

    /** Skips every alarm in the group from now through the end of [date]. Never shortens an existing skip. */
    suspend fun skipGroupThrough(id: Long, date: LocalDate) {
        val g = groups.get(id) ?: return
        val endOfDay = date.plusDays(1).atStartOfDay(clock().zone).toInstant().toEpochMilli() - 1
        groups.update(g.copy(skipUntil = maxOf(endOfDay, g.skipUntil ?: Long.MIN_VALUE)))
        onChanged()
    }

    suspend fun clearGroupSkip(id: Long) {
        val g = groups.get(id) ?: return
        groups.update(g.copy(skipUntil = null))
        onChanged()
    }

    suspend fun reorderGroups(orderedIds: List<Long>) {
        val byId = groups.getAll().associateBy { it.id }
        groups.updateAll(orderedIds.mapIndexedNotNull { i, id -> byId[id]?.copy(sortOrder = i) })
        onChanged()
    }

    /**
     * Deletes a group. Its alarms are either deleted too or kept as ungrouped alarms, in which case
     * they take over the group's repeat rule so they keep ringing on the same days.
     */
    suspend fun deleteGroup(id: Long, deleteAlarms: Boolean) {
        val removed = if (deleteAlarms) alarms.inGroup(id).map { it.id } else emptyList()
        db.withTransaction {
            val g = groups.get(id) ?: return@withTransaction
            if (deleteAlarms) {
                alarms.deleteInGroup(id)
            } else {
                alarms.inGroup(id).forEach {
                    alarms.update(it.copy(groupId = null, repeat = g.repeat, enabled = it.enabled && g.enabled))
                }
            }
            overrides.deleteEffectsForGroup(id)
            overrides.deleteEmpty()
            presets.deleteEffectsForGroup(id)
            presets.deleteEmpty()
            groups.delete(g)
        }
        onRemoved(removed)
        onChanged()
    }

    /** Creates the starter groups the app suggests on first run. */
    suspend fun createStarterGroups() {
        val base = groups.maxSortOrder()
        listOf(
            AlarmGroup(name = "Office", repeat = RepeatRule.weekly(RepeatRule.WEEKDAYS)),
            AlarmGroup(name = "WFH", repeat = RepeatRule.weekly(RepeatRule.WEEKDAYS), enabled = false),
            AlarmGroup(name = "No work", repeat = RepeatRule.weekly(RepeatRule.WEEKENDS)),
            AlarmGroup(name = "Vacation", repeat = RepeatRule.Daily, enabled = false),
        ).forEachIndexed { i, g -> groups.insert(g.copy(sortOrder = base + i + 1)) }
        onChanged()
    }

    // ---- Overrides ----

    suspend fun saveOverride(o: ScheduleOverride, effects: List<OverrideEffect>): Long {
        val id = db.withTransaction {
            val id = if (o.id == 0L) overrides.insert(o) else o.id.also { overrides.update(o) }
            overrides.deleteEffects(id)
            overrides.insertEffects(effects.map { it.copy(id = 0, overrideId = id) })
            id
        }
        onChanged()
        return id
    }

    suspend fun setOverrideEnabled(id: Long, enabled: Boolean) {
        val o = overrides.get(id) ?: return
        overrides.update(o.override.copy(enabled = enabled))
        onChanged()
    }

    suspend fun deleteOverride(id: Long) {
        overrides.delete(id)
        onChanged()
    }

    // ---- Presets ----

    fun observePresets(): Flow<List<PresetWithEffects>> = presets.observeAll()
    suspend fun presets(): List<PresetWithEffects> = presets.getAll()
    suspend fun preset(id: Long): PresetWithEffects? = presets.get(id)

    suspend fun savePreset(p: OverridePreset, effects: List<PresetEffect>): Long = db.withTransaction {
        val id = if (p.id == 0L) presets.insert(p) else p.id.also { presets.update(p) }
        presets.deleteEffects(id)
        presets.insertEffects(effects.map { it.copy(id = 0, presetId = id) })
        id
    }

    suspend fun deletePreset(id: Long) = presets.delete(id)

    /** Creates an override from the preset for [start]..[end]. Returns the new override's id. */
    suspend fun applyPreset(id: Long, start: LocalDate, end: LocalDate): Long? {
        val p = presets.get(id) ?: return null
        return saveOverride(
            ScheduleOverride(name = p.preset.name, startDate = start, endDate = maxOf(start, end)),
            p.effects.map(PresetEffect::toOverrideEffect),
        )
    }

    // ---- Backup ----

    /** Replaces everything with [data] (ids preserved so references stay valid). */
    suspend fun replaceAll(data: Snapshot, presetData: List<PresetWithEffects> = emptyList()) {
        val before = alarms.getAll().map { it.id }
        db.withTransaction {
            overrides.deleteAll()
            presets.deleteAll()
            alarms.deleteAll()
            groups.deleteAll()
            groups.insertAll(data.groups)
            alarms.insertAll(data.alarms.map { it.copy(snoozedUntil = null, nextTriggerAt = null) })
            data.overrides.forEach { o ->
                val id = overrides.insert(o.override)
                overrides.insertEffects(o.effects.map { it.copy(id = 0, overrideId = id) })
            }
            presetData.forEach { p ->
                val id = presets.insert(p.preset.copy(id = 0))
                presets.insertEffects(p.effects.map { it.copy(id = 0, presetId = id) })
            }
        }
        onRemoved(before - data.alarms.map { it.id }.toSet())
        onChanged()
    }
}
