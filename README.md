# AlarmGroups

An Android alarm clock built around **groups**. Put alarms in groups like *Office*, *WFH*, *No work* and *Vacation*; each group owns its repeat schedule and can be switched off, paused until a date, skipped for a day, or overridden for a date range.

Material 3 (dynamic color, light/dark), Kotlin + Jetpack Compose, minSdk 26.

## Download

Grab the latest APK from **[Releases](https://github.com/patheticGeek/AlarmGroups/releases/latest)** and open it on your phone (allow "install unknown apps" for your browser when asked). Every push to `master` publishes a new release, signed with the same key, so newer versions install over older ones and keep your alarms.

## Features

### Groups

A group is a set of alarms that share one repeat schedule, e.g. *Office* on weekdays or *Vacation* every day.

- **Repeat rules:** once (next occurrence, or on a chosen date), daily, weekly on chosen days, or every N days from a start date.
- **On/off switch:** turns every alarm in the group off or on at once. Groups you only use through overrides (like *Vacation*) can stay off.
- **Pause until…:** silences the group through a date; it resumes by itself the day after. *Resume now* ends it early.
- **Skip all alarms today/tomorrow/on a date:** skips every alarm in the group on the next day it would ring; the menu names that day. The group then shows *"All alarms skipped today"*, and *Stop skipping* brings them back.
- **Starter groups:** on first launch the app offers to create *Office*, *WFH*, *No work* and *Vacation*. Rename, change or delete them any time.
- **Deleting a group** deletes its alarms too.

### Alarms

- **Time, label, and group.** An alarm in a group follows the group's repeat rule. An alarm with no group has its own rule.
- **Sound:** the default alarm sound, any system sound, or any audio file on the phone, with a preview button.
- **Volume** (10–100%) that the alarm rings at, regardless of the phone's current alarm volume.
- **Gradual volume increase** over 15 s to 10 min, rising evenly to the ear, or off.
- **Vibration** on or off.
- **Snooze length** from 1 to 60 minutes.
- **Ring for** 1–60 minutes or *until I stop it*, and **then** either snooze and ring again or stop.
- **Skip the next ring** (the menu names it, e.g. *Skip today at 7:00 AM*); the alarm stays on and shows *"Skipped today at 7:00 AM · next ring tomorrow at 7:00 AM"*. On a one-off alarm, skipping switches it off instead.
- **One-off alarms** switch themselves off after they ring.
- **Delete** from the alarm's menu, with *Undo*.
- The editor shows when the alarm will next ring with the current groups and overrides, or warns if it won't ring at all.

### Overrides

An override changes groups for a date range without touching their normal schedule. For each group (and for ungrouped alarms) it can **pause** it, or make it **ring on** a different repeat rule, which also switches on a group that is normally off.

Example: *"Goa trip, Oct 3–10: Office and WFH paused, Vacation every day."* Before and after those dates, everything is back to normal on its own.

- *Pause everything* fills in a pause for every group in one tap.
- Overrides can be switched off without deleting them. Ended ones can be cleared in one go.
- Where overrides overlap, the **most recently saved** one wins for the groups it changes.

### Override presets

A preset is an override without dates, for changes you make often, e.g. *"WFH: pause Office, WFH every day"*.

- Create one under *Overrides → Presets → New preset*, or tap **Save as preset** in any override's editor.
- **Apply** it from the Overrides tab, or from the preset chips under the next-alarm card on the Alarms screen. Pick **Today**, **Tomorrow**, **Rest of this week**, **Next 7 days** or **Pick dates…**.
- Applying creates a normal override named after the preset, with **Undo** in the confirmation. Editing or deleting a preset doesn't affect overrides already created from it.

### How the app decides whether an alarm rings on a given day

1. A snooze in progress always rings.
2. Otherwise, the newest enabled override covering that day that mentions the alarm's group (or *Ungrouped*) decides: pause, or its own repeat rule.
3. Otherwise, a group that is off or paused through that day doesn't ring.
4. Otherwise, the group's repeat rule (or the alarm's own, if it has no group) decides.
5. Skipped rings are left out.

### Alarms screen

- **Next alarm** card: when the next alarm rings and how long until then.
- Alarms listed under their group, with each group's current state: its repeat rule, *Off*, *Paused through…*, an override in effect, or an upcoming override (*"Goa trip pauses from Sat, Oct 3"*).
- **Collapse** a group by tapping its header. A collapsed group shows how many alarms it has and its next ring. The top-bar button collapses or expands all groups. Collapsed groups are remembered.
- **Quick preset chips** to apply override presets in two taps.
- A **warning banner** if something would stop alarms ringing reliably, and a **ringing banner** to get back to a ringing alarm.

### When an alarm rings

- A full-screen alarm turns the screen on over the lock screen, with big **Snooze** and **Dismiss** buttons. Neither needs the phone unlocked.
- Snooze and Dismiss are also on the notification.
- Volume keys on the ringing screen do nothing by default, so they can't silence the alarm by accident. They can be set to snooze or dismiss instead.
- If several alarms ring at once, they share one ringing screen and are snoozed or dismissed together.

### Notifications

- **Upcoming alarm:** a quiet heads-up before each alarm (1 h by default, 15 min to 3 h, or off) with **Skip this one** and, for an alarm in a group, **Skip all *Office* today** to skip the whole group for that day.
- **Missed alarm:** if the phone was off when an alarm was due.
- **Backup alarm:** used only if the normal ringing screen can't start (see Reliability).

### Home screen widget

Shows the next alarm and each group's next ring, with a switch to turn each group on or off.

### Settings

- **Reliability:** the status of every permission and system setting alarms depend on, each with a *Fix* button. **Test alarm** rings a throwaway alarm 10 seconds later through the same path as a real one, using your defaults, so you can lock the phone and check it wakes the screen.
- **Appearance:** light, dark or follow system; dynamic color from your wallpaper (Android 12+).
- **Defaults for new alarms:** sound, volume, gradual increase, vibration, snooze length, ring duration and what happens after.
- **Behavior:** upcoming-alarm notification timing, and what the volume buttons do while ringing.
- **Backup:** export groups, alarms, overrides, presets and settings to a JSON file, and restore from one (replaces everything). The data is also included in Android's own device backup.

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

### CI

`.github/workflows/build.yml` builds, tests and lints every push to `master` and every pull request, uploading the APKs and reports as artifacts. Pushes to `master` are also signed with the release key and published as a GitHub Release `v1.0.<run number>`; the run number is the `versionCode`, so each release upgrades the previous one.

Release signing comes from these repository secrets: `RELEASE_KEYSTORE_BASE64` (the `.jks`, base64-encoded), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. **Back up the keystore and its password** — if the key is lost, no future release can install over existing ones (users would have to uninstall, losing their alarms).

## Project layout

```
app/src/main/java/dev/patheticgeek/alarmgroups/
  model/      Room entities: AlarmGroup, Alarm, ScheduleOverride/OverrideEffect, OverridePreset/PresetEffect, RepeatRule
  domain/     ScheduleCalculator — pure next-ring logic (groups, overrides, pause, skip, snooze, DST)
  data/       Room DB (device-protected), AlarmRepository, SettingsRepository (DataStore), backup
  alarm/      AlarmScheduler (AlarmManager), receivers, AlarmService (ringing), AlarmPlayer, notifications
  ui/         Compose screens: alarms, alarm/group/override/preset editors, settings, ringing screen, health checks
  widget/     Glance home screen widget
```

`ScheduleCalculator` is the heart of the app and is covered by unit tests (`app/src/test`), including DST gaps and overlaps, override precedence and clock changes. `AlarmEngineTest` runs the repository and scheduler together against Robolectric's AlarmManager. `BackupCodecTest` covers backup round trips and old backups.

### Database changes

The Room schema is exported to `app/schemas/`; commit the new JSON whenever the schema version changes. Upgrades must never lose alarms:

- Prefer `AutoMigration` (as in v1 → v2, which added presets); write a manual `Migration` when Room can't infer one.
- Extend `MigrationTest`, which creates a database at the old version with real data, migrates it, and reads it back. The schemas are added to debug assets only so Robolectric can load them; release builds don't include them.
