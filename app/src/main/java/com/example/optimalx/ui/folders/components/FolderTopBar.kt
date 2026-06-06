package com.example.optimalx.ui.folders.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun FolderTopBar(
    locationLabel: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onEidosClick: () -> Unit,
    onEidosSectionClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val searchShape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = locationLabel,
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(searchShape)
                    .background(colors.surface2)
                    .border(1.dp, colors.border, searchShape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = colors.textDim,
                    modifier = Modifier.size(18.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Search",
                            color = colors.textDim,
                            fontFamily = DmSansFamily,
                            fontSize = 15.sp,
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 15.sp,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                    )
                }
            }

            IconButton(
                onClick = onEidosClick,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    text = "E",
                    color = colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                )
            }

            if (onEidosSectionClick != null) {
                IconButton(onClick = onEidosSectionClick) {
                    Text(
                        text = "Ξ",
                        color = colors.textMid,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                    )
                }
            }

            if (onSettingsClick != null) {
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Settings",
                        tint = colors.textMid,
                    )
                }
            }
        }
    }
}
