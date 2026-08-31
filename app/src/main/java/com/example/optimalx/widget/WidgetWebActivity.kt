package com.example.optimalx.widget

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.ui.eidos.EidosChatEntrySurface
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.theme.OptimalXTheme
import com.example.optimalx.ui.web.WebPanel
import com.example.optimalx.ui.web.WebPanelScope

class WidgetWebActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_QUERY = "initial_query"
    }

    private var initialQuery by mutableStateOf("")
    private var launchNonce by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialQuery = intent.getStringExtra(EXTRA_INITIAL_QUERY).orEmpty()
        launchNonce = System.currentTimeMillis()

        setContent {
            OptimalXTheme {
                val eidosViewModel: EidosChatViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                            EidosChatViewModel(app as Application, EidosChatEntrySurface.WIDGET)
                        }
                    },
                )
                LaunchedEffect(Unit) {
                    eidosViewModel.setWebWidgetScope()
                }
                DisposableEffect(eidosViewModel) {
                    eidosViewModel.setChatUiVisible(true)
                    onDispose { eidosViewModel.setChatUiVisible(false) }
                }
                key(launchNonce) {
                    WebPanel(
                        modifier = Modifier.fillMaxSize(),
                        panelTitle = "Widget Web",
                        eidosViewModel = eidosViewModel,
                        scopeKey = WebPanelScope.WIDGET,
                        initialUrl = initialQuery,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initialQuery = intent.getStringExtra(EXTRA_INITIAL_QUERY).orEmpty()
        launchNonce = System.currentTimeMillis()
    }
}
