# AlarmGroups

An Android alarm clock built around **groups**. Put alarms in groups like *Office*, *WFH*, *No work* and *Vacation*; each group owns its repeat schedule and can be switched off, paused until a date, skipped for a day, or overridden for a date range.

Material 3 (dynamic color, light/dark), Kotlin + Jetpack Compose, minSdk 26.

## Features

- **Groups** with a repeat rule: once, daily, weekly on chosen days, or every N days. Alarms in a group follow it; ungrouped alarms have their own rule.
- **Group controls**: on/off switch, *pause until* a date, *skip next day*.
- **Overrides**: a date range that pauses any groups (and/or ungrouped alarms) or gives them a different repeat rule, e.g. *"Goa trip, Oct 3–10: Office & WFH paused, Vacation daily"*. The newest override wins where they overlap.
- **Per alarm**: label, sound (system sounds or any audio file, with preview), volume, gradual volume increase, vibration, snooze length, ring duration (1 min … *until I stop it*) and what happens after (snooze again or stop), *skip next*.
- **Upcoming-alarm notification** with a *Skip this one* button.
- **Home screen widget**: next alarm plus a switch for each group.
- **Backup/restore** of everything to a JSON file. The data is also included in Android's own backup.

## Reliability

A missed alarm is the one failure that matters, so the app layers several safeguards:

| Risk | Mitigation |
|---|---|
| Doze / app standby delaying alarms | `AlarmManager.setAlarmClock()` (exempt from Doze); `USE_EXACT_ALARM` |
| Reboot (e.g. overnight OTA update) before you unlock | Database and settings live in **device-protected storage**; boot receiver, ringing service and screen are direct-boot aware, so alarms ring before first unlock |
| Phone off at alarm time | On boot, alarms missed by < 15 min ring immediately; older ones get a *missed alarm* notification |
| Clock / timezone changes, app updates, permission changes | Everything is rescheduled on `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`, exact-alarm permission changes, and on every app start |
| Alarm entries silently dropped | 3-hourly heartbeat re-registers everything |
| Ringing service killed mid-ring | A *safety snooze* is saved the moment an alarm fires, so it rings again unless you snoozed or dismissed; the service is also restarted with its intent |
| Service not allowed to start | Falls back to an insistent full-screen notification that plays the alarm sound by itself |
| Chosen sound missing / unreadable (e.g. SD card, before unlock) | Falls back to default alarm → default ringtone → a tone synthesized in memory; a watchdog restarts stalled playback |
| Alarm volume turned down | Alarm stream volume is set to the alarm's volume while ringing (and restored afterwards) and forced back up if muted mid-ring; volume keys on the ringing screen are ignored by default |
| Permissions revoked | *Settings → Reliability* shows notification, exact-alarm, full-screen and battery status with one-tap fixes; a banner warns on the main screen |

**Phone setup (once):** open *Settings → Reliability* in the app and make every row green. On Nothing OS / other skins, also set the app's battery usage to **Unrestricted**.

Force-stopping the app from system settings cancels all alarms (Android rule). Open the app once afterwards and they are rescheduled.

## Building

Requires JDK 21 (Gradle/AGP don't support newer JDKs yet) and the Android SDK (platform 37).

```sh
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug                  # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest lintDebug    # unit + Robolectric tests, lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs side by side with release (`dev.patheticgeek.alarmgroups.debug`). The launcher shows it as "AlarmGroups".

### Release

Create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

then `./gradlew assembleRelease` (or `bundleRelease` for Play). Without that file the release build is signed with the debug key, which is fine for personal installs.

## Project layout

```
app/src/main/java/dev/patheticgeek/alarmgroups/
  model/      Room entities: AlarmGroup, Alarm, ScheduleOverride, OverrideEffect, RepeatRule
  domain/     ScheduleCalculator — pure next-ring logic (groups, overrides, pause, skip, snooze, DST)
  data/       Room DB (device-protected), AlarmRepository, SettingsRepository, backup
  alarm/      AlarmScheduler (AlarmManager), receivers, AlarmService (ringing), AlarmPlayer, notifications
  ui/         Compose screens: alarms, editors, overrides, settings, ringing screen, health checks
  widget/     Glance home screen widget
```

`ScheduleCalculator` is the heart of the app and is covered by unit tests (`app/src/test`), including DST gaps and overlaps, override precedence and clock changes. `AlarmEngineTest` runs the repository and scheduler together against Robolectric's AlarmManager.
