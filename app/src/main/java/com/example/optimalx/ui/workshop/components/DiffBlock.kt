package com.example.optimalx.ui.workshop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

/** Shared diff-line palette used by [DiffBlock] in the review and history surfaces. */
object DiffColors {
    val Addition: Color = Color(0xFF7BD389)
    val Deletion: Color = Color(0xFFE07A7A)
    val HunkHeader: Color = Color(0xFF8AB8E8)
}

/**
 * Renders a unified-diff string with one [Text] row per line, colored by line marker:
 * additions green, deletions red, hunk headers blue-grey, file headers dim.
 *
 * Shared between `DiffReviewScreen` (pending proposals) and `WorkshopHistorySheet`
 * (checkpoint vs working copy).
 */
@Composable
fun DiffBlock(
    diff: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val lines = remember(diff) { diff.split('\n') }
    val hScroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface2)
            .horizontalScroll(hScroll)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (lines.isEmpty() || (lines.size == 1 && lines[0].isEmpty())) {
            Text(
                text = "(no textual diff)",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
        } else {
            for (line in lines) {
                val color = when {
                    line.startsWith("+++") || line.startsWith("---") -> colors.textDim
                    line.startsWith("@@") -> DiffColors.HunkHeader
                    line.startsWith("+") -> DiffColors.Addition
                    line.startsWith("-") -> DiffColors.Deletion
                    else -> colors.textMid
                }
                Text(
                    text = if (line.isEmpty()) " " else line,
                    color = color,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
