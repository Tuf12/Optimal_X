package com.example.optimalx.data.preferences

import android.content.Context

/**
 * Persists which conversation the user last had open per surface, independent of
 * [Conversation.updatedAt] ordering (so e.g. widget voice traffic does not steal the
 * main-app General thread from [getRecentGeneral]).
 *
 * Keys: general (one), per parent folder, per subfolder, Quick Notes root/day.
 */
object ChatSessionPointers {
    private const val PREFS_NAME = "chat_session_pointers"
    private const val KEY_GENERAL_MAIN = "general_main_conversation_id"

    private fun parentKey(parentFolderId: Long) = "parent_${parentFolderId}_conversation_id"

    private fun subfolderKey(subfolderId: Long) = "subfolder_${subfolderId}_conversation_id"

    private fun quickNotesRootKey(parentFolderId: Long) = "qn_root_${parentFolderId}_conversation_id"

    private fun quickNotesDayKey(subfolderId: Long) = "qn_day_${subfolderId}_conversation_id"

    private fun panelWorkshopKey(subfolderId: Long) = "panel_workshop_${subfolderId}_conversation_id"
    private fun panelRunnerKey(subfolderId: Long) = "panel_runner_${subfolderId}_conversation_id"
    private fun imageStudioKey(subfolderId: Long) = "image_studio_${subfolderId}_conversation_id"
    private const val KEY_PANEL_GALLERY = "panel_gallery_conversation_id"
    private const val KEY_DUMP_EDIT = "dump_edit_conversation_id"

    fun getGeneralMain(context: Context): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_GENERAL_MAIN, -1L)
            .takeIf { it > 0L }

    fun setGeneralMain(context: Context, conversationId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_GENERAL_MAIN, conversationId ?: -1L)
            .apply()
    }

    fun getParentFolder(context: Context, parentFolderId: Long): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(parentKey(parentFolderId), -1L)
            .takeIf { it > 0L }

    fun setParentFolder(context: Context, parentFolderId: Long, conversationId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(parentKey(parentFolderId), conversationId ?: -1L)
            .apply()
    }

    fun clearParentFolder(context: Context, parentFolderId: Long) =
        setParentFolder(context, parentFolderId, null)

    fun getSubfolder(context: Context, subfolderId: Long): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(subfolderKey(subfolderId), -1L)
            .takeIf { it > 0L }

    fun setSubfolder(context: Context, subfolderId: Long, conversationId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(subfolderKey(subfolderId), conversationId ?: -1L)
            .apply()
    }

    fun clearSubfolder(context: Context, subfolderId: Long) =
        setSubfolder(context, subfolderId, null)

    fun getQuickNotesRoot(context: Context, parentFolderId: Long): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(quickNotesRootKey(parentFolderId), -1L)
            .takeIf { it > 0L }

    fun setQuickNotesRoot(context: Context, parentFolderId: Long, conversationId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(quickNotesRootKey(parentFolderId), conversationId ?: -1L)
            .apply()
    }

    fun clearQuickNotesRoot(context: Context, parentFolderId: Long) =
        setQuickNotesRoot(context, parentFolderId, null)

    fun getQuickNotesDay(context: Context, subfolderId: Long): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(quickNotesDayKey(subfolderId), -1L)
            .takeIf { it > 0L }

    fun setQuickNotesDay(context: Context, subfolderId: Long, conversationId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(quickNotesDayKey(subfolderId), conversationId ?: -1L)
            .apply()
    }

    fun clearQuickNotesDay(context: Context, subfolderId: Long) =
        setQuickNotesDay(context, subfolderId, null)

    fun getPanelWorkshop(context: Context, subfolderId: Long): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(panelWorkshopKey(subfolderId), -1L)
            .takeIf { it > 0L }

    fun setPanelWorkshop(context: Context, subfolderId: Long, conversationId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(panelWorkshopKey(subfolderId), conversationId ?: -1L)
            .apply()
    }

    fun clearPanelWorkshop(context: Context, subfolderId: Long) =
        setPanelWorkshop(context, subfolderId, null)

    fun getPanelGallery(context: Context): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PANEL_GALLERY, -1L)
            .takeIf { it > 0L }

    fun setPanelGallery(context: Context, conversationId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PANEL_GALLERY, conversationId ?: -1L)
            .apply()
    }

    fun clearPanelGallery(context: Context) = setPanelGallery(context, null)

    fun getPanelRunner(context: Context, subfolderId: Long): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(panelRunnerKey(subfolderId), -1L)
            .takeIf { it > 0L }

    fun setPanelRunner(context: Context, subfolderId: Long, conversationId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(panelRunnerKey(subfolderId), conversationId ?: -1L)
            .apply()
    }

    fun clearPanelRunner(context: Context, subfolderId: Long) =
        setPanelRunner(context, subfolderId, null)

    fun getImageStudio(context: Context, saveSubfolderId: Long): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(imageStudioKey(saveSubfolderId), -1L)
            .takeIf { it > 0L }

    fun setImageStudio(context: Context, saveSubfolderId: Long, conversationId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(imageStudioKey(saveSubfolderId), conversationId ?: -1L)
            .apply()
    }

    fun clearImageStudio(context: Context, saveSubfolderId: Long) =
        setImageStudio(context, saveSubfolderId, null)

    fun getDumpEdit(context: Context): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_DUMP_EDIT, -1L)
            .takeIf { it > 0L }

    fun setDumpEdit(context: Context, conversationId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DUMP_EDIT, conversationId ?: -1L)
            .apply()
    }

    fun clearDumpEdit(context: Context) = setDumpEdit(context, null)
}
