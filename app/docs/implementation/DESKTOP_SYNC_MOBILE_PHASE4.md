# Phase 4 — Mobile handoff (OptimalX Android)

Companion to desktop [mobile-phase4plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/mobile-phase4plan.md) and [phase4plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/phase4plan.md).

**Coordinator copy:** [mobile-phase4plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/mobile-phase4plan.md) in the desktop repo (keep in sync when this file changes).

**Prerequisite:** Phase 3 mobile complete ([DESKTOP_SYNC_MOBILE_PHASE3.md](DESKTOP_SYNC_MOBILE_PHASE3.md)) — Tier 3 phone→PC, Tier 2 sync.

**Desktop prerequisite:** Phase 4 Chunks 1–4 — desktop can edit `backups/mobile-workshop/`.

---

## Tier 3 policy (Option C)

Same model as Phase 3 — **not** changed in Phase 4:

| What | How |
|------|-----|
| Metadata | Tier 1 Push/Pull |
| Workshop file bytes | Files API only (`POST /files/push`, `GET /files/request`) |

Phase 4 added desktop workshop editor + **PC→phone** mobile UX that Phase 3 deferred.

---

## M1 — Sync workshop files from PC (Tier 3 reverse)

**Goal:** After editing a mobile-scoped project on desktop, user **Pull**s metadata then **Sync workshop files from PC**.

```http
GET /api/v1/files/request/:subfolderGlobalId?kind=mobile_workshop_backup&path=<relative>
```

| Task | Done |
|------|------|
| `SyncFileApi.fetchWorkshopBackupFile(globalId, relativePath)` | [x] |
| `SyncFileService.restoreWorkshopFromDesktop(subfolderId)` | [x] |
| Workshop drawer: **Sync workshop files from PC** | [x] |
| Confirm overwrite dialog; per-file progress | [x] |
| Banner when `WorkshopDiskCheck.needsRestoreFromDesktop` | [x] |
| Skip missing optional files on PC (don’t abort whole sync) | [x] |
| `syncFileReferenceAfterRestore` relative-path matching | [x] |
| Pull confirm mentions Tier 3 separate from metadata | [x] |
| UI copy: backup/restore → sync workshop files | [x] |

**Done when:** Edit `script.js` on desktop → Pull on phone → **Sync workshop files from PC** → phone editor shows new content.

**Reference files:**

- `app/src/main/java/com/example/optimalx/data/sync/SyncFileService.kt` — `restoreWorkshopFromDesktop`
- `app/src/main/java/com/example/optimalx/data/sync/SyncFilePathResolver.kt` — local paths on Pull
- `app/src/main/java/com/example/optimalx/ui/workshop/WorkshopEditorScreen.kt` — drawer + banner
- `app/src/main/java/com/example/optimalx/ui/settings/SyncWithDesktopScreen.kt` — Tier 3 notes on Pull

---

## M2 — On-device QA (joint)

See desktop [mobile-phase4plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/mobile-phase4plan.md) M2 checklist.

| Scenario | Pass |
|----------|------|
| Pull only — metadata without bytes | Expected |
| Pull + sync files from PC — panel runs | |
| Phone edit → sync to PC — desktop shows content | |
| `panel_state` valid after file sync | |

---

## Coordinator checklist

- [x] M1 complete — bidirectional Tier 3 verified 2026-07
- [ ] M2 on-device QA for one mobile-scoped project edited on PC
