# Developer guide

Start with [contribution guidelines](../CONTRIBUTING.md).

## Build and release

Requires Bash, JDK 17, Android SDK platform 36, build-tools 35.0.0, and standard zip/JDK tools. ZXing Core is vendored under `lib/`.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build.sh
```

Run `bash scripts/test-downloads.sh` to exercise Downloader resolution, redirects, and rejected/incomplete downloads.

Outputs: `build/GlideTV.apk` and `build/bridge-dex/classes.dex`. The build recognizes the development machine's Homebrew defaults on macOS. Local builds generate an ignored development keystore if needed. For your own releases, provide `GLIDE_KEYSTORE`, `GLIDE_KEYSTORE_PASSWORD`, `GLIDE_KEY_PASSWORD`, and `GLIDE_KEY_ALIAS`. Keep the same signing key for upgrades; a separately signed fork cannot replace an official build.

The [APK workflow](../.github/workflows/release.yml) publishes a signed APK, helper, checksums, `update.json`, current scene packs, and the scene catalogue. The [scene workflow](../.github/workflows/scenes.yml) publishes packs independently. Both require a changelog entry and derive release notes from it.

Maintainers configure `GLIDE_KEYSTORE_BASE64`, `GLIDE_KEYSTORE_PASSWORD`, `GLIDE_KEY_PASSWORD`, and `GLIDE_KEY_ALIAS` as repository secrets. The signing key is never committed; CI fails if it is missing.

For an app release, increase the Android version code, set `versionName`, update [CHANGELOG.md](../CHANGELOG.md), and push a matching `v<versionName>` tag. For a scene release, update the catalogue/pack versions and [scene changelog](../scenes/CHANGELOG.md), then push `scene-v<catalogVersion>`. Published pack content cannot change under an existing version. Manual workflow dispatch supports rebuilding an existing tag.

Forks should update the repository URLs in the updater, scene downloader, and release scripts and use their own release key.

## Development checks

`bash scripts/test-icon-palette.sh` checks icon color extraction, neutral/transparent backgrounds, dominant colors, and visibility of dark brand colors. Pull requests build a development APK and run these checks in GitHub Actions. The development APK uses a temporary signing key and cannot update an official installation; use the signed release workflow for upgrades.

`python3 scripts/test_release.py` checks reproducible scene archives, immutable versions, fallback coverage, and catalogue/tag rules. Both release workflows run these checks.

After `./build.sh`, run `./scripts/test-on-device.sh <adb-serial>` for Android runtime checks. It uses a separate shell process with temporary preferences/files and the public catalogue to test fresh setup, legacy-location migration, worldwide geocoding, city/fallback selection, verified downloads, cache reuse, and cleanup. It does not install or launch Glide or change the TV's real location/settings. Network access is required, and the script removes its own temporary test files afterward.

## Code map

| Path | Purpose |
| --- | --- |
| `src/space/glidetv/launcher/` | Native Java launcher, scenery, app discovery and local services |
| `assets/remote/` | Phone browser interface |
| `bridge/` | Optional ADB helper |
| `scenes/` | Downloadable scene catalogue and documentation |
| `tests/`, `scripts/` | Checks, packaging and release tools |

## Visual review captures

Pull-request checks capture the launcher on a disposable Android TV emulator, using synthetic demo apps and no personal watch history. Download the **GlideTV-visual-review** artifact for before/after screenshots, focus states and a short recording. Inspect and embed the relevant images in the PR description; the artifact alone is not a visual review.

To capture locally, start a disposable Android TV emulator, build the APK, and run `bash scripts/capture-ui.sh`. This script replaces emulator app preferences with demo data and refuses to run on a real TV. Set `VISUAL_BASE_APK` to a baseline APK to also capture the previous layout.
