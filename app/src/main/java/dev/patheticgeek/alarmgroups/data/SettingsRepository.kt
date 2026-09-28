package dev.patheticgeek.alarmgroups.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.patheticgeek.alarmgroups.model.Alarm
import dev.patheticgeek.alarmgroups.model.TimeoutAction
import dev.patheticgeek.alarmgroups.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

enum class VolumeButtonAction { NOTHING, SNOOZE, DISMISS }

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Minutes before an alarm to show the "upcoming" notification; 0 = off. */
    val upcomingMinutes: Int = 60,
    val volumeButtons: VolumeButtonAction = VolumeButtonAction.NOTHING,
    val starterGroupsOffered: Boolean = false,
    // Defaults for new alarms.
    val defaultRingtoneUri: String? = null,
    val defaultRingtoneTitle: String? = null,
    val defaultVolume: Int = 80,
    val defaultVibrate: Boolean = true,
    val defaultGradualSeconds: Int = 30,
    val defaultSnoozeMinutes: Int = 10,
    val defaultRingMinutes: Int = 10,
    val defaultTimeoutAction: TimeoutAction = TimeoutAction.SNOOZE,
) {
    fun newAlarm(hour: Int, minute: Int, groupId: Long?) = Alarm(
        hour = hour,
        minute = minute,
        groupId = groupId,
        ringtoneUri = defaultRingtoneUri,
        ringtoneTitle = defaultRingtoneTitle,
        volume = defaultVolume,
        vibrate = defaultVibrate,
        gradualSeconds = defaultGradualSeconds,
        snoozeMinutes = defaultSnoozeMinutes,
        ringMinutes = defaultRingMinutes,
        timeoutAction = defaultTimeoutAction,
    )
}

/** App settings, kept in device-protected storage alongside the database. */
class SettingsRepository(context: Context, fileName: String = "settings") {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.deviceProtectedContext().preferencesDataStoreFile(fileName)
    }

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val upcoming = intPreferencesKey("upcoming_minutes")
        val volumeButtons = stringPreferencesKey("volume_buttons")
        val starterOffered = booleanPreferencesKey("starter_groups_offered")
        val ringtoneUri = stringPreferencesKey("default_ringtone_uri")
        val ringtoneTitle = stringPreferencesKey("default_ringtone_title")
        val volume = intPreferencesKey("default_volume")
        val vibrate = booleanPreferencesKey("default_vibrate")
        val gradual = intPreferencesKey("default_gradual_seconds")
        val snooze = intPreferencesKey("default_snooze_minutes")
        val ring = intPreferencesKey("default_ring_minutes")
        val timeout = stringPreferencesKey("default_timeout_action")
    }

    val settings: Flow<Settings> = store.data.map(::fromPrefs)

    private fun fromPrefs(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            themeMode = p[Keys.theme].toEnum(d.themeMode),
            dynamicColor = p[Keys.dynamic] ?: d.dynamicColor,
            upcomingMinutes = p[Keys.upcoming] ?: d.upcomingMinutes,
            volumeButtons = p[Keys.volumeButtons].toEnum(d.volumeButtons),
            starterGroupsOffered = p[Keys.starterOffered] ?: d.starterGroupsOffered,
            defaultRingtoneUri = p[Keys.ringtoneUri],
            defaultRingtoneTitle = p[Keys.ringtoneTitle],
            defaultVolume = p[Keys.volume] ?: d.defaultVolume,
            defaultVibrate = p[Keys.vibrate] ?: d.defaultVibrate,
            defaultGradualSeconds = p[Keys.gradual] ?: d.defaultGradualSeconds,
            defaultSnoozeMinutes = p[Keys.snooze] ?: d.defaultSnoozeMinutes,
            defaultRingMinutes = p[Keys.ring] ?: d.defaultRingMinutes,
            defaultTimeoutAction = p[Keys.timeout].toEnum(d.defaultTimeoutAction),
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { p ->
            val s = transform(fromPrefs(p))
            p[Keys.theme] = s.themeMode.name
            p[Keys.dynamic] = s.dynamicColor
            p[Keys.upcoming] = s.upcomingMinutes
            p[Keys.volumeButtons] = s.volumeButtons.name
            p[Keys.starterOffered] = s.starterGroupsOffered
            s.defaultRingtoneUri?.let { p[Keys.ringtoneUri] = it } ?: p.remove(Keys.ringtoneUri)
            s.defaultRingtoneTitle?.let { p[Keys.ringtoneTitle] = it } ?: p.remove(Keys.ringtoneTitle)
            p[Keys.volume] = s.defaultVolume
            p[Keys.vibrate] = s.defaultVibrate
            p[Keys.gradual] = s.defaultGradualSeconds
            p[Keys.snooze] = s.defaultSnoozeMinutes
            p[Keys.ring] = s.defaultRingMinutes
            p[Keys.timeout] = s.defaultTimeoutAction.name
        }
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
