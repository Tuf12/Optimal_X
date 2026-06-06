package com.example.optimalx.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

@Composable
fun DocxViewerPanel(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val text = extractDocxText(filePath)
                withContext(Dispatchers.Main) { content = text }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = "Could not open document: ${e.message}" }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        when {
            error != null -> Text(
                text = error!!,
                color = colors.textDim,
                fontFamily = DmSansFamily,
                fontSize = 14.sp,
                modifier = Modifier.padding(24.dp),
            )
            content == null -> CircularProgressIndicator(color = colors.accent)
            else -> Text(
                text = content!!,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Extracts plain text from a .docx file by reading word/document.xml inside the ZIP.
 * Handles both <w:t> elements and paragraph breaks.
 */
private fun extractDocxText(filePath: String): String {
    val file = File(filePath)
    val sb = StringBuilder()

    ZipInputStream(FileInputStream(file)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                val xml = zip.readBytes().decodeToString()
                // Extract text runs, treating paragraph ends as newlines
                val paragraphPattern = Regex("<w:p[ >][^/]*?</w:p>", RegexOption.DOT_MATCHES_ALL)
                val runPattern = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)

                paragraphPattern.findAll(xml).forEach { para ->
                    val paraText = runPattern.findAll(para.value)
                        .joinToString("") { it.groupValues[1] }
                    if (paraText.isNotEmpty()) sb.appendLine(paraText)
                    else sb.appendLine()
                }
                break
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    return sb.toString().trim()
}
