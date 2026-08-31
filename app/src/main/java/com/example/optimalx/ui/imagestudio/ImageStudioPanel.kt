package com.example.optimalx.ui.imagestudio

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.imagestudio.ImageAspectRatio
import com.example.optimalx.data.imagestudio.ImageGalleryItem
import com.example.optimalx.data.imagestudio.ImageStudioMetadata
import com.example.optimalx.data.imagestudio.ImageTier
import com.example.optimalx.ui.components.ConfirmDeleteFileDialog
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import java.io.File

@Composable
fun ImageStudioPanel(
    saveSubfolderId: Long,
    onOpenFiles: () -> Unit,
    mode: ImageStudioPanelMode = ImageStudioPanelMode.SUBFOLDER,
    onSettingsClick: (() -> Unit)? = null,
    eidosViewModel: EidosChatViewModel? = null,
    modifier: Modifier = Modifier,
    viewModel: ImageStudioViewModel = viewModel(
        key = when (mode) {
            ImageStudioPanelMode.SUBFOLDER -> "image_studio_$saveSubfolderId"
            ImageStudioPanelMode.HUB -> "image_studio_hub"
        },
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                val galleryScope = when (mode) {
                    ImageStudioPanelMode.SUBFOLDER ->
                        ImageStudioGalleryScope.Subfolder(saveSubfolderId)
                    ImageStudioPanelMode.HUB -> ImageStudioGalleryScope.Hub
                }
                ImageStudioViewModel(app, saveSubfolderId, galleryScope)
            }
        },
    ),
) {
    val form by viewModel.form.collectAsState()
    val jobState by viewModel.jobState.collectAsState()
    val galleryItems by viewModel.filteredGalleryItems.collectAsState()
    val galleryFilter by viewModel.galleryFilter.collectAsState()
    val selectedGalleryId by viewModel.selectedGalleryId.collectAsState()
    val isConfigured by viewModel.isConfigured.collectAsState()
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshConfiguration()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshConfiguration()
    }

    val hubMode = mode == ImageStudioPanelMode.HUB
    LaunchedEffect(eidosViewModel, saveSubfolderId, hubMode, viewModel) {
        val eidos = eidosViewModel ?: return@LaunchedEffect
        eidos.pendingImageStudioDraftHandoff.collect { handoff ->
            if (handoff == null) return@collect
            if (handoff.saveSubfolderId != saveSubfolderId || handoff.hub != hubMode) return@collect
            eidos.consumeImageStudioDraftHandoff()
            viewModel.applyDraft(handoff.draft)
        }
    }

    LaunchedEffect(eidosViewModel, selectedGalleryId, galleryItems) {
        val eidos = eidosViewModel ?: return@LaunchedEffect
        val selected = galleryItems.firstOrNull { it.ref.id == selectedGalleryId }
        eidos.setImageStudioActivePreview(
            fileReferenceId = selected?.ref?.id,
            fileName = selected?.ref?.fileName,
        )
    }

    val isRunning = jobState is ImageStudioJobUiState.Running
    val contextLine = when (mode) {
        ImageStudioPanelMode.HUB ->
            "New images save to Image Studio › General. Browse every image below."
        ImageStudioPanelMode.SUBFOLDER ->
            "Images save to this folder. Gallery shows this subfolder only."
    }
    val galleryTitle = when (mode) {
        ImageStudioPanelMode.HUB -> "All Images"
        ImageStudioPanelMode.SUBFOLDER -> "Images in this folder"
    }
    var pendingDelete by remember { mutableStateOf<ImageGalleryItem?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
        Text(
            text = contextLine,
            color = colors.textDim,
            fontFamily = DmSansFamily,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = form.fileName,
                onValueChange = viewModel::updateFileName,
                label = { Text("File name", fontFamily = DmSansFamily) },
                placeholder = { Text("cover-art-dragon", fontFamily = DmMonoFamily, fontSize = 12.sp) },
                isError = form.nameError != null,
                supportingText = form.nameError?.let { err ->
                    { Text(err, color = colors.badgePdfText, fontFamily = DmSansFamily, fontSize = 12.sp) }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = textFieldColors(),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = viewModel::startOver) {
                Text("Start over", color = colors.textDim, fontFamily = DmSansFamily, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = form.prompt,
            onValueChange = viewModel::updatePrompt,
            label = { Text("Prompt", fontFamily = DmSansFamily) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
        )
        Text(
            text = "${form.prompt.length} characters",
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        if (form.showNegativePrompt) {
            OutlinedTextField(
                value = form.negativePrompt,
                onValueChange = viewModel::updateNegativePrompt,
                label = { Text("Negative prompt", fontFamily = DmSansFamily) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
        } else {
            TextButton(onClick = { viewModel.setShowNegativePrompt(true) }) {
                Text("Add negative prompt", color = colors.textDim, fontFamily = DmSansFamily, fontSize = 12.sp)
            }
        }

        Text(
            text = "Model tier",
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ImageTier.entries.forEach { tier ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp),
                ) {
                    RadioButton(
                        selected = form.tier == tier,
                        onClick = { viewModel.updateTier(tier) },
                        colors = RadioButtonDefaults.colors(selectedColor = colors.accent),
                    )
                    Text(
                        text = tier.name.lowercase().replaceFirstChar { it.titlecase() },
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        AspectRatioPicker(
            selected = form.aspectRatio,
            onSelect = viewModel::updateAspectRatio,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Est. cost: ${viewModel.costEstimateLabel}",
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 12.sp,
        )

        if (!isConfigured) {
            Text(
                text = "Add your xAI API key in Settings → Image Studio to generate.",
                color = colors.badgePdfText,
                fontFamily = DmSansFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            onSettingsClick?.let { openSettings ->
                TextButton(onClick = openSettings) {
                    Text("Open Settings", color = colors.accent, fontFamily = DmSansFamily)
                }
            }
        }

        form.formError?.let { error ->
            Text(
                text = error,
                color = colors.badgePdfText,
                fontFamily = DmSansFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::generate,
                enabled = !isRunning && isConfigured,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            ) {
                Text("Generate", fontFamily = DmSansFamily, color = colors.background)
            }
            if (isRunning) {
                OutlinedButton(onClick = viewModel::cancelGenerate) {
                    Text("Cancel", fontFamily = DmSansFamily)
                }
            }
        }

        if (isRunning) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "Generating…",
                color = colors.textDim,
                fontFamily = DmSansFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        when (val state = jobState) {
            is ImageStudioJobUiState.Success -> {
                val metadata = state.result.fileReference.metadataJson?.let(ImageStudioMetadata::parse)
                Spacer(Modifier.height(16.dp))
                PreviewSection(
                    filePath = state.result.fileReference.filePath,
                    estimatedCost = metadata?.estimatedCostUsd,
                    actualCost = metadata?.actualCostUsd,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::prepareRegenerate) {
                        Text("Regenerate", fontFamily = DmSansFamily)
                    }
                    OutlinedButton(
                        onClick = {
                            shareImage(context, state.result.fileReference.filePath)
                        },
                    ) {
                        Text("Share", fontFamily = DmSansFamily)
                    }
                    OutlinedButton(onClick = onOpenFiles) {
                        Text("Open Files", fontFamily = DmSansFamily)
                    }
                }
            }
            else -> Unit
        }

        Spacer(Modifier.height(20.dp))
        ImageStudioGallery(
            items = galleryItems,
            filter = galleryFilter,
            selectedId = selectedGalleryId,
            title = galleryTitle,
            onFilterChange = viewModel::setGalleryFilter,
            onItemClick = viewModel::selectGalleryItem,
            onReloadSettings = viewModel::reloadSettingsFromGallery,
            onDeleteRequest = { pendingDelete = it },
        )
        }

        pendingDelete?.let { item ->
            ConfirmDeleteFileDialog(
                fileName = item.ref.fileName,
                onDismiss = { pendingDelete = null },
                onConfirm = {
                    viewModel.deleteGalleryImage(item)
                    pendingDelete = null
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AspectRatioPicker(
    selected: ImageAspectRatio,
    onSelect: (ImageAspectRatio) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Aspect ratio",
        color = colors.textPrimary,
        fontFamily = DmSansFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageAspectRatio.entries.forEach { ratio ->
            FilterChip(
                selected = selected == ratio,
                onClick = { onSelect(ratio) },
                label = {
                    Text(ratio.wireValue, fontFamily = DmMonoFamily, fontSize = 12.sp)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                    selectedLabelColor = colors.accent,
                ),
            )
        }
    }
}

@Composable
private fun PreviewSection(
    filePath: String,
    estimatedCost: Double?,
    actualCost: Double?,
) {
    val colors = LocalOptimalXColors.current
    val bitmap = androidx.compose.runtime.remember(filePath) {
        runCatching {
            BitmapFactory.decodeFile(filePath)?.asImageBitmap()
        }.getOrNull()
    }
    Text(
        text = "Preview",
        color = colors.textPrimary,
        fontFamily = DmSansFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
    when {
        actualCost != null -> {
            Text(
                text = "Cost: $${"%.2f".format(actualCost)}",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
        }
        estimatedCost != null -> {
            Text(
                text = "Est. cost: ~$${"%.2f".format(estimatedCost)}",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Generated preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
        } else {
            CircularProgressIndicator(color = colors.accent)
        }
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalOptimalXColors.current.accent,
    cursorColor = LocalOptimalXColors.current.accent,
    focusedLabelColor = LocalOptimalXColors.current.accent,
)

private fun shareImage(context: android.content.Context, filePath: String) {
    val file = File(filePath)
    if (!file.isFile) return
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share image"))
}
