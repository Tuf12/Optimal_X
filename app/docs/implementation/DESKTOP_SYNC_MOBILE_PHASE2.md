# Phase 2 — Mobile sync work plan

**Audience:** Cursor agents working in the **OptimalX Android** repo (and coordinator handoff to **OptimalX Desktop**).  
**Desktop spec:** [OptimalXDesktop1.0](https://github.com/Tuf12/OptimalXDesktop1.0) — [flow.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/flow.md), [structure.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/structure.md), [design.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/design.md)  
**Coordinator copy:** `mobile-phase2plan.md` in the desktop repo (keep in sync when this file changes).

**Status (2026-07-08):** **Phase 2 mobile complete.** M1 + M2 shipped; M3 core bidirectional Tier 1 sync verified on LAN. Desktop note markdown **view** rendering fixed separately on desktop. Remaining M3 rows are optional spot-checks, not blockers for Phase 3.

Desktop chunks **1–7** are complete. Mobile **M1–M2** are complete. **M3** joint E2E: primary scenarios passed (see below).

---

## Desktop status (already built — do not reimplement on mobile)

| Item | Status |
|------|--------|
| Express sync server `0.0.0.0:7373` | Done |
| `GET /api/v1/sync/status` | Done |
| `POST /api/v1/sync/push` | Done |
| `POST /api/v1/sync/pull` | Done |
| `POST /api/v1/sync/resolve` | Done |
| Bearer auth on all routes | Done |
| Tier 1 apply + conflict algorithm | Done — mirror behavior |
| Desktop **Settings → Sync with Mobile** UI | Done |

**Pairing:** User copies LAN URL + token from desktop app menu. Mobile stores them after a successful Test.

---

## Spec references (desktop repo paths)

If both repos are on the same machine, point the agent at:

| Doc | Desktop repo |
|-----|--------------|
| Protocol + conflict algorithm | [flow.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/flow.md) |
| Schema + v28 migration notes | [structure.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/structure.md) |
| JSON wire format + content hash | [design.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/design.md) |
| Tier 1 parity | [features.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/features.md) |
| System `global_id` constants | [electron/seed/system-folders.js](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/seed/system-folders.js) |
| Desktop apply logic (port behavior) | [electron/sync/](https://github.com/Tuf12/OptimalXDesktop1.0/tree/main/electron/sync) |

Copy `electron/seed/system-folders.js` constants into Android — **must match exactly**.

---

## System `global_id` constants (must match desktop)

| Folder | `global_id` |
|--------|-------------|
| Eidos Journal | `a1000001-0001-4001-8001-000000000001` |
| Eidos Log | `a1000002-0002-4002-8002-000000000002` |
| Eidos Chats | `a1000003-0003-4003-8003-000000000003` |
| Eidos Daily | `a1000004-0004-4004-8004-000000000004` |
| Eidos Memory | `a1000005-0005-4005-8005-000000000005` |
| Eidos Reasoning | `a1000006-0006-4006-8006-000000000006` |
| Quick Notes | `a1000007-0007-4007-8007-000000000007` |
| Panel Workshop | `a1000008-0008-4008-8008-000000000008` |

**DumpEdit singleton:** `b2000000-0000-4000-8000-dumpedit00001`

---

## Protocol summary (mobile client)

- **Base URL:** `http://<DESKTOP_LAN_IP>:7373/api/v1/sync`
- **Auth:** header `Authorization: Bearer <token>` on every request
- **JSON:** camelCase (`globalId`, `updatedAt`, `deletedAt`, `contentHash`, `parentFolderGlobalId`, etc.)
- **`deviceId`:** `mobile_<random>` — generate once per install, send on every push/pull
- **`lastSyncAt`:** stored in mobile preferences; watermark for incremental push/pull
- **No auto-sync** — user taps Test / Push / Pull only

### Tier 1 tables (Phase 2 only)

`parentFolders`, `subfolders`, `notes`, `fileReferences`, `dumpEdit` (singleton object, not array). **`homePins` are device-local** — not in the sync envelope.

### Push payload filter

Include rows where `updatedAt > lastSyncAt` **or** `deletedAt` is set (tombstones).

### FK apply order (push and pull apply on mobile)

```
parentFolders → subfolders → notes → fileReferences → dumpEdit
```

(`home_pins` / user pin shortcuts are **not** synced — each device keeps its own pinned row layout.)

### Conflict algorithm

Same as [flow.md — Conflict resolution](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/flow.md). Port logic from desktop `electron/sync/conflict.js` + `apply-row.js`. On equal `updatedAt`, differing `contentHash` → conflict, not auto-merge.

### `content_hash`

SHA-256 hex of UTF-8 note/DumpEdit `content` (optional `sha256:` prefix on wire). Compute on every save on mobile after v28.

---

## M1 — Room migration v27 → v28 (phase2plan Chunk 8)

**Goal:** Phone schema matches desktop sync columns; stable system IDs align.

| Task | Done |
|------|------|
| Add `global_id`, `origin_device_id` to all Tier 1 + Tier 2 syncable tables | [x] |
| Add `content_hash` to `notes` (and `panel_state` per structure.md) | [x] |
| Add `target_platform` to `subfolders` (default `'mobile'`) | [x] |
| Backfill `global_id = UUID()` for existing user rows | [x] |
| Set system parent `global_id` from constants table above (by folder name) | [x] |
| Set DumpEdit singleton `global_id` to DumpEdit constant above | [x] |
| `target_platform = 'mobile'` for existing workshop subfolders | [x] |
| Bump `AppDatabase` version to **28** | [x] |
| Compute `content_hash` on note / DumpEdit save | [x] |

**Done when:** App opens existing user DB on v28; Quick Notes + Panel Workshop `global_id` match desktop.

**Do not** start HTTP sync client until M1 passes on a real device/emulator DB.

---

## M2 — Sync client + Settings UI (phase2plan Chunk 9)

**Goal:** User can Test, Push, and Pull from the phone.

| Task | Done |
|------|------|
| Settings → **Sync with Desktop** screen | [x] |
| Fields: server URL, port (default 7373 if omitted), bearer token | [x] |
| Optional: parse `optimalx-sync://<host>:<port>?token=...` pairing URI | [x] |
| **Test** → `GET /api/v1/sync/status` only (no LLM in Phase 2) | [x] |
| **Push** → `POST /api/v1/sync/push` | [x] |
| **Pull** → `POST /api/v1/sync/pull` with `tiers: [1]` | [x] |
| **Full pull** → same endpoint with `lastSyncAt: 0` (all Tier 1 rows) | [x] |
| **Resolve** (minimal) → `POST /api/v1/sync/resolve` when conflicts returned | [x] |
| Store URL + token in secure preferences after successful Test | [x] |
| `device_id` = `mobile_<random>` once per install | [x] |
| Build push payload from DAO queries | [x] |
| Apply pull with same conflict rules as desktop | [x] |
| Update `lastSyncAt` in preferences after successful push/pull | [x] |
| Result UI: applied / skipped / conflict counts + received row summary on pull | [x] |
| Push/Pull confirm dialogs with direction schematic | [x] |

**Done when:** Test succeeds on LAN with desktop running; Push/Pull call API and update local DB.

### Suggested package layout (Android) — **as built**

```
data/sync/
├── SyncApi.kt              # HttpURLConnection — status, push, pull, resolve
├── SyncDtos.kt             # camelCase DTOs (+ JsonNames for snake_case table keys)
├── SyncService.kt          # orchestrates Test / Push / Pull / Resolve
├── SyncPushBuilder.kt      # query changed rows since lastSyncAt
├── SyncPullApplier.kt      # FK order + conflict algorithm
├── SyncFingerprints.kt     # equal-timestamp folder/note fingerprints
├── SyncConflictLogic.kt
├── SyncMappers.kt
├── SyncPairingUri.kt
├── SyncPreferences.kt      # DataStore + encrypted token, lastSyncAt, deviceId
├── SyncContentHash.kt
├── SyncGlobalIds.kt
└── SyncPayloadCounts.kt    # pull diagnostics (received row counts)

ui/settings/
├── SyncWithDesktopScreen.kt
└── SyncWithDesktopViewModel.kt
```

Entry: **Settings → Sync with Desktop** (`Routes.SYNC_WITH_DESKTOP`).

---

## M3 — E2E acceptance (phase2plan Chunk 10 — joint)

Run with **desktop app open** on same LAN (`npm start`, port **7373**).

| Scenario | Result | Notes |
|----------|--------|-------|
| Phone → desktop | **PASS** | Push applied 301 rows; data readable on desktop |
| Desktop → phone | **PASS** | Full pull after desktop fixes; incremental pull needs `updatedAt` bump on desktop save |
| Soft delete | **Spot-check** | Tombstone logic implemented both sides; not formally signed off |
| Conflict | **PASS** (after fix) | False `equal_timestamp_field_mismatch` on subfolders fixed in `SyncFingerprints.kt` |
| DumpEdit | **Spot-check** | Wire + apply path exists; verify if needed |
| File metadata | **Spot-check** | `fileReferences` in Tier 1; bytes still Phase 3 |
| Desktop note view | **PASS** | Markdown render in view mode (display-only; no sync transform) |

**Report back to desktop coordinator:** which scenarios pass/fail; sample `global_id` if mismatch suspected.

### Issues found during M3 (resolved)

| Issue | Side | Resolution |
|-------|------|------------|
| Incremental pull returned 0 rows | Desktop + mobile | Desktop must use **request** `lastSyncAt`; bump `updated_at` on every save; mobile added **Full pull** (`lastSyncAt: 0`) |
| 136 false conflicts on full pull | Mobile | Subfolder fingerprint compared local vs incoming with different fields; fixed in `SyncFingerprints.kt` |
| Raw markdown symbols on desktop | Desktop | View-mode markdown renderer only — never transform `content` on sync apply |
| Missing `contentHash` on pull wire | Either | Mobile backfills hash from `content` when empty; desktop should still send hash |

---

## Note content format (mobile ↔ desktop)

**Canonical storage:** `notes.content` and DumpEdit `content` are **markdown** (UTF-8). Mobile reference: `NoteContentCodec.kt`, `EDITOR_AND_PANELS.md`.

| Layer | Mobile | Desktop (Phase 2) |
|-------|--------|-------------------|
| DB / sync | Raw markdown string + `contentHash` | Same — no transform on push/pull apply |
| View mode | `MarkdownRichText` / `chatMarkdownToDisplayHtml` | Markdown renderer (GFM-ish; fenced code as `<pre><code>`) |
| Edit mode | WYSIWYG rich text → save as markdown | WYSIWYG or source tab; on save update `updated_at` + hash |

**`content_hash`:** SHA-256 hex of exact `content` bytes. Optional `sha256:` prefix on wire; normalize when comparing. Never reformat content during sync — only on explicit user save if editor converts to markdown.

---

## Handoff — Phase 3 (desktop next)

Phase 2 Tier 1 metadata sync is **done**. Desktop agent should proceed per [phase2plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/phase2plan.md) **Phase 3** / Chunk scope:

| Item | Phase 3 work |
|------|----------------|
| `GET /files/request/:globalId` | Stream attachment/workshop bytes |
| Tier 2 tables | `conversations`, `panel_state`, chat messages, etc. |
| Mobile workshop file sync (Tier 3) | `backups/mobile-workshop/` on desktop |
| `file_references` | Metadata in Tier 1; bytes via Tier 3 Files API |

Mobile Phase 3: file byte client (M1 — phone→PC), Tier 2 push/pull (M2). Phase 4 M1: PC→phone sync. Bytes never inside Push/Pull JSON (Option C).

---

## Out of scope on mobile (Phase 2)

| Item | Phase |
|------|-------|
| File bytes (`/files/request`) | 3 |
| Tier 2 (conversations, panel_state, …) | 2–3 |
| `/api/v1/llm/*`, Desktop GPU | 5b |
| Background / auto sync | Never in v1 |

---

## Verification without UI (during M2 development)

Desktop must be running. From any LAN machine:

```bash
TOKEN=$(jq -r .syncToken ~/.config/optimalx-desktop/config.json)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://<DESKTOP_LAN_IP>:7373/api/v1/sync/status | jq .
```

Use the LAN IP from desktop startup log or **Settings → Sync with Mobile** — **not** port `17377` (old test port).

---

## Cursor agent prompts (copy to OptimalX mobile chat)

### Start M1 (migration only)

```text
Read app/docs/implementation/DESKTOP_SYNC_MOBILE_PHASE2.md section M1.

Implement Room migration v27 → v28 only. Do not build sync HTTP client or UI yet.

Requirements:
- global_id, origin_device_id on Tier 1 + Tier 2 syncable tables
- content_hash on notes (+ panel_state per structure.md)
- target_platform on subfolders (default mobile)
- Backfill UUIDs for existing rows; system folders use EXACT constants from
  DESKTOP_SYNC_MOBILE_PHASE2.md (must match desktop electron/seed/system-folders.js)
- DumpEdit singleton global_id: b2000000-0000-4000-8000-dumpedit00001
- AppDatabase version 28
- SHA-256 content_hash on note and DumpEdit save (UTF-8 content, hex)

Reference Android schema: AppDatabase.kt, DATA_MODEL.md.
Reference desktop spec: OptimalXDesktop1.0/structure.md and flow.md if available.

When done, list migration files changed and confirm system folder UUIDs match the table.
```

### Start M2 (sync client + UI — after M1 done)

```text
Read app/docs/implementation/DESKTOP_SYNC_MOBILE_PHASE2.md section M2. M1 (Room v28) must be complete.

Implement Settings → Sync with Desktop:
- Server URL, port (default 7373), bearer token
- Test → GET /api/v1/sync/status with Authorization: Bearer
- Push → POST /api/v1/sync/push
- Pull → POST /api/v1/sync/pull with tiers: [1]
- Resolve → POST /api/v1/sync/resolve when conflicts exist
- device_id mobile_<random> in preferences; lastSyncAt updated on success
- Secure storage for URL + token after successful Test

Mirror desktop behavior in OptimalXDesktop1.0/electron/sync/ for:
- serialize (camelCase), apply order, conflict algorithm, push row filter

Tier 1 only. No LLM routes, no file bytes, no Tier 2, no auto-sync.

Optional: parse optimalx-sync:// pairing URI from desktop settings.
```

### Start M3 (E2E — after M2 done)

```text
Read app/docs/implementation/DESKTOP_SYNC_MOBILE_PHASE2.md section M3. Run E2E against a desktop on LAN port 7373.

Walk through each acceptance row; fix failures. Report results to the human
coordinator with steps to reproduce any failure.
```

---

## Coordinator checklist (human)

- [x] Canonical copy at `app/docs/implementation/DESKTOP_SYNC_MOBILE_PHASE2.md`
- [x] Desktop running; user has LAN URL + token from **Settings → Sync with Mobile**
- [x] Mobile agent completes M1 → v28 opens without crash
- [x] Mobile agent completes M2 → Test connection succeeds on phone
- [x] M3 primary scenarios (push, full pull, formatting) verified on LAN
- [ ] Optional: formal spot-check soft delete, DumpEdit, file metadata
- [ ] Phase 3 kickoff on desktop (`/files/request`, Tier 2)
