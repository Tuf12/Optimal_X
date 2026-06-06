package com.example.optimalx.data.eidos.agentbyte

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.optimalx.MainActivity
import com.example.optimalx.R

class TagHintNotifierAndroid(
    private val context: Context,
) : TagHintNotifier {

    override fun notify(title: String, message: String, ref: String?) {
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN_EIDOS_INDEX, true)
            if (!ref.isNullOrBlank()) putExtra(EXTRA_TAG_HINT_REF, ref)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(
            context,
            stableNotificationId(ref, title, message),
            intent,
            flags,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(
            stableNotificationId(ref, title, message),
            notification,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Background Tag & Hint indexing notifications"
        }
        manager.createNotificationChannel(channel)
    }

    private fun stableNotificationId(ref: String?, title: String, message: String): Int {
        return listOf(ref.orEmpty(), title, message).joinToString("|").hashCode()
    }

    companion object {
        const val CHANNEL_ID = "tag_hint_index"
        const val CHANNEL_NAME = "Tag & Hint Index"
        const val EXTRA_OPEN_EIDOS_INDEX = "open_eidos_index"
        const val EXTRA_TAG_HINT_REF = "tag_hint_ref"
    }
}
