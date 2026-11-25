# Arche for Android

Android companion app for **Arche**, a self-hosted family NAS. It shows the Arche web interface
in a WebView and backs up your phone's photos and videos to your NAS in the background, with no
other app needed.

> Français : application Android de **Arche** (NAS familial auto-hébergé). Elle affiche l'interface web
> d'Arche et sauvegarde automatiquement les photos et vidéos du téléphone sur le NAS. Voir la section
> « Configuration » et « Compilation » ci-dessous.

## How it works
- **Interface**: a WebView on `https://<your-arche-host>`. A JavaScript bridge (`window.ArcheApp`, class `Bridge`)
  lets the Sync page turn backup on and choose albums. The bridge only answers pages on the configured host.
- **Pairing**: after you sign in on the web interface, the app receives a token of its own (revocable,
  limited to photo backup, notifications and `/api/app/*`).
- **Backup** (`BackupWorker`, WorkManager): triggered by new photos, every 15 minutes, or on demand.
  Duplicates are skipped (SHA-1 checked by the server), large files are uploaded in resumable chunks.
  Options: Wi-Fi only, only while charging, include videos.
- **Local network** (`Net`): at home, the public host name is resolved to the NAS's LAN address, so
  the connection stays local (the certificate is still checked for the public name).
- **Updates**: the app can download newer builds from the server (`/api/app/android.apk`), checking the SHA-256.

## Configuration
Edit `version.properties` before building:

| Key    | Meaning                                              |
|--------|------------------------------------------------------|
| `code` | Version code (integer, increases with each release)  |
| `name` | Version name, e.g. `1.0.0`                           |
| `host` | Public address of your Arche server (no `https://`)  |
| `lan`  | LAN IP of your NAS, used at home                     |

The host is also written into the app's deep-link intent filter at build time (`${archeHost}`).

## Build
Requirements: JDK 17+, Android SDK (platform 35, build-tools 35.0.0), Gradle 8.10.

```sh
gradle assembleDebug     # test APK, signed with the debug key
./build.sh               # release APK -> dist/ (needs signing.properties, see below)
./build.sh --bump        # same, with the version number incremented
```

### Signing
The signing key is **never** in the repository. Create a `signing.properties` file at the project root
(it is git-ignored), or point to another file with the `ARCHE_SIGNING` environment variable:

```properties
storeFile=/path/to/your-keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Keep the same key for every release: Android refuses to update an app that is signed with another key.

### Test variant
The `qa` build type targets a local test server (`10.0.2.2:8999`, with a test certificate under
`app/src/qa`). It is meant for emulator testing and is not for release.

## Licence
MIT (see `LICENSE`).
