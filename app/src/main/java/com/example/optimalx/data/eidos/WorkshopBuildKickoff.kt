package com.example.optimalx.data.eidos

/**
 * Active one-shot primary build action (not a user chip).
 * Set when the user taps **Build design** / **Build logic**; cleared after the kickoff send completes.
 */
enum class WorkshopBuildKickoff {
    DESIGN,
    LOGIC,
    ;

    companion object {
        fun fromStored(value: String?): WorkshopBuildKickoff? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }
    }
}
