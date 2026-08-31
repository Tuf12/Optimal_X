# Image Studio — data model and storage (Android)

## Principle

Generated images are **ordinary file attachments**. Image Studio does not introduce a parallel image table. All bytes and pointers flow through the existing `file_references` model and private storage layout used by Files import.

---

## `file_references` changes

### New column: `metadataJson`

| Field | Type | Description |
|-------|------|-------------|
| `metadataJson` | `TEXT` nullable | JSON string; null for imported non-generated files |

Desktop already ships this column ([image-studio-implementation.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/image-studio-implementation.md) Chunk 1). Android must add a Room migration before saving generated images.

Update [DATA_MODEL.md](../architecture/DATA_MODEL.md) `FileReference` section when migration lands.

### Unchanged fields (generation save)

| Field | Value |
|-------|--------|
| `subfolderId` | Hub → General subfolder id; subfolder panel → current subfolder |
| `fileName` | User-visible name + extension |
| `fileType` | `"image"` |
| `filePath` | Device-local path under app files dir (same resolver as sync imports) |
| `globalId` | New UUID per file |
| `originDeviceId` | This device id |
| `createdAt` | Save timestamp |

---

## `metadata_json` shape (v1)

Aligned with desktop [`metadata.js`](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio/metadata.js). Mobile uses different `backend` values.

```json
{
  "source": "image_studio",
  "backend": "xai_imagine",
  "prompt": "A red dragon on a cliff at sunset, cinematic lighting…",
  "negativePrompt": "blur, watermark, text",
  "modelId": "grok-imagine-image",
  "tier": "draft",
  "aspectRatio": "16:9",
  "seed": 123456789,
  "width": 1344,
  "height": 768,
  "createdAt": 1730000000000,
  "estimatedCostUsd": 0.04,
  "actualCostUsd": 0.038,
  "providerRequestId": "req_abc123"
}
```

### Field rules

| Field | Required | Notes |
|-------|----------|-------|
| `source` | Yes | Always `"image_studio"` for generated rows |
| `backend` | Yes | `xai_imagine`, `openai_images`, `fal_cloud`, `chutes_cloud`, etc. |
| `prompt` | Yes | Full positive prompt sent to API |
| `negativePrompt` | No | Empty string if omitted |
| `modelId` | Yes | Provider-native model slug |
| `tier` | Yes | `draft` \| `quality` |
| `aspectRatio` | Yes | One of the five ratios |
| `seed` | No | Null if provider did not return / random |
| `width`, `height` | Yes | Resolved from aspect ratio catalog |
| `createdAt` | Yes | Epoch ms at save time |
| `estimatedCostUsd` | No | Mobile bookkeeping |
| `actualCostUsd` | No | When API reports usage |
| `providerRequestId` | No | Support / debugging |

### Detection helpers

Mirror desktop:

- `isImageStudioMetadata(json)` → `json?.source == "image_studio"`
- Gallery “Generated” filter uses this flag, not filename heuristics.

---

## Filename normalization

Port desktop [`filename.js`](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio/filename.js) behavior:

1. Trim whitespace.
2. Collapse internal spaces to `-`.
3. Strip characters unsafe on Android filesystem / sync (`/ \ : * ? " < > |` etc.).
4. Lowercase extension; default `.png` if none.
5. Reject empty result.

### Uniqueness check

```sql
SELECT COUNT(*) FROM file_references
WHERE subfolder_id = :subfolderId AND file_name = :fileName
```

- Count > 0 → error code `duplicate_name` before API call or disk write.
- Scoped to **subfolder only**.

---

## System folders (seed)

Desktop constants in [`system-folders.js`](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/seed/system-folders.js):

| Entity | Name | `globalId` |
|--------|------|------------|
| Parent folder | **Image Studio** | `a1000009-0009-4009-8009-000000000009` |
| Child subfolder | **General** | `b2000001-0001-4000-8001-000000000001` |

### Android seed tasks

1. Add `SystemFolderNames.IMAGE_STUDIO = "Image Studio"` to `DatabaseSeed.kt`.
2. Add parent global id to `SyncGlobalIds.SYSTEM_PARENT_GLOBAL_IDS`.
3. On seed/migration: create parent with `isSystemFolder = true`, `visibility = pinned` (match desktop).
4. Create **General** subfolder under that parent with fixed `globalId` above.
5. Expose resolver `ImageStudioGeneralSubfolderId` for hub save target.

Until seed ships, hub route must not be reachable (or must fail gracefully).

---

## Panel state (form prefs)

Persist last-used UI per **save target subfolder** — same contract as desktop `panel-prefs.js`.

| Key | Value |
|-----|--------|
| Table | `panel_state` |
| `scopeKey` | `"image_studio"` |
| `workshopSubfolderId` | **Save-target subfolder id** (column name is historical; not workshop-only) |

### `stateJson` payload (example)

```json
{
  "prompt": "",
  "negativePrompt": "",
  "tier": "draft",
  "aspectRatio": "1:1",
  "lastFileName": ""
}
```

- Debounced save on field change (500–800 ms) + flush on leave panel.
- Do **not** store API keys in `panel_state`.

---

## On-disk bytes

| Topic | Rule |
|-------|------|
| Location | App private files dir via existing attachment path helper |
| Format | PNG from API response (convert WebP/JPEG to PNG if needed for consistency) |
| Naming on disk | Follow sync convention: `{globalId}_{fileName}` or existing `SyncFilePathResolver` pattern |
| Delete | Deleting `file_references` row should remove bytes (match Files delete behavior) |

---

## Job state (in-memory / service)

Not persisted to Room in v1:

| Field | Purpose |
|-------|---------|
| `jobId` | UUID for active generate |
| `status` | `idle` \| `running` \| `complete` \| `error` \| `cancelled` |
| `progress` | 0.0–1.0 if provider supports |
| `errorCode` | `duplicate_name`, `api_error`, `no_api_key`, `cancelled`, … |
| `resultFileReferenceId` | Set on success |

Coordinator may live in `ViewModel` + `ForegroundService` for cancel/progress notification (implementation plan).

---

## Gallery queries

### Subfolder scope

```sql
SELECT * FROM file_references
WHERE subfolder_id = :id AND file_type = 'image'
ORDER BY created_at DESC
LIMIT :limit
```

### Hub scope (all images)

Join `subfolders` + `parent_folders` for labels (mirror desktop `listAllImageStudioHistory`):

```sql
SELECT fr.*, s.name AS subfolder_name, p.name AS parent_folder_name
FROM file_references fr
JOIN subfolders s ON s.id = fr.subfolder_id
JOIN parent_folders p ON p.id = s.parent_folder_id
WHERE fr.file_type = 'image'
ORDER BY fr.created_at DESC
LIMIT :limit
```

Sort key: prefer `metadata.createdAt` when `source = image_studio`, else `file_references.createdAt` (desktop `history.js` behavior).

---

## Sync DTO updates

Extend `SyncFileReferenceRow` in `SyncDtos.kt`:

```kotlin
@Serializable
data class SyncFileReferenceRow(
    // ... existing fields ...
    val metadataJson: String? = null,
)
```

- Tier 1 Push/Pull must round-trip the field.
- Desktop already serializes `metadataJson` — verify against [structure.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/structure.md).
- Lenient deserialize: older peers omit field → null.

---

## Migrations checklist

| Step | Artifact |
|------|----------|
| Room `ALTER TABLE file_references ADD COLUMN metadataJson TEXT` | `AppDatabase` migration N→N+1 |
| Instrumented test | insert row with JSON, read back |
| Sync mapper | `FileReference` ↔ `SyncFileReferenceRow` |
| Seed Image Studio parent + General | `DatabaseSeed` + migration for existing installs |

---

## Anti-patterns

| Do not | Do |
|--------|-----|
| Store prompts only in Notes | `metadata_json.prompt` on the file row |
| Second `image_studio_files` table | `file_references` |
| Global unique file names | Per-subfolder uniqueness |
| Save before API success | Write bytes only after valid image response |
| Put API keys in `metadata_json` | Encrypted settings store |
