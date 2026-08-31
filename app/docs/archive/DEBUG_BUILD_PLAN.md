# DEBUG_BUILD_PLAN.md

## Purpose
Convert `DEBUG_TRACKER.md` into an executable, step-by-step debugging plan with clear order, owner checkpoints, and validation criteria.

## How To Use This Plan
- Execute steps in order.
- Do not start a lower-priority phase if a blocker in an earlier phase is still open.
- Mark tasks `[x]` only after the validation checklist for that task passes.
- After each session, append outcomes to `CURSOR_JOURNAL.md` with: what was fixed, what failed, what remains.

## Execution Log
- 2026-04-06 (Batch 1):
  - Implemented code changes for `#1` (three-dot menu consistency): settings/menu access added on Subfolder and Editor pages.
  - Implemented code changes for `#10` (settings save/apply feedback): inline save confirmations added for theme/provider/voice/API settings.
  - Implemented code changes for `#11` (API key visibility): keys now masked by default with global show/hide control.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: device/manual validation steps in Phase 1/2 checklists before marking `[x]`.
- 2026-04-06 (Batch 2):
  - Implemented code changes for `#4` (file viewer back navigation): Android back now returns from any open file panel directly to Files in one press.
  - Implemented code changes for `#6/#7/#8` (chat input visibility/insets): Eidos sheet now opens fully expanded and applies IME padding on the input row to keep controls visible above keyboard.
  - Implemented code changes for `#9` (provider unavailable messaging): replaced generic fallback with provider-specific, actionable errors including endpoint + HTTP status summaries; added explicit missing-key guidance and preserved retry-once behavior with retry context in the returned message.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: device/manual validation for back behavior, chat keyboard behavior, and provider-response scenarios (valid key, invalid key, missing key).
- 2026-04-06 (Batch 3):
  - Marked validated items complete based on device verification: Phase 1 (`#4/#6/#7/#8/#9/#10/#11`) and Phase 2.1 (`#1`).
  - Started Phase 2.2 (`#2` editor contrast): updated editor toolbar labels/icons to high-contrast text color and forced editor overflow menu container/item colors to theme surfaces/text for dark/light consistency.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: manual visual validation for Phase 2.2 before marking its validation checklist complete.
- 2026-04-06 (Batch 4):
  - Follow-up fix for dark-mode visibility regression in editor/menu surfaces.
  - Root cause addressed: Material3 components were not bound to app dark/light palette. `OptimalXTheme` now also provides a mapped Material3 `colorScheme`, so default Material3 surfaces/icons/menus follow app theme.
  - Hardened editor panel visibility by explicitly passing `RichTextEditorDefaults.richTextEditorColors(...)` with app colors for container/text/cursor/indicators.
  - Hardened folder context menu in dark mode by setting dropdown container/item colors explicitly.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: confirm on-device that editor text and menu text are readable in dark mode.
- 2026-04-06 (Batch 5):
  - Phase 2.2 validated complete based on device verification: editor/menu text now readable in dark mode.
  - Started Phase 2.3 (`#5`) with pager-retained approach.
  - Added image rotate controls (left/right) and reset control in image viewer panel.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: manual validation for zoom/pan + navigation predictability in file/image flows.
- 2026-04-06 (Batch 6):
  - Corrected Phase 2.3 behavior based on QA feedback: restored left/right pager swiping and changed image gesture policy so pan is enabled only when zoomed in.
  - Added bounded image panning so the canvas cannot drift infinitely off-screen.
  - Kept rotate left/right + reset controls.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: manual validation that base-scale swipe navigates files and zoomed-in pan works without accidental panel flips.
- 2026-04-06 (Batch 7):
  - Added PDF zoom/pan behavior to `PdfViewerPanel` (pinch zoom, bounded pan, base-scale vertical scroll).
  - Fixed file-open navigation race in `EditorScreen`: first click now auto-navigates to the newly opened panel by deferring pager navigation until `openFiles` state includes the file.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: manual validation for PDF zoom usability and first-click file panel auto-navigation.
- 2026-04-06 (Batch 8):
  - Added explicit PDF controls: zoom out (`-`), zoom in (`+`), rotate, and reset.
  - Added persistent per-file PDF view state (zoom + rotation) via `EditorViewModel` shared preferences; reopening the same PDF restores prior zoom/rotation.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: manual validation that PDF view state persists across closing/reopening panel (and app restart if desired).
- 2026-04-06 (Batch 9):
  - Implemented PDF Reader Mode stabilization:
    - Replaced continuous PDF list with single-page viewport + explicit `Prev/Next`.
    - Gestures apply to current page only (pinch zoom, pan only when zoomed, bounded pan).
    - Added controls row: `Prev`, `Next`, `-`, `+`, `Rotate`, and `Fit`.
    - Added double-tap zoom toggle (`1x` <-> `2x`).
  - Updated PDF persistence policy to rotation-only:
    - On open, always reset to page 1 and fit-centered view.
    - Rotation restored per file and re-saved on rotate.
    - Removed persisted PDF scale usage from open flow.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: manual validation for page navigation reliability, fit behavior after rotate, and rotation persistence across reopen/restart.
- 2026-04-06 (Batch 10):
  - Added image rotation persistence per file (parity with PDF rotation persistence).
  - Wired `EditorScreen` -> `ImageViewerPanel` with `initialRotation` + `onRotationCommit`.
  - Added `EditorViewModel` helpers for image rotation (`getImageRotation` / `saveImageRotation`) using shared file-view preferences.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: manual validation that rotated images reopen with last-used rotation.
- 2026-04-06 (Batch 11):
  - Implemented Phase 3.1 (`#12`) API key help links in Settings (xAI/OpenAI/Anthropic "Get API key" links).
  - Implemented Phase 3.2 (`#13`) layout density options with persistence:
    - Added list + 2-column + 3-column + 4-column layout cycle.
    - Persisted layout mode in DataStore (`SettingsKeys.FOLDER_LAYOUT`).
    - Applied mode to both parent and subfolder folder screens.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: manual validation for provider help links and layout mode cycling/persistence.
- 2026-04-06 (Batch 12):
  - Updated OpenAI API help link target to organization API keys page for better access reliability.
  - Removed layout mode label text under the grid/list icon while preserving mode cycling and persistence behavior.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: quick validation that OpenAI help link opens correctly.
- 2026-04-06 (Batch 13):
  - Voice handoff behavior corrected:
    - `VoiceController` now sends to Eidos only when handoff word is detected (or handoff word is blank).
    - Silence/final-result without handoff now restarts listening instead of auto-sending.
    - Added manual mic fallback in chat sheet: tapping mic while listening forces handoff of current transcript.
  - Widget voice flow aligned to handoff-word gating (no silence auto-send).
  - Settings page made scrollable so Voice settings are always reachable on smaller screens.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: manual validation of voice handoff timing and Settings scroll behavior.
- 2026-04-06 (Batch 14):
  - Voice stability hotfix from runtime logs:
    - Fixed `SpeechRecognizer` thread-safety by marshalling STT start/stop/destroy to main thread in `SpeechToTextEngine`.
    - Prevented sending bare handoff phrase as user message (`"over and out"` now treated as trigger only).
    - Added fallback to last meaningful transcript when handoff phrase is detected without captured content.
  - Applied same handoff-text safeguards to `WidgetVoiceService`.
  - Build status: `:app:compileDebugKotlin` successful.
  - Pending: manual validation that voice no longer dings/restarts unexpectedly and no duplicate/phrase-only sends occur.

---

## Phase 0 — Baseline Repro & Instrumentation

### 0.1 Reproduce Each Reported Issue
- [ ] Reproduce all issues from tracker items `#1, #2, #4, #5, #6, #7, #8, #9, #10, #11, #12, #13`
- [ ] Capture one short repro note per issue (screen, action, expected, actual)
- [ ] Capture one screenshot/video per high-priority issue (`#4, #6, #7, #9, #10, #11`)

### 0.2 Add Temporary Debug Logging (remove after fix)
- [ ] Add logs for provider selection + key presence checks (no key value logging)
- [ ] Add logs for chat sheet insets/keyboard state
- [ ] Add logs for editor/file-panel back navigation state

Validation:
- [ ] All high-priority issues have deterministic repro steps

---

## Phase 1 — High Priority Functional Fixes

### 1.1 File Viewer Back Navigation (`#4`)
- [x] Decide navigation behavior with developer:
  - Option A: back always exits open file panel to Files page "developer choice"
  
- [x] Implement chosen behavior
- [x] Ensure Android back from any open file/image panel returns to Files page in one back press

Validation:
- [x] Open 3 files in sequence, press back once from last file, land on Files page
- [x] Repeat from PDF and image panels

### 1.2 Chat Input Visibility/Insets (`#6`, `#7`, `#8`)
- [x] Fix initial bottom-sheet layout so input is immediately visible without manual scroll
- [x] Add proper IME handling so input stays above keyboard
- [x] Correct bottom inset/padding so input is not below system UI

Validation:
- [x] Open chat from main, subfolder, editor screens: input visible immediately
- [x] Focus input with keyboard open: send button remains visible
- [x] Test on gesture nav + 3-button nav devices/emulators

### 1.3 AI Provider Unavailable (`#9`)
- [x] Verify active provider + API key read path end-to-end
- [x] Verify provider-specific request shape and model compatibility
- [x] Replace generic error with actionable message (provider, endpoint, status code summary)
- [x] Add one retry with user-facing reason when failure persists

Validation:
- [x] With valid xAI key, send message succeeds
- [x] With invalid key, error is explicit and actionable
- [x] With missing key, error points user to Settings + provider field

### 1.4 Settings Save Feedback (`#10`)
- [x] Add auto-save confirmation feedback (toast/snackbar/inline status)
- [x] Show explicit feedback for provider change and API key update

Validation:
- [x] User gets visible save confirmation for every settings action

### 1.5 API Key Masking (`#11`)
- [x] Mask all API key inputs by default
- [x] Add single global reveal/hide toggle (or per-field if chosen)
- [x] Ensure logs never print full key values

Validation:
- [x] Keys are hidden by default
- [x] Reveal toggle works and re-hides correctly
- [x] App restart preserves key values and masking state

---

## Phase 2 — Medium Priority UX/Consistency

### 2.1 Three-Dot Menu Placement (`#1`)
- [x] Add consistent top-right menu placement on:
  - Main page
  - Subfolder page
  - Note/editor page
- [x] Keep menu action set context-appropriate

Validation:
- [x] Menu appears in same visual location across all target screens

### 2.2 Editor Text Contrast (`#2`)
- [x] Audit editor + toolbar colors under dark theme
- [x] Fix unreadable white-on-light or low-contrast combinations
- [x] Verify light theme remains readable

Validation:
- [x] WCAG-style readable contrast on both themes
- [x] Toolbar labels/icons readable in dark and light

### 2.3 File/Image Swipe Conflict (`#5`)
- [x] Decide with developer whether file panels remain pager-based
- [x] If pager retained: prevent image pan gestures from triggering panel navigation
- [x] Add optional rotate control for image viewer (and PDF if desired by product)

Validation:
- [x] Zoom/pan images does not accidentally navigate files
- [x] Horizontal navigation remains intentional and predictable

---

## Phase 3 — Low Priority Enhancements

### 3.1 API Key Help Links (`#12`)
- [x] Add help affordance for each provider key field
- [x] Provide provider-specific "Get API key" links

Validation:
- [ ] Links open expected provider docs/pages

### 3.2 Layout Density Options (`#13`)
- [x] Add additional layout option(s), e.g. 3-column or 4-column grid
- [x] Persist layout choice

Validation:
- [x] User can switch among available density options
- [x] Selection persists after restart

---

## Release Gate Checklist
- [x] All Phase 1 tasks completed and validated
- [ ] No regression in folder/subfolder creation, Eidos logs, and file imports
- [ ] Editor content persistence verified across app restart
- [ ] Eidos chat transcript persistence verified (including Eidos Chats)
- [ ] Manual smoke test complete on dark and light theme

---

## Issue-to-Plan Mapping
- `#1` → Phase 2.1
- `#2` → Phase 2.2
- `#4` → Phase 1.1
- `#5` → Phase 2.3
- `#6`/`#7`/`#8` → Phase 1.2
- `#9` → Phase 1.3
- `#10` → Phase 1.4
- `#11` → Phase 1.5
- `#12` → Phase 3.1
- `#13` → Phase 3.2
