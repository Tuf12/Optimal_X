package com.example.optimalx.ui.reasoning

import android.app.Application
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningInboxScreen(
    subfolderId: Long,
    onBack: () -> Unit,
    onEidosClick: () -> Unit,
    onEidosSectionClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
) {
    val viewModel: ReasoningInboxViewModel = viewModel(
        key = "reasoning_inbox_$subfolderId",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                ReasoningInboxViewModel(app as Application, subfolderId)
            }
        },
    )

    val colors = LocalOptimalXColors.current
    val reasoningSubfolder by viewModel.reasoningSubfolder.collectAsState()
    val entries by viewModel.entries.collectAsState()

    BackHandler { onBack() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textMid,
                    actionIconContentColor = colors.textMid,
                ),
                title = {
                    Column {
                        Text(
                            text = "Reasoning",
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = reasoningSubfolder?.name.orEmpty(),
                            fontFamily = DmMonoFamily,
                            color = colors.textDim,
                            fontSize = 13.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textMid,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onEidosClick) {
                        Text("Eidos", color = colors.accent, fontFamily = DmSansFamily)
                    }
                    if (onEidosSectionClick != null) {
                        TextButton(onClick = onEidosSectionClick) {
                            Text("Section", color = colors.textMid, fontFamily = DmSansFamily)
                        }
                    }
                    if (onSettingsClick != null) {
                        TextButton(onClick = onSettingsClick) {
                            Text("⋯", color = colors.textMid, fontSize = 18.sp)
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No reasoning entries yet.\nEidos will append trace entries here.",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 15.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { "${it.timestamp}_${it.sourceLabel}_${it.content.hashCode()}" }) { entry ->
                    ReasoningEntryRow(entry = entry)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ReasoningEntryRow(entry: ReasoningEntry) {
    val colors = LocalOptimalXColors.current
    val clipboard = LocalClipboardManager.current
    val plainText = remember(entry) { entry.toPlainText() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(plainText)) },
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy entry",
                        tint = colors.textMid,
                    )
                }
            }
            // Native TextView: reliable text selection + copy toolbar on all API levels (Compose SelectionContainer is flaky in cards/lists).
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { context ->
                    TextView(context).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setTextIsSelectable(true)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        includeFontPadding = true
                    }
                },
                update = { tv ->
                    tv.text = plainText
                    tv.setTextColor(colors.textPrimary.toArgb())
                },
            )
        }
    }
}

private fun ReasoningEntry.toPlainText(): String = buildString {
    append("Source: $sourceLabel\n")
    val metadata = buildList {
        timestamp?.let { add(it) }
        piece?.let { add("piece=$it") }
        situation?.let { add("situation=$it") }
        step?.let { add("step=$it") }
    }.joinToString(" | ")
    if (metadata.isNotBlank()) append("$metadata\n")
    append(content)
}
