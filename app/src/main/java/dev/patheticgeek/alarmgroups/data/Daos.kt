package dev.patheticgeek.alarmgroups.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.patheticgeek.alarmgroups.model.Alarm
import dev.patheticgeek.alarmgroups.model.AlarmGroup
import dev.patheticgeek.alarmgroups.model.OverrideEffect
import dev.patheticgeek.alarmgroups.model.OverrideWithEffects
import dev.patheticgeek.alarmgroups.model.ScheduleOverride
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    fun observeAll(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    suspend fun getAll(): List<Alarm>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE id = :id")
    fun observe(id: Long): Flow<Alarm?>

    @Query("SELECT * FROM alarms WHERE groupId = :groupId")
    suspend fun inGroup(groupId: Long): List<Alarm>

    @Insert
    suspend fun insert(alarm: Alarm): Long

    @Insert
    suspend fun insertAll(alarms: List<Alarm>)

    @Update
    suspend fun update(alarm: Alarm)

    @Delete
    suspend fun delete(alarm: Alarm)

    @Query("DELETE FROM alarms WHERE groupId = :groupId")
    suspend fun deleteInGroup(groupId: Long)

    @Query("UPDATE alarms SET nextTriggerAt = :at WHERE id = :id")
    suspend fun setNextTrigger(id: Long, at: Long?)

    @Query("DELETE FROM alarms")
    suspend fun deleteAll()
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<AlarmGroup>>

    @Query("SELECT * FROM groups ORDER BY sortOrder, id")
    suspend fun getAll(): List<AlarmGroup>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun get(id: Long): AlarmGroup?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM groups")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(group: AlarmGroup): Long

    @Insert
    suspend fun insertAll(groups: List<AlarmGroup>)

    @Update
    suspend fun update(group: AlarmGroup)

    @Update
    suspend fun updateAll(groups: List<AlarmGroup>)

    @Delete
    suspend fun delete(group: AlarmGroup)

    @Query("DELETE FROM groups")
    suspend fun deleteAll()
}

@Dao
interface OverrideDao {
    @Transaction
    @Query("SELECT * FROM overrides ORDER BY startDate, id")
    fun observeAll(): Flow<List<OverrideWithEffects>>

    @Transaction
    @Query("SELECT * FROM overrides ORDER BY startDate, id")
    suspend fun getAll(): List<OverrideWithEffects>

    @Transaction
    @Query("SELECT * FROM overrides WHERE id = :id")
    suspend fun get(id: Long): OverrideWithEffects?

    @Insert
    suspend fun insert(o: ScheduleOverride): Long

    @Update
    suspend fun update(o: ScheduleOverride)

    @Query("DELETE FROM overrides WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertEffects(effects: List<OverrideEffect>)

    @Query("DELETE FROM override_effects WHERE overrideId = :overrideId")
    suspend fun deleteEffects(overrideId: Long)

    @Query("DELETE FROM override_effects WHERE targetGroupId = :groupId")
    suspend fun deleteEffectsForGroup(groupId: Long)

    /** Removes overrides left with nothing to do, e.g. after the only group they touched was deleted. */
    @Query("DELETE FROM overrides WHERE id NOT IN (SELECT DISTINCT overrideId FROM override_effects)")
    suspend fun deleteEmpty()

    @Query("DELETE FROM overrides")
    suspend fun deleteAll()
}
