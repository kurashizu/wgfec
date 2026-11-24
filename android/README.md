# WG FEC (Android)

WireGuard through UDPspeeder FEC, no root. The app embeds the official WireGuard tunnel library (wireguard-go) and runs
a bundled `speederv2` as a child process listening on `127.0.0.1`; WireGuard's endpoint points at it. The app excludes
itself from the VPN so the helper's packets go straight out.

Fake-TCP (udp2raw) is not supported: it needs raw sockets, which means root.

## Build

Requirements: JDK 17, Android SDK (platform 34, build-tools 34.0.0), NDK 26.3.11579264, Gradle 8.10+, git.

```bash
export ANDROID_HOME=/path/to/sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
scripts/build-speeder.sh                 # builds libspeederv2.so for arm64 (clones UDPspeeder at a pinned commit)
gradle :app:assembleDebug                # app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed with your own key. Create `keystore.properties` (it is git-ignored):

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=wgfec
keyPassword=...
```

then `gradle :app:assembleRelease`. Keep the keystore: an app can only be updated with the key it was first signed with.

## How a profile gets in

The admin page's **Android** tab shows an app string (`wgfec://1z/...`). See [../docs/app-string.md](../docs/app-string.md).
You can also paste a plain WireGuard config followed by a `speederv2 ...` command line.

## Notes

- Minimum Android 8 (API 26), arm64 only. Tested on one device (Android 16).
- Profiles, including private keys, are stored in the app's private storage. Backups are disabled.
- The quick-settings tile and the notification's Disconnect button are not covered by any test yet.
