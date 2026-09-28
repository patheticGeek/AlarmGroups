package com.geek.lockin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.geek.lockin.model.Alarm
import com.geek.lockin.model.AlarmGroup
import com.geek.lockin.model.OverrideEffect
import com.geek.lockin.model.ScheduleOverride
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun fromDate(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun toDate(epochDay: Long?): LocalDate? = epochDay?.let(LocalDate::ofEpochDay)
}

@Database(
    entities = [AlarmGroup::class, Alarm::class, ScheduleOverride::class, OverrideEffect::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun groupDao(): GroupDao
    abstract fun overrideDao(): OverrideDao

    companion object {
        const val NAME = "lockin.db"

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
