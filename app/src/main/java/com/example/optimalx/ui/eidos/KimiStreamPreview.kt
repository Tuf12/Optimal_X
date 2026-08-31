package com.example.optimalx.ui.eidos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.data.eidos.model.EidosStreamUpdate
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun KimiStreamPreviewBubble(
    preview: EidosStreamUpdate?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val reasoning = preview?.reasoningText.orEmpty()
    val content = preview?.contentText.orEmpty()
    val bubbleShape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .border(1.dp, colors.messageBubbleEidosBorder, bubbleShape)
                .clip(bubbleShape)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(colors.accentDim, colors.surface2),
                    ),
                )
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            when {
                reasoning.isNotBlank() -> {
                    Text(
                        text = "Reasoning",
                        color = colors.textMid,
                        fontFamily = DmSansFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = reasoning,
                        color = colors.textDim,
                        fontFamily = DmMonoFamily,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                content.isNotBlank() -> {
                    Text(
                        text = content,
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                    )
                }
                else -> {
                    Text(
                        text = "Thinking…",
                        color = colors.textMid,
                        fontFamily = DmSansFamily,
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
            if (reasoning.isNotBlank() && content.isNotBlank()) {
                Text(
                    text = content,
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
