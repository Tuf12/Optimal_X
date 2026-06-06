package com.example.optimalx.ui.web

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.ui.components.ChatComposerBar
import com.example.optimalx.ui.components.ClearVoiceRecordingDialog
import com.example.optimalx.ui.components.ChatMessageBubbleFooter
import com.example.optimalx.ui.components.ChatTopBar
import com.example.optimalx.ui.components.MarkdownRichText
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.eidos.KimiStreamPreviewBubble
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.SyneFamily
import com.example.optimalx.voice.VoiceController
import com.example.optimalx.voice.VoiceSessionState
import com.example.optimalx.voice.WebSearchSttSession
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val WEB_PANEL_TAG = "WebPanel"

private val DEFAULT_WEB_BAR_SHORTCUTS = listOf(
    "https://duckduckgo.com/",
    "https://docs.anthropic.com/",
    "https://github.com/",
    "https://stackoverflow.com/",
    "https://developer.android.com/",
    "https://kotlinlang.org/",
)

@Composable
private fun WebChromeSquareButton(
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
) {
    val colors = LocalOptimalXColors.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
        shape = RoundedCornerShape(8.dp),
        color = colors.surface2,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(15.dp),
                tint = if (enabled) colors.textMid else colors.textDim.copy(alpha = 0.35f),
            )
        }
    }
}

@Composable
private fun WebRecentShortcutChip(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        color = colors.surface2,
        border = BorderStroke(1.dp, colors.border),
        shape = RoundedCornerShape(7.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WebEidosLaunchButton(
    onClick: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val pulse = rememberInfiniteTransition(label = "eidosDot").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eidosDotA",
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, colors.accentBorder), RoundedCornerShape(10.dp))
            .background(colors.accentDim)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = pulse.value)),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = "Open Eidos chat",
            modifier = Modifier.size(17.dp),
            tint = colors.accent,
        )
    }
}

@Composable
fun WebPanel(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") panelTitle: String,
    eidosViewModel: EidosChatViewModel,
    scopeKey: String = "global",
    initialUrl: String = "",
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val colors = LocalOptimalXColors.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Key by initialUrl so a new widget search/URL does not reuse saved URL + "already restored" from a prior visit.
    var urlInput by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var currentUrl by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var requestedUrl by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var fallbackUrl by remember { mutableStateOf<String?>(null) }
    var fallbackReason by remember { mutableStateOf("") }
    var lastFallbackSignature by rememberSaveable { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showSearchHistoryTab by rememberSaveable { mutableStateOf(false) }
    var hasRestoredSessionUrl by rememberSaveable(initialUrl) { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    val eidosSheetOpen by eidosViewModel.webPanelEidosSheetOpen.collectAsState()
    val eidosListState = rememberLazyListState()

    val messages by eidosViewModel.messages.collectAsState()
    val input by eidosViewModel.input.collectAsState()
    val isSending by eidosViewModel.isSending.collectAsState()
    val showKimiThinking by eidosViewModel.showKimiThinkingIndicator.collectAsState()
    val streamPreview by eidosViewModel.streamPreview.collectAsState()
    val readAloud by eidosViewModel.readAloud.collectAsState()
    val readAloudMicPassback by eidosViewModel.readAloudMicPassback.collectAsState()
    val readAloudInfoDismissed by eidosViewModel.readAloudInfoDismissed.collectAsState()
    val micUseWhisperApi by eidosViewModel.micUseWhisperApi.collectAsState()
    val hasOpenAiApiKey by eidosViewModel.hasOpenAiApiKey.collectAsState()
    val hasActiveConversation by eidosViewModel.hasActiveConversation.collectAsState()
    val conversationMemoryLabel by eidosViewModel.conversationMemoryLabel.collectAsState()
    val rereadMessageId by eidosViewModel.rereadMessageId.collectAsState()
    val chatScopeLabel by eidosViewModel.chatScopeLabel.collectAsState()
    val pendingConfirmation by eidosViewModel.pendingConfirmation.collectAsState()

    var addressFocused by remember { mutableStateOf(false) }
    var addressBarExpanded by remember { mutableStateOf(false) }
    var addressEditField by remember { mutableStateOf(TextFieldValue()) }
    var addressSearchListening by remember { mutableStateOf(false) }
    val addressSearchStt = remember { WebSearchSttSession(context) }
    val addressFocusRequester = remember { FocusRequester() }
    val recentsScrollState = rememberScrollState()
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val persistedLastUrl by context.settingsDataStore.data
        .map { prefs ->
            decodeLastUrlEntries(prefs[WEB_LAST_URL_KEY])
                .firstOrNull { scopeMatches(scopeKey, it.scopeKey) }
                ?.url
        }
        .collectAsState(initial = null)
    val bookmarks by context.settingsDataStore.data
        .map { prefs ->
            decodeBookmarks(prefs[WEB_BOOKMARKS_KEY])
                .asSequence()
                .filter { scopeMatches(scopeKey, it.scopeKey) }
                .toList()
        }
        .collectAsState(initial = emptyList())
    val recentPages by context.settingsDataStore.data
        .map { prefs ->
            decodeRecentEntries(prefs[WEB_RECENT_PAGES_KEY])
                .asSequence()
                .filter { scopeMatches(scopeKey, it.scopeKey) }
                .map { it.value }
                .toList()
        }
        .collectAsState(initial = emptyList())
    val recentSearches by context.settingsDataStore.data
        .map { prefs ->
            decodeRecentSearchEntries(prefs[WEB_RECENT_SEARCHES_KEY])
                .asSequence()
                .filter { scopeMatches(scopeKey, it.scopeKey) }
                .toList()
        }
        .collectAsState(initial = emptyList())
    val activeValidUrl = (normalizeAndValidateUrl(currentUrl.ifBlank { urlInput }) as? UrlResult.Valid)?.url
    val isActiveBookmarked = activeValidUrl != null && bookmarks.any { it.url == activeValidUrl }
    val webSubfolderId = remember(scopeKey) { subfolderIdFromWebScopeKey(scopeKey) }

    LaunchedEffect(activeValidUrl) {
        eidosViewModel.reportWebPanelPageUrl(activeValidUrl)
    }
    DisposableEffect(eidosViewModel) {
        onDispose {
            eidosViewModel.reportWebPanelPageUrl(null)
        }
    }

    val voiceController: VoiceController = viewModel(
        key = "web_panel_eidos_voice",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                VoiceController(app as Application)
            }
        },
    )
    val sessionState by voiceController.sessionState.collectAsState()
    val liveTranscript by voiceController.liveTranscript.collectAsState()
    val usesWhisperCapture by voiceController.usesWhisperCapture.collectAsState()

    LaunchedEffect(micUseWhisperApi) {
        eidosViewModel.refreshOpenAiKeyPresence()
        voiceController.applyMicEnginePreferenceFromSettings()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voiceController, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val activity = context as? android.app.Activity
                if (activity == null || !activity.isChangingConfigurations) {
                    voiceController.stopSession()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voiceController.stopSession()
        }
    }

    LaunchedEffect(eidosSheetOpen) {
        if (!eidosSheetOpen) {
            voiceController.stopSession()
            eidosViewModel.setRereadMessageId(null)
        }
    }

    LaunchedEffect(activeValidUrl, eidosSheetOpen) {
        if (!eidosSheetOpen) return@LaunchedEffect
        val url = activeValidUrl ?: return@LaunchedEffect
        eidosViewModel.ensureWebPageChatContext(compactUrlForDisplay(url))
    }

    val isListening = sessionState == VoiceSessionState.LISTENING
    val isTranscribing = sessionState == VoiceSessionState.TRANSCRIBING
    val isPaused = sessionState == VoiceSessionState.PAUSED
    val isSpeaking = sessionState == VoiceSessionState.SPEAKING
    val isCapturingVoice = if (usesWhisperCapture) isListening || isTranscribing else isListening

    var lastSeenEidosMessageCount by remember { mutableIntStateOf(0) }
    var initialEidosMessagesLoadDone by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size, readAloud, readAloudMicPassback, eidosSheetOpen) {
        if (!eidosSheetOpen) return@LaunchedEffect
        if (!initialEidosMessagesLoadDone) {
            lastSeenEidosMessageCount = messages.size
            if (messages.isNotEmpty()) initialEidosMessagesLoadDone = true
        } else if (messages.size > lastSeenEidosMessageCount) {
            val last = messages.lastOrNull()
            if (last != null && last.role == EidosRole.ASSISTANT && readAloud) {
                voiceController.speakResponse(
                    text = last.text,
                    thenListen = readAloudMicPassback,
                    onListenResult = { eidosViewModel.setInput(it) },
                )
            }
            lastSeenEidosMessageCount = messages.size
        } else {
            lastSeenEidosMessageCount = messages.size
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                voiceController.startListening(existingText = input) { text -> eidosViewModel.setInput(text) }
            }
        },
    )
    val addressSearchPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                addressSearchListening = true
                addressSearchStt.startListening(baseText = addressEditField.text)
            }
        },
    )
    DisposableEffect(addressSearchStt) {
        addressSearchStt.onPartialResult = { partial ->
            addressEditField = TextFieldValue(partial, TextRange(partial.length))
            urlInput = partial
        }
        onDispose { addressSearchStt.destroy() }
    }

    fun updateNavState() {
        canGoBack = webView?.canGoBack() == true
        canGoForward = webView?.canGoForward() == true
    }

    fun collapseAddressBar() {
        urlInput = addressEditField.text
        addressBarExpanded = false
    }

    fun expandAddressBar() {
        overflowMenuExpanded = false
        addressEditField = TextFieldValue(
            text = urlInput,
            selection = TextRange(0, urlInput.length),
        )
        addressBarExpanded = true
    }

    BackHandler(enabled = addressBarExpanded || eidosSheetOpen || canGoBack) {
        when {
            addressBarExpanded -> collapseAddressBar()
            eidosSheetOpen -> eidosViewModel.setWebPanelEidosSheetOpen(false)
            else -> {
                webView?.goBack()
                updateNavState()
            }
        }
    }

    BackHandler(enabled = customView != null) {
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    fun openInPanel(url: String) {
        pageError = null
        urlInput = url
        currentUrl = url
        requestedUrl = url
        addressBarExpanded = false
        webView?.loadUrl(url)
    }

    fun persistLastUrl(rawUrl: String) {
        val validUrl = (normalizeAndValidateUrl(rawUrl) as? UrlResult.Valid)?.url ?: return
        scope.launch {
            context.settingsDataStore.edit { prefs ->
                val updated = upsertLastUrlEntry(
                    existing = decodeLastUrlEntries(prefs[WEB_LAST_URL_KEY]),
                    scopeKey = scopeKey,
                    url = validUrl,
                )
                prefs[WEB_LAST_URL_KEY] = encodeLastUrlEntries(updated)
            }
        }
    }

    fun persistRecentPage(url: String) {
        val validUrl = (normalizeAndValidateUrl(url) as? UrlResult.Valid)?.url ?: return
        if (isSearchResultUrl(validUrl)) return
        scope.launch {
            context.settingsDataStore.edit { prefs ->
                val updated = upsertRecentEntry(
                    existing = decodeRecentEntries(prefs[WEB_RECENT_PAGES_KEY]),
                    scopeKey = scopeKey,
                    value = validUrl,
                )
                prefs[WEB_RECENT_PAGES_KEY] = encodeRecentEntries(updated)
            }
        }
    }

    fun persistRecentSearch(query: String, resolvedUrl: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val validUrl = (normalizeAndValidateUrl(resolvedUrl) as? UrlResult.Valid)?.url ?: return
        eidosViewModel.setWebSearchContext(trimmed)
        scope.launch {
            context.settingsDataStore.edit { prefs ->
                val updated = upsertRecentSearchEntry(
                    existing = decodeRecentSearchEntries(prefs[WEB_RECENT_SEARCHES_KEY]),
                    scopeKey = scopeKey,
                    query = trimmed,
                    url = validUrl,
                )
                prefs[WEB_RECENT_SEARCHES_KEY] = encodeRecentSearchEntries(updated)
            }
        }
    }

    fun saveCurrentBookmark() {
        val url = activeValidUrl
        if (url == null) {
            pageError = "Open a valid URL before saving a bookmark."
            return
        }
        scope.launch {
            context.settingsDataStore.edit { prefs ->
                val updated = upsertBookmark(
                    existing = decodeBookmarks(prefs[WEB_BOOKMARKS_KEY]),
                    scopeKey = scopeKey,
                    url = url,
                )
                prefs[WEB_BOOKMARKS_KEY] = encodeBookmarks(updated)
            }
        }
    }

    fun copyCurrentUrl() {
        val url = activeValidUrl
        if (url == null) {
            pageError = "Open a valid URL before copying."
            return
        }
        clipboardManager.setText(AnnotatedString(url))
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }

    fun shareCurrentUrl() {
        val url = activeValidUrl
        if (url == null) {
            pageError = "Open a valid URL before sharing."
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        runCatching {
            context.startActivity(Intent.createChooser(shareIntent, "Share link"))
        }.onFailure {
            if (it !is ActivityNotFoundException) throw it
        }
    }

    fun promptFallback(targetUrl: String, reason: String) {
        val signature = "$targetUrl|$reason"
        if (signature == lastFallbackSignature) return
        lastFallbackSignature = signature
        fallbackUrl = targetUrl
        fallbackReason = reason
    }

    fun submitInput(rawInput: String) {
        val prepared = normalizeAndValidateUrl(rawInput)
        when (prepared) {
            is UrlResult.Valid -> {
                pageError = null
                currentUrl = prepared.url
                requestedUrl = prepared.url
                urlInput = prepared.url
                addressBarExpanded = false
                persistLastUrl(prepared.url)
                prepared.searchQuery?.let { query ->
                    persistRecentSearch(query, prepared.url)
                }
                webView?.loadUrl(prepared.url)
            }
            is UrlResult.Invalid -> {
                pageError = prepared.message
            }
        }
    }

    fun loadTypedUrl() = submitInput(urlInput)

    fun loadTypedUrlFromExpandedEditor() {
        urlInput = addressEditField.text
        loadTypedUrl()
    }

    var showClearRecordingDialog by remember { mutableStateOf(false) }

    fun commitEidosSend() {
        voiceController.commitVoiceThen(mergeBaseText = input) { eidosViewModel.sendMessage() }
    }

    val loadBarPulse = rememberInfiniteTransition(label = "webPanelLoadPulse").animateFloat(
        initialValue = 0.12f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "webPanelLoadPulseV",
    )

    var webScrollAnchorFirstMessageId by remember { mutableStateOf<Long?>(null) }
    var webScrollAnchorMessageCount by remember { mutableIntStateOf(-1) }
    val webEidosNearBottom by remember {
        derivedStateOf {
            val layout = eidosListState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            if (lastVisible == null || layout.totalItemsCount == 0) {
                true
            } else {
                lastVisible.index >= layout.totalItemsCount - 2
            }
        }
    }
    LaunchedEffect(eidosSheetOpen) {
        if (!eidosSheetOpen) {
            webScrollAnchorFirstMessageId = null
            webScrollAnchorMessageCount = -1
        }
    }
    val streamPreviewLength = streamPreview?.contentText?.length ?: 0
    val streamReasoningLength = streamPreview?.reasoningText?.length ?: 0
    val lastMessageTextLength = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(
        messages.size,
        messages.firstOrNull()?.id,
        lastMessageTextLength,
        eidosSheetOpen,
        eidosListState.layoutInfo.totalItemsCount,
        showKimiThinking,
        isSending,
        streamPreviewLength,
        streamReasoningLength,
    ) {
        if (!eidosSheetOpen || messages.isEmpty()) return@LaunchedEffect
        val firstId = messages.firstOrNull()?.id ?: return@LaunchedEffect
        val conversationChanged =
            webScrollAnchorFirstMessageId != null && webScrollAnchorFirstMessageId != firstId
        val pendingInitialScroll = webScrollAnchorFirstMessageId == null
        val newMessagesAppended = messages.size > webScrollAnchorMessageCount
        val streamGrowing = showKimiThinking && isSending &&
            (streamPreviewLength > 0 || streamReasoningLength > 0)
        val shouldScroll = conversationChanged ||
            pendingInitialScroll ||
            (newMessagesAppended && webEidosNearBottom) ||
            (streamGrowing && webEidosNearBottom)
        if (!shouldScroll) return@LaunchedEffect
        val end = messages.lastIndex + if (isSending) 1 else 0
        if (eidosListState.layoutInfo.totalItemsCount <= end) return@LaunchedEffect
        eidosListState.scrollToItem(end)
        webScrollAnchorFirstMessageId = firstId
        webScrollAnchorMessageCount = messages.size
    }

    LaunchedEffect(addressBarExpanded) {
        if (!addressBarExpanded) return@LaunchedEffect
        delay(48)
        runCatching { addressFocusRequester.requestFocus() }
    }

    LaunchedEffect(initialUrl, persistedLastUrl, hasRestoredSessionUrl) {
        if (hasRestoredSessionUrl) return@LaunchedEffect
        val initialTrim = initialUrl.trim()
        if (initialTrim.isNotBlank()) {
            val prepared = normalizeAndValidateUrl(initialTrim)
            if (prepared is UrlResult.Valid) {
                persistLastUrl(prepared.url)
                prepared.searchQuery?.let { query ->
                    persistRecentSearch(query, prepared.url)
                }
                openInPanel(prepared.url)
            }
            hasRestoredSessionUrl = true
            return@LaunchedEffect
        }
        val raw = persistedLastUrl.orEmpty()
        if (raw.isNotBlank()) {
            val prepared = normalizeAndValidateUrl(raw)
            if (prepared is UrlResult.Valid) {
                openInPanel(prepared.url)
            }
            hasRestoredSessionUrl = true
            return@LaunchedEffect
        }
        if (persistedLastUrl != null) {
            hasRestoredSessionUrl = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
            persistLastUrl(currentUrl.ifBlank { requestedUrl.ifBlank { urlInput } })
            webView?.apply {
                stopLoading()
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                destroy()
            }
            webView = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(colors.background),
    ) {
        val recentsBarUrls = if (recentPages.isNotEmpty()) recentPages.take(14) else DEFAULT_WEB_BAR_SHORTCUTS
        val showHttpsLock = activeValidUrl?.startsWith("https:", ignoreCase = true) == true
        val sheetContextLabel = (normalizeAndValidateUrl(currentUrl.ifBlank { urlInput }) as? UrlResult.Valid)
            ?.url
            ?.let { compactUrlForDisplay(it) }
            ?: "No page loaded"

        Surface(color = colors.surface, shadowElevation = 0.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WebChromeSquareButton(
                            enabled = canGoBack,
                            onClick = { webView?.goBack(); updateNavState() },
                            contentDescription = "Back",
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                        )
                        WebChromeSquareButton(
                            enabled = canGoForward,
                            onClick = { webView?.goForward(); updateNavState() },
                            contentDescription = "Forward",
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                        )
                        WebChromeSquareButton(
                            enabled = true,
                            onClick = { webView?.reload() },
                            contentDescription = "Refresh",
                            icon = Icons.Default.Refresh,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (addressBarExpanded || addressFocused) colors.accentBorder else colors.border,
                                ),
                                RoundedCornerShape(10.dp),
                            )
                            .background(colors.surface2),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (showHttpsLock) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = colors.textDim,
                                )
                            } else {
                                Spacer(Modifier.width(4.dp))
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = { expandAddressBar() }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = urlInput.ifBlank { "Search or enter address" },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (urlInput.isBlank()) colors.textDim else colors.textPrimary,
                                    fontFamily = DmMonoFamily,
                                    fontSize = 12.5.sp,
                                    letterSpacing = 0.15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Surface(
                                onClick = { loadTypedUrl() },
                                shape = RoundedCornerShape(6.dp),
                                color = colors.accent,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = colors.background,
                                    )
                                    Text(
                                        "Go",
                                        color = colors.background,
                                        fontFamily = DmSansFamily,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { overflowMenuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = colors.textMid,
                            )
                        }
                        DropdownMenu(
                            expanded = overflowMenuExpanded,
                            onDismissRequest = { overflowMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Bookmarks") },
                                onClick = {
                                    overflowMenuExpanded = false
                                    showBookmarks = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Recent pages & searches") },
                                onClick = {
                                    overflowMenuExpanded = false
                                    showHistory = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (isActiveBookmarked) "Remove bookmark" else "Save bookmark") },
                                onClick = {
                                    overflowMenuExpanded = false
                                    if (isActiveBookmarked) {
                                        val toRemove = activeValidUrl ?: return@DropdownMenuItem
                                        scope.launch {
                                            context.settingsDataStore.edit { prefs ->
                                                val updated = decodeBookmarks(prefs[WEB_BOOKMARKS_KEY])
                                                    .filterNot {
                                                        scopeMatches(scopeKey, it.scopeKey) &&
                                                            it.url.equals(toRemove, ignoreCase = true)
                                                    }
                                                prefs[WEB_BOOKMARKS_KEY] = encodeBookmarks(updated)
                                            }
                                        }
                                    } else {
                                        saveCurrentBookmark()
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy link") },
                                onClick = {
                                    overflowMenuExpanded = false
                                    copyCurrentUrl()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    overflowMenuExpanded = false
                                    shareCurrentUrl()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Open in browser") },
                                onClick = {
                                    overflowMenuExpanded = false
                                    openExternal(context, currentUrl.ifBlank { urlInput })
                                },
                            )
                        }
                    }

                    WebEidosLaunchButton(
                        onClick = { eidosViewModel.setWebPanelEidosSheetOpen(!eidosSheetOpen) },
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = addressBarExpanded,
                    enter = fadeIn(animationSpec = tween(160)) + slideInVertically(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        initialOffsetY = { -it / 8 },
                    ),
                    exit = fadeOut(animationSpec = tween(120)) + slideOutVertically(
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        targetOffsetY = { -it / 8 },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.Top)
                            .padding(top = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface2,
                        border = BorderStroke(1.dp, colors.accentBorder),
                        shadowElevation = 3.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(align = Alignment.Top)
                                .padding(12.dp)
                                .imePadding(),
                        ) {
                            BasicTextField(
                                value = addressEditField,
                                onValueChange = {
                                    addressEditField = it
                                    urlInput = it.text
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight(align = Alignment.Top)
                                    .heightIn(max = 280.dp)
                                    .focusRequester(addressFocusRequester)
                                    .onFocusChanged { addressFocused = it.isFocused },
                                textStyle = TextStyle(
                                    color = colors.textPrimary,
                                    fontFamily = DmSansFamily,
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                ),
                                maxLines = 8,
                                cursorBrush = SolidColor(colors.accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { loadTypedUrlFromExpandedEditor() }),
                                decorationBox = { inner ->
                                    Box(Modifier.wrapContentHeight(align = Alignment.Top)) {
                                        if (addressEditField.text.isEmpty()) {
                                            Text(
                                                text = "Search or enter address",
                                                color = colors.textDim,
                                                fontFamily = DmSansFamily,
                                                fontSize = 16.sp,
                                                lineHeight = 24.sp,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { collapseAddressBar() }) {
                                    Text(
                                        "Done",
                                        color = colors.textMid,
                                        fontFamily = DmSansFamily,
                                        fontSize = 14.sp,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        if (addressSearchListening) {
                                            addressSearchStt.stopListeningAndCommit { spoken ->
                                                addressSearchListening = false
                                                addressEditField = TextFieldValue(spoken, TextRange(spoken.length))
                                                urlInput = spoken
                                            }
                                        } else {
                                            val granted = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO,
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (granted) {
                                                addressSearchListening = true
                                                addressSearchStt.startListening(baseText = addressEditField.text)
                                            } else {
                                                addressSearchPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (addressSearchListening) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = "Voice search",
                                        tint = if (addressSearchListening) colors.accent else colors.textMid,
                                    )
                                }
                                Surface(
                                    onClick = { loadTypedUrlFromExpandedEditor() },
                                    shape = RoundedCornerShape(8.dp),
                                    color = colors.accent,
                                ) {
                                    Text(
                                        text = "Go",
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                        color = colors.background,
                                        fontFamily = DmSansFamily,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }

                if (!pageError.isNullOrBlank()) {
                    Text(
                        text = pageError.orEmpty(),
                        color = colors.textDim,
                        fontFamily = DmSansFamily,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = colors.border)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.border),
        ) {
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { loadBarPulse.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = colors.accent,
                    trackColor = androidx.compose.ui.graphics.Color.Transparent,
                )
            }
        }

        Surface(color = colors.surface, shadowElevation = 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(recentsScrollState)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "RECENT",
                    fontFamily = DmMonoFamily,
                    fontSize = 10.sp,
                    color = colors.textDim,
                    letterSpacing = 0.6.sp,
                )
                recentsBarUrls.forEach { entry ->
                    val chipLabel = if (
                        entry.startsWith("http://", ignoreCase = true) ||
                        entry.startsWith("https://", ignoreCase = true)
                    ) {
                        compactUrlForDisplay(entry)
                    } else {
                        entry
                    }
                    WebRecentShortcutChip(
                        label = chipLabel,
                        onClick = { openInPanel(entry) },
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = colors.border)

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isLoading) 0.92f else 1f),
                factory = { ctx ->
                    WebView(ctx).also { view ->
                        webView = view
                        configureWebView(view)
                        view.setBackgroundColor(colors.surface.toArgb())
                        // Keep vertical scroll gestures in WebView instead of parent HorizontalPager.
                        view.setOnTouchListener { v, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            false
                        }
                        view.webChromeClient = object : WebChromeClient() {
                            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                (view.parent as? ViewGroup)?.removeView(view)
                                customView = view
                                customViewCallback = callback
                            }

                            override fun onHideCustomView() {
                                customViewCallback?.onCustomViewHidden()
                                customView = null
                                customViewCallback = null
                            }

                            override fun onCreateWindow(
                                view: WebView,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message,
                            ): Boolean {
                                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                val popup = WebView(view.context).apply {
                                    configureWebView(this)
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            popupView: WebView,
                                            request: WebResourceRequest,
                                        ): Boolean {
                                            val target = request.url.toString()
                                            val normalized = normalizeAndValidateUrl(target) as? UrlResult.Valid
                                            if (normalized != null) {
                                                openInPanel(normalized.url)
                                            } else {
                                                openExternalIfSupported(context, target)
                                            }
                                            popupView.destroy()
                                            return true
                                        }
                                    }
                                }
                                transport.webView = popup
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        view.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val target = request.url.toString()
                                if (!request.isForMainFrame) return false
                                val result = normalizeAndValidateUrl(target)
                                if (result is UrlResult.Valid) {
                                    return false
                                }
                                val openedExternally = openExternalIfSupported(context, target)
                                if (openedExternally) return true
                                val reason = (result as? UrlResult.Invalid)?.message
                                    ?: "Blocked unsupported URL scheme."
                                promptFallback(target, reason)
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                isLoading = true
                                pageError = null
                                lastFallbackSignature = ""
                                currentUrl = url
                                requestedUrl = url
                                urlInput = url
                                addressBarExpanded = false
                                updateNavState()
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                isLoading = false
                                currentUrl = url
                                persistLastUrl(url)
                                persistRecentPage(url)
                                updateNavState()
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    val detail = error.description?.toString().orEmpty().ifBlank { "Page failed to load." }
                                    pageError = "Load error: $detail"
                                    if (shouldPromptFallbackForMainFrameError(error.errorCode)) {
                                        promptFallback(
                                            request.url.toString(),
                                            "The page failed to load in the in-app Web panel.",
                                        )
                                    }
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView,
                                handler: SslErrorHandler,
                                error: SslError,
                            ) {
                                handler.cancel()
                                pageError = "SSL error: secure connection failed."
                                promptFallback(error.url.orEmpty(), "This page has a certificate/security issue.")
                            }
                        }
                        if (initialUrl.isNotBlank()) {
                            val result = normalizeAndValidateUrl(initialUrl)
                            if (result is UrlResult.Valid) {
                                urlInput = result.url
                                currentUrl = result.url
                                requestedUrl = result.url
                                view.loadUrl(result.url)
                            }
                        }
                    }
                },
                update = { view ->
                    val target = requestedUrl.trim()
                    if (target.isNotBlank() && view.url != target) {
                        view.loadUrl(target)
                    }
                },
            )

            if (currentUrl.isBlank() && !isLoading && pageError.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = colors.accentDim,
                            border = BorderStroke(1.dp, colors.accentBorder),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(26.dp),
                                    tint = colors.accent,
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Search or navigate",
                                fontFamily = SyneFamily,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = "Type a URL or search term above",
                                fontFamily = DmSansFamily,
                                fontSize = 13.sp,
                                color = colors.textDim,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WebRecentShortcutChip(
                                label = "DuckDuckGo",
                                onClick = { openInPanel("https://duckduckgo.com/") },
                            )
                            WebRecentShortcutChip(
                                label = "Android Docs",
                                onClick = { openInPanel("https://developer.android.com/") },
                            )
                        }
                    }
                }
            }

            if (customView != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        android.widget.FrameLayout(ctx).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { container ->
                        container.removeAllViews()
                        val videoView = customView
                        if (videoView != null) {
                            (videoView.parent as? ViewGroup)?.removeView(videoView)
                            container.addView(
                                videoView,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                )
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = eidosSheetOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    color = colors.sheetBackground,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                    ) {
                        val sheetScopeLabel = buildString {
                            append(sheetContextLabel)
                            if (chatScopeLabel.isNotBlank()) {
                                append("\n")
                                append(chatScopeLabel)
                            }
                        }
                        ChatTopBar(
                            onBack = { eidosViewModel.setWebPanelEidosSheetOpen(false) },
                            scopeLabel = sheetScopeLabel,
                            workshopPhaseLabel = null,
                            memoryDepthLabel = conversationMemoryLabel,
                            onMemoryDepthClick = { eidosViewModel.cycleConversationMemoryDepth() },
                            hasActiveConversation = hasActiveConversation,
                            isSending = isSending,
                            onHistoryClick = {},
                            onNewChatClick = { eidosViewModel.newChat() },
                            onMoveClick = {},
                            onStopClick = { eidosViewModel.cancelActiveSend() },
                            readAloud = readAloud,
                            readAloudMicPassback = readAloudMicPassback,
                            onReadAloudChange = eidosViewModel::setReadAloud,
                            onMicPassbackChange = eidosViewModel::setReadAloudMicPassback,
                            readAloudInfoDismissed = readAloudInfoDismissed,
                            onReadAloudInfoDismissedChange = eidosViewModel::setReadAloudInfoDismissed,
                            micUseWhisperApi = micUseWhisperApi,
                            hasOpenAiApiKey = hasOpenAiApiKey,
                            onMicUseWhisperApiChange = eidosViewModel::setMicUseWhisperApi,
                            onOpenChatSettings = eidosViewModel::refreshOpenAiKeyPresence,
                            restrictToolbar = true,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { focusManager.clearFocus() },
                        ) {
                        LazyColumn(
                            state = eidosListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { focusManager.clearFocus() })
                                },
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(messages, key = { it.id }) { message ->
                                val isUser = message.role == EidosRole.USER
                                val align = if (isUser) Alignment.End else Alignment.Start
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = align,
                                ) {
                                    val bubbleShape = RoundedCornerShape(12.dp)
                                    val bubbleModifier = if (isUser) {
                                        Modifier
                                            .clip(bubbleShape)
                                            .background(colors.messageBubbleUser)
                                    } else {
                                        Modifier
                                            .border(1.dp, colors.messageBubbleEidosBorder, bubbleShape)
                                            .clip(bubbleShape)
                                            .background(
                                                brush = Brush.verticalGradient(
                                                    listOf(colors.accentDim, colors.surface2),
                                                ),
                                            )
                                    }
                                    Surface(
                                        shape = bubbleShape,
                                        color = Color.Transparent,
                                        modifier = Modifier.fillMaxWidth(0.86f),
                                    ) {
                                        Column(
                                            modifier = bubbleModifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 13.dp, vertical = 10.dp),
                                        ) {
                                            Text(
                                                text = if (isUser) "YOU" else "EIDOS",
                                                fontFamily = DmMonoFamily,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isUser) colors.textDim else colors.accent.copy(alpha = 0.7f),
                                                letterSpacing = 0.6.sp,
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                                            )
                                            MarkdownRichText(
                                                text = message.text,
                                                style = TextStyle(
                                                    color = colors.textPrimary,
                                                    fontFamily = DmSansFamily,
                                                    fontSize = 13.5.sp,
                                                    lineHeight = 20.sp,
                                                ),
                                                selectable = true,
                                            )
                                            ChatMessageBubbleFooter(timeLabel = message.timeLabel) {
                                                if (!isUser) {
                                                    val isPlaying = message.id == rereadMessageId
                                                    IconButton(
                                                        onClick = {
                                                            if (isPlaying) {
                                                                voiceController.stopSession()
                                                                eidosViewModel.setRereadMessageId(null)
                                                            } else {
                                                                voiceController.stopSession()
                                                                eidosViewModel.setRereadMessageId(message.id)
                                                                voiceController.speakResponse(
                                                                    text = message.text,
                                                                    thenListen = false,
                                                                    onDone = { eidosViewModel.setRereadMessageId(null) },
                                                                )
                                                            }
                                                        },
                                                        modifier = Modifier.size(28.dp),
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                            contentDescription = if (isPlaying) "Stop" else "Read aloud",
                                                            tint = if (isPlaying) colors.accent else colors.textDim,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    }
                                                }
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setText(AnnotatedString(message.text))
                                                    },
                                                    modifier = Modifier.size(28.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.ContentCopy,
                                                        contentDescription = "Copy",
                                                        tint = colors.textDim,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (showKimiThinking && isSending) {
                                item(key = "kimi_thinking") {
                                    KimiStreamPreviewBubble(
                                        preview = streamPreview,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                            } else if (isSending) {
                                item {
                                    Text(
                                        text = "Eidos is thinking…",
                                        fontFamily = DmSansFamily,
                                        fontSize = 12.sp,
                                        color = colors.textMid,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        }

                        HorizontalDivider(color = colors.border, thickness = 1.dp)

                        ChatComposerBar(
                            input = input,
                            onInputChange = eidosViewModel::setInput,
                            onSend = { commitEidosSend() },
                            onMicClick = {
                                when (sessionState) {
                                    VoiceSessionState.LISTENING,
                                    VoiceSessionState.PAUSED,
                                    -> voiceController.handleComposerMicTap(input)
                                    VoiceSessionState.SPEAKING -> voiceController.stopSession()
                                    VoiceSessionState.IDLE -> {
                                        val perm = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO,
                                        )
                                        if (perm == PackageManager.PERMISSION_GRANTED) {
                                            voiceController.startListening(existingText = input) { text ->
                                                eidosViewModel.setInput(text)
                                            }
                                        } else {
                                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                    else -> Unit
                                }
                            },
                            onDiscardRecording = {
                                if (usesWhisperCapture) showClearRecordingDialog = true
                            },
                            onStopClick = { eidosViewModel.cancelActiveSend() },
                            sessionState = sessionState,
                            isSending = isSending,
                            isCapturingVoice = isCapturingVoice,
                            isPaused = isPaused,
                            isSpeaking = isSpeaking,
                            liveTranscript = liveTranscript,
                            usesWhisperBufferedCapture = usesWhisperCapture,
                            onTextFieldFocused = {
                                if (isListening) voiceController.finalizeListeningForEdit(input)
                            },
                            modifier = Modifier.navigationBarsPadding(),
                        )
                    }
                }
            }
        }
    }

    if (showClearRecordingDialog) {
        ClearVoiceRecordingDialog(
            onConfirm = {
                showClearRecordingDialog = false
                voiceController.discardRecording()
            },
            onDismiss = { showClearRecordingDialog = false },
        )
    }

    pendingConfirmation?.let { req ->
        val friendlyName = req.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
        AlertDialog(
            onDismissRequest = { eidosViewModel.denyConfirmation() },
            title = {
                Text("Allow action?", fontFamily = DmSansFamily, color = colors.textPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Eidos wants to run: $friendlyName",
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                    )
                    if (req.argsJson.length <= 300) {
                        Text(
                            req.argsJson,
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { eidosViewModel.approveConfirmation() }) {
                    Text("Allow", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { eidosViewModel.denyConfirmation() }) {
                    Text("Deny", color = colors.textMid, fontFamily = DmSansFamily)
                }
            },
            containerColor = colors.sheetBackground,
        )
    }

    if (fallbackUrl != null) {
        AlertDialog(
            onDismissRequest = { fallbackUrl = null },
            title = { Text("Open in Browser?") },
            text = {
                Text(
                    text = buildString {
                        append(fallbackReason.ifBlank { "This page is not usable in the in-app Web panel." })
                        if (!fallbackUrl.isNullOrBlank()) {
                            append("\n\n")
                            append(fallbackUrl)
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        openExternal(context, fallbackUrl.orEmpty())
                        fallbackUrl = null
                    },
                ) { Text("Open in Browser") }
            },
            dismissButton = {
                TextButton(onClick = { fallbackUrl = null }) { Text("Cancel") }
            },
        )
    }

    if (showBookmarks) {
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text("Saved Sites") },
            text = {
                if (bookmarks.isEmpty()) {
                    Text("No bookmarks yet.")
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        bookmarks.forEach { bookmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = compactUrlForDisplay(bookmark.url),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    color = colors.textPrimary,
                                    fontFamily = DmSansFamily,
                                    fontSize = 12.sp,
                                )
                                Row {
                                    TextButton(
                                        onClick = {
                                            openInPanel(bookmark.url)
                                            showBookmarks = false
                                        },
                                    ) { Text("Open") }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                context.settingsDataStore.edit { prefs ->
                                                    val updated = decodeBookmarks(prefs[WEB_BOOKMARKS_KEY])
                                                        .filterNot {
                                                            scopeMatches(scopeKey, it.scopeKey) &&
                                                                it.url == bookmark.url
                                                        }
                                                    prefs[WEB_BOOKMARKS_KEY] = encodeBookmarks(updated)
                                                }
                                            }
                                        },
                                    ) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBookmarks = false }) { Text("Done") }
            },
        )
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("Recent") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    ) {
                        TextButton(onClick = { showSearchHistoryTab = false }) {
                            Text(
                                text = "Pages (${recentPages.size})",
                                color = if (!showSearchHistoryTab) colors.textPrimary else colors.textMid,
                            )
                        }
                        TextButton(onClick = { showSearchHistoryTab = true }) {
                            Text(
                                text = "Searches (${recentSearches.size})",
                                color = if (showSearchHistoryTab) colors.textPrimary else colors.textMid,
                            )
                        }
                    }

                    if (!showSearchHistoryTab && recentPages.isEmpty()) {
                        Text(
                            text = "No recent pages in this scope.",
                        )
                    } else if (showSearchHistoryTab && recentSearches.isEmpty()) {
                        Text(text = "No recent searches in this scope.")
                    } else if (!showSearchHistoryTab) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            recentPages.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = compactUrlForDisplay(item),
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp),
                                        color = colors.textPrimary,
                                        fontFamily = DmSansFamily,
                                        fontSize = 12.sp,
                                    )
                                    Row {
                                        TextButton(
                                            onClick = {
                                                openInPanel(item)
                                                showHistory = false
                                            },
                                        ) { Text("Open") }
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    context.settingsDataStore.edit { prefs ->
                                                        val key = if (showSearchHistoryTab) {
                                                            WEB_RECENT_SEARCHES_KEY
                                                        } else {
                                                            WEB_RECENT_PAGES_KEY
                                                        }
                                                        val updated = decodeRecentEntries(prefs[key]).filterNot {
                                                            scopeMatches(scopeKey, it.scopeKey) &&
                                                                it.value.equals(item, ignoreCase = true)
                                                        }
                                                        prefs[key] = encodeRecentEntries(updated)
                                                    }
                                                }
                                            },
                                        ) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            recentSearches.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = item.query,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp),
                                        color = colors.textPrimary,
                                        fontFamily = DmSansFamily,
                                        fontSize = 12.sp,
                                    )
                                    Row {
                                        TextButton(
                                            onClick = {
                                                eidosViewModel.setWebSearchContext(item.query)
                                                openInPanel(item.url)
                                                showHistory = false
                                            },
                                        ) { Text("Open") }
                                        TextButton(
                                            onClick = {
                                                val deleteKey = normalizeWebSearchKey(item.query)
                                                eidosViewModel.deleteWebConversationForSearch(
                                                    webSubfolderId,
                                                    item.query,
                                                )
                                                scope.launch {
                                                    context.settingsDataStore.edit { prefs ->
                                                        val updated = decodeRecentSearchEntries(
                                                            prefs[WEB_RECENT_SEARCHES_KEY],
                                                        ).filterNot { entry ->
                                                            scopeMatches(scopeKey, entry.scopeKey) &&
                                                                normalizeWebSearchKey(entry.query) == deleteKey
                                                        }
                                                        prefs[WEB_RECENT_SEARCHES_KEY] = encodeRecentSearchEntries(updated)
                                                    }
                                                }
                                            },
                                        ) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistory = false }) { Text("Done") }
            },
        )
    }
}

private fun configureWebView(webView: WebView) {
    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.domStorageEnabled = true
    settings.setSupportMultipleWindows(true)
    settings.mediaPlaybackRequiresUserGesture = false
    settings.loadsImagesAutomatically = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.databaseEnabled = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        settings.offscreenPreRaster = true
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = true
    }
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
}

private sealed class UrlResult {
    data class Valid(
        val url: String,
        val searchQuery: String? = null,
    ) : UrlResult()
    data class Invalid(val message: String) : UrlResult()
}

private data class WebBookmark(
    val scopeKey: String,
    val url: String,
    val createdAtMillis: Long,
)

private data class LastUrlEntry(
    val scopeKey: String,
    val url: String,
    val updatedAtMillis: Long,
)

private data class RecentEntry(
    val scopeKey: String,
    val value: String,
    val createdAtMillis: Long,
)

private data class RecentSearchEntry(
    val scopeKey: String,
    val query: String,
    val url: String,
    val createdAtMillis: Long,
)

private val WEB_LAST_URL_KEY = stringPreferencesKey("web_panel_last_url_json")
private val WEB_BOOKMARKS_KEY = stringPreferencesKey("web_panel_bookmarks_json")
private val WEB_RECENT_PAGES_KEY = stringPreferencesKey("web_panel_recent_pages_json")
private val WEB_RECENT_SEARCHES_KEY = stringPreferencesKey("web_panel_recent_searches_json")
private const val SEARCH_ENGINE_URL = "https://duckduckgo.com/?q="
private const val MAX_WEB_BOOKMARKS = 100
private const val MAX_SCOPE_RECENTS = 20
private val LEGACY_EDITOR_SCOPE_REGEX = Regex("^editor_subfolder_(\\d+)$")

private fun decodeBookmarks(raw: String?): List<WebBookmark> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val scopeKey = item.optString("scopeKey").trim()
                val url = item.optString("url").trim()
                if (scopeKey.isBlank() || url.isBlank()) continue
                val createdAt = item.optLong("createdAtMillis", 0L)
                add(WebBookmark(scopeKey = scopeKey, url = url, createdAtMillis = createdAt))
            }
        }
    }.getOrDefault(emptyList())
}

private fun encodeBookmarks(items: List<WebBookmark>): String {
    val array = JSONArray()
    items.forEach { bookmark ->
        array.put(
            JSONObject().apply {
                put("scopeKey", bookmark.scopeKey)
                put("url", bookmark.url)
                put("createdAtMillis", bookmark.createdAtMillis)
            },
        )
    }
    return array.toString()
}

private fun upsertBookmark(existing: List<WebBookmark>, url: String): List<WebBookmark> {
    return upsertBookmark(existing, "global", url)
}

private fun upsertBookmark(existing: List<WebBookmark>, scopeKey: String, url: String): List<WebBookmark> {
    val now = System.currentTimeMillis()
    val deduped = existing.filterNot {
        scopeMatches(scopeKey, it.scopeKey) && it.url.equals(url, ignoreCase = true)
    }
    return listOf(WebBookmark(scopeKey = scopeKey, url = url, createdAtMillis = now))
        .plus(deduped)
        .take(MAX_WEB_BOOKMARKS)
}

private fun decodeLastUrlEntries(raw: String?): List<LastUrlEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val scopeKey = item.optString("scopeKey").trim()
                val url = item.optString("url").trim()
                if (scopeKey.isBlank() || url.isBlank()) continue
                val updatedAt = item.optLong("updatedAtMillis", 0L)
                add(LastUrlEntry(scopeKey = scopeKey, url = url, updatedAtMillis = updatedAt))
            }
        }
    }.getOrDefault(emptyList())
}

private fun encodeLastUrlEntries(items: List<LastUrlEntry>): String {
    val array = JSONArray()
    items.forEach { entry ->
        array.put(
            JSONObject().apply {
                put("scopeKey", entry.scopeKey)
                put("url", entry.url)
                put("updatedAtMillis", entry.updatedAtMillis)
            },
        )
    }
    return array.toString()
}

private fun upsertLastUrlEntry(
    existing: List<LastUrlEntry>,
    scopeKey: String,
    url: String,
): List<LastUrlEntry> {
    val now = System.currentTimeMillis()
    val deduped = existing.filterNot { scopeMatches(scopeKey, it.scopeKey) }
    return listOf(LastUrlEntry(scopeKey = scopeKey, url = url, updatedAtMillis = now))
        .plus(deduped)
        .take(200)
}

private fun decodeRecentEntries(raw: String?): List<RecentEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val scopeKey = item.optString("scopeKey").trim()
                val value = item.optString("value").trim()
                if (scopeKey.isBlank() || value.isBlank()) continue
                val createdAt = item.optLong("createdAtMillis", 0L)
                add(RecentEntry(scopeKey = scopeKey, value = value, createdAtMillis = createdAt))
            }
        }
    }.getOrDefault(emptyList())
}

private fun encodeRecentEntries(items: List<RecentEntry>): String {
    val array = JSONArray()
    items.forEach { entry ->
        array.put(
            JSONObject().apply {
                put("scopeKey", entry.scopeKey)
                put("value", entry.value)
                put("createdAtMillis", entry.createdAtMillis)
            },
        )
    }
    return array.toString()
}

private fun upsertRecentEntry(
    existing: List<RecentEntry>,
    scopeKey: String,
    value: String,
): List<RecentEntry> {
    val now = System.currentTimeMillis()
    val deduped = existing.filterNot {
        scopeMatches(scopeKey, it.scopeKey) && it.value.equals(value, ignoreCase = true)
    }
    val ordered = listOf(
        RecentEntry(
            scopeKey = scopeKey,
            value = value,
            createdAtMillis = now,
        ),
    ) + deduped
    return trimRecentsPerScope(ordered, MAX_SCOPE_RECENTS)
}

private fun decodeRecentSearchEntries(raw: String?): List<RecentSearchEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val scopeKey = item.optString("scopeKey").trim()
                val queryField = item.optString("query").trim()
                val urlField = item.optString("url").trim()
                val legacyValue = item.optString("value").trim()
                val query = when {
                    queryField.isNotBlank() -> queryField
                    legacyValue.isNotBlank() -> legacyValue
                    else -> ""
                }
                val rawUrl = when {
                    urlField.isNotBlank() -> urlField
                    legacyValue.startsWith("http://", true) || legacyValue.startsWith("https://", true) -> legacyValue
                    legacyValue.isNotBlank() -> "$SEARCH_ENGINE_URL${Uri.encode(legacyValue)}"
                    else -> ""
                }
                if (scopeKey.isBlank() || query.isBlank() || rawUrl.isBlank()) continue
                val validUrl = (normalizeAndValidateUrl(rawUrl) as? UrlResult.Valid)?.url ?: continue
                val createdAt = item.optLong("createdAtMillis", 0L)
                add(
                    RecentSearchEntry(
                        scopeKey = scopeKey,
                        query = query,
                        url = validUrl,
                        createdAtMillis = createdAt,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun encodeRecentSearchEntries(items: List<RecentSearchEntry>): String {
    val array = JSONArray()
    items.forEach { entry ->
        array.put(
            JSONObject().apply {
                put("scopeKey", entry.scopeKey)
                put("query", entry.query)
                put("url", entry.url)
                put("createdAtMillis", entry.createdAtMillis)
            },
        )
    }
    return array.toString()
}

private fun upsertRecentSearchEntry(
    existing: List<RecentSearchEntry>,
    scopeKey: String,
    query: String,
    url: String,
): List<RecentSearchEntry> {
    val now = System.currentTimeMillis()
    val newKey = normalizeWebSearchKey(query)
    val deduped = existing.filterNot { entry ->
        scopeMatches(scopeKey, entry.scopeKey) &&
            normalizeWebSearchKey(entry.query) == newKey
    }
    val ordered = listOf(
        RecentSearchEntry(
            scopeKey = scopeKey,
            query = query,
            url = url,
            createdAtMillis = now,
        ),
    ) + deduped
    return trimSearchRecentsPerScope(ordered, MAX_SCOPE_RECENTS)
}

private fun trimRecentsPerScope(entries: List<RecentEntry>, maxPerScope: Int): List<RecentEntry> {
    val counts = mutableMapOf<String, Int>()
    return buildList {
        entries.forEach { entry ->
            val count = counts[entry.scopeKey] ?: 0
            if (count < maxPerScope) {
                add(entry)
                counts[entry.scopeKey] = count + 1
            }
        }
    }
}

private fun trimSearchRecentsPerScope(entries: List<RecentSearchEntry>, maxPerScope: Int): List<RecentSearchEntry> {
    val counts = mutableMapOf<String, Int>()
    return buildList {
        entries.forEach { entry ->
            val count = counts[entry.scopeKey] ?: 0
            if (count < maxPerScope) {
                add(entry)
                counts[entry.scopeKey] = count + 1
            }
        }
    }
}

private fun scopeMatches(activeScopeKey: String, candidateScopeKey: String): Boolean {
    if (activeScopeKey == candidateScopeKey) return true
    val activeSubfolder = subfolderIdFromScope(activeScopeKey) ?: return false
    val candidateSubfolder = subfolderIdFromScope(candidateScopeKey) ?: return false
    return activeSubfolder == candidateSubfolder
}

private fun subfolderIdFromScope(scopeKey: String): Long? {
    LEGACY_EDITOR_SCOPE_REGEX.matchEntire(scopeKey)?.let { match ->
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }
    if (!scopeKey.startsWith("editor:")) return null
    val parts = scopeKey.split(':')
    if (parts.size != 3) return null
    return parts[2].toLongOrNull()
}

private fun isSearchResultUrl(url: String): Boolean {
    if (url.startsWith(SEARCH_ENGINE_URL, ignoreCase = true)) return true
    val uri = runCatching { url.toUri() }.getOrNull() ?: return false
    val host = uri.host?.lowercase().orEmpty()
    return host.contains("duckduckgo.com") && !uri.getQueryParameter("q").isNullOrBlank()
}

private fun compactUrlForDisplay(url: String): String {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return url
    val host = uri.host.orEmpty().ifBlank { return url }
    val hasExtra =
        !uri.path.isNullOrBlank() && uri.path != "/" ||
            !uri.query.isNullOrBlank() ||
            !uri.fragment.isNullOrBlank()
    return if (hasExtra) "$host/..." else host
}

private fun normalizeAndValidateUrl(raw: String): UrlResult {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return UrlResult.Invalid("Enter a URL to open.")

    val hasWhitespace = trimmed.any(Char::isWhitespace)
    val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed)
    if (hasScheme) {
        val uri = runCatching { trimmed.toUri() }.getOrNull()
            ?: return UrlResult.Invalid("Invalid URL format.")
        val scheme = uri.scheme?.lowercase().orEmpty()
        return when (scheme) {
            "http", "https" -> UrlResult.Valid(trimmed)
            "javascript", "file", "content", "intent" ->
                UrlResult.Invalid("Blocked URL scheme: $scheme")
            else -> UrlResult.Invalid("Unsupported URL scheme: $scheme")
        }
    }

    val looksLikeHost = !hasWhitespace && (
        '.' in trimmed ||
            trimmed.startsWith("localhost", ignoreCase = true) ||
            Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""").matches(trimmed)
        )

    return if (looksLikeHost) {
        val candidate = "https://$trimmed"
        val uri = runCatching { candidate.toUri() }.getOrNull()
            ?: return UrlResult.Invalid("Invalid URL format.")
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "http" || scheme == "https") UrlResult.Valid(candidate)
        else UrlResult.Invalid("Unsupported URL scheme: $scheme")
    } else {
        val query = Uri.encode(trimmed)
        UrlResult.Valid(
            url = "$SEARCH_ENGINE_URL$query",
            searchQuery = trimmed,
        )
    }
}

private fun openExternal(context: Context, rawUrl: String) {
    if (tryLaunchIntentUri(context, rawUrl)) return
    val result = normalizeAndValidateUrl(rawUrl)
    if (result !is UrlResult.Valid) return
    val uri = result.url.toUri()
    Log.d(WEB_PANEL_TAG, "openExternal requested uri=$uri")
    if (tryOpenYoutubeApp(context, uri)) return
    val intent = Intent(Intent.ACTION_VIEW, uri)
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open with"))
        Log.d(WEB_PANEL_TAG, "openExternal chooser launched for uri=$uri")
    }.onFailure {
        Log.w(WEB_PANEL_TAG, "openExternal chooser failed for uri=$uri", it)
        if (it !is ActivityNotFoundException) throw it
    }
}

private fun openExternalIfSupported(context: Context, rawUrl: String): Boolean {
    if (tryLaunchIntentUri(context, rawUrl)) return true
    val uri = runCatching { rawUrl.toUri() }.getOrNull() ?: return false
    if (tryOpenYoutubeApp(context, uri)) return true
    val scheme = uri.scheme?.lowercase().orEmpty()
    if (scheme.isBlank() || scheme == "http" || scheme == "https") return false
    val intent = Intent(Intent.ACTION_VIEW, uri)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrElse {
        if (it is ActivityNotFoundException) false else throw it
    }
}

private fun tryLaunchIntentUri(context: Context, rawUrl: String): Boolean {
    if (!rawUrl.trim().startsWith("intent://", ignoreCase = true)) return false
    val parsed = runCatching { Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME) }.getOrNull()
        ?: run {
            Log.w(WEB_PANEL_TAG, "Failed to parse intent URI: $rawUrl")
            return false
        }

    // Browsable handoff; include NEW_TASK when launching outside an Activity.
    parsed.addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is android.app.Activity) {
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // If the primary intent cannot resolve, honor browser_fallback_url when provided.
    val pm = context.packageManager
    if (parsed.resolveActivity(pm) == null) {
        val fallbackUrl = parsed.getStringExtra("browser_fallback_url")
        if (!fallbackUrl.isNullOrBlank()) {
            Log.w(WEB_PANEL_TAG, "Intent URI unresolved; using browser_fallback_url=$fallbackUrl")
            openExternal(context, fallbackUrl)
            return true
        }
        Log.w(
            WEB_PANEL_TAG,
            "Intent URI unresolved with no fallback action=${parsed.action} data=${parsed.data} pkg=${parsed.`package`}",
        )
        return false
    }

    return runCatching {
        context.startActivity(parsed)
        Log.d(
            WEB_PANEL_TAG,
            "Intent URI launched action=${parsed.action} data=${parsed.data} pkg=${parsed.`package`}",
        )
        true
    }.getOrElse {
        Log.w(WEB_PANEL_TAG, "Intent URI launch failed: $rawUrl", it)
        false
    }
}

private fun tryOpenYoutubeApp(context: Context, uri: Uri): Boolean {
    if (!isYoutubeUrl(uri)) return false
    Log.d(WEB_PANEL_TAG, "tryOpenYoutubeApp uri=$uri")
    val videoId = extractYoutubeVideoId(uri)
    val deepLinkIntent = videoId?.let {
        Intent(Intent.ACTION_VIEW, "vnd.youtube:$it".toUri())
    }
    if (deepLinkIntent != null && tryStartActivity(context, deepLinkIntent, forceYoutubePackage = true)) {
        Log.d(WEB_PANEL_TAG, "YouTube deep link launched via vnd.youtube for videoId=$videoId")
        return true
    }
    val appIntent = Intent(Intent.ACTION_VIEW, uri)
    val launched = tryStartActivity(context, appIntent, forceYoutubePackage = true)
    if (launched) {
        Log.d(WEB_PANEL_TAG, "YouTube app launched via https intent uri=$uri")
    } else {
        Log.w(WEB_PANEL_TAG, "YouTube app launch failed for uri=$uri")
    }
    return launched
}

private fun isYoutubeUrl(uri: Uri): Boolean {
    val host = uri.host?.lowercase().orEmpty()
    return host == "youtube.com" ||
        host.endsWith(".youtube.com") ||
        host == "youtu.be" ||
        host.endsWith(".youtu.be") ||
        host == "youtube-nocookie.com" ||
        host.endsWith(".youtube-nocookie.com")
}

private fun extractYoutubeVideoId(uri: Uri): String? {
    val host = uri.host?.lowercase().orEmpty()
    return when {
        host == "youtu.be" || host.endsWith(".youtu.be") -> {
            uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        }
        host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> {
            uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }
                ?: uri.pathSegments
                    .dropWhile { it != "shorts" && it != "embed" && it != "live" }
                    .drop(1)
                    .firstOrNull()
                    ?.takeIf { it.isNotBlank() }
        }
        else -> null
    }
}

private fun tryStartActivity(
    context: Context,
    baseIntent: Intent,
    forceYoutubePackage: Boolean = false,
): Boolean {
    val intent = Intent(baseIntent).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (forceYoutubePackage) `package` = "com.google.android.youtube"
        if (context !is android.app.Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    val pm = context.packageManager
    val hasHandler = intent.resolveActivity(pm) != null
    if (!hasHandler) {
        Log.w(
            WEB_PANEL_TAG,
            "No activity resolves intent action=${intent.action} data=${intent.data} pkg=${intent.`package`}",
        )
        return false
    }
    return runCatching {
        context.startActivity(intent)
        Log.d(
            WEB_PANEL_TAG,
            "startActivity success action=${intent.action} data=${intent.data} pkg=${intent.`package`}",
        )
        true
    }.getOrElse {
        Log.w(
            WEB_PANEL_TAG,
            "startActivity failure action=${intent.action} data=${intent.data} pkg=${intent.`package`}",
            it,
        )
        if (it is ActivityNotFoundException) false else throw it
    }
}

private fun shouldPromptFallbackForMainFrameError(errorCode: Int): Boolean {
    return errorCode != WebViewClient.ERROR_UNKNOWN &&
        errorCode != WebViewClient.ERROR_UNSUPPORTED_SCHEME &&
        errorCode != WebViewClient.ERROR_REDIRECT_LOOP &&
        errorCode != WebViewClient.ERROR_TOO_MANY_REQUESTS
}
