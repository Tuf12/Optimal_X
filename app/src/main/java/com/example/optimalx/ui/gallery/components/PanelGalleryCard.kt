package com.example.optimalx.ui.gallery.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.folders.components.FolderCard
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun PanelGalleryCard(
    name: String,
    isGrid: Boolean,
    statusBadge: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val badgeShape = RoundedCornerShape(6.dp)

    Box(modifier = modifier) {
        FolderCard(
            name = name,
            isGrid = isGrid,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth(),
        )
        if (statusBadge != null) {
            Text(
                text = statusBadge,
                color = colors.textMid,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(if (isGrid) Alignment.TopEnd else Alignment.CenterEnd)
                    .padding(if (isGrid) 10.dp else 16.dp)
                    .background(colors.surface2, badgeShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
