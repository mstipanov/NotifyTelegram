# Notify → Telegram

[![Latest release](https://img.shields.io/github/v/release/mstipanov/NotifyTelegram?sort=semver&label=release)](https://github.com/mstipanov/NotifyTelegram/releases/latest)
[![License: MIT](https://img.shields.io/github/license/mstipanov/NotifyTelegram)](LICENSE)

An Android app that forwards notifications from **the apps you choose** to a
Telegram chat or group. Sideload only, open source, no ads and no tracking.

This is the read-only successor to the Outlook/Slack mirror in my earlier
Duo → Telegram app: no Duo approvals, no reply path, no long-polling connection,
no foreground service and no wake lock. The phone can stay locked and the screen
off.

```
Selected app posts a notification
        │
        ▼
NotificationListenerService (NotifyNotificationListener)
        │  reads title / text / sender / channel, de-duplicates updates
        ▼
NotificationForwarder ──► Telegram Bot API (sendMessage)
        │
        ▼
Your chat or group: readable line + optional JSON block
```

## Setup

1. **Install** the APK from [Releases](https://github.com/mstipanov/NotifyTelegram/releases/latest)
   (see *Download and releases* below) and open the app.
2. **Grant notification access**: tap *Open notification access settings* and
   enable **Notify → Telegram**.
3. **Create a Telegram bot** with [@BotFather](https://t.me/BotFather) and paste
   its token into the app. A dedicated bot is recommended: the capture step uses
   `getUpdates`, and two consumers (for example an old relay still running with
   the same token) would compete for those updates.
4. **Send the bot a message** — `/start` in a private chat, or
   `/start@YourBot` in a group — then tap **Test**. The app verifies the bot,
   captures the chat id from the newest received message, saves it and sends a
   test message. A group chat id can also be pasted by hand.
5. **Choose apps**: tap *Choose apps*, tick the apps whose notifications should
   be forwarded (Outlook and Slack are preselected on first run), and go back.
   *Add package by name* covers apps without a launcher icon.
6. Recommended: **Disable battery optimization** so background sends are not
   deferred by Doze. No other permission is needed.

From then on every notification posted by a selected app is forwarded. There is
nothing to start or keep running: Android binds the listener itself and rebinds it
after a reboot. The master switch *Forward notifications* pauses forwarding
without revoking notification access.

## Message format

```
📨 Slack — #eng-team
Jane Doe: can you review the PR before standup?

{
  "source": "slack",
  "package": "com.Slack",
  "app": "Slack",
  "channel": "#eng-team",
  "sender": "Jane Doe",
  "title": "Jane Doe",
  "text": "can you review the PR before standup?",
  "subText": "",
  "postedAt": "2026-10-26T09:14:03Z"
}
```

The JSON block is controlled by *Include a JSON block in each message* and is
meant for an external agent that reads the destination chat. The readable part
always stays.

Group-summary rollups ("N new messages") are skipped, and notification updates
that keep the same content are de-duplicated. Slack updates a single
conversation notification as new messages arrive, so a busy thread produces one
Telegram message per content change, not one per keystroke.

## Diagnostics

* **Send current notifications to Telegram** snapshots every notification the
  listener can currently see (package, title, text, key) and marks selected
  packages with ✓forward. Use it when nothing arrives: entries marked
  ✓forward but no Telegram message means the bot token/chat id or the network is
  the problem; no ✓forward means the package is not selected.
* The in-app log on the main screen shows listener connections, forwarded items
  and send errors.

## Security & privacy

* **The bot token is encrypted at rest.** It is sealed with an AES-256-GCM key
  that lives in the Android Keystore; the key never leaves the device. A copied
  `SharedPreferences` file or a cloud backup is not enough to recover it.
* **Android backup is disabled** for the app (`allowBackup=false`), so the
  encrypted token and the chat id are not copied off the device.
* **The token is never logged.** The in-app log shows the bot username, the chat
  id and notification summaries, never the token.
* **No analytics, telemetry, ads or third-party SDKs.** The only network calls go
  to `https://api.telegram.org` over TLS; cleartext traffic is disabled.
* **Notification access is broad by design.** Android grants a notification
  listener access to every notification; the app only sends the ones from the
  packages you checked, and only to the chat configured in the app.
* **Release APKs are signed with a private key** kept outside this repository.
  Debug builds use the standard debug key and cannot install over a release build.

The token can be revoked at any time with BotFather (`/revoke`). See
[SECURITY.md](SECURITY.md) for the reporting policy.

## Limitations

* **Android 15+ sensitive notifications.** Notifications the system flags as
  sensitive (OTP/2FA codes) are redacted for ordinary notification listeners.
  Reading them requires `RECEIVE_SENSITIVE_NOTIFICATIONS`, a signature/role
  permission a sideloaded app cannot get, so it is not requested.
* **Delivery depends on the network.** During Doze a send may be delayed until
  the device wakes; exempting the app from battery optimization keeps it prompt.
  No wake lock is held.
* The listener is managed by the system. If a vendor ROM aggressively kills
  background apps, re-granting notification access or disabling the vendor's
  battery saver may be needed.

## Download and releases

Grab the latest APK from the
[Releases page](https://github.com/mstipanov/NotifyTelegram/releases/latest) —
`NotifyTelegram-<version>.apk`, signed with the project release key.

Each release is built from the tagged source and published by hand:

```sh
./gradlew :app:assembleRelease
gh release create <version> app/build/outputs/apk/release/NotifyTelegram-<version>.apk \
  --title "NotifyTelegram-v<version>" --latest --notes-file <notes>
```

`<version>` is the `versionName` in `app/build.gradle.kts` and the newest
`## <version>` section in `CHANGELOG.md`; `<notes>` is that section followed by
the support footer in `SUPPORT.md`, so every release carries the Buy Me a Coffee
and PayPal links. [AGENT.md](AGENT.md) documents the full loop.

The same release APK, version JSON and changelog are mirrored by the local
**AndroidAppDistributor** at `https://notify-telegram.sting.hr` (Cloudflare
Access); that page redirects to GitHub Releases. `/version` and `/apk/*` stay
public for scripted downloads.

## Building

Requires JDK 17+ and the Android SDK with platform 36.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/Library/Android/sdk

./gradlew :app:assembleRelease
# APK: app/build/outputs/apk/release/NotifyTelegram-1.1.apk
adb install -r app/build/outputs/apk/release/NotifyTelegram-1.1.apk

# development build (debug-signed):
./gradlew :app:assembleDebug
```

The project has **no third-party dependencies** beyond the Kotlin standard
library; networking uses `HttpURLConnection`, JSON uses the platform `org.json`
and secret encryption uses the Android Keystore, which keeps the build small and
offline-friendly.

### Release signing

Release builds read `keystore.properties` at the repo root:

```properties
storeFile=/absolute/path/to/notifytelegram-release.jks
storePassword=…
keyAlias=notifytelegram
keyPassword=…
```

The file and the keystore are **gitignored** and must never be committed. A
clean checkout without the file still configures; its release build is simply
left unsigned. The keystore and its password must be backed up: losing them means
installed users can never receive an update signed by the same key.

Debug and release builds have different signatures, so uninstall a debug build
before installing a release APK (and vice versa).

## Support

Notify → Telegram is free, with no ads or tracking. If it saves you time, you can
support its development:

- ☕ [Buy Me a Coffee](https://buymeacoffee.com/mstipanov)
- 💳 [PayPal](https://paypal.me/mstipanov)

## Source map

| File | Purpose |
| --- | --- |
| `MainActivity.kt` | Setup screen: notification access, bot token/chat id, app selection summary, options, support, log |
| `AppPickerActivity.kt` | Searchable list of installed apps with checkboxes |
| `NotifyNotificationListener.kt` | System notification listener; hands posts and removals to the forwarder |
| `NotificationForwarder.kt` | Extracts, de-duplicates and sends notifications to Telegram |
| `TelegramClient.kt` | Minimal Telegram Bot API client (sendMessage, getMe, getUpdates) |
| `Prefs.kt` | Configuration storage; encrypts the bot token and migrates the 1.0 plaintext format |
| `SecretStore.kt` | AES-256-GCM encryption backed by the Android Keystore |
| `Insets.kt` | Android 15+ edge-to-edge padding for the platform ActionBar screens |
| `LogBus.kt` | Shared in-app log |
| `Util.kt` | Telegram HTML escaping |

## License

[MIT](LICENSE) © 2026 Marko Stipanov. Not affiliated with Telegram or Duo.
