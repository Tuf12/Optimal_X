# TAG & HINT SYSTEM

> **ON HOLD (shipping app):** Eidos Index is disabled via `EidosIndexFeature`. Retrieval uses `search_semantic` (vector embeddings) instead. Implementation remains in the codebase for possible future revival.

## Purpose

Tag & Hint is semantic metadata attached to each Eidos Index object for fast routing and retrieval.

**Implementation anchor:** rows are stored in `tag_hint_lines` and exposed via `read_tag_hints`. Structural index truth is defined in [../architecture/EIDOS_INDEX.md](../architecture/EIDOS_INDEX.md).

Tag & Hint helps Eidos quickly decide:
- what an object is roughly about (tag + hint)
- where it lives in the app (objectName, parentFolderName, subfolderName)
- whether deeper retrieval is needed

It is not a summary system and not a replacement for semantic search.

## Core Shape

Each indexed object has:
- structural fields (`ref`, `objectType`, `scopeType`, `scopeId`, `parentRef`, `rootBranch`)
- semantic fields (`tag`, `hint`)
- human-readable location fields (`objectName`, `parentFolderName`, `subfolderName`)

Shape:

`[date] tag — hint | ref=object | at=Parent Folder / Subfolder`

## Tag and Hint

**Tag** — short semantic label (topic/category, typically 1–4 words).

**Hint** — brief description of what the object is about (not a full summary).

## Human-readable names

Structural materialization always fills folder names so Eidos can connect IDs to language:

- **objectName** — folder name, file name, chat title, journal date, etc.
- **parentFolderName** — user parent folder or system parent (e.g. Eidos Chats)
- **subfolderName** — user subfolder when applicable

Names are authoritative from Kotlin materialization; the LLM may only update `tag` and `hint` via `upsert_tag_hint`.

## Rules

1. Every indexed object carries one semantic pair (`tag`, `hint`).
2. Tag should be short (1–4 words when possible).
3. Hint should describe subject briefly; do not turn hints into full summaries.
4. Semantics can change over time; structure and names must stay tied to canonical ref.
5. Structural columns and folder names are never authored by the LLM.

## How Eidos Uses It

First pass: scan index via `read_tag_hints` (filter by scope/query).

If matched: fetch referenced object by `ref`.

If still unclear: use `search_semantic`.

Best flow: Structure filter + Tag & Hint scan → referenced object read → semantic search only if needed.
