package com.example.optimalx.data.eidos

/**
 * Greenfield spec file length caps and accept-gate checks (Panel Workshop v2).
 * See app/docs/architecture/PANEL_WORKSHOP.md#spec-files
 */
object WorkshopSpecValidation {

    val CHAR_CAPS: Map<String, Int> = mapOf(
        "README.md" to 600,
        "STRUCTURE.md" to 300,
        "FEATURES.md" to 800,
        "FLOW.md" to 800,
        "DESIGN.md" to 1000,
    )

    /**
     * Fraction above [CHAR_CAPS] target that still counts as acceptable without nagging the model
     * (e.g. 0.15 → README target 600, soft max 690). Accept specs never blocks on length alone.
     */
    const val CHAR_CAP_SLACK_RATIO: Double = 0.15

    fun softMaxFor(fileName: String): Int? {
        val target = capFor(fileName) ?: return null
        return (target * (1.0 + CHAR_CAP_SLACK_RATIO)).toInt()
    }

    /** Minimum non-whitespace chars to treat a spec as user/Eidos-authored (not empty scaffold). */
    const val MIN_MEANINGFUL_CHARS: Int = 48

    private val scaffoldMarkers: Map<String, Set<String>> = mapOf(
        "README.md" to setOf(
            "generated after you discuss intake with eidos",
            "short project summary",
        ),
        "STRUCTURE.md" to setOf("list each project file"),
        "FEATURES.md" to setOf("one section per feature"),
        "FLOW.md" to setOf("user interaction flow"),
        "DESIGN.md" to setOf("layout", "visual"),
    )

    data class SpecAcceptReadiness(
        val ready: Boolean,
        val message: String,
        val capWarnings: List<String> = emptyList(),
    )

    fun capFor(fileName: String): Int? =
        CHAR_CAPS.entries.firstOrNull { it.key.equals(fileName, ignoreCase = true) }?.value

    fun validateCharCap(fileName: String, content: String): String? {
        val target = capFor(fileName) ?: return null
        val softMax = softMaxFor(fileName) ?: return null
        val len = content.trim().length
        return if (len > softMax) {
            "$fileName is $len chars (target ~$target, soft max ~$softMax — trim only if easy; do not rewrite repeatedly)"
        } else {
            null
        }
    }

    /** Prompt block: approximate targets + anti–rewrite-loop guidance for Generate specs / Plan. */
    fun specLengthGuidanceForPrompt(): String = buildString {
        appendLine("Spec length (guidance — approximate targets, not a precision task):")
        appendLine("- Aim near each target. Up to ~${(CHAR_CAP_SLACK_RATIO * 100).toInt()}% over is fine.")
        appendLine("- Write clear specs once. Do NOT rewrite files repeatedly just to shave characters or hit exact counts.")
        appendLine("- If slightly over target, move on unless the user asks to shorten.")
        appendLine("Targets:")
        CHAR_CAPS.forEach { (name, target) ->
            val soft = softMaxFor(name) ?: target
            appendLine("- $name: aim ~$target chars (acceptable up to ~$soft)")
        }
    }.trim()

    fun isMeaningfulSpecContent(fileName: String, content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.length < MIN_MEANINGFUL_CHARS) return false
        val lower = trimmed.lowercase()
        val markers = scaffoldMarkers.entries
            .firstOrNull { it.key.equals(fileName, ignoreCase = true) }
            ?.value
            ?: return true
        val markerHits = markers.count { lower.contains(it) }
        return markerHits < markers.size || trimmed.length >= MIN_MEANINGFUL_CHARS + 40
    }

    fun evaluateAcceptReadiness(specContents: Map<String, String>): SpecAcceptReadiness {
        val missing = PanelPlatformSpec.SPEC_MARKDOWN_FILES.filter { name ->
            specContents.keys.none { it.equals(name, ignoreCase = true) }
        }
        if (missing.isNotEmpty()) {
            return SpecAcceptReadiness(
                ready = false,
                message = "Missing spec files: ${missing.joinToString(", ")}. Ask Eidos in Plan mode to write them.",
            )
        }

        val notReady = PanelPlatformSpec.SPEC_MARKDOWN_FILES.filter { name ->
            val content = specContents.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                .orEmpty()
            !isMeaningfulSpecContent(name, content)
        }
        if (notReady.isNotEmpty()) {
            return SpecAcceptReadiness(
                ready = false,
                message = "Specs still look like placeholders: ${notReady.joinToString(", ")}. " +
                    "Review in Docs or ask Eidos to finish them in Plan mode.",
            )
        }

        val capWarnings = specContents.mapNotNull { (name, text) ->
            validateCharCap(name, text)
        }
        val capNote = if (capWarnings.isNotEmpty()) {
            " Some files are well above soft length targets — optional trim; Accept is still OK."
        } else {
            ""
        }
        return SpecAcceptReadiness(
            ready = true,
            message = "Specs look ready.$capNote",
            capWarnings = capWarnings,
        )
    }

    fun formatCapReport(specContents: Map<String, String>): String = buildString {
        appendLine("Spec file length (targets — not exact limits; do not rewrite only to trim):")
        PanelPlatformSpec.SPEC_MARKDOWN_FILES.forEach { name ->
            val text = specContents.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                .orEmpty()
            val target = capFor(name) ?: return@forEach
            val softMax = softMaxFor(name) ?: target
            val len = text.trim().length
            val status = when {
                len > softMax -> "above soft max"
                len > target -> "slightly over target (OK)"
                else -> "OK"
            }
            appendLine("- $name: $len chars (target ~$target, soft max ~$softMax) — $status")
        }
        val warnings = specContents.mapNotNull { (n, t) -> validateCharCap(n, t) }
        if (warnings.isNotEmpty()) {
            appendLine("Optional trim (Accept specs is still allowed):")
            warnings.forEach { appendLine("- $it") }
        }
    }.trim()
}
