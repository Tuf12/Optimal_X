package com.example.optimalx.widget

import android.content.Context

/**
 * Persists widget chat UI state across service restarts and between the widget
 * provider and [WidgetChatActivity]. Quick Ask log uses a separate pointer.
 */
object WidgetPrefs {
    private const val FILE = "widget_prefs"
    private const val KEY_ACTIVE_CONVERSATION = "active_conversation_id"
    private const val KEY_QUICK_ASK_LOG_CONVERSATION = "quick_ask_log_conversation_id"

    fun getActiveConversationId(context: Context): Long? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_ACTIVE_CONVERSATION, -1L)
            .takeIf { it > 0 }

    fun setActiveConversationId(context: Context, id: Long?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ACTIVE_CONVERSATION, id ?: -1L)
            .apply()
    }

    fun clearActiveConversationId(context: Context) = setActiveConversationId(context, null)

    fun getQuickAskLogConversationId(context: Context): Long? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_QUICK_ASK_LOG_CONVERSATION, -1L)
            .takeIf { it > 0 }

    fun setQuickAskLogConversationId(context: Context, id: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_QUICK_ASK_LOG_CONVERSATION, id)
            .apply()
    }
}
