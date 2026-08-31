package com.example.optimalx.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.eidos.PanelStateScope
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.workshop.WorkshopPreviewPanel

@Composable
fun PanelRunnerScreen(
    workshopSubfolderId: Long,
    eidosViewModel: EidosChatViewModel,
    onBack: () -> Unit,
    onEidosClick: () -> Unit,
) {
    val viewModel: PanelRunnerViewModel = viewModel(
        key = "panel_runner_$workshopSubfolderId",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                PanelRunnerViewModel(app, workshopSubfolderId)
            }
        },
    )

    val panelName by viewModel.panelName.collectAsState()
    val compositeHtml by viewModel.compositeHtml.collectAsState()
    val colors = LocalOptimalXColors.current

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = colors.textMid, fontFamily = DmSansFamily)
            }
            Text(
                text = panelName,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEidosClick) {
                Text("Eidos", color = colors.accent, fontFamily = DmSansFamily)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            key(compositeHtml) {
                WorkshopPreviewPanel(
                    html = compositeHtml,
                    htmlFilePath = null,
                    workshopSubfolderId = workshopSubfolderId,
                    panelContextType = "gallery",
                    panelStateScopeKey = PanelStateScope.GLOBAL,
                    isVisibleAndFocused = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
