# Make Glide your default Home

These steps apply to Android TV and Google TV. Settings labels and manufacturer restrictions vary; Glide cannot guarantee that every firmware will allow a replacement Home.

## 1. Install and try Glide

Download the APK from [Releases](https://github.com/ghanta1996/GlideTV/releases/latest). Sideload it using a TV installer or ADB. Open Glide from your app list, set your location, let its scene download, and confirm that your apps open normally.

Keep the original launcher installed while setting up Glide.

## 2. Try the TV settings

Open Android **Settings → Apps → Default apps → Home app**, if your TV exposes that menu, and choose **Glide TV**. Some TVs place the option under **Special app access** or call it **Default launcher**. If pressing Home presents a launcher chooser, select Glide and choose **Always**.

Press the physical remote's Home button several times, including from a media app. If it consistently opens Glide, setup is complete.

## 3. Connect ADB if there is no Home setting

On the TV, open **Settings → System / Device Preferences → About** and select **Android TV OS build / Build number** seven times to enable Developer options. Open **Developer options**, enable **USB debugging**, and enable **Wireless debugging** if offered.

For TVs with a pairing screen:

```sh
adb pair <tv-ip>:<pairing-port>
# Enter the pairing code shown on the TV.
adb connect <tv-ip>:<debugging-port>
```

The pairing and debugging ports are often different. Use the values displayed by your TV. Some older TVs offer network debugging directly instead; use their displayed address/port or a USB debugging connection. Accept the computer authorization prompt on the TV, then run `adb devices` to confirm it is connected.

## 4. Record the original launcher

Run these on your computer, replacing the example serial with the one shown by `adb devices`:

```sh
TV_SERIAL='<your-adb-serial>'
TV_USER="$(adb -s "$TV_SERIAL" shell am get-current-user | tr -d '\r')"
adb -s "$TV_SERIAL" shell cmd package resolve-activity --brief --user "$TV_USER" \
  -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s "$TV_SERIAL" shell cmd package query-activities --brief --user "$TV_USER" \
  -a android.intent.action.MAIN -c android.intent.category.HOME
```

Save the original manufacturer's `package/activity` component from the results before changing anything. If the first command returns a resolver/chooser or Glide is already the default, identify the original TV launcher from the candidate list. Do not treat Android's resolver, System UI, Settings, or a setup wizard as your old launcher.

```sh
STOCK_HOME='<original.launcher.package/original.HomeActivity>'
printf '%s\n' "$STOCK_HOME" > previous-home.txt
```

Keep that file and the list of any components you disable in the next step.

## 5. Set Glide using ADB

```sh
adb -s "$TV_SERIAL" shell cmd package set-home-activity --user "$TV_USER" \
  space.glidetv.launcher/.HomeActivity
adb -s "$TV_SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s "$TV_SERIAL" shell cmd package resolve-activity --brief --user "$TV_USER" \
  -a android.intent.action.MAIN -c android.intent.category.HOME
```

The resolved component should be `space.glidetv.launcher/.HomeActivity`. Test the physical Home button from another app, then restart the TV and test again.

## 6. If the physical Home button still opens the stock launcher

Some firmware intercepts the remote's Home button even after changing Android's default. First confirm Glide opens explicitly:

```sh
adb -s "$TV_SERIAL" shell am start -n space.glidetv.launcher/.HomeActivity
```

If you choose to override the stock Home activity, keep ADB connected and use only the specific stock launcher component you recorded:

```sh
adb -s "$TV_SERIAL" shell pm disable --user "$TV_USER" "$STOCK_HOME"
adb -s "$TV_SERIAL" shell cmd package set-home-activity --user "$TV_USER" \
  space.glidetv.launcher/.HomeActivity
adb -s "$TV_SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

This disables that activity for the current user; it does not delete the package or its data. Do not disable whole groups of system apps, voice-search services, System UI, or setup/recovery components. If another manufacturer activity takes over Home, inspect it individually before changing it. Firmware can enforce additional restrictions; stop and restore the original Home if Glide still cannot be selected reliably.

## 7. Restore the original Home

With the same ADB connection and saved component:

```sh
adb -s "$TV_SERIAL" shell pm enable --user "$TV_USER" "$STOCK_HOME"
adb -s "$TV_SERIAL" shell cmd package set-home-activity --user "$TV_USER" "$STOCK_HOME"
adb -s "$TV_SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

Re-enable any additional components you explicitly disabled. The repository's helper performs the same restoration:

```sh
./restore-tv.sh "$TV_SERIAL" "$STOCK_HOME"
# Additional recorded Home components can be supplied as more arguments.
```

The restore script does not uninstall Glide, erase settings, or change unrelated services. Firmware updates may reset launcher preferences; use your saved component list when checking the setup again.
