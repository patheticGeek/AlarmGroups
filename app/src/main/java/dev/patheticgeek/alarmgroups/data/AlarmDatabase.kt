package dev.patheticgeek.alarmgroups.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.patheticgeek.alarmgroups.model.Alarm
import dev.patheticgeek.alarmgroups.model.AlarmGroup
import dev.patheticgeek.alarmgroups.model.OverrideEffect
import dev.patheticgeek.alarmgroups.model.OverridePreset
import dev.patheticgeek.alarmgroups.model.PresetEffect
import dev.patheticgeek.alarmgroups.model.ScheduleOverride
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun fromDate(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun toDate(epochDay: Long?): LocalDate? = epochDay?.let(LocalDate::ofEpochDay)
}

@Database(
    entities = [
        AlarmGroup::class, Alarm::class, ScheduleOverride::class, OverrideEffect::class,
        OverridePreset::class, PresetEffect::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // + override presets
    ],
)
@TypeConverters(Converters::class)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun groupDao(): GroupDao
    abstract fun overrideDao(): OverrideDao
    abstract fun presetDao(): PresetDao

    companion object {
        const val NAME = "alarmgroups.db"

        /**
         * The database lives in device-protected storage so alarms can be rescheduled and rung
         * after a reboot before the user has unlocked the phone (e.g. an overnight OTA update).
         */
        fun create(context: Context): AlarmDatabase {
            val storage = context.deviceProtectedContext()
            return Room.databaseBuilder(storage, AlarmDatabase::class.java, NAME)
                .build()
        }
    }
}

fun Context.deviceProtectedContext(): Context =
    if (isDeviceProtectedStorage) this else createDeviceProtectedStorageContext()
