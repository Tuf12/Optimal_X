# SEARCH_AND_RETRIEVAL.md

## Purpose

This file defines how both the user and Eidos search and retrieve information inside OptimalX v2.

Coding agents should use this file to understand the full search architecture — keyword search, semantic search, the two-pass tag system, and how search applies to notes, logs, and chat history.

---

## Retrieval vs Memory — Do Not Confuse Them

The retrieval system handles search and navigation — finding notes, folders, logs, and chats.
The memory system handles continuity over time — what Eidos knows and remembers between sessions.

They work together but serve different purposes.
The memory system is defined in MEMORY_SYSTEM.md.
This file covers retrieval only.

---

## Three Search Systems

OptimalX uses three search systems that work together:

| System | Used by | How it works | When to use |
|---|---|---|---|
| Keyword search | User + Eidos | Exact text matching against the Room database | Finding by name or exact phrase |
| Tag layer | Eidos | Match query against AI-generated folder/subfolder tags | Pass 1 — prune irrelevant branches before content search |
| Semantic search | Eidos | Vector similarity via Universal Sentence Encoder | Pass 2 — find content by meaning within matched branches |

All three systems run fully on device. No API calls required for any of them.

---

## Keyword Search

### What it searches
- Parent folder names
- Subfolder names
- Note content (full text)
- File names

### How it works
- Standard SQL query against the Room database
- Matches any text containing the search term
- Fast and simple
- Results returned in order of relevance (exact name matches first, content matches second)

### User-facing search bar
- Located in the top bar on both the Parent Folder Page and Subfolder Page
- Searches folder names and note content simultaneously
- Results show the folder or subfolder name and a snippet of matching note content
- Tapping a result navigates directly to that location

### Eidos keyword search
- Eidos uses the `search_system` tool
- Can be filtered by date range
- Used when the user asks for something specific by name or exact phrase

---

## Tag Layer

### What it is
Every parent folder and subfolder has a `semanticTags` field — a small set of AI-generated descriptive tags written when the folder is created and updated when its purpose shifts.

Tags are the first pass in every semantic search. They let Eidos prune entire branches of the folder tree before touching any note content.

### How tags are generated
- Eidos generates 3–5 tags automatically when a folder or subfolder is created
- Tags are short descriptive words or phrases based on the folder name and parent context
- Example: `"construction, materials, suppliers, lumber, Johnson job"`
- Eidos regenerates tags if it determines the folder's purpose has shifted meaningfully
- Stored as a comma-separated string in the database
- No user action required — fully automatic

### Why the tag layer matters
Without tags, semantic search has to run vector similarity against every note in the system on every query. As the system grows this becomes expensive and noisy.

Tags let Eidos skip entire branches before any note content is read. A query about lifting progress skips the entire Work folder tree. A query about the Johnson job skips Fitness, Finance, Personal, and everything else. Only matched branches proceed to vector search.

This is what keeps semantic search fast as the system scales.

---

## Two-Pass Semantic Search

All semantic searches by Eidos follow a two-pass pattern.

### Pass 1 — Tag match
- Query is compared against parent folder tags
- Folders with no meaningful tag overlap are eliminated entirely
- Their subfolders and notes are never read

### Pass 2 — Vector search within matched branches
- Universal Sentence Encoder converts the query to a vector
- Vector similarity search runs only within subfolders that survived Pass 1
- Results are ranked by similarity score
- Actual note content is retrieved from Room using matching IDs

### Example
```
Query: "my lifting progress"

Pass 1 — tag match:
  /Fitness [fitness, health, goals, workout] → match → continue
  /Work [projects, clients, deadlines] → no match → skip
  /Finance [budget, expenses, invoices] → no match → skip

Pass 2 — vector search inside /Fitness only:
  /Fitness/Lifting [strength, PRs, progressive overload] → match → retrieve
  /Fitness/Nutrition [diet, macros, meal prep] → weak match → skip

Result: content from /Fitness/Lifting returned
```

The entire Work, Finance, and other branches were never touched.

### Embedding model
- **Universal Sentence Encoder** via TensorFlow Lite
- Runs fully on device — no internet, no API calls, no cost per search
- Lightweight enough for modern Android devices
- Converts text to vectors locally and privately

### What gets embedded
- All note content — embedded at creation, re-embedded on update
- File content that Eidos has read (PDFs, Word docs, text, markdown, spreadsheets, code)
- Folder and subfolder names
- Chat message content (for chat history search)
- Log entry content (for log search)

### What does not get embedded
- Timestamps, IDs, metadata — handled by keyword and date filtering
- Daily Memory, Long-Term Tag & Hint index, journal Tag & Hint index — these ride in the prompt directly and are not searched via vector

### When embeddings are generated
- Note created → embed immediately
- Note updated → re-embed updated content
- File imported and read by Eidos → embed extracted text
- Chat message written → embed for future search
- Log entry written → embed for future search

---

## How the Three Systems Work Together

| Situation | Method |
|---|---|
| User searches by exact name or phrase | Keyword search |
| Eidos searches by topic or idea | Tag pass 1 → semantic pass 2 → keyword fallback |
| Time-specific query | Date filter applied on top of either method |
| Eidos needs a specific folder by name | Keyword search |
| Journal Tag & Hint index matches a topic | `read_journal` fetches the full entry — semantic search within it finds the specific content |
| Eidos verifies a past action | `read_log` with two-pass search |
| User asks to find a past conversation | Chat history two-pass search |

### Search priority for Eidos
1. Current location context first — if the answer is already in the prompt, stop
2. Tag pass 1 to prune branches
3. Semantic pass 2 within matched branches
4. Keyword search as fallback if semantic results are weak
5. Date filtering applied on top of any method when time is relevant

---

## Log Search

Eidos Log entries are never loaded into the prompt automatically. They are searched on demand when Eidos needs to verify a past action.

### How it works
Log entries follow the same two-pass pattern as note search:
- Pass 1: date filter and action type tag narrow to the relevant day and category
- Pass 2: semantic search within matched entries finds the specific action

### When Eidos searches the log
- When it needs to verify whether something was done previously
- When the user asks what Eidos has done or changed
- When resolving a conflict or discrepancy about system state

### User-initiated log browsing
The user can browse the Eidos Log directly from the Eidos section of the app. The log is organized by date — one subfolder per day. The user can scroll, read, and delete entries without triggering a search.

---

## Chat History Search

Conversations are hierarchically structured the same way the app is — general scope, parent folder scope, and subfolder scope. The two-pass retrieval system applies to chats exactly as it does to notes.

### How it works
- Pass 1: scope determines the branch to search (general / parent folder / subfolder), tag match narrows further within that branch
- Pass 2: semantic search within matched conversations finds the specific exchange

### When chat search is triggered
- User asks to find a past conversation ("we talked about lumber pricing the other day, can you find it")
- User wants to continue a specific previous conversation they cannot locate
- Eidos is asked to reference something discussed previously that is not in current memory

### What makes chat search efficient
Conversations are scoped at creation — they already belong to a specific branch of the hierarchy. A query inside a subfolder only searches that subfolder's conversations unless the user explicitly asks to search more broadly. The tag layer on the parent folder and subfolder narrows further within scope.

### Important
Chat searches are user-initiated. Eidos does not proactively search chat history during normal sessions. Important context from past chats should already be in memory via the midnight rollover process. Full chat storage detail is in DATA_MODEL.md.

---

## Naming Structure and Retrieval

Good naming improves keyword search, tag generation, and semantic retrieval.

A subfolder named "Johnson Job — Materials List" generates better tags, embeds more meaningfully, and surfaces in keyword search more reliably than one named "List 3."

Eidos should suggest clear, descriptive names when creating folders and subfolders. The user always controls naming — but good naming makes the whole system more searchable and useful over time.

---

## Scalability

The two-pass system is designed to stay fast as the system grows.

As more folders and notes are added, the tag layer prunes more aggressively — more branches means more gets skipped before vector search runs. Semantic search only gets better with more content because there is more meaningful material to match against.

The architecture supports switching to a more sophisticated embedding model in the future. Doing so would require re-embedding all existing content but no structural database changes.
