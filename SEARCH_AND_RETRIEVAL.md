# SEARCH_AND_RETRIEVAL.md

## Purpose

This file defines how both the user and Eidos search and retrieve information inside OptimalX v2.

Coding agents should use this file to understand the full search architecture including keyword search, semantic search, and how both systems work together.

---

## Two Search Systems

OptimalX uses two search systems that work alongside each other:

| System | Used by | How it works | When to use |
|---|---|---|---|
| Keyword search | User + Eidos | Exact text matching | Finding folders and notes by name |
| Semantic search | Eidos primary | Vector similarity matching | Finding content by meaning during conversations |

Both systems run fully on device. No API calls required for either.

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

### User facing search bar
- Located in the top bar on both the Parent Folder Page and Subfolder Page
- Searches folder names and note content simultaneously
- Results show the folder or subfolder name and a snippet of matching note content
- Tapping a result navigates directly to that location

### Eidos keyword search
- Eidos uses the `search_system` tool for keyword search
- Can be filtered by date range
- Used when the user asks for something specific by name or exact phrase

---

## Semantic Search

### What it is
Semantic search finds content by meaning rather than exact words.

Example: searching "materials for the Johnson job" finds a note about concrete and lumber costs even if those exact words were not in the query. The meaning matches even if the keywords do not.

This is critical for Eidos to be useful during conversations — the user will not always use the exact words that appear in their notes.

### How it works
- Every note and file is converted into a vector (a numerical representation of its meaning) when created or updated
- Vectors are stored in a local vector store alongside the Room database
- When Eidos performs a semantic search, the query is also converted to a vector
- The system finds notes and files whose vectors are closest in meaning to the query vector
- The actual content is then retrieved from Room using the matching IDs

### Embedding model
- **Universal Sentence Encoder** via TensorFlow Lite
- Runs fully on device — no internet required, no API calls, no cost per request
- Lightweight enough to run on modern Android devices
- Converts text to vectors locally and privately

### What gets embedded
- All note content (created and updated in real time)
- File content that Eidos has read (PDFs, Word docs, text files, markdown, spreadsheets, code files)
- Folder and subfolder names for context

### What does not get embedded
- Timestamps, IDs, and metadata — these are handled by keyword and date filtering

### When embeddings are generated
- When a note is created → embed immediately
- When a note is updated → re-embed the updated content
- When a file is imported and read by Eidos → embed the extracted text
- Embeddings are always kept in sync with the current note content

---

## How the Two Systems Work Together

Eidos uses both systems in combination depending on the situation:

| Situation | Search method |
|---|---|
| User asks about something by exact name | Keyword search |
| User asks about a topic or idea | Semantic search first, keyword as fallback |
| User asks for notes from a specific date | Date filter applied to either system |
| Eidos is rebuilding context from journal | Semantic search on journal entries |
| Eidos needs to find a specific folder | Keyword search by folder name |

### Search priority for Eidos
1. Semantic search for meaning
2. Keyword search as fallback if semantic results are weak
3. Date filtering applied on top of either method when time context is relevant

---

## Naming Structure and Retrieval

Good naming improves both keyword and semantic retrieval significantly.

Folder and subfolder names act as context anchors.
A subfolder named "Johnson Job — Materials List" is easier for both the user and Eidos to find than one named "List 3."

Eidos should be designed to suggest clear, descriptive names when creating folders and subfolders.
This is not a hard rule — the user always controls naming — but good naming makes the whole system more useful over time.

---

## Journal and Log Search

The Eidos Journal and Eidos Log are searchable by Eidos using the same tools.

- `read_journal` supports keyword and date range filtering
- `read_log` supports keyword and date range filtering
- Eidos does not load the entire journal or log into context each session
- It retrieves only what is relevant to the current session or query

This keeps context usage efficient as the journal and log grow over time.

---

## Future Considerations

The vector store and embedding system are designed to scale.

As the note and file system grows, semantic search becomes more powerful — more content means better retrieval.
The architecture supports adding more sophisticated embedding models in the future if device capability improves or a better lightweight model becomes available.
Switching embedding models would require re-embedding existing content but no structural changes to the database.