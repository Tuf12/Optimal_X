package com.example.optimalx.widget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import com.example.optimalx.voice.WebSearchSttSession
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.OptimalXTheme
import com.example.optimalx.ui.web.decodeWebRecentSearchEntriesPermissive
import com.example.optimalx.ui.web.encodeWebRecentSearchEntries
import com.example.optimalx.ui.web.mergeRecentSearchesNewestFirst
import com.example.optimalx.ui.web.normalizeWebSearchKey
import com.example.optimalx.ui.web.webPanelRecentSearchesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val WIDGET_WEB_SCOPE = "widget_quick_web"

/**
 * Lightweight entry from the home-screen widget: type or dictate a query/URL, then open
 * [WidgetWebActivity]. A separate widget control opens the web panel immediately without this screen.
 */
class WidgetWebSearchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OptimalXTheme {
                val colors = LocalOptimalXColors.current
                WidgetWebSearchScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background),
                    onGo = { query ->
                        startActivity(
                            Intent(this, WidgetWebActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                )
                                if (query.isNotBlank()) {
                                    putExtra(WidgetWebActivity.EXTRA_INITIAL_QUERY, query)
                                }
                            },
                        )
                        finish()
                    },
                    onOpenWebPanelOnly = {
                        startActivity(
                            Intent(this, WidgetWebActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                )
                            },
                        )
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }
}

@Composable
private fun WidgetWebSearchScreen(
    modifier: Modifier = Modifier,
    onGo: (String) -> Unit,
    onOpenWebPanelOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var searchListening by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val searchStt = remember { WebSearchSttSession(context) }

    DisposableEffect(searchStt) {
        searchStt.onPartialResult = { partial ->
            if (searchListening) query = partial
        }
        onDispose { searchStt.destroy() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            searchListening = true
            searchStt.startListening(baseText = query)
        }
    }


    LaunchedEffect(Unit) {
        delay(60)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    BackHandler { onDismiss() }

    Column(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close",
                    tint = colors.textPrimary,
                )
            }
            Text(
                text = "Search or URL",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                keyboard?.hide()
                onOpenWebPanelOnly()
            }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Panel",
                        color = colors.accent,
                        fontFamily = DmSansFamily,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
            placeholder = {
                Text(
                    text = "Search the web or enter a URL",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (searchListening) {
                            searchStt.stopListeningAndCommit { spoken ->
                                searchListening = false
                                query = spoken
                                keyboard?.hide()
                                if (spoken.isNotBlank()) onGo(spoken)
                            }
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                searchListening = true
                                searchStt.startListening(baseText = query)
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (searchListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Voice input",
                        tint = if (searchListening) colors.accent else colors.textPrimary,
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                focusedBorderColor = colors.accentBorder,
                unfocusedBorderColor = colors.border,
                focusedContainerColor = colors.surface2,
                unfocusedContainerColor = colors.surface2,
                cursorColor = colors.accent,
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = {
                    keyboard?.hide()
                    onGo(query.trim())
                },
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    keyboard?.hide()
                    onGo(query.trim())
                },
            ) {
                Text(
                    text = "Go",
                    fontFamily = DmSansFamily,
                )
            }
        }

        val recentSearches by context.settingsDataStore.data
            .map { prefs ->
                mergeRecentSearchesNewestFirst(
                    decodeWebRecentSearchEntriesPermissive(prefs[webPanelRecentSearchesKey])
                        .filter { it.scopeKey == WIDGET_WEB_SCOPE },
                    max = 50,
                )
            }
            .collectAsState(initial = emptyList())

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = colors.border,
        )
        Text(
            text = "Recent searches",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 12.sp,
        )
        if (recentSearches.isEmpty()) {
            Text(
                text = "No recent searches yet.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = colors.textDim,
                fontFamily = DmSansFamily,
                fontSize = 13.sp,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            ) {
                items(
                    recentSearches,
                    key = { "${it.scopeKey}|${it.createdAtMillis}|${it.query}" },
                ) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                            .clickable {
                                keyboard?.hide()
                                onGo(item.query)
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = colors.surface2,
                        border = BorderStroke(1.dp, colors.border),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = item.query,
                                color = colors.textPrimary,
                                fontFamily = DmSansFamily,
                                fontSize = 15.sp,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            )
                            {
                                Text(
                                    text = compactUrlDisplay(item.url),
                                    modifier = Modifier.weight(1f),
                                    color = colors.textDim,
                                    fontFamily = DmSansFamily,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    onClick = {
                                        val deleteKey = normalizeWebSearchKey(item.query)
                                        scope.launch {
                                            val app = context.applicationContext as OptimalXApplication
                                            if (deleteKey != null) {
                                                app.database.conversationDao()
                                                    .getByWebWidgetSearch(deleteKey)
                                                    ?.let { conv ->
                                                        app.database.chatMessageDao()
                                                            .deleteAllByConversation(conv.id)
                                                        app.database.conversationDao()
                                                            .deleteWebWidgetSearch(deleteKey)
                                                    }
                                            }
                                            context.settingsDataStore.edit { prefs ->
                                                val existing = decodeWebRecentSearchEntriesPermissive(
                                                    prefs[webPanelRecentSearchesKey],
                                                )
                                                val updated = existing.filterNot { entry ->
                                                    entry.scopeKey == WIDGET_WEB_SCOPE &&
                                                        normalizeWebSearchKey(entry.query) == deleteKey
                                                }
                                                prefs[webPanelRecentSearchesKey] =
                                                    encodeWebRecentSearchEntries(updated)
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        text = "Delete",
                                        color = colors.textMid,
                                        fontFamily = DmSansFamily,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun compactUrlDisplay(url: String): String {
    val u = url.trim().removePrefix("https://").removePrefix("http://")
    val host = u.substringBefore('/').substringBefore('?')
    return host.ifBlank { url }
}
