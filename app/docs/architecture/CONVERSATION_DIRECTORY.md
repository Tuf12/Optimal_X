# conversation_directory.md

## Purpose
Defines the current conversation directory model, navigation flow, and maintenance rules for chat history across app and widget surfaces.

Use this file with:
- `CHAT_UI.md`
- `CHAT_UI_BUILD.md`
- `DATA_MODEL.md`
- `WIDGET_SYSTEM.md`

## Storage Model

Conversations are stored in Room `conversations` and `chat_messages`.

Conversation scope fields:
- `scopeType = "general" | "parent" | "subfolder"`
- `parentFolderId` is set when scope is `parent`
- `subfolderId` is set when scope is `subfolder`

Ordering:
- Recency is `updatedAt DESC`

## Directory Surfaces

The conversation browser in chat UI uses directory-first navigation:

Top-level chips:
- `Recent`
- `General`
- `Parent`

Notes:
- `Subfolder` is intentionally not a top-level chip.
- Subfolder browsing is a second-step action inside `Parent`.

## Browser Flow

### Recent
- Shows last 5 conversations globally (all scopes)
- Sorted most recent first

### General
- Shows general-scope conversations
- Used for widget + root general continuity

### Parent
- Step 1: select parent folder
- Step 2: choose mode:
  - `Parent Chats`: show last 5 parent-scoped conversations for selected parent
  - `Subfolders`: show subfolders for selected parent, then last 5 for selected subfolder

## Scope Semantics

Two scope concepts are intentionally separate:

- `viewedScope`
  - Current page location where user opened chat
  - Target for `New Chat` and `Move Here`

- `currentScope`
  - Scope of the currently loaded conversation
  - Displayed in chat header label (`General` / `Parent` / `Subfolder`)

This separation prevents accidental re-homing and keeps directory behavior predictable.

## Move Here Rules

- `Move Here` is explicit and user-confirmed
- It moves the active conversation to the current `viewedScope`
- It must not infer target from currently loaded conversation scope

### Pointer cleanup on Move Here (target — Phase 3)

When a conversation moves scope, **session pointers must follow the conversation** and **stale pointers at the old scope must be cleared**. Otherwise the folder list and “resume last chat” logic can show the thread in two places or restore it from the wrong scope.

| Pointer store | Scope | On move |
|---------------|-------|---------|
| `ChatSessionPointers` | Main-app general / parent / subfolder / dump_edit / panel_* | Clear pointer at **pre-move** scope; persist at **new** scope after DB update |
| `WidgetPrefs.active_conversation_id` | Widget Chat UI (general or resumed thread) | Clear or update when move enters/leaves general on widget surface |

**Implementation:** [VOICE_CHAT_STT_COMPLETION_PLAN.md](../implementation/VOICE_CHAT_STT_COMPLETION_PLAN.md) Phase 3.  
**API:** keep `moveActiveConversationToCurrentScope()` only; remove dead `moveConversationToViewedDirectory()`.

### Folder list refresh (target — Phase 4)

After Move Here (or delete/rename), **directory UI must reload** so the conversation disappears from the old folder list and appears in the new one without killing the app.

- `ConversationListScreen` reloads on **Lifecycle ON_RESUME** and/or a shared revision signal from `EidosChatViewModel`
- Returning from chat to a Chat folder list should show updated membership (manual test **M1**, **M2** in [VOICE_CHAT_STT_QA.md](../implementation/VOICE_CHAT_STT_QA.md))

## New Chat Rules

- `New Chat` is explicit and user-confirmed
- Clears active conversation in UI and starts fresh thread on next send
- Uses current `viewedScope` as the new conversation scope

## System Folder Mapping

Directory UI maps logical scope to system locations:
- Root: system `Eidos Chats` for general-level access
- Parent folders: system `Chats` subfolder for parent-level access
- Editor/subfolder context: subfolder-scoped chat access via Eidos chats entry points

## Maintenance Checklist

When updating chat directory logic, verify:
- `Recent` still returns last 5 global
- `Parent` still lists all parent folders
- `Subfolders` only appears as second-step under `Parent`
- `Parent Chats` and `Subfolders` each return last 5 for selected location
- `Move Here` uses `viewedScope`
- `Move Here` clears old scope pointers and refreshes visible folder lists (Phase 3–4)
- `currentScope` label updates on conversation load/switch
- Widget and in-app chat browser behavior stay aligned

**Fix tracker:** [VOICE_CHAT_STT_COMPLETION_PLAN.md](../implementation/VOICE_CHAT_STT_COMPLETION_PLAN.md) Phases 3–4, 7.

