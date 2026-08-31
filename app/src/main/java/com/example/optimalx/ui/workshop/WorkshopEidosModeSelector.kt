package com.example.optimalx.ui.workshop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.ui.theme.DmSansFamily

@Composable
fun WorkshopEidosModeSelector(
    activeMode: WorkshopEidosMode,
    onModeSelected: (WorkshopEidosMode) -> Unit,
    modifier: Modifier = Modifier,
    visibleModes: List<WorkshopEidosMode> = WorkshopEidosMode.selectorEntries,
) {
    val scroll = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        visibleModes.forEach { mode ->
            FilterChip(
                selected = activeMode == mode,
                onClick = { onModeSelected(mode) },
                label = {
                    Text(
                        text = mode.displayName,
                        fontFamily = DmSansFamily,
                        fontSize = 12.sp,
                    )
                },
            )
        }
    }
}
