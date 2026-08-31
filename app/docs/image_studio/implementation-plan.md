# Image Studio — Android implementation plan

**Status:** Phases 0–7 complete (v1 shipped on Android)  
**Product specs:** [README.md](./README.md) and sibling docs in this folder  
**Desktop contract:** [electron/image-studio.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio.md)  
**Prerequisite:** Files panel, Eidos send path, desktop sync Tier 1 + Tier 3, encrypted AI settings

Phased work plan to ship **cloud API** Image Studio on Android: schema, generation service, panel UI, Eidos scope, hub pin, sync parity.

---

## Locked decisions (implementation)

| Topic | Decision |
|-------|----------|
| Generation | Grok Imagine (xAI) — v1 default; `ImageGenerationService` for later backends |
| API key | Reuse `ApiKeyNames.XAI` — no separate Image Studio key |
| BYOK | User pays xAI directly; no OptimalX proxy in v1 |
| LAN / local | **Not in v1** |
| GPU lock | **None** |
| Metadata | `metadataJson` on `file_references` |
| Panel placement | Swipe panel after Files |
| Hub | Pinned row + General subfolder seed |
| Eidos v1 | Scope + `list_images` + draft handoff — no `generate_image` tool |
| Cost UI | Required on mobile |

---

## Guiding order

1. **Schema + seed** before any UI — rows and system folders must exist.
2. **Generation service + filename rules** before panel polish — unit-test save path with fake API.
3. **Panel shell + ViewModel** before Eidos — manual generate end-to-end.
4. **Gallery queries** before hub pin — verify list SQL.
5. **Eidos scope + tools** after panel mount points exist.
6. **Draft handoff** after scope stable.
7. **Sync DTO** in parallel with schema or immediately after migration.
8. **Settings + cost** before public beta.

---

## Module map (target)

```text
app/src/main/java/com/example/optimalx/
  data/imagestudio/
    ImageStudioMetadata.kt       # build/parse metadataJson
    ImageStudioFilename.kt       # normalize + uniqueness
    ImageStudioAspectRatio.kt    # ratio → width/height
    ImageStudioModelCatalog.kt   # tier → Grok Imagine model + cost table
    ImageGenerationService.kt    # interface
    XaiImageGenerationService.kt
    ImageStudioRepository.kt     # gallery queries + save transaction
  ui/imagestudio/
    ImageStudioPanel.kt          # editor swipe panel
    ImageStudioHubScreen.kt      # pinned row full screen
    ImageStudioViewModel.kt
    ImageStudioFormState.kt
    ImageStudioGallery.kt
  ui/settings/
    ImageStudioSettingsSection.kt
  data/eidos/
    ImageStudioDraftParser.kt
    scope resolver + prompt layer updates
    list_images tool in RoomToolExecutor
  data/db/
    migration *→*+1 metadataJson
    DatabaseSeed.kt              # Image Studio parent + General
  data/sync/
    SyncDtos.kt                  # metadataJson on SyncFileReferenceRow

app/src/androidTest/.../
  ImageStudioMetadataTest.kt
  ImageStudioFilenameTest.kt
  ImageStudioDraftParserTest.kt
  AppDatabaseMigration*Test.kt

app/docs/image_studio/           # this directory
```

---

## Phase 0 — Documentation ✅

| Task | Status |
|------|--------|
| Feature spec directory | [x] this folder |
| Cross-links in `app/docs/README.md` | [x] |
| Cross-links in `cross-repo/README.md` | [x] |

**Done when:** Implementers can build from docs without re-reading desktop repo.

---

## Phase 1 — Schema, seed, sync wire

**Goal:** Database ready for generated rows; system folders exist; sync round-trips metadata.

| Task | File | Status |
|------|------|--------|
| Add `metadataJson` to `FileReference` entity | `FileReference.kt` | [x] |
| Room migration + instrumented test | `AppDatabase.kt`, `Migration*Test` | [x] |
| `ImageStudioMetadata` helpers | `ImageStudioMetadata.kt` | [x] |
| `ImageStudioFilename` normalize + duplicate check | `ImageStudioFilename.kt` | [x] |
| Seed **Image Studio** parent + **General** subfolder | `DatabaseSeed.kt`, `SyncGlobalIds.kt` | [x] |
| Extend `SyncFileReferenceRow` + mappers | `SyncDtos.kt`, sync apply | [x] |
| Update `DATA_MODEL.md` | docs | [x] |

**Done when:** Insert row with metadata via DAO; Push/Pull preserves `metadataJson`; General subfolder resolvable by globalId.

---

## Phase 2 — Cloud generation service

**Goal:** Prompt in → PNG bytes out (Grok Imagine / xAI); no UI yet.

| Task | File | Status |
|------|------|--------|
| `ImageGenerationService` + `XaiImageGenerationService` | `data/imagestudio/` | [x] |
| `ImageStudioAspectRatio` + `ImageStudioModelCatalog` | same | [x] |
| Read xAI API key from `ApiKeyNames.XAI` | reuse existing encrypted prefs | [x] |
| `ImageStudioRepository.saveGeneratedImage(...)` transaction | `ImageStudioRepository.kt` | [x] |
| Unit tests with fake service | `src/test/...` | [x] |
| Optional: `ForegroundService` stub for long jobs | later in Phase 3 | [ ] |

**Done when:** Instrumented or unit test saves real bytes + row from mocked API response.

---

## Phase 3 — Panel UI (subfolder)

**Goal:** User can generate from subfolder editor Image panel.

| Task | File | Status |
|------|------|--------|
| Insert Image panel in editor swipe order | `EditorScreen.kt` | [x] |
| `ImageStudioPanel` + `ImageStudioViewModel` | `ui/imagestudio/` | [x] |
| Form: name, prompt, negative, tier, aspect, cost | Compose | [x] |
| Generate / Cancel / progress / preview | ViewModel | [x] |
| `duplicate_name` UX | panel | [x] |
| `panel_state` prefs (`scopeKey=image_studio`) | `PanelStateRepository` | [x] |
| Gallery: subfolder scope | `ImageStudioGallery.kt` | [x] |
| Update `EDITOR_AND_PANELS.md` | docs | [x] |

**Done when:** Manual generate in a user subfolder; file in Files; gallery shows entry; prefs survive rotation.

---

## Phase 4 — Hub pin + all-images gallery

**Goal:** Home route saves to General; cross-folder browse.

| Task | File | Status |
|------|------|--------|
| Pinned row **Image Studio** slot | parent page UI | [x] |
| `ImageStudioHubScreen` route | navigation | [x] |
| Hub gallery query with folder labels | `ImageStudioRepository` | [x] |
| Save target = General subfolder id | ViewModel | [x] |

**Done when:** Generate from hub → file in General; hub lists images from other subfolders after sync.

---

## Phase 5 — Settings + cost

| Task | File | Status |
|------|------|--------|
| Settings section: default tier, link to xAI key if missing | `ImageStudioSettingsSection.kt` | [x] |
| Block Generate when xAI key unconfigured | panel | [x] |
| Cost estimate line + post-generate actual | catalog + UI | [x] |
| Store cost fields in metadata | `ImageStudioMetadata` | [x] |

**Done when:** Fresh install with xAI key configured shows cost; without key, Generate links to Settings.

---

## Phase 6 — Eidos integration

| Task | File | Status |
|------|------|--------|
| `image_studio` scope in resolver | scope / send context | [x] |
| Prompt layer rules + draft footer | prompt composer | [x] |
| Separate conversation pointers | conversation DAO | [x] |
| `list_images` tool | `RoomToolExecutor` + catalog | [x] |
| `ImageStudioDraftParser` | `ImageStudioDraftParser.kt` | [x] |
| **Use in Image Studio** on chat bubble | `EidosChatScreen` / message actions | [x] |
| Preview vision attach | scope + vision path | [x] |
| Update `TOOL_FUNCTIONS.md` | docs | [x] |

**Done when:** Phase 1b desktop acceptance equivalent passes on phone (see below).

---

## Phase 7 — Sync acceptance + docs

| Task | Status |
|------|--------|
| Tier 1 + Tier 3 round-trip tests (manual checklist) | [x] |
| `FILES_AND_MEDIA.md` mention Image Studio | [x] |
| `features.md` desktop parity row (PR to desktop repo) | [x] |

**Automated:** `ImageStudioSyncRoundTripInstrumentedTest` (Tier 1 push/pull + JSON wire + Tier 3 bytes path).  
**Manual:** end-to-end LAN Push/Pull with desktop still recommended before release — see [sync-and-desktop-parity.md](./sync-and-desktop-parity.md) testing matrix.

---

## Manual acceptance (v1)

### Panel

- [x] Image panel appears after Files in subfolder editor
- [x] Generate with xAI key completes; PNG in Files
- [x] Duplicate name in same subfolder rejected
- [ ] Same name in different subfolders allowed, this should NOT be allowed!!!!
- [ ] Regenerate creates second file
- [ ] Switch to Note mid-generate → return shows result
- [x] Cost estimate shown before Generate

### Hub

- [x] Pinned **Image Studio** opens hub
- [ ] Generate saves to General
- [ ] **All Images** lists cross-folder rows

### Eidos

- [ ] Scope label correct (hub vs subfolder)
- [ ] `list_images` from chat returns correct scope
- [ ] Draft block + **Use in Image Studio** fills form
- [ ] Handoff does not auto-generate

### Sync

- [ ] Phone generate → Push → desktop sees metadata + image
- [ ] Desktop generate → Pull → phone gallery + bytes

---

## Risk register

| Risk | Mitigation |
|------|------------|
| xAI pricing / model id drift | Catalog in one file; document pin date |
| API key leakage | Encrypted prefs only; audit logs |
| Large PNG memory | Stream to disk; downscale if > 4096 edge |
| `metadataJson` breaks old app versions | Nullable column; Tier 1 ignore-unknown |
| `panel_state` column naming confusion | Document: host = save-target subfolder id |
| User expects free local gen like desktop | Clear copy: cloud + BYOK + cost line |
| Missing General seed breaks hub | Migration + resolver test by globalId |

---

## Suggested coding order

```text
Phase 1 schema → Phase 2 cloud service → Phase 3 subfolder panel
    → Phase 4 hub → Phase 5 settings/cost
    → Phase 6 Eidos → Phase 7 sync acceptance
```

Start Phase 1 when ready to code.

---

## Not in v1 (defer to later phases)

- `generate_image` Eidos tool
- Note `ox-file:` embeds (desktop Phase 2)
- Desktop LAN generation proxy
- On-device diffusion / LiteRT image gen
- Video / img2img
- WorkManager survive-after-kill
