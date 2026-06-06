package com.example.optimalx.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.R
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.voice.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Visual state of the mic button — drives background color and label color. */
enum class WidgetMicState { IDLE, RECORDING, RESPONDING }
enum class WidgetCaptureTarget { NONE, QUICK_ASK, QUICK_NOTE }

class OptimalXWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val activeId = WidgetPrefs.getActiveConversationId(context)
        val views = buildWidgetViews(
            context = context,
            micState = WidgetMicState.IDLE,
            activeConversationId = activeId,
            captureTarget = WidgetCaptureTarget.NONE,
        )
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    override fun onEnabled(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val prefs = context.settingsDataStore.data.first()
            val wakeWord = prefs[SettingsKeys.WAKE_WORD] ?: SettingsDefaults.WAKE_WORD
            wakeWordDetector(context).startDetecting(wakeWord) {
                context.startService(
                    Intent(context, WidgetVoiceService::class.java).apply {
                        action = WidgetVoiceService.ACTION_START_VOICE
                    }
                )
            }
        }
    }

    override fun onDisabled(context: Context) {
        wakeWordDetector(context).stopDetecting()
    }
}

private fun wakeWordDetector(context: Context): WakeWordDetector =
    (context.applicationContext as OptimalXApplication).wakeWordDetector

/**
 * Builds the full RemoteViews for the widget in the given [micState].
 * Called by both [OptimalXWidget] and [WidgetVoiceService].
 */
fun buildWidgetViews(
    context: Context,
    micState: WidgetMicState,
    activeConversationId: Long?,
    captureTarget: WidgetCaptureTarget = WidgetCaptureTarget.NONE,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_layout)
    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    // ── Default visual state for both capture buttons ─────────────────────────
    var micBackground = R.drawable.widget_mic_idle_bg
    var micLabelColor = 0xFFA0A09C.toInt()
    var quickNoteBackground = R.drawable.widget_button_bg
    var quickNoteLabelColor = 0xFFA0A09C.toInt()
    var sendBackground = R.drawable.widget_button_bg
    var sendLabelColor = 0xFFA0A09C.toInt()
    var sendIconColor = 0xFFC8FB5E.toInt()

    // ── Active visual indicator applied only to the selected capture target ───
    if (micState != WidgetMicState.IDLE) {
        val (activeBackground, activeLabelColor) = when (micState) {
            WidgetMicState.RECORDING -> R.drawable.widget_mic_recording_bg to 0xFF0E0E0F.toInt()
            WidgetMicState.RESPONDING -> R.drawable.widget_mic_responding_bg to 0xFF0E0E0F.toInt()
            WidgetMicState.IDLE -> R.drawable.widget_mic_idle_bg to 0xFFA0A09C.toInt()
        }
        when (captureTarget) {
            WidgetCaptureTarget.QUICK_ASK -> {
                micBackground = activeBackground
                micLabelColor = activeLabelColor
            }
            WidgetCaptureTarget.QUICK_NOTE -> {
                quickNoteBackground = activeBackground
                quickNoteLabelColor = activeLabelColor
            }
            WidgetCaptureTarget.NONE -> Unit
        }
    }

    // Highlight Send as the "next step" while either capture path is listening.
    if (micState == WidgetMicState.RECORDING && captureTarget != WidgetCaptureTarget.NONE) {
        sendBackground = R.drawable.widget_send_active_bg
        sendLabelColor = 0xFF0E0E0F.toInt()
        sendIconColor = 0xFF0E0E0F.toInt()
    }

    views.setInt(R.id.btn_mic, "setBackgroundResource", micBackground)
    views.setTextColor(R.id.tv_mic_label, micLabelColor)
    views.setInt(R.id.btn_quick_note, "setBackgroundResource", quickNoteBackground)
    views.setTextColor(R.id.tv_quick_note_label, quickNoteLabelColor)
    views.setInt(R.id.btn_send, "setBackgroundResource", sendBackground)
    views.setTextColor(R.id.tv_send_label, sendLabelColor)
    views.setTextColor(R.id.tv_send_icon, sendIconColor)

    // ── Web Search row: field → type / voice first; globe → Web panel directly ─
    views.setOnClickPendingIntent(
        R.id.btn_web_search,
        PendingIntent.getActivity(
            context,
            6,
            Intent(context, WidgetWebSearchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            flags,
        ),
    )
    views.setOnClickPendingIntent(
        R.id.btn_web_open_panel,
        PendingIntent.getActivity(
            context,
            7,
            Intent(context, WidgetWebActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
            flags,
        ),
    )

    val micIntent = Intent(context, WidgetVoiceService::class.java).apply {
        action = WidgetVoiceService.ACTION_START_VOICE
    }
    views.setOnClickPendingIntent(
        R.id.btn_mic,
        widgetVoiceServicePendingIntent(context, 0, micIntent, flags, preferForeground = true),
    )

    // ── Quick Note — temporary routing to widget voice capture action ─────────
    val quickNoteIntent = Intent(context, WidgetVoiceService::class.java).apply {
        action = WidgetVoiceService.ACTION_QUICK_NOTE
    }
    views.setOnClickPendingIntent(
        R.id.btn_quick_note,
        widgetVoiceServicePendingIntent(context, 5, quickNoteIntent, flags, preferForeground = true),
    )

    // ── Send — commits the active voice draft via service action ──────────────
    val sendIntent = Intent(context, WidgetVoiceService::class.java).apply {
        action = WidgetVoiceService.ACTION_SEND
    }
    views.setOnClickPendingIntent(
        R.id.btn_send,
        PendingIntent.getService(context, 1, sendIntent, flags),
    )

    // ── Chat — opens the currently active conversation ─────────────────────────
    val chatIntent = Intent(context, WidgetChatActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (activeConversationId != null) {
            putExtra(WidgetVoiceService.EXTRA_CONVERSATION_ID, activeConversationId)
        }
    }
    views.setOnClickPendingIntent(
        R.id.btn_chat,
        PendingIntent.getActivity(context, 2, chatIntent, flags),
    )

    // ── Conversations picker ──────────────────────────────────────────────────
    views.setOnClickPendingIntent(
        R.id.btn_conversations,
        PendingIntent.getActivity(
            context, 4,
            Intent(context, ConversationPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            flags,
        ),
    )

    return views
}

/**
 * Mic / Quick Note start a microphone [Foreground Service]. On API 26+ use
 * [PendingIntent.getForegroundService] so the tap works on cold process / background limits.
 */
private fun widgetVoiceServicePendingIntent(
    context: Context,
    requestCode: Int,
    intent: Intent,
    flags: Int,
    preferForeground: Boolean,
): PendingIntent {
    return if (preferForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        PendingIntent.getForegroundService(context, requestCode, intent, flags)
    } else {
        PendingIntent.getService(context, requestCode, intent, flags)
    }
}
