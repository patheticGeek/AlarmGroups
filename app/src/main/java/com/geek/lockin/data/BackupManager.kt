package com.geek.lockin.data

import android.content.Context
import android.net.Uri
import com.geek.lockin.model.Alarm
import com.geek.lockin.model.AlarmGroup
import com.geek.lockin.model.OverrideWithEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: String = OffsetDateTime.now().toString(),
    val groups: List<AlarmGroup>,
    val alarms: List<Alarm>,
    val overrides: List<OverrideWithEffects>,
    val settings: Settings? = null,
) {
    companion object {
        const val FORMAT = "lockin-backup"
        const val VERSION = 1
    }
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Pure JSON encoding/decoding of backups. */
object BackupCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(snapshot: Snapshot, settings: Settings?): String = json.encodeToString(
        BackupFile.serializer(),
        BackupFile(
            groups = snapshot.groups,
            // Transient ringing state isn't worth carrying to another install.
            alarms = snapshot.alarms.map { it.copy(snoozedUntil = null, nextTriggerAt = null) },
            overrides = snapshot.overrides,
            settings = settings,
        ),
    )

    /** Parses and sanitizes a backup; throws [BackupException] if it isn't one. */
    fun decode(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw BackupException("This file isn't a Lock In backup.", e)
        }
        if (file.format != BackupFile.FORMAT) throw BackupException("This file isn't a Lock In backup.")
        if (file.version > BackupFile.VERSION) {
            throw BackupException("This backup was made by a newer version of the app. Update the app and try again.")
        }
        val groupIds = file.groups.map { it.id }.toSet()
        return file.copy(
            alarms = file.alarms.map {
                it.copy(
                    groupId = it.groupId?.takeIf(groupIds::contains),
                    hour = it.hour.coerceIn(0, 23),
                    minute = it.minute.coerceIn(0, 59),
                    volume = it.volume.coerceIn(10, 100),
                    snoozeMinutes = it.snoozeMinutes.coerceIn(1, 120),
                    ringMinutes = it.ringMinutes.coerceIn(0, 180),
                    gradualSeconds = it.gradualSeconds.coerceIn(0, 600),
                )
            },
            overrides = file.overrides.map { o ->
                o.copy(effects = o.effects.filter { it.targetGroupId == 0L || it.targetGroupId in groupIds })
            }.filter { it.effects.isNotEmpty() },
        )
    }

    const val MAX_CHARS = 5_000_000
}

class BackupManager(
    private val context: Context,
    private val repository: AlarmRepository,
    private val settings: SettingsRepository,
) {
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val text = BackupCodec.encode(repository.snapshot(), settings.current())
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("Can't write to that file.")
        out.bufferedWriter().use { it.write(text) }
    }

    /** Replaces all alarms, groups, overrides and settings with the backup's. Returns what was imported. */
    suspend fun import(uri: Uri): BackupFile = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw BackupException("Can't read that file.")
        val text = input.bufferedReader().use { it.readText() }
        if (text.length > BackupCodec.MAX_CHARS) throw BackupException("That file is too large to be a backup.")
        val file = BackupCodec.decode(text)
        repository.replaceAll(Snapshot(file.alarms, file.groups, file.overrides))
        file.settings?.let { imported -> settings.update { imported.copy(starterGroupsOffered = true) } }
        file
    }
}
