# EIDOS_INDEX

## Purpose

The Eidos Index is a hybrid, app-wide routing index. It must represent real app structure first, then attach Tag & Hint metadata for retrieval behavior.

The index is not a summary feed. It is structural truth plus lightweight semantics and human-readable folder names.

## Branch model

The index has explicit top-level branches:

- `hierarchy`: parent -> subfolder -> note/file (+ subfolder memory cache)
- `chats`: scoped chats (`general`, `parent`, `subfolder`)
- `quick_notes`: dated quick-note entries
- `journal`: dated journal entries
- `ltm`: long-term memory entries

## Row contract

Each row is one indexed object and includes:

- structural fields: `ref`, `objectType`, `scopeType`, `scopeId`, `parentRef`, `rootBranch`
- semantic fields: `tag`, `hint`
- location fields: `objectName`, `parentFolderName`, `subfolderName`

`tag`, `hint`, and folder names are metadata attached to each indexed object. They do not replace structure.

## Hard invariants

- One row per ref (`UNIQUE(ref)`).
- Structural fields and folder names are authoritative from Kotlin materialization (not LLM-generated).
- LLM enrichment may only update `tag` and `hint` via `upsert_tag_hint`.

See [TAG_HINT_SYSTEM.md](../agent_loops/TAG_HINT_SYSTEM.md) for ref grammar, examples, and retrieval flow.
