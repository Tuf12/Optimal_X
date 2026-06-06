# WORKSHOP_MEMORY.md

**Panel Workshop — categorical preference memory**

| Field | Value |
|--------|--------|
| **Status** | **Design spec** — not implemented in Kotlin yet |
| **Audience** | Product, Eidos prompt authors, Kotlin implementers |
| **Related** | [MEMORY_SYSTEM.md](MEMORY_SYSTEM.md) (general continuity memory), [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md) (workshop UX + phases), [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md) (mode + tool gating), [PANEL_PLATFORM.md](../architecture/PANEL_PLATFORM.md) (runtime contract) |

**Source of truth for tool names (when implemented):** [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt). Until workshop memory tools ship, this document is the product contract.

---

## Purpose

Panel Workshop builds many small interactive panels. Users repeat the same **design and interaction choices** across projects: preferred screen sizes, control types, menu patterns, list vs grid selections, spacing habits, and similar **categorical** facts.

**Workshop Memory** captures those preferences so Eidos can apply them consistently on the next project — without re-asking every time.

This is **not** a replacement for:

| System | Role | Shape |
|--------|------|--------|
| **Daily Memory** | Today’s working context app-wide | Free-form narrative |
| **Long-Term Memory** | Durable user facts and continuity | Free-form narrative |
| **Subfolder memory cache** | Per-subfolder operating ruleset under a parent | One ruleset blob per subfolder |
| **Workshop project prefs** | Phase, intake summary, mode override for **one** project | DataStore keys per subfolder |

Workshop Memory is **structured, categorical, and workshop-scoped**. Eidos maintains it, but only through a **fixed taxonomy and write protocol** — not ad-hoc paragraphs scattered into LTM or project notes.

---

## Design principles

1. **Category-first** — Every stored fact belongs to a known category key. No orphan prose.
2. **Upsert, don’t append** — One canonical value (or small ranked set) per `(category, subcategory)`; updates replace or merge, never duplicate.
3. **Explicit beats inferred** — User-stated preferences outrank guesses. Inferred entries carry lower confidence and expire unless reinforced.
4. **Read narrowly** — Eidos loads only categories relevant to the current workshop phase and task (e.g. layout prefs during design build, not menu prefs during logic-only debug).
5. **Write deliberately** — Writes happen at defined triggers (see [Write protocol](#write-protocol)), not on every chat turn.
6. **Human-readable storage** — Persisted form should be skimmable in the UI (structured markdown or JSON-with-schema), not an opaque blob only the model sees.
7. **Workshop-global, not per-project** — Preferences apply across Panel Workshop projects unless explicitly marked project-local (rare escape hatch).

---

## What belongs here (examples)

| Category | Subcategory (examples) | Example value |
|----------|------------------------|---------------|
| **layout** | `default_viewport`, `min_touch_target`, `safe_area`, `scroll_model` | `"360×640 design baseline; 48dp min touch; body scroll only"` |
| **screens** | `default_count`, `naming`, `transition` | `"Usually 2–3 screens; swipe between; names: Home / Settings"` |
| **controls** | `primary_button`, `numeric_input`, `toggle`, `picker` | `"Primary actions: full-width bottom bar; numbers: stepper not free text"` |
| **selection** | `list_style`, `multi_select`, `empty_state` | `"Lists: compact rows + check on right; always show empty hint"` |
| **menus** | `pattern`, `overflow`, `labels` | `"Bottom nav for ≤4 items; else hamburger; icon + short label"` |
| **typography** | `scale`, `heading_style` | `"System font stack; H1 = panel title only"` |
| **color** | `theme`, `accent_usage` | `"Dark panels preferred; accent only on primary CTA"` |
| **feedback** | `confirm_destructive`, `loading`, `errors` | `"Confirm deletes; inline error under field"` |
| **bridge** | `state_shape`, `action_naming` | `"Flat getState keys; runAction verbs: add/remove/toggle"` |
| **workflow** | `review_habit`, `debug_expectation` | `"User checks Preview on phone before accept design"` |

The taxonomy is **extensible** but **controlled**: new top-level categories require a schema bump (version field), not silent invention mid-session.

---

## Storage model (planned)

### Location

Under the system **Panel Workshop** parent ([DatabaseSeed.kt](../../src/main/java/com/example/optimalx/data/db/DatabaseSeed.kt) — `SystemFolderNames.PANEL_WORKSHOP`):

```text
Panel Workshop/
└── Workshop Memory/          ← system subfolder (isSystemSubfolder = true)
    └── preferences.md        ← single canonical note (aiLocked = true)
```

Alternative acceptable v1: one JSON file under `files/workshop/_global/workshop_memory.json` with a `FileReference` row — same schema, same tools. Pick one store in implementation; do not split across both.

### On-disk shape (v1 schema)

Structured markdown with a machine-parseable block (YAML front matter or fenced JSON). Example:

```markdown
---
schema: workshop_memory/v1
updated_at: 2026-05-25T14:30:00-05:00
---

# Workshop Memory

## layout
- **default_viewport**: 360×640 baseline; design for narrow phone first
- **min_touch_target**: 48×48 CSS px minimum

## controls
- **numeric_input**: stepper (+/−), not raw keyboard for quantities
- **primary_button**: full-width, bottom-fixed on form screens

## menus
- **pattern**: bottom nav when ≤4 sections; hamburger + drawer otherwise
```

**Entry metadata** (per key, optional in v1, required in v2):

| Field | Meaning |
|-------|---------|
| `value` | Human-readable preference text (short) |
| `source` | `user_explicit` \| `user_confirmed` \| `inferred` |
| `confidence` | `high` \| `medium` \| `low` |
| `last_reinforced` | ISO date — bump when user repeats or confirms |
| `project_id` | Set only for project-local exceptions; omit for global |

---

## Eidos tools (planned)

Names are provisional until added to `EidosToolCatalog`:

| Tool | Modifying | Purpose |
|------|-----------|---------|
| `read_workshop_memory` | No | Read full store or filtered categories (`categories[]` param) |
| `upsert_workshop_preference` | Yes | Insert or replace one `(category, subcategory)` entry; validates against taxonomy |
| `remove_workshop_preference` | Yes | Delete one key (user asked to forget, or stale inferred entry) |
| `list_workshop_categories` | No | Return allowed category/subcategory keys + schema version (for prompt grounding) |

**Not planned:** free-form `write_note` into Workshop Memory, bulk dump from chat, or mirroring the same fact into Long-Term Memory. One system, one store.

**Confirmation:** `remove_workshop_preference` may require user confirmation when clearing a `user_explicit` entry (align with other destructive memory tools).

---

## Read protocol

Eidos should **not** load all workshop memory on every workshop turn.

| Workshop context | Categories to read (typical) |
|------------------|------------------------------|
| Intake / Spec (Plan) | `workflow`, high-level `layout`, `screens` |
| Design build / Design review | `layout`, `screens`, `controls`, `menus`, `typography`, `color`, `selection` |
| Logic build / Logic review | `controls`, `selection`, `feedback`, `bridge`, `workflow` |
| Debug | `bridge`, `feedback` (+ project code only) |
| Update → Design section | Same as design review |
| Update → Logic section | Same as logic review |

Implementation: workshop mode resolver injects a **short pointer** in the system prompt (“Workshop Memory available; read relevant categories before layout work”) and/or pre-filters `read_workshop_memory` defaults by phase. Full index is never inlined into the prompt.

---

## Write protocol

Writes are **event-driven**, not continuous.

### When Eidos MAY write

| Trigger | Action |
|---------|--------|
| User states a general preference (“I always want steppers for numbers”) | `upsert_workshop_preference` → `controls.numeric_input`, `source=user_explicit`, `confidence=high` |
| User confirms after Eidos asks (“Should I remember bottom nav for 3-tab panels?”) | Upsert with `source=user_confirmed` |
| User completes **Accept design** or **Finish** and repeated choices appeared across the session | At most **one** inferred upsert per category if pattern was consistent and user did not object |
| User says “forget that” / “don’t do that again” | `remove_workshop_preference` or overwrite with negated guidance |

### When Eidos MUST NOT write

- Every tool call or every chat message
- Speculative prefs from a single project without user signal
- Duplicating intake summary or DESIGN.md content (project artifacts stay in the project)
- Copying the same text into Long-Term Memory or Daily Memory
- Writing during **INTAKE** before the user has expressed a durable preference

### Merge rules

1. **Same key:** new `user_explicit` replaces any prior value regardless of confidence.
2. **Inferred vs inferred:** keep higher confidence; if tie, keep newer `last_reinforced`.
3. **Inferred vs explicit:** explicit always wins.
4. **Contradiction in chat:** ask once; then upsert or remove — do not store both values.

---

## Relationship to workshop phases

Workshop Memory is **orthogonal to project phase** (stored globally) but **gated by mode** for tools:

| Phase | Read | Write |
|-------|------|-------|
| INTAKE | Optional (`workflow`, `layout`) | No |
| SPEC_REVIEW | Optional | No |
| DESIGN_BUILD / DESIGN_REVIEW | Yes (layout-related) | Yes (on triggers above) |
| LOGIC_BUILD / LOGIC_REVIEW | Yes (behavior-related) | Yes (on triggers above) |
| COMPLETE / UPDATE | Yes (section-scoped) | Yes (on triggers above) |

Tool gating follows [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md) — workshop memory tools are available in workshop scope only, not in general parent/subfolder chat.

---

## UI (planned)

- **Panel Workshop** menu or Workshop Memory subfolder: read-only view of `preferences.md` for the user (same pattern as other Eidos system surfaces).
- User can delete individual keys or the whole note from UI; Eidos can rebuild from explicit re-intake.
- Optional future: “Workshop preferences” summary chip in workshop editor header (read-only, links to note).

User does **not** edit the taxonomy keys by hand in v1; they steer via chat (“remember / forget / change default”).

---

## Prompt guidance for Eidos

Short block to embed in workshop system instructions (paraphrase in [PanelPlatformSpec.kt](../../src/main/java/com/example/optimalx/data/eidos/PanelPlatformSpec.kt) when implemented):

```text
Workshop Memory holds categorical UI/UX preferences across projects (layout, controls, menus, etc.).
- Before design work: read relevant categories via read_workshop_memory — do not assume from chat history alone.
- Store only durable, reusable preferences — not project-specific copy from specs or code.
- Use upsert_workshop_preference with a valid category/subcategory; one value per key.
- Do not write on every turn; write when the user states a preference, confirms a memory, or a clear pattern was accepted at a phase gate.
- Do not mirror the same facts into Long-Term Memory.
```

---

## Anti-patterns

| Anti-pattern | Why it’s wrong |
|--------------|----------------|
| Random LTM paragraphs about panel layout | Unqueryable; mixes global continuity with workshop taxonomy |
| Per-project “memory” note in each subfolder | Duplicates; drifts; defeats cross-project reuse |
| Storing full HTML/CSS snippets | Code belongs in project files; memory holds **choices**, not implementations |
| 50 inferred keys after one build | Noise; violates deliberate write protocol |
| Reading entire memory during Debug on a bridge bug | Load `bridge` (+ maybe `feedback`) only |

---

## Implementation checklist (when building)

- [ ] Seed **Workshop Memory** system subfolder + locked note under Panel Workshop (or global file + ref)
- [ ] Define `WorkshopMemorySchema` (categories, validation) in Kotlin — single validator used by tools
- [ ] Add catalog tools + `RoomToolExecutor` handlers with taxonomy validation
- [ ] Wire read defaults into `WorkshopEidosModeResolver` / workshop prompt assembly by phase
- [ ] Gate tools to workshop scope only
- [ ] UI: read-only viewer under Panel Workshop
- [ ] Tests: upsert merge rules, invalid category rejection, phase-filtered read defaults

Track Kotlin rollout in [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md) or a dedicated implementation slice when scheduled.

---

## Summary

| Concern | Workshop Memory |
|---------|-----------------|
| Scope | Panel Workshop, cross-project |
| Shape | Categorical keys + short values |
| Writer | Eidos only (system note locked) |
| Reader | Eidos on demand; user read-only UI |
| In default chat prompt | No — workshop scope only, filtered read |
| vs LTM / Daily | Structured prefs vs free-form continuity |
| vs subfolder cache | Global taxonomy vs per-subfolder ruleset |
| vs project prefs | Durable taste vs one project’s phase/intake |

---

## Document changelog

| Version | Date | Notes |
|---------|------|--------|
| 1 | 2026-05-25 | Initial design — categorical workshop preference memory |
