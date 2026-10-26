# Security policy

## Supported versions

Only the latest release on the
[Releases page](https://github.com/mstipanov/NotifyTelegram/releases) is
supported. Older sideloaded builds should be updated.

## Reporting a vulnerability

Please **do not open a public issue** for a security problem. Use either:

- GitHub's private advisory form:
  https://github.com/mstipanov/NotifyTelegram/security/advisories/new
- Email: marko.stipanov@gmail.com

Include the app version, the Android version and a short reproduction. You can
expect an initial reply within a few days.

## What the app stores and sends

| Data | Where it goes | Protection |
| --- | --- | --- |
| Telegram bot token | Android `SharedPreferences`, encrypted | AES-256-GCM with a key held in the Android Keystore; the key never leaves the device. Android backup is disabled. |
| Destination chat id | Android `SharedPreferences` | Not a secret; it is a Telegram chat identifier. |
| Notification title/text/sender/channel | The configured Telegram chat | Sent over HTTPS to `api.telegram.org` only. |
| Selected package names | Android `SharedPreferences` | Not a secret. |

The app has **no analytics, telemetry, ads or third-party SDKs**. It does not
send data anywhere except the Telegram Bot API.

## Permissions

- `INTERNET` — send messages to the Telegram Bot API.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — keep background sends prompt (optional,
  requested only when the user taps the button).
- Notification-listener binding (`BIND_NOTIFICATION_LISTENER_SERVICE`) — granted
  explicitly by the user in system settings. The app sees every notification but
  forwards only the selected packages.

Cleartext HTTP is disabled. Release builds are signed with a private key that is
not in the repository; debug builds use the standard debug key.

## Revoking access

- Revoke the bot with BotFather (`/revoke`) at any time.
- Revoke notification access in Android Settings → Notifications → Device &
  app notifications → Special access → Notification access.
- Uninstalling the app removes its preferences and the Keystore key.
