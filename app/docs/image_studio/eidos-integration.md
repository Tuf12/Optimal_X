# Image Studio — Eidos integration (Android)

## Overview

Image Studio has a dedicated Eidos scope: **`image_studio`**. Conversation pointers, prompt layers, and tool availability differ from Note/Files `subfolder` scope even when the same subfolder is open.

Desktop reference: [electron/image-studio.md §10](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio.md#10-eidos-integration), [`prompt-layers.js`](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/eidos/prompt-layers.js), [`list-images-tool.js`](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/eidos/list-images-tool.js).

---

## Scope resolution

| UI location | `scopeType` | `imageStudio.hub` | Save target subfolder | Default `list_images` scope |
|-------------|-------------|-------------------|----------------------|----------------------------|
| Home pin hub | `image_studio` | `true` | Image Studio › General | `all` |
| Editor Image panel | `image_studio` | `false` | Current subfolder | `subfolder` |

### Scope payload (prompt / trace)

```kotlin
data class ImageStudioScopeContext(
    val hub: Boolean,
    val saveSubfolderId: Long,
    val activePreviewFileReferenceId: Long? = null,
)
```

Attach to existing send context (`EidosSendContext` / scope resolver) alongside `subfolderId`.

### Location label (chat chrome)

| Mode | Example |
|------|---------|
| Hub | `Image Studio · All Images` |
| Subfolder | `Image Studio · Kitchen Remodel` |

### Conversation isolation

- Image Studio threads are **separate** from Note tab `subfolder` threads for the same subfolder id (match desktop `conversation-browser.js` behavior).
- Switching editor panel from Note → Image updates active scope and conversation pointer.

---

## System prompt layer

When `scopeType == image_studio`, inject rules equivalent to desktop `buildImageStudioLayer`:

```
Image Studio rules:
- Clarify subject, style, composition, lighting, materials, camera, and mood.
- Expand wording into a detailed visual prompt (cloud models still need concrete visuals).
- Browse existing images with list_images — never assume the gallery is in context.
- Do not trigger image generation from chat.

[Hub-specific or subfolder-specific lines]

### Image Studio draft
When the user is ready to generate, include a draft the UI can apply with Use in Image Studio.
```

### Hub lines

- Pinned Image Studio hub: All Images across OptimalX.
- `list_images` default scope=all.
- Save target subfolderId (General): `{id}`

### Subfolder lines

- Image Studio subfolder tab — images in this folder only.
- Active save target subfolderId: `{id}`

---

## What Eidos should do

| Task | How |
|------|-----|
| Clarify intent | Ask about subject, style, composition, lighting, palette |
| Expand prompts | Turn product language into concrete visual description |
| Suggest negative prompt | Artifacts to avoid (blur, watermark, extra fingers, gibberish text) |
| Suggest aspect + file name | Must be valid ratio + unique per save subfolder |
| Browse images | Call `list_images` — never assume gallery is in context |
| See preview image | When user selects gallery preview, attach vision for that send (see below) |

---

## What Eidos must not do (v1)

| Forbidden | Reason |
|-----------|--------|
| `generate_image` tool | Generation stays in panel |
| Tools that write panel form fields | Renderer handoff only |
| Inline full image catalog in system prompt | Use `list_images` |
| Auto-start Generate after draft | User confirms + cost review |

---

## Tools

### `list_images` (new on mobile)

Port desktop [`list-images-tool.js`](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/eidos/list-images-tool.js).

**Parameters:**

| Arg | Type | Default in `image_studio` scope |
|-----|------|-------------------------------|
| `scope` | `all` \| `subfolder` | hub → `all`; subfolder tab → `subfolder` |
| `subfolderId` | Long? | Required when `scope=subfolder` |
| `limit` | Int | 48 (max 200) |

**Returns (per image):**

```json
{
  "fileReferenceId": 42,
  "fileName": "cover-art-dragon.png",
  "globalId": "…",
  "subfolderId": 7,
  "subfolderName": "General",
  "parentFolderName": "Image Studio",
  "caption": "A red dragon…",
  "hasGenerationSettings": true,
  "tier": "draft",
  "aspectRatio": "16:9",
  "bytesOnDevice": true,
  "createdAt": 1730000000000
}
```

Register in tool catalog + `RoomToolExecutor` with same gating as desktop (`image_studio` scope).

### Existing tools in scope

| Tool | Available | Notes |
|------|-----------|-------|
| `search_folders` | Yes | Find other project folders |
| `list_folder_contents` | Yes | Folder structure; not hub image browser |
| `describe_image` | Yes | For Files rows by id |
| `read_file` | Yes | Non-image files in folder |
| `write_note` | No / restricted | Image scope is not note editing — match desktop tool policy |

Verify against desktop `scope-profile-registry.js` when implementing.

---

## Image Studio draft (chat → panel handoff)

### Block format (stable parser contract)

Must match desktop [`image-studio-draft.js`](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/renderer/js/image-studio-draft.js):

```markdown
### Image Studio draft
**Prompt:** …detailed positive prompt…
**Negative:** …optional…
**Aspect:** 16:9
**Suggested name:** cover-art-dragon
**Tier:** draft
```

| Field | Rules |
|-------|-------|
| `Prompt` | Required; multiline allowed |
| `Negative` | Optional; treat `none` / `n/a` / `—` as empty |
| `Aspect` | One of `1:1`, `2:3`, `9:16`, `3:2`, `16:9` |
| `Suggested name` | Strip `.png` / `.webp` suffix |
| `Tier` | `draft`, `quality`, `4b`, `9b` |

### Parser module

`ImageStudioDraftParser.kt` — port desktop tests to `ImageStudioDraftParserTest.kt`.

### UI handoff

1. Detect draft in assistant message (`hasImageStudioDraft`).
2. Show action chip **Use in Image Studio** when:
   - `viewedScope.scopeType == image_studio`, and
   - draft parses successfully.
3. On tap:
   - `ImageStudioFormState.applyDraft(draft)`
   - Persist to `panel_state` prefs for save target
   - Navigate to Image panel if on Note/Files
   - Focus prompt field
   - **Do not** call Generate or cloud API

---

## Vision: active preview + composer attach

| Source | Priority | Behavior |
|--------|----------|----------|
| Composer attach | Wins if both present | Existing chat vision path |
| Gallery active preview | Secondary | Attach preview bytes for that Eidos send |

When user taps a gallery item to preview, set `activePreviewFileReferenceId` in scope. Prompt layer adds preview line (desktop `buildImageStudioPreviewLayer`):

```
Active preview image
Currently previewing: "cover-art-dragon.png"
```

Vision inference uses existing `ImageVisionService` / provider multimodal path — same as chat attach and `describe_image` families.

---

## GPU lock

**Not applicable on Android.** Eidos chat and Image Studio Generate may run concurrently. Do not port desktop `gpu-gate.js`.

Document in Eidos status UI only if a future on-device backend is added.

---

## Prompt writing guidance (for prompt authors)

Include in `systems/PROMPT_SYSTEM.md` cross-link when shipped:

- Prefer concrete visuals over brand adjectives (“premium” → materials, lighting, palette).
- One clear subject; avoid contradictory instructions.
- Negative prompt for common artifacts when useful.
- Aspect must be one of the five supported ratios.
- File name must be unique in the **save target** subfolder (General when hub).

---

## API trace

Record in `EidosApiTrace` when Image Studio scope:

- `scopeType = image_studio`
- `list_images` tool calls
- Vision attach from preview (file name + byte size, not raw pixels in trace)

---

## Testing checklist

- [ ] Hub Eidos location string correct
- [ ] Subfolder Image tab separate conversation from Note tab
- [ ] `list_images` defaults: hub=all, tab=subfolder
- [ ] Draft parser round-trip desktop examples
- [ ] **Use in Image Studio** does not hit generation API
- [ ] Preview vision attach on send when preview selected
