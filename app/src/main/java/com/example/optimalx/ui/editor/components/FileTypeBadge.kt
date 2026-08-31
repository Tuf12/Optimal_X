package com.example.optimalx.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

private enum class BadgeType { PDF, DOC, IMG, OTHER }

private fun String.toBadgeType(): BadgeType = when (lowercase()) {
    "pdf" -> BadgeType.PDF
    "docx", "odt", "doc" -> BadgeType.DOC
    "jpg", "jpeg", "png", "gif", "webp", "heic", "image" -> BadgeType.IMG
    else -> BadgeType.OTHER
}

@Composable
fun FileTypeBadge(fileType: String, modifier: Modifier = Modifier) {
    val colors = LocalOptimalXColors.current

    val (text, textColor, bgColor) = when (fileType.toBadgeType()) {
        BadgeType.PDF -> Triple("PDF", colors.badgePdfText, colors.badgePdfBg)
        BadgeType.DOC -> Triple("DOC", colors.badgeDocText, colors.badgeDocBg)
        BadgeType.IMG -> Triple("IMG", colors.badgeImgText, colors.badgeImgBg)
        BadgeType.OTHER -> Triple(fileType.uppercase().take(4), colors.textDim, colors.surface2)
    }

    Text(
        text = text,
        color = textColor,
        fontFamily = DmMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        modifier = modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
