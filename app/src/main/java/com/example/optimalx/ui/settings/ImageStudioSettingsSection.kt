package com.example.optimalx.ui.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.data.imagestudio.ImageAspectRatio
import com.example.optimalx.data.imagestudio.ImageStudioModelCatalog
import com.example.optimalx.data.imagestudio.ImageTier
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageStudioSettingsSection(
    xaiKeyConfigured: Boolean,
    defaultTier: ImageTier,
    defaultAspectRatio: ImageAspectRatio,
    onDefaultTierChange: (ImageTier) -> Unit,
    onDefaultAspectRatioChange: (ImageAspectRatio) -> Unit,
    onFocusApiKeys: (() -> Unit)? = null,
) {
    val colors = LocalOptimalXColors.current

    Text(
        text = "Cloud provider: Grok Imagine (xAI). Uses the same xAI API key as chat.",
        color = colors.textDim,
        fontFamily = DmSansFamily,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )

    if (!xaiKeyConfigured) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Add your xAI API key in API Keys above to generate images.",
            color = colors.badgePdfText,
            fontFamily = DmSansFamily,
            fontSize = 12.sp,
        )
        onFocusApiKeys?.let { focus ->
            TextButton(onClick = focus) {
                Text("Go to API Keys", color = colors.accent, fontFamily = DmSansFamily)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        text = "Default tier",
        color = colors.textPrimary,
        fontFamily = DmSansFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        ImageTier.entries.forEach { tier ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 12.dp),
            ) {
                RadioButton(
                    selected = defaultTier == tier,
                    onClick = { onDefaultTierChange(tier) },
                    colors = RadioButtonDefaults.colors(selectedColor = colors.accent),
                )
                Text(
                    text = tier.name.lowercase().replaceFirstChar { it.titlecase() },
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                )
            }
        }
    }
    Text(
        text = "Est. cost per image: ${ImageStudioModelCatalog.estimateCost(defaultTier).label}",
        color = colors.textDim,
        fontFamily = DmMonoFamily,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    Text(
        text = "Default aspect ratio",
        color = colors.textPrimary,
        fontFamily = DmSansFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
    FlowRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ImageAspectRatio.entries.forEach { ratio ->
            FilterChip(
                selected = defaultAspectRatio == ratio,
                onClick = { onDefaultAspectRatioChange(ratio) },
                label = {
                    Text(ratio.wireValue, fontFamily = DmMonoFamily, fontSize = 12.sp)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                    selectedLabelColor = colors.accent,
                ),
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = "New Image Studio sessions start with these defaults. Per-folder form state is still saved separately.",
        color = colors.textDim,
        fontFamily = DmMonoFamily,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}
