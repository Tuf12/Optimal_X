package com.example.optimalx.data.eidos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.optimalx.MainActivity
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.R
import com.example.optimalx.data.eidos.ChatMessageHistoryLoader
import com.example.optimalx.data.eidos.ChatMessagePersistLimits
import com.example.optimalx.data.eidos.model.persistableReasoningContent
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EidosChatSendWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? OptimalXApplication ?: return@withContext Result.failure()
        val conversationId = inputData.getLong(KEY_CONVERSATION_ID, -1L)
        val userText = inputData.getString(KEY_USER_TEXT)?.trim().orEmpty()
        val previousResponseId = inputData.getString(KEY_PREVIOUS_RESPONSE_ID)
        val baseSystemPrompt = inputData.getString(KEY_BASE_SYSTEM_PROMPT)?.trim().orEmpty()
        val currentScopeType = inputData.getString(KEY_SCOPE_TYPE)
        val currentSubfolderId = inputData.getLong(KEY_SUBFOLDER_ID, -1L).takeIf { it > 0L }
        val currentParentFolderId = inputData.getLong(KEY_PARENT_ID, -1L).takeIf { it > 0L }
        val editorSurfaceHint = inputData.getString(KEY_EDITOR_SURFACE_HINT)
        val webPanelPageUrl = inputData.getString(KEY_WEB_PANEL_PAGE_URL)
        val workshopOpenFileName = inputData.getString(KEY_WORKSHOP_OPEN_FILE_NAME)
        val workshopOpenFileContent = inputData.getString(KEY_WORKSHOP_OPEN_FILE_CONTENT)
        val workshopEidosMode = WorkshopEidosMode.fromStored(inputData.getString(KEY_WORKSHOP_EIDOS_MODE))
        val workshopProjectPhase = WorkshopProjectPhase.fromStored(
            inputData.getString(KEY_WORKSHOP_PROJECT_PHASE),
        )
        val workshopUpdateSection = WorkshopUpdateSection.fromStored(
            inputData.getString(KEY_WORKSHOP_UPDATE_SECTION),
        )

        if (conversationId <= 0L || userText.isBlank() || baseSystemPrompt.isBlank()) {
            return@withContext Result.failure()
        }

        val db = app.database
        val conversation = db.conversationDao().getById(conversationId) ?: return@withContext Result.failure()
        val allMessages = ChatMessageHistoryLoader.forApi(db.chatMessageDao(), conversationId)
        val rawHistory = allMessages.toEidosApiHistoryExcludingLatestUser(userText)
        val history = rawHistory

        return@withContext try {
            val response = app.eidosApiClient.send(
                userMessage = userText,
                conversationId = conversationId,
                currentSubfolderId = currentSubfolderId,
                currentParentFolderId = currentParentFolderId,
                currentScopeType = currentScopeType,
                conversationHistory = history,
                baseSystemPrompt = baseSystemPrompt,
                previousResponseId = previousResponseId,
                subfolderEditorSurfaceHint = editorSurfaceHint,
                webPanelPageUrl = webPanelPageUrl,
                workshopOpenFileName = workshopOpenFileName,
                workshopOpenFileContent = workshopOpenFileContent,
                workshopEidosMode = workshopEidosMode,
                workshopProjectPhase = workshopProjectPhase
                    ?: currentSubfolderId?.let {
                        WorkshopProjectPreferences.getProjectPhase(applicationContext, it)
                    },
                workshopUpdateSection = workshopUpdateSection
                    ?: currentSubfolderId?.let {
                        WorkshopProjectPreferences.getUpdateSection(applicationContext, it)
                    },
            )

            val replyText = response.textResponse.ifBlank {
                "I ran the request but did not receive a text response."
            }
            val reasoningContent = response.persistableReasoningContent()
            val replyMsgId = db.chatMessageDao().insert(
                ChatMessagePersistLimits.clampForStorage(
                    ChatMessage(
                        conversationId = conversationId,
                        role = "eidos",
                        content = replyText,
                        assistantReasoningContent = reasoningContent,
                        createdAt = System.currentTimeMillis(),
                    ),
                ),
            )
            db.conversationDao().update(conversation.copy(updatedAt = System.currentTimeMillis()))
            app.appIndexSyncService.requestSync("background_conversation_reply_written:$conversationId")
            app.semanticSyncService.requestSync("background_conversation_reply_written:$conversationId")
            postCompletionNotification(
                context = applicationContext,
                conversationId = conversationId,
                title = conversation.title.ifBlank { "Eidos" },
                text = replyText,
            )
            Result.success()
        } catch (_: Throwable) {
            postFailureNotification(
                context = applicationContext,
                conversationId = conversationId,
                title = conversation.title.ifBlank { "Eidos" },
            )
            Result.retry()
        }
    }

    companion object {
        private const val CHANNEL_ID = "eidos_background_chat"
        private const val CHANNEL_NAME = "Eidos Background Chat"
        private const val UNIQUE_PREFIX = "eidos_chat_send_"

        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_USER_TEXT = "user_text"
        const val KEY_PREVIOUS_RESPONSE_ID = "previous_response_id"
        const val KEY_BASE_SYSTEM_PROMPT = "base_system_prompt"
        const val KEY_SCOPE_TYPE = "scope_type"
        const val KEY_SUBFOLDER_ID = "subfolder_id"
        const val KEY_PARENT_ID = "parent_id"
        const val KEY_EDITOR_SURFACE_HINT = "editor_surface_hint"
        const val KEY_WEB_PANEL_PAGE_URL = "web_panel_page_url"
        const val KEY_WORKSHOP_OPEN_FILE_NAME = "workshop_open_file_name"
        const val KEY_WORKSHOP_OPEN_FILE_CONTENT = "workshop_open_file_content"
        const val KEY_WORKSHOP_EIDOS_MODE = "workshop_eidos_mode"
        const val KEY_WORKSHOP_PROJECT_PHASE = "workshop_project_phase"
        const val KEY_WORKSHOP_UPDATE_SECTION = "workshop_update_section"

        fun notifyReplyReady(
            context: Context,
            conversationId: Long,
            title: String,
            text: String,
        ) {
            postCompletionNotification(context, conversationId, title, text)
        }

        fun enqueue(context: Context, payload: Data, conversationId: Long) {
            val work = OneTimeWorkRequestBuilder<EidosChatSendWorker>()
                .setInputData(payload)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + conversationId,
                ExistingWorkPolicy.REPLACE,
                work,
            )
        }

        private fun postCompletionNotification(
            context: Context,
            conversationId: Long,
            title: String,
            text: String,
        ) {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Eidos reply ready")
                .setContentText(text.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        context,
                        conversationId.toInt(),
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
            NotificationManagerCompat.from(context).notify(stableNotificationId(conversationId, title), notification)
        }

        private fun postFailureNotification(context: Context, conversationId: Long, title: String) {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Eidos background send retrying")
                .setContentText("Connection issue while finishing \"$title\".")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(stableNotificationId(conversationId, title), notification)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Completion notifications for background Eidos sends"
                }
            )
        }

        private fun stableNotificationId(conversationId: Long, title: String): Int {
            return "$conversationId|$title".hashCode()
        }
    }
}
