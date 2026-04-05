# VOICE_SYSTEM.md

## Purpose

This file defines the voice interaction system for OptimalX v2.

This covers wake word activation, speech to text input, text to speech output, the walkie talkie handoff system, and where voice is available across the app.

Coding agents should use this file alongside WIDGET_SYSTEM.md and EIDOS_AGENT.md to build the full voice layer.

---

## Where Voice Is Available

Voice interaction is available in three places:

| Location | Access method |
|---|---|
| Home screen widget | Talk button on the widget |
| Eidos bottom sheet (inside the app) | Voice button in the chat interface |
| Any chat screen | Voice button in the chat interface |

Voice is not a separate mode — it is an input and output option available wherever Eidos chat exists.

---

## Wake Word

The wake word activates Eidos voice mode without touching the screen.

- **Default wake word:** "Hey Eidos"
- The wake word is chosen to be unusual enough that it will not trigger accidentally in normal conversation
- The wake word is customizable in app settings
- Wake word detection runs in the background when the widget is active

### Wake word behavior
- User says "Hey Eidos"
- The widget activates and begins listening
- A visual indicator shows that Eidos is listening
- The user begins speaking their message

---

## Speaking to Eidos

### How input works
- User speaks their message
- Speech is converted to text in real time via Android speech to text
- Text is displayed on screen as the user speaks so they can see what is being captured
- The message is held until the user signals end of thought

### Signaling end of thought
The user has two options to send their message and pass control to Eidos:

| Method | Action |
|---|---|
| Voice command | Say the handoff word — default is "Over and out" |
| Button | Tap the send/pass button on screen |

Both methods do the same thing — send the captured text to Eidos and signal that it is Eidos turn to respond.

The handoff word is customizable in app settings.

---

## Eidos Responding

### Text to speech
- Eidos generates its response as text
- The response is read aloud via text to speech
- The response is also written into the active conversation note simultaneously
- Text to speech can be toggled on or off by the user — it does not have to always be active

### Passing back to the user
When Eidos finishes its response it signals that it is the user's turn:

| Method | Action |
|---|---|
| Voice signal | Eidos says the handoff word — "Over and out" |
| Visual indicator | Screen shows it is the user's turn |

This is the walkie talkie loop — explicit handoff in both directions so neither side is left waiting or guessing.

---

## Walkie Talkie Loop

The full conversation flow:

```
User says "Hey Eidos" → Eidos activates and listens
User speaks message → text captured in real time
User says "Over and out" or taps send → message sent to Eidos
Eidos generates response → reads it aloud
Eidos says "Over and out" → user knows it is their turn
User speaks next message → loop continues
User taps End button → conversation closes and is saved
```

This loop works hands free from start to finish.
The user never has to touch the screen once the wake word has activated the session.

---

## Read Aloud Toggle

Text to speech is not always on. The user controls it.

| State | Behavior |
|---|---|
| Read aloud on | Eidos reads every response aloud |
| Read aloud off | Eidos responds in text only — no audio |

The toggle is available in the chat interface.
The setting persists until the user changes it — it does not reset between sessions.

---

## Voice Settings

Both trigger phrases are customizable in app settings.

| Setting | Default | Description |
|---|---|---|
| Wake word | "Hey Eidos" | Phrase that activates voice mode |
| Handoff word | "Over and out" | Phrase that signals end of thought and passes control |

Both words should be set to phrases that are unlikely to appear in normal conversation.
The app should warn the user if they set a very common word or phrase.

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

## Text Chat Option

Not every interaction needs to be voice.

Every chat interface — widget and in-app — supports both voice and text input.

### Widget text option
- The widget has a button to open a text chat screen directly
- The chat screen supports both typing and voice
- The user switches between them freely within the same conversation

### In-app text option
- The Eidos bottom sheet and all chat screens default to text input
- A voice button is always available to switch to voice at any point
- Switching between text and voice mid conversation is supported

---

## Technical Notes

- Speech to text uses Android's built in speech recognition system
- Text to speech uses Android's built in text to speech engine
- Wake word detection requires a lightweight always-on listener — this should be implemented carefully to minimize battery impact
- Wake word detection only runs when the widget is active and enabled — it does not run in the background if the user has not set up the widget
- Voice processing happens on device where possible to protect privacy
