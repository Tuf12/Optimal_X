# Prompt architecture fix plan

| Field | Value |
|--------|--------|
| **Status** | **Superseded (2026-06-22)** — pre-router content/ordering goals shipped via [PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md) Phases 1–5. Use router plan + [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) for runtime truth. |
| **Audience** | Prompt authors, Kotlin implementers, coding agents |
| **Scope** | Prompt **text, ordering, and composition** — not HTTP transport (that shipped via [PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](./PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md)) |
| **Related** | [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md), [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md), [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](./PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) (superseded), [CHUTES_IMPLEMENTATION_PLAN.md](./CHUTES_IMPLEMENTATION_PLAN.md) |

> **Superseded for assembly/routing (2026-06-22):** Per-scope prompts, ontology, tools, and composer → [PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md). Items below describe the **2026-06-11 pre-router fix** that motivated the router migration; all six original problems are addressed in shipping code. Phase 4 tool-hop budget / auto-continue retired 2026-06-14.

---

## Executive summary (historical — resolved via scope router)

The transport layer (pre-router) was in good shape. The pre-router prompt problems listed below were fixed in the **2026-06-11 architecture pass** and then **consolidated into `EidosPromptComposer`** (router Phases 1–5, 2026-06-22):

1. ~~Base identity prompt too thin~~ → `EidosIdentityPrompt` + per-profile ontology in registry.
2. ~~Volatile data interleaved with stable blocks~~ → `EidosPromptComposer` four-section layout.
3. ~~Workshop retrieval policy duplicated~~ → single tail append in `WorkshopPanelContext` (Phase 4.1).
4. ~~Repeated negatives / no delimiters~~ → section headers + deduped mode instructions.
5. ~~No tool-hop budget line~~ → shipped in `PanelPlatformSpec` handoff/edit blocks (2026-06-11).
6. ~~No patch worked example~~ → shipped in `EIDOS_WORKSHOP_PATCH_POLICY` (2026-06-11).

---

## Ground truth — where prompt text lives today (2026-06-22)

| Source | Produces |
|--------|----------|
| [EidosScopeRouter + EidosScopeProfileRegistry](../../src/main/java/com/example/optimalx/data/eidos/prompt/) | Profile selection + ontology/location/tool matrix |
| [EidosPromptComposer.compose()](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosPromptComposer.kt) | Sectioned system prompt for all user-facing + internal profiles |
| [EidosIdentityPrompt](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosIdentityPrompt.kt) | Shared identity text |
| [EidosContextLimits.TOOL_FIRST_CONTEXT_RULES](../../src/main/java/com/example/optimalx/data/eidos/EidosContextLimits.kt) | Tool-first rules (non-workshop, non-internal) |
| [WorkshopPanelContext.buildVolatileContext()](../../src/main/java/com/example/optimalx/data/eidos/WorkshopPanelContext.kt) | Workshop volatile blocks + **single** retrieval policy tail |
| [PanelPlatformSpec](../../src/main/java/com/example/optimalx/data/eidos/PanelPlatformSpec.kt) | Workshop mode instructions (retrieval policy **not** duplicated) |
| [EidosApiClient.assembleSystemPrompt()](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) | Legacy passthrough fallback only |

### Known violations (2026-06-11) — ✅ resolved 2026-06-22

- Workshop retrieval dedupe: ✅ single `EIDOS_WORKSHOP_RETRIEVAL_POLICY` tail (Phase 4.1).
- PROMPT_SYSTEM checklist drift: ✅ Phase 6 doc sync.
- Daily memory / parent catalog “Target only”: ✅ `DailyMemoryContext`, `ParentFolderContext`.

---

## Phase 1 — Base prompt + canonical rule text — ✅ shipped 2026-06-11

**Files:** [EidosContextLimits.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosContextLimits.kt) (`BASE_SYSTEM_PROMPT`, `CORE_BEHAVIORAL_RULE`), [EidosChatViewModel.kt](../../src/main/java/com/example/optimalx/ui/eidos/EidosChatViewModel.kt), [WidgetVoiceService.kt](../../src/main/java/com/example/optimalx/widget/WidgetVoiceService.kt), [WorkshopPreviewPanel.kt](../../src/main/java/com/example/optimalx/ui/workshop/WorkshopPreviewPanel.kt), [EidosApiClient.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt)

**Shipped notes:** canonical block lives in `EidosContextLimits.BASE_SYSTEM_PROMPT`; all three send entry points (chat ViewModel, widget voice, workshop bridge-event worker) now share it — the workshop worker previously used a drifted inline copy. Sentinel coverage: [PromptAssemblySentinelTest.kt](../../src/test/java/com/example/optimalx/data/eidos/PromptAssemblySentinelTest.kt).

1. Expand `buildBasePrompt()` to include, verbatim:
   - Identity + conversational tone line (PROMPT_SYSTEM "conversational, not task-pushing").
   - Core behavioral rule: `Search before reading. Read before writing. Never assume content — retrieve it.`
   - Memory pointer lines: long-term memory and journal are retrieved via `search_semantic` / `read_long_term_memory` / `read_journal` — never assumed in context.
   - Web-search policy line: only when the user explicitly requests current info or the answer clearly requires post-training data.
   - Grounding line: when tools return nothing relevant, say so — do not fabricate file/note content.
2. Keep it ≤ ~20 lines. The base prompt is the only block every scope inherits — it must stay small and stable (cache prefix anchor).
3. Move the per-provider web notes in `assembleSystemPrompt()` to state **only the active provider's truth** (drop the "(xAI, OpenAI, Anthropic, Kimi)" cross-provider list).

**Done when:** PROMPT_SYSTEM.md checklist rows "Verbatim core behavioral rule", "Web search wording", "Conversational base prompt tone", "Memory tool pointer lines" flip to ✅.

---

## Phase 2 — Deduplicate workshop retrieval policy — ✅ shipped 2026-06-11

**Files:** [EidosApiClient.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt), [EidosContextLimits.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosContextLimits.kt), [PanelPlatformSpec.kt](../../src/main/java/com/example/optimalx/data/eidos/PanelPlatformSpec.kt)

1. Pick **one** canonical block: keep `EIDOS_WORKSHOP_RETRIEVAL_POLICY` (it is the more specific one) and delete `WORKSHOP_TOOL_FIRST_CONTEXT_RULES` from the workshop path in `assembleSystemPrompt()` (non-workshop scopes keep `TOOL_FIRST_CONTEXT_RULES`).
2. Audit composed workshop prompts for repeated negatives; one statement each for:
   - ".md write restrictions" → single positive scope line per phase ("Writable this phase: …").
   - "never ask the user to edit files" → only in `EIDOS_WORKSHOP_RUNTIME_EDIT_POLICY`.
   - Chutes no-web notice → only in the provider block, not repeated in web-scope rules + page-fetch instruction.
3. Add a unit test that renders representative workshop prompts (edit/build/chat × 2 phases) and asserts key sentinel phrases appear **exactly once**.

**Done when:** rendered workshop prompt contains one retrieval policy block; sentinel-uniqueness test green; net prompt chars reduced (assert in test: composed prompt smaller than before-fix snapshot).

---

## Phase 3 — Stable-prefix-first ordering + sectioned structure — ✅ shipped 2026-06-11

**Shipped notes:** new pure composer [EidosPromptSections.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosPromptSections.kt) (`compose`, `stablePrefix`, section header constants). `assembleSystemPrompt` now fills four section lists; `buildWorkshopPanelContext` returns `WorkshopPromptBlocks(stable, volatileBlocks)` and `buildPanelBridgeContext` returns `PanelBridgePromptBlocks(stable, recentEventsBlock)` — bridge events capped at 3 most recent (`MAX_PROMPT_BRIDGE_EVENTS`) and labeled "Panel events this turn". Volatile section also carries: loaded web URL, DumpEdit buffer block, Diff Review count, editor tab/excerpt, spec cap report + accept gate, new-chat nudge, editor surface hint, memory depth + trim notices. The authoritative closer is emitted by the composer for **all scopes** (previously workshop-only). Manifest already deterministically sorted (`formatFileManifest` sorts by lowercase name) — no change needed. Stable-prefix + ordering tests in [PromptAssemblySentinelTest.kt](../../src/test/java/com/example/optimalx/data/eidos/PromptAssemblySentinelTest.kt). **Manual verification still open:** API trace prefix diff + `cached_tokens` uplift per provider family (run one workshop build + one edit session).

**Files:** [EidosApiClient.assembleSystemPrompt()](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt), [EidosApiClient.buildWorkshopPanelContext()](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt)

**Problem:** volatile blocks (bridge events w/ timestamps, Diff Review pending count, open-file excerpt, spec cap report, new-chat nudge, history-trim notice) sit between stable blocks → first changed byte invalidates the provider prefix cache for everything after it, every turn.

1. Reorder assembly into two labeled segments:

```
## Identity & rules            (stable across turns)
## Provider & scope            (stable within a conversation)
## Project context             (stable within a workshop phase: manifest, mode instructions, spec orientation)
## This turn                   (volatile: open-file excerpt, Diff Review count, bridge events, nudges, memory-depth/trim notices)
```

2. Emit markdown `##` section headers between segments (delimiters double as model navigation aids — fixes the "no structural delimiters" issue in the same pass).
3. `buildPanelBridgeContext()`: move `recentEvents` (timestamps + payloads) into the volatile tail; registered-functions list stays in the stable segment. Cap events at the 3 most recent.
4. Manifest ordering inside `buildWorkshopPanelContext()`: ensure file list is sorted deterministically (id asc) so the stable segment is byte-identical between turns when files haven't changed.
5. Keep "User's current request is authoritative for this turn." as the final line of the volatile section.

**Verification:** API trace — two consecutive workshop sends in the same phase must produce **byte-identical** prompt text up to the `## This turn` marker. Manual check via `EidosApiTraceRecorder` output; Kimi/Anthropic usage logs should show higher `cached_tokens` on turn N+1 hop 1.

**Risk:** reordering changes what the model sees first; run one manual workshop build + one edit session per provider family before marking shipped.

---

## Phase 4 — Tool-hop budget awareness + handoff priming — ✅ shipped 2026-06-11

**Shipped notes:** budget line added to `EIDOS_WORKSHOP_HANDOFF_INSTRUCTIONS` (covers all four kickoff prompts: generate specs, build design, build logic, build plan) and to the edit auto-continue block in `eidosPhaseEditInstructions` (Diff-Review edit phases). References `WorkshopToolRoundPause.WORKSHOP_EDIT_BUILD_MAX_TOOL_ROUNDS` — no hardcoded number. No per-hop budget announcements (volatile, cache-busting). Sentinel tests: exactly once in kickoff + reviewed-edit prompts, zero in Chat prompts. **Manual verification still open:** Chutes plan-kickoff chunk ends with Handoff section instead of tool-cap pause.

**Files:** [PanelPlatformSpec.kt](../../src/main/java/com/example/optimalx/data/eidos/PanelPlatformSpec.kt), [WorkshopToolRoundPause.kt](../../src/main/java/com/example/optimalx/data/eidos/WorkshopToolRoundPause.kt)

**Problem:** the 12-hop cap (`WORKSHOP_EDIT_BUILD_MAX_TOOL_ROUNDS`) is invisible to the model until the pause fires; chunks truncate mid-edit instead of ending at clean boundaries. Matters most on Chutes models (weaker long-loop discipline than Kimi).

1. Add one line to `EIDOS_WORKSHOP_HANDOFF_INSTRUCTIONS` (build/plan kickoffs):
   `Budget: ~${WORKSHOP_EDIT_BUILD_MAX_TOOL_ROUNDS} tool steps per chunk. If the remaining work exceeds it, finish the current file, then stop with the handoff section — do not start a file you cannot finish.`
   Reference the constant — do not hardcode `12` in prose.
2. Edit-profile prompts (`eidosPhaseEditInstructions`): equivalent single line scoped to edit auto-continue.
3. Do **not** announce remaining hops per hop (volatile, cache-busting, low value).

**Done when:** budget line present in build + edit prompts (unit-assert sentinel once); manual: a plan-kickoff chunk on Chutes ends with a Handoff section instead of a tool-cap pause in ≥1 observed run.

---

## Phase 5 — `workshop_replace_string` worked example — ✅ shipped 2026-06-11

**Shipped notes:** 4-line example added to `EIDOS_WORKSHOP_PATCH_POLICY` (one-line change anchored by unchanged surrounding lines, plus "copy lines exactly as read — do not retype from memory"). Generic JS, no project-specific names. Reaches all prompts that compose the patch policy (Diff-Review edit phases); logic-build prompts use the substantial-write policy and intentionally do not carry it. Sentinel tests: exactly once in patch-policy edit prompts, zero in logic-build. **Manual verification still open:** patch failure→retry rate in API traces, directional.

**Files:** [PanelPlatformSpec.EIDOS_WORKSHOP_PATCH_POLICY](../../src/main/java/com/example/optimalx/data/eidos/PanelPlatformSpec.kt)

Applies to all providers — `EIDOS_WORKSHOP_PATCH_POLICY` is provider-agnostic. Every failed patch costs a full retry hop (and one of the 12 chunk-budget hops); the cost is highest on Messages-family providers (Kimi, Anthropic, Chutes) where each hop resends the growing history, and the failure *rate* is highest on Chutes open-weight models, which anchor `oldString` less reliably. Add a compact example to the patch policy (≤10 lines):

```
Example — unique oldString (3 context lines above and below the change):
oldString: "  const score = 0;\n  let level = 1;\n  function resetGame() {"
newString: "  const score = 0;\n  let level = startLevel;\n  function resetGame() {"
```

Keep it generic (no project-specific names). One example only — measure before adding more.

**Done when:** example present once in composed edit/build prompts; monitor `workshop_replace_string` failure-then-retry sequences in API traces before/after (manual, directional).

---

## Phase 6 — Doc truth sync — ✅ shipped 2026-06-11; superseded by router Phase 6 (2026-06-22)

**Original (2026-06-11):** [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md), [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) — Target rows for deferred injects.

**Router Phase 6 (2026-06-22):** [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) verified rows; [PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md) marked complete; this doc marked superseded for assembly.

---

## Explicitly out of scope

- HTTP transport changes (shipped — see transport plan).
- Daily Memory injection on main chat profiles (shipped — `DailyMemoryContext`, router Phase 4.2).
- Journal injection (blocked on write-quality fix).
- Duplicate tool-failure guard / global 13-hop cap (transport-plan leftovers, not prompt text).
- Internal identifier cosmetics (`Active scope: panel_workshop`, enum names) — harmless.

---

## Test plan

| Phase | Automated | Manual |
|-------|-----------|--------|
| 1 | Snapshot test on `buildBasePrompt()` sentinel lines | Smoke chat per provider |
| 2 | Sentinel-uniqueness + size-reduction asserts on composed workshop prompts | — |
| 3 | Stable-prefix test: two assembles same phase/different turn → identical up to `## This turn` | API trace diff; `cached_tokens` uplift on Kimi/Chutes |
| 4 | Sentinel presence (budget line) | One Chutes plan-kickoff run ends with Handoff, not cap pause |
| 5 | Sentinel presence (example block) | Patch-failure rate in traces, directional |
| 6 | — | Doc review |

Suggested new test file: `app/src/test/java/com/example/optimalx/data/eidos/PromptAssemblySentinelTest.kt` (pure-JVM; render prompts via `PanelPlatformSpec` + `EidosContextLimits` constants — `assembleSystemPrompt` itself is DB-bound, so test the block builders it composes).

---

## Sequencing & estimates

| Order | Phase | Size | Risk |
|-------|-------|------|------|
| 1 | Phase 1 (base prompt) | S | Low — additive text |
| 2 | Phase 2 (dedupe) | S | Low — removals; sentinel tests catch loss |
| 3 | Phase 4 (hop budget) | XS | Low |
| 4 | Phase 5 (patch example) | XS | Low |
| 5 | Phase 3 (reorder + sections) | M | **Medium** — changes prompt shape; needs per-provider manual pass |
| 6 | Phase 6 (docs) | S | None |

Phases 1, 2, 4, 5 are independent and can ship in one PR each or together. Phase 3 ships alone with its verification pass.
