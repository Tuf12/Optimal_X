package com.example.optimalx.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.data.litert.LitertLmDefaults
import com.example.optimalx.data.litert.LitertLmModelLocator
import com.example.optimalx.data.litert.LitertLmSmokeRunner
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.SyneFamily
import kotlinx.coroutines.launch

@Composable
fun LitertLmSmokeScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    val colors = LocalOptimalXColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { LitertLmSmokeRunner() }

    var modelPath by rememberSaveable {
        mutableStateOf(
            LitertLmDefaults.resolveModelPath(context, "").ifBlank {
                LitertLmModelLocator.canonicalInstallFile(context).absolutePath
            },
        )
    }
    var prompt by rememberSaveable {
        mutableStateOf(LitertLmDefaults.DEFAULT_SMOKE_PROMPT)
    }
    var status by rememberSaveable { mutableStateOf("Idle") }
    var resultText by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        LitertLmSmokeTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Phase 1 smoke test — load Gemma 4 E4B via LiteRT-LM and run one prompt on GPU. Expect several seconds for first load.",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Text(
                text = "Model path (.litertlm)",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            TextField(
                value = modelPath,
                onValueChange = { modelPath = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                enabled = !running,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = DmMonoFamily,
                    fontSize = 12.sp,
                    color = colors.textPrimary,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    cursorColor = colors.accent,
                    focusedIndicatorColor = colors.accent,
                    unfocusedIndicatorColor = colors.border,
                ),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Prompt",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            TextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                enabled = !running,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = DmMonoFamily,
                    fontSize = 12.sp,
                    color = colors.textPrimary,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    cursorColor = colors.accent,
                    focusedIndicatorColor = colors.accent,
                    unfocusedIndicatorColor = colors.border,
                ),
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = {
                    if (running) return@TextButton
                    running = true
                    status = "Loading model and running prompt…"
                    resultText = ""
                    scope.launch {
                        val outcome = runner.runOneShotPrompt(
                            context = context.applicationContext,
                            modelPath = modelPath,
                            prompt = prompt,
                        )
                        outcome.fold(
                            onSuccess = { result ->
                                status = "OK — init ${result.initMillis}ms, inference ${result.inferenceMillis}ms"
                                resultText = result.responseText
                            },
                            onFailure = { error ->
                                status = "Failed: ${error.message ?: error::class.simpleName}"
                                resultText = error.stackTraceToString()
                            },
                        )
                        running = false
                    }
                },
                enabled = !running,
            ) {
                Text(
                    text = if (running) "Running…" else "Run smoke test",
                    fontFamily = DmSansFamily,
                    color = colors.accent,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = status,
                color = colors.accent,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )

            if (resultText.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = resultText,
                    color = colors.textPrimary,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun LitertLmSmokeTopBar(onBack: () -> Unit) {
    val colors = LocalOptimalXColors.current
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = colors.textPrimary,
            )
        }
        Text(
            text = "LiteRT-LM smoke test",
            color = colors.textPrimary,
            fontFamily = SyneFamily,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
