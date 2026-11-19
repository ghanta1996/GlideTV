# Changelog

App releases use semantic versions and an increasing Android version code. Scene packs have their own [changelog](scenes/CHANGELOG.md) and can be published independently.

## 4.7.1

- Treat GitHub community issue forms as documentation-only so later template changes do not publish an unchanged APK.
- Keep workflows and all other non-documentation GitHub configuration behind the normal version and changelog release gate.

## 4.7.0

- Automatically strengthen the foreground scrim as scenery becomes brighter, preserving text and control readability without adding theme settings.
- Smoothly adapt the treatment across daylight, night, and changing weather while keeping scenery motion visible.
- Regression-test the luminance model and capture real light, dark, and moving Android TV scenery in CI.

## 4.6.5

- Refine existing glass surfaces with clearer translucency, layered highlights, and restrained depth shadows.
- Smooth focus elevation and scaling transitions without changing D-pad behavior or adding customization.
- Capture visual regression evidence on both light and dark scenery.

## 4.6.4

- Verify that Home controls, the dock, and Continue Watching remain on-screen and non-overlapping at 720p, 1080p, and 4K.
- Record normalized layout geometry alongside real screenshots so scaling regressions fail CI before release.

## 4.6.3

- Stop forcing a full garbage collection after each Continue Watching render.
- Discard artwork and scenery loads when their requesting screen state has been replaced or destroyed.
- Record comparable Home memory and frame-rendering evidence for visual pull requests.

## 4.6.2

- Keep D-pad focus deterministic between the top controls, Continue Watching, and the dock.
- Finish any in-flight dock swap before saving or cancelling a reorder, so focus and tile order cannot be restored by a stale animation.

## 4.6.1

- Run the actual Android TV UI capture and D-pad focus checks at 720p, 1080p, and 4K for every pull request.

## 4.6.0

- Favorites show each app's own square icon again instead of its wide TV banner.
- The favorites bar moves to the bottom of Home as a Mac-style dock: a dark glass bar, bare icons, a dot under the focused app, and magnification that steps neighbouring icons aside instead of overlapping them.
- Continue Watching is now a single glass speech bubble whose horn points at the focused app. It appears three seconds after Home opens, follows focus between apps, and is not drawn at all for an app with nothing to resume.
- Watch titles are plain artwork thumbnails ringed by their own resume indicator: the ring sits just outside the artwork, tinted with the app's icon colour, and traces from the bottom-left around the tile. The section heading, per-card labels and the separate progress bar are gone.
- Holding OK lifts a favorite off the dock and reordering slides the two icons past each other instead of jumping.
- Icon-derived accent colouring is gone from the dock; the icon colour now tints the resume ring instead.

## 4.5.1

- Signed app releases are published automatically when code changes reach main.
- Code changes require a higher app version and Android version code, with release notes checked before publishing. Documentation-only updates do not create an app release.
- Scene catalogues are hosted as release assets so automatic publishing works with protected branches.

## 4.5.0

- Download APKs directly to the TV from a Downloader code or public HTTPS APK link in the mobile Apps page.
- Live download progress, cancellation, APK validation, and app identity before installation; downloads do not require the ADB helper.
- Android installation confirmation and a retry after granting install permission.
- Automatic update checks on Home, with a selectable update notice when a newer stable release is available. Downloads and installation remain user initiated.
- A stable `GlideTV.apk` release asset for permanent Downloader codes alongside the versioned APK and checksums.

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
