package com.example.optimalx.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.optimalx.MainActivity
import com.example.optimalx.R

internal object NoteReadAloudNotification {
    private const val CHANNEL_ID = "optimalx_note_read_aloud"
    private const val CHANNEL_NAME = "Note read aloud"
    private const val CHANNEL_DESC = "Playback controls for note text-to-speech."
    private const val NOTIFICATION_ID = 9417

    fun show(
        context: Context,
        isPlaying: Boolean,
        contentTitle: String = "Reading note aloud",
    ) {
        ensureChannel(context)
        if (!canPostNotifications(context)) return

        val contentIntent = PendingIntent.getActivity(
            context,
            901,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                actionPendingIntent(context, NoteReadAloudReceiver.ACTION_TOGGLE),
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                actionPendingIntent(context, NoteReadAloudReceiver.ACTION_TOGGLE),
            )
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(contentTitle)
            .setContentText(if (isPlaying) "Playback in progress" else "Playback paused")
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_rew,
                    "Rewind 10s",
                    actionPendingIntent(context, NoteReadAloudReceiver.ACTION_REWIND_10),
                ),
            )
            .addAction(playPauseAction)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_ff,
                    "Forward 10s",
                    actionPendingIntent(context, NoteReadAloudReceiver.ACTION_FORWARD_10),
                ),
            )
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    actionPendingIntent(context, NoteReadAloudReceiver.ACTION_STOP),
                ),
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notif)
    }

    fun hide(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun actionPendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, NoteReadAloudReceiver::class.java).setAction(action)
        val requestCode = action.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
            },
        )
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
