package com.example.optimalx.ui.folders

/**
 * Items shown in the parent-page pinned row.
 * System slots are fixed-order; [UserPin] entries come from [home_pins] (device-local — not synced).
 */
sealed class PinnedRowItem {
    data object SystemPanels : PinnedRowItem()
    data object SystemDumpEdit : PinnedRowItem()
    data object SystemWorkshop : PinnedRowItem()
    data object SystemImageStudio : PinnedRowItem()
    data object SystemQuickNotes : PinnedRowItem()
    data object PinHintButton : PinnedRowItem()

    data class UserPin(
        val pinId: Long,
        val label: String,
        val pinType: HomePinType,
        val targetId: Long,
    ) : PinnedRowItem()
}

enum class HomePinType {
    PARENT,
    SUBFOLDER,
    PANEL,
    ;

    companion object {
        fun fromStorageKey(value: String): HomePinType? = when (value) {
            "parent" -> PARENT
            "subfolder" -> SUBFOLDER
            "panel" -> PANEL
            else -> null
        }
    }
}
