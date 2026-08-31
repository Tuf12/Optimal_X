package com.example.optimalx.ui.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.optimalx.ui.theme.LocalOptimalXColors
import kotlinx.coroutines.CompletableDeferred

@Composable
fun RegisterNavigationLeaveGuard(
    hasUnsavedChanges: Boolean,
    title: String = "Discard unsaved changes?",
    message: String = "You have unsaved edits. Leave without saving?",
) {
    val colors = LocalOptimalXColors.current
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    DisposableEffect(hasUnsavedChanges) {
        val handler: suspend () -> Boolean = {
            if (!hasUnsavedChanges) {
                true
            } else {
                val deferred = CompletableDeferred<Boolean>()
                pending = deferred
                deferred.await()
            }
        }
        NavigationLeaveGuard.register(handler)
        onDispose {
            NavigationLeaveGuard.unregister(handler)
            // If a chip/link awaited this dialog and the screen disposed, unblock the waiter.
            pending?.let { deferred ->
                if (!deferred.isCompleted) deferred.complete(false)
            }
            pending = null
        }
    }

    pending?.let { deferred ->
        AlertDialog(
            onDismissRequest = {
                deferred.complete(false)
                pending = null
            },
            title = { Text(title, color = colors.textPrimary) },
            text = { Text(message, color = colors.textDim) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deferred.complete(true)
                        pending = null
                    },
                ) {
                    Text("Leave", color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deferred.complete(false)
                        pending = null
                    },
                ) {
                    Text("Stay", color = colors.textDim)
                }
            },
        )
    }
}
