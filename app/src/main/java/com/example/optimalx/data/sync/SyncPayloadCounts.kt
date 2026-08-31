package com.example.optimalx.data.sync

fun SyncTablesPayload.totalRows(): Int =
    parentFolders.size +
        subfolders.size +
        notes.size +
        fileReferences.size +
        customPanelAssignments.size +
        conversations.size +
        chatMessages.size +
        contentCheckpoints.size +
        contentPatches.size +
        pendingChangeSets.size +
        pendingChangeItems.size +
        panelState.size +
        (if (dumpEdit != null) 1 else 0)

fun SyncTablesPayload.summaryLabel(): String = buildString {
    append("received ")
    append(totalRows())
    append(" rows (")
    append(
        listOf(
            "parents=${parentFolders.size}",
            "subfolders=${subfolders.size}",
            "notes=${notes.size}",
            "files=${fileReferences.size}",
            "conversations=${conversations.size}",
            "messages=${chatMessages.size}",
            "panelState=${panelState.size}",
            if (dumpEdit != null) "dumpEdit=1" else "dumpEdit=0",
        ).joinToString(", "),
    )
    append(")")
}
