package com.example.optimalx.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorSyncPolicyTest {

    @Test
    fun shouldReloadExternalWrite_whenNotEditedAndDbNewerAndContentChanged() {
        assertTrue(
            NoteEditorSyncPolicy.shouldReloadExternalWrite(
                userHasEdited = false,
                dbUpdatedAt = 200L,
                lastLoadedUpdatedAt = 100L,
                dbContent = "Eidos cleaned up",
                lastPersistedContent = "messy draft",
            ),
        )
    }

    @Test
    fun shouldReloadExternalWrite_skipsWhenUserHasEdited() {
        assertFalse(
            NoteEditorSyncPolicy.shouldReloadExternalWrite(
                userHasEdited = true,
                dbUpdatedAt = 200L,
                lastLoadedUpdatedAt = 100L,
                dbContent = "Eidos cleaned up",
                lastPersistedContent = "messy draft",
            ),
        )
    }

    @Test
    fun shouldReloadExternalWrite_skipsWhenDbNotNewer() {
        assertFalse(
            NoteEditorSyncPolicy.shouldReloadExternalWrite(
                userHasEdited = false,
                dbUpdatedAt = 100L,
                lastLoadedUpdatedAt = 100L,
                dbContent = "changed",
                lastPersistedContent = "old",
            ),
        )
    }

    @Test
    fun shouldPersistOnLifecycle_whenPendingChanges() {
        assertTrue(
            NoteEditorSyncPolicy.shouldPersistOnLifecycle(
                userHasEdited = true,
                hasPendingChanges = true,
            ),
        )
        assertTrue(
            NoteEditorSyncPolicy.shouldPersistOnLifecycle(
                userHasEdited = false,
                hasPendingChanges = true,
            ),
        )
        assertFalse(
            NoteEditorSyncPolicy.shouldPersistOnLifecycle(
                userHasEdited = false,
                hasPendingChanges = false,
            ),
        )
    }

    @Test
    fun shouldPersistWorkingCopy_whenContentDiffers() {
        assertTrue(
            NoteEditorSyncPolicy.shouldPersistWorkingCopy(
                content = "edited",
                lastPersistedContent = "original",
            ),
        )
        assertFalse(
            NoteEditorSyncPolicy.shouldPersistWorkingCopy(
                content = "same",
                lastPersistedContent = "same",
            ),
        )
    }

    @Test
    fun shouldTrackEditorSnapshots_onlyInEditMode() {
        assertFalse(NoteEditorSyncPolicy.shouldTrackEditorSnapshots(isEditMode = false))
        assertTrue(NoteEditorSyncPolicy.shouldTrackEditorSnapshots(isEditMode = true))
    }

    @Test
    fun shouldSkipPersistClobberingExternalWrite_whenDbNewerAndContentDiffers() {
        assertTrue(
            NoteEditorSyncPolicy.shouldSkipPersistClobberingExternalWrite(
                dbUpdatedAt = 200L,
                lastLoadedUpdatedAt = 100L,
                persistContent = "stale editor buffer",
                dbContent = "accepted diff review",
            ),
        )
    }

    @Test
    fun shouldSkipPersistClobberingExternalWrite_falseWhenPersistMatchesDb() {
        assertFalse(
            NoteEditorSyncPolicy.shouldSkipPersistClobberingExternalWrite(
                dbUpdatedAt = 200L,
                lastLoadedUpdatedAt = 100L,
                persistContent = "same",
                dbContent = "same",
            ),
        )
    }
}
