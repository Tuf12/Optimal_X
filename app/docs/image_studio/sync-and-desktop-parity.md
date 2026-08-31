# Image Studio — sync and desktop parity (Android)

## Goal

Images generated on phone or desktop must appear in the **same `file_references` rows** after sync, with generation metadata preserved, and bytes available via the existing Tier 3 attachment path.

Mobile does **not** sync local model weights or generation jobs — only **finished files**.

---

## Sync tiers (unchanged infrastructure)

| Tier | What moves | Image Studio relevance |
|------|------------|------------------------|
| **Tier 1** | Metadata rows including `file_references` + `metadataJson` | Prompt, tier, aspect, seed, backend |
| **Tier 2** | Conversations, messages, panel_state | Image Studio scoped chats; form prefs in `panel_state` |
| **Tier 3** | File bytes on demand | PNG/WebP bytes for generated images |

See [DESKTOP_SYNC_MOBILE_PHASE2.md](../implementation/DESKTOP_SYNC_MOBILE_PHASE2.md), [DESKTOP_SYNC_MOBILE_PHASE3.md](../implementation/DESKTOP_SYNC_MOBILE_PHASE3.md), [flow.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/flow.md).

---

## Cross-device scenarios

### Phone generates → desktop sees

1. User generates on Android (cloud API).
2. Row inserted locally with `metadataJson.backend = xai_imagine`.
3. User runs **Push** (Tier 1) — desktop receives row + metadata.
4. User runs **Sync files to PC** (Tier 3) — bytes upload to desktop `optimalx_files/`.
5. Desktop Image Studio hub / subfolder gallery shows image with prompt snippet.

### Desktop generates locally → phone sees

1. Desktop `sd-cli` save with `metadataJson.backend = sd_cli_local`.
2. Tier 1 Pull brings row to phone.
3. Tier 3 Pull brings bytes.
4. Android gallery shows image; **Reload settings** works from metadata (tier labels still readable).

### Same name in different subfolders

Allowed on both platforms. Sync uses `globalId` for identity — no cross-folder name collision.

---

## `metadataJson` wire format

| Rule | Detail |
|------|--------|
| Column name on wire | `metadataJson` (camelCase JSON field in sync bundle) |
| Desktop column | `metadata_json` TEXT |
| Android Room | `metadataJson` TEXT nullable |
| Null | Imported files, legacy rows |
| Unknown JSON keys | Ignore on parse (forward compatible) |

### Backend field semantics

| `backend` | Origin | Phone can regenerate settings? |
|-----------|--------|-------------------------------|
| `xai_imagine` | Android Grok Imagine | Yes — reload tier/aspect/prompt |
| `fal_cloud` | Optional future fal.ai | Yes |
| `sd_cli_local` | Desktop local | Yes — reload prompt; Generate uses cloud on phone |
| `openai_images` | Future | Yes |

Regenerate on phone always uses **cloud API**, even when metadata came from desktop local generation.

---

## System folder sync

Fixed global IDs must match desktop seed:

| Entity | `globalId` |
|--------|------------|
| Image Studio parent | `a1000009-0009-4009-8009-000000000009` |
| General subfolder | `b2000001-0001-4000-8001-000000000001` |

Hub save target on both platforms resolves to **General** by `globalId`, not display name.

Android must seed these **before** first hub generate on a fresh install. Desktop already seeds on DB init.

---

## `panel_state` sync

Image Studio form prefs (`scopeKey = image_studio`) sync via Tier 1 if `panel_state` is in the sync bundle.

| Platform | Host id column | Scope |
|----------|----------------|-------|
| Desktop | `workshop_subfolder_id` = save-target subfolder | `image_studio` |
| Android | `workshopSubfolderId` = save-target subfolder | `image_studio` |

Prefs are per save target — hub (General id) vs each user subfolder.

---

## Gallery parity

| Feature | Desktop | Android |
|---------|---------|---------|
| Hub lists all images | Yes | Yes |
| Subfolder tab lists folder images only | Yes | Yes |
| Folder labels on hub rows | Yes | Yes |
| “Not on device” placeholder | “Not on PC” | “Not on device” / sync hint |
| Filter generated vs imported | Yes | Yes |
| Caption from metadata prompt | Yes | Yes |

---

## Features intentionally different

| Topic | Desktop | Android |
|-------|---------|---------|
| Generation runtime | Local `sd-cli` | Cloud API (v1 default) |
| Cost in metadata | Usually omitted | `estimatedCostUsd` / `actualCostUsd` optional |
| GPU lock with Eidos | Yes | No |
| Model download Settings | Yes | No |
| Job survives app quit | No (cancel on quit) | Same |

---

## Desktop docs to keep aligned

When Android ships, update or verify:

| Desktop file | Check |
|--------------|-------|
| `electron/image-studio.md` §10 Mobile | Mark Android v1 shipped |
| `image-studio-implementation.md` Chunk 10 | Link to this doc set |
| `structure.md` | `metadata_json` on `file_references` |
| `features.md` | Parity matrix row for mobile Image Studio |

---

## Sync testing matrix

| # | Steps | Expected | Automated |
|---|-------|----------|-----------|
| 1 | Phone generate → Tier 1 Push | Desktop row + metadata | `ImageStudioSyncRoundTripInstrumentedTest.tier1_pushBundle_includesImageStudioMetadata` |
| 2 | Tier 3 upload | Desktop opens image in Files / Image Studio | Manual LAN |
| 3 | Desktop local generate → Pull | Phone row; bytes after Tier 3 | `ImageStudioSyncRoundTripInstrumentedTest.tier1_pull_preservesImageStudioMetadata` + manual Tier 3 |
| 4 | Hub gallery on phone after desktop Pull | Cross-folder labels correct | Manual |
| 5 | `metadataJson` null import | Sync does not crash either direction | `SyncFileReferenceMetadataTest` |
| 6 | Duplicate name on same subfolder on one device | Fails locally; never creates duplicate globalId | `ImageStudioRepositoryInstrumentedTest` |

---

## LAN / desktop proxy (deferred past v1)

Some future designs might route phone generation through desktop when on the same Wi‑Fi. Would require:

- New authenticated IPC/HTTP on desktop
- Cost accounting choice (desktop local vs cloud)
- A new `ImageGenerationService` backend implementation

Revisit when local or offline generation becomes a product priority.
