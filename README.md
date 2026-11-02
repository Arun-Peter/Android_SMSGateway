# Android SMS Gateway

An Android app and command-line client for automating SMS delivery from a computer or from Termux on the phone. It sends messages through the phone's SIM and records sent and delivered status for each SMS part, using carrier delivery reports when available.

**Automation project:** replaces manual phone-to-computer messaging steps with a scriptable interface, message references, delivery tracking, and CSV export.

## Features

- Send an SMS remotely from macOS/Linux/Windows through Android Debug Bridge (ADB), or locally from Termux.
- Track sent, delivered, failed, and pending outcomes, with per-part timestamps and carrier references.
- Query status by caller-supplied message ID, list recent messages, and wait for delivery with `watch`.
- Select a SIM by slot or subscription ID on dual-SIM devices.
- Export the message log as CSV from the Android app.
- Check app permissions, SIMs, battery restrictions, and device connection with `info`.

## Try the compiled app

Download and install the Android APK:

[SmsGateway v1.1.4 release APK](https://github.com/Arun-Peter/Android_SMSGateway/raw/refs/heads/main/releases/SmsGateway-v1.1.4-release.apk)

The app requires Android 10 (API 29) or later and a phone with SMS capability. Android will ask for SMS permission. The optional phone permission is used to list SIMs and select one explicitly. To control the phone from a computer, enable USB or wireless debugging and authorize the computer. For Termux use, install Termux on the same phone.

After installing, open SMS Gateway once and copy its access token for the CLI. On Xiaomi/HyperOS devices, enable Autostart and set Battery saver to **No restrictions** so background delivery reports and remote commands can run reliably.

## Command-line client

The `tools/smsgw` helper supports macOS/Linux with ADB and Termux on Android. Install Android Platform Tools for computer use and make `adb` available on `PATH`.

Set the access token without storing it in the repository:

```sh
export SMSGW_TOKEN='paste-token-from-the-app'
```

Or save it in `tools/.smsgw_token` locally; that file is ignored by Git. Then use:

```sh
./tools/smsgw info
./tools/smsgw send --to +910000000000 --text "Automation message" --id JOB001
./tools/smsgw status JOB001
./tools/smsgw watch JOB001
./tools/smsgw list --summary 20
```

Use `./tools/smsgw help` for all options, including `--slot`, `--sub`, and `--device`. Message IDs are local references and are not included in the SMS sent to the recipient.

The app also exposes a broadcast API for other tools:

```sh
adb shell am broadcast -n com.r2r.smsgateway/.Api \
  -a send --es token "$SMSGW_TOKEN" \
  --es to '+910000000000' --es text 'Automation message' --es id JOB001
```

See [`Api.kt`](app/src/main/java/com/r2r/smsgateway/Api.kt) for supported actions and arguments.

## Build from source

Requirements: JDK 17, Android SDK with platform 35, and Gradle 8.9. Configure `local.properties` with your local Android SDK path. This file is ignored by Git.

```sh
gradle testDebugUnitTest assembleRelease
```

For a locally signed release, create `keystore/keystore.properties` and place your own signing key under `keystore/`. The properties file and key are intentionally excluded from version control. The checked-in APK is a distributable release build and does not include the signing key.

## Repository layout

- `app/` — Android application source, resources, and unit tests
- `tools/smsgw` — ADB and Termux CLI
- `releases/` — compiled APK for direct installation

## Security and permissions

The CLI token is an app-generated credential. Keep it private, do not commit it, and rotate it if exposed. SMS is sent using the phone's normal carrier service and may incur carrier charges. The app needs SMS permission to send messages; notification and phone-state permissions support delivery notifications and optional SIM selection.

## License

No license has been specified yet. Until a license is added, standard copyright restrictions apply to reuse of this source.
