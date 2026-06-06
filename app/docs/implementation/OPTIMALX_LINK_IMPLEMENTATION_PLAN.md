# OPTIMALX_LINK — Implementation Plan

**Status:** Draft — 2026-05-26
**Architecture spec:** [OPTIMALX_LINK.md](../architecture/OPTIMALX_LINK.md)
**Related:** [FILES_AND_MEDIA.md](../architecture/FILES_AND_MEDIA.md), [DATA_MODEL.md](../architecture/DATA_MODEL.md), [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md)

Phased rollout of the snapshot bridge defined in `OPTIMALX_LINK.md`. The phone-side server is **scoped to the Link screen** — no foreground service. The desktop side is a PySide6 GUI + a small HTTP server, running while the desktop app is open.

Phases are independently shippable. Phase 1 has no user-visible change; Phases 2–6 each ship something the user can use directly.

---

## Decisions locked

| Topic | Decision |
|-------|----------|
| Layout | Both sides run a small HTTP server. Either side can initiate any operation. |
| Phone server lifetime | Runs only while `Settings → OptimalX Link` is the active screen. Closes on screen exit / app background. No foreground service, no notification. |
| Desktop server lifetime | Runs while the desktop app process is open. |
| Phone server engine | Ktor `3.x`, `CIO` engine (pure Kotlin, no Netty / JNI). |
| Desktop runtime | Python 3.12. |
| Desktop GUI | PySide6 (Qt 6, LGPL). |
| Desktop HTTP | `aiohttp` server (stdlib `asyncio`-based, single dependency). |
| Auth | Bearer token in `Authorization: Bearer …`. Phone token regenerated each Link-screen open; desktop token persisted in `~/.config/OptimalX-Link/config.json` (chmod 600), regeneratable from GUI. |
| Restore confirm | Every `/v1/restore/full` on the phone requires an on-device tap before applying. 5-minute timeout. |
| Archive format | Same `formatVersion = 1` zip used by `OptimalXBackupManager` today. No format bump. |
| Snapshots on disk | Plain folders under `~/OptimalX-Link/snapshots/<timestamp>/`. Conversion to/from `.zip` only on the wire. |
| Salvage scope | Workshop + attachments only in v1. |
| Cloud | Out of scope. |

---

## Phase 1 — Archive core refactor (no user-visible change)

Goal: split `OptimalXBackupManager` into a pure stream API + Android adapter so any channel (SAF, HTTP) calls the same code.

**Tasks**
1. Extract `internal fun writeBackupZip(out: OutputStream, manifest: BackupManifest, dbFile: File, attachmentsDir: File?, workshopDir: File?)`.
2. Extract `internal fun extractBackupZip(input: InputStream, destDir: File): ExtractedBackup`.
3. Add `internal fun planRestore(extracted: ExtractedBackup, hostSchemaVersion: Int): RestorePlan` (`sealed` Ok/Failure).
4. Rewrite current `export(context, uri, ...)` and `import(context, uri)` as thin wrappers that open the URI streams and delegate.
5. Move `BackupManifest` data class to `data/backup/BackupManifest.kt` so the HTTP layer can depend on it without pulling in Android types.

**Tests (JVM, no emulator)**
- Roundtrip: write `manifest + db bytes + attachments + workshop` to a `ByteArrayOutputStream`, read back through `extractBackupZip`, verify entry list, manifest fields, and file bytes match.
- Wrapped folder: pre-build a `.zip` where everything is nested under `wrapper/`; verify `extractBackupZip` + `planRestore` still find the manifest and DB.
- Missing manifest: zip with only `database/optimalx.db`; expect `RestorePlan.Failure` carrying the listed entries.
- Invalid manifest JSON: zip with `manifest.json` containing `not json`; expect `Failure(reason)` carrying the parse error.
- Forward compat: manifest JSON with extra unknown fields; parsing succeeds.
- Backward compat: manifest JSON missing optional fields; defaults applied.
- Format-version mismatch: `formatVersion: 2` → `Failure("Unsupported backup format")`.
- `dbVersion > hostSchemaVersion` → `Failure("Backup requires a newer app …")`.

**Acceptance**
- All new tests green.
- `Settings → Data backup → Export / Import` still works end-to-end on a device.
- No new dependencies yet (phase 1 is pure refactor).

---

## Phase 2 — Phone Link screen + Ktor server (read-only)

Goal: phone serves `/v1/status` and `/v1/bundles/full` from the Link screen.

**Tasks**
1. Add Ktor deps to `libs.versions.toml` and `app/build.gradle.kts`:
   - `io.ktor:ktor-server-core`
   - `io.ktor:ktor-server-cio`
   - `io.ktor:ktor-server-auth`
   - `io.ktor:ktor-server-status-pages`
   - `io.ktor:ktor-server-content-negotiation`
   - `io.ktor:ktor-serialization-kotlinx-json`
2. Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`.
3. `LinkServer` class: starts on `0.0.0.0:<port>`, default `17832`, configurable. Bearer auth via `Authentication { bearer { … } }`.
4. Endpoints:
   - `GET /v1/status` → `{appVersion, dbVersion, formatVersion, linkVersion, dbFileSize, workshopProjectCount, attachmentCount}`.
   - `GET /v1/bundles/full` → `PRAGMA wal_checkpoint(FULL)`, then stream `writeBackupZip` directly into the response body.
5. Compose screen `LinkScreen` (under `Settings → OptimalX Link`):
   - Status row: ON/OFF, IP, port, token (tap to reveal), QR code.
   - Wi-Fi-only policy toggle (default ON).
   - Token regenerate button.
   - Activity log (in-memory ring, last 50 lines).
   - Lifecycle: `LaunchedEffect` starts the server on entry, `onDispose` stops it.
6. Settings entry pointing into `LinkScreen`.

**Tests**
- Unit: token generator entropy + length.
- Integration (Ktor `TestApplication`): `/v1/status` requires bearer, returns expected shape, `/v1/bundles/full` streams a parseable zip.
- Manual: open Link screen, `curl -H "Authorization: Bearer …" http://<phone-ip>:17832/v1/status` returns JSON; `curl -O http://…/v1/bundles/full` produces a zip that re-imports through SAF on a fresh install.

**Acceptance**
- Link screen visible in Settings.
- Server starts/stops with screen lifecycle.
- Two read endpoints respond correctly.
- 401 without bearer.

---

## Phase 3 — Desktop app skeleton (PySide6) + desktop server

Goal: ship the Linux Mint app. It can be installed via `pip install -e .` from a repo subdir. It runs both the GUI and an HTTP server in the same process.

**Tasks**
1. Repo layout:
   ```
   desktop/                          (NEW, separate from app/)
   ├── pyproject.toml                Python 3.12, pip-installable
   ├── README.md
   ├── optimalx_link/
   │   ├── __init__.py
   │   ├── __main__.py               `python -m optimalx_link`
   │   ├── config.py                 reads/writes ~/.config/OptimalX-Link/config.json
   │   ├── snapshot_lib.py           filesystem operations on ~/OptimalX-Link/snapshots/
   │   ├── archive.py                write_snapshot_zip / extract_snapshot_zip / read_manifest
   │   ├── server.py                 aiohttp app with /v1/* endpoints
   │   ├── gui/
   │   │   ├── main_window.py        PySide6 QMainWindow
   │   │   ├── snapshot_list.py      QListView model + delegate
   │   │   ├── connection_panel.py   phone URL + token + test button
   │   │   └── activity_log.py
   │   └── tests/                    pytest
   └── share/
       └── applications/optimalx-link.desktop
   ```
2. Snapshot storage: `~/OptimalX-Link/snapshots/<YYYY-MM-DD_HH-MM-SS>/`. ID = the directory name. Manifest read on enumeration to surface `appVersion`, `dbVersion`, `size`.
3. Desktop endpoints in `server.py`:
   - `GET /v1/status` → `{linkVersion, snapshotCount, latestSnapshotEpochMs}`.
   - `GET /v1/snapshots` → list `[{id, epochMs, appVersion, dbVersion, sizeBytes}]`.
   - `GET /v1/snapshots/{id}/bundle` → streams the directory as a `.zip`.
   - `POST /v1/snapshots` → streams body to a temp `.zip`, extracts into `snapshots/<timestamp>/`, returns `{id, epochMs}`.
   - `DELETE /v1/snapshots/{id}` → removes that snapshot folder.
4. PySide6 main window:
   - Top bar: phone URL field, token field, "Test connection" button (calls phone `/v1/status`).
   - Left pane: snapshot list (newest first), each row shows timestamp, app+DB version, size.
   - Right pane (when a snapshot selected): manifest summary, project count, attachment count, "Open in Files" (`xdg-open`), **Restore to phone** button.
   - Top-of-window action: **Update from phone** button.
   - Bottom: activity log (server requests + outbound calls).
5. Desktop installer: `pip install -e .`, `xdg-desktop-menu install share/applications/optimalx-link.desktop`.

**Tests (pytest, JVM-free)**
- `archive.py`: roundtrip write → extract → manifest check.
- `snapshot_lib.py`: temp HOME, add three snapshots, list them sorted newest first, delete one, re-list.
- `server.py`: use `aiohttp.test_utils`, hit each endpoint with bearer, expect right responses.
- Manual: launch GUI on Mint, paste phone URL+token, click **Test connection** → green dot, then **Update from phone** → new snapshot appears.

**Acceptance**
- `python -m optimalx_link` launches the GUI.
- Desktop server runs on `17833`, accepts the five endpoints with bearer auth.
- **Update from phone** pulls a fresh snapshot and the GUI shows it.
- New snapshot folder on disk is browsable in Linux Files; `manifest.json` is human-readable.

---

## Phase 4 — Restore endpoints + confirm tap

Goal: bidirectional restore. Phone can apply a snapshot. Either side can initiate.

**Tasks (phone)**
1. New endpoint `POST /v1/restore/full`:
   - Streams body to `cacheDir/incoming-<jobId>.zip`.
   - Validates with `extractBackupZip + planRestore`.
   - Enqueues a `RestoreJob`, returns `202 {jobId}`.
   - Fires a state event observed by `LinkScreen` → full-screen confirm dialog ("Restore snapshot from <caller-IP>, taken <timestamp>? This replaces your current data.") with Accept / Reject.
   - On Accept, apply via the shared restore pipeline (close DB → rename current `optimalx.db` to `optimalx.db.bak` → copy new files in → reopen).
   - On Reject or 5-minute timeout, drop the cache file, mark job `REJECTED`.
2. New endpoint `GET /v1/jobs/{id}` → progress / final status.
3. New button **Push snapshot to desktop** on `LinkScreen` (phone client of desktop's `POST /v1/snapshots`).
4. New button **Import from desktop** on `LinkScreen` (phone client of desktop's `GET /v1/snapshots` + `GET /v1/snapshots/{id}/bundle`).

**Tasks (desktop)**
- New button **Restore to phone** in the per-snapshot pane: zips the snapshot folder, POSTs to phone `/v1/restore/full`, polls `/v1/jobs/{id}` for status, surfaces success / rejection in the activity log.

**Tests**
- Integration (phone, `TestApplication`): full restore round-trip via HTTP with the confirm dialog stubbed to auto-accept.
- Integration: token valid + confirm rejected → no file changes on phone.
- Integration: malformed body → `Failure(reason)` returned in job; staging cleaned up.
- Manual end-to-end:
  - Push from phone → snapshot appears in desktop library.
  - Update from desktop → fresh snapshot in library.
  - Restore to phone from desktop → phone gets confirm, accept, DB swapped.
  - Import from desktop on phone → same end state.

**Acceptance**
- Every restore requires the confirm tap to actually apply.
- A failed or rejected restore leaves the phone state untouched (`optimalx.db.bak` exists for one more open cycle).
- All four flows in `OPTIMALX_LINK.md §6` work end-to-end.

---

## Phase 5 — Orphan recovery on phone (no transfer)

Goal: address the workshop-wipe class of bug independent of having a snapshot. Same screen as Link, but a separate section.

**Tasks**
1. `OrphanScanner.scanWorkshop(context): List<OrphanProject>` — walks `files/workshop/<id>/`, finds dirs with no `subfolders` row.
2. `OrphanScanner.scanAttachments(context): List<OrphanAttachmentGroup>` — walks `files/optimalx_files/`, finds attachment dirs with no matching subfolder.
3. `OrphanScanner.relinkWorkshop(orphan, parentFolderId)` — re-inserts a `subfolders` row + `file_references` rows for every file in the dir. Preserves the original numeric ID when free.
4. New `LinkScreen` subsection **Salvage orphans** — separate lists for workshop and attachments, each with a "Recover" button per entry.

**Tests**
- Unit: build a temp `files/workshop/42/` with three files and no `subfolders` row → `scanWorkshop` returns one entry of size 3.
- Unit: `relinkWorkshop` reinserts rows; subsequent scan returns empty.
- Instrumented: simulate row loss, run salvage, verify the Workshop panel sees the project again.

**Acceptance**
- After deleting `subfolders` rows but leaving files on disk, the Link screen shows the orphan and recovery restores the project.

---

## Phase 6 — Hardening + polish

**Tasks**
- Activity log persistence on phone (last 50 in `DataStore`).
- Configurable phone server idle timeout (auto-stop if Link screen open but no requests in N minutes).
- Cellular opt-in toggle on phone.
- QR pairing URI scheme: `optimalx-link://?host=…&port=…&token=…` — desktop GUI can scan it via a webcam or by pasting.
- Settings show the working `curl` snippet for each endpoint.
- Phone rate-limits `/v1/restore/full` to 5 req/min/IP.
- Phone rebinds when the device's IP changes mid-session; UI reflects new IP.
- Desktop: snapshot rename + delete with confirm dialog.
- Desktop: "Pin" a snapshot so it survives any future "trim old snapshots" tool (deferred).
- Desktop: "Open snapshot in Files" → `xdg-open <snapshot-dir>`.

**Tests**
- Manual: Wi-Fi roam → phone rebinds, Settings reflects new IP.
- Manual: kill app while Link screen active → port released, desktop sees connection drop.

**Acceptance**
- The system feels safe to leave the screen on for a sustained session.
- Token rotation, rate limit, and confirm-tap all observable in the activity logs.

---

## Sequencing & risk

| Phase | Depends on | Blocks |
|-------|-----------|--------|
| 1 — Core refactor | – | 2, 4 |
| 2 — Phone Link screen + read | 1 | 3, 4 |
| 3 — Desktop app + server | 1 | 4 |
| 4 — Restore + bidirectional | 2, 3 | 6 |
| 5 — Orphan recovery | 1 | – |
| 6 — Hardening | 4 | – |

Phases 1 + 2 + 3 + 4 together deliver the full snapshot bridge. Phase 5 can ship in parallel with Phase 3 if desired — it's phone-only.

---

## Risk register

| Risk | Mitigation |
|------|-----------|
| Phone server bound on hostile Wi-Fi | Server only listens while Link screen is open + Wi-Fi-only default + bearer + confirm tap on every restore. |
| Token leak via screenshot or shared screen | Phone token rotates per screen open; desktop token regeneratable from GUI; auto-stop limits exposure. |
| Ktor + Netty conflicts with existing OkHttp | CIO engine sidesteps Netty entirely. |
| Phone server killed mid-transfer (screen rotated, app backgrounded) | Lifecycle scoped to `LinkScreen`; cancelling cleans up `cacheDir`; desktop client surfaces connection drop in activity log. |
| DB corruption mid-restore | Backup current DB to `.bak`, atomic rename target, only delete `.bak` after successful next open. |
| Path traversal in `/v1/snapshots/{id}/...` | Snapshot IDs constrained to `[A-Za-z0-9_-]+`; absolute paths rejected; canonical path under `~/OptimalX-Link/snapshots/` enforced. |
| Restore from older incompatible DB | Existing `planRestore` rejects newer-than-host DBs; older DBs ride Room's migration chain. |
| Desktop app distribution to other Linuxes | v1 is Mint-targeted (Python 3.12 + pip). Flatpak / AppImage deferred. |

---

## Open questions

- Should the desktop preflight-check `dbVersion` against the phone's `/v1/status` before pushing, so the user gets a clearer message than "Backup requires a newer app"? Probably yes — cheap.
- Should snapshots include the `cacheDir` `eidos_*` working files? Currently no — those are transient. Revisit if Eidos starts persisting non-DB state outside `cacheDir`.
- Desktop activity log persistence — flat file vs SQLite? Likely flat file for now.

---

## Document changelog

| Date | Change |
|------|--------|
| 2026-05-26 | Initial plan — six phases. Both sides run small HTTP servers; phone server scoped to Link screen. Supersedes the earlier Dump Zone plan. |
