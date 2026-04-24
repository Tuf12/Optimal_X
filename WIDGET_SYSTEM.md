# WIDGET_SYSTEM.md

## Purpose

Defines the behavior contract for the home screen widget as a voice-first entry point to Eidos.

Use this file with `VOICE_SYSTEM.md`, `CHAT_UI.md`, and `DATA_MODEL.md`.

## Core Rule

The widget mic button **does not** open chat UI.

The mic is for hands-busy, app-independent conversation while the user navigates their device. Voice capture and responses run in the background flow.

## Interaction Model

### Idle Widget
- User sees minimal controls: mic, conversation picker, new chat, Quick Chat (not implemented yet).
- Widget remains a launcher/control surface, not a mini app UI.

### Mic State Model

- `Mic OFF` (idle): no active listening.
- `Mic ON` (recording): active capture/transcription.
- `Mic PAUSED` (yellow): listening paused, current draft preserved.
- `Mic PROCESSING` (red): send in progress (STT finalize -> LLM -> optional TTS).

### Primary Conversation Loop

1. User taps mic -> `Mic ON`.
2. User speaks; STT accumulates into a persistent pre-send draft buffer.
3. User taps send -> `Mic PROCESSING`.
4. STT finalizes the full utterance before send commit.
5. Message sends to Eidos; response is generated.
6. Optional TTS reads response.
7. Mic is handed back to user -> `Mic ON` again for continuous turn-taking.

### Pause / Resume / Cancel Rules

- Mic tap while `Mic ON` pauses listening -> `Mic PAUSED` (yellow).
- Pause must preserve the full draft (including late/segment-final text) before returning control.
- Mic tap while `Mic PAUSED` resumes listening and continues appending to the same draft.
- Long-press mic is explicit cancel: discard current unsent draft and turn mic off.
- Timeout must not discard a non-empty draft.

## Voice-to-Chat Continuity

Even though mic does not open chat UI, the same conversation must stay continuous:

- While voice is active, transcript and messages are written to the conversation record.
- Widget chat UI reads that same conversation record.
- If the user opens widget chat after speaking, they land in the same thread with full history already present.

This allows seamless transition from voice-first interaction to typed chat without branching into a second conversation.

## Active Conversation Targeting

- Chat button opens the current active widget conversation.
- Mic input appends to that same active conversation (no separate voice-only thread).
- `New chat` creates a new conversation and makes it active; subsequent mic turns append there.
- Selecting a conversation from the widget conversation list makes that thread active; subsequent mic turns append there.
- Users can change active conversation before mic or during an ongoing voice session; send commits to whichever conversation is currently active.

## Send From Anywhere (Current Scope)

To keep implementation small and reliable, "send from anywhere" is implemented with a foreground notification action:

- When widget mic starts a voice session, show an ongoing voice notification.
- Notification includes a `Send` action available from any screen/app.
- Notification `Send` and widget `Send` must call the same service action and commit the same active draft.

## Draft Buffer Contract (Pre-Send)

- Widget voice keeps an unsent draft buffer separate from committed chat messages.
- STT partials/finals append into this draft until send is tapped.
- Send commits the finalized draft as the user message.
- Pause/resume operates on this same draft buffer.
- Cancel (long-press mic) clears this draft buffer.


## Conversation Scope

Conversations created from `New chat` in the widget default to **general scope**:

- `scopeType = "general"`
- `parentFolderId = null`
- `subfolderId = null`
- Stored under `Eidos Chats` logical root

Widget can also resume an existing conversation from any scope when that conversation is selected as active.
Once selected, mic/send continue appending to that conversation instead of creating a new general thread.





## System Folder Context

`Eidos Journal`, `Eidos Log`, and `Eidos Chats` remain system folders. Widget chat history belongs in `Eidos Chats` when scope is general.

## Voice Engine Contract

Widget uses the same voice runtime contract as in-app chat:

- Shared engine factory and transcript assembler behavior
- Append-only transcript buffer until send/cancel
- Engine internals (segment boundaries/restarts) are hidden from user-facing widget behavior

See `VOICE_SYSTEM.md` for implementation details and runtime wiring.
