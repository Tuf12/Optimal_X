# EIDOS_INDEX (archived)

**Removed from the shipping app (2026-06).**

The Eidos Index was a Tag & Hint routing catalog (`tag_hint_lines` table, `read_tag_hints` / `upsert_tag_hint` tools, background materializer). It is no longer seeded, migrated away (DB v23 drops the table), or exposed in `EidosToolCatalog`.

**Use instead:** [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md) — chunk-level `search_semantic` plus `read_file` / `workshop_read_file` when expanding file hits or editing.

Historical spec: [archive/agent_loops/TAG_HINT_SYSTEM.md](../archive/agent_loops/TAG_HINT_SYSTEM.md)
