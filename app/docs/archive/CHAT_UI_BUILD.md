# CHAT_UI_BUILD.md

## Purpose
Step-by-step plan to bring the in-app Eidos bottom sheet and widget chat into full alignment with CHAT_UI.md.

## How To Use This Plan
- Execute phases in order — each phase produces a working, buildable deliverable before the next begins.
- Mark tasks `[x]` when device (or emulator) confirms correct behavior.
- After each session append an entry to CURSOR_JOURNAL.md: what was done, what is next, any decisions.
- Reference files: CHAT_UI.md, DATA_MODEL.md, VOICE_SYSTEM.md, THEME.md, conversation_directory.md.

---

## Execution Log

**2026-04-07 — Phase 0 complete + Phase 1 complete**
Implemented the full data layer migration (Phase 0) and timestamp font fix (Phase 1).

Phase 0: Created `Conversation` and `ChatMessage` Room entities and their DAOs. Bumped AppDatabase to v3 with MIGRATION_2_3 (creates both tables, inserts "Chats" system subfolder for all existing user parent folders). Rewrote `EidosChatViewModel` to use new schema with `ConversationScope` sealed class (General/ParentFolder/Subfolder), lazy conversation creation on first send, `loadConversation()`, `newChat()`, `switchConversation()`, `refreshSummaries()`, and conversation persistence (scope change no longer resets the active conversation). Updated `WidgetVoiceService`, `WidgetChatActivity`, `ConversationPickerActivity`, `WidgetPrefs`, and `OptimalXWidget` to use `conversationId` instead of `subfolderId` throughout. Updated `FolderRepository.createParentFolder()` to atomically insert "Chats" system subfolder. Updated `AppNavigation` scope wiring (General/ParentFolder/Subfolder per page). Build passed. Runtime crash on device: MIGRATION_2_3 contained `CREATE INDEX` statements that the entity classes do not declare via `@Index`, causing Room schema validation to fail (`indices=[]` expected, indices found). Fixed by removing both `CREATE INDEX` blocks from MIGRATION_2_3. Build confirmed successful post-fix.

Phase 1: Timestamp `Text` changed from `DmSansFamily` → `DmMonoFamily` in both `EidosBottomSheet.kt` and `WidgetChatActivity.kt`.

Pending device validation: conversation persistence across app close/reopen, new chat flow, and widget conversation round trip.

---

## Phase 0 — Data Layer: Conversation and ChatMessage

The current implementation stores Eidos conversations as formatted text inside `Note` records in system `Subfolder`s. DATA_MODEL.md defines dedicated `Conversation` and `ChatMessage` Room entities. This phase migrates to that proper schema.

This phase must be complete before any UI work begins — all later phases depend on it.

### 0.1 Auto-create "Chats" system subfolder per parent folder
**Files:** `data/db/DatabaseSeed.kt`, wherever new parent folders are inserted (ViewModel or DAO layer)

- [x] When a user creates a new `ParentFolder`, immediately insert a `Subfolder` with `name = "Chats"`, `isSystemSubfolder = true`, linked to that parent folder's ID
- [x] Add a one-time migration to insert the "Chats" system subfolder into any existing user parent folders that don't have one yet — run this as part of DB migration 2→3
- [x] The "Chats" subfolder must not appear in the note editor — tapping it routes to the Conversation List screen, not the editor

### 0.2 Room entities
**New files:** `data/model/Conversation.kt`, `data/model/ChatMessage.kt`

- [x] Create `Conversation` entity:
  ```kotlin
  @Entity(tableName = "conversations")
  data class Conversation(
      @PrimaryKey(autoGenerate = true) val id: Long = 0,
      val scopeType: String,          // "general" | "parent" | "subfolder"
      val parentFolderId: Long? = null,
      val subfolderId: Long? = null,
      val title: String,              // "yyyy-MM-dd — h:mm a"
      val createdAt: Long = System.currentTimeMillis(),
      val updatedAt: Long = System.currentTimeMillis(),
  )
  ```
- [x] Create `ChatMessage` entity:
  ```kotlin
  @Entity(tableName = "chat_messages")
  data class ChatMessage(
      @PrimaryKey(autoGenerate = true) val id: Long = 0,
      val conversationId: Long,
      val role: String,               // "user" | "eidos"
      val content: String,
      val createdAt: Long = System.currentTimeMillis(),
  )
  ```

### 0.2 DAOs
**New files:** `data/dao/ConversationDao.kt`, `data/dao/ChatMessageDao.kt`

- [x] `ConversationDao`:
  - `insert(conversation: Conversation): Long`
  - `update(conversation: Conversation)`
  - `getById(id: Long): Conversation?`
  - `getRecentByScope(scopeType: String, limit: Int): List<Conversation>` — ordered by `updatedAt DESC`, `deletedAt` not applicable (conversations are never trashed)
  - `getRecentByParentFolder(parentFolderId: Long, limit: Int): List<Conversation>`
  - `getRecentBySubfolder(subfolderId: Long, limit: Int): List<Conversation>`
  - `searchByScope(scopeType: String, query: String): List<Conversation>` — `WHERE title LIKE '%query%'`
  - `delete(conversation: Conversation)` — hard delete per DATA_MODEL.md (no trash for conversations)
  - `deleteAllByParentFolder(parentFolderId: Long)` — cascade when parent folder deleted
  - `deleteAllBySubfolder(subfolderId: Long)` — cascade when subfolder deleted

- [x] `ChatMessageDao`:
  - `insert(message: ChatMessage): Long`
  - `getAllByConversation(conversationId: Long): List<ChatMessage>` — ordered by `createdAt ASC`
  - `deleteAllByConversation(conversationId: Long)` — cascade when conversation deleted

### 0.3 Register in AppDatabase
**File:** `data/db/AppDatabase.kt`

- [x] Add `Conversation::class` and `ChatMessage::class` to `@Database` entities list
- [x] Bump `version` from 2 → 3
- [x] Add `MIGRATION_2_3`:
  ```sql
  CREATE TABLE IF NOT EXISTS conversations (
      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
      scopeType TEXT NOT NULL,
      parentFolderId INTEGER,
      subfolderId INTEGER,
      title TEXT NOT NULL,
      createdAt INTEGER NOT NULL,
      updatedAt INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS chat_messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
      conversationId INTEGER NOT NULL,
      role TEXT NOT NULL,
      content TEXT NOT NULL,
      createdAt INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS index_conversations_scopeType_updatedAt ON conversations(scopeType, updatedAt);
  CREATE INDEX IF NOT EXISTS index_chat_messages_conversationId ON chat_messages(conversationId);
  ```
  > **Note:** The `CREATE INDEX` statements above were removed from the actual migration — the entity classes have no `@Index` annotations so Room's schema validator rejected them. Migration creates tables only.
- [x] Expose `conversationDao()` and `chatMessageDao()` abstract functions

### 0.4 Rewrite EidosChatViewModel to use new schema
**File:** `ui/eidos/EidosChatViewModel.kt`

- [x] Remove all `Subfolder`/`Note`-based conversation storage logic:
  - `ensureConversationNote()`, `appendLineToNote()`, `parseNoteToMessages()`, `getOrCreateScopedChatSubfolder()`, `createRootConversationSubfolder()`
- [x] Add `private var activeConversationId: Long? = null`
- [x] Add `private suspend fun ensureConversation(): Conversation` — creates a new `Conversation` row if `activeConversationId` is null, otherwise loads existing. Title = `conversationNameFormatter.format(Instant.now())`
- [x] Rewrite `sendMessage()`:
  - `ensureConversation()` to get/create the conversation
  - `chatMessageDao().insert(ChatMessage(conversationId, role = "user", content = text))`
  - Call API
  - `chatMessageDao().insert(ChatMessage(conversationId, role = "eidos", content = reply))`
  - `conversationDao().update(conversation.copy(updatedAt = now))`
- [x] Rewrite `setScope(subfolderId)` → replaced with `setGeneralScope()`, `setParentFolderScope()`, `setSubfolderScope()` each calling `applyScope()` which restores last conversation for that scope
- [x] Add `fun loadConversation(conversationId: Long)` — sets `activeConversationId`, loads all `ChatMessage`s, maps to `EidosUiMessage`, emits to `_messages`
- [x] Remove `previousResponseId` (not applicable with per-message storage — the API call still uses it in-memory within a session; re-derive from in-memory history if needed)
- [x] Expose conversation history for the browser:
  - `val conversationSummaries: StateFlow<List<ConversationSummary>>` (see Phase 4 for data class)
  - Updated when `activeConversationId` changes or a new message is sent

### 0.5 Update WidgetVoiceService
**File:** `widget/WidgetVoiceService.kt`

- [x] Replace `appendToNote()` / `ensureConversationNote()` with `ConversationDao` + `ChatMessageDao` calls
- [x] Store `activeConversationId: Long?` instead of `currentNoteId: Long?`
- [x] `WidgetPrefs` stores an active `conversationId` (Long) instead of `subfolderId` — update key name and all callers

### 0.6 Update WidgetChatActivity
**File:** `widget/WidgetChatActivity.kt`

- [x] Update intent extra to pass `conversationId` instead of `subfolderId`
- [x] Call `chatViewModel.loadConversation(conversationId)` in `LaunchedEffect`

### 0.7 Update ConversationPickerActivity
**File:** `widget/ConversationPickerActivity.kt`

- [x] Query `ConversationDao` instead of `SubfolderDao`
- [x] Pass `conversationId` in intent to `WidgetChatActivity`

Validation:
- [x] Build passes `compileDebugKotlin` with migration in place
- [ ] Send a message — it persists in the new `chat_messages` table *(pending device validation)*
- [ ] Close and reopen the app — conversation still loads correctly *(pending device validation)*

---

## Phase 1 — Minor Fix: Timestamp Font

**Files:** `EidosBottomSheet.kt`, `WidgetChatActivity.kt`

- [x] Change timestamp `Text` font from `DmSansFamily` to `DmMonoFamily` in `EidosBottomSheet` bubble column
- [x] Same change in `WidgetChatActivity` bubble column

Validation:
- [ ] Timestamps render in DM Mono on both surfaces *(pending device validation)*

---

## Phase 2 — Scroll-to-Bottom Button

**File:** `EidosBottomSheet.kt`

Spec: If the user has scrolled up and a new message arrives, a scroll-to-bottom button appears.

- [x] Derive `isAtBottom` from `listState.layoutInfo` — true when the last item is fully visible
- [x] Show overlaid `IconButton` via `Box` at the bottom-right of the message list when `!isAtBottom && messages.isNotEmpty()`
- [x] Icon: `Icons.Default.KeyboardArrowDown`, tint `accent`
- [x] On tap: `listState.animateScrollToItem(messages.lastIndex)`
- [x] Auto-scroll on new message only when `isAtBottom` — skip auto-scroll if user has scrolled up

Validation:
- [ ] Scroll up, send a message — button appears *(pending device validation)*
- [ ] Tap button — jumps to bottom, button disappears *(pending device validation)*
- [ ] At bottom when new message arrives — auto-scrolls, button does not appear *(pending device validation)*

---

## Phase 3 — Bubble Actions + Long Press Copy

**Files:** `EidosBottomSheet.kt`, `WidgetChatActivity.kt`, `EidosChatViewModel.kt`

Spec: Every message bubble has a row of action icons below it. Eidos bubbles: Reread, Copy. User bubbles: Copy, Edit. Long press any bubble text to select and copy text.

---

### 3.1 ViewModel state for reread and edit

- [x] Add `private val _rereadMessageId = MutableStateFlow<Long?>(null)` to `EidosChatViewModel` — `ChatMessage.id` of the actively playing reread, or null
- [x] Expose as `val rereadMessageId: StateFlow<Long?>`
- [x] Add `fun setRereadMessageId(id: Long?)` — called by UI when reread starts or stops
- [x] Add `fun editMessage(messageId: Long, newText: String)` — updates the user `ChatMessage` content in DB, deletes all `ChatMessage`s in the conversation with `createdAt` after the edited message's `createdAt`, clears `_messages` to the trimmed history, sets `activeConversationId` to the same conversation (ready for next send)

---

### 3.2 Reread button — Eidos bubbles only

**Files:** `EidosBottomSheet.kt`, `WidgetChatActivity.kt`

- [x] Collect `rereadMessageId` from viewModel
- [x] Below the timestamp of each **Eidos** bubble, add an `IconButton`:
  - Icon: `Icons.Default.VolumeUp`
  - Tint: `colors.accent` when `message.id == rereadMessageId`, else `colors.textDim`
  - Icon size: 18.dp; button size: 28.dp
- [x] On tap: stop/start via `voiceController.speakResponse(onDone = { setRereadMessageId(null) })`
- [x] Eidos auto-TTS (`readAloud`) does not set `rereadMessageId`
- [x] Added `onDone` callback parameter to `VoiceController.speakResponse()` to support reread completion

---

### 3.3 Copy button — all bubbles

- [x] Next to the Reread button (Eidos) or as the first action icon (User), add a Copy `IconButton`:
  - Icon: `Icons.Default.ContentCopy`
  - Tint: `colors.textDim`
  - Icon size: 18.dp; button size: 28.dp
- [x] On tap: copy `message.text` to system clipboard via `LocalClipboardManager`
- [x] Apply to both Eidos and User bubbles in `EidosBottomSheet` and `WidgetChatActivity`

---

### 3.4 Edit Message button — User bubbles only

- [x] Next to Copy on each **User** bubble, add an Edit `IconButton`:
  - Icon: `Icons.Default.Edit`
  - Tint: `colors.textDim`
  - Icon size: 18.dp; button size: 28.dp
- [x] On tap: `AlertDialog` pre-filled with message text; Save calls `viewModel.editMessage(message.id, newText)`
- [x] On confirm: ViewModel updates content, deletes all messages after (`createdAt >`), reloads trimmed history

---

### 3.5 Long press text copy

- [x] Wrap each bubble's `Text` composable (the message body) in a `SelectionContainer` so the user can long-press to enter text-selection mode and copy a substring
- [x] Apply to both Eidos and User bubbles in `EidosBottomSheet` and `WidgetChatActivity`

---

Validation:
- [ ] Tap speaker on Eidos bubble → accent highlight, TTS plays *(pending device validation)*
- [ ] Tap different bubble while playing → first stops, second starts *(pending device validation)*
- [ ] Tap same bubble while playing → TTS stops, icon returns to dim *(pending device validation)*
- [ ] TTS ends naturally → icon returns to dim *(pending device validation)*
- [ ] Tap Copy on any bubble → text copied, paste confirms correct content *(pending device validation)*
- [ ] Tap Edit on User bubble → dialog pre-filled; confirm → messages after removed; next send appends new reply *(pending device validation)*
- [ ] Long press bubble text → system text selection handles appear; can copy a portion *(pending device validation)*

---

## Phase 4 — Top Bar Buttons + Conversation History + Directory Access

**Files:** `EidosBottomSheet.kt`, `WidgetChatActivity.kt`, `EidosChatViewModel.kt`, `EidosSystemScreens.kt`, new `ConversationHistorySheet.kt`, new `ConversationListScreen.kt`

Spec: Two buttons in the top bar — History and New Chat. Conversations persist until user explicitly acts. Conversation history is also accessible via Chat folders in the directory and via the Eidos menu (hamburger) on every page.

### 4.1 Conversation persistence rule
- [x] On `setScope()` change: do NOT reset `activeConversationId` — instead restore the last active conversation for this scope from the DB (query most recent by scope)
- [x] `activeConversationId = null` only when the user explicitly taps New Chat
- [x] On first open with no prior conversation: create one only on first send (lazy creation)

### 4.2 ConversationSummary data class
Add near top of `EidosChatViewModel.kt`:
```kotlin
data class ConversationSummary(
    val id: Long,
    val title: String,
    val snippet: String,   // first message content, max 60 chars
    val isActive: Boolean,
)
```

### 4.3 ViewModel: load and expose summaries
- [x] Add `private val _conversationSummaries = MutableStateFlow<List<ConversationSummary>>(emptyList())`
- [x] Expose as `val conversationSummaries: StateFlow<List<ConversationSummary>>`
- [x] Add `private suspend fun refreshSummaries()` — queries appropriate `ConversationDao` method based on current scope; snippet = first `ChatMessage.content.take(60)`
- [x] Call `refreshSummaries()` after: scope change, new conversation created, conversation switched
- [x] Add `fun newChat()` — sets `activeConversationId = null`, clears `_messages`, calls `refreshSummaries()`
- [x] Add `fun switchConversation(id: Long)` — calls `loadConversation(id)`, calls `refreshSummaries()`

### 4.4 Top bar buttons in EidosBottomSheet
- [x] Add **History** `IconButton` to the top bar Row (right side, before Read Aloud toggle):
  - Icon: `Icons.Default.History` or `Icons.Default.AccessTime`
  - Tint: `colors.textMid`
  - On tap: `showHistorySheet = true`
- [x] Add **New Chat** `IconButton` to the top bar Row:
  - Icon: `Icons.Default.AddComment` or similar
  - Tint: `colors.textMid`
  - On tap: `viewModel.newChat()`

### 4.5 ConversationHistorySheet composable
Create `ui/eidos/ConversationHistorySheet.kt`:

- [x] `@Composable fun ConversationHistorySheet(summaries, onSelect, onDismiss)`
- [x] `ModalBottomSheet` with `skipPartiallyExpanded = true`
- [x] Layout: title "Conversations", search `TextField`, `LazyColumn` of rows
- [x] Each row: title (DM Mono, 12sp), snippet (DM Sans, 12sp, `textDim`); active row has `accent` left border
- [x] Tap row: `onSelect(id)` then `onDismiss()`
- [x] Empty state: "No conversations yet." in `textDim`

### 4.5A Directory-first conversation navigation (implemented model)
This section replaces the earlier draft version of 4.5A.

- [x] Top scope chips are `Recent`, `General`, `Parent` (subfolder is no longer a top-level chip)
- [x] Default open path is `Recent` with last 5 conversations across scopes, most recent first
- [x] `Parent` provides explicit second-step navigation:
  - [x] `Parent Chats` (shows most recent 5 for selected parent)
  - [x] `Subfolders` (shows parent selector, then subfolder selector, then most recent 5 for selected subfolder)
- [x] Context label/breadcrumb shown (`Chats / ...`)
- [x] Conversation identity is preserved by `conversationId` across widget/app entry points
- [x] Scope does not change unless user explicitly uses `Move Here`
- [x] `Move Here` uses viewed page scope (not loaded conversation scope)

### 4.6 Wire history sheet in EidosBottomSheet
- [x] Collect `conversationSummaries` from viewModel
- [x] Show `ConversationHistorySheet` when `showHistorySheet == true`
- [x] `onSelect`: `viewModel.switchConversation(id); showHistorySheet = false`
- [x] `onDismiss`: `showHistorySheet = false`

### 4.7 Same top bar buttons in WidgetChatActivity
- [x] Add History and New Chat `IconButton`s to the top bar Row
- [x] New Chat: `chatViewModel.newChat()` + `WidgetPrefs.clearActiveConversationId(this)`
- [x] History: show `ConversationHistorySheet`; on select: `chatViewModel.switchConversation(id)` + `WidgetPrefs.setActiveConversationId(this, id)`

### 4.8 ConversationListScreen composable
Create `ui/eidos/ConversationListScreen.kt` — full-page list opened from a Chat folder or the Eidos menu Chats option.

- [x] `@Composable fun ConversationListScreen(scopeType, parentFolderId, subfolderId, title, onBack, onOpenConversation)`
- [x] Layout: top bar (Back, title), `LazyColumn` of rows
- [x] Each row: title (DM Mono, 12sp), snippet (DM Sans, 12sp, `textDim`), last updated (DM Mono, 11sp, `textDim`)
- [x] Tap row: `onOpenConversation(conversationId)` → opens chat UI with `loadConversation(id)` called
- [x] Long-press or checkbox multi-select → "Delete selected" (hard delete)
- [x] Empty state: "No conversations yet."
- [x] ViewModel: queries `ConversationDao` using the appropriate scope method

### 4.9 Route "Chats" system subfolder to ConversationListScreen
**File:** wherever subfolder tap is handled (navigation graph / folder screen)

- [x] When the user taps a subfolder where `name == "Chats"` and `isSystemSubfolder == true`, route to `ConversationListScreen(scopeType = "parent", parentFolderId = subfolder.parentFolderId)` — do NOT open the note editor
- [x] Use the parent folder name as the screen title

### 4.10 Route "Eidos Chats" system parent folder to ConversationListScreen
**File:** main folder list screen

- [x] When the user taps the "Eidos Chats" parent folder, route to `ConversationListScreen(scopeType = "general")` — do NOT open a subfolder list

### 4.11 Eidos menu — scope-aware Chat routing
**File:** `EidosSystemScreens.kt` / `EidosSectionScreen`

Currently "Chats" in the Eidos menu routes to `EidosSystemFolderScreen` (old note-based list). Replace with scope-aware routing:

- [x] Pass current page context into the Eidos menu: scope args (scopeType/scopeId) threaded via AppNavigation → EidosSectionScreen
- [x] When user taps "Chats":
  - Main folder list → `ConversationListScreen(scopeType = "general")`
  - Parent folder page → `ConversationListScreen(scopeType = "parent", parentFolderId = id)`
  - Editor / panel → `ConversationListScreen(scopeType = "subfolder", subfolderId = id)`
- [x] Journal and Log entries remain unchanged
- [x] Confirm the Eidos menu (hamburger) button exists in the header on every page — EditorTopBar now has `onEidosSectionClick`

Validation:
- [ ] Reopen chat → same conversation resumes
- [ ] Tap New Chat → messages clear; next send creates a new conversation
- [ ] Tap History button → sheet shows 5 most recent at current scope
- [ ] Tap conversation in sheet → messages switch; active entry highlighted
- [ ] Search in history sheet → results filter correctly
- [x] History/list uses `Recent`, `General`, `Parent` with parent-first then subfolder navigation
- [ ] Widget conversation opened in-app continues same `conversationId` (no split copy/thread)
- [ ] In `General`, most recent widget conversation appears at/near top and opens directly
- [ ] Tap "Eidos Chats" folder in main list → ConversationListScreen with general conversations
- [ ] Tap "Chats" subfolder in a parent folder → ConversationListScreen with parent-scoped conversations
- [ ] Eidos menu → Chats on main page → general conversations
- [ ] Eidos menu → Chats on parent folder page → parent-scoped conversations
- [ ] Eidos menu → Chats on editor page → subfolder-scoped conversations
- [ ] Tap conversation row in ConversationListScreen → opens chat UI with that conversation
- [ ] Delete from list → conversation removed permanently

---

## Release Gate

- [ ] All Phase 0–4 tasks complete
- [ ] DB migration runs without crash on fresh install and upgrade from v2
- [ ] No regression in message sending, TTS, or voice input
- [ ] Both surfaces verified: EidosBottomSheet (in-app) and WidgetChatActivity (widget)
- [ ] Widget Conversations and New Chat buttons still work correctly after Phase 0 data model change
- [ ] Dark theme visual check on all new UI elements
- [ ] Build passes `:app:compileDebugKotlin`
