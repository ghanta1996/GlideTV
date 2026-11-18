# Install and update GlideTV

## Install

Glide runs on Android TV / Google TV with Android 8.0 (API 26) or newer. Replacement-Home behavior depends on the TV's firmware.

1. Download the APK from [Releases](https://github.com/ghanta1996/GlideTV/releases/latest) and sideload it using your preferred installer, or ADB:

   ```sh
   adb -s <adb-serial> install -r GlideTV.apk
   ```

2. Open Glide. Choose **Use device location** or **Search city**. If the TV cannot provide a location, city/postal-code search works worldwide. Downloading scenes and fetching weather require internet access.
3. Follow the complete [default-Home guide](DEFAULT_HOME.md). It covers TV settings, wireless ADB, saving the original launcher, selecting Glide, testing Home, firmware restrictions, and restoring the original Home.
4. Pin your favorite apps from the library. Existing saved favorites and supported older location settings are preserved during an update.

The app may request Android location, TV listings/Watch Next, and Bluetooth connection access. Location is optional; searching for a city does not require device-location permission. Coordinates determine local weather, air quality, time zone, sunrise, and sunset. Weather includes temperature, feels-like, humidity, UV, precipitation, rain chance, wind, and US AQI.

## Updates

Choose **Settings → Check for updates**. Glide checks the latest stable public APK, downloads over HTTPS, verifies its checksum and package/version, then opens Android's installation confirmation. Android enforces the signing identity; normal upgrades preserve your settings. From v4.5.0, Glide also checks quietly when Home opens, then at most every six hours while Home is active. A selectable **Glide update available** notice appears on Home; it does not interrupt another app or install anything automatically. Failed background checks retry after fifteen minutes. Versions before v4.5.0 need one manual check to receive this feature.

Android may evict Glide while another app is active. Reopen Home and start Remote to make the browser connection available again.

## Share Glide with a Downloader code

Each release publishes this stable APK address:

```text
https://github.com/ghanta1996/GlideTV/releases/latest/download/GlideTV.apk
```

Submit that URL at the [AFTVnews URL Shortener](https://go.aftvnews.com/) and complete its verification to obtain a numeric code. The code can be entered in Downloader or Glide's mobile Downloader field. Use the stable address so future releases keep the same code; AFTVnews does not currently let creators edit a code's destination. No numeric Glide code has been registered by this repository.
