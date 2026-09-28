package dev.patheticgeek.alarmgroups.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import dev.patheticgeek.alarmgroups.AlarmGroupsApp
import dev.patheticgeek.alarmgroups.ui.health.Check
import dev.patheticgeek.alarmgroups.ui.health.Health
import dev.patheticgeek.alarmgroups.ui.theme.AlarmGroupsTheme
import dev.patheticgeek.alarmgroups.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private val failing = mutableStateOf<List<Check>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AlarmGroupsApp.container(this)
        setContent {
            val settings by container.settings.settings.collectAsState(initial = null)
            AlarmGroupsTheme(
                themeMode = settings?.themeMode ?: ThemeMode.SYSTEM,
                dynamicColor = settings?.dynamicColor ?: true,
            ) {
                AppNav(container, failing.value, onHealthChanged = ::refreshHealth)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can change in system settings while we're in the background.
        refreshHealth()
    }

    private fun refreshHealth() {
        failing.value = Health.failing(this)
    }
}
