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
import com.example.optimalx.data.litert.GemmaLocalPolicy
import com.example.optimalx.data.eidos.model.persistableReasoningContent
import com.example.optimalx.data.eidos.prompt.EidosEntrySurface
import com.example.optimalx.data.eidos.prompt.EidosIdentityPrompt
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.widget.WidgetChatActivity
import com.example.optimalx.widget.WidgetVoiceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where tapping an Eidos reply notification should take the user. */
enum class EidosReplyNotificationTarget {
    MAIN_APP,
    WIDGET_CHAT,
}

class EidosChatSendWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? OptimalXApplication ?: return@withContext Result.failure()
        val conversationId = inputData.getLong(KEY_CONVERSATION_ID, -1L)
        val userText = inputData.getString(KEY_USER_TEXT)?.trim().orEmpty()
        val previousResponseId = inputData.getString(KEY_PREVIOUS_RESPONSE_ID)
        val baseSystemPrompt = inputData.getString(KEY_BASE_SYSTEM_PROMPT)?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: EidosIdentityPrompt.TEXT
        val entrySurface = inputData.getString(KEY_ENTRY_SURFACE)
            ?.let { runCatching { EidosEntrySurface.valueOf(it) }.getOrNull() }
            ?: EidosEntrySurface.BACKGROUND_WORKER
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

        if (conversationId <= 0L || userText.isBlank()) {
            return@withContext Result.failure()
        }

        val db = app.database
        val conversation = db.conversationDao().getById(conversationId) ?: return@withContext Result.failure()
        val convForHistory = db.conversationDao().getById(conversationId) ?: return@withContext Result.failure()
        val lastUser = db.chatMessageDao().getAllByConversation(conversationId)
            .lastOrNull { it.role == "user" && it.content.trim() == userText }
        val useLocalGemma = GemmaLocalPolicy.isActiveProvider(applicationContext)
        val history = if (useLocalGemma) {
            ConversationOutboundHistory.buildForLocalGemma(
                chatMessageDao = db.chatMessageDao(),
                conversation = convForHistory,
                activeUserText = userText,
                excludeMessageId = lastUser?.id,
            )
        } else {
            ConversationOutboundHistory.build(
                chatMessageDao = db.chatMessageDao(),
                conversation = convForHistory,
                activeUserText = userText,
                excludeMessageId = lastUser?.id,
            )
        }
        val attachedImage = ChatVisionAttachmentCodec.parseAttachmentJson(lastUser?.imageAttachmentJson)
        val attachedImagePaths = ChatVisionImageStore.filePathsForAttachment(
            applicationContext,
            attachedImage,
        )
        if (attachedImage != null && attachedImagePaths.isEmpty()) {
            val now = System.currentTimeMillis()
            val replyText = ChatVisionAttachmentCodec.MISSING_BYTES_REPLY
            db.chatMessageDao().insert(
                ChatMessage(
                    conversationId = conversationId,
                    role = "eidos",
                    content = replyText,
                    createdAt = now,
                ),
            )
            db.conversationDao().update(conversation.copy(updatedAt = now))
            postCompletionNotification(
                context = applicationContext,
                conversationId = conversationId,
                title = conversation.title.ifBlank { "Eidos" },
                text = replyText,
                launchTarget = EidosReplyNotificationTarget.MAIN_APP,
            )
            return@withContext Result.success()
        }

        return@withContext try {
            val response = app.eidosApiClient.send(
                userMessage = userText,
                conversationId = conversationId,
                currentSubfolderId = currentSubfolderId,
                currentParentFolderId = currentParentFolderId,
                currentScopeType = currentScopeType,
                conversationHistory = history,
                baseSystemPrompt = baseSystemPrompt,
                entrySurface = entrySurface,
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
                attachedImagePaths = attachedImagePaths,
            )

            val replyText = EidosNavigationCodec.appendNavigationMarkdownLinks(
                replyText = response.textResponse.ifBlank {
                    "I ran the request but did not receive a text response."
                },
                targets = response.navigationTargets,
            )
            val reasoningContent = response.persistableReasoningContent()
            val navigationJson = EidosNavigationCodec.serializeTargets(response.navigationTargets)
            val replyMsgId = db.chatMessageDao().insert(
                ChatMessage(
                    conversationId = conversationId,
                    role = "eidos",
                    content = replyText,
                    assistantReasoningContent = reasoningContent,
                    navigationTargetsJson = navigationJson,
                    createdAt = System.currentTimeMillis(),
                )
            )
            db.conversationDao().update(conversation.copy(updatedAt = System.currentTimeMillis()))
            app.semanticSyncService.requestSync("background_conversation_reply_written:$conversationId")
            postCompletionNotification(
                context = applicationContext,
                conversationId = conversationId,
                title = conversation.title.ifBlank { "Eidos" },
                text = replyText,
                launchTarget = EidosReplyNotificationTarget.MAIN_APP,
            )
            Result.success()
        } catch (_: Throwable) {
            postFailureNotification(
                context = applicationContext,
                conversationId = conversationId,
                title = conversation.title.ifBlank { "Eidos" },
                launchTarget = EidosReplyNotificationTarget.MAIN_APP,
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
        const val KEY_ENTRY_SURFACE = "entry_surface"
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

        /** MainActivity extra: open [Routes.EIDOS_CHAT] for this conversation when the user taps a reply notification. */
        const val EXTRA_OPEN_CONVERSATION_ID = "open_conversation_id"

        fun conversationOpenIntent(
            context: Context,
            conversationId: Long,
            launchTarget: EidosReplyNotificationTarget = EidosReplyNotificationTarget.MAIN_APP,
        ): Intent = when (launchTarget) {
            EidosReplyNotificationTarget.WIDGET_CHAT ->
                Intent(context, WidgetChatActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    putExtra(WidgetVoiceService.EXTRA_CONVERSATION_ID, conversationId)
                }
            EidosReplyNotificationTarget.MAIN_APP ->
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    putExtra(EXTRA_OPEN_CONVERSATION_ID, conversationId)
                }
        }

        fun notifyReplyReady(
            context: Context,
            conversationId: Long,
            title: String,
            text: String,
            launchTarget: EidosReplyNotificationTarget = EidosReplyNotificationTarget.MAIN_APP,
        ) {
            postCompletionNotification(context, conversationId, title, text, launchTarget)
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
            launchTarget: EidosReplyNotificationTarget,
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
                        pendingIntentRequestCode(conversationId, launchTarget),
                        conversationOpenIntent(context, conversationId, launchTarget),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
            NotificationManagerCompat.from(context).notify(stableNotificationId(conversationId, title), notification)
        }

        private fun postFailureNotification(
            context: Context,
            conversationId: Long,
            title: String,
            launchTarget: EidosReplyNotificationTarget,
        ) {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Eidos background send retrying")
                .setContentText("Connection issue while finishing \"$title\".")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        context,
                        pendingIntentRequestCode(conversationId, launchTarget),
                        conversationOpenIntent(context, conversationId, launchTarget),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
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

        private fun pendingIntentRequestCode(
            conversationId: Long,
            launchTarget: EidosReplyNotificationTarget,
        ): Int {
            val targetSalt = when (launchTarget) {
                EidosReplyNotificationTarget.MAIN_APP -> 0
                EidosReplyNotificationTarget.WIDGET_CHAT -> 1
            }
            return (conversationId xor targetSalt.toLong()).toInt()
        }
    }
}
