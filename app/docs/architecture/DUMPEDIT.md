# DUMPEDIT.md

## What It Is

DumpEdit is a disposable working surface always accessible from the Pinned row on the Parent Folder Page.

It is not a note. It has no folder, no date structure, and no automatic save. It is a place to dump content — paste text, type thoughts, drop in a copied block of anything — and work on it with Eidos before deciding what to do with it.

The name comes from an earlier desktop utility built for the same purpose: getting large or messy content into a workable state before sending it somewhere useful.

**Implementation:** Kotlin `DumpEditScreen`, DataStore `dump_edit`, Eidos scope `dump_edit`.

---

## Persistence

| Key (DataStore `dump_edit`) | Purpose |
|-----------------------------|---------|
| `dump_edit_content` | Rich-text HTML buffer |
| `dump_edit_ai_locked` | Lock from Eidos |
| `dump_edit_ai_blind` | Blind from Eidos |

- Survives app restart until the user **Clear**s the buffer
- **Undo clear** is session-only (in-memory) — not restored after process death
- **OptimalX backup (.zip):** `preferences/dump_edit.json` sidecar in snapshot exports

---

## Editor UX

Reuses the note editor chrome:

- Formatting toolbar (no read-aloud in v1)
- Dropdown: strikethrough, view/edit, lock/blind, export/share, **Clear**, **Undo clear**, **Promote to folder**
- No “Generate Eidos summary” (not a folder note)

Screen copy: title **DumpEdit**, subtitle *Scratch buffer — not saved to a folder*.

---

## Eidos Interaction

When DumpEdit is open, Eidos uses conversation scope **`dump_edit`** (dedicated thread via `ChatSessionPointers`).

| Privacy | System prompt | `read_dump_edit` tool |
|---------|---------------|----------------------|
| Normal | Full buffer if ≤ **8K chars**; else semantic excerpts for the user’s message | Allowed |
| **AI locked** | No inline body; use `read_dump_edit` | Allowed |
| **Blind** | “Do not request content” | Rejected |

Large-buffer retrieval uses the same sectioning as notes (`ContentSectionRetriever`, query + optional line range).

**Kotlin:** `DumpEditContext`, `DumpEditContextLimits.FULL_CONTEXT_CHAR_THRESHOLD` (8192).

---

## Promote to Folder

**Promote to folder** creates a new subfolder under a user-chosen parent and writes the current buffer into that subfolder’s note.

- Buffer is **not** cleared after promote
- User may open the new note in the editor from the success dialog

---

## Pinned Row Presence

DumpEdit is always present in the Pinned row. It cannot be unpinned. It opens as a full screen directly from the pin tap.

The pinned row does **not** appear on the DumpEdit screen itself.

---

## What DumpEdit Is Not

- Not a note — content is not part of the folder system until promoted
- Not a chat — it is a working surface, not a conversation transcript
- Not Quick Notes — Quick Notes is for fast capture and always saves; DumpEdit is for working with content that may never be saved
