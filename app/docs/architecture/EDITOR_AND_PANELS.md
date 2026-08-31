# EDITOR_AND_PANELS.md

## Purpose

This file defines the editor environment in OptimalX v2.

This covers the note panel, formatting toolbar, dropdown menu, panel navigation system, and Eidos access from within the editor.

Coding agents should use this file to understand everything that happens inside the editor view.

---

## Entry Behavior

- Tapping a subfolder opens directly into the editor view
- The default panel is always the Note Panel
- **Default mode is View** (read-only markdown preview) — tap **Edit** in the toolbar or the bottom-right **Edit** button to type
- No intermediate screens
- No extra taps

---

## Panel System

The editor view is a single screen with multiple panels accessed by swiping horizontally.

Panels do not create new screens. They are swipeable content areas within the same view.

### Panel order (v2)

| Position | Panel | Notes |
|---|---|---|
| Center | Note | Default, always opens here |
| Right of Note | Files | Swipe left from Note to access |
| Right of Files | Image Studio | Generate/browse images for this subfolder |
| Right of Image | Web | Subfolder-scoped in-app web panel |
| Right of Web | Custom panels | Panel Workshop assignments (if any) |
| Right of custom panels | Open file panels | Each opened file adds a panel to the right |

### Panel navigation rules
- Swipe left → move to the next panel to the right
- Swipe right → move back to the previous panel
- Android back button from any open file panel returns to Files
- Android back button from Web returns to Image Studio
- Android back button from Image Studio returns to Files
- Android back button from Files returns to Note
- Android back button from the Note Panel returns to the Subfolder Page
- Panels are horizontal only — no vertical swipe navigation inside the editor

### Future panels
The swipe system is designed to accept new panels without restructuring the layout.
Future panels (calculator, contractor math tools, checklist, etc.) slot into the swipe order.

### Custom panels (Panel Workshop)

Users add completed workshop panels via **Add Panel** on a subfolder. Each assignment adds a swipe page rendered by `WorkshopPreviewPanel` (`panelContextType = custom_panel`).

**User data persistence:** One saved state blob per host subfolder (`scopeKey = subfolder:{hostSubfolderId}`), separate from Panel Gallery’s `global` scope for the same panel project. The panel’s **script.js** must implement `panelGetState` and restore hooks — Kotlin does not auto-save form fields or scores. See [PANEL_PLATFORM.md](PANEL_PLATFORM.md#platform-capability-panel-state-persistence) and [PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md](../implementation/PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md).

---

## Note Panel

The Note Panel is the core working space of the app.

### Storage vs editing surface

| Layer | Format | Notes |
|---|---|---|
| **Room `notes.content`** | Markdown | Canonical bytes for Eidos tools, semantic search line ranges, and backup |
| **WYSIWYG editor** | Rich text (visual) | User edits with the markdown-persisting toolbar subset — no `#` symbols required |
| **View mode** | Rich text (read-only) | Same `RichTextNoteEditor` as Edit mode (`readOnly=true`) — headings, lists, bold match Edit |
| **Edit mode** | Rich text (WYSIWYG) | User can type; saves only after `userHasEdited` |

**Canonical rule:** `notes.content` markdown bytes do not change on open or while in View mode. Re-encoding through `NoteContentCodec` happens only when the user edits in Edit mode and saves. View mode loads the richeditor buffer for display only (no edit session until the user switches to Edit).

Load/save boundary: [`NoteContentCodec`](../../src/main/java/com/example/optimalx/ui/components/NoteContentCodec.kt) (`loadIntoRichText` / `persistFromRichText`). Legacy HTML rows migrate to markdown lazily on first open (background DB write only).

Shared panel chrome: [`RichTextNotePanel.kt`](../../src/main/java/com/example/optimalx/ui/editor/components/RichTextNotePanel.kt) (`RichTextNotePanelSupport`, `RichTextNoteEditor`, `MarkdownNoteViewPanel`).

### Eidos co-editing

- Eidos writes via `write_note` / `note_replace_string` (see [DIFF_REVIEW.md](DIFF_REVIEW.md) when the note body is non-empty).
- The open editor reloads the View preview when Room `updatedAt` advances and the user has not started an edit session (`userHasEdited` guard in `NotePanel`).
- Before Eidos sends from the subfolder editor, pending user edits flush to Room (`flushNoteToDbForEidos`). If working copy is **dirty vs HEAD**, edits are **auto-committed** before Eidos tools run.
- While editing, changes persist to the working copy on **leave / back flush only** (no debounced save). Use **Commit** in the top bar to advance HEAD. See [NOTE_PERSISTENCE_MODEL.md](NOTE_PERSISTENCE_MODEL.md).

### Layout
- Full screen text editor
- Formatting toolbar visible at the top of the editor
- Dropdown menu accessible from the toolbar

### Formatting Toolbar

The toolbar sits above the editing area and contains markdown-persisting formatting actions (see [NOTE_EDITOR_HARDENING_PLAN.md](../implementation/NOTE_EDITOR_HARDENING_PLAN.md) for the supported subset).

| Action | Description |
|---|---|
| Bold | Toggles bold on selected text |
| Italic | Toggles italic on selected text |
| Strikethrough | Toggles strikethrough on selected text |
| Heading | Cycles line style: Body → Title (`#`) → Section (`##`) → Subsection (`###`) |
| Link | Adds `[text](url)` — prompts for URL |
| Inline code | Toggles `` `code` `` on selected text |
| Bullet list | Formats selected or new lines as a bulleted list |
| Numbered list | Formats selected or new lines as a numbered list |
| Dictate | Voice-to-text into the note (Edit mode only) |
| Dropdown menu | Opens the extended options menu |

**No toolbar Undo / Redo.** Those icons previously walked a save-time content deque, not keystroke undo — misleading UX. While typing, use the Android text field’s native undo where available.

### Version history and Commit (top bar)

Persistence uses **working copy** (`notes.content`) vs **HEAD** (latest checkpoint). See [NOTE_PERSISTENCE_MODEL.md](NOTE_PERSISTENCE_MODEL.md).

| Control | Behavior |
|---------|----------|
| **Commit** | Visible when working copy ≠ HEAD. Appends a user checkpoint without Diff Review. |
| **Review N** | Eidos pending proposals only → `DiffReviewScreen` Accept / Reject. |
| **History** | `ContentHistorySheet` — checkpoint timeline (user commits, auto-commits, Eidos accepts, restores). Restore replaces the editor buffer and appends a “Restored to seq N” checkpoint. |

Leave-flush alone updates the working copy but does **not** create a History seq. See [DIFF_REVIEW.md](DIFF_REVIEW.md).

### Dropdown Menu

The dropdown contains less frequently used actions and system level options.

| Action | Description |
|---|---|
| Additional formatting | Strikethrough moved to toolbar in Phase 2; dropdown holds system actions |
| Edit / View mode toggle | Switches between edit mode (user can type) and view mode (read only for user). Eidos can still edit in view mode. Stays in whatever mode the user sets — does not auto switch. |
| AI lock toggle | Locks or unlocks Eidos access to this note. When locked, Eidos can read the note for context but cannot write or modify it. When unlocked, Eidos has full access. User controls this manually. |
| Export as PDF | Saves a **PDF** file — markdown → HTML → PDF; opens in any PDF viewer |
| Export as HTML | Saves a **rendered HTML** file (`.html`) |
| Share note | Shares **rendered HTML** via Android share sheet (`text/html`); plain-text fallback for apps that ignore HTML |

**Mobile office formats:** Word (`.docx`) and OpenDocument (`.odt`) are **desktop-only** (Pandoc). Mobile ships PDF + HTML in Phase 4b.

| Format | Storage | Export |
|--------|---------|--------|
| Markdown | Canonical (`notes.content`) | Desktop only (`.md` menu item) |
| HTML | Export-only | Mobile + desktop |
| PDF | Export-only | Mobile + desktop |
| DOCX / ODT | Export-only | Desktop when Pandoc is installed |

The dropdown is accessed from the toolbar. It does not clutter the main toolbar.

### Edit and AI Lock Logic

| State | User can edit | Eidos can read | Eidos can edit |
|---|---|---|---|
| Edit mode, AI unlocked | Yes | Yes | Yes |
| View mode, AI unlocked | No | Yes | Yes |
| Edit mode, AI locked | Yes | Yes | No |
| View mode, AI locked | No | Yes | No |

- View mode only restricts the user from typing — it does not affect Eidos
- AI lock only restricts Eidos from writing — it does not affect the user
- Eidos can always read a note regardless of lock status
- Both toggles are independent and stay in whatever state the user sets them

---

## Files Panel

The Files Panel shows all files attached to the current subfolder.

### Layout
- List of attached files
- Each file shows its name and type (PDF, Word, image)
- Import button to attach new files

### Supported file types
- PDF
- Word document (.docx)
- Image (jpg, png)

### Opening a file
- Tapping a file in the Files Panel opens it as a new panel to the right
- Each opened file becomes its own swipeable panel
- Multiple files can be open at the same time
- The user swipes between open file panels freely
- Closing a file panel removes it from the swipe stack and returns to the Files Panel

### File import
- User taps the import button in the Files Panel
- Device file picker opens
- Selected file is copied into app storage and a FileReference is created in the database
- Full detail on file storage is defined in FILES_AND_MEDIA.md

---

## Image Studio Panel

The Image Studio panel sits between Files and Web. It generates cloud images (xAI Grok Imagine) into the current subfolder and shows a subfolder-scoped gallery.

### Layout
- File name, prompt, optional negative prompt
- Model tier (Draft / Quality) and aspect ratio chips
- Generate / Cancel with progress
- Preview + Share / Open Files after success
- Gallery filtered to this subfolder (All / Generated / Imported)

### Persistence
- Form fields persist in `panel_state` with `scopeKey = image_studio` and host = subfolder id
- Generated images are saved as `file_references` rows with `metadata_json` (see `app/docs/image_studio/`)

### Requirements
- xAI API key must be configured in Settings → AI before Generate is enabled

---

## Web Panel

The Web panel is available in the editor only and is scoped to the current subfolder context.

### Layout
- Embedded WebView with address/search input
- Navigation controls (back, forward, refresh)
- History/search UI filtered to the current subfolder scope

### Scope behavior
- Each subfolder has its **own browser** — canonical scope key `editor:subfolder:{subfolderId}`
- Bookmarks, recent pages, recent searches, and last URL restore are stored per subfolder and must not appear in other subfolders or the widget browser
- Widget web browsing uses a separate scope: `widget:quick_web` (see `WEB_SYSTEM.md` for the full isolation contract)
- Eidos web chat in the editor is keyed by subfolder + search/page thread in Room; switching subfolders must not reuse another subfolder's active web search thread

---

## Eidos Access — Bottom Sheet

Eidos is accessible from the editor view via the Eidos button in the top bar.

### Behavior
- Tap the Eidos button → a chat panel slides up from the bottom of the screen
- The bottom sheet covers approximately 60-70% of the screen
- The note remains partially visible above the sheet for context
- To dismiss: swipe the bottom sheet downward
- No back button required to close Eidos — swipe down returns the user directly to the editor
- The bottom sheet does not conflict with horizontal panel swipes

### Why bottom sheet
- Keeps the note visible while using Eidos
- No full screen takeover
- Swipe down to dismiss is faster than hitting back
- Standard Android pattern — familiar to users and straightforward to build
