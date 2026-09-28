package com.geek.routine

import android.app.Application
import android.content.Context
import android.util.Log
import com.geek.routine.alarm.AlarmScheduler
import com.geek.routine.alarm.Notifications
import com.geek.routine.data.AlarmDatabase
import com.geek.routine.data.AlarmRepository
import com.geek.routine.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-rolled dependency container; everything is created lazily on first use. */
class AppContainer(context: Context) {
    val app: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AlarmDatabase by lazy { AlarmDatabase.create(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val scheduler: AlarmScheduler by lazy { AlarmScheduler(app, { repository }, settings) }
    val repository: AlarmRepository by lazy {
        AlarmRepository(
            database,
            onChanged = { scheduler.rescheduleAll() },
            onRemoved = { ids -> scheduler.cancel(ids) },
        )
    }
}

class RoutineApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Self-heal on every process start: AlarmManager entries can be lost (force-stop, restore, etc.).
        container.appScope.launch {
            runCatching { container.scheduler.rescheduleAll() }
                .onFailure { Log.e("RoutineApp", "Initial reschedule failed", it) }
        }
    }

    companion object {
        fun container(context: Context): AppContainer = (context.applicationContext as RoutineApp).container
    }
}
