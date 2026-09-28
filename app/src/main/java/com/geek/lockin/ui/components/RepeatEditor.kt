package com.geek.lockin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geek.lockin.model.RepeatRule
import com.geek.lockin.model.RepeatType
import com.geek.lockin.util.TimeFormat
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatEditor(rule: RepeatRule, onChange: (RepeatRule) -> Unit, modifier: Modifier = Modifier) {
    var pickOnceDate by remember { mutableStateOf(false) }
    var pickAnchor by remember { mutableStateOf(false) }
    val types = listOf(
        RepeatType.ONCE to "Once",
        RepeatType.DAILY to "Daily",
        RepeatType.WEEKLY to "Weekly",
        RepeatType.INTERVAL to "Every N",
    )
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            types.forEachIndexed { i, (type, label) ->
                SegmentedButton(
                    selected = rule.type == type,
                    onClick = {
                        onChange(
                            when (type) {
                                RepeatType.WEEKLY -> rule.copy(
                                    type = type,
                                    days = if (rule.days == 0) RepeatRule.WEEKDAYS else rule.days,
                                )
                                RepeatType.INTERVAL -> rule.copy(
                                    type = type,
                                    interval = rule.interval.coerceAtLeast(2),
                                    anchorDate = rule.anchorDate ?: LocalDate.now(),
                                )
                                else -> rule.copy(type = type)
                            },
                        )
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, types.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        when (rule.type) {
            RepeatType.ONCE -> Row(verticalAlignment = Alignment.CenterVertically) {
                val date = rule.onceDate
                if (date == null) {
                    Text(
                        "Rings the next time the clock reaches the alarm time.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    AssistChip(onClick = { pickOnceDate = true }, label = { Text("Pick date") })
                } else {
                    InputChip(
                        selected = true,
                        onClick = { pickOnceDate = true },
                        label = { Text("On ${TimeFormat.day(date)}") },
                    )
                    AssistChip(
                        onClick = { onChange(rule.copy(onceDate = null)) },
                        label = { Text("Next occurrence") },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            RepeatType.DAILY -> Unit
            RepeatType.WEEKLY -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    localeWeek().forEach { day ->
                        FilterChip(
                            selected = rule.hasDay(day),
                            onClick = { onChange(rule.withDay(day, !rule.hasDay(day))) },
                            label = { Text(day.narrow(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                            modifier = Modifier.size(width = 42.dp, height = 40.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { onChange(rule.copy(days = RepeatRule.WEEKDAYS)) }, label = { Text("Weekdays") })
                    AssistChip(onClick = { onChange(rule.copy(days = RepeatRule.WEEKENDS)) }, label = { Text("Weekends") })
                    AssistChip(onClick = { onChange(rule.copy(days = RepeatRule.ALL_DAYS)) }, label = { Text("All") })
                }
                if (rule.isEmpty) {
                    Text("Pick at least one day.", color = MaterialTheme.colorScheme.error)
                }
            }
            RepeatType.INTERVAL -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalIconButton(
                        onClick = { onChange(rule.copy(interval = (rule.interval - 1).coerceAtLeast(2))) },
                        enabled = rule.interval > 2,
                    ) { Icon(Icons.Filled.Remove, contentDescription = "Fewer days") }
                    Text("Every ${rule.interval} days", style = MaterialTheme.typography.titleMedium)
                    FilledIconButton(
                        onClick = { onChange(rule.copy(interval = (rule.interval + 1).coerceAtMost(365))) },
                    ) { Icon(Icons.Filled.Add, contentDescription = "More days") }
                }
                AssistChip(
                    onClick = { pickAnchor = true },
                    label = { Text("Starting ${TimeFormat.day(rule.anchorDate ?: LocalDate.now())}") },
                )
            }
        }
    }
    if (pickOnceDate) {
        DatePickerDialogFor(
            initial = rule.onceDate,
            onDismiss = { pickOnceDate = false },
            onPick = { onChange(rule.copy(onceDate = it)) },
        )
    }
    if (pickAnchor) {
        DatePickerDialogFor(
            initial = rule.anchorDate,
            minDate = null,
            onDismiss = { pickAnchor = false },
            onPick = { onChange(rule.copy(anchorDate = it)) },
        )
    }
}
