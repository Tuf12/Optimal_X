package com.example.optimalx.ui.folders.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.folders.SortOrder
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortBottomSheet(
    currentSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.sheetBackground,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 20.dp),
        ) {
            Text(
                text = "Sort by",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            SortOrder.entries.forEach { order ->
                val isSelected = order == currentSort
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSortSelected(order); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = order.label,
                        color = if (isSelected) colors.accent else colors.textPrimary,
                        fontFamily = DmSansFamily,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Spacer(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.accent),
                        )
                    }
                }
            }
        }
    }
}
