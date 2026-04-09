
# VOICE_SYSTEM.md

## Purpose

This file defines the voice interaction system for OptimalX v2.

This covers speech to text input, text to speech output, the push-to-talk interaction model, and where voice is available across the app.

Coding agents should use this file alongside WIDGET_SYSTEM.md and EIDOS_AGENT.md to build the full voice layer.

---

## Where Voice Is Available

Voice interaction is available in three places:

| Location | Access method |
|---|---|
| Home screen widget | Mic button on the widget |
| Eidos bottom sheet (inside the app) | Mic button in the chat interface |
| Any chat screen | Mic button in the chat interface |

Voice is not a separate mode — it is an input and output option available wherever Eidos chat exists.

---

## Speech to Text — Core Model

### Push-to-talk

Voice input uses a push-to-talk model.

- User taps the mic button → recording starts
- User speaks freely, pausing as needed
- User taps the send button (or taps mic again) → captured text is sent to Eidos

The user controls when recording starts and when the message is sent.
There is no auto-send on silence.
There is no timeout that ends the conversation.

### Persistent text buffer

All captured speech is accumulated in a persistent text buffer.

Rules:
- The buffer holds everything the user has said since tapping the mic button
- The buffer is displayed in the input bar in real time as speech is captured
- The buffer is never cleared by a SpeechRecognizer restart — new results are always appended
- The buffer is only cleared after the message is successfully sent
- Tapping the mic button to start recording never clears the buffer
- If the user taps mic again to resume after a pause, new speech appends to what is already there

### SpeechRecognizer restart behavior

Android's `SpeechRecognizer` has a built-in silence timeout that cannot be disabled.
When it times out it stops itself. The app must restart it silently.

Implementation rules:
- When `onError` or `onEndOfSpeech` fires and the mic button is still active, restart `SpeechRecognizer` immediately
- The restart must be silent — no beeps, no system audio feedback, no UI change visible to the user
- Disable all system audio feedback on the recognizer intent:
    - Set `RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_SILENCE` to true
    - Set audio stream to silent or suppress default recognizer sounds
- `onResults` and `onPartialResults` always append to the buffer — never replace it
- The mic icon stays active (lit, animated) through the restart — it must never flicker or reset
- The user must not be able to tell that a restart happened

### What the input bar shows

- While mic is active: accumulated text updates in real time as speech comes in
- Text already in the buffer is preserved and new speech appends after it
- After sending: input bar clears and returns to empty state
- While Eidos is responding: input bar is inactive

---

## Sending a Message

The user has two ways to send while the mic is active:

| Method | Action |
|---|---|
| Tap send button | Finalizes the buffer and sends to Eidos |
| Tap mic button (while active) | Stops recording and sends to Eidos |

Both methods do the same thing: stop recording, send the full buffer content to Eidos, clear the buffer.

---

## Eidos Responding

### Text to speech
- Eidos generates its response as text
- If read-aloud is on, the response is read aloud via Android TTS immediately after it is received
- The response is also written into the active conversation note simultaneously
- Text to speech can be toggled on or off by the user at any time

### After Eidos finishes responding
- If read-aloud is on: mic button reactivates automatically when TTS finishes — the user can speak immediately without tapping anything
- If read-aloud is off: mic button is available to tap — the user initiates the next input manually

There is no handoff phrase. The flow is controlled by buttons and TTS completion, not voice commands.

---

## Read Aloud Toggle

| State | Behavior |
|---|---|
| Read aloud on | Eidos reads every response aloud. Mic reactivates automatically after TTS finishes. |
| Read aloud off | Eidos responds in text only. User taps mic to speak next message. |

The toggle is a button in the chat interface.
The setting persists until the user changes it — it does not reset between sessions.

---

## Wake Word

The wake word activates Eidos voice mode without touching the screen.

- Default wake word: "Hey Eidos"
- Customizable in app settings
- Wake word detection only runs when the widget is active and enabled
- Does not run in the background if the widget has not been set up by the user

### Wake word behavior
- User says "Hey Eidos"
- The widget activates and the mic goes active — same state as tapping the mic button
- A visual indicator shows that Eidos is listening
- User speaks their message, then taps send

---

## Voice Settings

| Setting | Default | Description |
|---|---|---|
| Wake word | "Hey Eidos" | Phrase that activates voice mode from the widget |
| Read aloud | On | Whether Eidos reads responses aloud |

The handoff phrase ("Over and out") has been removed. Sending is controlled by the send button or mic button only.

---

## Widget Voice vs In-App Voice

The voice system works the same way in both the widget and inside the app.
The only difference is where the conversation is saved.

| Location | Conversation saved to |
|---|---|
| Widget | Eidos Chats system folder (app root) |
| Inside a subfolder | That subfolder's chat folder |
| Outside a subfolder inside the app | Eidos Chats system folder (app root) |

Full detail on conversation storage is defined in WIDGET_SYSTEM.md and JOURNAL_SYSTEM.md.

---

## Text and Voice Together

Every chat interface supports both text and voice input in the same conversation.

- The input bar always accepts typed text
- The mic button is always available alongside the input bar
- The user switches between typing and speaking freely within the same conversation
- Voice input appends to whatever is already in the input bar — including text the user typed

---

## Technical Notes

- Speech to text uses Android's built-in `SpeechRecognizer`
- Text to speech uses Android's built-in `TextToSpeech` engine
- `SpeechRecognizer` will time out on silence — this is an Android limitation that cannot be removed
- The app must restart `SpeechRecognizer` silently on every timeout while the mic is active
- All restarts must be inaudible — suppress all system audio feedback on the recognizer intent
- The persistent buffer in the ViewModel (not in the recognizer) is the source of truth for accumulated text
- Never rely on the recognizer's own state to hold the full message — always write results to the buffer immediately
- Voice processing happens on device — no audio is sent to external servers
- Wake word detection requires a lightweight always-on listener — implement carefully to minimize battery impact
- Wake word detection only runs when the widget is active and enabled
