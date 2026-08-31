package com.example.optimalx.data.eidos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.optimalx.MainActivity
import com.example.optimalx.R
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the process in the foreground while Eidos is calling the LLM API so Android does not
 * throttle DNS/network when the user minimizes the app or closes the chat sheet.
 */
class EidosSendForegroundService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var promotedOnThisInstance = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // Promote immediately — onStartCommand can be delayed when the main thread is busy
        // (e.g. WebView compositing while opening a subfolder file), and Android kills the
        // process if startForeground() is not called within the FGS deadline.
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        // Release may run before lifecycle callbacks when work finishes quickly.
        // Defer the idle check so a back-to-back acquire() is not racing stopSelf().
        scheduleStopIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregroundStarted.set(false)
        super.onDestroy()
    }

    private fun promoteToForeground() {
        if (promotedOnThisInstance) return
        promotedOnThisInstance = true
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted.set(true)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
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

    private fun scheduleStopIfIdle() {
        mainHandler.post {
            if (activeSends.get() <= 0) {
                stopForegroundAndSelf()
            }
        }
    }

    private fun stopForegroundAndSelf() {
        foregroundStarted.set(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        private const val NOTIFICATION_ID = 9002
        private const val CHANNEL_ID = "eidos_active_send"
        private const val CHANNEL_NAME = "Eidos active requests"

        private val activeSends = AtomicInteger(0)
        private val foregroundStarted = AtomicBoolean(false)

        fun acquire(context: Context) {
            val app = context.applicationContext
            if (activeSends.incrementAndGet() == 1) {
                val start = {
                    ContextCompat.startForegroundService(
                        app,
                        Intent(app, EidosSendForegroundService::class.java),
                    )
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    start()
                } else {
                    // Prefer the front of the main queue so service lifecycle runs before heavy UI work.
                    mainHandler.postAtFrontOfQueue(start)
                }
            }
        }

        fun release(context: Context) {
            val app = context.applicationContext
            val remaining = activeSends.decrementAndGet()
            if (remaining <= 0) {
                activeSends.set(0)
                // Never stop before startForeground() — onCreate/onStartCommand will stop if idle.
                if (foregroundStarted.get()) {
                    app.stopService(Intent(app, EidosSendForegroundService::class.java))
                }
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
