README.md
primary use for an app overview for sharing with others, not a necessary file for the build, as the other .md files provide the actual details.

## Docs Directory Map

- `architecture/`: app structure, UI architecture, data model, and core design docs
- `systems/`: subsystem behavior (voice, widget, web, journal, retrieval, Eidos)
- `reference/`: API/tool references used during implementation
- `notes/`: working notes, journal, and active debug tracker
- `archive/`: older planning/debug drafts kept for history

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
- swipe navigation (planned)
- hidden/expandable tools
- secondary panels (files, etc.)

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
- swipe-based navigation
- full AI integration (Eidos as operator)
- tool-based AI actions
- structured knowledge storage

---

## Summary

OptimalX is:

> a simple folder-based system for thinking, storing, and retrieving information  
> designed to be used directly by both humans and AI agents
