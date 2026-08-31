package com.example.optimalx.ui.eidos.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.data.eidos.ChatVisionAttachment
import com.example.optimalx.data.eidos.ChatVisionAttachmentCodec
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

/** Filename chip when a chat-vision attach is present (pixels may be missing after sync). */
@Composable
fun ChatVisionAttachmentChip(
    attachment: ChatVisionAttachment,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .padding(bottom = 6.dp)
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, colors.accentDim, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Image,
            contentDescription = "Image attached",
            tint = colors.textDim,
            modifier = Modifier
                .padding(end = 6.dp)
                .size(14.dp),
        )
        Text(
            text = ChatVisionAttachmentCodec.displayLabel(attachment),
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
