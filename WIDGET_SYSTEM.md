
# WIDGET_SYSTEM.md

## Purpose

This file defines the home screen widget and quick AI entry system for OptimalX v2.

The widget allows the user to interact with Eidos from outside the app — hands free, voice first — without needing to open OptimalX.

Coding agents should use this file alongside EIDOS_AGENT.md, JOURNAL_SYSTEM.md, DATA_MODEL.md, and VOICE_SYSTEM.md to understand how the widget connects to the rest of the system.

---

## What the Widget Is

The widget is a home screen component that gives the user direct voice access to Eidos from anywhere on their phone.

It is not a mini version of the app.
It is a voice entry point with a persistent conversation — speak to Eidos hands-free, and optionally open the chat UI to read or type.

---

## Widget Layout

The widget sits on the Android home screen.

### Controls

| Button | Action |
|---|---|
| Mic | Push-to-talk. Tap to start recording, tap again to send. Color indicates current state (see below). |
| End | Ends and saves the current conversation. Widget returns to idle. |
| Chat | Opens the widget chat UI showing the currently active conversation. |
| New Chat | Closes the current conversation (saves it) and starts a fresh one. |
| Conversations | Shows the 5 most recent general conversations. Tap one to open it in the chat UI. |

### What the widget does NOT show
- Notes or folder content
- File previews
- Chat messages inline on the widget surface

Keep it minimal. The widget is an entry point, not a display surface.

---

## Mic Button Color States

The mic button communicates voice session state through color.

| State | Color | Tappable |
|---|---|---|
| Idle — no active recording | Grey | Yes — starts recording |
| Recording — mic is active, accumulating speech | Green | Yes — stops recording and sends |
| Eidos responding — API call or TTS in progress | Red | No — locked until response is complete |

When the mic is red the user cannot accidentally interrupt an in-progress response.
When TTS finishes (or read-aloud is off and the response is saved), the mic returns to grey.

---

## Conversation Persistence

The widget maintains a single active conversation across interactions.

### What keeps a conversation open
- Each mic session (record + send + response) appends to the same active conversation
- The user can leave the home screen, return, and continue the same conversation
- Opening the chat UI (Chat button) does not close or reset the conversation

### What closes a conversation
| Action | Result |
|---|---|
| Tap End | Conversation saved, widget returns to idle |
| Tap New Chat | Current conversation saved, new blank conversation started |
| Select a conversation from history | Current conversation saved, selected conversation becomes active |
| 10-minute idle timeout | If no mic activity for 10 minutes, conversation is automatically saved and closed |

After closing, the next mic tap starts a new conversation automatically.

---

## How Voice Works in the Widget

The widget mic runs voice interaction without opening any UI.
The full conversation is written to a note in the background.
The user can tap Chat at any time to view what was said.

### Mic push-to-talk flow

1. User taps mic (grey → green) — recording starts via WidgetVoiceService foreground service
2. User speaks freely — speech is accumulated in a persistent buffer, mic stays open via auto-restart on silence timeout
3. User taps mic again (green → red) — recording stops, transcript is sent to Eidos
4. Eidos generates a response — mic stays red (locked) while processing
5. If read-aloud is on, response is spoken via TTS — mic stays red during playback
6. Response is written to the active conversation note
7. Mic returns to grey — ready for next input

There is no handoff phrase. There is no silence auto-send. The user always controls when to send.

### Chat button behavior

- Opens WidgetChatActivity showing the **currently active** widget conversation
- If no conversation is active, opens a blank new conversation
- The user can read what was said, type a message, or use the in-chat mic
- The in-chat mic follows the standard voice system rules defined in VOICE_SYSTEM.md

### New Chat button behavior

- Saves and closes the current conversation
- Starts a fresh blank conversation as the new active conversation
- The next mic tap will record into this new conversation

### Selecting a conversation from the picker

- Saves and closes the current conversation
- The selected conversation becomes the active conversation
- Opens WidgetChatActivity with that conversation loaded
- Future mic taps append to the selected conversation

---

## 10-Minute Idle Timeout

If no mic activity occurs for 10 minutes while a conversation is active, the conversation is automatically saved and closed.

- The timeout resets each time the user taps the mic (either tap — start or send)
- The timeout does not apply while the mic is recording or while Eidos is responding
- When the timeout fires, the widget returns to idle state exactly as if the user had tapped End
- The conversation is still accessible in the conversation history

---

## Widget Voice vs In-Chat Voice

These are two different interaction modes. Both save to the same conversation note.

| Mode | How triggered | UI shown | Mic behavior |
|---|---|---|---|
| Widget voice | Tap Mic on widget | None — background only | Push-to-talk: tap to start, tap to send |
| In-chat voice | Tap mic inside WidgetChatActivity | Full chat UI | Per VOICE_SYSTEM.md: continuous, send on mic tap or send button |

---

## Chat Scoping

Every conversation is scoped to the context where it was started.

| Where Eidos is opened | Scope | Conversation saves to |
|---|---|---|
| Widget | General | Eidos Chats root system folder |
| Parent folder page | General | Eidos Chats root system folder |
| Subfolder page (inside Johnson) | Parent folder | Johnson parent folder's conversation list |
| Editor — any panel (inside Johnson → Landscaping) | Subfolder | Landscaping subfolder's conversation list |

### Rules
- Widget conversations are always general scope — they save to the Eidos Chats root
- Eidos knows its location context in every scope and uses it when responding
- The conversation picker in the widget shows only general scope conversations
- Inside the app, each screen shows conversations scoped to that level

---

## Conversation Picker

The widget shows the 5 most recent general conversations by default.

### How it works
- Tap Conversations on the widget to open the picker
- Lists the 5 most recent general conversations by date and time
- Tap one to make it the active conversation and open it in the chat UI
- Selecting a conversation saves and closes the current active conversation first

### Search
- A search field is available inside the picker for finding older conversations
- Search is keyword-based — shows all matching conversations with no limit
- When the search field is empty, only the 5 most recent are shown

---

## Three System Level Folders

When OptimalX is first installed three system level parent folders are created automatically at the app root:

| Folder | Purpose |
|---|---|
| Eidos Journal | Eidos working memory — private context log |
| Eidos Log | Activity record — every system modification Eidos makes |
| Eidos Chats | General conversation history — widget and parent folder page conversations |

All three are system locked. They cannot be renamed, moved to trash, or deleted.
They do not appear in the regular user folder list.
They are accessible from a dedicated Eidos section in the app.

---

## Data Model Notes

Conversations and messages use the Conversation and ChatMessage objects defined in DATA_MODEL.md.

### General conversations (widget and parent folder page)
- scopeType = "general"
- parentFolderId = null
- subfolderId = null
- Stored under the Eidos Chats system folder logically — queryable by scopeType = "general"

### Parent folder conversations (subfolder page)
- scopeType = "parent"
- parentFolderId = ID of the parent folder the user is browsing
- subfolderId = null

### Subfolder conversations (editor — any panel)
- scopeType = "subfolder"
- subfolderId = ID of the subfolder the user is inside
- parentFolderId set for reference

---

## What Does Not Exist in v2

- No visual display of notes or folders in the widget
- No file previews in the widget
- No ability to create folders or notes directly from the widget — that goes through Eidos in conversation
- No multi-account or multi-user support
