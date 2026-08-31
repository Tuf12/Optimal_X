package com.example.optimalx.data.litert

import android.content.Context
import com.example.optimalx.data.eidos.EidosHistoryTrimmer
import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosToolDefinition
import com.example.optimalx.data.eidos.EidosToolCatalog
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.first

/**
 * Transport policy for on-device Gemma 4 E4B — see [app/docs/GemmaLocal.md].
 *
 * ## Tuning the context window (change numbers here)
 *
 * LiteRT [com.google.ai.edge.litertlm.EngineConfig.maxNumTokens] **pre-allocates** the KV
 * cache at [com.google.ai.edge.litertlm.Engine.initialize] — bigger window = more RAM at
 * init, before any chat. Upstream Gemma 4 E4B is trained up to ~128k; the `.litertlm` we
 * ship is treated as having a practical LiteRT ceiling around [MODEL_MAX_NUM_TOKENS].
 *
 * Observed on S25:
 * - **4096** — Gallery-class, always safe (previous default).
 * - **8192** — current default; validate init + a tool turn (`search_semantic`) on device.
 * - **32768** — OOM / process kill at init (do not use as mobile default).
 *
 * Suggested ladder when probing a phone: 4096 → 8192 → 12288 → 16384. After each bump,
 * cold-start the local provider and confirm init succeeds, then run a tool-using chat.
 * If init OOMs, drop back one step. If init works but chat fails with
 * "Input token ids are too long … N >= MAX_NUM_TOKENS", the window is fine but outbound
 * trim is too loose — lower [MAX_OUTBOUND_CHARS] / [MAX_USER_EXCHANGES], or raise
 * [MAX_NUM_TOKENS] further if RAM allows.
 *
 * [MAX_NUM_TOKENS] is **input + output combined** (system, history, tool schemas/results,
 * and the model reply all share one budget).
 */
object GemmaLocalPolicy {

    val TOOL_NAMES: List<String> = listOf(
        "search_semantic",
        "create_parent_folder",
        "create_subfolder",
        "write_note",
    )

    /**
     * LiteRT KV / context length passed to [com.google.ai.edge.litertlm.EngineConfig].
     *
     * Change this first when testing how large a window the phone can allocate.
     * Must rebuild/reinstall so the engine reinits with the new value (warm engine
     * keeps the old [maxNumTokens] until reload).
     */
    const val MAX_NUM_TOKENS: Int = 12_192

    /**
     * Documented LiteRT package ceiling for our `.litertlm` — **not** a runtime default.
     * Full allocation OOMs on phone. Upstream architecture may claim 128k; do not set
     * [MAX_NUM_TOKENS] to this (or 128k) without proving init on-device.
     */
    const val MODEL_MAX_NUM_TOKENS: Int = 32_768

    /** Target system prompt size (slim compose); not a hard truncate yet. */
    const val MAX_SYSTEM_PROMPT_CHARS: Int = 2_500

    /**
     * Soft cap on user-led exchanges kept on the wire before the char budget binds.
     * Raise with [MAX_NUM_TOKENS] if you want longer verbatim threads locally.
     */
    const val MAX_USER_EXCHANGES: Int = 6

    /**
     * Verbatim outbound history char budget (lossy trim of oldest exchanges).
     *
     * Sized for [MAX_NUM_TOKENS] with headroom for system prompt, tool JSON schemas,
     * tool results (e.g. `search_semantic`), and decode — ~4 chars/token heuristic,
     * history ≈ 50% of the window so system + current user + decode still fit:
     * `MAX_OUTBOUND_CHARS ≈ (MAX_NUM_TOKENS * 0.50) * 3` (conservative Gemma tokens).
     * The previous 24k / 12-exchange cap overflowed the 8192 KV window after a few
     * turns and the model degenerated (repeated "the", off-topic replies).
     *
     * When you change [MAX_NUM_TOKENS], rescale this roughly the same way, or tool
     * turns will still hit "input token ids are too long" even though the engine
     * window grew. Char trim is approximate (not a real tokenizer).
     */
    const val MAX_OUTBOUND_CHARS: Int = 12_000

    /**
     * Local Gemma scribe: continuous mic capture, rolling fixed-duration WAV slices
     * (no VAD / no mic restart). Slices reuse one conversation for the recording
     * session. Tune with [MAX_NUM_TOKENS] — longer slices need more audio headroom.
     */
    const val SCRIBE_SLICE_SECONDS: Float = 8f

    /** Soft overlap between consecutive slices so words at boundaries are not clipped. */
    const val SCRIBE_SLICE_OVERLAP_SECONDS: Float = 0.5f

    /** On stop, skip leftover tails shorter than this (already covered by prior overlap). */
    const val SCRIBE_MIN_FLUSH_SECONDS: Float = 0.4f

    val HISTORY_BUDGET: EidosHistoryTrimmer.HistoryBudget = EidosHistoryTrimmer.HistoryBudget(
        maxUserExchanges = MAX_USER_EXCHANGES,
        maxChars = MAX_OUTBOUND_CHARS,
    )

    fun toolDefinitions(): List<EidosToolDefinition> =
        EidosToolCatalog.toolsByNames(*TOOL_NAMES.toTypedArray())

    /**
     * Tools for a local send: full allowlist when [toolsEnabled], else empty
     * (chat-only mode — model must not receive tool schemas).
     */
    fun toolDefinitions(toolsEnabled: Boolean): List<EidosToolDefinition> =
        if (toolsEnabled) toolDefinitions() else emptyList()

    fun trimOutboundHistory(messages: List<EidosMessage>): List<EidosMessage> =
        EidosHistoryTrimmer.trimHistoryIfNeeded(messages, HISTORY_BUDGET)

    suspend fun isActiveProvider(context: Context): Boolean {
        val app = context.applicationContext
        val encrypted = getEncryptedPrefs(app).getString(EncryptedSettingKeys.ACTIVE_PROVIDER, null)
        val prefs = app.settingsDataStore.data.first()[SettingsKeys.ACTIVE_PROVIDER]
        val active = encrypted ?: prefs ?: SettingsDefaults.ACTIVE_PROVIDER
        return active == LitertLmDefaults.PROVIDER_ID
    }

    /** Persisted tools toggle (default on). Only meaningful when local Gemma is active. */
    suspend fun toolsEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.settingsDataStore.data.first()
        return prefs[SettingsKeys.LOCAL_GEMMA_TOOLS_ENABLED]
            ?: SettingsDefaults.LOCAL_GEMMA_TOOLS_ENABLED
    }
}
