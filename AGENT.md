# AGENT.md

Instructions for AI agents working in this repository.

## What this repo is

- `app/` — Android app (Kotlin, no third-party dependencies). A
  `NotificationListenerService` forwards notifications from user-selected
  packages to a Telegram chat or group. There is no reply path, no polling
  service and no foreground service.
- `CHANGELOG.md` — user-facing changelog, in **English**. One `## <version>`
  section per release.
- `README.md` — setup, message format, security notes and the release loop.
- `SUPPORT.md` — Buy Me a Coffee / PayPal footer. It is appended to every
  GitHub release's notes.
- Distribution is **GitHub Releases**: each version is tagged, built with
  `:app:assembleRelease`, and attached as `NotifyTelegram-<version>.apk`. A local
  AndroidAppDistributor mirror serves the same release APK at
  `https://notify-telegram.sting.hr`.

## Branches

`master` is the stable line. For anything larger than a one-line fix, create a
branch first and merge it into `master` before releasing. This project has no
beta channel; if one is added, it must use a separate build type and a separate
output directory so a beta APK can never be picked up as the stable release.

## The rule: after every change

Every user-visible change must go through this loop **before you consider the
task done**:

1. Bump the version.
2. Update `CHANGELOG.md`.
3. Test and build.
4. Publish the release.

Skipping the version bump means the change is **not available for download**.

## Step 1 — Bump the version

Edit `app/build.gradle.kts` (`defaultConfig`):

```kotlin
versionCode = 3      // always +1
versionName = "1.2"  // increment the last segment, e.g. 1.1 -> 1.2
```

Keep `versionCode` and `versionName` in lockstep with the newest
`## <version>` section in `CHANGELOG.md`.

## Step 2 — Update `CHANGELOG.md`

- Add a new section at the **top**, below `# Notify → Telegram`.
- Never edit or renumber a section that already shipped; only add a new one.
- One bullet per user-visible change, matching the existing style.

## Step 3 — Test and build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleRelease
```

- Output: `app/build/outputs/apk/release/NotifyTelegram-<versionName>.apk`.
- Verify the signature before publishing:

  ```sh
  "$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)/apksigner" \
    verify --print-certs app/build/outputs/apk/release/NotifyTelegram-<versionName>.apk
  # certificate DN: CN=Marko Stipanov, O=NotifyTelegram, C=HR
  ```

- Install it on the emulator and smoke-test the setup screen. A debug build has a
  different signature, so `adb uninstall hr.sting.notifytelegram` before switching
  between debug and release.

## Step 4 — Publish the release

Create the GitHub Release: tag = `versionName`, title =
`NotifyTelegram-v<versionName>`, asset = the release APK. The notes are the
version's `CHANGELOG.md` section **followed by the support footer in
`SUPPORT.md`**, so every release carries the Buy Me a Coffee / PayPal links.

```sh
# Release notes = this version's CHANGELOG.md section + the support footer.
cat > /tmp/notify-telegram-notes.md <<'EOF'
<the 1.2 section of CHANGELOG.md>
EOF
cat SUPPORT.md >> /tmp/notify-telegram-notes.md

gh release create 1.2 app/build/outputs/apk/release/NotifyTelegram-1.2.apk \
  --title "NotifyTelegram-v1.2" --latest --notes-file /tmp/notify-telegram-notes.md
```

Done when the release appears at
https://github.com/mstipanov/NotifyTelegram/releases/latest with the APK attached
and the support footer.

The local distributor rescans its APK directory on every request, so a rebuilt
release is published without restarting it:
`app/build/outputs/apk/release` is configured in
`~/IdeaProjects/AndroidAppDistributor/apps.properties`.

## Signing

Release builds are signed with a private key that is **not** in this repository.

- Keystore: `~/.android/keystores/notifytelegram-release.jks` — PKCS12, alias
  `notifytelegram`, RSA 4096, valid ~27 years.
- Credentials: `keystore.properties` at the repo root, **gitignored**. It holds
  `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
- `app/build.gradle.kts` reads that file when it exists and leaves release
  builds unsigned when it does not, so a fresh checkout still configures.

**Back up the keystore and its password somewhere safe.** If they are lost you
can never update an installed app again: Android rejects an APK whose signature
differs from the installed one. Never commit `keystore.properties`, `*.jks` or
`*.keystore` — they are in `.gitignore` for exactly this reason.

## Security invariants

Do not regress these without a very good reason and a changelog entry:

- The bot token is encrypted with [SecretStore] (AES-256-GCM in the Android
  Keystore). Never store it as plaintext, log it, include it in an exception
  message or put it in a build config/string resource.
- `android:allowBackup="false"` and `android:usesCleartextTraffic="false"` stay in
  the manifest.
- Only `MainActivity` is a launcher activity; `AppPickerActivity` is not
  exported. The notification listener is exported only because the system binds
  it, and it is protected by `BIND_NOTIFICATION_LISTENER_SERVICE`.
- The app forwards only package names in `Prefs.packages` and only to the
  configured chat. Do not add wildcard forwarding or a public webhook.
- Run a secrets scan before every push: no token, password, keystore or
  `local.properties` in the tree.

## Emulator smoke test

```sh
"$ANDROID_HOME/emulator/emulator" -avd pixel36 -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot &
adb wait-for-device
adb install -r app/build/outputs/apk/release/NotifyTelegram-<version>.apk
adb shell cmd notification allow_listener \
  hr.sting.notifytelegram/hr.sting.notifytelegram.NotifyNotificationListener
adb shell am start -n hr.sting.notifytelegram/.MainActivity
adb exec-out screencap -p > /tmp/notify-telegram.png
```

Post a test notification from the shell (`adb shell cmd notification post -t
"Test" tag "Hello"`), add `com.android.shell` in *Choose apps*, and check the
in-app log for a forward attempt.
