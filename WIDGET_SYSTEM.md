# WIDGET_SYSTEM.md

## Purpose

This file defines the home screen widget and quick AI entry system for OptimalX v2.

The widget allows the user to interact with Eidos from outside the app — hands free, voice first — without needing to open OptimalX.

Coding agents should use this file alongside EIDOS_AGENT.md, JOURNAL_SYSTEM.md, and DATA_MODEL.md to understand how the widget connects to the rest of the system.

---

## What the Widget Is

The widget is a home screen component that gives the user direct voice access to Eidos from anywhere on their phone.

It is not a mini version of the app.
It is a single purpose voice entry point — tap to talk, Eidos responds, everything is logged in the background.

---

## Widget Layout

The widget sits on the Android home screen.

### Controls
- **Talk button** — tap to start speaking to Eidos
- **End button** — tap to close the current conversation
- **Conversation picker** — dropdown showing the last 10 conversations across the entire system. User can select one to continue or start a new one.
 - **Text chat button** — tap to open a text chat screen directly (no voice required) so the user can type messages to Eidos

### What the widget does NOT show
- Notes or folder content
- File previews
- Any app UI beyond the three controls above

Keep it minimal. The widget is an entry point, not a display surface.

---

## How Voice Works in the Widget

### Speaking to Eidos
- User taps the Talk button
- The widget begins recording via speech to text
- Everything the user says is converted to text in real time
- The text is sent to Eidos as a message when the user pauses or signals end of thought

### Eidos responding
- Eidos generates a response
- The response is read back to the user via text to speech
- The response is also written into the active conversation note in the background

### Walkie talkie style handoff
- When Eidos finishes speaking it signals back to the user that it is their turn
- The user can begin speaking again without tapping anything
- This creates a continuous hands free back and forth
- Full detail on the voice handoff signal is defined in VOICE_SYSTEM.md

### End of conversation
- User taps the End button to close the current conversation
- The conversation note is finalized and saved
- The widget returns to idle state ready for a new or continued conversation

---

## Conversation Logging

Every widget conversation is written in real time to a note inside the system.

### What gets written
- User messages (speech converted to text)
- Eidos responses (full text)
- Timestamps on each message

### Format inside the note
Each message in the conversation note is written as:

```
[HH:MM AM/PM] User: message text here
[HH:MM AM/PM] Eidos: response text here
```

The note is append only during an active conversation — nothing is overwritten mid session.

---

## Chat Folder System

Conversations are stored in a structured folder system that mirrors the rest of the app.

### System level — Eidos Chats (app root)
- A system locked parent folder created automatically on install
- Cannot be renamed, moved to trash, or deleted
- Stores all conversations that happen via the widget or anywhere outside a specific subfolder
- Each conversation gets its own subfolder named by date and time (e.g. 2026-04-01 — 2:34 PM)
- The note inside each subfolder contains the full conversation log
- User can read and delete individual conversations

### Subfolder level — Chat folder inside every subfolder
- Every user-created subfolder automatically gets a system chat subfolder inside it
- Conversations that happen via the Eidos bottom sheet while inside that subfolder are saved here
- Same structure — each conversation is its own note
- This keeps all subfolder related content together — notes, files, and chats all in one place
- User can read and delete individual conversations

### Why this separation matters
- Widget and app-level conversations save to the root Eidos Chats folder
- Subfolder conversations save to that subfolder's chat folder
- Eidos can find context exactly where it should be without searching across the whole app
- Everything is scoped to where it belongs — consistent with the rest of the system design

---

## Conversation Picker

The widget shows the last 10 conversations from anywhere in the system.

### How it works
- Dropdown menu in the widget
- Lists the 10 most recent conversations by date and time
- Shows which subfolder or system folder each conversation belongs to
- User taps one to continue it
- If nothing is selected a new conversation starts automatically when the user taps Talk

### Search
- A search option is available inside the picker for finding older conversations
- Search by keyword or date

---

## Three System Level Folders

When OptimalX is first installed three system level parent folders are created automatically at the app root:

| Folder | Purpose |
|---|---|
| Eidos Journal | Eidos working memory — private context log |
| Eidos Log | Activity record — every system modification Eidos makes |
| Eidos Chats | Conversation history — widget and app-level conversations |

All three are system locked. They cannot be renamed, moved to trash, or deleted.
They do not appear in the regular user folder list.
They are accessible from a dedicated Eidos section in the app.

---

## Data Model Notes

The chat folder system uses the same ParentFolder, Subfolder, and Note objects defined in DATA_MODEL.md.

### Eidos Chats root folder
- isSystemFolder = true
- Created on first install
- One subfolder per conversation, named by date and time
- Note inside each subfolder contains the conversation log

### Subfolder chat folder
- A new system subfolder type is needed inside every user subfolder
- This subfolder is auto-created when the parent subfolder is created
- It follows the same isSystemFolder rules — cannot be deleted or renamed
- One note per conversation inside it

### New field needed in Subfolder
| Field | Type | Description |
|---|---|---|
| isSystemSubfolder | Boolean | If true, subfolder cannot be renamed, moved to trash, or deleted. Default is false. Used for the auto-created chat subfolder inside each user subfolder. |

---

## What Does Not Exist in v2

- No visual display of notes or folders in the widget
- No file previews in the widget
- No ability to create folders or notes directly from the widget — that goes through Eidos in conversation
- No multi-account or multi-user support
