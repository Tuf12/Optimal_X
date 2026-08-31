package com.example.optimalx.ui.eidos.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.optimalx.data.eidos.EidosNavigationCodec
import com.example.optimalx.data.eidos.EidosNavigationTarget
import com.example.optimalx.ui.theme.LocalOptimalXColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EidosNavigationChips(
    targets: List<EidosNavigationTarget>,
    onNavigate: (EidosNavigationTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val deduped = EidosNavigationCodec.dedupeTargets(targets)
    if (deduped.isEmpty()) return

    val colors = LocalOptimalXColors.current
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        deduped.forEach { target ->
            AssistChip(
                onClick = { onNavigate(target) },
                label = {
                    Text(
                        text = EidosNavigationCodec.navigationActionLabel(target),
                        color = colors.textPrimary,
                    )
                },
                shape = RoundedCornerShape(999.dp),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = colors.surface2,
                    labelColor = colors.textPrimary,
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = colors.accentDim,
                ),
            )
        }
    }
}
