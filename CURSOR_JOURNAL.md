JOURNAL.md

# OptimalX — Agent Context Journal

## System Overview (Read First)

OptimalX is an **AI-first structured knowledge system**.

It is designed so that:
- both humans and AI operate on the same data
- the AI (Eidos) can fully interact with and modify the system
- structure is simple, consistent, and predictable

Core structure:

- App opens to **parent folders**
- Parent folders contain **subfolders**
- Subfolders open directly into a **note/editor**
- The note is the central working space
- Additional data (files, tools, etc.) attaches to the subfolder

This system prioritizes:
- simplicity
- clarity
- fast navigation
- structured organization

---

## AI Role in the System

The AI is not a feature.

The AI is an **active operator inside the system**.

It can:
- create and organize folders
- write and update notes
- search and retrieve information
- build structure over time
- maintain its own understanding of the system

The AI interacts through **tool functions**.

---

## Purpose of This File

This file exists to allow the agent to:

- maintain continuity between prompts
- store useful observations about the system
- track decisions and reasoning
- improve how it approaches development over time

This is **not a rule system**.

This is a **working memory layer**.

---

## How To Use This File (Agent)

You may use this space to:

- log important changes
- store insights about the codebase
- track patterns that improve performance
- note problems and potential solutions
- refine your approach to building OptimalX

You may organize this section in any way that helps you operate better.

Keep entries:
- concise
- relevant
- useful for future steps

Avoid unnecessary repetition.

---

## Developer Visibility

This file is visible to the developer.

Entries should remain:
- clear
- grounded in the project
- aligned with the system's purpose

---

## Journal Entries

### Entry Format (suggested)

- Date:
- Change / Observation:
- Reasoning:
- Impact:

---

### Entries

---

**2026-04-05 — Phase 1 complete**
Built full project foundation: Gradle/Compose/Room setup, theme system (DarkColors/LightColors/OptimalXTheme), Google Fonts (Syne/DM Sans/DM Mono), all four Room entities and DAOs, AppDatabase, DatabaseSeed (creates Eidos Journal/Log/Chats system folders on first install).

---

**2026-04-05 — Phase 2 complete**
Built all folder screens and navigation. Key files: OptimalXApplication, FolderRepository, AppNavigation, ParentFolderViewModel, SubfolderViewModel, TrashViewModel, FolderPage (shared layout), ParentFolderScreen, SubfolderScreen, TrashScreen, EditorScreen (placeholder). All components: FolderCard, FolderTopBar, FolderBottomBar, CreateFolderDialog, RenameFolderDialog, SortBottomSheet, FolderContextMenu, MoveFolderDialog. Design decision: Move option omitted from parent folder context menu (nowhere to move them — no parent above root).

---

**2026-04-05 — Phase 3 complete**
Built full editor view. Key files: EditorRepository, EditorViewModel (undo/redo history stack + SharedFlow restore, auto-save debounce), EditorScreen (HorizontalPager with Note/Files/open file panels, back handler per panel), NotePanel (richeditor-compose RichTextEditor, formatting toolbar, dropdown menu, view/edit mode, AI lock, share/export), FilesPanel (file picker, list with badges, delete), PdfViewerPanel (Android PdfRenderer), ImageViewerPanel (pinch-to-zoom with transformable), DocxViewerPanel (ZIP+XML text extraction), TextFilePanel (plain text). Added richeditor-compose:1.0.0-rc06 to Gradle. Undo/redo is content-level (500ms snapshots), not character-level — acceptable for v2.

---

**2026-04-05 — Phase 4 complete**
Built full settings flow and navigation. Key files: SettingsScreen (new), SettingsViewModel (existing, fully consumed by UI), AppNavigation (new settings route and wired three-dot menu action). Implemented: theme segmented control (light/dark/system, instant DataStore write and live app theme update), API key fields for xAI/OpenAI/Anthropic (EncryptedSharedPreferences), provider radio selection (xAI default), wake word + handoff word text fields (DataStore), and read-aloud toggle (DataStore). Added back navigation from Settings to Parent Folder screen.

---

**2026-04-05 — Phase 5 complete**
Built provider-agnostic API layer and model clients. Key files: EidosApiClient, Eidos models (request/response/messages/tool-call types), EidosToolCatalog, provider implementations (XAIProvider/OpenAIProvider/AnthropicProvider), shared ProviderHttp helper, and DefaultToolExecutor placeholder for Phase 6. Implemented: provider selection from EncryptedSharedPreferences, API key routing per provider, OpenAI Responses API with `previous_response_id` + `reasoning.effort = none` + `text.verbosity = low`, Anthropic prompt caching block on system prompt, context assembly (current subfolder, note unless AI-locked, attached files, recent journal entries 1-3 days), history trimming, tool-calling loop with repeated round-trips until final text, confirmation gate hook for destructive tools, automatic write_log_entry calls after modifying actions, and retry-once fallback with provider-switch guidance. Also added OkHttp + kotlinx.serialization dependencies and INTERNET permission.

---

**2026-04-05 — Phase 6 mostly complete (2 open items)**
Replaced placeholder tool executor with `RoomToolExecutor` and wired it into app startup. Implemented full folder tools, note tools (with AI lock checks), search_system with date filtering, journal and log daily-subfolder append/read behavior, and voice_handoff logging. Implemented file tools with working extraction for docx/odt/ods/xlsx/text/code formats and metadata-based image description, plus summarize_file. Remaining open: `read_file` PDF extraction and true model-vision integration for `describe_image` (currently returns image metadata plus explicit not-yet-integrated status). API-layer auto-log path now safely handles non-JSON tool argument payloads.

---

**2026-04-05 — Phase 6 complete**
Finished the remaining file-tool gaps. `read_file` now supports PDF text extraction via PDFBox Android (`com.tom-roush:pdfbox-android`) in addition to existing docx/odt/ods/xlsx/txt/md/code/json/xml/html/css paths. `describe_image` now calls provider vision APIs through a new `ImageVisionService` and returns model-generated descriptions with image metadata. Provider routing follows active encrypted provider setting and uses provider-specific request formats (OpenAI Responses vision input, Anthropic image blocks, xAI multimodal chat payload). Restored downloadable Google Fonts with official cert arrays (`font_certs.xml`) and kept full compile green.

---

**2026-04-05 — Phase 7 complete**
Built and wired the in-app Eidos chat bottom sheet. Key files: `EidosBottomSheet` (new UI), `AppNavigation` (global bottom-sheet state + scope wiring), and `EidosChatViewModel` (used by sheet with message persistence). Implemented: Eidos button wiring from Parent Folder, Subfolder, and Editor top bars; swipe-to-dismiss ModalBottomSheet sized to ~65% of screen; user/right and Eidos/left bubble styling with gradient + `messageBubbleEidosBorder`; input + send controls; voice button placeholder for Phase 10; read-aloud toggle bound to DataStore from inside sheet; scoped chat storage (`__chat_{subfolderId}__` system subfolder) and root chat storage (`Eidos Chats` system folder) with appended transcript format `[HH:MM AM/PM] User/Eidos: ...`. Also fixed a chat persistence bug so successive lines append against latest note content and hardened send flow with error fallback + `isSending` reset in `finally`.

---

**2026-04-05 — Phase 8 complete**
Built the dedicated Eidos system UI flow for Journal/Log/Chats. Key files: `EidosSystemScreens` (new section, system-folder list, read-only note view, linked-note deep-link target), `AppNavigation` (new eidos routes and deep-link routing), `FolderTopBar` + `FolderPage` + `ParentFolderScreen` (new Parent top-bar entry point into Eidos section). Implemented: dedicated Eidos section accessible from Parent top bar; three entries (Journal, Log, Chats); per-entry subfolder lists sourced from system parent folders; checkbox multi-select with delete-selected behavior; read-only note screens for Journal/Log; Chats open into the standard Editor route; log entry parsing for `location=` / `anchor=` metadata; deep-link open flow resolving `subfolder:<id>` and navigating to a linked note screen that auto-scrolls/highlights the anchor line when present.

---

**2026-04-05 — Phase 9 complete**
Finished on-device semantic search end-to-end. Added TensorFlow Lite dependency and bundled a USE-style text embedder model at `app/src/main/assets/models/universal_sentence_encoder.tflite` (`TFL3` flatbuffer). Completed vector store implementation (`SemanticVector` + DAO + DB migration v1→v2), `EmbeddingEngine`, `SemanticIndexer`, and semantic embedding hooks across all write/update paths (folder/subfolder create+rename, note updates in editor and tool calls, chat transcript note writes, and file text extraction via `read_file`). Added `search_semantic` tool and updated `search_system` to run semantic-first with keyword fallback and date filtering. Added startup diagnostics in `OptimalXApplication` (`EmbeddingEngine.diagnostics()`) so runtime logs confirm whether model inference path is active vs fallback.

**Addendum — TFLite model fix:**
The original `tensorflow-lite-select-tf-ops` dependency did not include `TFSentencepieceTokenizeOp` (a custom op from TensorFlow Text, not standard select-ops). Switched to `com.google.mediapipe:tasks-text:0.10.14` which bundles `libmediapipe_tasks_text_jni.so` with full SentencePiece support. Rewrote `EmbeddingEngine` to use MediaPipe `TextEmbedder` (`createFromOptions` + `BaseOptions.setModelAssetPath`). Confirmed working: diagnostics log shows `loaded=true, inferenceOk=true, fallbackOnly=false`. Removed `tensorflow-lite-select-tf-ops` entirely; kept bare `tensorflow-lite` runtime as it's pulled transitively anyway.

**Next: Phase 10 — Voice System.**

---

**2026-04-05 — Phase 10 complete**
Built full voice layer. Key files: `SpeechToTextEngine` (Android SpeechRecognizer wrapper — partial results via callbacks, handoff-word detection in partial stream), `TextToSpeechEngine` (Android TTS wrapper — async init, utterance completion via UtteranceProgressListener), `VoiceController` (AndroidViewModel — state machine: IDLE/LISTENING/PROCESSING/SPEAKING; `startListening`, `setProcessing`, `speakAndRestart`, `stopSession`), `WakeWordDetector` (stub — wired in Phase 11 when widget is built). Added RECORD_AUDIO permission to AndroidManifest. Rewrote `EidosBottomSheet` to wire the mic button: RECORD_AUDIO permission request on first use, VoiceController state drives the input field display (live transcript shown in accent color during LISTENING), MicOff icon during active session, LaunchedEffect detects new ASSISTANT messages during PROCESSING state and calls `speakAndRestart`. Added `isCommonWord` warning to SettingsScreen below wake/handoff word fields (warns if phrase is 3 chars or fewer, or in a list of common trigger words). All voice processing on-device.

**Next: Phase 11 — Home Screen Widget.**

---

**2026-04-05 — Phase 11 complete**
Built the home screen widget end-to-end. Key files: `res/xml/widget_info.xml` (4×2 cell, no auto-update), `res/layout/widget_layout.xml` (dark #0E0E0F background, two-row layout: conversation picker row + Talk/End/Chat action row with accent green Talk button), `widget/OptimalXWidget.kt` (AppWidgetProvider — onUpdate wires all four PendingIntents; onEnabled starts WakeWordDetector via app singleton; onDisabled stops it), `widget/WidgetVoiceService.kt` (foreground service with FOREGROUND_SERVICE_MICROPHONE type; holds SpeechToTextEngine + TextToSpeechEngine; reads handoffWord + readAloud from DataStore per session; full walkie-talkie loop writing to Eidos Chats DB; "End" notification action; START_VOICE / END_SESSION / CONTINUE_CONVERSATION intent actions), `widget/ConversationPickerActivity.kt` (transparent-overlay Compose Activity; queries last 50 chat subfolders; shows filtered top 10 with search; tap starts CONTINUE on WidgetVoiceService), `widget/WidgetChatActivity.kt` (full-screen Compose text chat using EidosChatViewModel; same bubble UI as bottom sheet; supports continuing an existing conversation). Added `getRecentConversations` JOIN query to SubfolderDao (crosses Eidos Chats root + __chat_ scoped subfolders, ordered by note.updatedAt DESC). Wired `wakeWordDetector` lazy singleton into OptimalXApplication. Implemented WakeWordDetector restart-loop using SpeechRecognizer (partial result early-exit on wake word match, 3s cooldown after detection, 800ms restart delay on error). Added FOREGROUND_SERVICE and FOREGROUND_SERVICE_MICROPHONE permissions. All 11 phases complete.

---

**2026-04-06 — Debug build plan Batch 2**
Worked Phase 1 high-priority debug items and compiled green. Implemented file-viewer back behavior so pressing Android back from any open file panel now returns directly to the Files panel in one press (`EditorScreen` BackHandler update). Improved Eidos bottom-sheet usability and keyboard behavior by forcing full expansion on open (`skipPartiallyExpanded = true`) and applying `imePadding()` to the chat input row so the input/send controls stay above IME. Reworked provider failure handling in `EidosApiClient` + `ProviderHttp`: missing key now returns explicit Settings guidance; HTTP failures now return provider-specific actionable messages with endpoint + status context (including invalid-key and rate-limit cases) and include retry context after the built-in second attempt.

---

**2026-04-06 — Debug build plan Batch 3**
Marked validated checklist items complete in `DEBUG_BUILD_PLAN.md` for the confirmed-fixing set: `#1`, `#4`, `#6`, `#7`, `#8`, `#9`, `#10`, and `#11`. Continued into `#2` (editor contrast): updated `FormattingToolbar` to use high-contrast `textPrimary` for font-size label and non-active icons, and updated `EditorDropdownMenu` to use themed `containerColor` plus explicit item text colors via `MenuDefaults.itemColors(...)` so labels stay readable on dark/light surfaces. Compile remains green (`:app:compileDebugKotlin`). Remaining: manual dark/light visual validation for Phase 2.2 and then move to Phase 2.3 (`#5` swipe/rotate behavior).

---

**2026-04-06 — Debug build plan Batch 4 (dark-mode follow-up)**
Addressed reported dark-mode visibility regressions (menu text and note editor text). Implemented a root fix in `OptimalXTheme`: app theme now wraps content in Material3 `MaterialTheme(colorScheme=...)` mapped from `OptimalXColors`, so Material3 defaults (e.g., menus, text fields, rich-editor defaults) align with dark/light mode instead of falling back to light tokens. Added explicit rich-editor color mapping in `NotePanel` via `RichTextEditorDefaults.richTextEditorColors(...)` for container/text/cursor/indicator consistency. Also hardened `FolderContextMenu` with explicit dropdown container/item colors. Build remains green (`:app:compileDebugKotlin`). Pending device verification: dark-mode editor canvas/text and dropdown menu readability.

---

**2026-04-06 — Debug build plan Batch 5 (Phase 2.3 implementation)**
Marked Phase 2.2 complete after user confirmation that dark-mode text visibility is fixed. Started and implemented Phase 2.3 (`#5`) with pager-retained behavior: disabled `HorizontalPager` swipe gestures whenever an open file panel is active so image pan/zoom cannot trigger accidental file-to-file navigation. Added image viewer controls for rotate-left, rotate-right, and reset (reset also restores zoom/pan). Initial compile failed due missing `dp` import in `ImageViewerPanel`; fixed and recompiled successfully (`:app:compileDebugKotlin`). Pending manual validation: confirm zoom/pan no longer navigates files and confirm navigation remains predictable via back flow.

---

**2026-04-06 — Debug build plan Batch 6 (Phase 2.3 correction)**
Adjusted Phase 2.3 implementation after UX clarification: users should still swipe left/right between files at default image scale. Reverted the `HorizontalPager` swipe lock in `EditorScreen` and moved conflict handling into `ImageViewerPanel` gesture rules: pan now activates only when image is zoomed (`scale > 1.02`), while base scale allows pager swipe navigation. Added offset clamping based on viewport and zoom so the image cannot drift endlessly off-screen while panning. Kept rotate controls as: rotate-left, rotate-right, and reset-view (restore rotation + zoom + pan). Compile successful (`:app:compileDebugKotlin`). Pending manual validation of this corrected interaction model.

---

**2026-04-06 — Debug build plan Batch 7 (PDF zoom + first-open navigation)**
Implemented two follow-up UX fixes before continuing the broader debug plan. First, added PDF gesture support in `PdfViewerPanel`: pinch-to-zoom, bounded pan while zoomed, and normal vertical scroll at base scale. Second, fixed first-open file navigation in `EditorScreen`: when a user opens a file for the first time, the app now reliably auto-navigates to that file’s panel by deferring pager animation until `openFiles` state includes the new file (avoids page-count timing race). Compile successful (`:app:compileDebugKotlin`). Pending device validation for: (1) PDF zoom/pan feel, and (2) first click on a file immediately opening that panel onscreen.

---

**2026-04-06 — Debug build plan Batch 8 (PDF controls + persistence)**
Implemented three PDF UX additions: explicit zoom controls (`-` and `+`), dedicated rotate button, and reset button in `PdfViewerPanel`. Added persistent per-file PDF view state in `EditorViewModel` (`SharedPreferences`): stores `scale` and `rotation` by file ID and restores them when reopening the same PDF panel. This is intended to reduce repeated re-rotation/re-zoom for frequently viewed files. Compile successful (`:app:compileDebugKotlin`). Pending validation: confirm state restore after closing/reopening panel and optionally after app restart.

---

**2026-04-06 — Debug build plan Batch 9 (Reader Mode PDF overhaul)**
Implemented the approved PDF Reader Mode plan. Replaced the continuous multi-page `LazyColumn` PDF surface with a single-page viewport and explicit page controls (`Prev/Next`) so page navigation remains predictable at any zoom level. Added fixed control row for `Prev`, `Next`, `-`, `+`, `Rotate`, and `Fit`, plus double-tap zoom toggle (`1x` <-> `2x`). Gesture policy updated: pinch always scales current page; pan is enabled only when zoomed; pan is clamped with rotation-aware bounds so content cannot drift off-canvas. Persistence policy switched to rotation-only per file: open always resets to page 1 + fit-centered view, then applies stored rotation. Updated `EditorViewModel` and `EditorScreen` wiring accordingly (`getPdfRotation`/`savePdfRotation`, removed scale persistence path). Compile successful (`:app:compileDebugKotlin`). Pending validation on-device for PDF page flow and reopen behavior.

---

**2026-04-06 — Debug build plan Batch 10 (image rotation persistence)**
Implemented image rotation persistence so images reopen with the last user-selected rotation. Added `EditorViewModel` storage helpers (`getImageRotation`/`saveImageRotation`) backed by shared file-view preferences, and wired `EditorScreen` to pass image rotation state into `ImageViewerPanel` (`initialRotation`, `onRotationCommit`). Updated image rotate-left, rotate-right, and reset actions to commit rotation updates immediately. Compile successful (`:app:compileDebugKotlin`).

---

**2026-04-06 — Debug build plan Batch 11 (Phase 3.1 + 3.2)**
Implemented low-priority enhancement items from `DEBUG_BUILD_PLAN.md`. Phase 3.1 (`#12`): added provider-specific API key help links in Settings for xAI, OpenAI, and Anthropic (clickable inline links under each API key field). Phase 3.2 (`#13`): added layout density modes with persistence across app restarts by introducing `SettingsKeys.FOLDER_LAYOUT` in DataStore and cycling layout modes in folder screens (`List`, `2-col`, `3-col`, `4-col`). Updated `FolderPage` grid rendering to honor selected columns and exposed current mode label in bottom bar. Compile successful (`:app:compileDebugKotlin`). Pending manual validation for link navigation and layout mode persistence after restart.

---

**2026-04-06 — Debug build plan Batch 12 (OpenAI link + layout label cleanup)**
Applied follow-up UX cleanup from validation feedback. Updated OpenAI API help link to `https://platform.openai.com/settings/organization/api-keys` to improve access behavior. Removed the layout mode text label under the grid/list icon in the folder bottom bar while preserving the underlying mode cycle and persistence. Compile successful (`:app:compileDebugKotlin`).

---

**2026-04-06 — Debug build plan Batch 13 (voice handoff + settings scroll)**
Fixed voice handoff semantics so speech does not auto-send on pause/silence. In `VoiceController`, STT now forwards to Eidos only when the handoff phrase is detected (or when no handoff phrase is configured); otherwise it restarts listening. Added a manual fallback in `EidosBottomSheet`: tapping the mic while in LISTENING forces immediate handoff with the current transcript. Updated `WidgetVoiceService` to match the same handoff-word gating behavior (no silence-driven auto-send). Also made `SettingsScreen` content scrollable so voice settings are visible/reachable on smaller viewports. Compile successful (`:app:compileDebugKotlin`).

---

**2026-04-06 — Debug build plan Batch 14 (voice runtime hotfix)**
Resolved runtime crash and phrase-only send behavior reported from logcat. Root cause was `SpeechRecognizer` being started from a non-main binder callback thread after TTS (`SpeechRecognizer should be used only from the application's main thread`). Hardened `SpeechToTextEngine` by routing start/stop/destroy through main-thread handler dispatch. Updated handoff parsing to avoid sending the raw handoff phrase as the user message and to prefer the last meaningful transcript content when available. Mirrored the same safeguard in `WidgetVoiceService`. Compile successful (`:app:compileDebugKotlin`).

---

**2026-04-07 — Widget system v2 (5 buttons, mic states, idle timeout, active conversation)**
Implemented full WIDGET_SYSTEM.md spec. Widget now has 5 controls: Mic, End, Chat, New Chat, Conversations.

Mic button color states: grey (idle, `widget_mic_idle_bg`), green (recording, `widget_mic_recording_bg`), red (responding, `widget_mic_responding_bg`). Service calls `setMicState()` on every state change which pushes updated RemoteViews to all widget instances via AppWidgetManager.

Active conversation persistence: `WidgetPrefs.kt` (SharedPreferences) stores the active subfolder ID across service restarts. Service reads/writes it on session start/end. Chat button intent always includes the current active subfolder ID via `buildWidgetViews()`. WidgetChatActivity falls back to WidgetPrefs if no ID in intent.

New Chat (ACTION_NEW_CHAT): ends current session, clears active subfolder ID, widget returns to idle. Next mic tap starts a fresh conversation.

10-minute idle timeout: `idleTimeoutJob` coroutine resets on each mic tap (both start and send taps). Fires `endSession(clearConversation=false)` after 10 min inactivity — conversation saved and stays accessible in history.

`buildWidgetViews(context, micState, activeSubfolderId)` is a shared top-level function in `OptimalXWidget.kt` — used by both the AppWidgetProvider (onUpdate) and the service (setMicState) so wiring is never duplicated.

ConversationPickerActivity: saves selected subfolder to WidgetPrefs before opening WidgetChatActivity, then calls finish() to close picker.

Files created: `widget_mic_idle_bg.xml`, `widget_mic_recording_bg.xml`, `widget_mic_responding_bg.xml`, `WidgetPrefs.kt`.
Files changed: `widget_layout.xml` (5 buttons, 2 rows), `OptimalXWidget.kt` (buildWidgetViews, WidgetMicState enum), `WidgetVoiceService.kt` (full rewrite), `ConversationPickerActivity.kt` (WidgetPrefs save + finish), `WidgetChatActivity.kt` (WidgetPrefs fallback).

---

**2026-04-07 — WIDGET_SYSTEM.md review and widget rebuild (corrected)**
Reviewed updated WIDGET_SYSTEM.md. Initial rebuild was incorrect (removed Chat button, routed Mic to WidgetChatActivity). Corrected to match actual spec: widget has 4 controls — Mic, End, Chat, Conversations. Mic = background push-to-talk via WidgetVoiceService (no UI opens). Chat = opens WidgetChatActivity for reading/typing. Conversations picker = opens ConversationPickerActivity.

WidgetVoiceService rewritten as push-to-talk toggle: first ACTION_START_VOICE starts STT with continuous accumulation (auto-restarts on OS silence timeout, same model as VoiceController); second ACTION_START_VOICE stops STT and sends the full accumulated transcript to Eidos; reply is spoken via TTS if read-aloud is on, then service ends. ACTION_END_SESSION cancels at any state.

Wake word restored to trigger WidgetVoiceService (same as Mic button), not WidgetChatActivity. EXTRA_AUTO_START_MIC and related LaunchedEffect removed from WidgetChatActivity — in-chat mic is always user-initiated per VOICE_SYSTEM.md. Conversation picker unchanged: 5 default / all on search.

Files changed: `widget_layout.xml` (restored 4 buttons: Mic/End/Chat with weightSum=3), `OptimalXWidget.kt` (Mic→service, Chat→activity, wake word→service), `WidgetVoiceService.kt` (push-to-talk state machine with continuous STT accumulation), `WidgetChatActivity.kt` (removed EXTRA_AUTO_START_MIC and auto-start LaunchedEffect).

---

**2026-04-07 — VOICE_SYSTEM.md review and voice system update**
Reviewed updated VOICE_SYSTEM.md and closed 5 gaps vs the implementation:

1. Mic tap while LISTENING → now sends the message (was: committed text to input field but did not send). Both mic button and send button finalize and send.
2. Send button now enabled while mic is active — commits transcript then sends. Previously disabled while listening.
3. Voice input now appends to existing typed text in the input bar. `startListening(existingText)` pre-loads the accumulated buffer with whatever the user had already typed. Previously replaced/ignored it.
4. Read aloud ON → mic now auto-restarts after TTS finishes. `speakResponse(thenListen, onListenResult)` starts a new listening session when TTS completes instead of returning to IDLE. Previously user had to tap mic manually after every Eidos response.
5. Handoff word completely removed. Removed from `SettingsKeys`, `SettingsDefaults`, `SettingsViewModel`, and `SettingsScreen`. No longer appears in Voice settings.

Also added `EXTRA_HIDE_PARTIAL_TRAILING_SILENCE` and `EXTRA_DICTATION_MODE` hints to `SpeechToTextEngine` to suppress recognizer beep sounds during silent restarts.

Files changed: `VoiceController.kt` (startListening takes existingText, speakResponse takes thenListen/onListenResult), `SpeechToTextEngine.kt` (silence suppression hints), `EidosBottomSheet.kt` (mic+send behavior, TTS auto-restart), `WidgetChatActivity.kt` (same), `SettingsPreferences.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt` (handoff word removed).

---

**2026-04-07 — Continuous mic (mic stays open until user stops it)**
Fixed the STT session ending too quickly on silence. Two-part change: (1) maxed out silence timeout hints in `SpeechToTextEngine` (complete silence: 4s→10s, possibly-complete: 2.5s→6s, minimum length: 12s→60s). (2) `VoiceController` now accumulates text across automatic recognizer restarts — when the OS ends a session due to silence, the controller immediately restarts STT silently and appends new results to the buffer. The mic icon stays lit and the user never sees a restart happen. The mic only stops when the user taps mic again (which commits + sends) or taps send.

Files changed: `SpeechToTextEngine.kt` (silence timeout values), `VoiceController.kt` (auto-restart loop in startListeningInternal, accumulatedText buffer, buildDisplayText helper).

---

**2026-04-08 — Phase 3 addendum 2: input field unlocked + mic/send separation**

Input field always editable: removed `!isListening` from `enabled` in both `EidosBottomSheet` and `WidgetChatActivity`. Field gains focus → `onFocusChanged` pauses mic → user edits → tap mic to resume or tap Send to send. `onValueChange` still guards `!isListening` so typing while live transcript is showing doesn't corrupt the buffer; the focus event pauses first.

Mic button no longer sends: LISTENING → `pauseListening()` only. Send button is the only send path for in-app chat.

Widget home screen: `btn_end` renamed to `btn_send` in `widget_layout.xml` (icon ➤ accent green, label "Send"). `OptimalXWidget` wires `btn_send` → `ACTION_SEND`. `WidgetVoiceService`: `ACTION_END_SESSION` removed and replaced by `ACTION_SEND`; mic tap while LISTENING now calls `endSession()` (cancel, no send); `handleSend()` does the old commit-and-send logic. Notification action changed from "End" to "Send". Notification text updated: "Listening… tap Send to send, Mic to cancel". Build passing.

**Next:** Phase 4 — History + New Chat buttons, ConversationHistorySheet, ConversationListScreen.

---

**2026-04-08 — Phase 3 addendum: edit auto-resend + mic PAUSED state**

Edit message now auto-resends to Eidos. Extracted `callApiAndInsertReply(conversation, userText)` from `sendMessage()` — shared by both send and edit paths. `editMessage()` now: updates content, deletes messages after, reloads trimmed history, calls `callApiAndInsertReply()`, sets `_isSending` guard. `appendErrorMessage()` helper extracted for both paths.

Mic PAUSED state added to `VoiceSessionState`. `VoiceController` gets `pauseListening()` — stops STT, commits accumulated text to input via callback, keeps `activeOnResult` alive; and `resumeListening(currentInputText)` — restarts STT with edited text as new base. Both UIs: `TextField.onFocusChanged` pauses mic when focused while LISTENING. Mic button handles PAUSED → resume. Placeholder shows "Paused — tap mic to resume". Indicator underline stays accent during PAUSED. Send button enabled while PAUSED (text already in input). Build passing.

**Next:** Phase 4 — History + New Chat buttons, ConversationHistorySheet, ConversationListScreen.

---

**2026-04-07 — CHAT_UI_BUILD.md Phase 4 complete**
All Phase 4 code implemented and compiling green.

4.1–4.3 (EidosChatViewModel): `newChat()`, `switchConversation()`, `conversationSummaries` flow, `refreshSummaries()`, scope restore on `applyScope()` — all implemented in Phase 0 session.

4.4–4.6 (EidosBottomSheet): History (`Icons.Default.History`) and New Chat (`Icons.Default.AddComment`) top bar buttons; `ConversationHistorySheet` modal bottom sheet; `showHistorySheet` state wired.

4.7 (WidgetChatActivity): Same History + New Chat buttons wired with `WidgetPrefs.clearActiveConversationId()` / `setActiveConversationId()`.

4.8 (ConversationListScreen): Full-page conversation list with per-scope ViewModel, multi-select delete, empty state. `onOpenConversation` routes to editor.

4.9 (Chats subfolder routing): `FolderDisplayItem.isSystem`, `FolderPage.onSystemFolderClick`, `SubfolderScreen.onSystemSubfolderClick` → `conversationList("parent", parentFolderId, "Chats")`.

4.10 (Eidos Chats routing): `ParentFolderDao.getActiveParentFoldersWithChats()` exposes "Eidos Chats" in main list; `ParentFolderScreen.onSystemFolderClick` → `conversationList("general", 0L, "Eidos Chats")`.

4.11 (Eidos menu scope routing): `EIDOS_SECTION` route now has `{scopeType}/{scopeId}` args; NavGraph passes scope to `EidosSectionScreen`; CHATS tap routes to `conversationList(scopeType, scopeId, ...)` instead of EidosSystemFolderScreen. EditorTopBar gains optional `onEidosSectionClick` button ("Section") so Editor can navigate into the scoped Eidos menu.

Navigation: `CONVERSATION_LIST = "conversation_list/{scopeType}/{scopeId}/{title}"` added to NavHost. All phases complete. No regressions in build (`:app:compileDebugKotlin`).

**Next:** Device validation for Phase 4 flows, then mark remaining validation items done.

---

**2026-04-08 — CHAT_UI_BUILD.md Phase 3 complete**
Bubble action row + long press copy in `EidosBottomSheet` and `WidgetChatActivity`.

Added to `ChatMessageDao`: `getById()`, `updateContent()`, `deleteAfter(conversationId, afterTimestamp)`.

Added to `EidosChatViewModel`: `editMessage(messageId, newText)` — updates message content in DB, hard-deletes all messages in the conversation with `createdAt > edited.createdAt`, reloads trimmed history, resets `previousResponseId`.

Added `onDone: (() -> Unit)?` parameter to `VoiceController.speakResponse()` — fires after TTS completes (used by reread to clear highlight).

Both surfaces now have per-bubble action rows (below timestamp):
- Eidos bubbles: VolumeUp (reread, accent when playing), ContentCopy
- User bubbles: ContentCopy, Edit (opens AlertDialog pre-filled with message text)

`SelectionContainer` wraps message body Text on all bubbles for long-press text selection.

Edit flow: Save → `editMessage()` → DB update + deleteAfter + reload. Cancel → dismiss. Build passing.

**Next:** Phase 4 — History + New Chat top bar buttons, ConversationHistorySheet, ConversationListScreen, directory routing.

---

**2026-04-08 — CHAT_UI_BUILD.md Phase 2 complete**
Scroll-to-bottom button in `EidosBottomSheet`. Wrapped the `LazyColumn` in a `Box`; added `isAtBottom` derived state from `listState.layoutInfo` (true when last visible item index == totalItemsCount - 1). Auto-scroll on new message now only fires when `isAtBottom`. When scrolled up, an `IconButton` with `KeyboardArrowDown` appears bottom-right of the message list, tinted `accent`, with `surface2` background + `border` circle. Tap animates scroll to last item via `rememberCoroutineScope`. Build passing. Pending device validation.

**Next:** Phase 3 — reread button on Eidos bubbles.

---

**2026-04-07 — CHAT_UI_BUILD.md Phase 0 + Phase 1 complete**
Implemented the full data layer migration from note-based chat storage to dedicated Room entities.

Phase 0 — Data layer:
- Created `data/model/Conversation.kt` and `data/model/ChatMessage.kt` Room entities (no @Index annotations)
- Created `data/dao/ConversationDao.kt` (getRecentGeneral, getRecentByParentFolder, getRecentBySubfolder, search variants, delete methods) and `data/dao/ChatMessageDao.kt`
- Updated `AppDatabase` to v3 with MIGRATION_2_3: creates both tables, inserts "Chats" system subfolder for every existing user parent folder. **Critical note:** migration does NOT create any indices — entity classes have no @Index and Room schema validation requires them to match exactly.
- Rewrote `EidosChatViewModel` with `ConversationScope` sealed class (General/ParentFolder/Subfolder), `applyScope()` restores last conversation for scope from DB, lazy `ensureConversation()` on first send, `loadConversation()`, `newChat()`, `switchConversation()`, `refreshSummaries()`. Conversation persists on scope change — only `newChat()` resets it.
- Updated `AppNavigation` scope wiring: Main→General, SubfolderScreen→ParentFolder, EditorScreen→Subfolder
- Updated `FolderRepository.createParentFolder()` to atomically insert "Chats" system subfolder
- Rewrote `WidgetVoiceService` to use `ConversationDao`/`ChatMessageDao`, `ensureConversation()` creates Conversation row
- Updated `WidgetPrefs` key `active_subfolder_id`→`active_conversation_id`, all accessors renamed
- Updated `OptimalXWidget`, `WidgetChatActivity`, `ConversationPickerActivity` to use `EXTRA_CONVERSATION_ID`
- Updated `EidosBottomSheet` to use `items(messages, key = { it.id })`

Phase 1 — Timestamp font: changed from `DmSansFamily` → `DmMonoFamily` in both `EidosBottomSheet` and `WidgetChatActivity` bubble columns.

Runtime crash fix: MIGRATION_2_3 originally contained `CREATE INDEX` statements that Room's schema validator rejected (entity declares `indices=[]`, migration created indices). Removed both `CREATE INDEX` blocks. Build now passes.

**Next:** Phase 2 (scroll-to-bottom button in EidosBottomSheet), then Phase 3 (reread button), then Phase 4 (top bar History + New Chat buttons, ConversationHistorySheet, ConversationListScreen, directory routing). First validate Phase 0/1 on device.

---

**2026-04-06 — Debug build plan Batch 13 (voice handoff + settings scroll)**
Removed the walkie-talkie auto-restart loop from the voice system. The old loop caused ERROR_RECOGNIZER_BUSY (11) and ERROR_NO_MATCH (7) errors to cascade into an infinite restart cycle that the user could not stop without force-closing the app.

New behavior:
- Mic button (IDLE): starts one STT session. When STT ends (naturally or user taps mic again), transcript goes into the input field. No auto-send, no auto-restart.
- Mic button (LISTENING): stops STT immediately and commits whatever partial transcript exists into the input field.
- Mic button (SPEAKING): cancels TTS.
- Send button: user sends manually as before.
- TTS (read aloud): still fires automatically when a new ASSISTANT message arrives and readAloud is on. After TTS finishes, returns to IDLE — no loop.
- Widget Talk button (WidgetVoiceService): one round trip only — listen → send to Eidos → optionally speak reply → end foreground service.

Files changed: `VoiceController.kt` (complete rewrite, removed PROCESSING state and all loop/handoff-word machinery), `EidosBottomSheet.kt` (updated mic handler and TTS trigger), `WidgetChatActivity.kt` (same), `WidgetVoiceService.kt` (removed loop, now single round trip).

---

**2026-04-20 — Voice system v3: continuous local STT pipeline**
Implemented the continuous local voice system plan. Replaced the `VoiceController` / `WidgetVoiceService` direct dependency on `SpeechToTextEngine` with a new shared pipeline under `voice/pipeline/`.

New files:
- `VoicePipelineConfig.kt` — sample rate, frame size, VAD thresholds, segment limits, UI update throttle
- `AudioCaptureSource.kt` — `AudioRecord` 16 kHz mono PCM capture on a `THREAD_PRIORITY_URGENT_AUDIO` thread, emits per-frame RMS
- `VadSegmenter.kt` — frame-level energy VAD with start/end hysteresis, adaptive noise floor, max-segment guard, `onSpeechStart` / `onSpeechEnd(reason)` / `onLevel` callbacks
- `TranscriptAssembler.kt` — persistent draft buffer, `setBase` / `setPartial` / `commitFinal` / `flushPartialAsFinal` / `takeAndReset` semantics
- `OnDeviceTranscriber.kt` — pluggable transcriber interface with `ownsMicrophone` flag + `TranscriberListener`
- `RecognizerOnDeviceTranscriber.kt` — v1 concrete backend wrapping the stability-improved `SpeechToTextEngine` (Android on-device recognizer)
- `ContinuousSpeechToTextEngine.kt` — orchestrator: owns the assembler, runs capture+VAD for PCM-consuming backends, exposes `onDraftChanged` / `onSessionEnded(reason, finalText)` / `onTransientError` / `onAudioLevel`
- `VoiceRuntime.kt` — central `transcriberFactory`; swapping backends (future Whisper adapter) is one assignment and affects in-app + widget simultaneously

Integration:
- `VoiceController` now constructs its engine via `VoiceRuntime.newEngine(app)` and observes the engine's draft / session-ended callbacks. Push-to-talk, pause/resume, commit/send, and read-aloud auto-relisten semantics preserved.
- `WidgetVoiceService` uses the same factory. Send path waits for `onSessionEnded(USER_STOP, finalText)` so late partials aren't dropped; mic-tap-while-listening still cancels without committing.

Tests (`app/src/test/java/com/example/optimalx/voice/pipeline/`):
- `TranscriptAssemblerTest` — 6 cases covering partial/final merge, base seed, empty-final guard, snapshot vs takeAndReset
- `VadSegmenterTest` — 5 cases covering start gate, silence hangover, max-segment force-close, reset, and below-threshold rejection

All 11 unit tests pass; `:app:assembleDebug` green.

Docs updated: `VOICE_SYSTEM.md` (added "Implementation Map (v3 shipped)" section with class table, backend families, threading rules), `WIDGET_SYSTEM.md` (added "Shipped wiring" under STT Engine Notes), `CHAT_UI.md` (expanded STT Engine Integration v3 with controller callback wiring).

---

**2026-04-20 — Voice system v3.1: Whisper backend wiring (flavor A — segment-based)**
Added the full Whisper-class local STT path on top of the v3 pipeline. Goal: eliminate the Android recognizer's session cut-in/cut-out by running a continuous AudioRecord stream into a local Whisper model, with VAD-bounded segments fed to the model on a background worker.

New files:
- `voice/pipeline/WhisperEngine.kt` — `WhisperEngine` interface (`transcribe(FloatArray) → String`, `release()`) + process-wide `WhisperEngineRegistry` singleton
- `voice/pipeline/WhisperLocalTranscriber.kt` — `OnDeviceTranscriber` with `ownsMicrophone = false`. Buffers PCM per VAD segment with a 300 ms pre-roll ring so first phonemes aren't clipped. Serializes inference through a single background worker thread; bounded queue with drop-oldest backpressure. Uses internal `FloatRingBuffer` for pre-roll.

Modified:
- `voice/pipeline/VoiceRuntime.kt` — replaced single-factory with backend-aware selector. `Backend` enum (`AUTO` / `LOCAL_WHISPER` / `ANDROID_RECOGNIZER`). AUTO picks Whisper if registered, else the Android recognizer. `activeBackendLabel()` for diagnostics.
- `OptimalXApplication.kt` — added `initWhisperEngine()` init hook + `onLowMemory`/`onTerminate` registry release. Ships as a no-op; concrete engine registration happens when a whisper.cpp or TFLite backend is added (documented in `assets/models/README.txt` and `VOICE_SYSTEM.md`).
- `data/preferences/SettingsPreferences.kt` — new `STT_BACKEND` key, default `"auto"`.
- `ui/settings/SettingsViewModel.kt` — exposes `sttBackend` StateFlow, writes preference back to `VoiceRuntime.backendPreference` via collector.
- `ui/settings/SettingsScreen.kt` — Voice section gains STT backend picker (Auto / Local Whisper / Android recognizer) using existing `ProviderOption` radio row pattern.

Tests (`voice/pipeline/WhisperLocalTranscriberTest.kt`):
- `FloatRingBufferTest` — 3 cases covering fill, wraparound, clear.
- `WhisperLocalTranscriberTest` — 2 cases covering engine call with normalized PCM + pre-roll inclusion, and minimum-segment drop behavior.

All 16 voice pipeline unit tests pass; `:app:assembleDebug` green. The Android recognizer backend remains default/fallback until a concrete `WhisperEngine` is registered.

What's needed to flip to Whisper for real:
1. Drop a Whisper-class model (e.g. distil-whisper-small.en-q8_0, ~165 MB) into `app/src/main/assets/models/`.
2. Either vendor the whisper.cpp Android AAR into `app/libs/` or implement a TFLite Whisper backend.
3. Implement `WhisperEngine` wrapping the chosen runtime.
4. Call `WhisperEngineRegistry.register(engine)` from `OptimalXApplication.initWhisperEngine()`.

See `VOICE_SYSTEM.md § Integrating a Whisper backend` and `app/src/main/assets/models/README.txt` for the full recipe.

---

**2026-04-20 — Whisper model one-tap installer in Settings**
Implemented an in-app Whisper model installer so users do not need to manually place model files.

Added `voice/pipeline/WhisperModelInstaller.kt`:
- `WhisperModelCatalog.SmallEnglishQ51` (recommended `ggml-small.en-q5_1.bin`)
- `download(...)` with progress callback, temp `.part` file, atomic replace, and cleanup on cancel/failure
- `installedFile(...)`, `isInstalled(...)`, `removeInstalled(...)`

Updated `SettingsViewModel`:
- Added `WhisperInstallUiState` state flow (installed/downloading/progress/error)
- Added actions: `installRecommendedWhisperModel()`, `cancelWhisperModelDownload()`, `removeInstalledWhisperModel()`, `refreshWhisperInstallState()`
- Installer writes to private app path `filesDir/models/`

Updated `SettingsScreen` Voice/STT section:
- Added install status text (not installed / downloading with bytes / installed / error)
- Added one-tap action link: "Install recommended model now"
- Added cancel/remove actions depending on current install state
- Kept direct external model links for advanced users

Docs/comments updated for storage path alignment:
- `VOICE_SYSTEM.md`: Whisper model can be loaded from `filesDir/models/` (installer path) or `assets/models/` (manual/dev path)
- `app/src/main/assets/models/README.txt`: clarifies one-tap installer path vs manual assets path
- `OptimalXApplication.kt`: example comment now references `filesDir/models/ggml-small.en-q5_1.bin`

Validation:
- `./gradlew :app:compileDebugKotlin` passes.

---

**2026-04-20 — Installer default switched to 252 MB model**
Adjusted the one-tap Whisper installer to match user preference for higher quality (`ggml-small.en-q8_0.bin`, ~252 MB).

Changes:
- `WhisperModelCatalog` now includes `SmallEnglishQ80` and uses exact upstream file size from HuggingFace API.
- `SettingsViewModel` installer target switched from q5_1 to q8_0 (status label now reflects higher-quality model).
- Settings action text updated to "Install higher quality model now (small.en-q8_0, 252 MB)".

Critical bug fix:
- Corrected expected file size for q5_1 in installer metadata (was slightly too high), which could trigger false "download incomplete" failures near 100%.

Validation:
- `./gradlew :app:compileDebugKotlin` passes.

---

**2026-04-20 — Whisper runtime hook wired (executes local model)**
Implemented the final runtime bridge so installed Whisper models are actually used by the voice pipeline.

Changes:
- Added concrete `WhisperAndroidEngine` (`voice/pipeline/WhisperAndroidEngine.kt`) backed by `mx.valdora:whisper-android`. It converts each VAD segment float PCM buffer into a temporary 16 kHz mono WAV and calls Whisper inference.
- Added `WhisperRuntimeSync` (`voice/pipeline/WhisperRuntimeSync.kt`) to detect installed model files in `filesDir/models/`, load/register the engine, and clear registry when no model exists.
- Replaced `OptimalXApplication.initWhisperEngine()` no-op with `WhisperRuntimeSync.sync(this)` so app startup now attempts real Whisper registration automatically.
- Updated `SettingsViewModel` to call `WhisperRuntimeSync.sync(ctx)` after model install/remove/refresh so backend availability updates without requiring process restart.
- Added dependency wiring in Gradle (`mx.valdora:whisper-android:1.0.0`).

Validation:
- `./gradlew :app:compileDebugKotlin` passes.

---

**2026-04-20 — Whisper send/stop flush fix**
Investigated missing/late transcript commits with local Whisper and found a stop-path race: user stop/send could finalize the session before queued Whisper segments were flushed, dropping recent speech text.

Changes:
- Added `OnDeviceTranscriber.flushOnStop()` optional hook.
- Implemented synchronous flush in `WhisperLocalTranscriber.flushOnStop()` to transcribe any in-progress and queued segments before teardown.
- Updated `ContinuousSpeechToTextEngine.stop()` to call `flushOnStop()`, commit any returned text into `TranscriptAssembler`, then finalize the session.
- Kept cancel semantics drop-safe (`WhisperLocalTranscriber.cancel()` now clears queue explicitly).

Validation:
- `./gradlew :app:compileDebugKotlin` passes.

---

**2026-04-20 — Chat UI no-text fix (VoiceController stop handling + VAD sensitivity)**
Follow-up debug for "Whisper active but no transcript in chat UI." Root cause was controller/session stop ordering: `VoiceController.stopListeningAndCommit()` captured draft text *before* `ContinuousSpeechToTextEngine.stop()` finished stop-time flush, so flushed Whisper text never reached the input callback.

Changes:
- `VoiceController` now waits for `onSessionEnded(USER_STOP, finalText)` and routes by explicit `StopIntent` (`COMMIT` / `PAUSE`) before delivering text.
- `pauseListening()` now also uses the finalized `finalText` from the engine stop callback (preserves edit flow and avoids stale draft capture).
- `VAD_SPEECH_RMS_THRESHOLD` reduced from `0.035f` to `0.015f` to avoid missed speech starts on lower mic gain devices.
- `WhisperLocalTranscriber` now keeps a bounded fallback capture ring and uses it in `flushOnStop()` when no VAD segments were queued, so stop/send can still produce text even if VAD boundaries were missed.

Validation:
- `./gradlew :app:compileDebugKotlin` passes.

---

**2026-04-20 — Native crash guard for second Whisper run**
User reported app force-close on second Whisper attempt with SIGSEGV inside `libwhisper_android.so` (`quantize_row_q8_0`). Most likely cause is concurrent native context usage during stop/flush/worker overlap.

Changes:
- Hardened `WhisperAndroidEngine` with strict synchronization around `writeWav + whisper.transcribe(...)`.
- Added `closed` guard (`AtomicBoolean`) so release/transcribe cannot race.
- `release()` now executes under the same lock and becomes idempotent.

Validation:
- `./gradlew :app:compileDebugKotlin` passes.

---

**2026-04-22 — Whisper model selection dropdown in Settings**
Added a persisted Whisper model picker so users with multiple locally downloaded GGML files can choose which one the runtime uses.

Changes:
- Added `SettingsKeys.WHISPER_MODEL_FILE_NAME` in DataStore.
- Extended `WhisperModelInstaller` with `installedModels(...)` discovery and `removeInstalledByFileName(...)` for selected-model removal.
- Extended `WhisperModelCatalog` with `BaseEnglishQ80`, `allModels`, and `labelForFileName(...)` for friendly display labels.
- Updated `WhisperRuntimeSync.sync(...)` to accept an optional preferred model file name and load that first when available.
- Updated `SettingsViewModel` Whisper state to expose installed model options + selected model, persist selection, sync runtime with selected model, and auto-fallback selection when chosen model is removed.
- Updated `SettingsScreen` Whisper section with a dropdown that appears when multiple installed models exist and saves selection immediately.
- Updated `OptimalXApplication.initWhisperEngine()` to read the saved preferred model at startup and sync Whisper runtime accordingly.

Validation:
- `./gradlew :app:compileDebugKotlin` passes.

---

**2026-04-22 — Whisper links now install in-app for all 3 models**
Updated Whisper install UX so all three model rows in Settings perform direct in-app installs (to `filesDir/models`) instead of opening external download links.

Changes:
- `SettingsScreen`: replaced the three `↓ ...` external URL actions with direct calls to `viewModel.installWhisperModel(...)` for:
  - `small.en-q5_1`
  - `small.en-q8_0`
  - `base.en-q8_0`
- Removed the redundant single "Install higher quality model now" link since model choice is now explicit in the three install actions.
- `SettingsViewModel`: added generic `installWhisperModel(spec: WhisperModelSpec)` and made `installRecommendedWhisperModel()` delegate to it.

Impact:
- Users can install multiple models without leaving the app.
- Once 2+ models are installed, the existing dropdown ("Active local model") appears and allows selecting the runtime model.

Validation:
- `./gradlew :app:compileDebugKotlin` passes.
