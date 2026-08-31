# Cross-repo documentation

OptimalX is split across two repositories. This file explains where to find specs when working in the **Android** repo.

| Repo | GitHub | Role |
|------|--------|------|
| **OptimalX Android** (this repo) | [Optimal_X](https://github.com/Tuf12/Optimal_X) | Room DB, mobile UI, cloud Eidos |
| **OptimalX Desktop** | [OptimalXDesktop1.0](https://github.com/Tuf12/OptimalXDesktop1.0) | Electron app, sync server, desktop Eidos |

## Desktop spec (read-only reference)

Canonical desktop documentation lives in the **desktop repo root**, not here:

| Doc | Link |
|-----|------|
| Overview | [README.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/README.md) |
| Schema + sync tiers | [structure.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/structure.md) |
| Sync protocol + phases | [flow.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/flow.md) |
| Wire format + Eidos design | [design.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/design.md) |
| Chat vision (desktop shipped) | [design.md — Chat vision](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/design.md#chat-vision-desktop-shipped) |
| Feature parity matrix | [features.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/features.md) |
| Desktop Phase 2 plan | [phase2plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/phase2plan.md) |
| Desktop Phase 3 plan | [phase3plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/phase3plan.md) |
| Desktop Phase 4 plan | [phase4plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/phase4plan.md) |
| Workspace layout | [WORKSPACE.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/WORKSPACE.md) |
| Agentic IDE (platform) | [agentic-ide.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/agentic-ide.md) |
| Agentic IDE — workshop edits | [agentic-ide-workshop-edits.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/agentic-ide-workshop-edits.md) |
| Agentic IDE — implementation plan | [agentic-ide-implementation-plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/agentic-ide-implementation-plan.md) |
| Prompt transport & tool-loop context | [prompt-transport-and-context.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/prompt-transport-and-context.md) |
| Panel Workshop workflow | [panel-workshop-workflow.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/panel-workshop-workflow.md) |
| NoteX (multi-doc writing workspace) | [electron/notex/docs_notex/README.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/notex/docs_notex/README.md) |
| Image Studio (desktop product spec) | [electron/image-studio.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio.md) |
| Image Studio (desktop implementation) | [image-studio-implementation.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/image-studio-implementation.md) |

## Mobile handoff plans (edit here)

When implementing sync on Android, use these **local** plans:

| Phase | File |
|-------|------|
| Phase 2 (Tier 1 sync) | [DESKTOP_SYNC_MOBILE_PHASE2.md](../implementation/DESKTOP_SYNC_MOBILE_PHASE2.md) |
| Phase 3 (files + Tier 2) | [DESKTOP_SYNC_MOBILE_PHASE3.md](../implementation/DESKTOP_SYNC_MOBILE_PHASE3.md) |
| Phase 4 (Tier 3 PC→phone) | [DESKTOP_SYNC_MOBILE_PHASE4.md](../implementation/DESKTOP_SYNC_MOBILE_PHASE4.md) |
| Note editor (markdown / WYSIWYG) | [NOTE_EDITOR_HARDENING_PLAN.md](../implementation/NOTE_EDITOR_HARDENING_PLAN.md) |
| Chat vision attach (mobile) | [CHAT_VISION_ATTACH_PLAN.md](../implementation/CHAT_VISION_ATTACH_PLAN.md) |
| Eidos chat navigation (chips + `optimalx://`) | [EIDOS_NAVIGATION.md](../architecture/EIDOS_NAVIGATION.md), [EIDOS_NAVIGATION_IMPLEMENTATION_PLAN.md](../implementation/EIDOS_NAVIGATION_IMPLEMENTATION_PLAN.md) |
| Image Studio (Android panel; cloud API v1) | [image_studio/README.md](../image_studio/README.md), [image_studio/implementation-plan.md](../image_studio/implementation-plan.md) |

Coordinator copies of the same files exist in the desktop repo (`mobile-phase2plan.md`, `mobile-phase3plan.md`, `mobile-phase4plan.md`, [note-editor-plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/note-editor-plan.md)).

## Tier 3 workshop files (Option C)

Workshop **metadata** syncs via Push/Pull. **File bytes** use the Files API — never inside sync JSON. Current UI: **Sync workshop files to/from PC**. See [flow.md — Workshop file sync](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/flow.md#workshop-file-sync-tier-3).

## Deprecated: `OptimalX Desktop/` folder

An old duplicate copy of desktop specs lived at repo root in `OptimalX Desktop/`. That folder was removed — it drifted from the real desktop repo and had a misnamed `README.md` (it contained the Phase 2 mobile plan). Use the links above instead.
