package com.example.optimalx.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.data.backup.OptimalXBackupManager
import com.example.optimalx.data.eidos.provider.XAI_MODEL_CHOICES
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.SyneFamily
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenOptimalXLink: () -> Unit = {},
) {
    BackHandler { onBack() }

    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                SettingsViewModel(app)
            }
        }
    )

    val colors = LocalOptimalXColors.current
    val uriHandler = LocalUriHandler.current
    val themePreference by viewModel.themePreference.collectAsState()
    val xaiKey by viewModel.xaiKey.collectAsState()
    val xaiModel by viewModel.xaiModel.collectAsState()
    val openAiKey by viewModel.openAiKey.collectAsState()
    val anthropicKey by viewModel.anthropicKey.collectAsState()
    val kimiKey by viewModel.kimiKey.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val memoryRollover by viewModel.memoryRollover.collectAsState()
    val semanticIndex by viewModel.semanticIndex.collectAsState()
    val dataBackup by viewModel.dataBackup.collectAsState()
    val wakeWord by viewModel.wakeWord.collectAsState()
    val readAloud by viewModel.readAloud.collectAsState()
    val readAloudMicPassback by viewModel.readAloudMicPassback.collectAsState()
    val widgetVoiceHandsFree by viewModel.widgetVoiceHandsFree.collectAsState()
    val micUseWhisperApi by viewModel.micUseWhisperApi.collectAsState()
    var showApiKeys by rememberSaveable { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshApiKeysFromStorage()
    }

    val hasOpenAiKey = openAiKey.isNotBlank()

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(OptimalXBackupManager.BACKUP_MIME_TYPE),
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackup(uri)
            feedback = "Export started"
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
        }
    }

    if (pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = {
                Text(
                    text = "Import backup?",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                )
            },
            text = {
                Text(
                    text = "This replaces all notes, chats, folders, attached files, and workshop projects on this device. A raw .db import replaces the database only. API keys are not included.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportUri?.let { viewModel.importBackup(it) }
                        pendingImportUri = null
                        feedback = "Import started"
                    },
                ) {
                    Text("Replace data", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) {
                    Text("Cancel", color = colors.textMid, fontFamily = DmSansFamily)
                }
            },
            containerColor = colors.surface,
        )
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(1400)
            feedback = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textMid,
                )
            }
            Text(
                text = "Settings",
                color = colors.textPrimary,
                fontFamily = SyneFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (feedback != null) {
                Text(
                    text = feedback ?: "",
                    color = colors.accent,
                    fontFamily = DmMonoFamily,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            SettingsSection(title = "Theme") {
                SegmentedThemeControl(
                    selected = themePreference,
                    onSelect = {
                        viewModel.setTheme(it)
                        feedback = "Theme saved"
                    },
                )
            }

            SettingsSection(title = "API Keys") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = if (showApiKeys) "Hide keys" else "Show keys",
                        color = colors.textMid,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showApiKeys = !showApiKeys }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                SettingsTextField(
                    label = "xAI",
                    value = xaiKey,
                    onValueChange = {
                        viewModel.setXaiKey(it)
                        feedback = "xAI key saved"
                    },
                    isSecure = true,
                    reveal = showApiKeys,
                )
                KeyHelpLink(
                    text = "Get xAI API key",
                    onClick = { uriHandler.openUri("https://console.x.ai/") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Uses the same xAI key; pick which Grok model receives chat requests.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                XaiModelDropdown(
                    selectedModelId = xaiModel,
                    onSelect = {
                        viewModel.setXaiModel(it)
                        feedback = "Grok model saved"
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsTextField(
                    label = "OpenAI",
                    value = openAiKey,
                    onValueChange = {
                        viewModel.setOpenAiKey(it)
                        feedback = "OpenAI key saved"
                    },
                    isSecure = true,
                    reveal = showApiKeys,
                )
                KeyHelpLink(
                    text = "Get OpenAI API key",
                    onClick = { uriHandler.openUri("https://platform.openai.com/settings/organization/api-keys") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsTextField(
                    label = "Anthropic",
                    value = anthropicKey,
                    onValueChange = {
                        viewModel.setAnthropicKey(it)
                        feedback = "Anthropic key saved"
                    },
                    isSecure = true,
                    reveal = showApiKeys,
                )
                KeyHelpLink(
                    text = "Get Anthropic API key",
                    onClick = { uriHandler.openUri("https://console.anthropic.com/settings/keys") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsTextField(
                    label = "Kimi (Moonshot)",
                    value = kimiKey,
                    onValueChange = {
                        viewModel.setKimiKey(it)
                        feedback = "Kimi key saved"
                    },
                    isSecure = true,
                    reveal = showApiKeys,
                )
                KeyHelpLink(
                    text = "Get Kimi API key",
                    onClick = { uriHandler.openUri("https://platform.kimi.ai/console/api-keys") },
                )
                Text(
                    text = "Chat uses kimi-k2.6 with prompt caching (cache_control + prompt_cache_key).",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SettingsSection(title = "Provider") {
                ProviderOption(
                    label = "xAI (Grok)",
                    selected = activeProvider == "xai",
                    onClick = {
                        viewModel.setActiveProvider("xai")
                        feedback = "Provider saved: xAI"
                    },
                )
                ProviderOption(
                    label = "OpenAI",
                    selected = activeProvider == "openai",
                    onClick = {
                        viewModel.setActiveProvider("openai")
                        feedback = "Provider saved: OpenAI"
                    },
                )
                ProviderOption(
                    label = "Anthropic",
                    selected = activeProvider == "anthropic",
                    onClick = {
                        viewModel.setActiveProvider("anthropic")
                        feedback = "Provider saved: Anthropic"
                    },
                )
                ProviderOption(
                    label = "Kimi (Moonshot K2.6)",
                    selected = activeProvider == "kimi",
                    onClick = {
                        viewModel.setActiveProvider("kimi")
                        feedback = "Provider saved: Kimi"
                    },
                )
            }

            SettingsSection(title = "Eidos chat") {
                val conversationMemory by viewModel.conversationMemoryDepth.collectAsState()
                Text(
                    text = "In-conversation memory",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "How much prior chat Eidos resends each turn. Low saves tokens; raise to Medium/High if Eidos forgets thread details. Notes and folders are loaded via tools, not bulk-inlined.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                listOf(
                    "low" to "Low (~8 messages)",
                    "medium" to "Medium (~16 messages)",
                    "high" to "High (~40 messages)",
                ).forEach { (id, label) ->
                    ProviderOption(
                        label = label,
                        selected = conversationMemory == id,
                        onClick = {
                            viewModel.setConversationMemoryDepth(id)
                            feedback = "Chat memory: $label"
                        },
                    )
                }
            }

            SettingsSection(title = "Data backup") {
                Text(
                    text = "Export or import your OptimalX database for development backups. Saves to Google Drive, Downloads, or any folder you pick.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Text(
                    text = "Includes: Room database (notes, chats, folders, semantic chunks), attached files, and workshop projects. Excludes: API keys and theme preferences.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Text(
                    text = "Import accepts full .zip backups or a raw optimalx.db pulled from Android Studio Device Explorer (database only — no attached files).",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                KeyHelpLink(
                    text = if (dataBackup.isWorking) "Exporting..." else "Export backup (.zip)",
                    onClick = {
                        exportBackupLauncher.launch(OptimalXBackupManager.suggestedExportFileName())
                    },
                )
                KeyHelpLink(
                    text = if (dataBackup.isWorking) "Importing..." else "Import backup (.zip or .db)",
                    onClick = {
                        importBackupLauncher.launch(
                            arrayOf(
                                OptimalXBackupManager.BACKUP_MIME_TYPE,
                                "application/x-sqlite3",
                                "application/octet-stream",
                            ),
                        )
                    },
                )
                if (!dataBackup.message.isNullOrBlank()) {
                    Text(
                        text = dataBackup.message.orEmpty(),
                        color = colors.accent,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }
            }

            SettingsSection(title = "Developer") {
                val apiTraceEnabled by viewModel.eidosApiTraceEnabled.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "API Trace inspector",
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Records exact outbound LLM request JSON per provider round (system prompt, messages, tools). View in Eidos → API Trace. Off by default.",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                        )
                    }
                    Switch(
                        checked = apiTraceEnabled,
                        onCheckedChange = {
                            viewModel.setEidosApiTraceEnabled(it)
                            feedback = if (it) "API Trace enabled" else "API Trace disabled"
                        },
                    )
                }
                val workshopAutoContinue by viewModel.workshopAutoContinueEnabled.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Workshop Auto-Continue",
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "During build kickoffs, chain Eidos chunks using LLM handoff messages (synthetic user resend).",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                        )
                    }
                    Switch(
                        checked = workshopAutoContinue,
                        onCheckedChange = {
                            viewModel.setWorkshopAutoContinueEnabled(it)
                            feedback = if (it) "Auto-Continue enabled" else "Auto-Continue disabled"
                        },
                    )
                }
                val pauseBetweenChunks by viewModel.workshopPauseBetweenChunks.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Pause between workshop chunks",
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "When on, tool-cap pauses wait for you to reply continue instead of auto-resending the handoff.",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                        )
                    }
                    Switch(
                        checked = pauseBetweenChunks,
                        enabled = workshopAutoContinue,
                        onCheckedChange = {
                            viewModel.setWorkshopPauseBetweenChunks(it)
                            feedback = if (it) "Pause between chunks on" else "Pause between chunks off"
                        },
                    )
                }
            }

            SettingsSection(title = "OptimalX Link") {
                Text(
                    text = "Pair OptimalX with the desktop app on your PC to pull, push, or restore the full app state over your local network. The server runs only while this screen is open.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                KeyHelpLink(
                    text = "Open OptimalX Link",
                    onClick = onOpenOptimalXLink,
                )
            }

            SettingsSection(title = "Search & retrieval") {
                Text(
                    text = "Rebuild semantic index",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Re-chunks and re-embeds all notes, files, and conversations on-device. Runs automatically after saves; use this after imports, model changes, or if search results look stale.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                KeyHelpLink(
                    text = if (semanticIndex.isRunning) "Rebuilding index..." else "Rebuild semantic index now",
                    onClick = { viewModel.rebuildSemanticIndex() },
                )
                if (!semanticIndex.message.isNullOrBlank()) {
                    Text(
                        text = semanticIndex.message.orEmpty(),
                        color = colors.accent,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }
            }

            SettingsSection(title = "Memory") {
                Text(
                    text = "Force memory rollover",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Automatic: WorkManager queues rollover for the next local midnight (needs network; OS may defer slightly). Logcat: OptimalX.MemoryRollover. Force uses “now” for Daily Memory; the overnight run targets the calendar day that just ended.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                KeyHelpLink(
                    text = if (memoryRollover.isRunning) "Rollover running..." else "Run Force rollover now",
                    onClick = { viewModel.forceMemoryRollover() },
                )
                if (!memoryRollover.message.isNullOrBlank()) {
                    Text(
                        text = memoryRollover.message.orEmpty(),
                        color = colors.accent,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }
            }

            SettingsSection(title = "Voice") {
                SettingsTextField(
                    label = "Wake word",
                    value = wakeWord,
                    onValueChange = {
                        viewModel.setWakeWord(it)
                        feedback = "Wake word saved"
                    },
                )
                if (isCommonWord(wakeWord)) {
                    Text(
                        text = "⚠ This phrase is very common and may trigger accidentally.",
                        color = colors.accent,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface2)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "Read aloud",
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Text-to-speech replies",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = readAloud,
                        onCheckedChange = {
                            viewModel.setReadAloud(it)
                            feedback = if (it) "Read aloud enabled" else "Read aloud disabled"
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface2)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Re-open mic after read aloud",
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "When on, mic opens after TTS — tap Send to send. When off, TTS only.",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = readAloudMicPassback,
                        onCheckedChange = {
                            viewModel.setReadAloudMicPassback(it)
                            feedback = if (it) "Mic pass-back enabled" else "Mic pass-back disabled"
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface2)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Widget hands-free",
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Home widget Ask Eidos / Quick Notes: speak reply aloud after send.",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = widgetVoiceHandsFree,
                        onCheckedChange = {
                            viewModel.setWidgetVoiceHandsFree(it)
                            feedback = if (it) "Widget hands-free enabled" else "Widget hands-free disabled"
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface2)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Use OpenAI Whisper for mic",
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Chat and notes mics. Requires OpenAI key and network.",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = micUseWhisperApi,
                        onCheckedChange = { enabled ->
                            if (enabled && !hasOpenAiKey) {
                                feedback = "Save an OpenAI API key in API Keys above first"
                            } else {
                                viewModel.setMicUseWhisperApi(enabled)
                                feedback = if (enabled) {
                                    "Whisper mic enabled"
                                } else {
                                    "Google mic fallback enabled"
                                }
                            }
                        },
                    )
                }
                if (!hasOpenAiKey) {
                    Text(
                        text = if (micUseWhisperApi) {
                            "Whisper is on in settings but needs an OpenAI key — add one above or turn this off."
                        } else {
                            "Save an OpenAI API key above to use Whisper mic (otherwise Google STT)."
                        },
                        color = colors.textDim,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Speech-to-text",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Chat and notes use Whisper when enabled above, otherwise Google speech recognition. Widget Quick Ask, Quick Notes, and web search always use Google. Keyboard voice (Gboard) works without API calls.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun KeyHelpLink(
    text: String,
    onClick: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Text(
        text = text,
        color = colors.accent,
        fontFamily = DmMonoFamily,
        fontSize = 11.sp,
        modifier = Modifier
            .padding(top = 4.dp, start = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(
            text = title.uppercase(),
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        content()
    }
}

@Composable
private fun SegmentedThemeControl(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val items = listOf("light" to "Light", "dark" to "Dark", "system" to "System")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface2)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (key, label) ->
            val isSelected = selected == key
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accentDim else colors.surface2)
                    .border(
                        width = if (isSelected) 1.dp else 0.dp,
                        color = if (isSelected) colors.accentBorder else colors.surface2,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(key) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) colors.accent else colors.textMid,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ProviderOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Text(
            text = label,
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XaiModelDropdown(
    selectedModelId: String,
    onSelect: (String) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    var expanded by remember { mutableStateOf(false) }
    val selected = XAI_MODEL_CHOICES.firstOrNull { it.modelId == selectedModelId }
        ?: XAI_MODEL_CHOICES.first()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        TextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = {
                Text(
                    text = "Grok model (xAI)",
                    fontFamily = DmSansFamily,
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surface2,
                unfocusedContainerColor = colors.surface2,
                focusedIndicatorColor = colors.accent,
                unfocusedIndicatorColor = colors.border,
                focusedLabelColor = colors.accent,
                unfocusedLabelColor = colors.textDim,
                cursorColor = colors.accent,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surface2,
        ) {
            XAI_MODEL_CHOICES.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = choice.label,
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 14.sp,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(choice.modelId)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isSecure: Boolean = false,
    reveal: Boolean = false,
) {
    val colors = LocalOptimalXColors.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = if (isSecure && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isSecure) {
            {
                Icon(
                    imageVector = if (reveal) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (reveal) "Visible" else "Hidden",
                    tint = colors.textDim,
                )
            }
        } else null,
        label = {
            Text(
                text = label,
                fontFamily = DmSansFamily,
            )
        },
        textStyle = androidx.compose.ui.text.TextStyle(
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 14.sp,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surface2,
            unfocusedContainerColor = colors.surface2,
            focusedIndicatorColor = colors.accent,
            unfocusedIndicatorColor = colors.border,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.textDim,
            cursorColor = colors.accent,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private val COMMON_WORDS = setOf(
    "ok", "okay", "yes", "no", "hi", "hey", "hello", "stop", "go", "start",
    "done", "end", "next", "back", "now", "right", "left", "up", "down",
    "one", "two", "three", "play", "pause", "help", "please", "thanks",
)

private fun isCommonWord(phrase: String): Boolean {
    val normalized = phrase.trim().lowercase()
    if (normalized.isBlank()) return false
    // Warn if the entire phrase is a single common word, or is 3 chars or fewer
    return normalized in COMMON_WORDS || normalized.length <= 3
}
