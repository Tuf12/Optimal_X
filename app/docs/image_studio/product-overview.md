# Image Studio — product overview (Android)

## Purpose

Image Studio lets the user **generate AI images from text**, save them into a subfolder’s Files (`file_references`), and browse past work in a scoped gallery — without leaving OptimalX.

It replaces most day-to-day need for an external image app while keeping images **first-class project attachments** (same sync path as imported files).

On Android, v1 generation uses **cloud APIs**. Desktop uses local FLUX Klein; mobile matches **UX and data rules**, with backend choice abstracted behind `ImageGenerationService`.

---

## What Image Studio is not

| Not this | Why |
|----------|-----|
| Panel Workshop custom panel | Built-in platform surface — shared Files + Eidos infrastructure |
| Chat composer attach | Chat attach is per-send, no `file_references` row — see chat vision plan |
| A global photo library | Images are always scoped to a subfolder (hub saves to **General**) |
| Eidos auto-generate | Eidos may draft prompts; user clicks **Generate** in the panel |

---

## Surfaces (two entry points)

| Surface | How user opens | Save target | Gallery scope |
|---------|----------------|-------------|---------------|
| **Subfolder editor** | Swipe left from Files → **Image** panel | Current subfolder’s `file_references` | **This subfolder only** — generated, imported, synced |
| **Home pin** | Pinned row → **Image Studio** (full-screen route) | System subfolder **Image Studio › General** | **All images app-wide** with folder labels |

### Hub vs subfolder tab

- **Subfolder tab:** project artwork lives with the job.
- **Home pin:** browse every image in OptimalX; new generates from the hub still land in **General**, not arbitrary project folders.

Location label examples (Eidos chrome):

- Hub: `Image Studio · All Images`
- Subfolder: `Image Studio · {subfolder name}`

---

## Core user flows

### Flow A — Generate in the panel

1. Open Image Studio (subfolder Image panel or home pin).
2. Enter a **readable file name** (required before save).
3. Enter **prompt** (optional **negative prompt**, collapsed by default).
4. Pick **model tier** (Draft / Quality) and **aspect ratio**.
5. Review **estimated cost** (mobile).
6. Tap **Generate**.
7. Progress indicator while the cloud job runs; user may navigate away — job continues in a foreground service or in-process coordinator (see implementation plan).
8. On success: preview in panel; row inserted in `file_references` with `metadata_json`.
9. User may **Regenerate** (new file, new name), open in Files viewer, or share/export.

### Flow B — Eidos-assisted prompt (v1 handoff)

1. User opens Eidos while Image Studio is in scope.
2. User describes intent in plain language.
3. Eidos replies with an **Image Studio draft** block (structured markdown).
4. User taps **Use in Image Studio** on that message.
5. Panel form fields populate; user reviews cost and taps **Generate**.

Eidos does **not** call generation APIs or fill the form via tools in v1.

### Flow C — Browse existing images

- Gallery below the form lists `file_type = image` rows in scope.
- Tap thumbnail → preview; if row has Image Studio metadata, offer **Reload settings** into the form (not auto-generate).
- Imported images (Files → import) appear in the same list with caption “Imported” / “Added to folder.”

---

## Model tiers (mobile cloud)

| Tier | User intent | v1 expectation |
|------|-------------|----------------|
| **Draft** | Fast iteration, lower cost | `grok-imagine-image` (~$0.02/image) |
| **Quality** | Higher fidelity | `grok-imagine-image-quality` (~$0.05/image) |

Exact model IDs are configured in [cloud-generation-api.md](./cloud-generation-api.md). Tier names match desktop so Eidos drafts and synced metadata stay readable cross-platform.

---

## File naming (no-repeat policy)

Uniqueness is **per subfolder**, not global.

1. User or Eidos supplies a readable base name (e.g. `cover-art-dragon`).
2. App normalizes to a safe filename + extension (`.png` default).
3. Before save: if `fileName` already exists **in this subfolder** → **reject** with clear error. No overwrite, no silent `-2` suffix.
4. **Regenerate** always creates a **new** file; UI may suggest `cover-art-dragon-v2` but user must confirm.

Same display name in two different subfolders is allowed.

---

## Regenerate semantics

| Action | Behavior |
|--------|----------|
| **Regenerate** | New cloud call (new seed unless user fixed seed); **new** `file_references` row; prior file remains |
| **Same name as existing** | Blocked in same subfolder |
| **Reload from gallery** | Copy prompt/tier/aspect from metadata into form only |

---

## Cost visibility (mobile-only)

Desktop local generation is free and hides cost. Android **must** show:

- **Before Generate:** estimated price from tier + aspect (catalog-driven).
- **After success:** actual billed amount if the API returns it; otherwise show estimate with “approx.”
- **On API error after charge:** surface provider message; still avoid saving partial/corrupt files.

Store `estimatedCostUsd` / `actualCostUsd` in `metadata_json` when available (optional fields).

---

## Parity matrix (desktop vs Android v1)

| Feature | Desktop | Android v1 |
|---------|---------|------------|
| Built-in panel | Editor tab + home pin | Swipe panel + home pin |
| Local `sd-cli` / FLUX weights | Yes | Not in v1 |
| Cloud API generation | Later optional tier | **Yes (v1 default)** |
| Cost UI | No | **Yes** |
| GPU lock with Eidos | Yes | **No** |
| `metadata_json` on generated files | Yes | Yes (after migration) |
| Per-subfolder name uniqueness | Yes | Yes |
| Hub “All Images” gallery | Yes | Yes |
| Eidos `image_studio` scope | Yes | Yes |
| `list_images` tool | Yes | Yes |
| Draft + **Use in Image Studio** | Yes | Yes |
| `generate_image` Eidos tool | Later | Later |
| Note `ox-file:` embeds | Phase 2 | Phase 2 (shared) |
| Settings: download local weights | Yes | Not in v1 |
| Settings: cloud API key | N/A (local) | **Reuses xAI key** (Settings → AI) |

---

## Not in v1 (may follow later)

- Video generation
- img2img / inpainting (unless cloud provider adds with trivial API mapping — defer)
- Desktop GPU proxy over LAN
- On-device LiteRT / Gemma image generation
- Content moderation / NSFW filters
- Survive generation after app kill (WorkManager retry is a later enhancement)
- Eidos `generate_image` tool

---

## Success criteria (product)

User can:

1. Generate an image from a subfolder and find it in Files and the subfolder gallery.
2. Open the home hub, see images from other folders, and generate into General.
3. Get a duplicate-name error without losing their prompt.
4. Chat with Eidos in Image Studio scope, receive a draft, apply it to the form, and generate.
5. Sync a generated image to desktop (Tier 1 + Tier 3) with metadata intact.
6. Understand what a generate will cost before tapping the button.
