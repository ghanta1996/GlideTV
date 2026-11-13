# Changelog

App releases use semantic versions and an increasing Android version code. Scene packs have their own [changelog](scenes/CHANGELOG.md) and can be published independently.

## 4.4.0

- Worldwide city/postal-code search and optional Android location setup.
- Local weather, air quality, sunrise/sunset, and time-zone handling from the selected coordinates.
- Existing saved locations migrate without resetting favorites or settings.
- Downloadable, versioned GitHub scene packs with SHA-256 verification and offline caching.
- Only the selected pack is downloaded; the APK no longer bundles city artwork.
- A native moving landscape is available before download and where a dedicated city pack is unavailable.
- Favorites on a fresh install populate from apps actually installed on the TV.
- Generic default-Home instructions and a restore script that takes the user's previous launcher.

## 4.3.1

- First public release with screenshots and setup documentation.
- Signed APK, optional helper, checksums, and in-app update metadata published by GitHub Actions.
- Manual, verified in-app updates through Settings → Check for updates.
- Browser remote, TV status, file transfers, running-app tools, and APK installation from a phone.
- Live weather scenery, per-app Continue Watching, favorites, and an app library.
- QR pairing closes the dialog, activates a green remote indicator, and stops after five minutes without a command.
