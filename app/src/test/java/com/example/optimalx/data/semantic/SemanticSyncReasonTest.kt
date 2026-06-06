package com.example.optimalx.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSyncReasonTest {

    @Test
    fun parse_fullBootstrapReasons() {
        assertTrue(SemanticSyncReason.parse("startup_seed") is SemanticSyncAction.FullBootstrap)
        assertTrue(SemanticSyncReason.parse("manual_rebuild") is SemanticSyncAction.FullBootstrap)
    }

    @Test
    fun parse_noteSave_isIncremental() {
        val action = SemanticSyncReason.parse("save_note_content:42")
        assertEquals(SemanticSyncAction.IndexNote(42), action)
    }

    @Test
    fun parse_conversationReply_isIncremental() {
        val action = SemanticSyncReason.parse("conversation_reply_written:99")
        assertEquals(SemanticSyncAction.IndexConversation(99), action)
    }

    @Test
    fun parse_deleteSubfolder() {
        val action = SemanticSyncReason.parse("delete_subfolder:7")
        assertEquals(SemanticSyncAction.DeleteSubfolder(7), action)
    }

    @Test
    fun parse_unknownReason_isNoOp() {
        assertEquals(SemanticSyncAction.NoOp, SemanticSyncReason.parse("something_new"))
    }

    @Test
    fun parse_workshopEditorOpen_indexesSubfolder() {
        val action = SemanticSyncReason.parse("workshop_editor_open:12")
        assertEquals(SemanticSyncAction.IndexSubfolder(12), action)
    }
}
