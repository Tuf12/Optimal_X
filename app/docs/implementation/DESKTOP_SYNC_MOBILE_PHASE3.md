# Phase 3 — Mobile handoff (OptimalX Android)

Companion to [phase3plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/phase3plan.md).

**Coordinator copy:** [mobile-phase3plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/mobile-phase3plan.md) in the desktop repo (keep in sync when this file changes).

**Prerequisite:** Phase 2 mobile complete ([DESKTOP_SYNC_MOBILE_PHASE2.md](DESKTOP_SYNC_MOBILE_PHASE2.md)) — Room v28, Settings → Sync, Tier 1 push/pull.

**Desktop prerequisite:** Phase 3 Chunks 1–2 (file routes) before M1 file client; Chunk 4 before M2 Tier 2.

**Phase 4 follow-up:** PC→phone file sync — [DESKTOP_SYNC_MOBILE_PHASE4.md](DESKTOP_SYNC_MOBILE_PHASE4.md) / desktop [mobile-phase4plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/mobile-phase4plan.md) M1.

---

## Tier 3 policy (Option C) — canonical model

Workshop **metadata** syncs in Tier 1 Push/Pull. **File bytes** use the Files API only — never embedded in sync JSON.

| Direction | Mobile UI (current labels) | API |
|-----------|---------------------------|-----|
| Phone → PC | **Sync workshop files to PC** / **Sync all workshop files to PC** | `POST /files/push` (`kind=workshop`) |
| PC → phone | **Sync workshop files from PC** (Phase 4 M1) | `GET /files/request` (`kind=mobile_workshop_backup`) |

Phase 3 M1 shipped phone→PC. Phase 4 M1 completed PC→phone UX (2026-07). Historical docs may say "backup/restore" — same Tier 3 path.

`SyncFilePathResolver` rewrites `file_path` on Pull so metadata never points at desktop absolute paths.

---

## Path constants (must align with desktop)

| Asset | Mobile path | Desktop path (after transfer) |
|-------|-------------|-------------------------------|
| Attachment | `context.filesDir/optimalx_files/<subfolderId>/<fileName>` | `~/OptimalX/files/optimalx_files/<globalId>_<fileName>` |
| Workshop source | `context.filesDir/workshop/<subfolderId>/` | — |
| Workshop PC tree | — | `~/OptimalX/backups/mobile-workshop/<subfolderGlobalId>/` |

Use `file_reference.globalId` for attachment fetch. Use **subfolder `globalId`** for workshop file sync. Local `subfolderId` is device-only.

---

## M1 — File transfer client (Tier 3 phone → PC)

**Goal:** Fetch attachment bytes from desktop; sync workshop tree to PC.

| Task | Done |
|------|------|
| `SyncFileApi.kt` — authenticated `GET /api/v1/files/request/:globalId` | [x] |
| `SyncFileApi.kt` — multipart `POST /api/v1/files/push/:globalId` | [x] |
| On file open: if `File(filePath)` missing, `kind=attachment` download | [x] |
| Workshop sync (single project): walk `workshop/<subfolderId>/`, upload each with `kind=workshop&path=<relative>` | [x] |
| Workshop sync (all projects): `backupAllMobileWorkshopsToDesktop()` — every Panel Workshop subfolder | [x] |
| UI: per-project **Sync workshop files to PC** in workshop drawer | [x] |
| UI: **Sync all workshop files to PC** in Settings → Sync with Desktop; show progress | [x] |
| Handle 401 / 404 / 413 with user-readable errors | [x] |

**Done when:** PDF attached on desktop appears on phone after fetch; workshop `index.html` visible on desktop under `backups/mobile-workshop/<globalId>/`.

### Workshop file sync UX (separate from Push/Pull)

Metadata (subfolder rows, `file_references` names) syncs via **Tier 1 Push/Pull**. File **bytes** use `/api/v1/files/*` — not embedded in sync JSON.

| Entry point | When to use |
|-------------|-------------|
| **Settings → Sync with Desktop → Sync all workshop files to PC** | Initial full sync, or catch-up so no panel is missed |
| **Panel Workshop project drawer → Sync workshop files to PC** | After editing one project on phone |

**Phone → PC workflow**

1. **Push** (Tier 1) — desktop SQLite gets each workshop subfolder + `globalId`.
2. **Sync all workshop files to PC** — copies every `filesDir/workshop/<subfolderId>/` tree to `~/OptimalX/backups/mobile-workshop/<subfolderGlobalId>/`.
3. Later: edit one panel on phone → **Sync workshop files to PC** in that project only.

**PC → phone workflow** — Phase 4 M1; see [DESKTOP_SYNC_MOBILE_PHASE4.md](DESKTOP_SYNC_MOBILE_PHASE4.md):

1. **Pull** (Tier 1) on phone.
2. **Sync workshop files from PC** in that project drawer.

**Reference files:**

- `app/src/main/java/com/example/optimalx/data/sync/SyncFileApi.kt`
- `app/src/main/java/com/example/optimalx/data/sync/SyncFileService.kt`
- `app/src/main/java/com/example/optimalx/ui/settings/SyncWithDesktopScreen.kt`
- `app/src/main/java/com/example/optimalx/ui/workshop/WorkshopEditorViewModel.kt`

---

## M2 — Tier 2 push/pull

**Goal:** Sync conversations, messages, panel state, revision history.

| Task | Done |
|------|------|
| `SyncPushBuilder.buildTier2(lastSyncAt)` for all Tier 2 tables | [x] |
| DAO `getChangedSince` queries (mirror Tier 1 pattern) | [x] |
| `SyncPullApplier` Tier 2 apply + FK remap via `globalId` | [x] |
| Push/Pull requests use `tiers: [1, 2]` | [x] |
| `panel_state.contentHash` updated on `stateJson` save | [x] |
| `chat_messages` append-only on apply | [x] |

**Done when:** Phone Eidos thread pushes to desktop SQLite; `panel_state` for a workshop project round-trips.

---

## M3 — E2E acceptance (phase3plan Chunk 8 — joint)

**Run:** 2026-07-10 — desktop `:7373` on LAN.

| Scenario | Pass | Notes |
|----------|------|-------|
| Attachment fetch after Tier 1 sync | yes | Phone Pull → PDF on device (Jul 10) |
| Workshop file sync to desktop (single) | yes | Full trees under `~/OptimalX/backups/mobile-workshop/` |
| Workshop file sync to desktop (all) | yes | 4 project dirs; 2026-07-10 08:12 UTC |
| Tier 2 conversation visible on desktop | yes | `general` + 2 messages, `origin_device_id` mobile |
| `panel_state` sync | yes | `global` scope, 64 B JSON on desktop |
| Desktop Panel Workshop shows mobile project | yes | Coordinator confirmed |

**Signed off:** 2026-07-10.

---

## Out of scope (mobile Phase 3)

| Item | Phase |
|------|-------|
| In-app workshop editor parity with desktop | 4 |
| PC → phone workshop file sync UX | 4 M1 |
| Desktop GPU / `/api/v1/llm/*` | 5b |
| Workshop bytes inside Push/Pull JSON | Never (Option C) |
| Background sync | Never in v1 |

---

## Coordinator checklist

- [x] Desktop Chunks 1–2 deployed → mobile can curl `/files/request`
- [x] M1 complete → workshop files on PC via Tier 3
- [x] Desktop Chunk 4 deployed → Tier 2 pull returns keys
- [x] M2 complete → conversation round-trip
- [x] M3 joint acceptance signed off — 2026-07-10
- [x] Phase 4 M1 — PC→phone sync verified 2026-07
