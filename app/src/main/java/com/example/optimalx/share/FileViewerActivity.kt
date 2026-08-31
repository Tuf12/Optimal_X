package com.example.optimalx.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.editor.panels.DocxViewerPanel
import com.example.optimalx.ui.editor.panels.ImageViewerPanel
import com.example.optimalx.ui.editor.panels.PdfViewerPanel
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.OptimalXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Purge temp view files older than 2 hours to keep cache clean
        purgeStaleTempFiles()

        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }

        if (uri == null) {
            finish()
            return
        }

        val mimeType = intent.type ?: contentResolver.getType(uri) ?: ""
        val fileName = resolveFileName(uri)

        setContent {
            OptimalXTheme {
                FileViewerScreen(
                    uri = uri,
                    mimeType = mimeType,
                    fileName = fileName,
                    onBack = { finish() },
                    onSaveToOptimalX = { openSavePicker(uri) },
                )
            }
        }
    }

    private fun resolveFileName(uri: Uri): String {
        var name = ""
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
        }
        return name.ifEmpty { uri.lastPathSegment?.substringAfterLast('/') ?: "file" }
    }

    private fun openSavePicker(uri: Uri) {
        startActivity(
            Intent(this, ShareReceiverActivity::class.java).apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uri)
                // Grant read permission so ShareReceiverActivity can copy the file
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    private fun purgeStaleTempFiles() {
        val dir = File(cacheDir, "view_temp")
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - 2 * 60 * 60 * 1000L // 2 hours
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}

/** Copies a content URI to a temp file in cacheDir/view_temp and returns the absolute path. */
private suspend fun copyToViewTemp(context: android.content.Context, uri: Uri, fileName: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "view_temp").apply { mkdirs() }
            val dest = File(dir, "${System.currentTimeMillis()}_$fileName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
private fun FileViewerScreen(
    uri: Uri,
    mimeType: String,
    fileName: String,
    onBack: () -> Unit,
    onSaveToOptimalX: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current

    // Copy the content URI to a temp path so existing viewer panels can read it as a File.
    val tempPath by produceState<String?>(initialValue = null, uri.toString()) {
        value = copyToViewTemp(context, uri, fileName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = fileName,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            TextButton(onClick = onSaveToOptimalX) {
                Text(
                    text = "Save to OptimalX",
                    color = colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }
        }

        HorizontalDivider(color = colors.border, thickness = 1.dp)

        // ── File content ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                tempPath == null -> {
                    // Still copying to temp — show spinner
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(36.dp))
                }
                isPdf(mimeType, fileName) -> {
                    PdfViewerPanel(
                        fileId = 0L,
                        filePath = tempPath!!,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                isImage(mimeType, fileName) -> {
                    ImageViewerPanel(
                        filePath = tempPath!!,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                isDocx(mimeType, fileName) -> {
                    DocxViewerPanel(
                        filePath = tempPath!!,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                isText(mimeType, fileName) -> {
                    PlainTextViewer(
                        filePath = tempPath!!,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    // Unsupported type — show name and save option
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = fileName,
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                        )
                        Text(
                            text = "Preview not available",
                            color = colors.textDim,
                            fontFamily = DmSansFamily,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainTextViewer(filePath: String, modifier: Modifier = Modifier) {
    val colors = LocalOptimalXColors.current
    var content by remember(filePath) { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath) {
        content = withContext(Dispatchers.IO) {
            try { File(filePath).readText() } catch (_: Exception) { null }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            content == null -> CircularProgressIndicator(
                color = colors.accent,
                modifier = Modifier.size(28.dp),
            )
            content!!.isEmpty() -> Text(
                text = "Empty file",
                color = colors.textDim,
                fontFamily = DmSansFamily,
                fontSize = 14.sp,
            )
            else -> Text(
                text = content!!,
                color = colors.textPrimary,
                fontFamily = DmMonoFamily,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

// ── MIME / extension helpers ──────────────────────────────────────────────────

private fun isPdf(mimeType: String, fileName: String) =
    mimeType == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true)

private fun isImage(mimeType: String, fileName: String): Boolean {
    if (mimeType.startsWith("image/")) return true
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic")
}

private fun isDocx(mimeType: String, fileName: String): Boolean {
    if (mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document") return true
    if (mimeType == "application/vnd.oasis.opendocument.text") return true
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in setOf("docx", "odt")
}

private fun isText(mimeType: String, fileName: String): Boolean {
    if (mimeType.startsWith("text/")) return true
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in setOf("txt", "md", "json", "xml", "html", "css", "js", "py", "kt", "ts")
}
