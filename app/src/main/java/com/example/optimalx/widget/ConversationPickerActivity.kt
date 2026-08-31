package com.example.optimalx.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.ui.eidos.ConversationDirectory
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.OptimalXTheme

class ConversationPickerActivity : ComponentActivity() {

    private val app get() = applicationContext as OptimalXApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OptimalXTheme {
                val background = Color(0xFF0E0E0F)
                val surface2 = Color(0xFF1E1E21)
                val border = Color(0xFF2C2C30)
                val textPrimary = Color(0xFFF0F0EE)
                val textDim = Color(0xFF7A7A76)
                val accent = Color(0xFFC8FB5E)

                var allConversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
                var selectedDirectory by remember { mutableStateOf(ConversationDirectory.RECENT) }
                var parentTargets by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
                var subfolderTargets by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
                var selectedLocationId by remember { mutableStateOf<Long?>(null) }
                var query by remember { mutableStateOf("") }

                suspend fun refreshTargets() {
                    val parentIds = app.database.conversationDao().getParentDirectoryIdsByRecency()
                    parentTargets = parentIds.mapNotNull { id ->
                        app.database.parentFolderDao().getById(id)?.let { id to it.name }
                    }
                    val subfolderIds = app.database.conversationDao().getSubfolderDirectoryIdsByRecency()
                    subfolderTargets = subfolderIds.mapNotNull { id ->
                        val sub = app.database.subfolderDao().getById(id) ?: return@mapNotNull null
                        val parent = app.database.parentFolderDao().getById(sub.parentFolderId)
                        id to if (parent != null) "${parent.name} / ${sub.name}" else sub.name
                    }
                }

                suspend fun refreshConversations() {
                    allConversations = when (selectedDirectory) {
                        ConversationDirectory.RECENT -> app.database.conversationDao().getRecentMainChat(5)
                        ConversationDirectory.HERE -> emptyList()
                        ConversationDirectory.GENERAL -> app.database.conversationDao().getRecentGeneral(200)
                        ConversationDirectory.PARENT -> {
                            val id = selectedLocationId
                            if (id == null) emptyList() else app.database.conversationDao().getRecentByParentFolder(id, 200)
                        }
                        ConversationDirectory.SUBFOLDER -> {
                            val id = selectedLocationId
                            if (id == null) emptyList() else app.database.conversationDao().getRecentBySubfolder(id, 200)
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    refreshTargets()
                    selectedLocationId = null
                    refreshConversations()
                }

                LaunchedEffect(selectedDirectory) {
                    selectedLocationId = when (selectedDirectory) {
                        ConversationDirectory.RECENT -> null
                        ConversationDirectory.HERE -> null
                        ConversationDirectory.GENERAL -> null
                        ConversationDirectory.PARENT -> parentTargets.firstOrNull()?.first
                        ConversationDirectory.SUBFOLDER -> subfolderTargets.firstOrNull()?.first
                    }
                    refreshConversations()
                }

                LaunchedEffect(selectedLocationId) {
                    if (selectedDirectory == ConversationDirectory.PARENT || selectedDirectory == ConversationDirectory.SUBFOLDER) {
                        refreshConversations()
                    }
                }

                val filtered = if (query.isBlank()) {
                    allConversations
                } else {
                    allConversations.filter { it.title.contains(query, ignoreCase = true) }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                        .clickable { finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(background)
                            .border(1.dp, border, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                            .clickable(onClick = {}),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Conversations",
                            color = textPrimary,
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 17.sp,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedDirectory == ConversationDirectory.RECENT,
                                onClick = { selectedDirectory = ConversationDirectory.RECENT },
                                label = { Text("Recent", fontFamily = DmSansFamily, fontSize = 12.sp) },
                            )
                            FilterChip(
                                selected = selectedDirectory == ConversationDirectory.GENERAL,
                                onClick = { selectedDirectory = ConversationDirectory.GENERAL },
                                label = { Text("General", fontFamily = DmSansFamily, fontSize = 12.sp) },
                            )
                            FilterChip(
                                selected = selectedDirectory == ConversationDirectory.PARENT,
                                onClick = { selectedDirectory = ConversationDirectory.PARENT },
                                label = { Text("Parent", fontFamily = DmSansFamily, fontSize = 12.sp) },
                            )
                            FilterChip(
                                selected = selectedDirectory == ConversationDirectory.SUBFOLDER,
                                onClick = { selectedDirectory = ConversationDirectory.SUBFOLDER },
                                label = { Text("Subfolder", fontFamily = DmSansFamily, fontSize = 12.sp) },
                            )
                        }

                        if (selectedDirectory == ConversationDirectory.PARENT || selectedDirectory == ConversationDirectory.SUBFOLDER) {
                            val targets = if (selectedDirectory == ConversationDirectory.PARENT) parentTargets else subfolderTargets
                            if (targets.isEmpty()) {
                                Text(
                                    text = "No directories yet for this scope.",
                                    color = textDim,
                                    fontFamily = DmMonoFamily,
                                    fontSize = 12.sp,
                                )
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(targets, key = { it.first }) { target ->
                                        FilterChip(
                                            selected = selectedLocationId == target.first,
                                            onClick = { selectedLocationId = target.first },
                                            label = { Text(target.second, fontFamily = DmSansFamily, fontSize = 12.sp, maxLines = 1) },
                                        )
                                    }
                                }
                            }
                        }

                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = {
                                Text("Search…", color = textDim, fontFamily = DmSansFamily, fontSize = 13.sp)
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = surface2,
                                unfocusedContainerColor = surface2,
                                focusedIndicatorColor = accent,
                                unfocusedIndicatorColor = border,
                                cursorColor = accent,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary,
                            ),
                        )

                        if (filtered.isEmpty()) {
                            Text(
                                text = "No conversations yet.",
                                color = textDim,
                                fontFamily = DmMonoFamily,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(filtered) { conversation ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(surface2)
                                        .border(1.dp, border, RoundedCornerShape(10.dp))
                                        .clickable { continueConversation(conversation.id) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = conversation.title,
                                        color = textPrimary,
                                        fontFamily = DmSansFamily,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun continueConversation(conversationId: Long) {
        WidgetPrefs.setActiveConversationId(this, conversationId)
        startActivity(
            Intent(this, WidgetChatActivity::class.java).apply {
                putExtra(WidgetVoiceService.EXTRA_CONVERSATION_ID, conversationId)
            }
        )
        finish()
    }
}
