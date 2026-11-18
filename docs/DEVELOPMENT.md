# Developer guide

Start with [contribution guidelines](../CONTRIBUTING.md).

## Build and release

Requires Bash, JDK 17, Android SDK platform 36, build-tools 35.0.0, and standard zip/JDK tools. ZXing Core is vendored under `lib/`.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build.sh
```

| Build output | Path |
| --- | --- |
| Launcher APK | `build/GlideTV.apk` |
| Optional helper DEX | `build/bridge-dex/classes.dex` |

Local builds generate an ignored development keystore when needed. Use the same signing key to upgrade an existing install.

| Release | Update first | Tag | Publishes |
| --- | --- | --- | --- |
| [App](../.github/workflows/release.yml) | Android version code, `versionName`, [app changelog](../CHANGELOG.md) | Automatic on `main` | Signed APK, helper, checksums, updater manifest and scenes |
| [Scenes](../.github/workflows/scenes.yml) | Catalogue and pack versions, [scene changelog](../scenes/CHANGELOG.md) | `scene-v<catalogVersion>` | Packs and catalogue, independently of the APK |

Code changes must increase **both** `versionName` (stable `major.minor.patch`) and `versionCode` in `AndroidManifest.xml`, and add matching notes to the app changelog. **Version check** runs on every PR to `main`. Once merged, CI creates `v<versionName>` at the exact tested commit and publishes the signed release. Explicit app tags and rebuilding an existing tag remain supported.

| Changes | Version bump / app release |
| --- | --- |
| Source, assets, build tools, workflows, tests, and other non-documentation files | Required / automatic on `main` |
| `*.md`, `*.rst`, `*.adoc`, `docs/`, `screenshots/`, `LICENSE`, `NOTICE` | No |
| Generated `scenes/index.json` | No |

Set **Version check** as a required status check in the `main` branch rules to block merges without a version bump. Direct pushes are also checked before a release can publish. Local check: `python3 scripts/check-version.py --base origin/main --pull-request`.

Generated scene metadata is uploaded to the `scene-catalogue` release asset, with `--latest=false`. Publishing needs no commits or bypass permission on protected `main`. The packager uses the legacy index to preserve existing pack hashes when bootstrapping the new endpoint; see the [scene guide](../scenes/README.md).

<details>
<summary>Release signing and forks</summary>

- Local release build variables are `GLIDE_KEYSTORE`, `GLIDE_KEYSTORE_PASSWORD`, `GLIDE_KEY_PASSWORD` and `GLIDE_KEY_ALIAS`.
- CI uses `GLIDE_KEYSTORE_BASE64`, `GLIDE_KEYSTORE_PASSWORD`, `GLIDE_KEY_PASSWORD` and `GLIDE_KEY_ALIAS` as repository secrets.
- For forks, update the repository URLs in the updater, scene downloader and release scripts. Use your own key.
- To rebuild an existing tag, use workflow dispatch. Published scene pack versions are immutable.

</details>

## Development checks

| Check | Command |
| --- | --- |
| Launcher build | `bash build.sh` |
| Icon palette | `bash scripts/test-icon-palette.sh` |
| Downloader resolution and redirects | `bash scripts/test-downloads.sh` |
| App version policy | `python3 scripts/test_version.py` |
| Scene archives and release rules | `python3 scripts/test_release.py` |
| Location, downloads and caching | `./scripts/test-on-device.sh <adb-serial>` after building |

The on-device test needs network access. It uses separate temporary files and preferences and cleans them up afterward.

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

To capture locally, start a disposable Android TV emulator, build a local APK with `android:debuggable="true"` on the application element in the manifest, and run `bash scripts/capture-ui.sh`. This script replaces emulator app preferences with demo data and refuses to run on a real TV. Set `VISUAL_BASE_APK` to a debuggable baseline APK to also capture the previous layout.
