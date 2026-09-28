package com.geek.alarmy.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.net.toUri

/** Picks an alarm sound from the system list or any audio file. `null` uri = system default alarm. */
@Composable
fun SoundPickerRow(
    uri: String?,
    title: String?,
    onPick: (uri: String?, title: String?) -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Ringtone?>(null) }
    DisposableEffect(Unit) { onDispose { preview?.stop() } }

    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked = r.data?.let {
            IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        }
        if (picked == null || picked == Settings.System.DEFAULT_ALARM_ALERT_URI) {
            onPick(null, null)
        } else {
            onPick(picked.toString(), soundTitle(context, picked))
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        onPick(picked.toString(), fileName(context, picked) ?: "Custom sound")
    }

    Box {
        ListItem(
            modifier = Modifier.clickable { menu = true },
            leadingContent = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
            headlineContent = { Text("Sound") },
            supportingContent = { Text(title ?: "Default alarm sound", color = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                IconButton(onClick = {
                    val playing = preview
                    if (playing != null && playing.isPlaying) {
                        playing.stop()
                        preview = null
                    } else {
                        preview = startPreview(context, uri)
                    }
                }) {
                    Icon(
                        if (preview != null) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (preview != null) "Stop preview" else "Preview sound",
                    )
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Default alarm sound") }, onClick = { menu = false; onPick(null, null) })
            DropdownMenuItem(text = { Text("Choose from system sounds…") }, onClick = {
                menu = false
                preview?.stop()
                preview = null
                systemPicker.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
                        .putExtra(
                            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            uri?.toUri() ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
                        ),
                )
            })
            DropdownMenuItem(text = { Text("Choose audio file…") }, onClick = {
                menu = false
                preview?.stop()
                preview = null
                filePicker.launch(arrayOf("audio/*"))
            })
        }
    }
}

private fun startPreview(context: Context, uri: String?): Ringtone? {
    val u = uri?.toUri() ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return null
    return RingtoneManager.getRingtone(context, u)?.apply {
        audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        play()
    }
}

private fun soundTitle(context: Context, uri: Uri): String? =
    runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()

private fun fileName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
    }
}.getOrNull()
