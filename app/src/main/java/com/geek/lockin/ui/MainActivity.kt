package com.geek.lockin.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.geek.lockin.LockInApp
import com.geek.lockin.ui.health.Check
import com.geek.lockin.ui.health.Health
import com.geek.lockin.ui.theme.LockInTheme
import com.geek.lockin.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private val failing = mutableStateOf<List<Check>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = LockInApp.container(this)
        setContent {
            val settings by container.settings.settings.collectAsState(initial = null)
            LockInTheme(
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
