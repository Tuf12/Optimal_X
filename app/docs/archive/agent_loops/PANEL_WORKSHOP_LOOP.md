# PANEL WORKSHOP LOOP

## Purpose

Panel Workshop loop guides Eidos through building custom HTML/JS panels inside OptimalX.

**Product / UI context:** [../architecture/PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md), [../architecture/EDITOR_AND_PANELS.md](../architecture/EDITOR_AND_PANELS.md).

## Terminology

Phase labels (**King, Rook, Queen, …**) are **workflow roles** for prompts and tool allowlists. They align with **`ChessPiece`–style** policy in AgentByte — not with “the model must call **`chess_taxonomy`**.” That tool is optional and only mirrors piece/lens vocabulary when present in the catalogue.

## Implementation status

**v1 workshop UX:** Use **[WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md)** (Plan / Build / Edit / Debug / Chat) — user-visible modes with prompt + tool gating. That doc is the active product spec for Panel Workshop Eidos.

**This file (chess-piece phases):** Deferred for a separate AgentByte branch. [`OperatingMode.PANEL_WORKSHOP`](../../src/main/java/com/example/optimalx/data/eidos/agentbyte/AgentByteModels.kt) and piece allowlists in Kotlin are **not** wired to workshop chat today. Do not implement new workshop features on King/Rook/Queen routing until that branch is intentional.

The **named phases** below remain a **design reference** for future AgentByte integration, not current workshop behavior.

This loop is longer and more structured than normal chat.

## High-Level Phases

1. KING_INIT
2. ROOK_DOC_REVIEW
3. ROOK_SPEC_PLAN
4. QUEEN_CODE_WRITE
5. KNIGHT_ERROR_RECOVERY
6. ROOK_VERIFY_AGAINST_DOCS
7. USER_REVIEW
8. KING_CLOSE_OR_PAUSE

## Core Rule

Docs define the target.

Code follows the docs.

If code and docs disagree, fix the docs or ask the user before continuing.

## Rook Role

Rook handles:
- reading project docs
- checking structure
- comparing implementation to spec
- verifying logic against FEATURES and FLOW

## Queen Role

Queen handles:
- writing code
- updating files
- creating direct implementation changes
- applying clear edits

Queen acts only when the path is clear.

## Knight Role

Knight handles:
- WebView errors
- console errors
- broken interactions
- missing dependencies
- alternate implementation paths

## Bishop Role

Bishop may be used when:
- user feedback is preference-based
- design feels wrong
- tone/feel/usability matters

## King Role

King handles:
- overwrites
- destructive changes
- token milestones
- pause/resume
- final completion

## Tools

Panel Workshop profile should expose tools based on phase.

Do not expose all code/write tools during doc review.

Do not expose destructive tools without King.

## Exit

Exit when:
- panel works
- user approves
- user pauses
- token milestone pause
- unrecoverable error
