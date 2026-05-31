# Android Auto Backup (Google Drive)

**Status:** Configured  
**Scope:** Platform auto backup / restore only — no Kotlin changes.

---

## Purpose

Ensure OptimalX user content is included in **Android Auto Backup** (restored from the user’s Google account on reinstall or a new device with the same Google sign-in).

This document does **not** cover:

| Mechanism | Where it lives |
|-----------|----------------|
| **Settings → Export / Import backup** (`.zip` via SAF) | `OptimalXBackupManager` — see [FILES_AND_MEDIA.md](architecture/FILES_AND_MEDIA.md) |
| **OptimalX Link** (LAN snapshot with desktop app) | [OPTIMALX_LINK.md](architecture/OPTIMALX_LINK.md) |

Those are separate, user-initiated or Link-scoped flows. Google Drive auto backup is handled entirely by the OS from XML rules in `res/xml/`.

---

## What gets restored

| Data | On-device path | Notes |
|------|----------------|-------|
| Room database | `databases/optimalx.db` (+ `-wal`, `-shm`) | `AppDatabase.DATABASE_NAME` — folders, notes, chat, memory, semantic index, checkpoints, file references |
| Imported attachments | `files/optimalx_files/<subfolderId>/…` | PDFs, images, docs per subfolder |
| Workshop projects | `files/workshop/<subfolderId>/…` | Panel Workshop HTML/JS/CSS |
| In-app settings (non-secret) | `files/datastore/*.preferences_pb` | DataStore: `settings`, `theme_preferences`, `seed_preferences` |

After restore, the user **re-enters API keys** in Settings. That is expected and intentional.

---

## What must not be backed up

### API keys (required exclude)

Provider API keys live in **EncryptedSharedPreferences** (`optimalx_api_keys`). They must **not** be included in auto backup:

- Keys are bound to the device keystore; restoring them on another device or after reinstall has caused **failed backup runs** in the past.
- Excluding them keeps auto backup reliable; users add keys again after restore.

### Whisper voice models (recommended exclude)

Downloaded Whisper binaries live under `files/models/`. They are large, re-installable from Settings, and can push the app over the **25 MB** auto-backup cap. Exclude them so backup budget stays on notes, DB, attachments, and workshop files.

### Cache (explicit exclude)

`cache/` and `external-cache/` — exclude for clarity (OS often skips these anyway).

---

## 25 MB platform limit

- Android Auto Backup is capped at **~25 MB per app** (cloud backup).
- Over the cap, Android **skips** backup silently — it does not truncate.
- Heavy libraries (many attachments, large workshop trees) may outgrow auto backup; use **Settings → Export backup** or **OptimalX Link** for full snapshots.

---

## Current repo state

Configured in `AndroidManifest.xml` (`<application>`):

- `android:allowBackup="true"`
- `android:fullBackupContent="@xml/backup_rules"`
- `android:dataExtractionRules="@xml/data_extraction_rules"`

Backup rules live in `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` (same include/exclude in both). Do not add a second `<application>` tag or duplicate manifest attributes.

---

## Rules reference

Use the **same include/exclude rules** in both XML files (API 31+ cloud backup reads `data_extraction_rules.xml`; older paths still use `backup_rules.xml`).

### `backup_rules.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>

    <!-- Core app data -->
    <include domain="database" path="optimalx.db" />
    <include domain="file" path="optimalx_files/" />
    <include domain="file" path="workshop/" />
    <include domain="file" path="datastore/" />

    <!-- Secrets & re-downloadable blobs -->
    <exclude domain="sharedpref" path="optimalx_api_keys.xml" />
    <exclude domain="file" path="models/" />

    <!-- Cache -->
    <exclude domain="cache" path="." />
    <exclude domain="external-cache" path="." />

</full-backup-content>
```

### `data_extraction_rules.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>

    <cloud-backup>
        <include domain="database" path="optimalx.db" />
        <include domain="file" path="optimalx_files/" />
        <include domain="file" path="workshop/" />
        <include domain="file" path="datastore/" />

        <exclude domain="sharedpref" path="optimalx_api_keys.xml" />
        <exclude domain="file" path="models/" />

        <exclude domain="cache" path="." />
        <exclude domain="external-cache" path="." />
    </cloud-backup>

    <device-transfer>
        <include domain="database" path="optimalx.db" />
        <include domain="file" path="optimalx_files/" />
        <include domain="file" path="workshop/" />
        <include domain="file" path="datastore/" />

        <exclude domain="sharedpref" path="optimalx_api_keys.xml" />
        <exclude domain="file" path="models/" />

        <exclude domain="cache" path="." />
        <exclude domain="external-cache" path="." />
    </device-transfer>

</data-extraction-rules>
```

With explicit `<include>` rules, only listed domains are backed up. Widget `SharedPreferences` (`widget_prefs`) are not included; that is acceptable (widget re-bind picks a conversation again).

---

## Behavior after rules are applied

- Android backs up on its own schedule (typically idle + charging + Wi‑Fi).
- Backup is stored in the user’s Google account under **App data** (not browsable like Drive files).
- On reinstall, restore runs **before** first launch when backup transport is available.
- Room runs migrations on the restored DB the same as after a normal app upgrade.
- No Kotlin or new dependencies.

---

## Testing

1. Install a debug/release build on a device signed into Google.
2. Create real data: folder, note, attachment or workshop file.
3. Force backup: `adb shell bmgr backupnow com.example.optimalx` (or `adb shell bmgr run` for a full pass).
4. Uninstall the app.
5. Reinstall from the same signing key — content should appear on first open; API keys will be empty until entered in Settings.

Logcat tag `BackupManagerService` helps confirm backup/restore. `adb shell dumpsys backup` shows last backup size and transport errors.

---

## Notes for agents

- **Do not** add Kotlin for auto backup unless product requirements change.
- **Do not** include `optimalx_api_keys` in backup rules.
- **Do not** conflate this with OptimalX Link or `OptimalXBackupManager` zip export.
- **Do** keep `backup_rules.xml` and `data_extraction_rules.xml` in sync.
- If auto backup still fails on a test device, check total payload size (25 MB) and logcat for transport errors before changing includes.
