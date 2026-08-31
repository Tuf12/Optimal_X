# OPERATING_MODES.md

## Purpose

Defines the starting board state for the AgentByte loop based on where the user is in the app.
The app already feeds Eidos its current location. Operating modes define what King sees when it scans that location and what the opening move looks like.

King always opens. King scans the board. Then the loop classifies and routes.

**Note:** Piece and mode names here describe **AgentByte routing policy** (`ChessPiece`, operating modes), not dependence on the optional **`chess_taxonomy`** tool (which only mirrors vocabulary for the model when registered).

---

## Mode 1 — General

**When:** Widget, main parent folder page, no specific folder context.

**King scans:**
- Check for active or recent general conversation
- Check general tag-hint index for any relevant context
- Assess user message — what kind of interaction is this?

**Routes to:**
- Bishop — emotional, personal, talking about their day
- Rook — logical question, needs research or retrieval
- Queen — simple direct request, path is clear
- Knight — stuck on something, needs a workaround
- Pawn supports any of the above if more context is needed

**Tools available:** memory tools, search tools, conversation tools, log tools

---

## Mode 2 — Parent Folder

**When:** User is browsing inside a specific parent folder.

**King scans:**
- Load folder tag-hints — understand what kind of space this is
- List subfolders — what is available here
- Check for active parent folder conversation
- Assess user message in context of this folder

**Routes to:**
- Rook — user referencing specific subfolder content, logical retrieval
- Queen — user wants to create or organize something, path is clear
- Bishop — emotional or personal context tied to this folder
- Knight — user stuck, needs a workaround or creative path
- Pawn expands tool set if primary piece needs more reach

**Tools available:** folder tools, search tools, memory tools, note tools

---

## Mode 3 — Subfolder

**When:** User is inside a specific subfolder — note editor, files panel, any panel.

**King scans:**
- Load subfolder memory cache — what does Eidos already know about this space
- Load note content via Pawn if memory cache is insufficient
- Load tag-hints for this subfolder
- Check reasoning note — was there an interrupted loop here
- Assess user message in context of this subfolder

**Routes to:**
- Queen — clear action, content available, just execute
- Rook — logical retrieval or structured task
- Bishop — emotional or preference driven context
- Knight — something blocked or broken
- Pawn expands tool set throughout as needed

**Tools available:** full tool set

---

## Mode 4 — Panel Workshop

**When:** User is inside the Panel Workshop building or editing a sub-panel.

**King scans:**
- Identify current build phase — docs, scaffold, design, logic, features, or edit
- Load current doc files and code files
- Check reasoning note for interrupted loop state
- Assess user message in context of current phase

**Routes to:**
- Rook leads doc phase — logical, structured, one correct path through the docs
- Queen leads code phase — decisive execution against a clear spec
- Knight activates on any error automatically
- King triggers on file overwrites and token milestones

**Tools available:** file tools, note tools, WebView error feedback, search tools

---

## King Is Always Present

King does not get replaced by any mode. King monitors throughout every loop iteration regardless of mode.
King warns when anything approaches dangerous territory — destructive actions, token milestones, irreversible changes.
King opens every session. King watches every move.

---

## Situation Classification Per Mode

After King scans the board the loop classifies the situation and selects the primary piece.

| Situation | Primary Piece | Setup If Needed |
|---|---|---|
| Emotional or personal | Bishop | Pawn loads more context |
| Logical retrieval or research | Rook | Pawn loads more tools |
| Clear direct action | Queen | None needed |
| Blocked or broken | Knight repositions | Then Rook or Bishop captures |
| Dangerous or irreversible | King confirms | Then original piece resumes |