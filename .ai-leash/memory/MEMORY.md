# Alarmy (Android alarm app with groups)

## User preferences
- Work autonomously step by step; don't wait for approval between steps.
- Commit on `master`, UNSIGNED (repo-local `commit.gpgsign=false`) — user's global config signs and needs a passphrase.
- User's phone: Nothing Phone 2 (Android 14+).

## Product decisions (2026-09-28)
- Repeat schedule lives on the GROUP only (Once / Daily / Weekly days / every N days). Ungrouped alarms carry their own repeat.
- Overrides: date-range rules that can affect multiple groups (pause, or use a different repeat). Newest override wins on conflict.
- Extras requested: skip next occurrence (alarm or group), backup/restore JSON, home screen widget. NOT requested: dismiss challenges.
- Must be robust (missed alarm = lost money): setAlarmClock, reboot/time-change rescheduling, direct boot, audio fallbacks.

## Toolchain
- System JDK is 27 (too new). Use JDK 21 at ~/.local/share/jdks/jdk-21.0.12.1+1 (set in gradle.properties org.gradle.java.home).
- Android SDK at ~/Android/Sdk (platform android-37.0).
