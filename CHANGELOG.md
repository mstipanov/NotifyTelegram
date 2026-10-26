# Notify → Telegram

## 1.1
- Security: the Telegram bot token is now encrypted with an AES-256-GCM key held in the Android Keystore, and Android backup is disabled. A token saved by 1.0 is migrated automatically and the plaintext copy is removed.
- Added a Support section in the app with Buy Me a Coffee and PayPal links.
- Published as an open-source project under the MIT license, with release-signed APKs on GitHub Releases.

## 1.0
- Initial release: forwards notifications from selected apps to a Telegram chat or group as a readable line plus an optional machine-parseable JSON block.
- Searchable app picker with checkboxes; Outlook and Slack are preselected on first run, and apps without a launcher icon can be added by package name.
- Chat id is captured automatically from the newest message the bot received, so no long-polling service is needed.
- Read-only by design: no inline buttons, no reply path, no foreground service and no wake lock. The phone can stay locked.
- Group-summary rollups are skipped and unchanged notification updates are de-duplicated.
- Diagnostics: forward outgoing notifications to Telegram on demand, with selected packages marked ✓forward, plus an in-app log.
