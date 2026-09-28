package dev.patheticgeek.alarmgroups.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Override target id meaning "alarms that aren't in any group". */
const val UNGROUPED_ID = 0L

@Serializable
@Entity(tableName = "groups")
data class AlarmGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    @Embedded(prefix = "repeat_") val repeat: RepeatRule = RepeatRule.weekly(RepeatRule.WEEKDAYS),
    /** Group is paused up to and including this date. */
    @Serializable(with = LocalDateSerializer::class)
    val pausedThrough: LocalDate? = null,
    /** Occurrences at or before this epoch-millis instant are skipped. */
    val skipUntil: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class TimeoutAction { SNOOZE, DISMISS }

@Serializable
@Entity(
    tableName = "alarms",
    foreignKeys = [
        ForeignKey(
            entity = AlarmGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("groupId")],
)
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long? = null,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
    /** Only used when the alarm isn't in a group; grouped alarms follow the group's repeat. */
    @Embedded(prefix = "repeat_") val repeat: RepeatRule = RepeatRule.Once,
    /** null = system default alarm sound. */
    val ringtoneUri: String? = null,
    val ringtoneTitle: String? = null,
    /** Alarm stream volume while ringing, percent 0..100. */
    val volume: Int = 80,
    val vibrate: Boolean = true,
    /** Seconds to ramp from quiet to [volume]; 0 = start at full volume. */
    val gradualSeconds: Int = 30,
    val snoozeMinutes: Int = 10,
    /** Minutes to ring before [timeoutAction] kicks in; 0 = ring until I act. */
    val ringMinutes: Int = 0,
    val timeoutAction: TimeoutAction = TimeoutAction.SNOOZE,
    /** Occurrences at or before this epoch-millis instant are skipped. */
    val skipUntil: Long? = null,
    val snoozedUntil: Long? = null,
    /** The scheduled occurrence (epoch millis) that most recently rang. */
    val lastFiredAt: Long? = null,
    /** What was last handed to AlarmManager; used to detect alarms missed while powered off. */
    val nextTriggerAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "overrides")
data class ScheduleOverride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate,
    @Serializable(with = LocalDateSerializer::class)
    val endDate: LocalDate,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun covers(date: LocalDate) = enabled && !date.isBefore(startDate) && !date.isAfter(endDate)
}

enum class OverrideAction { PAUSE, REPEAT }

@Serializable
@Entity(
    tableName = "override_effects",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleOverride::class,
            parentColumns = ["id"],
            childColumns = ["overrideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("overrideId"), Index("targetGroupId")],
)
data class OverrideEffect(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val overrideId: Long = 0,
    /** Group id, or [UNGROUPED_ID]. */
    val targetGroupId: Long,
    val action: OverrideAction,
    @Embedded(prefix = "repeat_") val repeat: RepeatRule = RepeatRule.Daily,
)

@Serializable
data class OverrideWithEffects(
    @Embedded val override: ScheduleOverride,
    @Relation(parentColumn = "id", entityColumn = "overrideId")
    val effects: List<OverrideEffect>,
)

/** A saved override without dates, e.g. "WFH: pause Office, turn on WFH", applied to any date range in two taps. */
@Serializable
@Entity(tableName = "override_presets")
data class OverridePreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "preset_effects",
    foreignKeys = [
        ForeignKey(
            entity = OverridePreset::class,
            parentColumns = ["id"],
            childColumns = ["presetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("presetId"), Index("targetGroupId")],
)
data class PresetEffect(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val presetId: Long = 0,
    /** Group id, or [UNGROUPED_ID]. */
    val targetGroupId: Long,
    val action: OverrideAction,
    @Embedded(prefix = "repeat_") val repeat: RepeatRule = RepeatRule.Daily,
) {
    fun toOverrideEffect() = OverrideEffect(targetGroupId = targetGroupId, action = action, repeat = repeat)
}

@Serializable
data class PresetWithEffects(
    @Embedded val preset: OverridePreset,
    @Relation(parentColumn = "id", entityColumn = "presetId")
    val effects: List<PresetEffect>,
)
