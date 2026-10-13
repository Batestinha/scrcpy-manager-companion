# scrcpy Manager companion

Android companion app for scrcpy Manager. It uses a Shizuku user service to
watch the USB or Ethernet interface selected by the desktop manager and restore
the physical display if that connection disappears while scrcpy has turned the
screen off.

The companion does not use a permanent notification or a standalone heartbeat.
The desktop manager arms cable-loss protection only for the active session.

## Vibration and haptics

While a companion-managed scrcpy session is active, the companion checks the
physical panel power once per second. If Android is awake but scrcpy has turned
the internal panel off through its hidden display APIs, the companion turns off
the **Use vibration and haptics** master switch (`system vibrate_on`). Turning
the panel back on restores the prior setting, including its platform default.
Ordinary sleep/doze and sessions with the panel on do not suppress vibration.

Recovery also runs on session exit, cable loss, and companion shutdown. The
original setting and Android user ID are saved before suppression, so a daemon
restart can resume or restore the change. An already-disabled setting stays off;
individual vibration intensities are unchanged. Unrecognized physical-display
state never starts suppression. Existing `WATCH` and legacy `ARM` clients work
without a protocol change; the companion must be authorized and managing the
session (normally through the manager's cable-loss protection).

## Manager provisioning contract

Version 1.2.0 adds two exported broadcasts protected by
`android.permission.DUMP`, so only ADB shell/root can use them:

- `com.diogobatista.scrcpymanager.companion.action.STATUS`
- `com.diogobatista.scrcpymanager.companion.action.START`

Both return protocol-versioned JSON as ordered-broadcast result data. `START`
binds the daemon when Shizuku is running and already authorized; it never opens
the setup activity or prompts the user.

## Requirements

- Android 8.0 (API 26) or newer
- Shizuku 11 or newer, running and authorized for the companion
- The matching desktop scrcpy Manager integration

## Install

1. Download the APK from the latest GitHub release and install it.
2. Start Shizuku on the phone.
3. Open **scrcpy Manager companion** and select **Authorize with Shizuku**.
4. Enable cable-loss protection for the phone in the desktop manager.

## Build

The project requires JDK 17 and Android SDK API 37. Set `ANDROID_HOME` to the
installed Android SDK before running Gradle.

On Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

On Linux or macOS:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Release signing is optional and is configured through these environment
variables:

- `COMPANION_STORE_FILE`
- `COMPANION_STORE_PASSWORD`
- `COMPANION_KEY_PASSWORD`

The expected signing-key alias is `scrcpy-manager-companion`.

GitHub Actions runs the unit tests, release lint, and unsigned release build.
Download the unsigned APK from the successful workflow for the release commit,
sign it with the existing release key using `apksigner`, and verify that its
signing certificate matches the previous release before publishing the APK and
its SHA-256 checksum. The signing key is kept outside the repository and CI.

## Third-party notice

`DisplayPower.java` contains a maintained Java port of display-power logic from
[Genymobile/scrcpy](https://github.com/Genymobile/scrcpy), licensed under the
Apache License 2.0. See [NOTICE](NOTICE) for details.
