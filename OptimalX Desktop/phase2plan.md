# Phase 2 — Mobile sync work plan

**Audience:** Cursor agents working in the **OptimalX Android** repo.  
**Coordinator:** Human bridges this doc ↔ [OptimalXDesktop1.0](.) (desktop is **done** for Phase 2 server side).

Desktop chunks **1–7** are complete. Mobile work is **chunks 8–9** below (renamed **M1–M2** here for clarity). **M3** is joint E2E acceptance with desktop.

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

| Doc | Path (from OptimalXDesktop1.0) |
|-----|--------------------------------|
| Protocol + conflict algorithm | [flow.md](flow.md) |
| Schema + v28 migration notes | [structure.md](structure.md) |
| JSON wire format + content hash | [design.md](design.md) |
| Tier 1 parity | [features.md](features.md) |
| System `global_id` constants | [electron/seed/system-folders.js](electron/seed/system-folders.js) |
| Desktop apply logic (port behavior) | [electron/sync/](electron/sync/) |

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

`parentFolders`, `subfolders`, `notes`, `fileReferences`, `homePins`, `dumpEdit` (singleton object, not array)

### Push payload filter

Include rows where `updatedAt > lastSyncAt` **or** `deletedAt` is set (tombstones).

### FK apply order (push and pull apply on mobile)

```
parentFolders → subfolders → notes → fileReferences → homePins → dumpEdit
```

### Conflict algorithm

Same as [flow.md — Conflict resolution algorithm](flow.md). Port logic from desktop `electron/sync/conflict.js` + `apply-row.js`. On equal `updatedAt`, differing `contentHash` → conflict, not auto-merge.

### `content_hash`

SHA-256 hex of UTF-8 note/DumpEdit `content` (optional `sha256:` prefix on wire). Compute on every save on mobile after v28.

---

## M1 — Room migration v27 → v28 (phase2plan Chunk 8)

**Goal:** Phone schema matches desktop sync columns; stable system IDs align.

| Task | Done |
|------|------|
| Add `global_id`, `origin_device_id` to all Tier 1 + Tier 2 syncable tables | [ ] |
| Add `content_hash` to `notes` (and `panel_state` per structure.md) | [ ] |
| Add `target_platform` to `subfolders` (default `'mobile'`) | [ ] |
| Backfill `global_id = UUID()` for existing user rows | [ ] |
| Set system parent `global_id` from constants table above (by folder name) | [ ] |
| Set DumpEdit singleton `global_id` to DumpEdit constant above | [ ] |
| `target_platform = 'mobile'` for existing workshop subfolders | [ ] |
| Bump `AppDatabase` version to **28** | [ ] |
| Compute `content_hash` on note / DumpEdit save | [ ] |

**Done when:** App opens existing user DB on v28; Quick Notes + Panel Workshop `global_id` match desktop.

**Do not** start HTTP sync client until M1 passes on a real device/emulator DB.

---

## M2 — Sync client + Settings UI (phase2plan Chunk 9)

**Goal:** User can Test, Push, and Pull from the phone.

| Task | Done |
|------|------|
| Settings → **Sync with Desktop** screen | [ ] |
| Fields: server URL, port (default 7373 if omitted), bearer token | [ ] |
| Optional: parse `optimalx-sync://<host>:<port>?token=...` pairing URI | [ ] |
| **Test** → `GET /api/v1/sync/status` only (no LLM in Phase 2) | [ ] |
| **Push** → `POST /api/v1/sync/push` | [ ] |
| **Pull** → `POST /api/v1/sync/pull` with `tiers: [1]` | [ ] |
| **Resolve** (minimal) → `POST /api/v1/sync/resolve` when conflicts returned | [ ] |
| Store URL + token in secure preferences after successful Test | [ ] |
| `device_id` = `mobile_<random>` once per install | [ ] |
| Build push payload from DAO queries | [ ] |
| Apply pull with same conflict rules as desktop | [ ] |
| Update `lastSyncAt` in preferences after successful push/pull | [ ] |
| Result UI: applied / skipped / conflict counts | [ ] |

**Done when:** Test succeeds on LAN with desktop running; Push/Pull call API and update local DB.

### Suggested package layout (Android)

```
data/sync/
├── SyncApi.kt              # Ktor/OkHttp — status, push, pull, resolve
├── SyncDtos.kt             # camelCase data classes matching design.md
├── SyncPushBuilder.kt      # query changed rows since lastSyncAt
├── SyncPullApplier.kt      # FK order + conflict algorithm
├── SyncPreferences.kt      # URL, token, lastSyncAt, deviceId
ui/settings/
└── SyncWithDesktopScreen.kt
```

---

## M3 — E2E acceptance (phase2plan Chunk 10 — joint)

Run with **desktop app open** on same LAN (`npm start`, port **7373**).

| Scenario | Mobile action | Desktop check |
|----------|---------------|---------------|
| Phone → desktop | Edit note → **Push** | Note visible in desktop app |
| Desktop → phone | — | Edit note → phone **Pull** |
| Soft delete | Delete on one side → sync | Tombstone on other |
| Conflict | Same `updatedAt`, different body on both → Push/Pull | Conflict flagged; resolve works |
| DumpEdit | Edit scratch buffer → sync | Both directions |
| File metadata | Add file ref on phone → Push | Metadata on desktop (no bytes yet) |

**Report back to desktop coordinator:** which scenarios pass/fail; sample `global_id` if mismatch suspected.

---

## Out of scope on mobile (Phase 2)

| Item | Phase |
|------|-------|
| File bytes (`/files/request`) | 3 |
| Tier 2 (conversations, panel_state, …) | 2–3 |
| `/api/v1/llm/*`, Desktop GPU | 5b |
| Replace OptimalX Link Ktor server | After M3 passes — gate behind debug first |
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
Read mobile-phase2plan.md section M1 in this repo (or the copy from OptimalXDesktop1.0).

Implement Room migration v27 → v28 only. Do not build sync HTTP client or UI yet.

Requirements:
- global_id, origin_device_id on Tier 1 + Tier 2 syncable tables
- content_hash on notes (+ panel_state per structure.md)
- target_platform on subfolders (default mobile)
- Backfill UUIDs for existing rows; system folders use EXACT constants from
  mobile-phase2plan.md (must match desktop electron/seed/system-folders.js)
- DumpEdit singleton global_id: b2000000-0000-4000-8000-dumpedit00001
- AppDatabase version 28
- SHA-256 content_hash on note and DumpEdit save (UTF-8 content, hex)

Reference Android schema: AppDatabase.kt, DATA_MODEL.md.
Reference desktop spec: OptimalXDesktop1.0/structure.md and flow.md if available.

When done, list migration files changed and confirm system folder UUIDs match the table.
```

### Start M2 (sync client + UI — after M1 done)

```text
Read mobile-phase2plan.md section M2. M1 (Room v28) must be complete.

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
Read mobile-phase2plan.md section M3. Run E2E against a desktop on LAN port 7373.

Walk through each acceptance row; fix failures. Report results to the human
coordinator with steps to reproduce any failure.
```

---

## Coordinator checklist (human)

- [ ] Copy or sync this file into Android repo (keep in sync when desktop spec changes)
- [ ] Desktop running; user has LAN URL + token from **Settings → Sync with Mobile**
- [ ] Mobile agent completes M1 → you confirm v28 opens without crash
- [ ] Mobile agent completes M2 → Test connection succeeds on phone
- [ ] Run M3 scenarios; relay failures to desktop or mobile agent as needed
