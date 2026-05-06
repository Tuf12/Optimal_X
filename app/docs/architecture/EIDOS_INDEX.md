# EIDOS_INDEX

## Purpose

The Eidos Index is a hybrid, app-wide routing index. It must represent real app structure first, then attach Tag & Hint metadata for retrieval behavior.

The index is not a summary feed. It is structural truth plus lightweight semantics.

## Branch model

The index has explicit top-level branches:

- `hierarchy`: parent -> subfolder -> note/file (+ subfolder memory cache)
- `chats_general`: global/general chats only
- `quick_notes`: dated quick-note entries
- `journal`: dated journal entries
- `ltm`: long-term memory entries

Parent and subfolder scoped chats are nested under `hierarchy`.
Subfolder memory cache is nested under each subfolder node as `subfolder_memory_cache`.

General chat, quick notes, journal, and LTM are sibling branches, not nested under folder hierarchy.

## Ref grammar

Canonical refs:

- `parent:<parentId>`
- `subfolder:<subfolderId>`
- `note:<noteId>`
- `file:<fileId>`
- `chat:general:<conversationId>`
- `chat:parent:<parentId>:<conversationId>`
- `chat:subfolder:<subfolderId>:<conversationId>`
- `chat:panel:<panelId>:<conversationId>`
- `quick_note:<yyyy-mm-dd>:<ordinal>`
- `journal:<yyyy-mm-dd>`
- `ltm:<slug>`
- `cache:subfolder:<subfolderId>`

## Parent mapping rules

- `parent:*` -> `parent_ref = null`, `root_branch=hierarchy`
- `subfolder:*` -> `parent_ref = parent:<parentId>`, `root_branch=hierarchy`
- `note:*` / `file:*` -> `parent_ref = subfolder:<id>`, `root_branch=hierarchy`
- `cache:subfolder:*` -> `parent_ref = subfolder:<id>`, `root_branch=hierarchy`
- `chat:general:*` -> `parent_ref = null`, `root_branch=chats_general`
- `chat:parent:<p>:*` -> `parent_ref = parent:<p>`, `root_branch=hierarchy`
- `chat:subfolder:<s>:*` -> `parent_ref = subfolder:<s>`, `root_branch=hierarchy`
- `chat:panel:<x>:*` -> `parent_ref = null`, `root_branch=hierarchy`
- `quick_note:*` -> `parent_ref = null`, `root_branch=quick_notes`
- `journal:*` -> `parent_ref = null`, `root_branch=journal`
- `ltm:*` -> `parent_ref = null`, `root_branch=ltm`

## Row contract

Each row is one indexed object and includes:

- structural fields: `ref`, `objectType`, `scopeType`, `scopeId`, `parentRef`, `rootBranch`
- semantic fields: `piece`, `lens`, `hint`

`piece/lens/hint` are metadata attached to each indexed object. They do not replace structure.

## Hard invariants

- One row per ref (`UNIQUE(ref)`).
- Ref must parse to valid grammar.
- `objectType` and `scopeType` must match ref shape.
- Removed or invalid backing objects must remove index rows.
- Structural fields are authoritative from Kotlin materialization (not LLM-generated).

## Tree projection example

The persisted table is row-based, but read APIs may project a branch-first tree for LLM readability:

- `hierarchy.parents[].children.subfolders[].children.note/files/subfolder_chat_conversations/subfolder_memory_cache`
- `hierarchy.parents[].children.parent_chat_conversations[]`
- `chats_general.general_chat_conversations[]`
- `quick_notes.entries[]`
- `journal.entries[]`
- `ltm.entries[]`

In either row or tree form, every object carries its own `piece/lens/hint`.