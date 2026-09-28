package dev.patheticgeek.alarmgroups.data

import android.app.Application
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Upgrading must never lose alarms: the database on users' phones is migrated in place. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AlarmDatabase::class.java)

    @Test
    fun `v1 to v2 keeps groups, alarms and overrides and adds presets`() = runBlocking {
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO groups (id, name, enabled, repeat_type, repeat_days, repeat_interval, sortOrder, createdAt) " +
                    "VALUES (1, 'Office', 1, 'WEEKLY', 31, 1, 0, 0)",
            )
            execSQL(
                "INSERT INTO alarms (id, groupId, hour, minute, label, enabled, repeat_type, repeat_days, repeat_interval, " +
                    "volume, vibrate, gradualSeconds, snoozeMinutes, ringMinutes, timeoutAction, createdAt) " +
                    "VALUES (7, 1, 7, 30, 'Gym', 1, 'ONCE', 0, 1, 80, 1, 30, 10, 0, 'SNOOZE', 0)",
            )
            execSQL("INSERT INTO overrides (id, name, startDate, endDate, enabled, createdAt) VALUES (3, 'Trip', 20000, 20005, 1, 0)")
            execSQL(
                "INSERT INTO override_effects (id, overrideId, targetGroupId, action, repeat_type, repeat_days, repeat_interval) " +
                    "VALUES (1, 3, 1, 'PAUSE', 'DAILY', 0, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 2, true).close()

        // Open with the real Room database to check everything reads back.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.databaseBuilder(context, AlarmDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals("Office", db.groupDao().getAll().single().name)
            val alarm = db.alarmDao().get(7)!!
            assertEquals("Gym", alarm.label)
            assertEquals(1L, alarm.groupId)
            assertEquals(listOf(1L), db.overrideDao().getAll().single().effects.map { it.targetGroupId })
            assertEquals(0, db.presetDao().getAll().size)
        } finally {
            db.close()
        }
    }
}
