# scrcpy Manager companion

Android companion app for scrcpy Manager. It uses a Shizuku user service to
watch the USB or Ethernet interface selected by the desktop manager and restore
the physical display if that connection disappears while scrcpy has turned the
screen off.

The companion does not use a permanent notification or a standalone heartbeat.
The desktop manager arms cable-loss protection only for the active session.

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

## Third-party notice

`DisplayPower.java` contains a maintained Java port of display-power logic from
[Genymobile/scrcpy](https://github.com/Genymobile/scrcpy), licensed under the
Apache License 2.0. See [NOTICE](NOTICE) for details.
