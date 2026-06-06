package com.example.optimalx.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun ComingSoonScreen(
    title: String,
    message: String,
    onBack: () -> Unit,
) {
    val colors = LocalOptimalXColors.current

    BackHandler(onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                color = colors.textMid,
                fontFamily = DmSansFamily,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onBack) {
                Text(
                    text = "Back",
                    color = colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
            }
        }
    }
}
