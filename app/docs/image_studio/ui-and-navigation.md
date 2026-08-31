# Image Studio — UI and navigation (Android)

## Panel placement

Image Studio is a **built-in horizontal swipe panel** in the subfolder editor — same class as Note, Files, and Web.

### Updated panel order (v2 + Image Studio)

| Position | Panel | Notes |
|----------|-------|-------|
| Center (default) | **Note** | Unchanged — subfolder still opens here |
| Right of Note | **Files** | Unchanged |
| Right of Files | **Image Studio** | **New** — generate + gallery |
| Right of Image Studio | **Web** | Unchanged |
| Further right | Open file panels, custom workshop panels | Unchanged |

Update [EDITOR_AND_PANELS.md](../architecture/EDITOR_AND_PANELS.md) when implementation lands.

### Navigation rules

- Swipe left from Files → Image Studio; swipe right → back to Files.
- Android back from Image panel → previous panel (Files), not subfolder page.
- Switching panels during an in-flight generate **does not cancel** the job (match desktop).
- Returning to Image panel restores progress/result state.

---

## Home pin route

Add **Image Studio** to the parent-page **pinned row** (fixed system slot after Panel Workshop or per product sort — align with desktop order in [system-folders.js](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/seed/system-folders.js)).

| Behavior | Detail |
|----------|--------|
| Tap | Full-screen **Image Studio hub** (not embedded in editor) |
| Save target | **Image Studio › General** system subfolder |
| Gallery title | **All Images** |
| Pinned row | Same row as Panels / DumpEdit / Workshop / Quick Notes |

The hub screen reuses the same compose UI as the editor panel (`mode = hub` vs `mode = subfolder`).

---

## Screen layout (shared hub + subfolder panel)

Single scrollable column (Material 3):

```text
┌─────────────────────────────────────┐
│  [Toolbar: subfolder name or         │
│   "Image Studio" + Eidos button]    │
├─────────────────────────────────────┤
│  Context line (muted)               │
│  File name *                        │
│  [...........................]      │
│  Prompt *                           │
│  [                           ]      │
│  [multiline]                        │
│  0 characters                       │
│  ▸ Negative prompt (collapsed)      │
│  Model tier:  ( ) Draft  ( ) Quality│
│  Aspect ratio: [1:1][2:3][9:16]...  │
│  Est. cost: $0.04                   │
│  [ Generate ]  [ Cancel ]             │
│  Status / progress                  │
│  ┌─────────────────────────────┐    │
│  │     Preview image           │    │
│  └─────────────────────────────┘    │
│  [ Regenerate ] [ Share ] [ Open ]  │
├─────────────────────────────────────┤
│  ▾ Gallery                          │
│  Filter: [All | Generated | Import] │
│  View: grid | list                  │
│  [thumb] name · caption             │
│  ...                                │
└─────────────────────────────────────┘
```

### Context line copy

| Mode | Text |
|------|------|
| Hub | New images save to **Image Studio › General**. Browse every image below. |
| Subfolder | Images save to **this folder**. Gallery shows this subfolder only. |

---

## Form fields

### File name

- Required before **Generate**.
- Single-line `OutlinedTextField`.
- Hint: “Readable name, e.g. cover-art-dragon”
- On `duplicate_name` error: keep prompt/tier/aspect; focus name field.

### Prompt

- Multiline, required for generate.
- Character count below field (desktop parity).
- Prefilled from Eidos draft handoff.

### Negative prompt

- `Expandable` / collapsed `Card` by default.
- Optional; empty is valid.

### Model tier

- Radio group: **Draft** (default), **Quality**.
- If xAI API key missing or tier unavailable → disable Generate with link to Settings → AI.

### Aspect ratio

- Horizontal chip group with icons + labels:
  - `1:1`, `2:3`, `9:16`, `3:2`, `16:9`
- Default `1:1`.
- Pixel dimensions resolved in generation service (see [cloud-generation-api.md](./cloud-generation-api.md)) — same mapping as desktop `aspect-ratio.js`.

### Cost line

- Visible on mobile only.
- Updates when tier or aspect changes.
- If price unknown → “Cost varies — check provider dashboard.”

---

## Generate / progress / output

| State | UI |
|-------|-----|
| Idle | Generate enabled when name + prompt + API configured |
| Running | Linear progress + status text (“Generating… ~30s”); **Cancel** visible |
| Success | Preview `AsyncImage`; show actual cost if known |
| Error | Inline error card; form preserved |

### Output actions

| Action | Behavior |
|--------|----------|
| **Regenerate** | New seed; suggest new file name (`-v2`); do not auto-generate |
| **Share** | Android share sheet with image URI |
| **Open in Files** | Swipe/navigate to Files panel; highlight row (optional polish) |

No “download outside OptimalX” special case — share sheet covers export.

---

## Gallery

Collapsible section below the form. **Same data as Files** — not a second store.

| Mode | Section title | Query |
|------|---------------|-------|
| Hub | **All Images** | All `file_type = image`, newest first, with subfolder + parent labels |
| Subfolder | **Images in this folder** | `subfolderId = current` |

### Row content

- Thumbnail (Coil; placeholder if bytes missing)
- File name
- Caption:
  - Image Studio metadata → prompt snippet (~96 chars)
  - Else → “Imported” or “Added to folder”
- Optional badge: folder name (hub only)

### Row interactions

- Tap → full preview bottom sheet or inline expand
- Long press → delete (same rules as Files) — confirm dialog
- Active preview highlights row (desktop `image-studio-history-item-active`)

### Gallery controls (match desktop)

- Filter: All / Generated only / Imported only
- View toggle: grid vs list
- Empty state copy when no images in scope

### Missing bytes

If Tier 1 row exists but Tier 3 bytes not pulled: thumbnail placeholder + “Not on device — sync from PC” (mirror desktop “Not on PC”).

---

## Settings entry

**Settings → AI → Image Studio** (new section):

| Control | Purpose |
|---------|---------|
| Cloud provider | Grok Imagine (xAI) — v1 only; extensible later |
| API key | Reuses existing xAI key from Settings → AI |
| Default tier | Draft / Quality |
| Default aspect | Last used per save target also stored in `panel_state` |

No model download UI on mobile.

---

## Eidos entry points

| From | Scope when sheet opens |
|------|------------------------|
| Image panel toolbar | `image_studio` (subfolder) |
| Hub screen toolbar | `image_studio` (hub) |
| Assistant message | **Use in Image Studio** when draft detected + scope is `image_studio` |

Handoff switches to Image panel if user was on Note/Files (editor only).

---

## Accessibility

- Tier and aspect groups: `RadioGroup` / `Row` with `contentDescription`
- Progress: `LiveRegion` for status announcements
- Gallery thumbnails: `contentDescription` = file name

---

## Theming

Follow [THEME.md](../architecture/THEME.md). Reuse Files panel spacing and muted caption styles for consistency.
