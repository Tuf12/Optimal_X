package com.example.optimalx.ui.memorycache

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
fun MemoryCacheInboxScreen(
    subfolderId: Long,
    onBack: () -> Unit,
    onEidosClick: () -> Unit,
    onEidosSectionClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
) {
    val viewModel: MemoryCacheInboxViewModel = viewModel(
        key = "memory_cache_$subfolderId",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                MemoryCacheInboxViewModel(app as Application, subfolderId)
            }
        },
    )

    val colors = LocalOptimalXColors.current
    val memoryCacheSubfolder by viewModel.memoryCacheSubfolder.collectAsState()
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
                            text = "Memory Cache",
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = memoryCacheSubfolder?.name.orEmpty(),
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
                    text = "No cache entries yet.\nEidos will add entries as needed.",
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
                items(entries, key = { it.subfolderId }) { entry ->
                    MemoryCacheEntryRow(entry = entry)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun MemoryCacheEntryRow(entry: MemoryCacheEntry) {
    val colors = LocalOptimalXColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "Source: ${entry.subfolderName}",
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "subfolderId=${entry.subfolderId}",
                fontFamily = DmMonoFamily,
                color = colors.textDim,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.content,
                fontFamily = DmSansFamily,
                color = colors.textPrimary,
                fontSize = 14.sp,
            )
        }
    }
}
