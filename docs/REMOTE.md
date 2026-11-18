# Phone remote

## Pair and control

Open **Remote** on Glide Home, or **Settings → Mobile control**, and scan the QR while your phone is on the same network. The address/code are also displayed. The dots-only QR uses light-on-dark colors; scanners without inverted-code support can use the address and code.

Pairing closes the TV dialog and turns the Remote indicator green. The browser shows TV status, apps, and shared files. Choose **Apps → Install APK**, select a file on your phone, and confirm installation on the TV. If Android asks you to allow Glide to install apps, enable that setting and use **Retry installation** in the browser.

For a **Downloader code or APK link**, open **Apps → Downloader**, enter the numeric code or a direct HTTPS APK URL, and choose **Download APK**. The TV downloads in the background, shows progress and the APK's app name/package/version, then **Install on TV** opens Android's confirmation. This download route works without the ADB helper. You can cancel a running download; after granting install permission, tap Install on TV again. Links must resolve to an APK over public HTTPS; web pages, app bundles, private-network addresses, and files over 1 GB are unsupported. Downloader codes resolve through AFTVnews; no separate Downloader app is needed.

TV-wide process management, shared-storage browsing/transfers, and global key input require the optional ADB helper below. Uploads go to `Download/Glide Remote`, do not overwrite existing files, and are limited to 2 GB each. Android private app directories remain inaccessible.

The local server uses temporary PIN pairing and a bearer session over HTTP on the TV's subnet. Use a trusted private network and do not expose the ports to the internet. PINs expire after ten minutes, sessions after thirty minutes, and paired remotes stop after five minutes without an action. Status polling does not keep the session alive. **Turn off** closes the server manually.

## Optional ADB helper

The helper runs as a debugging-shell process on `127.0.0.1`, authenticates with a random app-generated token, and offers fixed operations rather than arbitrary shell commands.

Open Glide and its RAM dialog or Remote once to create the token. After building from source:

```sh
./start-memory-helper.sh <adb-serial>
```

Or download `glide-memory-helper.dex` from the same release, download the source scripts, and use:

```sh
GLIDE_HELPER_DEX=/path/to/glide-memory-helper.dex ./start-memory-helper.sh <adb-serial>
```

The helper script requires zsh and ADB. Restart the helper after a TV reboot. Android can reclaim memory after a cleanup; free RAM is not a persistent reservation.
