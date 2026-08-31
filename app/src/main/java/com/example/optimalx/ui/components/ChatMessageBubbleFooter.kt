package com.example.optimalx.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

/**
 * Timestamp and action icons for a chat bubble, visually separated from message body text.
 */
@Composable
fun ChatMessageBubbleFooter(
    timeLabel: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        HorizontalDivider(
            color = colors.borderSoft.copy(alpha = 0.7f),
            thickness = 1.dp,
        )
        Text(
            text = timeLabel,
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
fun ChatMessageRetryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    IconButton(
        onClick = onClick,
        modifier = modifier.size(28.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Retry",
            tint = colors.textDim,
            modifier = Modifier.size(18.dp),
        )
    }
}
