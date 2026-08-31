package com.example.optimalx

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.optimalx.data.eidos.EidosChatSendWorker
import com.example.optimalx.data.eidos.EidosNavigationCodec
import com.example.optimalx.data.eidos.EidosNavigationTarget
import com.example.optimalx.ui.navigation.AppNavigation
import com.example.optimalx.ui.theme.OptimalXTheme
import com.example.optimalx.ui.theme.getThemePreference

class MainActivity : ComponentActivity() {
    private var pendingOpenConversationId by mutableStateOf<Long?>(null)
    private var pendingOpenNavigationTargetsJson by mutableStateOf<String?>(null)
    private var pendingOpenNavigationUri by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op: app can still run without notifications */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        applyLaunchIntent(intent)
        val app = application as OptimalXApplication
        setContent {
            val themePreference by getThemePreference(this).collectAsState(initial = "system")
            OptimalXTheme(themePreference = themePreference) {
                AppNavigation(
                    folderRepository = app.folderRepository,
                    pendingOpenConversationId = pendingOpenConversationId,
                    onPendingOpenConversationConsumed = { pendingOpenConversationId = null },
                    pendingOpenNavigationTargetsJson = pendingOpenNavigationTargetsJson,
                    pendingOpenNavigationUri = pendingOpenNavigationUri,
                    onPendingOpenNavigationConsumed = {
                        pendingOpenNavigationTargetsJson = null
                        pendingOpenNavigationUri = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent(intent)
    }

    private fun applyLaunchIntent(intent: Intent?) {
        pendingOpenConversationId = readOpenConversationId(intent)
        pendingOpenNavigationTargetsJson = intent
            ?.getStringExtra(EXTRA_EIDOS_NAVIGATION_TARGETS_JSON)
            ?.takeIf { it.isNotBlank() }
        pendingOpenNavigationUri = intent
            ?.getStringExtra(EXTRA_EIDOS_NAVIGATION_URI)
            ?.takeIf { it.isNotBlank() }
    }

    private fun readOpenConversationId(intent: Intent?): Long? {
        val id = intent?.getLongExtra(EidosChatSendWorker.EXTRA_OPEN_CONVERSATION_ID, -1L) ?: -1L
        return id.takeIf { it > 0L }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_EIDOS_NAVIGATION_TARGETS_JSON = "eidos_navigation_targets_json"
        const val EXTRA_EIDOS_NAVIGATION_URI = "eidos_navigation_uri"

        /** Open the main app on an Eidos navigation target (used by widget chat chips). */
        fun intentForNavigationTarget(context: Context, target: EidosNavigationTarget): Intent? {
            val json = EidosNavigationCodec.serializeTargets(listOf(target)) ?: return null
            return Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(EXTRA_EIDOS_NAVIGATION_TARGETS_JSON, json)
            }
        }

        fun intentForOptimalxUri(context: Context, uri: String): Intent? {
            if (!uri.startsWith("optimalx://")) return null
            if (EidosNavigationCodec.parseOptimalxUri(uri) == null) return null
            return Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(EXTRA_EIDOS_NAVIGATION_URI, uri)
            }
        }
    }
}
