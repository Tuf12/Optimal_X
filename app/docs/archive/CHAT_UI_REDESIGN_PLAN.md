# Chat UI Redesign Plan

**Status:** Shipped (2026-05-31)  
**Reason:** Phase 1 of [VOICE_CHAT_STT_COMPLETION_PLAN.md](../implementation/VOICE_CHAT_STT_COMPLETION_PLAN.md) added Read aloud + Mic pass-back labeled `Switch` toggles to the top bar. This overloads the top bar on narrow screens and clips controls. The composer row can also stack up to 4 buttons (Mic, Stop, Send, and the header duplicates Stop).  
**Supersedes:** Top bar and Input bar sections of [CHAT_UI.md](../architecture/CHAT_UI.md)  
**Applies to:** `EidosChatScreen`, `WebPanel` (Eidos sheet), `WidgetChatActivity`

---

## Problems

| # | Problem | Where |
|---|---------|-------|
| 1 | Top bar has up to 8+ elements in one `Row`: Back, title/scope column, History, New Chat, Move, Stop, "Read aloud" label+Switch, "Mic pass-back" label+Switch | All 3 chat surfaces |
| 2 | Labeled `Switch` toggles ("Read aloud", "Mic pass-back") occupy ~180dp of horizontal space and clip on phones < 400dp wide | All 3 chat surfaces |
| 3 | Bottom composer row can show 4 items (TextField + Mic + Stop + Send) when model is streaming | `EidosChatScreen`, `WidgetChatActivity` |
| 4 | No shared composable — composer is copy-pasted across 3 files with slight inconsistencies (TextField vs OutlinedTextField, IconButton vs Surface send) | Code duplication across 3 files |
| 5 | Adding any more controls near the mic button further shrinks the text field on narrow screens | Bottom row, `weight(1f)` text field |

---

## Design principles (from UI_PRINCIPLES.md)

- **"Big. Simple. Blunt."** — one clear purpose per screen area
- **"If it isn't needed, it isn't there"** — no extra buttons
- **Consistency** — same layout patterns across similar screens
- **No decorative elements** — every pixel serves a function

---

## New layout

### Top bar — clean action row

```
┌─────────────────────────────────────────────────┐
│  ←  │ Eidos          │  🕘  💬+  🔊  ⋮         │
│     │ scope label    │                          │
└─────────────────────────────────────────────────┘
```

| Position | Element | Notes |
|----------|---------|-------|
| Left | Back `IconButton` | Unchanged |
| Center-left | Title column (`weight(1f)`) | "Eidos", scope label, optional workshop phase — unchanged. Memory depth **moves to overflow menu** |
| Right | History `IconButton` | Unchanged |
| Right | New Chat `IconButton` | Unchanged |
| Right | Read Aloud `IconButton` (`VolumeUp` / `VolumeOff`) | **New** — toggles Read aloud on/off. Accent tint when on, `textMid` when off. Shows info dialog on first use |
| Right | Overflow `IconButton` (⋮ `MoreVert`) | **New** — opens dropdown menu |

The top bar drops from 8+ elements to **6 fixed icons** (Back, title column, History, New Chat, Read Aloud, overflow) — compact, never clips.

**Read Aloud icon button behavior:**

- **Tap** toggles `READ_ALOUD` on/off immediately
- **First-time tap** (per `READ_ALOUD_INFO_DISMISSED` DataStore key, default `false`): shows an `AlertDialog` **before** toggling, explaining the read aloud system:
  - Title: "Read Aloud"
  - Body explains: auto TTS on Eidos replies, mic pass-back option (re-opens mic after TTS so you can speak your next message, then tap Send), how hands-free flow works (speak → Send → TTS → mic reopens → repeat)
  - Checkbox: "Don't show this again" — when checked, sets `READ_ALOUD_INFO_DISMISSED = true`
  - Buttons: "Enable" (turns on Read aloud + respects checkbox), "Cancel" (dismisses, no change)
- **Subsequent taps** (after info dismissed): direct toggle, no dialog
- **Visual state:** `VolumeUp` icon in `accent` when on; `VolumeOff` icon in `textMid` when off

**Overflow menu (⋮) contents:**

| Item | Type | When visible |
|------|------|-------------|
| Move conversation here | Menu item | Always (disabled when no active conversation) |
| Memory depth: *label* | Tappable menu item | When active conversation exists. Tap cycles depth (same as current clickable label) |
| Whisper mic | Toggle menu item | Always (requires OpenAI API key to enable) |
| ─── divider ─── | | |
| Pass-back mic | Toggle menu item (switch) | Only when Read aloud is on |
| Stop generating | Menu item | While `isSending` (secondary to composer Stop) |

Stop generating **primary** control is the composer row (Send/Stop share one slot). This replaces the labeled `Switch` blocks, the Move icon button, and the inline memory depth label. The top bar is a fixed icon set that never grows.

### Bottom composer — 3-element row with Send/Stop merge

```
┌─────────────────────────────────────────────────┐
│  ┌─────────────────────────┐  🎤  ▶/■          │  ← composer row
│  │ Message Eidos…          │                    │
│  └─────────────────────────┘                    │
└─────────────────────────────────────────────────┘
```

No voice status strip — Read aloud state is visible on the top bar icon (accent vs dim), and mic pass-back is accessible from the overflow menu. The composer area stays maximally clean.

**Composer row** (always visible):

| Position | Element | Size | Notes |
|----------|---------|------|-------|
| 1 | `OutlinedTextField` | `weight(1f)`, `heightIn(min = 48.dp, max = 140.dp)` | Rounded corners (`RoundedCornerShape(12.dp)`), max 5 lines. Same text/voice shared field behavior |
| 2 | Mic `IconButton` | 44dp | Same state machine (Idle→start, Listening→pause, Paused→resume, Speaking→stop TTS) |
| 3 | Send / Stop | 44dp `Surface` button | **Mutually exclusive:** shows Stop (■) when `isSending`, otherwise shows Send (▶). Accent fill when enabled; `surface2` when disabled |

Key changes:
- **Send and Stop share one slot** — never show both simultaneously. This removes the 4th button.
- **`OutlinedTextField`** everywhere (not `TextField` in some, `OutlinedTextField` in others) — consistency
- **Slight size reduction** (44dp vs 46dp) — frees ~4dp horizontal space
- **Top bar Stop button removed** — Stop lives only in the composer row (was duplicated because top bar clipped; no longer needed since top bar is clean)
- **No voice status strip** — voice state is communicated by the Read aloud icon tint in the top bar

### Shared composable: `ChatComposerBar`

Extract the bottom composer row into a single reusable composable:

```kotlin
@Composable
fun ChatComposerBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    sessionState: VoiceSessionState,
    isSending: Boolean,
    isCapturingVoice: Boolean,
    isPaused: Boolean,
    isSpeaking: Boolean,
    liveTranscript: String,
    onTextFieldFocused: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Placed in `ui/components/ChatComposerBar.kt`. Used by all 3 chat surfaces — removes ~120 lines of duplicated code per surface.

### Shared composable: `ChatTopBar`

Extract the top bar into a reusable composable:

```kotlin
@Composable
fun ChatTopBar(
    onBack: () -> Unit,
    scopeLabel: String,
    workshopPhaseLabel: String?,
    memoryDepthLabel: String,
    onMemoryDepthClick: () -> Unit,
    hasActiveConversation: Boolean,
    isSending: Boolean,
    onHistoryClick: () -> Unit,
    onNewChatClick: () -> Unit,
    onMoveClick: () -> Unit,
    onStopClick: () -> Unit,
    readAloud: Boolean,
    readAloudMicPassback: Boolean,
    onReadAloudChange: (Boolean) -> Unit,
    onMicPassbackChange: (Boolean) -> Unit,
    readAloudInfoDismissed: Boolean,
    onReadAloudInfoDismissedChange: (Boolean) -> Unit,
    micUseWhisperApi: Boolean,
    hasOpenAiApiKey: Boolean,
    onMicUseWhisperApiChange: (Boolean) -> Unit,
    onOpenChatSettings: () -> Unit = {},
    restrictToolbar: Boolean = false,
    modifier: Modifier = Modifier,
)
```

Placed in `ui/components/ChatTopBar.kt`. The `restrictToolbar` flag hides History/NewChat for surfaces that don't use them (e.g. Web panel sheet).

### Read Aloud info dialog

Shown on first tap of the Read Aloud icon (when `READ_ALOUD_INFO_DISMISSED` is `false`). Managed inside `ChatTopBar`.

**DataStore key:** `SettingsKeys.READ_ALOUD_INFO_DISMISSED` (`Boolean`, default `false`). Set to `true` when user checks "Don't show again" and taps Enable.

---

## Phase checklist

### Phase 0 — Data layer + shared composables

| ID | Task | Status |
|----|------|--------|
| 0a | Add `SettingsKeys.READ_ALOUD_INFO_DISMISSED` + default `false` in `SettingsPreferences.kt` | ✅ |
| 0b | Expose `readAloudInfoDismissed` flow + setter in `EidosChatViewModel` | ✅ |
| 0c | Create `ChatComposerBar.kt` in `ui/components/` — composer row (OutlinedTextField + Mic + Send/Stop) | ✅ |
| 0d | Create `ChatTopBar.kt` in `ui/components/` — top bar with Read Aloud icon, overflow menu, info dialog | ✅ |
| 0e | Create `ReadAloudInfoDialog` inside `ChatTopBar.kt` — explainer dialog with "Don't show again" checkbox | ✅ |

**Files:** `SettingsPreferences.kt`, `EidosChatViewModel.kt`, `ui/components/ChatComposerBar.kt`, `ui/components/ChatTopBar.kt`

### Phase 1 — EidosChatScreen

| ID | Task | Status |
|----|------|--------|
| 1a | Replace inline top bar with `ChatTopBar` | ✅ |
| 1b | Remove "Read aloud" label + `Switch` from top `Row` | ✅ |
| 1c | Remove "Mic pass-back" label + `Switch` from top `Row` | ✅ |
| 1d | Remove inline memory depth label from title column (now in overflow) | ✅ |
| 1e | Remove header Stop `IconButton` (keep only composer Stop) | ✅ |
| 1f | Remove header Move `IconButton` (now in overflow) | ✅ |
| 1g | Replace inline composer `Row` with `ChatComposerBar` | ✅ |
| 1h | Visual test: narrow phone (360dp), landscape, with keyboard open | ✅ |

**Files:** `EidosChatScreen.kt`

### Phase 2 — WidgetChatActivity

| ID | Task | Status |
|----|------|--------|
| 2a | Replace inline top bar with `ChatTopBar` | ✅ |
| 2b | Remove labeled switches + Move + Stop from top `Row` | ✅ |
| 2c | Replace inline composer with `ChatComposerBar` | ✅ |
| 2d | Visual test: widget on small home screen / split screen | ✅ |

**Files:** `WidgetChatActivity.kt`

### Phase 3 — WebPanel Eidos sheet

| ID | Task | Status |
|----|------|--------|
| 3a | Replace inline sheet header with `ChatTopBar` (appropriate params for sheet context) | ✅ |
| 3b | Remove labeled switches + Stop from sheet header `Row` | ✅ |
| 3c | Replace inline composer with `ChatComposerBar` | ✅ |
| 3d | Verify Eidos sheet overlay + `imePadding` still works | ✅ |
| 3e | Visual test: WebPanel sheet on narrow + wide screens | ✅ |
| 3f | `webPanelEidosSheetOpen` in `EidosChatViewModel` — sheet survives rotation (pager breaks `rememberSaveable`) | ✅ |

**Files:** `WebPanel.kt`, `EidosChatViewModel.kt`

### Phase 4 — Architecture doc update

| ID | Task | Status |
|----|------|--------|
| 4a | Update `CHAT_UI.md` § Top Bar Controls — Read Aloud icon + overflow menu | ✅ |
| 4b | Update `CHAT_UI.md` § Input Bar — Send/Stop merge, shared composable | ✅ |
| 4c | Update `VOICE_CHAT_STT_COMPLETION_PLAN.md` Phase 1 — note redesign moved controls | ✅ |
| 4d | Update this plan's status | ✅ |

**Files:** `CHAT_UI.md`, `VOICE_CHAT_STT_COMPLETION_PLAN.md`

---

## Post-ship follow-ups (not in original phases)

| Item | Notes |
|------|--------|
| Web send without search thread | `ensureWebPageChatContext()` when Eidos sheet opens on a loaded URL |
| Composer tap targets | Focus-dismiss `clickable` scoped to message list only (not whole column) so Send/Stop work |
| Keyboard dismiss | Tap message area clears focus / hides IME |
| Whisper mic in overflow | Added after initial plan; documented in `CHAT_UI.md` |
| Rotation voice | `WebPanel` skips `stopSession()` on `ON_STOP` when `isChangingConfigurations` |

---

## Visual comparison

### Before (current)

**Top bar:**
```
←  Eidos / scope / mem  🕘 💬+ 📁 ■  Read aloud [==] Mic pass-back [==]
```
(8+ elements, clips on narrow screens)

**Bottom composer (while sending):**
```
[  Message Eidos…          ]  🎤  ■  ▶
```
(4 elements)

### After (redesign)

**Top bar (Read aloud off):**
```
←  Eidos / scope                    🕘  💬+  🔇  ⋮
```
(6 fixed icons, never clips. 🔇 = `VolumeOff` in `textMid`)

**Top bar (Read aloud on):**
```
←  Eidos / scope                    🕘  💬+  🔊  ⋮
```
(same 6 icons. 🔊 = `VolumeUp` in `accent`)

**Overflow menu (⋮):**
```
┌──────────────────────────────┐
│ Move conversation here       │
│ Memory: full context         │  ← tap cycles depth
│ Whisper mic            [==] │
│ ─────────────────────        │
│ Pass-back mic            [==] │  ← only when Read aloud on
│ Stop generating              │  ← while sending (optional)
└──────────────────────────────┘
```

**Bottom composer (while sending):**
```
[  Message Eidos…          ]  🎤  ■
```
(3 elements, Stop replaces Send)

**Bottom composer (idle with text):**
```
[  Hello Eidos              ]  🎤  ▶
```
(3 elements, Send shown)

---

## Out of scope

- Tablet/foldable dual-pane chat layout
- Chat bubble redesign (bubbles + footer unchanged)
- Conversation history sheet redesign
- Workshop mode selector row (unchanged, lives below top bar when applicable)
- Settings screen voice toggles (unchanged — global defaults)

---

## Changelog

| Date | Notes |
|------|-------|
| 2026-05-31 | **Shipped** — Phases 0–4 complete. Follow-ups: Web send (`ensureWebPageChatContext`), composer tap target fix, `webPanelEidosSheetOpen` in VM for rotation, keyboard dismiss on tap-outside, Whisper mic in overflow. Docs: `CHAT_UI.md`, `VOICE_CHAT_STT_COMPLETION_PLAN.md`, `CHAT_FIX.md` hygiene. |
| 2026-05-31 | v2: Read aloud becomes standalone `IconButton` with first-use info dialog; mic pass-back + memory depth move to overflow menu; voice status strip removed |
| 2026-05-31 | v1: Plan created — redesign top bar (overflow menu), composer (shared composable, Send/Stop merge) |
