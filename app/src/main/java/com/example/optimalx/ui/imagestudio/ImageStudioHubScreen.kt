package com.example.optimalx.ui.imagestudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.repository.FolderRepository
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.folders.ParentFolderViewModel
import com.example.optimalx.ui.folders.PinnedRow
import com.example.optimalx.ui.folders.PinnedRowItem
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ImageStudioHubScreen(
    folderRepository: FolderRepository,
    eidosViewModel: EidosChatViewModel,
    onBack: () -> Unit,
    onEidosClick: () -> Unit,
    onSettingsClick: (() -> Unit)? = null,
    onOpenGeneralFiles: (Long) -> Unit,
    onPinnedPanelsClick: () -> Unit,
    onPinnedDumpEditClick: () -> Unit,
    onPinnedWorkshopClick: () -> Unit,
    onPinnedQuickNotesClick: () -> Unit,
    onPinnedImageStudioClick: () -> Unit,
    onPinnedUserPinClick: (PinnedRowItem.UserPin) -> Unit,
) {
    val parentFolderViewModel: ParentFolderViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                ParentFolderViewModel(app)
            }
        },
    )
    val userPins by parentFolderViewModel.userPins.collectAsState()

    val colors = LocalOptimalXColors.current
    var generalSubfolderId by remember { mutableStateOf<Long?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(folderRepository) {
        val id = withContext(Dispatchers.IO) {
            folderRepository.getImageStudioGeneralSubfolderId()
        }
        if (id == null) {
            loadFailed = true
        } else {
            generalSubfolderId = id
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
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
                    text = "Image Studio",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEidosClick) {
                    Text("Eidos", color = colors.accent, fontFamily = DmSansFamily)
                }
            }
            PinnedRow(
                userPins = userPins,
                onPanelsClick = onPinnedPanelsClick,
                onDumpEditClick = onPinnedDumpEditClick,
                onWorkshopClick = onPinnedWorkshopClick,
                onQuickNotesClick = onPinnedQuickNotesClick,
                onImageStudioClick = onPinnedImageStudioClick,
                onUserPinClick = onPinnedUserPinClick,
                onUserPinLongClick = { pin -> parentFolderViewModel.unpinById(pin.pinId) },
            )
        }

        when {
            loadFailed -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Image Studio is not set up yet. Restart the app or sync from desktop.",
                        color = colors.textDim,
                        fontFamily = DmSansFamily,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            generalSubfolderId == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
            else -> {
                ImageStudioPanel(
                    saveSubfolderId = generalSubfolderId!!,
                    mode = ImageStudioPanelMode.HUB,
                    onOpenFiles = { onOpenGeneralFiles(generalSubfolderId!!) },
                    onSettingsClick = onSettingsClick,
                    eidosViewModel = eidosViewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
