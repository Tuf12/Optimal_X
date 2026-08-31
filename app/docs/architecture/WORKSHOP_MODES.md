# WORKSHOP_MODES.md

**Panel Workshop — project phases, Eidos modes, prompts, and tool gating**

| Field | Value |
|--------|--------|
| **Status** | **v2 spec** — phased workflow (partial legacy v1 implemented; see [Migration from v1](#migration-from-v1)) |
| **Audience** | Product, Eidos prompt authors, Kotlin implementers |
| **Related** | [PANEL_WORKSHOP.md](PANEL_WORKSHOP.md), [PANEL_PLATFORM.md](PANEL_PLATFORM.md), [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](../implementation/PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md), [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md), [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md) Phase 3.6 |
| **Explicitly out of scope** | Chess-piece AgentByte routing (removed from shipping app) |

---

## Problem this solves

v1 workshop behavior collapsed too much into one step:

- README-as-form for intake
- **Start Build** → all spec `.md` files + full runtime in one Eidos turn
- **Sync to code** when markdown digest changed — noisy, often wrong, poor UX for non-developers

That caused:

1. **Token waste** — full platform instructions + large specs + long history every turn
2. **Doc/code drift** — specs updated while user iterates in Preview; later edits revert each other
3. **Wrong mode** — small layout tweak rewrites all files; chat still writes files when user wanted to talk
4. **Design/logic entangled** — user cannot approve layout before paying for behavior

**v2** splits **project phase** (lifecycle gates) from **Eidos mode** (chat intent within a phase). Docs align **code → spec on user approval only**. There is **no Sync to code**.

**Auto-Continue (in progress):** [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](../implementation/PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) adds a second axis — **execution profile** (build run vs workshop edit) — on top of phase + mode. Build kickoffs chain Eidos chunks with LLM handoffs; interactive edit keeps Diff Review and short sends.

---

## Two execution profiles (build run vs workshop edit)

Distinct from Eidos chips and from `WorkshopProjectPhase` alone. See the Auto-Continue plan for full tables.

| Profile | When | Auto-Continue | Diff Review mid-loop |
|---------|------|---------------|----------------------|
| **Build run** | Generate specs, Build design/logic, Build plan kickoffs | **Yes** — chunk ↔ handoff ↔ chunk | **No** — review at Accept / Preview |
| **Workshop edit** | Edit fixes after complete; user-driven changes | **No** (or minimal) | **Yes** — proposals until accepted |

Kotlin: [WorkshopReviewPolicy.kt](../../src/main/java/com/example/optimalx/data/revision/WorkshopReviewPolicy.kt) (`shouldReview`). Phase 1.5: explicit `WorkshopExecutionProfile` in ViewModel.

---

## Two layers: phase vs mode

| Layer | Enum (planned) | Drives |
|-------|----------------|--------|
| **Project phase** | `WorkshopProjectPhase` | Top-bar primary button, which files Eidos may see, when doc alignment runs |
| **Eidos mode** | `WorkshopEidosMode` | Prompt block, tool allowlist, history trim — user chip or phase default |

```text
WorkshopProjectPhase          Eidos chips (Chat · Plan · Edit)
─────────────────────         ───────────────────────────────
INTAKE                        CHAT only
All other phases              CHAT, PLAN, EDIT

BUILD_DESIGN / BUILD_LOGIC are internal kickoff modes (primary buttons) — not chips.
Legacy DESIGN / DEBUG enum values map to Edit for prefs migration only.
```

---

## Project phases (`WorkshopProjectPhase`)

Persist per subfolder in `WorkshopProjectPreferences`.

```kotlin
enum class WorkshopProjectPhase {
    INTAKE,
    SPEC_REVIEW,
    DESIGN_BUILD,
    DESIGN_REVIEW,
    LOGIC_BUILD,
    LOGIC_REVIEW,
    COMPLETE,
    UPDATE, // maintenance / update/edit (single UI flow)
}
```

### Phase contracts

| Phase | Source of truth | Eidos may read `.md` | Eidos may write `.md` | Eidos may write code | Doc align |
|-------|-----------------|----------------------|----------------------|----------------------|-----------|
| INTAKE | Chat + stored intake summary | ✗ | ✗ | ✗ | — |
| SPEC_REVIEW | Spec files | ✓ | ✓ (Plan) | ✗ | — |
| DESIGN_BUILD | Intake + specs | ✓ (brief) | ✗ | shell only | — |
| DESIGN_REVIEW | **Code** | ✓ (Plan) | ✓ (Plan) | HTML/CSS/stub JS (Edit) | on **Accept design** |
| LOGIC_BUILD | Code layout + specs snapshot | ✓ (brief) | ✗ | logic files | on enter (from design) |
| LOGIC_REVIEW | **Code** | optional read | ✗ | runtime | on **Finish** |
| COMPLETE | Code | optional | rare manual | Edit/Debug/Chat | — |
| UPDATE (update/edit) | **Code** during session | ✓ (Plan) | ✓ (Plan); frozen for Edit until Accept | runtime via diff review | on **Accept update** (all `.md`) |

**Intake summary:** Persist `workshop_{id}_intake_summary` when user taps **Generate specs** — later phases use this instead of replaying the entire chat thread.

---

## Eidos modes (`WorkshopEidosMode`)

**User-visible chips:** **Chat**, **Plan**, **Edit** only.  
**Primary-button kickoffs only:** `BUILD_DESIGN`, `BUILD_LOGIC` (not shown as chips).  
**Legacy enum values** `DESIGN`, `BUILD`, `DEBUG` remain for prefs migration and map to **Edit**.
**Plan artifact (Phase 4):** optional `IMPLEMENTATION_PLAN.md` — phased steps, files touched, test notes; Plan may read any file, write spec `.md` only.

Canonical contracts: [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md#eidos-modes-three-chips).

### Mode contracts

#### Chat

- **Goal:** Intake, brainstorm, explain — no silent edits.
- **Writes:** None.
- **Reads:** Optional `workshop_read_file` if user asks.

#### Plan

- **Goal:** Write or revise spec files; optional phased implementation plan (`IMPLEMENTATION_PLAN.md`).
- **Writes:** `README.md`, `STRUCTURE.md`, `FEATURES.md`, `FLOW.md`, `DESIGN.md`, optional `IMPLEMENTATION_PLAN.md`.
- **Reads:** Any project file (spec + runtime) for comparison; **must not** write runtime files.
- **Platform:** `PanelPlatformSpec.eidosPlanModeInstructions()` + `WorkshopAndroidLayoutRules.EIDOS_CONTEXT_SUMMARY` for DESIGN.md — not full Build checklist.
- **Spec rule:** Summarize intent in **human language**; do not rewrite code in human language. Enforce greenfield char caps in prompt.
- **UPDATE execution:** After **Accept implementation plan**, one **Build plan** tap runs all phases with Auto-Continue (build-run profile — direct disk, no Diff Review mid-run). See [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](../implementation/PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md).

#### Build design (`BUILD_DESIGN`)

- **Goal:** Produce layout shell — structure visible in Preview, no business logic.
- **Writes:** `index.html`, `style.css`, stub `script.js` (placeholders, empty/stub `panelHandleAction`).
- **Must not:** Full calculations, real game/business rules, or bulk `.md` rewrites.
- **Platform:** Full layout/bundling/bridge-order rules from `PanelPlatformSpec`; use existing **scaffolds** as baseline (`WORKSHOP_*_SCAFFOLD` in `FolderRepository`).
- **Reads:** Intake summary + spec excerpts; avoid loading all `.md` in full.

#### Design (`DESIGN`)

- **Goal:** Iterative layout tweaks during **DESIGN_REVIEW**.
- **Writes:** `index.html`, `style.css`, minimal `script.js` for DOM structure only.
- **Must not:** Read or write any `.md` file (tool executor rejects).
- **Must not:** Re-open full spec audit or re-apply logic build.

#### Build logic (`BUILD_LOGIC`)

- **Goal:** Wire behavior after design accepted.
- **Writes:** `script.js`, `bridge.js` as needed; touch HTML/CSS only if required for behavior hooks.
- **Reads:** Current runtime files + short intake/spec snapshot (not full doc dump).
- **Platform:** Bridge contract, `getState`/`runAction`, forbidden APIs.

#### Edit

- **Goal:** Minimal change for **current user message** during logic review or Complete.
- **Writes:** Only files clearly required.
- **Must not:** Full platform conformance sweep; must not touch `bridge.js` unless bridge-related.

#### Debug

- **Available from:** `LOGIC_BUILD` onward (not intake, spec, or design shell phases).
- **Goal:** Fix broken Preview, calculations, bridge, scroll; separate panel bug vs platform gap.
- **Writes:** Targeted runtime fixes.
- **Reads:** Runtime files, console buffer, `call_panel_function`.
- **Platform gaps:** When panel JS looks correct but Preview/bridge fails, use `platformIssueTemplate()` — surfaces Kotlin ↔ JS communication gaps for platform work.

#### Chat / Plan nudge

If user asks for code during Chat, suggest **Design**, **Edit**, or the phase-appropriate build mode — do not write files in Chat.

---

## Tools per mode

| Tool | Plan | Build design | Design | Build logic | Edit | Debug | Chat |
|------|:----:|:------------:|:------:|:-----------:|:----:|:-----:|:----:|
| `workshop_read_file` | ✓ | ✓ | ✓ (no `.md`) | ✓ | ✓ | ✓ | optional |
| `workshop_write_file` | `.md` | shell files | shell files | logic files | ✓ | ✓ | ✗ |
| `workshop_create_file` | `.md` | shell | shell | logic | ✓ | ✓ | ✗ |
| `call_panel_function` | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |

Enforcement:

- `EidosToolCatalog.toolsForWorkshopMode(mode, phase)`
- `RoomToolExecutor.enforceWorkshopModeForWrite()` — reject `.md` in **DESIGN** phase/mode; reject runtime in **Plan**.

Phase-level `.md` freeze during **DESIGN_REVIEW** overrides Plan read access unless user explicitly enters Update → Specs.

---

## Prompts per mode

Extend `PanelPlatformSpec.eidosInstructionsForMode()`:

| Function | Mode / phase |
|----------|----------------|
| `eidosPlanModeInstructions()` | Plan |
| `eidosBuildDesignInstructions()` | BUILD_DESIGN |
| `eidosDesignModeInstructions()` | DESIGN (design review) |
| `eidosBuildLogicInstructions()` | BUILD_LOGIC |
| `eidosEditModeInstructions()` | Edit |
| `eidosDebugModeInstructions()` | Debug (+ platform gap note) |
| `eidosChatModeInstructions()` | Chat |

`EidosApiClient.buildWorkshopPanelContext()` must:

1. Read `WorkshopProjectPhase` + active `WorkshopEidosMode`.
2. Append **only** that mode’s instruction block.
3. Include validation report in **Build logic** and **Debug**, not every Edit turn.
4. Inject **intake summary** instead of README body during intake/design when `.md` is frozen.
5. Embed **WorkshopAndroidLayoutRules** / scaffold references for any mode that writes HTML/CSS.

### Spec generation prompt (Generate specs)

On primary action from INTAKE:

- Write all five `.md` files from intake summary.
- Enforce char caps (see [PANEL_WORKSHOP.md](PANEL_WORKSHOP.md#spec-files)).
- DESIGN.md includes Android WebView layout section from platform rules — not copied from user chat verbatim.

### Doc alignment prompts (approval gates)

Run on **Accept design**, **Finish**, and **Update section accept** — not during iteration:

- Input: current runtime files (and optionally prior spec).
- Output: short human-language snapshots; **code → spec** only.
- Must not trigger a full code rewrite from specs.

---

## Removed: Sync to code

**Do not implement or restore:**

- `canSyncDocsToCode` as a primary top-bar action
- `WorkshopEditorViewModel.applyPrimaryBuildAction` doc→code branch
- Auto-suggest **Build** because markdown digest changed

Users change behavior by talking to Eidos in **Edit** / **Design** / **Debug**. Specs update on **approval gates** only.

Optional future: warn if user manually edits `.md` while code has diverged — informational only, not a sync button.

---

## Phase-driven UI

### Top bar (replaces Start Build / Sync to code)

See [PANEL_WORKSHOP.md](PANEL_WORKSHOP.md#top-bar) for button labels per phase.

### Eidos sheet mode chips

```text
[ Plan ] [ Design ] [ Edit ] [ Debug ] [ Chat ]     Phase: Design review ▾
```

- Phase label shows current `WorkshopProjectPhase` (human-readable).
- Chips select `WorkshopEidosMode` within allowed set for that phase.
- **Debug** chip disabled until `LOGIC_BUILD` or later.

### Auto-suggest (revised)

Heuristics suggest mode within the **current phase allowlist** — never suggest doc→code sync.

```kotlin
fun suggestWorkshopMode(
    phase: WorkshopProjectPhase,
    userMessage: String?,
    userOverride: WorkshopEidosMode?,
): WorkshopEidosMode {
    userOverride?.let { return it }
    return when (phase) {
        INTAKE, SPEC_REVIEW -> when {
            userMessage looks like chat -> CHAT
            else -> PLAN
        }
        DESIGN_BUILD -> BUILD_DESIGN
        DESIGN_REVIEW -> when {
            userMessage looks like debug -> /* still DESIGN unless LOGIC */ DESIGN
            userMessage looks like chat -> CHAT
            else -> DESIGN
        }
        LOGIC_BUILD -> BUILD_LOGIC
        LOGIC_REVIEW, COMPLETE -> when {
            userMessage looks like debug -> DEBUG
            userMessage looks like chat -> CHAT
            else -> EDIT
        }
        UPDATE -> /* from update sub-scope */ ...
    }
}
```

Remove `canSyncDocsToCode` and `initialBuildSent` as mode drivers; use `WorkshopProjectPhase` instead.

---

## Conversation history

Keep v1 workshop trim policies:

| Policy | Value |
|--------|--------|
| Turn nudge | After 10 user messages → new-chat nudge |
| Mode-aware trim | Last 2 user turns (`HISTORY_KEEP_EXCHANGE_PAIRS = 2`) |
| System anchor | Current phase + mode + one-line task each send |

Prefer **intake summary** and **checkpoint doc snapshots** over replaying long threads when entering a new phase.

---

## Implementation map

| Piece | Path | v2 notes |
|-------|------|----------|
| Project phase enum | `data/eidos/WorkshopProjectPhase.kt` | ✅ Phase 0 |
| Mode enum | `data/eidos/WorkshopEidosMode.kt` | ✅ Phase 0 — `BUILD_DESIGN`, `BUILD_LOGIC`, `DESIGN` |
| Prefs | `WorkshopProjectPreferences.kt` | ✅ Phase 0 — phase, intake, update section |
| Phase + mode resolver | `data/eidos/WorkshopEidosModeResolver.kt` | Phase-aware suggest; remove sync digest |
| Per-mode prompts | `PanelPlatformSpec.kt` | New design/logic/align prompts |
| Tool allowlist | `EidosToolCatalog.kt` | ✅ Phase 0 modes wired |
| Write guards | `RoomToolExecutor` | ✅ DESIGN rejects `.md` writes; phase freeze in Phase 3 |
| API wiring | `EidosApiClient.kt` | `workshopProjectPhase`, intake summary |
| UI | `WorkshopEditorScreen.kt` | Phase primary button; remove Sync to code |
| ViewModel | `WorkshopEditorViewModel.kt` | ✅ Phase 0 — `projectPhase` StateFlow |
| Eidos chat VM | `EidosChatViewModel.kt` | ✅ Phase 0 — `workshopProjectPhase` StateFlow |

**Deferred:** `panel_verify` tool; DIFF_REVIEW pending batches (see [DIFF_REVIEW.md](DIFF_REVIEW.md)).

---

## Migration from v1

| v1 | v2 |
|----|-----|
| README three questions | Chat intake + `intake_summary` |
| `isReadmeReady` + Start Build | Phase buttons; **Generate specs** / **Build design** / … |
| `initialBuildSent` | `WorkshopProjectPhase` |
| `canSyncDocsToCode` | **Removed** — align docs on approval |
| Single `BUILD` mode | `BUILD_DESIGN` + `BUILD_LOGIC` |
| Debug anytime | Debug from logic build onward |
| Mode status “Implemented v1” | Phase machine **not yet fully implemented** — this doc is target |

Existing projects with `initialBuildSent == true` migrate to `WorkshopProjectPhase.COMPLETE`.

---

## Relationship to `PanelPlatformSpec`

| Concern | Owner |
|---------|--------|
| WebView bundling, bridge, scroll, forbidden APIs | `PanelPlatformSpec` / [PANEL_PLATFORM.md](PANEL_PLATFORM.md) |
| Android layout rules in scaffolds + DESIGN.md | `WorkshopAndroidLayoutRules.kt` |
| **When** full platform instructions apply | Phase + mode (design shell vs logic vs debug) |
| File-level write restrictions | Phase + mode + `RoomToolExecutor` |
| Built-in scaffold content | `FolderRepository.WORKSHOP_*_SCAFFOLD` |

Build design and build logic use runtime platform instructions; Plan uses layout summary for DESIGN.md; Design edit uses compact layout reminder; Chat uses almost none.

---

## Separation from AgentByte chess

Chess-piece routing (`ChessPiece`, `PANEL_WORKSHOP_LOOP.md` King/Rook/Queen) remains **deferred**. Workshop v2 uses **phases + modes** only. Do not wire workshop sends through `chess_taxonomy` or piece classifiers.

---

## Change review (related)

Build / Design / Edit / Debug writes should use **pending change sets** per [DIFF_REVIEW.md](DIFF_REVIEW.md). Chat stays read-only.

---

## Document changelog

| Version | Date | Notes |
|---------|------|--------|
| 1 | 2026-05-18 | Plan/Build/Edit/Debug/Chat; auto-suggest; Sync to code; chess separation |
| 2 | 2026-05-25 | Phased lifecycle; BUILD_DESIGN/BUILD_LOGIC/DESIGN modes; doc align on approval; remove Sync to code; Debug from logic build; intake summary; `.md` freeze during design review |
| 3 | 2026-06-06 | Kimi Formula `quickjs` removed from product and codebase |
