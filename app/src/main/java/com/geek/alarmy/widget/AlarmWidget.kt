package com.geek.alarmy.widget

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.geek.alarmy.AlarmyApp
import com.geek.alarmy.domain.ScheduleCalculator
import com.geek.alarmy.ui.MainActivity
import com.geek.alarmy.util.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class WidgetGroup(val id: Long, val name: String, val enabled: Boolean, val next: String?)
private data class WidgetData(val nextTime: String?, val nextDetail: String?, val groups: List<WidgetGroup>)

private val GroupIdKey = ActionParameters.Key<Long>("groupId")

class AlarmWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load(context)
        provideContent { GlanceTheme { Content(data) } }
    }

    private suspend fun load(context: Context): WidgetData {
        val snap = AlarmyApp.container(context).repository.snapshot()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val nexts = snap.alarms.associateWith { ScheduleCalculator.nextTrigger(it, snap.groupOf(it), snap.overrides, now, zone) }
        // Weekday names rather than "Tomorrow" so the widget doesn't go stale at midnight.
        val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        fun fmt(i: Instant): String {
            val z = i.atZone(zone)
            return "${z.format(dayFmt)} ${TimeFormat.time(context, z.hour, z.minute)}"
        }
        val next = nexts.entries.filter { it.value != null }.minByOrNull { it.value!! }
        return WidgetData(
            nextTime = next?.value?.let(::fmt),
            nextDetail = next?.key?.label?.takeIf { it.isNotBlank() },
            groups = snap.groups.map { g ->
                val groupNext = nexts.filterKeys { it.groupId == g.id }.values.filterNotNull().minOrNull()
                WidgetGroup(g.id, g.name, g.enabled, groupNext?.let(::fmt))
            },
        )
    }

    @Composable
    private fun Content(data: WidgetData) {
        Column(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .padding(16.dp),
        ) {
            Column(GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>())) {
                Text("Next alarm", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp))
                Text(
                    data.nextTime ?: "None",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 28.sp, fontWeight = FontWeight.Medium),
                )
                data.nextDetail?.let {
                    Text(it, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp), maxLines = 1)
                }
            }
            if (data.groups.isNotEmpty()) {
                Spacer(GlanceModifier.height(8.dp))
                LazyColumn {
                    items(data.groups, itemId = { it.id }) { g ->
                        Row(
                            GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(GlanceModifier.defaultWeight()) {
                                Text(
                                    g.name,
                                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp),
                                    maxLines = 1,
                                )
                                Text(
                                    g.next ?: if (g.enabled) "Nothing scheduled" else "Off",
                                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                                    maxLines = 1,
                                )
                            }
                            Switch(
                                checked = g.enabled,
                                onCheckedChange = actionRunCallback<ToggleGroupAction>(actionParametersOf(GroupIdKey to g.id)),
                            )
                        }
                    }
                }
            }
        }
    }
}

class ToggleGroupAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[GroupIdKey] ?: return
        val repo = AlarmyApp.container(context).repository
        val g = repo.group(id) ?: return
        // Triggers a reschedule, which in turn refreshes all widgets.
        repo.setGroupEnabled(id, !g.enabled)
    }
}

class AlarmWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AlarmWidget()
}

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun requestUpdate(context: Context) {
        // Widgets can't be touched before first unlock; the post-unlock reschedule refreshes them.
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        scope.launch {
            runCatching { AlarmWidget().updateAll(context.applicationContext) }
                .onFailure { Log.w("WidgetUpdater", "Widget update failed", it) }
        }
    }
}
