# Smart Clipboard — Build, Configuration & Release Guide

## 1. Architecture Overview
- **Local-First Persistence**: Room SQLite (`SmartClipboardDatabase`) with indexed queries, soft-delete Trash, and AES-256-GCM encryption via `AndroidKeyStore` (`KeystoreCryptoManager`) for sensitive clips and integration tokens.
- **Sync Engine**: Independent `SyncEngine` + WorkManager `SyncWorker` (`@HiltWorker`) coordinating `IntegrationProvider` implementations (`TelegramProvider`, `DiscordProvider`, `GoogleDriveProvider`) without blocking local clipboard operations.
- **Zero Keyboard / IME Dependency**: Built strictly as a central clipboard vault and Android Share Sheet / `PROCESS_TEXT` hub.

## 2. Building & Testing
Run unit and Robolectric JVM tests:
```bash
gradle :app:testDebugUnitTest
```

Build Debug APK:
```bash
gradle :app:assembleDebug
```

Build Release APK:
```bash
gradle :app:assembleRelease
```
Outputs are generated under `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`.

### GitHub Actions Automated APK Builds
The repository includes `.github/workflows/android-apk.yml`:
- Automatically decodes `debug.keystore.base64` -> `debug.keystore` on CI, runs `:app:testDebugUnitTest`, and builds `SmartClipboard-debug.apk`.
- Uploads the installable APK under **Actions → Artifacts**, and publishes APK assets directly to **GitHub Releases** when pushing version tags (`v*`).
- Optionally builds a signed `SmartClipboard-release.apk` when `RELEASE_KEYSTORE_BASE64`, `STORE_PASSWORD`, and `KEY_PASSWORD` GitHub Actions secrets are configured (using key alias `upload`).

## 3. External Integration Setup (User-Configured at Runtime)
No Bot Tokens, Webhook URLs, or OAuth secrets are hardcoded in source control.

### Telegram Bot
1. Create a bot via `@BotFather` in Telegram to obtain a Bot Token.
2. Open your new bot chat in Telegram and send `/start` (a bot cannot access your other private chats).
3. In **Smart Clipboard → Integrations → Telegram**, enter the Bot Token, tap **Detect ID** (or enter your Chat ID), and tap **Save & Test**.

### Discord Workspace
- **Outgoing clips (Send only)**: Create a Channel Webhook in Discord Server Settings → Integrations → Webhooks, and paste the Webhook URL into Smart Clipboard.
- **Incoming clips (Receive)**: Webhooks cannot read messages. To pull messages from a channel, configure an authorized Discord **Bot Token** with `View Channel` + `Read Message History` intents/permissions alongside the **Channel ID**.

### Google Drive Backup & Sync (OAuth 2.0)
- Uses Google Play Services `AuthorizationClient` with minimal scopes:
  - `https://www.googleapis.com/auth/drive.appdata` (private App Data Folder by default)
  - `https://www.googleapis.com/auth/drive.file` (only when selecting or creating a visible Drive folder)
- Ensure your Google Cloud project has an **Android OAuth 2.0 Client ID** registered for package `com.aistudio.smartclipboard.qvmkrt` and your signing key's SHA-1 fingerprint.
