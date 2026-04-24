
# CHAT_UI.md

## Purpose

This file defines the chat interface used for all Eidos conversations in OptimalX v2.

The chat UI appears as a bottom sheet inside the app and as the primary interface in the home screen widget. The layout and behavior are identical in both contexts.

Coding agents should use this file alongside EIDOS_AGENT.md, VOICE_SYSTEM.md, WIDGET_SYSTEM.md, and DATA_MODEL.md to build the chat layer.

---

## Where the Chat UI Appears

| Location | How it opens |
|---|---|
| Inside the app (any screen) | Tap the Eidos button in the top bar — bottom sheet slides up |
| Home screen widget | Tap the widget to open the chat screen directly |
| Chat folder in the directory | Tap a conversation entry in any Chat folder |
| Eidos menu → Chats | Tap the Chats item in the Eidos menu (hamburger) on any page |

The chat UI is the same component in all cases. The conversation scope determines where the conversation is saved.

---

## Chat Scoping

Every conversation is scoped to the context where Eidos was opened.

| Where Eidos is opened | Scope | Eidos context |
|---|---|---|
| Widget | General | No specific folder context |
| Main folder list | General | No specific folder context |
| Parent folder page | Parent folder | Knows which parent folder |
| Subfolder page | Parent folder | Knows which parent folder |
| Editor — any panel | Subfolder | Knows the exact subfolder |

- Scope is determined automatically when Eidos opens — the user does nothing
- Eidos uses the scope context to give relevant, informed responses
- Switching panels inside the editor does not change the scope — the conversation stays the same
- Full storage detail is defined in DATA_MODEL.md and WIDGET_SYSTEM.md

---

## Chat Access Paths

The user has two ways to reach their conversation history from anywhere in the app. Both paths lead to the same data.

### Path 1 — Chat Folders in the Directory

Chat conversations are accessible as system folders in the directory structure at every level.

| Page | What the user sees | What it contains |
|---|---|---|
| Main folder list | "Eidos Chats" system parent folder | All general conversations (app + widget) |
| Parent folder subfolder list | "Chats" system subfolder | All conversations scoped to that parent folder |
| Editor / Panels | Eidos menu → Chats | All conversations scoped to that subfolder |

- The "Eidos Chats" folder at the root level is visible in the main folder list alongside the user's own folders
- It is a system folder: non-renameable, non-deletable, cannot have items added to it manually
- Each user-created parent folder automatically gets a "Chats" system subfolder when it is created
- That "Chats" subfolder is visible in the subfolder list but is system-protected — no rename, no delete, no manual notes
- Tapping a Chat folder opens a conversation list screen for that scope
- Tapping a conversation entry in that list opens the chat UI loaded with that conversation

### Path 2 — Eidos Menu (Hamburger)

The Eidos menu (three horizontal lines) is present in the header on every page alongside the settings icon.

| Page | Eidos menu → Chats shows |
|---|---|
| Main folder list | General conversations (same as Eidos Chats folder) |
| Parent folder page | That parent folder's conversations (same as the Chats subfolder) |
| Editor / Panels | That subfolder's conversations |

- The Eidos menu continues to show Journal, Log, and Chats — the full menu is unchanged
- Only the Chats content changes based on current page context
- Tapping Chats in the menu opens the same conversation list screen as the folder path

### Why Two Paths

Both paths lead to the same scoped conversation list. The folder path gives the user a discoverable, visual structure. The menu path gives direct access from wherever they are. Structured scoping also makes it efficient for Eidos to locate and reference prior conversations without searching through a flat unstructured list.

---

## Layout

### Bottom sheet (in-app)
- Covers approximately 60–70% of the screen
- The screen below remains partially visible
- Swipe down to dismiss
- Does not conflict with horizontal panel swipes in the editor

### Full screen (widget)
- Opens as a full screen chat activity
- Back button closes and returns to the home screen

---

## Top Bar Controls

The top bar of the chat UI contains the following controls (left to right):

| Control | Position | Action |
|---|---|---|
| Close / Back | Left | Dismisses the bottom sheet (in-app) or returns to home screen (widget) |
| "Eidos" title | Center-left | Static label |
| History button | Right | Opens the Conversation History Browser |
| New Chat button | Right | Starts a fresh conversation immediately |
| Read Aloud toggle | Right | Enables / disables TTS for Eidos replies |

The History and New Chat buttons mirror the behavior of those same buttons on the home screen widget.

---

## Conversation Persistence

Conversations stay open and active until one of these events occurs:

- The user taps **New Chat** — clears the current conversation and starts a fresh one
- The user selects a different conversation from the **History Browser** — switches to that conversation
- The user selects a different conversation from a **Chat folder** — switches to that conversation
- The user explicitly deletes a conversation

Reopening the chat UI (without any of the above) always resumes the last active conversation at the current scope. The conversation does not reset on open.

---

## Conversation History Browser

Opens as a sheet when the History button is tapped in the chat top bar.

### What it shows
- The 5 most recent conversations at the current scope level by default
- Each entry shows the conversation title (date and time) and a snippet of the first message
- The currently active conversation is highlighted

### Behavior
- Tap a conversation to switch to it and continue it
- A search field is available to find older conversations by keyword or date — searching shows all matching results, not just 5
- Tapping outside the browser dismisses it without switching conversations

### Scope filtering
- From the widget or main folder list: shows the 5 most recent general conversations
- From a parent folder or subfolder page: shows the 5 most recent conversations scoped to that parent folder
- From the editor: shows the 5 most recent conversations scoped to that subfolder

---

## Conversation List Screen

A standalone screen shown when the user navigates to a Chat folder or opens Chats from the Eidos menu.

- Title: "Chats" (or "Eidos Chats" at the root level)
- Lists all conversations at the current scope, ordered by most recent first
- Each row shows: conversation title (date/time), snippet of first message, last updated timestamp
- Tap a row to open that conversation in the chat UI
- Delete: long-press a row or use a checkbox multi-select to delete selected conversations
- No rename or edit — conversation titles are always auto-generated date/time stamps

---

## Message Bubbles

Every message in the conversation is displayed as a bubble.

### User messages
- Aligned to the right
- Background: `messageBubbleUser` token
- Text: `textPrimary` token
- No action buttons on user bubbles

### Eidos messages
- Aligned to the left
- Background: `messageBubbleEidos` gradient
- Border: `messageBubbleEidosBorder` token
- Text: `textPrimary` token
- Reread button visible on every Eidos bubble (see below)

### Timestamps
- Displayed below each bubble in `textDim` color using DM Mono font
- Format: HH:MM AM/PM

---

## Reread Button

Every Eidos message bubble has a small speaker icon below it.

### Behavior
- Tap the speaker icon → TTS reads that message aloud from the beginning
- If TTS is already playing, tapping stops it and starts the selected message instead
- Only one message plays at a time
- The speaker icon on the currently playing message is highlighted with the accent color
- All other speaker icons are in `textDim` color

## Copy Button
- next to Reread button attached to message bubbles
purpose: allow user to quickly copy the text in a message bubble

## Edit Message button
- Next to Reread and Copy button attached to message bubbles
purpose: allow user to edit a message that has already been sent to Eidos, giving user ability to fix errors in messages
- If edit message is selected and executed by user all messages that are in the chat after the editted message will be removed and replaced by the next message sent. 

# Long Press Text Copy
- allows user to long press on a message to select text to copy. 


---

## Input Bar

The input bar sits at the bottom of the chat UI above the keyboard.

### Components (left to right)
- Text input field — accepts typed text at any time
- Mic button — activates voice input (defined in VOICE_SYSTEM.md)
- Send button — sends the current input bar content to Eidos

### Behavior
- Text input and voice input share the same input bar
- Voice input appends to whatever is already typed in the bar
- Send button is active whenever the input bar contains text
- Send button is inactive when the input bar is empty
- After sending: input bar clears and returns to empty state

Voice internals are defined in `VOICE_SYSTEM.md`. The chat UI should treat voice as a continuous transcript stream and remain agnostic to segment/model boundaries.

## Mic states
Idle — mic off
Listening — STT active
Paused — STT temporarily stopped because user interacted with text input
Suggested rules
Mic button starts STT
STT appends text into the current draft
If user taps input while STT is active → STT pauses
User can edit text normally
Mic shows paused state visually
User taps mic again to resume listening
Send sends the full current draft exactly as shown in the input field
After sending, clear draft and reset mic to idle

---

## STT Engine Integration (v3)

- Primary STT path is local continuous ASR (`AudioRecord` + VAD + on-device transcription).
- UI consumes partial/final transcript updates from a persistent buffer in controller/viewmodel state.
- UI must not flicker or reset during internal speech segment transitions.
- During rollout, recognizer-based fallback is allowed, but user-facing chat behavior must stay identical.

Shipped wiring:

- `VoiceController` observes `ContinuousSpeechToTextEngine.onDraftChanged` and publishes it to
  `liveTranscript` — the input bar binds to that flow directly.
- The mic button calls `startListening(existingText = inputText)` so spoken words append to any
  typed draft without clobbering it.
- Tapping the send button or mic-stop calls `stopListeningAndCommit`, which reads the final
  assembled transcript from the engine and delivers it to the `onResult` callback.
- Tapping the input field calls `pauseListening`, which commits the current draft into the input
  field for editing and puts the session in `PAUSED` until `resumeListening(currentInputText)`.

See `VOICE_SYSTEM.md § Implementation Map (v3 shipped)` for the pipeline class layout.

---

## Chat States

| State | Input bar | Mic button | Send button |
|---|---|---|---|
| Idle | Active | Available to tap | Active if text present |
| User speaking | Shows accumulated speech text | Active (lit) | Active |
| Waiting for Eidos response | Inactive | Inactive | Inactive |
| Eidos TTS playing | Active | Available to tap | Active if text present |

The user can type or prepare their next message while TTS is playing.
The input bar is only inactive while waiting for the Eidos response to arrive.

---

## Scroll Behavior

- The conversation scrolls to the latest message automatically when a new message arrives
- The user can scroll up freely to read earlier messages
- If the user has scrolled up and a new message arrives, a scroll-to-bottom button appears
- Tapping the scroll-to-bottom button jumps to the latest message

---

## Technical Notes

- Message bubbles are rendered in a `LazyColumn`
- Each bubble is a separate composable item
- The reread button calls the same `TextToSpeech` instance used by the voice system
- Conversation scope is passed into the chat UI composable as a parameter — the composable does not determine its own scope
- All colors come from the theme token system defined in THEME.md — no hardcoded values
- The "Chats" system subfolder inside each user parent folder is auto-created by the database seed / parent folder creation flow — it is never created manually
