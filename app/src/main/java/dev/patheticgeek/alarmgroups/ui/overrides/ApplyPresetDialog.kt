package dev.patheticgeek.alarmgroups.ui.overrides

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.patheticgeek.alarmgroups.ui.components.DateRangePickerDialog
import dev.patheticgeek.alarmgroups.ui.components.dateRange
import dev.patheticgeek.alarmgroups.util.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

/** Asks when to apply [preset]: today, tomorrow, the rest of the week, or custom dates. */
@Composable
fun ApplyPresetDialog(
    preset: PresetItem,
    onDismiss: () -> Unit,
    onApply: (start: LocalDate, end: LocalDate) -> Unit,
) {
    var pickDates by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val endOfWeek = generateSequence(today) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.SUNDAY }
    val options = buildList {
        add("Today" to (today to today))
        add("Tomorrow" to (today.plusDays(1) to today.plusDays(1)))
        if (endOfWeek != today) add("Rest of this week" to (today to endOfWeek))
        add("Next 7 days" to (today to today.plusDays(6)))
    }
    if (pickDates) {
        DateRangePickerDialog(
            start = today,
            end = today,
            onDismiss = { pickDates = false; onDismiss() },
            onPick = { s, e -> onApply(s, e) },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply \"${preset.name}\"") },
        text = {
            Column {
                Text(preset.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                options.forEach { (label, range) ->
                    ListItem(
                        modifier = Modifier.clickable { onApply(range.first, range.second); onDismiss() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(label) },
                        supportingContent = { Text(dateRange(range.first, range.second)) },
                    )
                }
                ListItem(
                    modifier = Modifier.clickable { pickDates = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Pick dates…") },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shows "WFH applied · Today" with an Undo that deletes the new override. */
fun CoroutineScope.announceApplied(
    snackbar: SnackbarHostState,
    preset: PresetItem,
    start: LocalDate,
    end: LocalDate,
    overrideId: Long?,
    undo: (Long) -> Unit,
) = launch {
    val r = snackbar.showSnackbar(
        "${preset.name} applied · ${if (start == end) TimeFormat.day(start) else dateRange(start, end)}",
        actionLabel = if (overrideId != null) "Undo" else null,
        duration = SnackbarDuration.Short,
    )
    if (r == SnackbarResult.ActionPerformed && overrideId != null) undo(overrideId)
}
