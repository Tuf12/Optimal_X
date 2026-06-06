# WIDGET_SYSTEM.md

## Purpose

Defines the behavior contract for the home screen widget as a voice-first entry point to Eidos.

Use this file with `VOICE_SYSTEM.md`, `CHAT_UI.md`, and `DATA_MODEL.md`.

**Voice / read-aloud fix tracker:** [VOICE_CHAT_STT_COMPLETION_PLAN.md](../implementation/VOICE_CHAT_STT_COMPLETION_PLAN.md) Phase 1.

## Core Rule

The widget **Ask Eidos** button runs a one-shot voice Q&A in the background (Google STT). It logs exchanges to a general-scope **Quick Ask log** conversation in the DB — **not** the Widget Chat UI thread.

The **Chat** button opens [WidgetChatActivity](app/src/main/java/com/example/optimalx/widget/WidgetChatActivity.kt), a standalone chat surface with its own mic (Whisper when enabled). Widget voice service transcripts no longer feed Widget Chat automatically.

## Interaction Model

### Idle Widget
- Controls: **Quick Notes**, **Conversations**, **Ask Eidos**, **Send**, **Chat**, web search row.
- No **New** button on the widget (new chat lives inside Widget Chat UI).

### Ask Eidos (bottom mic)

1. Tap **Ask Eidos** → Google STT listens.
2. Tap **Send** → transcript sent to Eidos; optional TTS when **Widget hands-free** is on (see Voice preferences below).
3. Exchange saved to Quick Ask log (`WidgetPrefs.quick_ask_log_conversation_id`).
4. **Chat** button opens Widget Chat UI pointer only — not the Quick Ask log.

Post-reply TTS uses **`WIDGET_VOICE_HANDS_FREE`**. Mic re-open after TTS uses **`READ_ALOUD_MIC_PASSBACK`** (independent of in-app Read aloud).

### Quick Notes

- Top-row **Quick Notes** mic → today's Quick Notes day folder (unchanged).
- Uses Google STT via `WidgetVoiceService`.

### Mic State Model

- `Mic OFF` (idle): no active listening.
- `Mic ON` (recording): active capture/transcription.
- `Mic PAUSED` (yellow): listening paused, current draft preserved.
- `Mic PROCESSING` (red): send in progress (STT finalize -> LLM -> optional TTS).

### Quick Notes Capture Mode

- Quick Notes capture is a widget-specific variant of mic flow.
- User taps `Note` to start capture into a dedicated Quick Notes draft buffer.
- `Note` indicator is green while capture is active.
- User taps widget `Send` to commit that draft as a quick note.
- `Note` indicator is red while send/processing is in progress.
- Quick Notes capture does not use pause/resume state.
- Commit path writes via `write_quick_note`.
- After commit, mic is handed back to user (`Mic ON`) so they can continue adding entries.
- User ends Quick Notes capture by tapping `Note` again.
- If intent is clearly non-note (question/request), Eidos may branch to normal conversation behavior.

### Primary Conversation Loop

1. User taps mic -> `Mic ON`.
2. User speaks; STT accumulates into a persistent pre-send draft buffer.
3. User taps send -> `Mic PROCESSING`.
4. STT finalizes the full utterance before send commit.
5. Message sends to Eidos; response is generated.
6. When **Widget hands-free** is on, TTS reads the response.
7. When **Widget hands-free** and **Mic pass-back** are both on, mic is handed back -> `Mic ON` for the next turn.
8. User **taps Send** again to commit the next utterance — no silence auto-send.

### Voice preferences (three independent settings)

| Pref | Default | Applies to | Behavior |
|------|---------|------------|----------|
| `READ_ALOUD` | off | In-app + **Widget Chat UI** only | Auto TTS when a **new assistant message** arrives in chat surfaces |
| `READ_ALOUD_MIC_PASSBACK` | on | Chat UIs + widget hands-free loop | After TTS, re-open mic (`thenListen` / `handMicBackToUser`). Off = speak only, mic stays idle |
| `WIDGET_VOICE_HANDS_FREE` | off | **Home widget** Quick Ask / Quick Notes | Post-reply TTS + optional mic loop in `WidgetVoiceService`. **Not** tied to `READ_ALOUD` |

**Target matrix** (Phase 1): [VOICE_CHAT_STT_COMPLETION_PLAN.md](../implementation/VOICE_CHAT_STT_COMPLETION_PLAN.md) § Voice behavior matrix.

**Widget Chat UI** uses `VoiceController` (Whisper when enabled + key; else Google). **Ask Eidos / Quick Notes** use Google STT only via `WidgetVoiceService`.

### Pause / Resume / Cancel Rules

- Mic tap while `Mic ON` pauses listening -> `Mic PAUSED` (yellow).
- Pause must preserve the full draft (including late/segment-final text) before returning control.
- Mic tap while `Mic PAUSED` resumes listening and continues appending to the same draft.
- Long-press mic is explicit cancel: discard current unsent draft and turn mic off.
- Timeout must not discard a non-empty draft.

## Voice-to-Chat Continuity

Widget Chat UI is **decoupled** from widget voice capture:

- Quick Ask log is for audit/history only; opening **Chat** does not auto-load Quick Ask exchanges.
- Widget Chat maintains its own active conversation via `WidgetPrefs.active_conversation_id`, set only from in-activity sends/new chat.

## Active Conversation Targeting

- **Chat** opens `WidgetPrefs.active_conversation_id` (Widget Chat UI only).
- **Ask Eidos** writes to Quick Ask log — does not update the chat pointer.
- **Conversations** picker sets chat pointer and opens Widget Chat.

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

**Shipped (2026-05-31):**

| Widget surface | STT engine |
|----------------|------------|
| Ask Eidos, Quick Notes | `GoogleSpeechToTextEngine` only (`WidgetVoiceService`) |
| Widget Chat UI mic | `VoiceController` → Whisper API when toggle + key; else Google |

- Append-only transcript buffer until send/cancel
- Engine internals hidden from user-facing widget behavior

See `VOICE_SYSTEM.md` for routing and `VOICE_CHAT_STT_COMPLETION_PLAN.md` for remaining voice pref split (Phase 1).
