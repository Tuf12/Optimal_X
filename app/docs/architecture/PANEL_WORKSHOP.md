# PANEL_WORKSHOP.md

**Panel Workshop — product UX and build lifecycle**

| Field | Value |
|--------|--------|
| **Status** | **v2 spec** — phased workflow (replaces README-first + one-shot Start Build) |
| **Audience** | Product, UX, Eidos prompt authors |
| **Related** | [WORKSHOP_MODES.md](WORKSHOP_MODES.md) (modes + phases), [PANEL_PLATFORM.md](PANEL_PLATFORM.md) (runtime contract), [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](../implementation/PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) (Auto-Continue + prompt/token fixes), [WORKSHOP_MEMORY.md](../memory/WORKSHOP_MEMORY.md) (categorical cross-project preferences), [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md) (recovery checklist), [DIFF_REVIEW.md](DIFF_REVIEW.md) (pending edits on edit profile), [agentic-ide.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/agentic-ide.md) (desktop: Agentic IDE platform), [agentic-ide-implementation-plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/agentic-ide-implementation-plan.md) (desktop: phased build plan), [agentic-ide-workshop-edits.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/agentic-ide-workshop-edits.md) (desktop: workshop write/verify layer) |
| **Kimi integration** | Formula `web_search` / `fetch` (and utility tools) in workshop when provider is Kimi — see [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md) |

---

## Purpose

The Panel Workshop is where users design and build custom HTML/JS panels with Eidos.
Panels are self-contained interactive tools that render inside the OptimalX editor via WebView.

**Platform contract (what Kotlin provides vs what panel HTML/CSS/JS must do):** [PANEL_PLATFORM.md](PANEL_PLATFORM.md).

**Eidos modes, project phases, tool gating, prompts:** [WORKSHOP_MODES.md](WORKSHOP_MODES.md).

**Auto-Continue (build run profile):** Hands-off chunked Eidos runs during kickoffs — [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](../implementation/PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md). Interactive fixes after complete use the **workshop edit profile** (Diff Review, no long auto chains).

---

## Design principles

1. **Chat first, files second** — Users discuss what they want with Eidos before any spec files exist. The three intake questions (What / Why / How) live in conversation, not as editable prompts in `README.md`.
2. **Gated phases** — Each major step has an explicit user action (Generate specs, Build design, Build logic, Finish, etc.). Eidos does not silently run a full greenfield build in one hop.
3. **Short human-language specs** — Spec `.md` files summarize intent for normal people. They must not rewrite code in human language or grow into unreadable essays. Startup caps apply during greenfield; see [Spec files](#spec-files).
4. **Design before logic** — First code build is layout and controls only (shell). Behavior comes after the user accepts the design in Preview.
5. **Code is truth during iteration; docs align on approval** — While the user is editing design or logic, Eidos works from chat + current code. Spec files are updated only when the user approves a phase — not on every edit. This avoids token burn and doc/code spaghetti.
6. **Built-in platform guidance** — Every project ships scaffolds and embedded rules so Eidos follows Android WebView layout, touch targets, bridge order, and bundling without the user reading platform docs. See [Built-in platform guidance](#built-in-platform-guidance).
7. **No “Sync to code”** — The legacy primary action that fired when markdown changed is removed. Users discuss changes with Eidos; Eidos updates code directly in the appropriate mode. Docs catch up on approval, not via a standing sync button.

---

## System structure

The Panel Workshop is a system-locked parent folder on the parent folder page.
Inside it, users create subfolders — each subfolder is one custom panel project.

Tapping a project subfolder opens the **Workshop Editor**, not the standard note editor.

### Where panels are built vs run (do not mix these)

| Surface | Role | User action |
|---------|------|-------------|
| **Panel Workshop** | Build and maintain the project | **Preview** — test layout/behavior while editing (WebView in the workshop; not persisted user data). **Update** — enter maintenance after **Complete**. |
| **Panel Gallery** | Run finished panels | Launch a **Complete** project; live panel + `panel_runner` Eidos. |
| **Editor custom panel tab** | Run a panel inside a subfolder | **Add Panel** on a regular subfolder; same runtime files as Gallery. |

Workshop does **not** “use” the panel in the product sense — it **previews** during build and **updates** after complete. Runtime **use** is Gallery or custom tab only.

---

## Build lifecycle (phases)

```text
┌─────────────┐     Generate      ┌──────────────┐     Accept      ┌──────────────┐
│   INTAKE    │ ──── specs ────► │ SPEC_REVIEW  │ ──── specs ───► │ DESIGN_BUILD │
│  (Eidos     │                   │ view .md +   │                 │ layout shell │
│   chat)     │                   │ chat / edit  │                 │ only         │
└─────────────┘                   └──────────────┘                 └──────┬───────┘
                                                                          │
                    Align docs ◄── Accept design ◄── DESIGN_REVIEW ◄──────┘
                    (short MD)         │              chat + edit
                                       │              (no .md for Eidos)
                                       ▼
                              ┌──────────────┐     Accept      ┌──────────────┐
                              │ LOGIC_BUILD  │ ──── logic ───► │ LOGIC_REVIEW │
                              │ behavior     │                 │ chat + edit  │
                              └──────────────┘                 │ Debug avail. │
                                       │                       └──────┬───────┘
                                       │                              │
                                       └──────── Accept logic ◄─────────┘
                                                │
                                                ▼ align docs (short)
                                         ┌──────────────┐
                                         │   COMPLETE   │ ──Update──► UPDATE ──Accept update──► COMPLETE
                                         └──────────────┘              (repeat)
```

### Phase summary

| Phase | User experience | Primary action | Eidos focus |
|-------|-----------------|----------------|-------------|
| **Intake** | Open Eidos; discuss What / Why / How | **Generate specs** (enabled when aligned) | Chat — no file writes |
| **Spec review** | View generated `.md` files; chat or edit until happy | **Accept specs** | Plan — `.md` only |
| **Design build** | Eidos writes layout shell | **Build design** (after specs accepted) | Design build — HTML/CSS + stub JS |
| **Design review** | Preview panel; chat and iterate on layout | **Accept design** | Design edit — code only; **no `.md` read/write** |
| **Doc align (design)** | Optional quick read of updated DESIGN.md | Automatic on accept design | Short code → DESIGN.md (+ FLOW if needed) |
| **Logic build** | Eidos wires calculations, state, bridge actions | **Build logic** | Logic build — `script.js` / `bridge.js` |
| **Logic review** | Preview + chat; **Debug** available (console + bridge) | **Accept logic** | Chat / Plan / Edit on runtime files |
| **Doc align (finish)** | Quick read of all spec files | Automatic on Accept logic | Short code → all `.md` snapshots |
| **Complete** | Build finished; run panel from Gallery or custom tab | **Update** | Opens maintenance (or edit files / Preview in workshop) |
| **Update/edit** | Maintenance cycle (repeatable) | **Accept update** | Chat / Plan / Edit; diffs reviewed; doc align on Accept update |

Persist phase in `WorkshopProjectPreferences` (`WorkshopProjectPhase`). Details: [WORKSHOP_MODES.md](WORKSHOP_MODES.md).

---

## Workshop editor layout

### Slide-out drawer (sidebar)

Two sections:

- **Docs** — `.md` spec files (hidden or read-only to Eidos during design review)
- **Code** — `.html`, `.js`, `.css`

Tapping any file opens it in the editor area. **New File** at the bottom creates additional files (advanced; not required for v2 flow).

### Editor area

- **Markdown:** rendered by default; Edit/View toggle for raw editing.
- **Code:** plain monospace editor.
- **New project:** open Eidos chat first (not a blank README with prompts). Default editor view may show an empty spec list or a brief “Start in chat with Eidos” notice.

### Top bar

Project name, back, drawer menu, Edit/View (markdown), **Preview**, Eidos chat.

**Phase action button** (replaces legacy Start Build / Sync to code):

| Current phase | Button label | Enabled when |
|---------------|--------------|--------------|
| Intake | **Generate specs** | User confirmed alignment in chat (or explicit tap) |
| Spec review | **Accept specs** | User ready to build |
| Post–spec accept | **Build design** | Specs accepted |
| Design review | **Accept design** | User satisfied with Preview layout |
| Post–design accept | **Build logic** | Design accepted + docs aligned |
| Logic review | **Accept logic** | User satisfied with behavior |
| Complete | **Update** | Enters update/edit maintenance phase |
| Update/edit | **Accept update** | Pending diffs cleared; two-tap confirm; then doc align → Complete |

**Eidos mode chips** in the workshop sheet: **Chat**, **Plan**, **Edit** (Debug optional from logic build onward). **Build design** and **Build logic** are primary-button actions, not chips. See [WORKSHOP_MODES.md](WORKSHOP_MODES.md) and [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md).

### Preview

Local WebView renders composite HTML (`PanelHtmlComposer`). Console errors are captured for Debug mode.

### Chat

Eidos bottom sheet is the primary surface for intake and iteration. Each phase may use a dedicated chat thread or a phase-scoped “new chat” nudge when pivoting (see WORKSHOP_MODES history policy).

---

## Onboarding — chat first

**Do not** pre-fill `README.md` with three questions the user must edit.

Eidos opens intake in **Chat** mode with a structured script:

1. **What** do you want to build?
2. **Why** do you want it?
3. **How** should it work?

The user answers in conversation. When user and Eidos agree, the user taps **Generate specs**. Eidos writes the five spec files (short, capped) from the intake summary stored in project prefs — not from a README form.

---

## Spec files

Generated by Eidos at **Generate specs**; revised only at **approval gates** (and in Update → Specs), not during design/logic iteration.

| File | Purpose | Greenfield cap (guideline) |
|------|---------|----------------------------|
| `README.md` | What it is and why | ~400–600 chars |
| `STRUCTURE.md` | File list | ~300 chars |
| `FEATURES.md` | Feature bullets | ~800 chars |
| `FLOW.md` | Interaction steps | ~800 chars |
| `DESIGN.md` | Layout + mobile/WebView rules | ~1000 chars |

**Prompt rule:** Summarize intent in **human language** — do not transcribe HTML/JS into the specs.

Caps apply during greenfield and Update spec passes. After **Finish**, caps may relax slightly for power users, but specs should stay scannable.

`DESIGN.md` must reference Android WebView layout rules; Eidos pulls condensed rules from `WorkshopAndroidLayoutRules` / `PanelPlatformSpec` when writing specs — users never hand-copy platform docs.

---

## Built-in platform guidance

New projects include scaffolds and embedded instructions so Eidos aligns with OptimalX without extra user steps:

| Source | Role |
|--------|------|
| `FolderRepository` `WORKSHOP_*_SCAFFOLD` | Starter `index.html`, `style.css`, `bridge.js`, `script.js` with viewport, touch, and bridge patterns |
| `WorkshopAndroidLayoutRules.kt` | Phone touch layout, canvas sizing, scroll-safe body rules — injected into DESIGN.md seeds and Eidos Plan/Design prompts |
| `PanelPlatformSpec.kt` | Bundling order (`bridge.js` before `script.js`), forbidden APIs, workshop mode instructions |
| `PANEL_PLATFORM.md` | Authoritative runtime contract when docs and behavior diverge — **Kotlin wins** |

Design build and logic build prompts use the appropriate subset: full platform checklist only when writing runtime files, not during Chat or spec-only Plan.

---

## Design build vs logic build

### Design build (shell)

**Goal:** Layout, screens, buttons, inputs, labels, spacing — visible in Preview.

**Includes:** `index.html`, `style.css`, minimal `script.js` (stub handlers, placeholder text).

**Excludes:** Business logic, calculations, real `getState`/`runAction` behavior.

User validates in Preview, then chat + **Design edit** until **Accept design**.

During design review, Eidos **must not** read or write `.md` files. Context = intake summary + current HTML/CSS/JS + user message.

### Logic build

**Goal:** Wire behavior, state, calculations, bridge actions.

**Includes:** `script.js`, `bridge.js` as needed.

Triggered after **Accept design** and a short **align docs from code** pass (DESIGN.md / FLOW.md only).

---

## Doc alignment (approval only)

**There is no standing “Sync to code” button.**

| Trigger | Direction | What updates |
|---------|-----------|--------------|
| Accept design | Code → spec | Short DESIGN.md (+ FLOW if interaction changed) |
| Finish | Code → spec | All `.md` files — snapshots for the user to skim |
| Update → Accept (any section) | Code → spec | Relevant `.md` for that section only |

Specs are **human-readable snapshots**, not live build inputs during iteration. If the user later wants to change behavior, they use Update mode (chat → edit code → accept → align docs) — same pattern as greenfield.

Manual `.md` edits by the user are allowed but uncommon; Eidos should not treat stale specs as authority over current code.

---

## Debug (logic review onward)

**Debug** mode becomes available starting **LOGIC_BUILD** / **LOGIC_REVIEW** (not during intake or design shell work).

Use when Preview shows wrong behavior, console errors, or bridge issues.

Debug workflow uses the workshop **console buffer**, **`call_panel_function`**, and targeted runtime edits — not a remote JS sandbox.

When logic looks correct in isolated reasoning but Preview/bridge still fails, Eidos should classify **platform gap** (Kotlin ↔ JS bridge, WebView, bundling) vs panel bug — use `PanelPlatformSpec.platformIssueTemplate()` for gaps.

---

## Update/edit mode (complete panels)

After the first build, the project is **Complete**. To change it in the workshop, tap **Update** (enters **Update/edit**):

1. **Plan** (optional): draft or revise `IMPLEMENTATION_PLAN.md` and spec `.md` files — no runtime writes.
2. **Accept implementation plan** when the plan is ready (gate — **Phase 4.5**, see [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md#phase-45--implementation-plan-execution-update--plan-mode)).
3. **Build plan** (chat banner / top bar): One tap — Eidos executes the accepted plan on runtime files with **Auto-Continue** across plan phases (direct disk; review in Preview when done).
4. **Chat** / **Edit** for ad-hoc discussion or fixes between plan batches.
5. User tests in **Preview** as needed (workshop test only — not Gallery runtime).
6. User taps **Accept update** when the batch is done (all diffs resolved; second tap confirms and starts doc align).
7. Eidos runs a **sync spec docs from code** pass (all `.md` snapshots updated from current runtime files).
8. Phase returns to **Complete**. The user can start another **Update / edit** session later.

Same rule as greenfield iteration: **spec `.md` files are not rewritten on every Eidos edit** — only on **Accept update** after diff review. The implementation plan file is maintained in **Plan**; execution is **Build plan**, not silent auto-run.

There is no separate Specs / Design / Logic picker in the UI; maintenance is one **Update/edit** flow (internally `WorkshopProjectPhase.UPDATE`).

---

## File storage

`files/workshop/{subfolderId}/{filename}` with `FileReference` rows in Room.

---

## Adding panels to the editor

When complete, users add the panel to any regular subfolder via **Add Panel** (dashboard icon). Assignments persist in `custom_panel_assignments`.

---

## WebView error capture

Preview `onConsoleMessage` → error buffer passed to Eidos in Debug mode.

---

## Panel bridge protocol (Eidos runtime)

Android injects `window.OptimalXPanelBridge`. Panels implement `getState` and `runAction`; Eidos uses `call_panel_function`. Full contract: [PANEL_PLATFORM.md](PANEL_PLATFORM.md).

```javascript
async function getState(args) { /* current state */ }
async function runAction(args) { /* execute action */ }
```

User must open Preview (or custom panel tab) for `call_panel_function` to target a live runtime.

---

## Change review (planned)

Eidos writes in Build / Design / Edit / Debug should land in a **pending change set** (accept/reject, checkpoints) per [DIFF_REVIEW.md](DIFF_REVIEW.md). Chat stays read-only.

---

## What does not exist in v1

- Syntax highlighting / inline error indicators in editor
- Terminal
- External API calls from panels
- Asset/image import for panels
- Widget access to Workshop
- Legacy **Sync to code** primary action

Workshop projects sync file bytes to the PC via **Settings → Sync with Desktop** or the per-project drawer (**Sync workshop files to PC**). Tier 1 Push/Pull syncs metadata only; bytes use the Files API (Option C). PC→phone: Pull then **Sync workshop files from PC**. Per-panel share endpoints inside an individual project are not in v1.

---

## Agentic loop

Chess-piece AgentByte phases in [archive/agent_loops/PANEL_WORKSHOP_LOOP.md](../archive/agent_loops/PANEL_WORKSHOP_LOOP.md) were **removed / never shipped**. v2 workshop UX uses **phases + modes** in this doc and [WORKSHOP_MODES.md](WORKSHOP_MODES.md).

---

## Document changelog

| Version | Date | Notes |
|---------|------|--------|
| 1 | 2026-05-18 | Initial README-first workshop UX |
| 2 | 2026-05-25 | Phased lifecycle: chat intake, design-before-logic, doc align on approval, remove Sync to code, Debug from logic build |
| 3 | 2026-06-06 | Kimi Formula `quickjs` removed from product and codebase |
