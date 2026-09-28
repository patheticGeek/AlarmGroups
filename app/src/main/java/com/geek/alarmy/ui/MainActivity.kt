package com.geek.alarmy.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.geek.alarmy.AlarmyApp
import com.geek.alarmy.ui.health.Check
import com.geek.alarmy.ui.health.Health
import com.geek.alarmy.ui.theme.AlarmyTheme
import com.geek.alarmy.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private val failing = mutableStateOf<List<Check>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AlarmyApp.container(this)
        setContent {
            val settings by container.settings.settings.collectAsState(initial = null)
            AlarmyTheme(
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
