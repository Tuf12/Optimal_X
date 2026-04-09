# CLAUDE.md

review .md files in this order as necessary
1. README.md
2. UI_PRINCIPLES.md
3. THEME.md
   CHAT_UI.MD
4. APP_STRUCTURE.md
   CONVERSATION_DIRECTORY.md
5. DATA_MODEL.md
6. EDITOR_AND_PANELS.md
7. FILES_AND_MEDIA.md
8. EIDOS_AGENT.md
9. TOOL_FUNCTIONS.md
10. JOURNAL_SYSTEM.md
11. SEARCH_AND_RETRIEVAL.md
12. API.md
13. WIDGET_SYSTEM.md
14. VOICE_SYSTEM.md



## First Action Every Session
Before writing any code, read these two files:
1. CURSOR_JOURNAL.md — your memory from previous sessions
2. currently working on different odds and ends
These two files tell you where you are and what to do next.

## First Session Only
CHAT_UI_PLAN.md should break the build into logical phases with specific tasks listed under each phase.
Each task should be marked [ ] incomplete. Mark tasks [x] as you complete them.

## How to Work
- Complete all tasks in a phase before moving to the next
- At the end of every session update CURSOR_JOURNAL.md with what you did, what is next, and any decisions or problems worth noting
- At the start of every session read CURSOR_JOURNAL.md before touching anything

## When You Are Unsure
If something is not covered in the architecture files, stop and ask before proceeding.
Do not invent architecture or add features not defined in the docs.