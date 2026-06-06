# WEB_SYSTEM.md

## Purpose

Defines the Web panel system for OptimalX.

This system allows users to view live web pages inside the app without leaving their workflow.

Use this file with `EDITOR_AND_PANELS.md`, `TOOL_FUNCTIONS.md`, and `EIDOS_AGENT.md`.

---

## Core Product Goal

Enable fast in-context web access so users can gather information and continue working in the same note/folder flow.

The Web panel is an in-app browser surface, not a scraped-content reader.

---

## Terminology

- **MVP** = **Minimum Viable Product**
- In this file, MVP means the smallest version that is useful, stable, and safe to ship.

---

## Core Rule

Web pages are rendered as the site serves them, through an Android `WebView`.

The system does not convert pages to sanitized article text for primary browsing behavior.

---

## Scope Boundaries

### In scope
- Open and render `https://` and `http://` URLs in an in-app Web panel
- Standard browser actions: back, forward, reload, open externally
- Per-page loading and error states
- Fallback prompt to open in external browser when page cannot be used in-panel

### Out of scope for MVP
- Full multi-tab browser
- Bookmark manager
- Download manager
- Password manager
- Advanced browser devtools
- Full extension-like behavior

---

## User Experience Contract

### Primary flow
1. User (or Eidos) provides a URL.
2. URL opens in Web panel.
3. Panel renders live page content.
4. User can navigate or return to editor/files flow.

### External browser fallback flow
If page cannot be properly used in Web panel:
- Show a modal prompt:
  - **Open in Browser** (accept)
  - **Cancel** (reject)
- If accepted, launch system chooser/default browser.
- If rejected, remain in app with no navigation side effect.

### No forced app exit
Web failures should never trap the user or force-close the panel flow.

---

## Web Panel Placement

The Web panel is a dedicated panel type in the editor/panel system.

It is separate from:
- note editing panel
- file viewer panel
- future HTML/JS custom panel system

The Web panel should not mutate note content by default unless user or tool explicitly asks to save a reference.

---

## Access Surfaces

WebView access is available in two locations:

### 1) Editor-level panel access
- From inside a subfolder editor flow, users can open the Web panel alongside Notes/Files.
- This is the context-rich path where web browsing happens while editing a specific note.

### 2) Widget quick access
- From the widget, user can launch web search and open the widget web panel directly.
- This supports quick global browsing outside the in-app folder/editor navigation stack.

### Scope behavior rule
- Editor Web panel is subfolder-scoped and persists separately per subfolder.
- Widget Web panel uses an isolated widget scope.
- Scope isolation applies to:
  - current session URL restore
  - recent pages
  - recent searches
  - bookmarks

---

## URL Handling Rules

### Allowed schemes
- `https`
- `http` (allowed, but can be warned if desired)

### Blocked schemes in MVP
- `javascript:`
- `file:`
- `content:`
- `intent:` (unless explicitly handled by app policy later)
- any unknown custom scheme

When blocked:
- show explanatory UI
- offer external browser option if appropriate

---

## Cookie, Storage, and Privacy Rules

The Web panel must respect standard web platform behavior required by many sites.

### Required baseline
- cookies enabled
- DOM storage enabled
- session/local storage enabled through WebView defaults

### Third-party cookie policy
- allow third-party cookies in MVP for compatibility
- make this setting explicit in future settings UI if needed
- if possible always auto force minimum cookies and none whenver the option is available if possible, if not possible just ignore this and let the developer know 

### Privacy posture
- browsing requests go directly to destination websites
- site cookies and trackers behave according to site/browser rules
- app should not claim private/isolated browsing unless explicitly implemented

### No hidden scraping mode
The Web panel should not silently fetch/transform site content in the background as a replacement for rendering.

---

## Security Requirements

### WebView hardening baseline
- JavaScript enabled only for the page runtime (standard browsing)
- `addJavascriptInterface` not used in MVP
- file access from WebView disabled unless explicitly required
- mixed-content mode set deliberately (prefer secure behavior)
- safe browsing enabled where supported

### Navigation safety
- keep navigation inside panel for normal `http/https`
- prevent unsafe scheme escalation
- explicit user intent required for leaving app

### TLS and SSL errors
- show clear error state
- do not silently bypass SSL validation
- allow external-open fallback choice

---

## Eidos Tooling Contract

Web browsing and web search are separate but can work together:

- Provider-native web search: discover links and current external info through the active API provider
- Provider-native web fetch/browsing: read accessible page content for grounding/summarization when the active provider supports it
- Web panel: render target URL as a live page
- If provider web access is blocked/unavailable, Eidos should still provide URL links so user can open them in Web panel.

### Recommended tools
- Provider-hosted web search/fetch/browsing — current information and URL analysis, with citations
- `open_web_page(url)` — open URL in Web panel
- `save_web_reference(url, title?)` — optional: persist link in system
- `list_web_references(scope?)` — optional future helper

### Tool behavior rule
If Eidos opens a page, it should announce what it opened and why.

### Correct Phase 1 flow
1. Eidos uses the active provider's hosted web capability for current information or URL analysis.
2. Eidos cites source links returned by the provider in the final answer.
3. If hosted fetch/browsing is unavailable or insufficient, Eidos returns the URL(s) so user can open them directly in the Web panel.

**Kimi (Moonshot K2.6):** Formula `web_search` and `fetch` via `KimiFormulaToolService` — tool schemas from `GET /formulas/moonshot/{web-search,fetch}:latest/tools`, execution via `POST .../fibers`. Fetch tool results prepend `Source: <url>` for citation. Thinking remains enabled; this is not the builtin `$web_search` path.

---

## Error Model

The UI must distinguish:
- invalid URL format
- network unavailable
- DNS/connect timeout
- HTTP error page
- blocked/unsupported scheme
- site not compatible with in-app rendering

Each error state should provide:
- plain explanation
- retry action
- external browser fallback action

---

## Performance Expectations

### MVP targets
- panel opens quickly from URL action
- progress indicator appears immediately
- no UI thread blocking from browser callbacks

### Memory behavior
- release heavy WebView state when panel closes where possible
- avoid unbounded hidden instances in memory

---

## Analytics and Logging (Optional MVP+)

At minimum, capture debug logs for:
- URL open attempts
- major load failures (error code/class)
- external fallback accept/decline

Do not log sensitive page content by default.

---

## Implementation Phases

### Phase 1 — Search reliability first
- Route Eidos public web access through provider-hosted web tools instead of app-executed search/fetch functions.
- Preserve source links/citations in chat responses.
- For URL-level analysis, use hosted provider fetch/browsing when available and provide URL links for user navigation when it is not.
- Avoid generic "tool is down" behavior when links or partial data exist.

### Phase 2 — Web panel MVP
- add Web panel surface
- add URL open path from Eidos/user action
- add loading/error UI
- add fallback modal (Open in Browser / Cancel)
- apply cookie/security baseline
- include both access points:
  - editor-level Notes/Files/Web panel
  - quick-access Web panel via widget

### Phase 3 — UX polish
- address bar + editable URL
- refresh/back/forward controls
- better error language and retry affordances

### Phase 4 — Advanced browsing (only if justified)
- multi-tab support
- persistent web references/bookmarks
- download/upload handling
- site permissions UI

---

## Acceptance Criteria (MVP)

MVP is complete when all are true:

1. A valid `https` URL opens in-app and renders live page content.
2. Standard navigation controls (back/forward/reload) function.
3. Incompatible or failed pages show an external-browser fallback prompt.
4. User can accept or decline fallback without breaking app flow.
5. Cookies/storage behavior supports normal modern website login/session flows.
6. No silent sanitization pipeline replaces page rendering.
7. Web panel can be opened from both:
   - editor context (with Notes/Files)
   - widget quick-access context.

---

## Non-Goals (Current)

- Competing with full commercial browsers
- Implementing extension ecosystems
- Building custom HTML/JS panel runtime in this feature

Those remain separate tracks.
