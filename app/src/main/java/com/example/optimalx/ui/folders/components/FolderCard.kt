package com.example.optimalx.ui.folders.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.folders.SystemFolderBranding
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

private val cardShape = RoundedCornerShape(14.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    name: String,
    isGrid: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    systemBranding: SystemFolderBranding? = null,
) {
    val colors = LocalOptimalXColors.current

    val listNeedsTallerRow =
        systemBranding != null && !isGrid && systemBranding.caption != name
    val sizeModifier = if (isGrid) {
        modifier.aspectRatio(1f)
    } else {
        modifier
            .fillMaxWidth()
            .then(
                if (listNeedsTallerRow) Modifier.heightIn(min = 72.dp)
                else Modifier.height(64.dp),
            )
    }

    Box(
        modifier = sizeModifier
            .clip(cardShape)
            .background(colors.surface)
            .border(1.dp, colors.border, cardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            systemBranding != null && isGrid -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = systemBranding.icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = systemBranding.caption,
                        color = colors.textDim,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            systemBranding != null && !isGrid -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = systemBranding.icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = colors.textPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (systemBranding.caption != name) {
                            Text(
                                text = systemBranding.caption,
                                color = colors.textDim,
                                fontFamily = DmSansFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            else -> {
                Text(
                    text = name,
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = if (isGrid) 15.sp else 16.sp,
                    textAlign = TextAlign.Center,
                    maxLines = if (isGrid) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
