package com.example.optimalx.data.eidos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.optimalx.MainActivity
import com.example.optimalx.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the process in the foreground while Eidos is calling the LLM API so Android does not
 * throttle DNS/network when the user minimizes the app or closes the chat sheet.
 */
class EidosSendForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Eidos")
            .setContentText("Working on your request…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        activeSends.set(0)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 9002
        private const val CHANNEL_ID = "eidos_active_send"
        private const val CHANNEL_NAME = "Eidos active requests"

        private val activeSends = AtomicInteger(0)

        fun acquire(context: Context) {
            val app = context.applicationContext
            if (activeSends.incrementAndGet() == 1) {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, EidosSendForegroundService::class.java),
                )
            }
        }

        fun release(context: Context) {
            val app = context.applicationContext
            val remaining = activeSends.decrementAndGet()
            if (remaining <= 0) {
                activeSends.set(0)
                app.stopService(Intent(app, EidosSendForegroundService::class.java))
            }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Eidos is processing a request in the background"
                },
            )
        }
    }
}
