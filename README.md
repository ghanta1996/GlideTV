# Glide TV

A lightweight Android TV launcher with a moving Abu Dhabi skyline, weather-aware scenery, large app icons, and a phone browser remote. Built with native Android views and Java.

[Download the latest APK](https://github.com/ghanta1996/GlideTV/releases/latest) · [Release builds](https://github.com/ghanta1996/GlideTV/actions/workflows/release.yml) · [MIT license](LICENSE)

![Glide TV home screen with Abu Dhabi skyline](screenshots/home-abu-dhabi.png)

*Real TV capture, cropped above Continue Watching to keep viewing history private.*

## Features

- Clock, date, configurable weather city, temperature, feels-like, humidity, UV, precipitation, rain chance, wind, and US AQI.
- Generated Abu Dhabi panorama with native camera movement, day/night blending, moving clouds, and rain driven by weather conditions. The default scene uses bitmap rendering. An optional local aerial video can be used when supplied separately; no video is bundled.
- Large favorites, a separate app library, bounded D-pad navigation, and Continue Watching for the highlighted app when it shares Android TV Watch Next data.
- Live RAM/storage meters and Wi-Fi, LAN, and Bluetooth connection shortcuts.
- Optional debugging helper for running-process details, app stopping, storage browsing, file transfers, and global remote keys.
- Browser remote with TV status, D-pad, volume, app launch/stop, file download/upload, and **Apps → Install APK** from your phone.
- QR pairing, a green active-remote indicator, pairing-dialog dismissal on connection, and paired-session shutdown after five minutes without a command. Status polling does not extend the session.
- **Settings → Check for updates** downloads the latest public APK, verifies its SHA-256 and package/version, then asks Android to confirm installation.
- Android TV's existing voice-search service stays available.

## Phone remote

<p>
  <img src="screenshots/mobile-remote.png" width="310" alt="Glide browser remote with memory, network, display details and D-pad">
  <img src="screenshots/mobile-apps.png" width="310" alt="Browser Apps page with Install APK and running app memory">
</p>

*Browser screenshots show demo device data, not a connected personal TV.*

## Install

Requires Android TV / Google TV, Android 8.0 (API 26) or newer. Development has focused on a TCL Android 14 TV; behavior on other firmware may differ.

1. Download the APK from [Releases](https://github.com/ghanta1996/GlideTV/releases/latest).
2. Sideload it using your preferred TV installer, or ADB:

   ```sh
   adb -s <adb-serial> install -r GlideTV-v4.3.1.apk
   ```

3. Open Glide TV. Select it as the default Home in Android settings where supported. With ADB, the equivalent is:

   ```sh
   adb -s <adb-serial> shell cmd package set-home-activity --user 0 space.glidetv.launcher/.HomeActivity
   ```

Some TV firmware overrides third-party Home selection. Review your device's behavior before disabling any stock launcher components. Installing Glide itself does not disable or remove other apps.

The app may request TV listings / Watch Next access, Wi-Fi SSID access, and Bluetooth connection permission. Continue Watching depends on each media app sharing its history through Android TV.

## Mobile control and APK installation

Open **Remote** on Glide Home, or **Settings → Mobile control**, and scan the QR on your phone while on the same network. The address and pairing code are also displayed. The dots-only QR is light on dark; scanners without inverted-code support may need the address/code fallback.

The browser can navigate the TV, show memory and device details, browse shared storage, transfer files, and install APKs. Choose **Apps → Install APK**, select an APK from your phone, and confirm on the TV. If Android asks you to allow Glide to install apps, enable that permission, then use **Retry installation** in the browser.

Storage browsing and file transfers require the optional ADB helper below. Uploads go to `Download/Glide Remote`; uploads cannot overwrite existing files. Android private app directories remain inaccessible. Individual files are limited to 2 GB.

The server accepts the TV's local subnet, uses temporary PIN pairing and a bearer session, and serves HTTP. Use a trusted private network; do not expose its ports to the internet. Pairing codes expire after ten minutes. Sessions expire after thirty minutes, or the server stops after five minutes with no authenticated action other than status polling. **Turn off** closes the server manually.

## Optional ADB helper

Android restricts process control and global key input for ordinary apps. Glide's helper runs as a debugging-shell process on `127.0.0.1`, authenticates using a random local token, and exposes fixed operations rather than arbitrary shell commands.

Open Glide and its RAM dialog or Remote once to create the token. Then either build the project and run:

```sh
./start-memory-helper.sh <adb-serial>
```

Or download `glide-memory-helper.dex` from the same release, download the source scripts, and run:

```sh
GLIDE_HELPER_DEX=/path/to/glide-memory-helper.dex ./start-memory-helper.sh <adb-serial>
```

The helper scripts require zsh and ADB. The helper stops when the TV reboots. Android can reclaim memory again after cleanup; free RAM is not a persistent reservation.

## In-app updates

From version 4.3.1 onward, choose **Settings → Check for updates**. Glide checks this repository's latest stable release and reads its `update.json` asset. Downloads use HTTPS and are streamed to disk; the updater verifies the checksum, expected package, and version code before opening Android's installer. Android verifies the APK signing identity and asks for confirmation. Favorites and settings are preserved by an ordinary update.

Update checks are manual. Android can evict the launcher while another app is active; the browser remote then becomes available again after you reopen Home and start Remote.

## Build from source

Requirements: Bash, JDK 17, Android SDK platform 36, build-tools 35.0.0, and standard `zip`/JDK tools. ZXing Core 3.5.4 is vendored under `lib/`.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build.sh
```

Outputs are `build/GlideTV.apk` and `build/bridge-dex/classes.dex`. On macOS, the script also recognizes the Homebrew JDK/SDK locations used during development.

A local build creates an ignored development keystore on first use. For your own releases, supply `GLIDE_KEYSTORE`, `GLIDE_KEYSTORE_PASSWORD`, `GLIDE_KEY_PASSWORD`, and `GLIDE_KEY_ALIAS`. Keep the same signing key for future updates. A locally signed build cannot replace an official release with a different signing key.

## Release automation

The [release workflow](.github/workflows/release.yml) builds on Ubuntu with JDK 17, verifies APK signing, and publishes:

- `GlideTV-v<version>.apk`
- `glide-memory-helper.dex`
- `SHA256SUMS`
- `update.json` for the in-app updater

Maintainers configure these repository secrets: `GLIDE_KEYSTORE_BASE64`, `GLIDE_KEYSTORE_PASSWORD`, `GLIDE_KEY_PASSWORD`, and `GLIDE_KEY_ALIAS`. The private keystore never belongs in Git. CI fails if the signing key is missing.

To release, increase `android:versionCode`, set `android:versionName` in `AndroidManifest.xml`, commit, then push a matching tag:

```sh
git tag v4.3.1
git push origin main v4.3.1
```

The tag must exactly match `v` plus the manifest version name. Existing tags can also be rebuilt with the workflow's manual dispatch. Forks should change the GitHub repository URL in `AppUpdater.java` and use their own signing key.

## Restore the original TV Home

[`restore-tv.sh`](restore-tv.sh) restores the particular TCL/Google TV packages and animation settings changed during development. Review it before use on another TV; package/component names and disabled states vary by firmware. It does not uninstall Glide or delete user app data.

## Credits and license

Glide source is [MIT licensed](LICENSE). ZXing Core is Apache 2.0; its license is included in [`lib/ZXING-LICENSE.txt`](lib/ZXING-LICENSE.txt). Weather comes from Open-Meteo; air quality uses CAMS ENSEMBLE through Open-Meteo. Source attribution is also shown in About.

The city and cloud artwork is AI-generated, with prompts in [`assets/live`](assets/live). It is an artistic interpretation of Abu Dhabi, not a live camera feed. App names and logos visible in the home screenshot belong to their respective owners and are not bundled third-party apps.
