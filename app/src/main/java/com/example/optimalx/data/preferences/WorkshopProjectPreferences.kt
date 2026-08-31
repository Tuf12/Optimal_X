package com.example.optimalx.data.preferences

import android.content.Context
import com.example.optimalx.data.eidos.WorkshopBuildKickoff
import com.example.optimalx.data.eidos.WorkshopDocAlignScope
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.eidos.WorkshopUpdateSection

/** Per workshop subfolder: lifecycle phase, Eidos mode, and v1 migration flags. */
object WorkshopProjectPreferences {
    private const val PREFS_NAME = "workshop_project_state"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun initialKey(subfolderId: Long) = "workshop_${subfolderId}_initial_build_sent"

    private fun docDigestKey(subfolderId: Long) = "workshop_${subfolderId}_doc_digest_at_code_sync"

    private fun alignFingerprintKey(subfolderId: Long, scope: WorkshopDocAlignScope) =
        "workshop_${subfolderId}_align_fp_${scope.name}"

    private fun alignSpecFingerprintsKey(subfolderId: Long, scope: WorkshopDocAlignScope) =
        "workshop_${subfolderId}_align_specfp_${scope.name}"

    /** SHA-256 of design runtime files captured when Build design was kicked off. */
    private fun designBuildBaselineKey(subfolderId: Long) =
        "workshop_${subfolderId}_design_build_baseline"

    /** SHA-256 of logic runtime files captured when Build logic was kicked off. */
    private fun logicBuildBaselineKey(subfolderId: Long) =
        "workshop_${subfolderId}_logic_build_baseline"

    private fun eidosModeKey(subfolderId: Long) = "workshop_${subfolderId}_eidos_mode"

    /** Active one-shot Build design / Build logic kickoff (cleared after send). */
    private fun buildKickoffKey(subfolderId: Long) = "workshop_${subfolderId}_build_kickoff"

    private fun projectPhaseKey(subfolderId: Long) = "workshop_${subfolderId}_project_phase"

    private fun intakeSummaryKey(subfolderId: Long) = "workshop_${subfolderId}_intake_summary"

    private fun updateSectionKey(subfolderId: Long) = "workshop_${subfolderId}_update_section"

    private fun pendingDesignReviewKey(subfolderId: Long) =
        "workshop_${subfolderId}_pending_design_review_after_build"

    private fun designLayoutReadyKey(subfolderId: Long) = "workshop_${subfolderId}_design_layout_ready"

    private fun pendingLogicReviewKey(subfolderId: Long) =
        "workshop_${subfolderId}_pending_logic_review_after_build"

    private fun logicBehaviorReadyKey(subfolderId: Long) =
        "workshop_${subfolderId}_logic_behavior_ready"

    /** User confirmed Accept update; return to COMPLETE once review queue is clear and doc align finished. */
    private fun pendingFinishUpdateKey(subfolderId: Long) =
        "workshop_${subfolderId}_pending_finish_update"

    /** UPDATE Design/Logic: doc-align kickoff sent; wait for send + spec review before finishing. */
    private fun pendingUpdateAwaitingAlignKey(subfolderId: Long) =
        "workshop_${subfolderId}_pending_update_awaiting_align"

    /** UPDATE Design/Logic: code→spec align finished at least once this accept flow. */
    private fun pendingUpdateDocAlignDoneKey(subfolderId: Long) =
        "workshop_${subfolderId}_pending_update_doc_align_done"

    /** Epoch ms when runtime files were last copied to `panel_releases/{subfolderId}/`. */
    private fun publishedAtKey(subfolderId: Long) = "workshop_${subfolderId}_published_at_ms"

    /** Set when Generate specs kickoff is sent; cleared after a successful summary or failed send. */
    private fun pendingProjectSummaryAfterSpecGenerateKey(subfolderId: Long) =
        "workshop_${subfolderId}_pending_project_summary_after_spec_generate"

    /** Bounded spec digest ([WorkshopSpecMarkdown]) at last successful project summary. */
    private fun projectSummarySpecDigestKey(subfolderId: Long) =
        "workshop_${subfolderId}_project_summary_spec_digest"

    fun isInitialBuildSent(context: Context, subfolderId: Long): Boolean =
        prefs(context).getBoolean(initialKey(subfolderId), false)

    fun setInitialBuildSent(context: Context, subfolderId: Long, sent: Boolean) {
        prefs(context).edit().putBoolean(initialKey(subfolderId), sent).apply()
    }

    /** SHA-256 hex of canonical workshop .md contents at last legacy build/sync (telemetry only in v2). */
    fun getDocDigestAtLastCodeSync(context: Context, subfolderId: Long): String =
        prefs(context).getString(docDigestKey(subfolderId), null) ?: ""

    fun setDocDigestAtLastCodeSync(context: Context, subfolderId: Long, digestHex: String) {
        prefs(context).edit().putString(docDigestKey(subfolderId), digestHex).apply()
    }

    /** Runtime+spec fingerprint after last successful doc align for [scope]. */
    fun getAlignFingerprint(context: Context, subfolderId: Long, scope: WorkshopDocAlignScope): String =
        prefs(context).getString(alignFingerprintKey(subfolderId, scope), null) ?: ""

    fun setAlignFingerprint(
        context: Context,
        subfolderId: Long,
        scope: WorkshopDocAlignScope,
        fingerprint: String,
    ) {
        prefs(context).edit().putString(alignFingerprintKey(subfolderId, scope), fingerprint).apply()
    }

    /** Per-spec fingerprints (spec content + its relevant code) after the last successful [scope] align. */
    fun getAlignSpecFingerprints(
        context: Context,
        subfolderId: Long,
        scope: WorkshopDocAlignScope,
    ): Map<String, String> {
        val raw = prefs(context).getString(alignSpecFingerprintsKey(subfolderId, scope), null)
            ?: return emptyMap()
        return raw.split('\n').mapNotNull { line ->
            val idx = line.indexOf('\t')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
    }

    fun setAlignSpecFingerprints(
        context: Context,
        subfolderId: Long,
        scope: WorkshopDocAlignScope,
        fingerprints: Map<String, String>,
    ) {
        val raw = fingerprints.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        prefs(context).edit().putString(alignSpecFingerprintsKey(subfolderId, scope), raw).apply()
    }

    /** Runtime digest snapshot captured when Build design was kicked off (empty = none). */
    fun getDesignBuildBaseline(context: Context, subfolderId: Long): String =
        prefs(context).getString(designBuildBaselineKey(subfolderId), null) ?: ""

    fun setDesignBuildBaseline(context: Context, subfolderId: Long, digestHex: String) {
        prefs(context).edit().putString(designBuildBaselineKey(subfolderId), digestHex).apply()
    }

    /** Runtime digest snapshot captured when Build logic was kicked off (empty = none). */
    fun getLogicBuildBaseline(context: Context, subfolderId: Long): String =
        prefs(context).getString(logicBuildBaselineKey(subfolderId), null) ?: ""

    fun setLogicBuildBaseline(context: Context, subfolderId: Long, digestHex: String) {
        prefs(context).edit().putString(logicBuildBaselineKey(subfolderId), digestHex).apply()
    }

    /**
     * Current workshop lifecycle phase. Migrates v1 projects on first read:
     * [isInitialBuildSent] → [WorkshopProjectPhase.COMPLETE], else [WorkshopProjectPhase.INTAKE].
     */
    fun getProjectPhase(context: Context, subfolderId: Long): WorkshopProjectPhase {
        val p = prefs(context)
        val stored = p.getString(projectPhaseKey(subfolderId), null)
        if (stored != null) {
            return WorkshopProjectPhase.fromStored(stored) ?: WorkshopProjectPhase.INTAKE
        }
        val migrated = WorkshopProjectPhase.resolveFromLegacy(
            storedPhaseName = null,
            initialBuildSent = isInitialBuildSent(context, subfolderId),
        )
        setProjectPhase(context, subfolderId, migrated)
        return migrated
    }

    fun setProjectPhase(context: Context, subfolderId: Long, phase: WorkshopProjectPhase) {
        prefs(context).edit().putString(projectPhaseKey(subfolderId), phase.name).apply()
    }

    fun getIntakeSummary(context: Context, subfolderId: Long): String =
        prefs(context).getString(intakeSummaryKey(subfolderId), null)?.trim().orEmpty()

    fun setIntakeSummary(context: Context, subfolderId: Long, summary: String) {
        val trimmed = summary.trim()
        val editor = prefs(context).edit()
        if (trimmed.isEmpty()) {
            editor.remove(intakeSummaryKey(subfolderId))
        } else {
            editor.putString(intakeSummaryKey(subfolderId), trimmed)
        }
        editor.apply()
    }

    fun getUpdateSection(context: Context, subfolderId: Long): WorkshopUpdateSection? =
        WorkshopUpdateSection.fromStored(prefs(context).getString(updateSectionKey(subfolderId), null))

    fun setUpdateSection(context: Context, subfolderId: Long, section: WorkshopUpdateSection?) {
        val editor = prefs(context).edit()
        if (section == null) {
            editor.remove(updateSectionKey(subfolderId))
        } else {
            editor.putString(updateSectionKey(subfolderId), section.name)
        }
        editor.apply()
    }

    fun getEidosModeOverride(context: Context, subfolderId: Long): WorkshopEidosMode? =
        WorkshopEidosMode.fromStored(prefs(context).getString(eidosModeKey(subfolderId), null))

    fun setEidosModeOverride(context: Context, subfolderId: Long, mode: WorkshopEidosMode?) {
        val editor = prefs(context).edit()
        if (mode == null) {
            editor.remove(eidosModeKey(subfolderId))
        } else {
            editor.putString(eidosModeKey(subfolderId), mode.name)
        }
        editor.apply()
    }

    fun getBuildKickoff(context: Context, subfolderId: Long): WorkshopBuildKickoff? =
        WorkshopBuildKickoff.fromStored(prefs(context).getString(buildKickoffKey(subfolderId), null))

    fun setBuildKickoff(context: Context, subfolderId: Long, kickoff: WorkshopBuildKickoff?) {
        val editor = prefs(context).edit()
        if (kickoff == null) {
            editor.remove(buildKickoffKey(subfolderId))
        } else {
            editor.putString(buildKickoffKey(subfolderId), kickoff.name)
        }
        editor.apply()
    }

    fun clearBuildKickoff(context: Context, subfolderId: Long) {
        setBuildKickoff(context, subfolderId, null)
    }

    fun isPendingDesignReviewAfterBuild(context: Context, subfolderId: Long): Boolean =
        prefs(context).getBoolean(pendingDesignReviewKey(subfolderId), false)

    fun setPendingDesignReviewAfterBuild(context: Context, subfolderId: Long, pending: Boolean) {
        prefs(context).edit().putBoolean(pendingDesignReviewKey(subfolderId), pending).apply()
    }

    fun isDesignLayoutReady(context: Context, subfolderId: Long): Boolean =
        prefs(context).getBoolean(designLayoutReadyKey(subfolderId), false)

    fun setDesignLayoutReady(context: Context, subfolderId: Long, ready: Boolean) {
        prefs(context).edit().putBoolean(designLayoutReadyKey(subfolderId), ready).apply()
    }

    fun isPendingLogicReviewAfterBuild(context: Context, subfolderId: Long): Boolean =
        prefs(context).getBoolean(pendingLogicReviewKey(subfolderId), false)

    fun setPendingLogicReviewAfterBuild(context: Context, subfolderId: Long, pending: Boolean) {
        prefs(context).edit().putBoolean(pendingLogicReviewKey(subfolderId), pending).apply()
    }

    fun isLogicBehaviorReady(context: Context, subfolderId: Long): Boolean =
        prefs(context).getBoolean(logicBehaviorReadyKey(subfolderId), false)

    fun setLogicBehaviorReady(context: Context, subfolderId: Long, ready: Boolean) {
        prefs(context).edit().putBoolean(logicBehaviorReadyKey(subfolderId), ready).apply()
    }

    fun isPendingFinishUpdate(context: Context, subfolderId: Long): Boolean =
        prefs(context).getBoolean(pendingFinishUpdateKey(subfolderId), false)

    fun setPendingFinishUpdate(context: Context, subfolderId: Long, pending: Boolean) {
        prefs(context).edit().putBoolean(pendingFinishUpdateKey(subfolderId), pending).apply()
    }

    fun isPendingUpdateAwaitingAlign(context: Context, subfolderId: Long): Boolean =
        prefs(context).getBoolean(pendingUpdateAwaitingAlignKey(subfolderId), false)

    fun setPendingUpdateAwaitingAlign(context: Context, subfolderId: Long, pending: Boolean) {
        prefs(context).edit().putBoolean(pendingUpdateAwaitingAlignKey(subfolderId), pending).apply()
    }

    fun isPendingUpdateDocAlignDone(context: Context, subfolderId: Long): Boolean =
        prefs(context).getBoolean(pendingUpdateDocAlignDoneKey(subfolderId), false)

    fun setPendingUpdateDocAlignDone(context: Context, subfolderId: Long, done: Boolean) {
        prefs(context).edit().putBoolean(pendingUpdateDocAlignDoneKey(subfolderId), done).apply()
    }

    fun getPublishedAtMs(context: Context, subfolderId: Long): Long =
        prefs(context).getLong(publishedAtKey(subfolderId), 0L)

    fun setPublishedAtMs(context: Context, subfolderId: Long, epochMs: Long) {
        prefs(context).edit().putLong(publishedAtKey(subfolderId), epochMs).apply()
    }

    fun clearPublishedAt(context: Context, subfolderId: Long) {
        prefs(context).edit().remove(publishedAtKey(subfolderId)).apply()
    }

    fun isPendingProjectSummaryAfterSpecGenerate(context: Context, subfolderId: Long): Boolean =
        prefs(context).getBoolean(pendingProjectSummaryAfterSpecGenerateKey(subfolderId), false)

    fun setPendingProjectSummaryAfterSpecGenerate(context: Context, subfolderId: Long, pending: Boolean) {
        prefs(context).edit()
            .putBoolean(pendingProjectSummaryAfterSpecGenerateKey(subfolderId), pending)
            .apply()
    }

    fun getProjectSummarySpecDigest(context: Context, subfolderId: Long): String =
        prefs(context).getString(projectSummarySpecDigestKey(subfolderId), null).orEmpty()

    fun setProjectSummarySpecDigest(context: Context, subfolderId: Long, digestHex: String) {
        prefs(context).edit().putString(projectSummarySpecDigestKey(subfolderId), digestHex).apply()
    }
}
