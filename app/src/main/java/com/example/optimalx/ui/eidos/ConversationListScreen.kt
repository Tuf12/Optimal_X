package com.example.optimalx.ui.eidos

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val listTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

data class ConversationListItem(
    val id: Long,
    val title: String,
    val snippet: String,
    val updatedAt: String,
    val sourceLabel: String? = null,
)

class ConversationListViewModel(
    app: Application,
    private val scopeType: String,
    private val scopeId: Long,
) : ViewModel() {

    private val appRef = app as OptimalXApplication
    private val db = appRef.database

    private val _items = MutableStateFlow<List<ConversationListItem>>(emptyList())
    val items: StateFlow<List<ConversationListItem>> = _items.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val conversations: List<Conversation> = when (scopeType) {
                "parent" -> db.conversationDao().getRecentByParentIncludingSubfolders(scopeId, 200)
                "subfolder" -> db.conversationDao().getRecentBySubfolder(scopeId, 200)
                else -> db.conversationDao().getRecentGeneral(200)
            }
            _items.value = conversations.map { conv ->
                val snippet = db.chatMessageDao().getFirstMessage(conv.id)?.content?.take(80) ?: ""
                val sourceLabel = when (scopeType) {
                    "parent" -> when (conv.scopeType) {
                        "subfolder" -> {
                            val sid = conv.subfolderId
                            if (sid == null) "Source: Subfolder"
                            else "Source: ${db.subfolderDao().getById(sid)?.name ?: "Subfolder #$sid"}"
                        }
                        "parent" -> "Source: Parent folder"
                        else -> "Source: ${conv.scopeType}"
                    }
                    "subfolder" -> "Source: This subfolder"
                    else -> null
                }
                ConversationListItem(
                    id = conv.id,
                    title = conv.title,
                    snippet = snippet,
                    updatedAt = listTimeFormatter.format(Instant.ofEpochMilli(conv.updatedAt)),
                    sourceLabel = sourceLabel,
                )
            }
        }
    }

    fun toggleSelect(id: Long) {
        _selectedIds.value = _selectedIds.value.toMutableSet().apply {
            if (contains(id)) remove(id) else add(id)
        }
    }

    fun deleteSelected() {
        val toDelete = _selectedIds.value
        if (toDelete.isEmpty()) return
        viewModelScope.launch {
            toDelete.forEach { id ->
                db.chatMessageDao().deleteAllByConversation(id)
                db.conversationDao().deleteById(id)
            }
            _selectedIds.value = emptySet()
            load()
        }
    }

    fun renameConversation(id: Long, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val conversation = db.conversationDao().getById(id) ?: return@launch
            db.conversationDao().update(
                conversation.copy(
                    title = trimmed,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            load()
        }
    }
}

class ConversationListViewModelFactory(
    private val app: Application,
    private val scopeType: String,
    private val scopeId: Long,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ConversationListViewModel(app, scopeType, scopeId) as T
    }
}

@Composable
fun ConversationListScreen(
    scopeType: String,
    scopeId: Long,
    title: String,
    onBack: () -> Unit,
    onOpenConversation: (Long) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val app = LocalContext.current.applicationContext as Application
    val vm: ConversationListViewModel = viewModel(
        key = "conv_list_${scopeType}_$scopeId",
        factory = ConversationListViewModelFactory(app, scopeType, scopeId),
    )
    val items by vm.items.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    var renamingConversationId by remember { mutableStateOf<Long?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = colors.textMid, fontFamily = DmSansFamily)
            }
            Text(
                text = title,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            if (selectedIds.isNotEmpty()) {
                TextButton(onClick = { vm.deleteSelected() }) {
                    Text(
                        "Delete (${selectedIds.size})",
                        color = colors.accent,
                        fontFamily = DmSansFamily,
                    )
                }
            }
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No conversations yet.",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 15.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    val isSelected = item.id in selectedIds
                    val shape = RoundedCornerShape(10.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(colors.surface2)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) colors.accent else colors.border,
                                shape = shape,
                            )
                            .clickable { onOpenConversation(item.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.title,
                                color = colors.textPrimary,
                                fontFamily = DmMonoFamily,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = item.updatedAt,
                                color = colors.textDim,
                                fontFamily = DmMonoFamily,
                                fontSize = 11.sp,
                            )
                        }
                        if (!item.sourceLabel.isNullOrBlank()) {
                            Text(
                                text = item.sourceLabel,
                                color = colors.accent,
                                fontFamily = DmMonoFamily,
                                fontSize = 11.sp,
                            )
                        }
                        if (item.snippet.isNotBlank()) {
                            Text(
                                text = item.snippet,
                                color = colors.textDim,
                                fontFamily = DmSansFamily,
                                fontSize = 12.sp,
                                maxLines = 2,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Select",
                                color = colors.textDim,
                                fontFamily = DmMonoFamily,
                                fontSize = 10.sp,
                                modifier = Modifier.clickable { vm.toggleSelect(item.id) },
                            )
                            TextButton(
                                onClick = {
                                    renamingConversationId = item.id
                                    renameDraft = item.title
                                },
                            ) {
                                Text(
                                    text = "Rename",
                                    color = colors.textMid,
                                    fontFamily = DmSansFamily,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renamingConversationId?.let { conversationId ->
        AlertDialog(
            onDismissRequest = { renamingConversationId = null },
            title = {
                Text("Rename chat", fontFamily = DmSansFamily, color = colors.textPrimary)
            },
            text = {
                TextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surface2,
                        unfocusedContainerColor = colors.surface2,
                        focusedIndicatorColor = colors.accent,
                        unfocusedIndicatorColor = colors.border,
                        cursorColor = colors.accent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameConversation(conversationId, renameDraft)
                    renamingConversationId = null
                }) {
                    Text("Save", color = colors.accent, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingConversationId = null }) {
                    Text("Cancel", color = colors.textMid, fontFamily = DmSansFamily)
                }
            },
            containerColor = colors.background,
        )
    }
}
