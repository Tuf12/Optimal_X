# OPTIMALX_LINK.md

**Status:** Draft — 2026-05-26
**Owners:** data layer, settings UI, desktop app
**Related:** [FILES_AND_MEDIA.md](FILES_AND_MEDIA.md), [PANEL_WORKSHOP.md](PANEL_WORKSHOP.md), [DATA_MODEL.md](DATA_MODEL.md), [EIDOS_INDEX.md](EIDOS_INDEX.md)
**Implementation plan:** [OPTIMALX_LINK_IMPLEMENTATION_PLAN.md](../implementation/OPTIMALX_LINK_IMPLEMENTATION_PLAN.md)

This is the **single source of truth** for moving bulk data into and out of OptimalX. Any other doc that refers to "backup", "export", "import", ".zip", "restore", or "snapshot" at a system level must conform to this spec. Per-note share-sheet export and the Files Panel single-file picker are separate, narrower flows and stay as-is.

---

## What this is

**OptimalX Link** is a paired-system bridge between the OptimalX Android app and a small companion desktop app on Linux Mint. Both sides run a tiny HTTP server scoped to the moment the user is using the feature. Either side can push or pull a **full snapshot** of the OptimalX state. No automatic syncing, no per-file shuffling, no cloud — manual, full-state, point-in-time, both directions.

```
+---------------------------+              +---------------------------+
|  OptimalX (Android)       |              |  OptimalX Link (PC)       |
|                           |              |                           |
|   Settings → Link screen  |              |   PySide6 GUI             |
|   Ktor server (in-screen) | <==LAN HTTP==>   Python HTTP server     |
|                           |              |                           |
|   Buttons:                |              |   Snapshot library on FS  |
|     - Push to desktop     |              |     ~/OptimalX-Link/      |
|     - Import from desktop |              |        snapshots/         |
|     - Confirm restore     |              |          2026-05-26_…/    |
|                           |              |             manifest.json |
|                           |              |             database/     |
|                           |              |             files/        |
+---------------------------+              +---------------------------+
```

---

## Why this exists

Android sandboxes the app's `filesDir` so file managers and other apps cannot see what's in `/data/data/com.example.optimalx/files/`. Two consequences hurt the user today:

- The OptimalX `.zip` backup only flows through the Storage Access Framework (`Settings → Data backup → Export / Import`). One channel. Easy to forget.
- When the database loses rows (migration bug, accidental clear), the user has no in-app way to inspect what's still on disk, and the AI relationship (conversations, memory rollover, tag hints) goes with it.

OptimalX Link gives the user a desktop-side **library of full snapshots** they can browse with the system file manager, restore from at any time, and refresh on demand — with a single shared archive format used by every channel.

---

## What a "snapshot" contains

A full snapshot is **everything OptimalX has on disk**. Nothing else is needed to faithfully restore the app:

| Path inside snapshot | Source on phone | Contains |
|----------------------|-----------------|----------|
| `manifest.json` | generated | format version, app version, db version, timestamp |
| `database/optimalx.db` | `filesDir/../databases/optimalx.db` | folders, subfolders, notes (full text), conversations, chat messages, memory rollover summaries (daily + long-term), tag hints, semantic vector embeddings, content checkpoints, pending changes, file references, custom panel assignments |
| `files/optimalx_files/...` | `filesDir/optimalx_files/` | every attached PDF, image, doc, etc. |
| `files/workshop/<subfolderId>/...` | `filesDir/workshop/` | every workshop project's source files (`.html`, `.js`, `.css`) |

The Eidos relationship — every conversation, every memory rollover, every tag hint — lives inside `optimalx.db`. A snapshot preserves it bit-for-bit.

### Snapshots on the desktop are folders, not zips

On the PC, each snapshot lives as a **real directory** under `~/OptimalX-Link/snapshots/<timestamp>/`. You can open any workshop project in your editor, read the `manifest.json`, drag attachments to other apps — all using Linux Mint's normal file tools. Conversion to/from a `.zip` only happens at the wire when streaming over HTTP.

```
~/OptimalX-Link/
└── snapshots/
    ├── 2026-05-26_15-40-12/
    │   ├── manifest.json
    │   ├── database/
    │   │   └── optimalx.db
    │   └── files/
    │       ├── optimalx_files/
    │       └── workshop/
    └── 2026-05-26_18-02-44/
        └── ...
```

---

## Surfaces

### 1. Archive format (canonical, every channel shares this)

Inside the wire, snapshots travel as a `.zip` with this layout:

```
manifest.json
database/optimalx.db
files/optimalx_files/...
files/workshop/<subfolderId>/...
```

`manifest.json`:

```json
{
  "formatVersion": 1,
  "exportEpochMs": 1716800000000,
  "appVersionName": "1.0",
  "dbVersion": 18,
  "includesFiles": true
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `formatVersion` | yes | Bumped only on archive-shape changes. `1` today. |
| `exportEpochMs` | optional | Defaults to `0`. |
| `appVersionName` | optional | Defaults to `""`. |
| `dbVersion` | optional | Defaults to `0`. Used by import to gate older-than-current restores. |
| `includesFiles` | optional | Defaults to `false`. Reserved for future DB-only archives. |

Forward-compatibility rules:
- New optional fields ship with defaults so older builds can still parse newer manifests.
- Unknown keys are ignored (Kotlin `Json { ignoreUnknownKeys = true }`).
- Renaming or removing a field requires bumping `formatVersion` and writing a migrator.

### 2. SAF channel (kept, unchanged in concept)

`Settings → Data backup`
- `Export backup (.zip)` — writes via `contentResolver.openOutputStream(uri, "wt")` (truncating, see *Notes on past defects* below). Picks destination via `CreateDocument`.
- `Import backup (.zip or .db)` — opens via `OpenDocument`, sniffs header, routes to ZIP or raw-DB import.

This channel **stays available** as the offline / no-PC fallback. Uses the same archive core as the Link channel.

### 3. Phone Link screen (new)

A new entry under `Settings → OptimalX Link`. While this screen is open:

- A Ktor HTTP server runs on the device's Wi-Fi IP at a configurable port (default `17832`).
- The screen shows: server status, IP, port, bearer token, QR code, recent request log.
- Two action buttons:
  - **Push snapshot to desktop** — builds a snapshot in `cacheDir`, POSTs it to the configured desktop URL, then deletes the cache copy.
  - **Import from desktop** — fetches the desktop's snapshot list, lets the user pick which one to restore (defaults to "latest"), downloads it, applies via the shared restore pipeline after a confirm tap.
- A persistent label: *"Server running — only while this screen is open."*

Leave the screen → the server stops, the port is released, the token is invalidated. There is **no foreground service**, no notification, no battery drain when the screen is closed.

There is **no phone-side staging directory.** All transfers use a transient `cacheDir` buffer that is cleaned up after each operation.

### 4. Desktop app (OptimalX Link, PySide6)

A small Linux desktop application that combines a GUI and a tiny HTTP server.

**GUI surface:**
- Connection panel: phone host:port, bearer token (paste once, stored in `~/.config/OptimalX-Link/config.json`), connection status, "Test connection" button.
- Snapshot library: list of every snapshot folder under `~/OptimalX-Link/snapshots/`, sorted newest first, with timestamp, app version, DB version, size, project count.
- Per-snapshot actions: **Restore to phone**, **Open in Files**, **Rename**, **Delete**.
- Top-of-window buttons: **Update from phone** (pull a fresh snapshot now), **Browse phone** (read-only file tree of the phone's `files/` and DB metadata, optional v2).
- Activity log: last 50 transfers + errors.

**Server surface:**
- Runs while the desktop app is open, on `0.0.0.0:<port>` (default `17833`, configurable).
- Bearer token (generated once, persisted in the config) — distinct from the phone's token.
- Endpoints in §5.

### 5. HTTP endpoint surface (v1)

Both sides run small, symmetric servers. Endpoints are **versioned** (`/v1/...`). Both sides accept the same `Authorization: Bearer …` header.

**Phone server endpoints** (in-screen):

| Method | Path | Body | Returns |
|--------|------|------|---------|
| `GET` | `/v1/status` | – | `{appVersion, dbVersion, formatVersion, linkVersion, dbFileSize, workshopProjectCount, attachmentCount}` |
| `GET` | `/v1/bundles/full` | – | streams full snapshot `.zip` (calls `writeBackupZip` directly into the response) |
| `POST` | `/v1/restore/full` | snapshot `.zip` | streams body to `cacheDir`, validates via `extractBackupZip + planRestore`, then surfaces an on-device confirm prompt. Responds `202 {jobId}`. |
| `GET` | `/v1/jobs/{id}` | – | progress (`PENDING_CONFIRM` / `RUNNING` / `OK` / `REJECTED` / `FAILED`) |

**Desktop server endpoints** (while app is open):

| Method | Path | Body | Returns |
|--------|------|------|---------|
| `GET` | `/v1/status` | – | `{linkVersion, snapshotCount, latestSnapshotEpochMs}` |
| `GET` | `/v1/snapshots` | – | array of `{id, epochMs, appVersion, dbVersion, sizeBytes}` |
| `GET` | `/v1/snapshots/{id}/bundle` | – | streams the snapshot directory as a `.zip` |
| `POST` | `/v1/snapshots` | snapshot `.zip` | extracts into `~/OptimalX-Link/snapshots/<timestamp>/`, returns the new `{id, epochMs}` |
| `DELETE` | `/v1/snapshots/{id}` | – | removes that snapshot folder |

That's the entire wire surface. Five phone endpoints, five desktop endpoints, all over HTTP on the LAN with bearer auth.

### 6. Flows

**Refresh from phone (the common case):**

1. User opens OptimalX Link on the PC.
2. User opens `Settings → OptimalX Link` on the phone (server starts).
3. User clicks **Update from phone** in the desktop GUI.
4. Desktop calls `GET phone:17832/v1/bundles/full`.
5. Desktop streams response into `~/OptimalX-Link/snapshots/<timestamp>/`, extracted on the fly.
6. New entry appears at the top of the snapshot library.

**Restore a snapshot to phone:**

1. User picks a snapshot in the desktop library and clicks **Restore to phone**.
2. Desktop POSTs the zipped snapshot to `phone:17832/v1/restore/full`.
3. Phone validates the manifest + writes the body to `cacheDir`.
4. Phone surfaces a full-screen confirm prompt: *"Restore snapshot from <desktop-IP> taken <timestamp>? This replaces your current data."* with Accept / Reject.
5. On Accept, phone applies via the shared restore pipeline and responds `OK` on `/v1/jobs/{id}`.

**Pull from phone:**

1. User taps **Import from desktop** on the phone Link screen.
2. Phone calls `GET desktop:17833/v1/snapshots` — list shown in a picker.
3. User picks one (or "latest" by default).
4. Phone calls `GET desktop:17833/v1/snapshots/{id}/bundle`, streams to `cacheDir`, validates.
5. Same confirm prompt as above.
6. On Accept, restore applies.

**Push from phone:**

1. User taps **Push snapshot to desktop** on the phone Link screen.
2. Phone builds a snapshot to `cacheDir/snapshot.zip` via `writeBackupZip`.
3. Phone POSTs to `desktop:17833/v1/snapshots`.
4. Desktop extracts under `~/OptimalX-Link/snapshots/<timestamp>/`.
5. Desktop GUI adds it to the library list.

All four flows are **manual, full-state, point-in-time.** No incremental, no auto, no per-file decisions.

### 7. Cloud (future)

Cloud sync is **not** part of v1. When it lands, the cloud channel will reuse the same archive format and the same restore pipeline, with the cloud bucket replacing the desktop app as the snapshot library. No format changes.

---

## Recovery: the workshop-wipe case

A scenario this design exists to make routine to recover from:

1. The phone DB drops workshop rows (migration, panel bug, manual clear).
2. User opens OptimalX Link on PC.
3. The desktop library still contains a snapshot from before the wipe.
4. User clicks **Restore to phone** on that snapshot.
5. Confirm tap on phone, restore applies, workshop and Eidos memory both come back intact.

For the very first failure (no snapshot yet) the user can use **Salvage orphans** inside `Settings → OptimalX Link` to scan `files/workshop/<id>/` for directories whose `subfolders` row is missing and re-link them. Same scan runs for `files/optimalx_files/`. That is a phone-only operation, no transfer involved.

---

## Security model

| Concern | Mitigation |
|---------|-----------|
| Random LAN host hits the phone server | Bearer token required, rotated each time the Link screen opens. Server only listens while the screen is open. |
| Random LAN host hits the desktop server | Bearer token required, persisted in `~/.config/OptimalX-Link/config.json` with `chmod 600`, regeneratable from the GUI. |
| Stolen token used to wipe phone | Every `/v1/restore/full` requires an on-device confirm tap before applying. Body sits in `cacheDir` until accepted or 5-minute timeout. |
| Stolen token used to fill the PC with junk | Desktop rejects payloads larger than configurable limit (default 1 GB). Caller IP is logged. |
| Cellular exposure | Phone server only binds while connected to Wi-Fi by default. Cellular bind is a separate opt-in toggle. |
| Path traversal | Every path is canonicalised and required to live under one of the snapshot roots. Snapshot IDs are pure timestamps + alphanumeric — no slashes. |
| DB corruption mid-restore | Phone renames current `optimalx.db` to `optimalx.db.bak` before overwriting. `.bak` is removed on next successful open. |
| Mid-restore power loss | Each restore is atomic per-file using `File.renameTo` after the full body is verified. Failure leaves the original DB intact. |

---

## Shared archive core

Every channel calls the same Kotlin (phone) and Python (desktop) functions:

**Kotlin (phone):**

```
internal fun writeBackupZip(
    out: OutputStream,
    manifest: BackupManifest,
    dbFile: File,
    attachmentsDir: File?,
    workshopDir: File?,
)

internal data class ExtractedBackup(
    val root: File,
    val manifestFile: File?,
    val entryNames: List<String>,
)

internal fun extractBackupZip(input: InputStream, destDir: File): ExtractedBackup

internal fun planRestore(
    extracted: ExtractedBackup,
    hostSchemaVersion: Int,
): RestorePlan  // sealed: Ok(manifest, dbSource, effectiveRoot) | Failure(message)
```

**Python (desktop):**

```python
def write_snapshot_zip(out_stream, snapshot_dir: Path) -> None: ...
def extract_snapshot_zip(in_stream, dest_dir: Path) -> ExtractedSnapshot: ...
def read_manifest(snapshot_dir: Path) -> Manifest: ...
```

The Python side only ever writes / reads / catalogs snapshots. It never mutates a phone DB directly. Anything that actually applies a restore goes back over the wire to the phone's `/v1/restore/full`, which runs the canonical Kotlin pipeline.

---

## What conforms, what doesn't

| Surface | Conforms via |
|---------|--------------|
| `Settings → Data backup → Export` | Calls `writeBackupZip`; channel = SAF. |
| `Settings → Data backup → Import` | Calls `extractBackupZip` + `planRestore`; channel = SAF. |
| `Settings → OptimalX Link → Push to desktop` | Calls `writeBackupZip`, channel = HTTP POST to desktop. |
| `Settings → OptimalX Link → Import from desktop` | Calls `extractBackupZip` + `planRestore`, channel = HTTP GET from desktop. |
| Desktop **Update from phone** | Calls phone `GET /v1/bundles/full`, extracts into snapshot folder. |
| Desktop **Restore to phone** | POSTs snapshot zip to phone `/v1/restore/full`. |
| Salvage orphans (in-app, phone only) | Scans `files/workshop/<id>/` + `files/optimalx_files/`; no transfer. |
| Per-note "Export note" (editor menu) | **Separate flow**, single text payload via Android share sheet. Not part of Link. |
| Files Panel "Import" button | **Separate flow**, single-file picker into one subfolder. Not part of Link. |

If anyone proposes another bulk-transfer surface (cloud sync, ADB hook, Eidos tool, scheduled job), it routes through the shared archive core. If it can't, the spec needs updating first.

---

## Notes on past defects this design closes

### SAF overwrite truncation

`contentResolver.openOutputStream(uri)` defaulted to mode `"w"`, which on many SAF providers does **not** truncate the existing file. Overwriting a larger previous `.zip` with a smaller new export left trailing bytes from the old file, producing a corrupt archive that import would fail with "missing manifest". The fix in `OptimalXBackupManager.export` is to request `"wt"` explicitly with a fallback to the default. The same lesson applies to any future SAF write path.

### Silent manifest discovery

The original import only matched `manifest.json` at the exact zip root. When `.zip`s got re-archived by a third-party tool inside a wrapper folder, the manifest was technically present but unreachable. The current import walks the extracted tree and accepts the manifest at any depth, with the effective root inferred from the manifest's parent directory. Any new channel must use `extractBackupZip` so this leniency is inherited.

### Failure messages

Before this spec, every restore failure said "missing or invalid manifest". The current code distinguishes:
- Missing manifest entry (lists the entries actually found)
- Empty manifest file
- Parse-error manifest (includes the underlying error message)
- Missing database file
- Backup newer than the running app

All channels must keep this granularity — the HTTP responses simply marshal the same `RestorePlan.Failure` reason into the response body.

---

## Out of scope for v1

- Cloud sync transport.
- Per-row JSON export (export the SQLite tables as JSON files). Possible later under `/v1/json/...`.
- Diff-only / incremental archives. Snapshots are always full point-in-time.
- Multi-user / role-based access on the servers. Single bearer token per side, single paired device.
- mDNS / zeroconf discovery.
- Self-signed HTTPS. Plain HTTP on LAN with bearer token.
- Browser UI served from the device. PySide6 desktop GUI only on the PC.
- Phone-side staging directory. Removed by design — every transfer uses a transient `cacheDir`.

---

## Document changelog

| Version | Date | Notes |
|---------|------|-------|
| 1 | 2026-05-26 | Initial OptimalX Link spec — supersedes the earlier "Dump Zone" draft. Phone-side staging removed; both sides run small HTTP servers; PySide6 desktop app keeps the snapshot library as plain folders. |
