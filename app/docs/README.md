README.md
primary use for an app overview for sharing with others, not a necessary file for the build, as the other .md files provide the actual details.

## Docs Directory Map

- `architecture/`: app structure, UI architecture, data model, and core design docs ([PANEL_GALLERY.md](architecture/PANEL_GALLERY.md) + [DUMPEDIT.md](architecture/DUMPEDIT.md) — pinned row, gallery, scratch buffer; [PANEL_PLATFORM.md](architecture/PANEL_PLATFORM.md) — WebView host contract + `panel_state`; [PANEL_WORKSHOP.md](architecture/PANEL_WORKSHOP.md) + [WORKSHOP_MODES.md](architecture/WORKSHOP_MODES.md) — v2 phased workshop UX; [DIFF_REVIEW.md](architecture/DIFF_REVIEW.md) — change review, checkpoints, undo; [NOTE_PERSISTENCE_MODEL.md](architecture/NOTE_PERSISTENCE_MODEL.md) — git-like working-copy vs version-history model for notes/DumpEdit; [EIDOS_NAVIGATION.md](architecture/EIDOS_NAVIGATION.md) — clickable navigation from Eidos chat to notes, folders, workshop)
- `image_studio/`: Image Studio built-in panel — cloud API generation in v1 (provider abstraction for later backends), gallery, Eidos scope ([README.md](image_studio/README.md) — design spec; [implementation-plan.md](image_studio/implementation-plan.md) — phased Android build; desktop contract in [electron/image-studio.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio.md))
- `implementation/`: build plans and checklists ([EIDOS_PREFETCH_RAG_PLAN.md](implementation/EIDOS_PREFETCH_RAG_PLAN.md) — proactive semantic prefetch before hop 1 — shipped 2026-07; [CHAT_VISION_ATTACH_PLAN.md](implementation/CHAT_VISION_ATTACH_PLAN.md) — Eidos composer image attach (desktop shipped; mobile Phases 0–2 shipped, Phase 3 polish optional); [EIDOS_NAVIGATION_IMPLEMENTATION_PLAN.md](implementation/EIDOS_NAVIGATION_IMPLEMENTATION_PLAN.md) — Eidos chat navigation chips + `optimalx://` links (planned); [NOTE_EDITOR_HARDENING_PLAN.md](implementation/NOTE_EDITOR_HARDENING_PLAN.md) — note/DumpEdit editor: save, toolbar subset, history, export, desktop Quill; [EIDOS_API_TRACE_IMPLEMENTATION_PLAN.md](implementation/EIDOS_API_TRACE_IMPLEMENTATION_PLAN.md) — API Trace capture + UI; [PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md](implementation/PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md) — pinned row, gallery, DumpEdit, pins, panel state storage — shipped; [PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md](implementation/PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md) — panel save/restore contract, Eidos enforcement, scaffold debounce, UX reset; [PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md](implementation/PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md) — Eidos Chat on Panel Gallery + Panel Runner; [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](implementation/PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) — workshop Auto-Continue system, build run vs edit profiles, token/prompt fixes (active); [PANEL_WORKSHOP_RECOVERY_PLAN.md](implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md) — workshop recovery checklist; [archive/PANEL_WORKSHOP_OVERHAUL_PLAN.md](archive/PANEL_WORKSHOP_OVERHAUL_PLAN.md) — v2 rollout checklist (archived); [DESKTOP_SYNC_MOBILE_PHASE2.md](implementation/DESKTOP_SYNC_MOBILE_PHASE2.md) + [DESKTOP_SYNC_MOBILE_PHASE3.md](implementation/DESKTOP_SYNC_MOBILE_PHASE3.md) — Android-side sync handoff for OptimalX Desktop)
- `cross-repo/`: links to the separate [OptimalX Desktop](https://github.com/Tuf12/OptimalXDesktop1.0) Electron repo ([README.md](cross-repo/README.md))
- `systems/`: subsystem behavior (voice, widget, web, journal, retrieval, rollover, Eidos; [NOTE_SUMMARY.md](systems/NOTE_SUMMARY.md) — per-note folder memory + content digest; [ROLLOVER.md](systems/ROLLOVER.md) — nightly memory rollover; [EIDOS_API_TRACE.md](systems/EIDOS_API_TRACE.md) — developer LLM request inspector; [GemmaLocal.md](GemmaLocal.md) — on-device Gemma 4B transport policy)
- `reference/`: API/tool references used during implementation ([API.md](reference/API.md) — supported providers; [LLM_API_REFERENCE.md](reference/LLM_API_REFERENCE.md) — per-call transport; [KIMI_K26_MOONSHOT_SPEC.md](implementation/KIMI_K26_MOONSHOT_SPEC.md) — Kimi K2.6 authoritative)
- `archive/`: older planning/debug drafts and removed specs ([archive/agent_loops/](archive/agent_loops/) — AgentByte / Tag & Hint, not in shipping app)
- `memory/`: continuity and system-folder memory model ([MEMORY_SYSTEM.md](memory/MEMORY_SYSTEM.md), [WORKSHOP_MEMORY.md](memory/WORKSHOP_MEMORY.md))
- `notes/`: working notes, journal, and active debug tracker

# OptimalX v2

## What This Is

OptimalX is a **simple, structured note system designed for both humans and AI agents to use directly**.

It is not a traditional note app.

It is a **hierarchical knowledge environment** where:
- users store information in folders and notes
- AI agents (Eidos) can read, write, organize, and operate inside the same system

---

## Core Idea

OptimalX is built on one principle:

> The system should be equally usable by a human and an AI.

That means:
- no hidden AI layer
- no duplicated systems
- no “AI bolted on top”

Everything exists in one shared structure.

---

## How It Works

### 1. Folder System

The app is built around a simple hierarchy:

- Parent Folders
- Subfolders
- Notes (inside subfolders)

Each subfolder acts as a **working space**, not just a container.

This is already implemented in the current system:
- parent folders → navigate to subfolders
- subfolders → open directly into content/editing 0 1

---

### 2. Direct Note Access

When a user enters a subfolder:
- they are taken directly into the note/editor
- no extra clicks
- no menu selection

This keeps the workflow fast and simple.

---

### 3. Editor-Centered Design

The note editor is the core of the app.

Additional features are accessed through:
- horizontal swipe between editor panels (Note, Files, Web, custom panels)
- pinned row shortcuts on the parent page (Panels, DumpEdit, Workshop, Quick Notes)
- hidden/expandable tools

The goal:
> keep the screen clean and focused on thinking/writing

---

### 4. File-Based Storage

OptimalX uses a real file system structure:
- folders exist as actual directories
- notes and subfolders are tied to those directories
- deletion currently moves items to a Trash directory 2 3

This makes the system:
- simple
- transparent
- easy to manage

---

## AI Integration (Eidos)

OptimalX includes an AI agent called **Eidos**.

Eidos is not just a chatbot.

It is designed to operate inside the system.

### Eidos can:
- read notes
- search folders
- create folders and subfolders
- write or append to notes
- organize content
- maintain a journal/log of activity

### Important:
Eidos uses the same structure as the user.

It does not use a hidden memory system.

Instead, it builds context through:
- stored notes
- folder structure
- internal journal entries

---

## Why This Exists

Most apps are built for:
- humans only
- or AI layered on top

OptimalX is built for:
- **human + AI collaboration inside the same environment**

This allows:
- better organization
- better retrieval
- evolving AI behavior over time
- a system that improves as it is used

---

## Design Philosophy

OptimalX follows these rules:

- Big, simple, blunt UI
- Minimal clutter
- Fast navigation
- Direct access to notes
- Structure over features
- AI integrated at the core, not added later

---

## What It Is NOT

OptimalX is NOT:
- a full office suite
- a feature-heavy productivity app
- a traditional note app
- a chatbot app

It is a:
> structured knowledge system with AI operation capabilities

---

## Current State

The current version includes:
- parent folder system
- subfolder navigation
- basic note entry
- file-based storage
- sorting, searching, renaming
- trash system for deleted items

Known issues:
- some features are incomplete or inconsistent
- delete behavior uses a trash system instead of true deletion
- UI has extra buttons and clutter
- AI is not fully integrated yet

---

## Direction (v2)

OptimalX v2 focuses on:

- clean data structure
- simplified UI
- editor-first experience
- pinned row + Panel Gallery + DumpEdit
- editor swipe panels + full AI integration (Eidos as operator)
- tool-based AI actions
- structured knowledge storage

---

## Summary

OptimalX is:

> a simple folder-based system for thinking, storing, and retrieving information  
> designed to be used directly by both humans and AI agents
