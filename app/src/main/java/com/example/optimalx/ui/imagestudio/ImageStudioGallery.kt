package com.example.optimalx.ui.imagestudio

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.data.imagestudio.ImageGalleryFilter
import com.example.optimalx.data.imagestudio.ImageGalleryItem
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import java.io.File

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ImageStudioGallery(
    items: List<ImageGalleryItem>,
    filter: ImageGalleryFilter,
    selectedId: Long?,
    title: String = "Images in this folder",
    onFilterChange: (ImageGalleryFilter) -> Unit,
    onItemClick: (ImageGalleryItem) -> Unit,
    onReloadSettings: (ImageGalleryItem) -> Unit,
    onDeleteRequest: (ImageGalleryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            ImageGalleryFilter.entries.forEach { entry ->
                FilterChip(
                    selected = filter == entry,
                    onClick = { onFilterChange(entry) },
                    label = {
                        Text(
                            text = when (entry) {
                                ImageGalleryFilter.ALL -> "All"
                                ImageGalleryFilter.GENERATED -> "Generated"
                                ImageGalleryFilter.IMPORTED -> "Imported"
                            },
                            fontFamily = DmSansFamily,
                            fontSize = 12.sp,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                        selectedLabelColor = colors.accent,
                    ),
                )
            }
        }

        if (items.isEmpty()) {
            Text(
                text = "No images in this folder yet.",
                color = colors.textDim,
                fontFamily = DmSansFamily,
                fontSize = 13.sp,
            )
        } else {
            LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(280.dp),
        ) {
            items(items, key = { it.ref.id }) { item ->
                val isSelected = item.ref.id == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) colors.accent.copy(alpha = 0.08f) else colors.surface)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) colors.accent else colors.border,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .combinedClickable(
                            onClick = { onItemClick(item) },
                            onLongClick = { onDeleteRequest(item) },
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GalleryThumbnail(item = item)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.ref.fileName,
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.folderBadge?.let { badge ->
                            Text(
                                text = badge,
                                color = colors.accent,
                                fontFamily = DmMonoFamily,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (item.caption.isNotBlank()) {
                            Text(
                                text = item.caption,
                                color = colors.textDim,
                                fontFamily = DmMonoFamily,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!item.bytesOnDevice) {
                            Text(
                                text = "Not on device — sync from desktop",
                                color = colors.textDim,
                                fontFamily = DmMonoFamily,
                                fontSize = 10.sp,
                            )
                        }
                    }
                    if (item.isGenerated) {
                        Text(
                            text = "Reload",
                            color = colors.accent,
                            fontFamily = DmSansFamily,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { onReloadSettings(item) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    IconButton(
                        onClick = { onDeleteRequest(item) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete image",
                            tint = colors.textDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun GalleryThumbnail(item: ImageGalleryItem) {
    val colors = LocalOptimalXColors.current
    val bitmap = if (item.bytesOnDevice) {
        androidx.compose.runtime.remember(item.ref.filePath) {
            runCatching {
                BitmapFactory.decodeFile(item.ref.filePath)?.asImageBitmap()
            }.getOrNull()
        }
    } else {
        null
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = item.ref.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp),
            )
        } else {
            Text("?", color = colors.textDim, fontFamily = DmSansFamily, fontSize = 18.sp)
        }
    }
}
