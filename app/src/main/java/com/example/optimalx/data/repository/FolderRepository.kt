package com.example.optimalx.data.repository

import androidx.room.withTransaction
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.panel.PanelReleaseStore
import com.example.optimalx.data.eidos.PanelPlatformSpec
import com.example.optimalx.data.eidos.WorkshopAndroidLayoutRules
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.eidos.AppIndexSyncService
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.quicknotes.QuickNotesFormat
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticIndexer
import com.example.optimalx.data.semantic.SemanticSyncService
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SearchResult(
    val id: Long,
    val name: String,
    val isSubfolder: Boolean,
    val parentFolderId: Long,
    val parentFolderName: String,
    val noteSnippet: String,
)

class FolderRepository(
    private val db: AppDatabase,
    private val semanticIndexer: SemanticIndexer? = null,
    private val appIndexSync: AppIndexSyncService? = null,
    private val semanticSync: SemanticSyncService? = null,
    private val semanticChunkBuilder: SemanticChunkBuilder? = null,
    private val homePinRepository: HomePinRepository? = null,
    private val panelStateRepository: PanelStateRepository? = null,
) {

    companion object {
        val WORKSHOP_SPEC_MD_FILES: List<Pair<String, String>> = listOf(
            "STRUCTURE.md" to """
                # Structure

                List each project file and what it does. Layout rules for Android WebView are in DESIGN.md.
                """.trimIndent(),
            "FEATURES.md" to """
                # Features

                One section per feature.

                ## Persistence

                (Required if users can change data — scores, forms, game progress.)

                - **What is saved:**
                - **When it saves:** (e.g. after each action, debounced on input, on background)
                - **Scope:** Panel Gallery uses one global save per panel; each editor subfolder tab has its own save when pinned here.
                - **Implementation:** `panelGetState` / `panelRestoreState` in script.js — Kotlin stores opaque JSON only.
                """.trimIndent(),
            "FLOW.md" to """
                # Flow

                User interaction flow — what happens when each control is used.
                """.trimIndent(),
            "DESIGN.md" to """
                # Design

                ${PanelPlatformSpec.designMarkdownSection()}
                """.trimIndent(),
        )

        // Canvas attribute size for scaffold (Galaxy-friendly portrait game board).
        private const val SCAFFOLD_CANVAS_WIDTH = 360
        private const val SCAFFOLD_CANVAS_HEIGHT = 640

        val WORKSHOP_INDEX_HTML_SCAFFOLD: String = """
            <!doctype html>
            <!-- ${WorkshopAndroidLayoutRules.HTML_FILE_HEADER_COMMENT} -->
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no" />
              <title>{{TITLE}}</title>
              <link rel="stylesheet" href="./style.css" />
            </head>
            <body>
              <main class="panel">
                <section class="game-shell">
                  <h1 class="panel-title">{{TITLE}}</h1>
                  <!-- Internal resolution: set width/height attributes; CSS aspect-ratio must match -->
                  <canvas
                    id="gameCanvas"
                    width="$SCAFFOLD_CANVAS_WIDTH"
                    height="$SCAFFOLD_CANVAS_HEIGHT"
                    aria-label="Panel canvas"
                  ></canvas>
                  <!-- Controls belong below the canvas, not inside the canvas wrapper -->
                  <section class="controls">
                    <label class="field-label" for="noteField">Note (debounced save)</label>
                    <input
                      id="noteField"
                      type="text"
                      class="note-field"
                      placeholder="Type here — saves after a short pause"
                      autocomplete="off"
                    />
                    <button id="pingBtn" type="button">Run Action: ping</button>
                  </section>
                  <pre id="output" class="debug-output" aria-live="polite"></pre>
                </section>
              </main>
              <script src="./bridge.js"></script>
              <script src="./script.js"></script>
            </body>
            </html>
            """

        val WORKSHOP_STYLE_CSS_SCAFFOLD: String = """
            /* ${WorkshopAndroidLayoutRules.CSS_FILE_HEADER_COMMENT} */

            :root {
              color-scheme: light;
              --canvas-w: $SCAFFOLD_CANVAS_WIDTH;
              --canvas-h: $SCAFFOLD_CANVAS_HEIGHT;
            }

            * {
              box-sizing: border-box;
            }

            body {
              margin: 0;
              min-height: 100vh;
              display: flex;
              flex-direction: column;
              align-items: center;
              justify-content: flex-start;
              font-family: "DM Sans", sans-serif;
              background: linear-gradient(160deg, #f4f8ff 0%, #eef7ef 100%);
              color: #1e293b;
            }

            .panel {
              width: 100%;
              display: flex;
              flex-direction: column;
              align-items: center;
              padding: 12px 0 24px;
            }

            .game-shell {
              display: flex;
              flex-direction: column;
              align-items: stretch;
              gap: 10px;
              width: 100%;
              max-width: calc(var(--canvas-w) * 1px + 24px);
              padding: 12px;
            }

            .panel-title {
              margin: 0;
              font-size: 1.125rem;
              font-weight: 700;
              text-align: center;
            }

            #gameCanvas {
              display: block;
              width: 100%;
              height: auto;
              aspect-ratio: var(--canvas-w) / var(--canvas-h);
              border-radius: 12px;
              background: #0f172a;
            }

            .controls {
              display: flex;
              flex-direction: column;
              flex-wrap: wrap;
              gap: 10px;
              width: 100%;
            }
            .field-label {
              font-size: 0.875rem;
              color: #475569;
            }
            .note-field {
              width: 100%;
              min-height: 48px;
              padding: 10px 12px;
              font-size: 1rem;
              border-radius: 10px;
              border: 1px solid #cbd5e1;
              touch-action: manipulation;
            }

            .controls button {
              border: 0;
              border-radius: 12px;
              min-height: 56px;
              min-width: 56px;
              padding: 12px 18px;
              font-weight: 600;
              font-size: 1rem;
              background: #0f766e;
              color: #fff;
              touch-action: manipulation;
            }

            .debug-output {
              margin: 0;
              padding: 10px;
              min-height: 48px;
              border-radius: 10px;
              background: #f8fafc;
              border: 1px solid #e2e8f0;
              white-space: pre-wrap;
              font-size: 0.8125rem;
            }
            """

        /** Eidos/Android panel bridge — local WebView only; no network. Loaded before script.js. */
        const val WORKSHOP_BRIDGE_JS_SCAFFOLD = """
            /**
             * bridge.js — OptimalX ↔ panel runtime (local WebView only, not network).
             *
             * Loaded before script.js. Exposes global getState/runAction for Eidos call_panel_function.
             * Do not put game/UI logic here — use script.js and optionally wire:
             *   window.panelGetState(args)   → return JSON-serializable panel state
             *   window.panelHandleAction(args) → handle { action: "..." } from Eidos; return { ok, ... }
             *
             * For panels that need no Eidos control, leave those hooks undefined; bridge stays idle.
             *
             * Persistence (when panelStateScopeKey is set on Android):
             *   persistPanelStateDebounced(ms) — call from script.js after user edits (default 800ms)
             *   flushPersistedPanelState() — immediate save (also on tab hide / pagehide)
             *   runAction also saves when panelGetState exists (safety net)
             */

            const DEFAULT_PERSIST_DELAY_MS = 800;
            let _persistSaveTimer = null;

            async function persistPanelStateNow() {
              if (!window.OptimalXPanelBridge || !window.OptimalXPanelBridge.savePersistedState) return;
              if (typeof window.panelGetState !== "function") return;
              try {
                const snapshot = await window.panelGetState({});
                window.OptimalXPanelBridge.savePersistedState(JSON.stringify(snapshot));
              } catch (_e) {}
            }

            function persistPanelStateDebounced(delayMs) {
              if (typeof window.panelGetState !== "function") return;
              const ms = typeof delayMs === "number" && delayMs >= 0 ? delayMs : DEFAULT_PERSIST_DELAY_MS;
              if (_persistSaveTimer) clearTimeout(_persistSaveTimer);
              _persistSaveTimer = setTimeout(function () {
                _persistSaveTimer = null;
                persistPanelStateNow();
              }, ms);
            }

            function flushPersistedPanelState() {
              if (_persistSaveTimer) {
                clearTimeout(_persistSaveTimer);
                _persistSaveTimer = null;
              }
              persistPanelStateNow();
            }

            window.persistPanelStateNow = persistPanelStateNow;
            window.persistPanelStateDebounced = persistPanelStateDebounced;
            window.flushPersistedPanelState = flushPersistedPanelState;

            function registerBridgeTools() {
              if (!window.OptimalXPanelBridge || !window.OptimalXPanelBridge.registerTools) return;
              window.OptimalXPanelBridge.registerTools([
                { name: "getState", description: "Read current panel state", isMutating: false },
                { name: "runAction", description: "Run an action in panel runtime", isMutating: true },
              ]);
            }

            function emitBridgeEvent(name, payload) {
              if (!window.OptimalXPanelBridge || !window.OptimalXPanelBridge.emitEvent) return;
              window.OptimalXPanelBridge.emitEvent(name, payload);
            }

            async function getState(args = {}) {
              if (typeof window.panelGetState === "function") {
                return window.panelGetState(args);
              }
              return { status: "idle", argsEcho: args };
            }

            async function runAction(args = {}) {
              let result;
              if (typeof window.panelHandleAction === "function") {
                result = await window.panelHandleAction(args);
              } else {
                result = { ok: false, error: "no_panel_handler" };
              }
              await persistPanelStateNow();
              return result;
            }

            async function restorePersistedStateIfAvailable() {
              if (!window.OptimalXPanelBridge || !window.OptimalXPanelBridge.loadPersistedState) return;
              try {
                const raw = window.OptimalXPanelBridge.loadPersistedState();
                if (!raw || raw === "{}") return;
                const state = JSON.parse(raw);
                if (typeof window.panelRestoreState === "function") {
                  window.panelRestoreState(state);
                } else if (typeof window.panelHandleAction === "function") {
                  await window.panelHandleAction({ action: "__restoreState", state: state });
                }
              } catch (_e) {}
            }

            window.registerBridgeTools = registerBridgeTools;

            function bootPanelBridge() {
              registerBridgeTools();
              emitBridgeEvent("panel_loaded", {});
              restorePersistedStateIfAvailable();
            }

            window.bootPanelBridge = bootPanelBridge;

            function registerPersistLifecycleHooks() {
              document.addEventListener("visibilitychange", function () {
                if (document.visibilityState === "hidden") flushPersistedPanelState();
              });
              window.addEventListener("pagehide", function () {
                flushPersistedPanelState();
              });
            }
            registerPersistLifecycleHooks();

            window.addEventListener("optimalx-bridge-ready", registerBridgeTools);
            window.addEventListener("optimalx-bridge-ready", restorePersistedStateIfAvailable);
            """

        /** Panel app logic — games, UI, tools. Optional hooks for bridge.js. */
        const val WORKSHOP_SCRIPT_JS_SCAFFOLD = """
            /**
             * script.js — panel app logic (UI, game rules, interactions).
             *
             * bridge.js is loaded first and owns Eidos/Android wiring (getState, runAction, emitBridgeEvent).
             * This file should NOT redefine those globals — only implement hooks if Eidos should drive the panel:
             *
             *   panelGetState(args)       — snapshot for persistence + Eidos
             *   panelRestoreState(state)  — apply saved blob on load (preferred)
             *   panelHandleAction(args)   — Eidos actions; __restoreState fallback if no panelRestoreState
             *
             * After user-mutable UI changes, call persistPanelStateDebounced() from bridge.js
             * (do not save on every keystroke without debounce).
             *
             * At the end, bootPanelBridge() registers tools with Android. Use emitBridgeEvent() only
             * when you need to notify Eidos of UI events (defined in bridge.js).
             *
             * Games: use a <canvas> with width/height attributes; scale in CSS via aspect-ratio (see DESIGN.md).
             * Map pointer/touch coordinates from the displayed canvas rect, not CSS pixels alone.
             */

            const panelState = {
              version: 1,
              status: "ready",
              counter: 0,
              note: "",
              lastAction: null,
              createdAt: Date.now(),
            };

            const outputEl = document.getElementById("output");
            const gameCanvas = document.getElementById("gameCanvas");
            const noteField = document.getElementById("noteField");

            function paintScaffoldCanvas() {
              if (!(gameCanvas instanceof HTMLCanvasElement)) return;
              const ctx = gameCanvas.getContext("2d");
              if (!ctx) return;
              ctx.fillStyle = "#0f172a";
              ctx.fillRect(0, 0, gameCanvas.width, gameCanvas.height);
              ctx.fillStyle = "#94a3b8";
              ctx.font = "16px sans-serif";
              ctx.textAlign = "center";
              ctx.fillText("Canvas ready", gameCanvas.width / 2, gameCanvas.height / 2);
            }

            function renderOutput(payload) {
              if (!outputEl) return;
              outputEl.textContent = typeof payload === "string"
                ? payload
                : JSON.stringify(payload, null, 2);
            }

            // --- Eidos hooks (optional): bridge.js forwards getState/runAction here ---

            function syncNoteFieldFromState() {
              if (noteField instanceof HTMLInputElement) {
                noteField.value = String(panelState.note || "");
              }
            }

            window.panelGetState = function panelGetState(args = {}) {
              if (noteField instanceof HTMLInputElement) {
                panelState.note = noteField.value;
              }
              return { ...panelState, argsEcho: args };
            };

            window.panelRestoreState = function panelRestoreState(state) {
              const saved = state && typeof state === "object" ? state : {};
              Object.assign(panelState, saved);
              syncNoteFieldFromState();
              paintScaffoldCanvas();
              renderOutput({ ok: true, restored: true, state: { ...panelState } });
            };

            window.panelHandleAction = async function panelHandleAction(args = {}) {
              const action = String(args.action || "").trim();
              panelState.lastAction = { action, args, at: Date.now() };

              if (action === "__restoreState") {
                if (typeof window.panelRestoreState === "function") {
                  window.panelRestoreState(args.state);
                  return { ok: true, restored: true, state: { ...panelState } };
                }
                const saved = args.state && typeof args.state === "object" ? args.state : {};
                Object.assign(panelState, saved);
                syncNoteFieldFromState();
                renderOutput({ ok: true, restored: true, state: { ...panelState } });
                return { ok: true, restored: true, state: { ...panelState } };
              }

              panelState.counter += 1;

              if (action === "ping") {
                panelState.status = "pong";
                const result = { ok: true, message: "pong", state: { ...panelState } };
                renderOutput(result);
                return result;
              }

              if (action === "setStatus") {
                panelState.status = String(args.value || "ready");
                const result = { ok: true, message: "status-updated", state: { ...panelState } };
                renderOutput(result);
                return result;
              }

              const result = { ok: false, error: "unknown_action", state: { ...panelState } };
              renderOutput(result);
              return result;
            };

            // --- Demo UI (replace with your panel) ---

            noteField?.addEventListener("input", () => {
              if (noteField instanceof HTMLInputElement) {
                panelState.note = noteField.value;
              }
              if (typeof window.persistPanelStateDebounced === "function") {
                window.persistPanelStateDebounced();
              }
            });

            document.getElementById("pingBtn")?.addEventListener("click", async () => {
              // runAction / emitBridgeEvent are globals from bridge.js
              const result = await runAction({ action: "ping" });
              emitBridgeEvent("ui_ping_clicked", result);
              renderOutput(result);
            });

            // Register getState/runAction with Android after hooks are defined
            paintScaffoldCanvas();
            syncNoteFieldFromState();

            if (typeof window.bootPanelBridge === "function") {
              window.bootPanelBridge();
            }
            renderOutput({ message: "Panel loaded", state: panelState });
            """
    }

    // ── Parent Folders ────────────────────────────────────────────────────────

    fun getActiveParentFolders(): Flow<List<ParentFolder>> =
        db.parentFolderDao().getActiveUserFolders()

    fun getDeletedParentFolders(): Flow<List<ParentFolder>> =
        db.parentFolderDao().getDeleted()

    fun getAllActiveParentFolders(): Flow<List<ParentFolder>> =
        db.parentFolderDao().getAllActive()

    suspend fun createParentFolder(name: String): Long {
        val id = db.withTransaction {
            val id = db.parentFolderDao().insert(ParentFolder(name = name))
            val memoryCacheSubfolderId = db.subfolderDao().insert(
                Subfolder(
                    parentFolderId = id,
                    name = SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER,
                    isSystemSubfolder = true,
                    sortOrder = 9998,
                )
            )
            db.noteDao().insert(Note(subfolderId = memoryCacheSubfolderId))
            id
        }
        requestIndexAndSemanticSync("create_parent_folder:$id")
        return id
    }

    suspend fun renameParentFolder(id: Long, newName: String) {
        val folder = db.parentFolderDao().getById(id) ?: return
        db.parentFolderDao().update(folder.copy(name = newName, updatedAt = System.currentTimeMillis()))
        requestIndexAndSemanticSync("rename_parent_folder:$id")
    }

    suspend fun softDeleteParentFolder(id: Long) {
        db.withTransaction {
            val ts = System.currentTimeMillis()
            db.parentFolderDao().softDelete(id, ts)
            db.subfolderDao().softDeleteByParent(id, ts)
            db.noteDao().softDeleteByParentFolder(id, ts)
        }
        homePinRepository?.onParentFolderDeleted(id)
        requestIndexAndSemanticSync("soft_delete_parent_folder:$id")
    }

    suspend fun restoreParentFolder(id: Long) {
        db.withTransaction {
            db.parentFolderDao().restore(id)
            // Restore all subfolders that were deleted at the same time
            val subfolders = db.subfolderDao().getAllByParentOnce(id)
            subfolders.forEach { sf ->
                db.subfolderDao().restore(sf.id)
                db.noteDao().restoreBySubfolder(sf.id)
            }
        }
        requestIndexAndSemanticSync("restore_parent_folder:$id")
    }

    suspend fun permanentlyDeleteParentFolder(id: Long) {
        db.withTransaction {
            val subfolders = db.subfolderDao().getAllByParentOnce(id)
            subfolders.forEach { sf ->
                val files = db.fileReferenceDao().getBySubfolderOnce(sf.id)
                files.forEach { ref -> File(ref.filePath).delete() }
            }
            db.parentFolderDao().deleteById(id) // FK cascade deletes subfolders, notes, file refs
        }
        requestIndexAndSemanticSync("delete_parent_folder:$id")
    }

    // ── Subfolders ────────────────────────────────────────────────────────────

    fun getActiveSubfolders(parentId: Long): Flow<List<Subfolder>> =
        db.subfolderDao().getActiveByParent(parentId)

    fun getDeletedSubfolders(): Flow<List<Subfolder>> =
        db.subfolderDao().getAllDeleted()

    suspend fun createSubfolder(parentId: Long, name: String): Long {
        val subfolderId = db.withTransaction {
            val subfolderId = db.subfolderDao().insert(Subfolder(parentFolderId = parentId, name = name))
            // Auto-create the one note for this subfolder
            db.noteDao().insert(Note(subfolderId = subfolderId))
            subfolderId
        }
        requestIndexAndSemanticSync("create_subfolder:$subfolderId")
        return subfolderId
    }

    suspend fun renameSubfolder(id: Long, newName: String) {
        val sf = db.subfolderDao().getById(id) ?: return
        db.subfolderDao().update(sf.copy(name = newName, updatedAt = System.currentTimeMillis()))
        requestIndexAndSemanticSync("rename_subfolder:$id")
    }

    suspend fun moveSubfolder(subfolderId: Long, newParentId: Long) {
        val sf = db.subfolderDao().getById(subfolderId) ?: return
        db.subfolderDao().update(sf.copy(parentFolderId = newParentId, updatedAt = System.currentTimeMillis()))
        requestIndexAndSemanticSync("move_subfolder:$subfolderId")
    }

    suspend fun softDeleteSubfolder(id: Long) {
        db.withTransaction {
            val ts = System.currentTimeMillis()
            db.subfolderDao().softDelete(id, ts)
            db.noteDao().softDeleteBySubfolder(id, ts)
        }
        homePinRepository?.onSubfolderDeleted(id)
        panelStateRepository?.deleteForWorkshopProject(id)
        requestIndexAndSemanticSync("soft_delete_subfolder:$id")
    }

    suspend fun restoreSubfolder(id: Long) {
        db.withTransaction {
            db.subfolderDao().restore(id)
            db.noteDao().restoreBySubfolder(id)
        }
        requestIndexAndSemanticSync("restore_subfolder:$id")
    }

    suspend fun permanentlyDeleteSubfolder(context: android.content.Context, id: Long) {
        db.withTransaction {
            val files = db.fileReferenceDao().getBySubfolderOnce(id)
            files.forEach { ref -> File(ref.filePath).delete() }
            db.subfolderDao().deleteById(id) // FK cascade deletes notes and file refs
        }
        panelStateRepository?.deleteForWorkshopProject(id)
        PanelReleaseStore.deleteRelease(context, id)
        requestIndexAndSemanticSync("delete_subfolder:$id")
    }

    // ── Panel Workshop ─────────────────────────────────────────────────────────

    suspend fun createWorkshopProject(context: android.content.Context, parentId: Long, name: String): Long {
        val subfolderId = db.withTransaction {
            val subfolderId = db.subfolderDao().insert(Subfolder(parentFolderId = parentId, name = name))
            val workshopDir = File(context.filesDir, "workshop/$subfolderId")
            workshopDir.mkdirs()

            val readmeContent = "# README\n\n_Short project summary — generated after you discuss intake with Eidos._\n"
            val readmeFile = File(workshopDir, "README.md")
            readmeFile.writeText(readmeContent)

            db.fileReferenceDao().insert(
                FileReference(
                    subfolderId = subfolderId,
                    fileName = "README.md",
                    fileType = "md",
                    filePath = readmeFile.absolutePath,
                )
            )

            WORKSHOP_SPEC_MD_FILES.forEach { (fileName, content) ->
                val specFile = File(workshopDir, fileName)
                specFile.writeText(content)
                db.fileReferenceDao().insert(
                    FileReference(
                        subfolderId = subfolderId,
                        fileName = fileName,
                        fileType = "md",
                        filePath = specFile.absolutePath,
                    ),
                )
            }

            val implPlanFile = File(workshopDir, PanelPlatformSpec.IMPLEMENTATION_PLAN_MD)
            implPlanFile.writeText(
                """
                # Implementation plan

                _Optional — Plan mode may add phased steps, files to touch, and test notes here._
                """.trimIndent(),
            )
            db.fileReferenceDao().insert(
                FileReference(
                    subfolderId = subfolderId,
                    fileName = PanelPlatformSpec.IMPLEMENTATION_PLAN_MD,
                    fileType = "md",
                    filePath = implPlanFile.absolutePath,
                ),
            )

            val indexFile = File(workshopDir, "index.html")
            indexFile.writeText(WORKSHOP_INDEX_HTML_SCAFFOLD.replace("{{TITLE}}", name))
            db.fileReferenceDao().insert(
                FileReference(
                    subfolderId = subfolderId,
                    fileName = "index.html",
                    fileType = "html",
                    filePath = indexFile.absolutePath,
                )
            )

            val styleFile = File(workshopDir, "style.css")
            styleFile.writeText(WORKSHOP_STYLE_CSS_SCAFFOLD.trimIndent())
            db.fileReferenceDao().insert(
                FileReference(
                    subfolderId = subfolderId,
                    fileName = "style.css",
                    fileType = "css",
                    filePath = styleFile.absolutePath,
                )
            )

            val bridgeFile = File(workshopDir, "bridge.js")
            bridgeFile.writeText(WORKSHOP_BRIDGE_JS_SCAFFOLD.trimIndent())
            db.fileReferenceDao().insert(
                FileReference(
                    subfolderId = subfolderId,
                    fileName = "bridge.js",
                    fileType = "js",
                    filePath = bridgeFile.absolutePath,
                ),
            )

            val scriptFile = File(workshopDir, "script.js")
            scriptFile.writeText(WORKSHOP_SCRIPT_JS_SCAFFOLD.trimIndent())
            db.fileReferenceDao().insert(
                FileReference(
                    subfolderId = subfolderId,
                    fileName = "script.js",
                    fileType = "js",
                    filePath = scriptFile.absolutePath,
                )
            )
            subfolderId
        }
        WorkshopProjectPreferences.setProjectPhase(context, subfolderId, WorkshopProjectPhase.INTAKE)
        requestIndexAndSemanticSync("create_workshop_project:$subfolderId")
        return subfolderId
    }

    suspend fun getWorkshopParentId(): Long? =
        db.parentFolderDao().getSystemFolderByName(SystemFolderNames.PANEL_WORKSHOP)?.id

    suspend fun getQuickNotesParentId(): Long? =
        db.parentFolderDao().getSystemFolderByName(SystemFolderNames.QUICK_NOTES)?.id

    // ── Search ────────────────────────────────────────────────────────────────

    suspend fun searchAll(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<Long>() // subfolder IDs already added

        // Parent folder name matches
        db.parentFolderDao().searchActive(query).forEach { pf ->
            results.add(SearchResult(pf.id, pf.name, false, 0, "", ""))
        }

        // Subfolder name matches
        db.subfolderDao().searchActive(query).forEach { sf ->
            seen.add(sf.id)
            val parent = db.parentFolderDao().getById(sf.parentFolderId)
            val snippet = db.noteDao().getBySubfolderOnce(sf.id)?.content?.take(100) ?: ""
            results.add(SearchResult(sf.id, sf.name, true, sf.parentFolderId, parent?.name ?: "", snippet))
        }

        // Note content matches (subfolders not already matched by name)
        db.noteDao().searchContent(query).forEach { note ->
            if (note.subfolderId in seen) return@forEach
            val sf = db.subfolderDao().getById(note.subfolderId) ?: return@forEach
            if (sf.deletedAt != null || sf.isSystemSubfolder) return@forEach
            seen.add(sf.id)
            val parent = db.parentFolderDao().getById(sf.parentFolderId)
            val idx = note.content.indexOf(query, ignoreCase = true)
            val snippet = if (idx >= 0) {
                note.content.substring(maxOf(0, idx - 20), minOf(note.content.length, idx + 80)).trim()
            } else note.content.take(100)
            results.add(SearchResult(sf.id, sf.name, true, sf.parentFolderId, parent?.name ?: "", "...${snippet}..."))
        }

        return results
    }

    // ── Eidos system folder access ────────────────────────────────────────────

    suspend fun getSystemParentFolderByName(name: String): ParentFolder? =
        db.parentFolderDao().getSystemFolderByName(name)

    suspend fun getParentFolderById(id: Long): ParentFolder? =
        db.parentFolderDao().getById(id)

    suspend fun getSubfolderById(id: Long): Subfolder? =
        db.subfolderDao().getById(id)

    // ── Quick Notes inbox ─────────────────────────────────────────────────────

    suspend fun ensureQuickNotesDaySubfolder(quickNotesParentId: Long, date: LocalDate): Long {
        val name = date.toString()
        db.subfolderDao().getActiveByParentAndName(quickNotesParentId, name)?.id?.let { return it }
        val sid = db.withTransaction {
            val siblings = db.subfolderDao().getAllByParentOnce(quickNotesParentId)
            val sortOrder = (siblings.maxOfOrNull { it.sortOrder } ?: 0) + 1
            val now = System.currentTimeMillis()
            val sid = db.subfolderDao().insert(
                Subfolder(
                    parentFolderId = quickNotesParentId,
                    name = name,
                    isSystemSubfolder = false,
                    sortOrder = sortOrder,
                    updatedAt = now,
                ),
            )
            db.noteDao().insert(Note(subfolderId = sid, updatedAt = now))
            sid
        }
        requestIndexAndSemanticSync("create_quick_notes_day_subfolder:$sid")
        return sid
    }

    /** When Quick Notes has no visible day folders yet, create today so the inbox is reachable. */
    suspend fun ensureQuickNotesShowsTodayWhenEmpty(quickNotesParentId: Long) {
        val parent = db.parentFolderDao().getById(quickNotesParentId) ?: return
        if (parent.name != SystemFolderNames.QUICK_NOTES) return
        val hasVisibleDay = db.subfolderDao().getAllByParentOnce(quickNotesParentId)
            .any { it.deletedAt == null && !it.isSystemSubfolder }
        if (hasVisibleDay) return
        ensureQuickNotesDaySubfolder(quickNotesParentId, LocalDate.now(ZoneId.systemDefault()))
    }

    suspend fun saveQuickNoteFullContent(subfolderId: Long, content: String) {
        val note = db.noteDao().getBySubfolderOnce(subfolderId) ?: return
        val now = System.currentTimeMillis()
        db.noteDao().update(note.copy(content = content, updatedAt = now))
        semanticIndexer?.let { indexer ->
            semanticChunkBuilder?.indexNote(indexer, subfolderId)
        }
        requestIndexAndSemanticSync("save_quick_note_full_content:$subfolderId")
    }

    suspend fun appendQuickNoteLine(subfolderId: Long, body: String, atMillis: Long) {
        val note = db.noteDao().getBySubfolderOnce(subfolderId) ?: return
        val label = QuickNotesFormat.timeLabelForMillis(atMillis)
        val newContent = QuickNotesFormat.appendBlock(note.content, label, body)
        saveQuickNoteFullContent(subfolderId, newContent)
    }

    /** Resolves the dated Quick Notes subfolder for [timestampMillis] (system default zone). */
    suspend fun resolveQuickNotesDaySubfolderId(timestampMillis: Long = System.currentTimeMillis()): Long? {
        val parent = getSystemParentFolderByName(SystemFolderNames.QUICK_NOTES) ?: return null
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        return ensureQuickNotesDaySubfolder(parent.id, date)
    }

    /** Used by Eidos `write_quick_note` and widget pipeline later. */
    suspend fun appendQuickNoteFromTool(rawContent: String, timestampMillis: Long): Result<Long> {
        val parent = getSystemParentFolderByName(SystemFolderNames.QUICK_NOTES)
            ?: return Result.failure(IllegalStateException("Quick Notes system folder not found"))
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        val sid = ensureQuickNotesDaySubfolder(parent.id, date)
        appendQuickNoteLine(sid, rawContent, timestampMillis)
        return Result.success(sid)
    }

    private fun requestIndexAndSemanticSync(reason: String) {
        appIndexSync?.requestSync(reason)
        semanticSync?.requestSync(reason)
    }
}
