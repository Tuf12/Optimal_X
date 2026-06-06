# Memory Build Plan

Working plan for OptimalX **layered memory**, **Eidos system surfaces**, and **rollover**. 

Canonical product detail remains in `app/docs/memory/MEMORY_SYSTEM.md` until merged elsewhere.

---

## Glossary (read this first)

**“Key” (API key)**  
Same credential as normal chat: the user’s **provider API key** stored in app settings (Kimi / OpenAI / Anthropic / xAI). **Rollover is not a separate service.** When the clock fires, the app runs the same “send a system + task prompt to Eidos” path it uses for chat. If no key is configured (or the provider fails), Eidos cannot run rollover. That is **not** a special “worker key”; it is the same gate as opening Eidos and sending a message.

**Worker / scheduler**  
Only responsibility: **wake the app on a schedule** (e.g. WorkManager) and call **one** function, e.g. `runMemoryRollover()`, which is **identical** to what **Settings → Force memory rollover** calls. No second permission model, no separate “rollover credentials.”

**Eidos system area**  
Eidos has its own directory where it stores Eidos Journal, Eidos logs, Eidos Chats which are the general chat conversations, and once memory system is implmented Daily Memory and Long Term memory.  **Note this is not the same location as the Parent folder this is a seperate directory for Eidos data and the general chat conversations, as this was the best place to store them. Therefore, if any other files direct any of these features to a different location the Developer must alter this message and provide permision to do so. 

**Tag & Hint**  
Compact index lines used for **routing and retrieval** across the app—not the same as the **long-term memory cache** body (see `MEMORY_SYSTEM.md` and your separation: cache = assist knowledge; Tag & Hint = labels + hooks for finding the right slice).

---

## Product intent (agreed direction)

1. **Journal, Log, Eidos Chats (general), Daily Memory, Long-Term Memory** all belong under the **Eidos system** tree, alongside what already exists.
2. **User capabilities:** user **must not write** to Eidos-only streams (journal, log, daily, LTM, etc.); user **may read** and **delete individual entries** where the product allows (align with current journal/log screens).
3. **Presentation:** optionally evolve toward a **single inbox-style page** (similar in spirit to Quick Notes inbox) listing mixed entry types with **timestamps**, instead of making users hunt through date subfolders—**storage can still use subfolders/notes internally** if that keeps Room and tools simple; UI can aggregate.
4. **Rollover:** scheduler triggers at night → app calls **`runMemoryRollover()`** → same code path as **Force rollover** in Settings. If Eidos cannot complete (no key, provider error, offline): **do not clear daily**; leave content for next attempt or user force. **LLM-less rollover is out of scope and not supported.** **`clear_daily_memory` is not a model-visible tool**—only this pipeline clears after successful Eidos rollover.
5. **Existing journal content:** ignore backfill for v1; Tag & Hint lines apply to **new** rollover output going forward.

---

## Relationship to current code (anchors)

| Area | Files / concepts |
|------|------------------|
| System folders | `DatabaseSeed.kt` (`SystemFolderNames`), `ParentFolder.isSystemFolder` |
| Eidos section UI | `EidosSystemScreens.kt` (`EidosSystemKind`), `AppNavigation.kt` |
| Chat / tools | `EidosApiClient.kt`, `EidosToolCatalog.kt`, `RoomToolExecutor.kt` |
| Quick Notes inbox pattern | `QuickNotesInboxScreen.kt`, `SubfolderScreen` / `SubfolderViewModel` |

---

## Phase 0 — Decisions (short, do before heavy coding)

- [ ] **LTM cache persistence:** confirm whether long-term assist cache is **Room-only**, **dedicated table**, **file under app storage**, or **still a locked note** for v1—pick one so implementation does not split across two stores by accident.
- [ ] **Daily Memory shape:** one rolling note vs one note per day internally; user-facing inbox can still show one feed.
- [ ] **Unified inbox:** Phase 1 keeps current per-folder screens vs one **Eidos Hub** screen—choose scope for first release.

---

## Phase 1 — Data model & seed

- [ ] Add `SystemFolderNames` entries for **Eidos Daily** and **Eidos Memory** (or final names).
- [ ] Seed those parents in `seedDatabaseIfNeeded` with `isSystemFolder = true`.
- [ ] Define where **Tag & Hint** indices live (dedicated subfolder + note per index, or single note with sections)—document in code comments next to constants.
- [ ] Optional: `Subfolder.memoryCache`, `semanticTags`, `agentReasoningNote` when subfolder-scoped memory enters scope (`MEMORY_SYSTEM.md`).

---

## Phase 2 — Rollover pipeline (no “worker magic”)

- [ ] Implement **`runMemoryRollover()`** (suspend): builds rollover system prompt, calls **`EidosApiClient`** (or shared use-case) same as chat; on **success** only, perform **worker-only** clears/updates (e.g. clear daily via repository, append Tag & Hint index lines, etc.).
- [ ] Wire **Settings → Force memory rollover** to `runMemoryRollover()`.
- [ ] Add **WorkManager** (or chosen scheduler) periodic work that **only** invokes `runMemoryRollover()` (same entry point).
- [ ] If Eidos step fails or no API key: **no daily wipe**; set rollover pending state and retry on next schedule or Force rollover.

---

## Phase 3 — Prompt assembly & tools

- [ ] Extend `EidosApiClient.assembleSystemPrompt` per spec: **full Daily** always; inject **Tag & Hint indices** (journal + long-term + others as designed); subfolder context + `memoryCache` when present; remove “dump last N journal days” once indices exist.
- [ ] Add model tools: `read_journal_tag_hint_index`, `read_long_term_tag_hint_index`, `write_daily_memory`, `read_daily_memory`, read/write LTM as designed—**exclude** `clear_daily_memory` from `EidosToolCatalog`.
- [ ] Implement tools in `RoomToolExecutor` / repositories; keep **automatic** `write_log_entry` for mutating tools where applicable.

---

## Phase 4 — AgentByte (hybrid, grows with memory)

- [ ] Introduce thin **`AgentByteContext`** or loop: situation label, chess piece, **tool allowlist** / King acknowledgment text in system prompt—reuse existing **`requiresConfirmation`** for destructive tools.
- [ ] Full loop features from `agent_loops/` specs (e.g. [`agentbyte-chat-loop-v1.md`](agent_loops/agentbyte-chat-loop-v1.md): token milestones, reasoning note persistence, etc.) **after** Phase 2–3 stable.

---

## Phase 5 — Eidos Hub / inbox UI (optional follow)

- [ ] Single screen: sections or one `LazyColumn` of **timestamped rows** from Journal, Log, Chats, Daily snapshots, LTM entries—filter by type; delete actions respect DAO rules.
- [ ] Keep **Quick Notes** behavior separate unless you explicitly merge navigation.

---

## Phase 6 — Docs & cleanup

- [ ] Merge `MEMORY_SYSTEM.md` truth into `reference/` + `systems/` when stable; archive redundant overlays under `memory/` or `Not_implemented/` if any remain.
- [ ] Update `agent_loops/` loop docs' tool names (`read_journal_summary_index` → `read_journal_tag_hint_index`, etc.) when those specs become active again.

---

## Open questions (resolve when you hit them)

1. For **inbox**, do deletes **hard-delete** subfolders/notes or **soft-delete** to match trash semantics elsewhere?

---

## Success criteria (minimal)

- Scheduled and manual rollover share **`runMemoryRollover()`**.
- Daily memory is never cleared unless Eidos rollover **succeeds**.
- User cannot assign tools that wipe daily mid-chat.
- Prompt carries **full daily** + **deterministic** rest from board state + indices.
