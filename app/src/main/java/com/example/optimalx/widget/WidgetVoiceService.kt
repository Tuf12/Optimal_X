package com.example.optimalx.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.db.seedDatabaseIfNeeded
import com.example.optimalx.data.conversation.buildConversationTitleFromText
import com.example.optimalx.data.conversation.looksLikeAutoTimestampTitle
import com.example.optimalx.data.eidos.ConversationOutboundHistory
import com.example.optimalx.data.litert.GemmaLocalPolicy
import com.example.optimalx.data.eidos.prompt.EidosEntrySurface
import com.example.optimalx.data.eidos.prompt.EidosIdentityPrompt
import com.example.optimalx.data.eidos.model.persistableReasoningContent
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.model.ConversationScopes
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.voice.SttEngine
import com.example.optimalx.voice.TextToSpeechEngine
import com.example.optimalx.voice.VoiceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Background voice service for widget Quick Ask and Quick Notes capture buttons.
 *
 * Push-to-talk model:
 *   First  ACTION_START_VOICE / ACTION_QUICK_NOTE → starts Google STT.
 *   Second tap on the active capture button → cancel.
 *   ACTION_SEND → stops STT and sends transcript to Eidos.
 *
 * Quick Ask exchanges are logged to a general-scope conversation that is not linked
 * to the Widget Chat UI pointer in [WidgetPrefs].
 */
class WidgetVoiceService : Service(), CoroutineScope {

    companion object {
        const val ACTION_START_VOICE = "com.example.optimalx.widget.START_VOICE"
        const val ACTION_QUICK_NOTE = "com.example.optimalx.widget.QUICK_NOTE"
        const val ACTION_SEND = "com.example.optimalx.widget.SEND"
        const val ACTION_CONTINUE = "com.example.optimalx.widget.CONTINUE_CONVERSATION"
        const val EXTRA_CONVERSATION_ID = "conversation_id"

        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "optimalx_widget_voice"
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val TAG = "OptimalX.WidgetVoice"
        private const val QUICK_ASK_TITLE = "Widget Quick Ask"

        private val conversationTitleFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd — h:mm a").withZone(ZoneId.systemDefault())
    }

    private enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    override val coroutineContext = SupervisorJob() + Dispatchers.Main

    private val app get() = applicationContext as OptimalXApplication

    private lateinit var stt: SttEngine
    private lateinit var tts: TextToSpeechEngine

    private var state = State.IDLE
    private var captureTarget = WidgetCaptureTarget.NONE
    private var transcriptDraft = ""
    private var pendingSendCommit = false
    private var idleTimeoutJob: Job? = null

    private var quickNoteSessionConversationId: Long? = null
    private var quickNotePreviousResponseId: String? = null

    override fun onCreate() {
        super.onCreate()
        runBlocking {
            try {
                seedDatabaseIfNeeded(app, app.database)
            } catch (e: Exception) {
                Log.e(TAG, "Widget voice: DB seed failed", e)
            }
        }
        createNotificationChannel()
        stt = VoiceRuntime.createGoogleOnlySttEngine(this)
        wireSttCallbacks()
        tts = TextToSpeechEngine(this)
    }

    private fun wireSttCallbacks() {
        stt.onPartialResult = { partial ->
            if (state == State.LISTENING) {
                transcriptDraft = partial.trim()
            }
        }
        stt.onFinalResult = { final ->
            transcriptDraft = final.trim()
            if (pendingSendCommit) {
                pendingSendCommit = false
                commitOrEnd(transcriptDraft)
            }
        }
        stt.onError = { code ->
            Log.d(TAG, "Widget STT error code=$code")
            if (pendingSendCommit) {
                pendingSendCommit = false
                commitOrEnd(transcriptDraft)
            }
        }
        stt.onListeningEnded = {
            if (state == State.LISTENING && !pendingSendCommit) {
                state = State.IDLE
                setMicState(WidgetMicState.IDLE)
                updateNotification("Ready — tap Ask Eidos to speak")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VOICE -> handleMicTap(WidgetCaptureTarget.QUICK_ASK)
            ACTION_QUICK_NOTE -> handleMicTap(WidgetCaptureTarget.QUICK_NOTE)
            ACTION_SEND -> handleSend()
            ACTION_CONTINUE -> {
                val id = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L).takeIf { it > 0 }
                if (id != null) {
                    endSession()
                    WidgetPrefs.setActiveConversationId(this, id)
                    setMicState(WidgetMicState.IDLE)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        idleTimeoutJob?.cancel()
        stt.destroy()
        tts.destroy()
        coroutineContext.cancel()
        super.onDestroy()
    }

    private fun handleMicTap(target: WidgetCaptureTarget) {
        when (state) {
            State.IDLE -> {
                if (!hasRecordAudioPermission()) {
                    Log.w(TAG, "RECORD_AUDIO not granted — cannot start widget voice session")
                    setMicState(WidgetMicState.IDLE)
                    startActivity(
                        Intent(this, WidgetVoiceLauncherActivity::class.java).apply {
                            action = when (target) {
                                WidgetCaptureTarget.QUICK_NOTE -> ACTION_QUICK_NOTE
                                else -> ACTION_START_VOICE
                            }
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                    stopSelf()
                    return
                }
                promoteToMicrophoneForeground("Listening… tap Send to send, Ask Eidos to cancel")
                state = State.LISTENING
                captureTarget = target
                transcriptDraft = ""
                pendingSendCommit = false
                setMicState(WidgetMicState.RECORDING)
                resetIdleTimeout()
                stt.startListening()
            }
            State.LISTENING -> {
                Log.d(TAG, "Capture tapped while listening — cancelling session")
                pendingSendCommit = false
                stt.stopListening()
                endSession()
            }
            State.PROCESSING -> Log.d(TAG, "Capture tapped while processing — ignoring")
            State.SPEAKING -> {
                Log.d(TAG, "Capture tapped while speaking — stopping TTS and returning to listening")
                tts.stop()
                handMicBackToUser()
            }
        }
    }

    private fun handleSend() {
        when (state) {
            State.LISTENING -> {
                pendingSendCommit = true
                stt.stopListening()
                resetIdleTimeout()
            }
            else -> Log.d(TAG, "Send tapped but state=$state — nothing to send")
        }
    }

    private fun commitOrEnd(capturedText: String) {
        val payload = capturedText.trim()
        if (payload.isNotBlank()) {
            handleTranscript(payload)
        } else {
            Log.d(TAG, "Commit requested but no speech captured — ending")
            endSession()
        }
    }

    private fun handleTranscript(text: String) {
        state = State.PROCESSING
        setMicState(WidgetMicState.RESPONDING)
        updateNotification("Thinking…")

        val target = captureTarget
        launch(Dispatchers.IO) {
            try {
                when (target) {
                    WidgetCaptureTarget.QUICK_NOTE -> handleQuickNoteTranscript(text)
                    else -> handleQuickAskTranscript(text)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error during widget voice round trip", t)
                withContext(Dispatchers.Main) {
                    state = State.IDLE
                    setMicState(WidgetMicState.IDLE)
                    updateNotification("Ready — tap Ask Eidos to speak")
                }
            }
        }
    }

    private suspend fun handleQuickAskTranscript(text: String) {
        var conversation = ensureQuickAskLogConversation()
        val now = System.currentTimeMillis()

        db.chatMessageDao().insert(
            ChatMessage(conversationId = conversation.id, role = "user", content = text, createdAt = now),
        )
        conversation = maybeRetitleConversation(conversation, text, now)

        val convForHistory = db.conversationDao().getById(conversation.id) ?: conversation
        val useLocalGemma = GemmaLocalPolicy.isActiveProvider(this)
        val history = if (useLocalGemma) {
            ConversationOutboundHistory.buildForLocalGemma(
                chatMessageDao = db.chatMessageDao(),
                conversation = convForHistory,
                activeUserText = text,
            )
        } else {
            ConversationOutboundHistory.build(
                chatMessageDao = db.chatMessageDao(),
                conversation = convForHistory,
                activeUserText = text,
            )
        }
        val response = app.eidosApiClient.send(
            userMessage = text,
            conversationId = conversation.id,
            currentSubfolderId = null,
            conversationHistory = history,
            baseSystemPrompt = EidosIdentityPrompt.TEXT,
            entrySurface = EidosEntrySurface.WIDGET_ASK,
            previousResponseId = null,
        )

        val reply = response.textResponse.ifBlank { "I could not generate a response." }
        val reasoningContent = response.persistableReasoningContent()
        val replyMsgId = db.chatMessageDao().insert(
            ChatMessage(
                conversationId = conversation.id,
                role = "eidos",
                content = reply,
                assistantReasoningContent = reasoningContent,
            ),
        )
        db.conversationDao().update(conversation.copy(updatedAt = System.currentTimeMillis()))
        requestRetrievalSync("widget_quick_ask_reply:${conversation.id}")

        finishWidgetReplyWithOptionalTts(reply)
    }

    private suspend fun handleQuickNoteTranscript(text: String) {
        val daySubfolderId = app.folderRepository.resolveQuickNotesDaySubfolderId()
        if (daySubfolderId == null) {
            Log.e(TAG, "Could not resolve Quick Notes day folder — using Quick Ask log")
            handleQuickAskTranscript(text)
            return
        }
        var conversation = ensureQuickNotesConversation(daySubfolderId, text)
        val now = System.currentTimeMillis()

        db.chatMessageDao().insert(
            ChatMessage(conversationId = conversation.id, role = "user", content = text, createdAt = now),
        )
        conversation = maybeRetitleConversation(conversation, text, now)

        val convForHistory = db.conversationDao().getById(conversation.id) ?: conversation
        val useLocalGemma = GemmaLocalPolicy.isActiveProvider(this)
        val history = if (useLocalGemma) {
            ConversationOutboundHistory.buildForLocalGemma(
                chatMessageDao = db.chatMessageDao(),
                conversation = convForHistory,
                activeUserText = text,
            )
        } else {
            ConversationOutboundHistory.build(
                chatMessageDao = db.chatMessageDao(),
                conversation = convForHistory,
                activeUserText = text,
            )
        }

        val response = app.eidosApiClient.send(
            userMessage = text,
            conversationId = conversation.id,
            currentSubfolderId = daySubfolderId,
            currentScopeType = ConversationScopes.QUICK_NOTES_DAY,
            conversationHistory = history,
            baseSystemPrompt = EidosIdentityPrompt.TEXT,
            entrySurface = EidosEntrySurface.WIDGET_QUICK_NOTE,
            previousResponseId = quickNotePreviousResponseId,
        )
        quickNotePreviousResponseId = response.providerResponseId ?: quickNotePreviousResponseId

        val reply = response.textResponse.ifBlank { "I could not generate a response." }
        val reasoningContent = response.persistableReasoningContent()
        val parentFolderId = db.subfolderDao().getById(daySubfolderId)?.parentFolderId
        val replyMsgId = db.chatMessageDao().insert(
            ChatMessage(
                conversationId = conversation.id,
                role = "eidos",
                content = reply,
                assistantReasoningContent = reasoningContent,
            ),
        )
        db.conversationDao().update(conversation.copy(updatedAt = System.currentTimeMillis()))
        requestRetrievalSync("widget_quick_note_reply:${conversation.id}")

        finishWidgetReplyWithOptionalTts(reply)
    }

    private suspend fun finishWidgetReplyWithOptionalTts(reply: String) {
        val prefs = settingsDataStore.data.first()
        val handsFree = prefs[SettingsKeys.WIDGET_VOICE_HANDS_FREE] ?: SettingsDefaults.WIDGET_VOICE_HANDS_FREE
        val micPassback = prefs[SettingsKeys.READ_ALOUD_MIC_PASSBACK] ?: SettingsDefaults.READ_ALOUD_MIC_PASSBACK
        withContext(Dispatchers.Main) {
            if (handsFree) {
                state = State.SPEAKING
                updateNotification("Speaking…")
                tts.speak(reply) {
                    if (micPassback) {
                        handMicBackToUser()
                    } else {
                        finishSpeakingWithoutMicPassback()
                    }
                }
            } else {
                finishSpeakingWithoutMicPassback()
            }
        }
    }

    private fun finishSpeakingWithoutMicPassback() {
        state = State.IDLE
        setMicState(WidgetMicState.IDLE)
        updateNotification("Ready — tap Ask Eidos to speak")
        resetIdleTimeout()
    }

    private fun handMicBackToUser() {
        state = State.LISTENING
        transcriptDraft = ""
        pendingSendCommit = false
        setMicState(WidgetMicState.RECORDING)
        updateNotification("Listening… tap Send to send, Ask Eidos to cancel")
        resetIdleTimeout()
        stt.startListening()
    }

    private fun endSession() {
        idleTimeoutJob?.cancel()
        state = State.IDLE
        captureTarget = WidgetCaptureTarget.NONE
        quickNoteSessionConversationId = null
        quickNotePreviousResponseId = null
        transcriptDraft = ""
        pendingSendCommit = false
        stt.stopListening()
        tts.stop()
        setMicState(WidgetMicState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resetIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = launch {
            delay(IDLE_TIMEOUT_MS)
            Log.d(TAG, "Idle timeout — ending session")
            endSession()
        }
    }

    private fun setMicState(micState: WidgetMicState) {
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, OptimalXWidget::class.java))
        if (ids.isEmpty()) return
        val views = buildWidgetViews(
            this,
            micState,
            WidgetPrefs.getActiveConversationId(this),
            captureTarget,
        )
        manager.updateAppWidget(ids, views)
    }

    private val db get() = app.database
    private val semanticSync get() = app.semanticSyncService

    private fun requestRetrievalSync(reason: String) {
        semanticSync.requestSync(reason)
    }

    private suspend fun ensureQuickAskLogConversation(): Conversation {
        WidgetPrefs.getQuickAskLogConversationId(this)?.let { id ->
            db.conversationDao().getById(id)?.let { return it }
        }
        val existing = db.conversationDao().getRecentGeneral(20)
            .firstOrNull { it.title == QUICK_ASK_TITLE }
        if (existing != null) {
            WidgetPrefs.setQuickAskLogConversationId(this, existing.id)
            return existing
        }
        val newConv = Conversation(scopeType = "general", title = QUICK_ASK_TITLE)
        val id = db.conversationDao().insert(newConv)
        requestRetrievalSync("widget_quick_ask_log_created:$id")
        WidgetPrefs.setQuickAskLogConversationId(this, id)
        return newConv.copy(id = id)
    }

    private suspend fun ensureQuickNotesConversation(subfolderId: Long, firstUserText: String): Conversation {
        quickNoteSessionConversationId?.let { id ->
            db.conversationDao().getById(id)?.takeIf { conv ->
                conv.scopeType == "quick_notes_day" && conv.subfolderId == subfolderId
            }?.let { return it }
        }
        db.conversationDao().getRecentQuickNotesDay(subfolderId, 1).firstOrNull()?.let {
            quickNoteSessionConversationId = it.id
            return it
        }
        val title = buildConversationTitleFromText(firstUserText)
            .ifBlank { conversationTitleFormatter.format(Instant.now()) }
        val newConv = Conversation(
            scopeType = "quick_notes_day",
            subfolderId = subfolderId,
            title = title,
        )
        val id = db.conversationDao().insert(newConv)
        requestRetrievalSync("widget_quick_note_conversation_created:$id")
        quickNoteSessionConversationId = id
        return newConv.copy(id = id)
    }

    private suspend fun maybeRetitleConversation(
        conversation: Conversation,
        seedText: String,
        now: Long = System.currentTimeMillis(),
    ): Conversation {
        if (conversation.title == QUICK_ASK_TITLE) return conversation
        if (!looksLikeAutoTimestampTitle(conversation.title)) return conversation
        val generatedTitle = buildConversationTitleFromText(seedText)
        if (generatedTitle.isBlank()) return conversation
        val updatedConversation = conversation.copy(title = generatedTitle, updatedAt = now)
        db.conversationDao().update(updatedConversation)
        requestRetrievalSync("widget_conversation_auto_retitle:${conversation.id}")
        return updatedConversation
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun promoteToMicrophoneForeground(notificationText: String) {
        val notification = buildNotification(notificationText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Eidos Voice",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Active widget voice session" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Eidos")
        .setContentText(text)
        .setOngoing(true)
        .addAction(
            android.R.drawable.ic_btn_speak_now,
            "Send",
            PendingIntent.getService(
                this,
                0,
                Intent(this, WidgetVoiceService::class.java).apply { action = ACTION_SEND },
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
