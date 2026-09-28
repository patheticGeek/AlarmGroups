package dev.patheticgeek.alarmgroups.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.patheticgeek.alarmgroups.AppContainer
import dev.patheticgeek.alarmgroups.alarm.RingingState
import dev.patheticgeek.alarmgroups.ui.alarms.AlarmsTab
import dev.patheticgeek.alarmgroups.ui.alarms.AlarmsViewModel
import dev.patheticgeek.alarmgroups.ui.edit.AlarmEditScreen
import dev.patheticgeek.alarmgroups.ui.edit.AlarmEditViewModel
import dev.patheticgeek.alarmgroups.ui.edit.GroupEditScreen
import dev.patheticgeek.alarmgroups.ui.edit.GroupEditViewModel
import dev.patheticgeek.alarmgroups.ui.health.Check
import dev.patheticgeek.alarmgroups.ui.overrides.OverrideEditScreen
import dev.patheticgeek.alarmgroups.ui.overrides.OverrideEditViewModel
import dev.patheticgeek.alarmgroups.ui.overrides.OverridesTab
import dev.patheticgeek.alarmgroups.ui.overrides.OverridesViewModel
import dev.patheticgeek.alarmgroups.ui.ringing.RingingActivity
import dev.patheticgeek.alarmgroups.ui.settings.SettingsTab
import dev.patheticgeek.alarmgroups.ui.settings.SettingsViewModel
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable data class AlarmRoute(val id: Long = 0, val groupId: Long = -1)
@Serializable data class GroupRoute(val id: Long = 0)
@Serializable data class OverrideRoute(val id: Long = 0)

enum class Tab(val title: String) { ALARMS("Alarms"), OVERRIDES("Overrides"), SETTINGS("Settings") }

@Composable
fun AppNav(container: AppContainer, failing: List<Check>, onHealthChanged: () -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            Home(
                container = container,
                failing = failing,
                onHealthChanged = onHealthChanged,
                onEditAlarm = { id, groupId -> nav.navigate(AlarmRoute(id, groupId ?: -1)) },
                onEditGroup = { nav.navigate(GroupRoute(it)) },
                onEditOverride = { nav.navigate(OverrideRoute(it)) },
            )
        }
        composable<AlarmRoute> { entry ->
            val r = entry.toRoute<AlarmRoute>()
            val vm = viewModel { AlarmEditViewModel(container, r.id, r.groupId.takeIf { it > 0 }) }
            AlarmEditScreen(vm, onDone = { nav.popBackStack() })
        }
        composable<GroupRoute> { entry ->
            val r = entry.toRoute<GroupRoute>()
            val vm = viewModel { GroupEditViewModel(container, r.id) }
            GroupEditScreen(vm, onDone = { nav.popBackStack() })
        }
        composable<OverrideRoute> { entry ->
            val r = entry.toRoute<OverrideRoute>()
            val vm = viewModel { OverrideEditViewModel(container, r.id) }
            OverrideEditScreen(vm, onDone = { nav.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(
    container: AppContainer,
    failing: List<Check>,
    onHealthChanged: () -> Unit,
    onEditAlarm: (id: Long, groupId: Long?) -> Unit,
    onEditGroup: (Long) -> Unit,
    onEditOverride: (Long) -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.ALARMS) }
    val snackbar = remember { SnackbarHostState() }
    val alarmsVm = viewModel { AlarmsViewModel(container) }
    val overridesVm = viewModel { OverridesViewModel(container) }
    val settingsVm = viewModel { SettingsViewModel(container) }
    val ringing by RingingState.alarms.collectAsState()
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Ask for notification permission up front: without it the ringing screen can't appear.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onHealthChanged() }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(if (tab == Tab.ALARMS) "AlarmGroups" else tab.title) },
                scrollBehavior = scroll,
                actions = {
                    if (tab == Tab.ALARMS) {
                        IconButton(onClick = { onEditGroup(0) }) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "New group")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            when (tab) {
                Tab.ALARMS -> ExtendedFloatingActionButton(
                    onClick = { onEditAlarm(0, null) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Alarm") },
                )
                Tab.OVERRIDES -> ExtendedFloatingActionButton(
                    onClick = { onEditOverride(0) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Override") },
                )
                Tab.SETTINGS -> Unit
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val selected = t == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t },
                        label = { Text(t.title) },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.ALARMS -> if (selected) Icons.Filled.Alarm else Icons.Outlined.Alarm
                                    Tab.OVERRIDES -> if (selected) Icons.Filled.EventRepeat else Icons.Outlined.EventRepeat
                                    Tab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
                                },
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(targetState = tab, label = "tab") { current ->
            when (current) {
                Tab.ALARMS -> {
                    val state by alarmsVm.state.collectAsState()
                    AlarmsTab(
                        state = state,
                        vm = alarmsVm,
                        failing = failing,
                        ringing = ringing,
                        padding = padding,
                        snackbar = snackbar,
                        onFix = { tab = Tab.SETTINGS },
                        onOpenRinging = {
                            context.startActivity(Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        onAddAlarm = { groupId -> onEditAlarm(0, groupId) },
                        onEditAlarm = { onEditAlarm(it, null) },
                        onEditGroup = onEditGroup,
                    )
                }
                Tab.OVERRIDES -> {
                    val state by overridesVm.state.collectAsState()
                    OverridesTab(state, overridesVm, padding, onEdit = onEditOverride)
                }
                Tab.SETTINGS -> SettingsTab(settingsVm, failing, padding, snackbar, onHealthChanged)
            }
        }
    }
}
