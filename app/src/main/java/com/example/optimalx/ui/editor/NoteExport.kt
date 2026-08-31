package com.example.optimalx.ui.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.example.optimalx.ui.components.chatMarkdownToDisplayHtml
import com.example.optimalx.ui.components.escapeHtmlText
import com.example.optimalx.voice.stripMarkdownForTts
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object NoteExport {
    const val MIME_HTML = "text/html"
    const val MIME_MARKDOWN = "text/markdown"
    const val MIME_PLAIN_UTF8 = "text/plain"

    fun sanitizeBaseName(baseName: String): String {
        val trimmed = baseName.trim().ifBlank { "note" }
        return trimmed
            .removeSuffix(".html")
            .removeSuffix(".HTML")
            .removeSuffix(".pdf")
            .removeSuffix(".PDF")
            .removeSuffix(".md")
            .removeSuffix(".MD")
            .replace(Regex("""[^\w\s-]+"""), "-")
            .replace(Regex("""\s+"""), "-")
            .replace(Regex("""-+"""), "-")
            .trim('-')
            .ifBlank { "note" }
    }

    fun sanitizeFileName(baseName: String, format: NoteExportFormat): String {
        val sanitized = sanitizeBaseName(baseName)
        return when (format) {
            NoteExportFormat.PDF -> "$sanitized.pdf"
            NoteExportFormat.MARKDOWN -> "$sanitized.md"
        }
    }

    fun mimeType(format: NoteExportFormat): String = when (format) {
        NoteExportFormat.PDF -> NotePdfExport.MIME_PDF
        NoteExportFormat.MARKDOWN -> MIME_MARKDOWN
    }

    fun markdownToRenderedBodyHtml(markdown: String): String =
        chatMarkdownToDisplayHtml(markdown)

    fun wrapHtmlDocument(title: String, bodyHtml: String): String {
        val safeTitle = escapeHtmlText(title.ifBlank { "Note" })
        return """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |  <meta charset="utf-8">
            |  <meta name="viewport" content="width=device-width, initial-scale=1">
            |  <title>$safeTitle</title>
            |  <style>
            |    body { font-family: sans-serif; font-size: 15px; line-height: 1.6; color: #111; padding: 16px; max-width: 720px; margin: 0 auto; }
            |    h1 { font-size: 1.75em; font-weight: 700; margin: 1em 0 0.5em; }
            |    h2 { font-size: 1.5em; font-weight: 600; margin: 1em 0 0.5em; }
            |    h3 { font-size: 1.25em; font-weight: 600; margin: 1em 0 0.5em; }
            |    p { margin: 0.5em 0; }
            |    ul, ol { margin: 0.5em 0; padding-left: 1.5em; }
            |    li { margin: 0.25em 0; }
            |    a { color: #1a73e8; text-decoration: underline; }
            |    code { font-family: monospace; background: #f4f4f4; padding: 0.1em 0.3em; border-radius: 3px; font-size: 0.92em; }
            |    pre { background: #f4f4f4; padding: 12px; border-radius: 6px; overflow-x: auto; }
            |    pre code { background: none; padding: 0; }
            |    strong, b { font-weight: 700; }
            |    em, i { font-style: italic; }
            |  </style>
            |</head>
            |<body>
            |$bodyHtml
            |</body>
            |</html>
        """.trimMargin()
    }

    fun buildRenderedHtmlDocument(title: String, markdown: String): String {
        val body = markdownToRenderedBodyHtml(markdown)
        return wrapHtmlDocument(title, body)
    }

    fun shareRenderedNote(
        context: Context,
        markdown: String,
        title: String,
        chooserTitle: String,
    ) {
        val html = buildRenderedHtmlDocument(title, markdown)
        val plainFallback = stripMarkdownForTts(markdown).ifBlank { markdown }
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = MIME_HTML
                    putExtra(Intent.EXTRA_HTML_TEXT, html)
                    putExtra(Intent.EXTRA_TEXT, plainFallback)
                },
                chooserTitle,
            ),
        )
    }

    suspend fun sharePdfNote(
        context: Context,
        markdown: String,
        title: String,
        chooserTitle: String,
    ) {
        val html = buildRenderedHtmlDocument(title, markdown)
        val fileName = sanitizeFileName(title, NoteExportFormat.PDF)
        val pdfFile = NotePdfExport.writePdfToCacheFile(context, html, fileName)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.exportfileprovider",
            pdfFile,
        )
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = NotePdfExport.MIME_PDF
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "Note" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                chooserTitle,
            ),
        )
    }

    @Throws(IOException::class)
    fun writeMarkdownToUri(context: Context, uri: Uri, markdown: String) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(markdown.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Could not open output stream for $uri")
    }
}

class NoteExportActions internal constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val exportBaseName: String,
    private val shareChooserTitle: String,
    private val sharePdfChooserTitle: String,
    private val onFlushBeforeExport: suspend (String) -> Unit,
    private val launchCreateDocument: (String, String) -> Unit,
    private val stashPendingExport: (String, NoteExportFormat) -> Unit,
    private val writeExport: suspend (Uri, String, NoteExportFormat, String) -> Unit,
    private val onExportMessage: (String) -> Unit,
) {
    fun share(markdown: String) {
        scope.launch {
            onFlushBeforeExport(markdown)
            NoteExport.shareRenderedNote(
                context = context,
                markdown = markdown,
                title = exportBaseName,
                chooserTitle = shareChooserTitle,
            )
        }
    }

    fun sharePdf(markdown: String) {
        scope.launch {
            try {
                onFlushBeforeExport(markdown)
                NoteExport.sharePdfNote(
                    context = context,
                    markdown = markdown,
                    title = exportBaseName,
                    chooserTitle = sharePdfChooserTitle,
                )
            } catch (e: Exception) {
                onExportMessage(e.message ?: "Could not share PDF")
            }
        }
    }

    fun export(markdown: String, format: NoteExportFormat) {
        scope.launch {
            onFlushBeforeExport(markdown)
            stashPendingExport(markdown, format)
            launchCreateDocument(
                NoteExport.sanitizeFileName(exportBaseName, format),
                NoteExport.mimeType(format),
            )
        }
    }
}

@Composable
fun rememberNoteExportActions(
    exportBaseName: String,
    shareChooserTitle: String = "Share note",
    sharePdfChooserTitle: String = "Share note as PDF",
    onFlushBeforeExport: suspend (String) -> Unit,
): NoteExportActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExportMarkdown by remember { mutableStateOf<String?>(null) }
    var pendingExportFormat by remember { mutableStateOf(NoteExportFormat.PDF) }
    val latestFlush = rememberUpdatedState(onFlushBeforeExport)

    fun showMessage(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun completeExport(uri: Uri?) {
        val markdown = pendingExportMarkdown
        val format = pendingExportFormat
        pendingExportMarkdown = null
        if (uri != null && markdown != null) {
            scope.launch {
                runCatching {
                    writeExport(context, uri, markdown, format, exportBaseName)
                }.onSuccess {
                    showMessage(
                        when (format) {
                            NoteExportFormat.PDF -> "PDF exported"
                            NoteExportFormat.MARKDOWN -> "Markdown exported"
                        },
                    )
                }.onFailure { error ->
                    showMessage(error.message ?: "Export failed")
                }
            }
        }
    }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(NotePdfExport.MIME_PDF),
    ) { uri -> completeExport(uri) }

    val exportMarkdownLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(NoteExport.MIME_MARKDOWN),
    ) { uri -> completeExport(uri) }

    return remember(exportBaseName, shareChooserTitle, sharePdfChooserTitle) {
        NoteExportActions(
            scope = scope,
            context = context,
            exportBaseName = exportBaseName,
            shareChooserTitle = shareChooserTitle,
            sharePdfChooserTitle = sharePdfChooserTitle,
            onFlushBeforeExport = { content -> latestFlush.value(content) },
            launchCreateDocument = { fileName, mimeType ->
                pendingExportFormat = when (mimeType) {
                    NotePdfExport.MIME_PDF -> NoteExportFormat.PDF
                    else -> NoteExportFormat.MARKDOWN
                }
                when (mimeType) {
                    NotePdfExport.MIME_PDF -> exportPdfLauncher.launch(fileName)
                    else -> exportMarkdownLauncher.launch(fileName)
                }
            },
            stashPendingExport = { markdown, format ->
                pendingExportMarkdown = markdown
                pendingExportFormat = format
            },
            writeExport = { uri, markdown, format, title ->
                writeExport(context, uri, markdown, format, title)
            },
            onExportMessage = ::showMessage,
        )
    }
}

private suspend fun writeExport(
    context: Context,
    uri: Uri,
    markdown: String,
    format: NoteExportFormat,
    title: String,
) {
    when (format) {
        NoteExportFormat.PDF -> {
            val html = NoteExport.buildRenderedHtmlDocument(title, markdown)
            NotePdfExport.writePdfFromHtml(context, html, uri)
        }
        NoteExportFormat.MARKDOWN -> {
            NoteExport.writeMarkdownToUri(context, uri, markdown)
        }
    }
}
