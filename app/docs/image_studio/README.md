# Image Studio — feature specs (Android)

**Image Studio** is OptimalX’s built-in AI image generation panel: prompt in, image saved to `file_references`, browse in a scoped gallery, and reuse in Files / Notes (note embeds later).


| Field                              | Value                                                                                                                  |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| **Status**                         | **v1 shipped** on Android (cloud Grok Imagine); desktop uses local `sd-cli` |
| **Audience**                       | Product, UX, Eidos prompt authors, implementers                                                                        |
| **Platform**                       | **Android first** (this doc set); desktop already shipped local generation                                             |
| **Generation backend (mobile v1)** | **Grok Imagine (xAI)** — reuses existing xAI API key; `ImageGenerationService` abstraction for other backends later    |
| **Desktop product contract**       | [electron/image-studio.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio.md)             |
| **Desktop implementation**         | [image-studio-implementation.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/image-studio-implementation.md) |


---



## Why this exists

Users need project-scoped artwork without leaving OptimalX or juggling external tools. Desktop ships **local** FLUX Klein via `sd-cli`. Android v1 uses the **same panel UX and file model** with **cloud image APIs** (BYOK) and **cost visibility** — patterns desktop deliberately defers to “mobile Phase 3.” Local or LAN backends remain possible behind the same provider abstraction.

Eidos assists by expanding vague intent into a detailed visual prompt; the user always confirms **Generate** in the panel (no GPU tools from chat).

---



## Locked decisions (mobile v1)


| Topic                           | Decision                                                                                 |
| ------------------------------- | ---------------------------------------------------------------------------------------- |
| Panel class                     | **Built-in** swipe panel — not Panel Workshop                                            |
| Generation                      | **Grok Imagine (xAI)** — Draft / Quality tiers; provider abstraction for later backends  |
| API key                         | **Reuse existing xAI key** (`ApiKeyNames.XAI`); no separate Image Studio key in v1       |
| BYOK                            | User pays xAI directly; no OptimalX-hosted proxy in v1                                   |
| Local / LAN                     | **Not in v1** — `sd-cli`, Ollama FLUX, desktop GPU proxy deferred                        |
| GPU lock                        | **N/A** on phone — Eidos chat and image gen may run concurrently                         |
| File storage                    | Same as Files import — `file_references` + bytes in app private storage                  |
| Metadata                        | `metadata_json` on `file_references` (Room migration required)                           |
| Name uniqueness                 | **Per subfolder** — reject duplicate `fileName`; no silent suffix; no overwrite          |
| Regenerate                      | Always a **new** file + new unique name                                                  |
| Job lifecycle                   | Survives navigation within app; **cancel on process death**; optional WorkManager later  |
| Cost UI                         | **Show estimated cost before Generate** and actual/API-reported cost after (mobile-only) |
| Safety filters                  | None in product scope (match desktop)                                                    |
| Eidos generate from chat        | handoff via draft block + **Use in Image Studio** only in v1                             |
| Note inline images (`ox-file:`) | shared Phase 2 with desktop                                                              |


---



## Doc map

Read in this order for a full picture:


| Doc                                                        | Contents                                                        |
| ---------------------------------------------------------- | --------------------------------------------------------------- |
| [product-overview.md](./product-overview.md)               | Purpose, surfaces, user flows, parity vs desktop                |
| [ui-and-navigation.md](./ui-and-navigation.md)             | Swipe panel placement, home pin, gallery, form controls         |
| [data-model-and-storage.md](./data-model-and-storage.md)   | `metadata_json`, system folders, `panel_state` prefs, filenames |
| [cloud-generation-api.md](./cloud-generation-api.md)       | Provider abstraction, tiers, aspect ratios, cost, errors        |
| [eidos-integration.md](./eidos-integration.md)             | `image_studio` scope, tools, draft handoff, vision              |
| [sync-and-desktop-parity.md](./sync-and-desktop-parity.md) | Tier 1/3 sync, cross-device gallery, metadata wire              |
| [implementation-plan.md](./implementation-plan.md)         | Phased Android build chunks + acceptance                        |


---



## Relationship to other surfaces

```text
Parent folder page
├── Pinned row → Image Studio (hub)     ← saves to Image Studio › General
└── User subfolder → Editor
      ├── Note (default)
      ├── Files
      ├── Image Studio (new)            ← saves to current subfolder
      ├── Web
      └── Open file panels / custom panels
```


| Surface                | Role                                                                                                                           |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **Files panel**        | Import + open images; Image Studio **writes** generated rows into the same table                                               |
| **Chat vision attach** | Per-send composer image — **not** Image Studio; see [CHAT_VISION_ATTACH_PLAN.md](../implementation/CHAT_VISION_ATTACH_PLAN.md) |
| `describe_image`       | Describes existing Files rows — works today; unchanged                                                                         |
| **Panel Workshop**     | Separate product — do not implement Image Studio as a workshop panel                                                           |
| **NoteX (desktop)**    | May embed `ox-file:` links later; consumes same Files pool                                                                     |


---



## Cross-repo links


| Repo                       | Doc                                                                                                                                                                |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Desktop spec               | [electron/image-studio.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio.md)                                                         |
| Desktop setup (local only) | [electron/image-studio/SETUP.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio/SETUP.md)                                             |
| Sync tiers                 | [flow.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/flow.md)                                                                                           |
| Mobile sync handoff        | [DESKTOP_SYNC_MOBILE_PHASE2.md](../implementation/DESKTOP_SYNC_MOBILE_PHASE2.md), [DESKTOP_SYNC_MOBILE_PHASE3.md](../implementation/DESKTOP_SYNC_MOBILE_PHASE3.md) |


---



## Document changelog


| Version | Date       | Notes                                                                         |
| ------- | ---------- | ----------------------------------------------------------------------------- |
| 1       | 2026-08-28 | Initial Android Image Studio spec set; cloud API v1                           |
| 2       | 2026-08-28 | Soften v1-scoped language — avoid permanent “cloud only” / “no local” wording |
| 3       | 2026-08-28 | v1 default provider: Grok Imagine (xAI) — reuse existing xAI API key          |


