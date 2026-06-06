package com.example.optimalx.ui.workshop

import android.annotation.SuppressLint
import android.webkit.WebView
import com.example.optimalx.data.model.FileReference
import java.io.File

/**
 * Builds self-contained panel HTML for Android WebView and loads it reliably.
 *
 * External `<script src>` / `<link rel=stylesheet>` often fail under `file://` in WebView;
 * composite HTML inlines assets and strips duplicate external references.
 */
object PanelHtmlComposer {

    private val EXTERNAL_SCRIPT = Regex(
        """<script\b[^>]*\bsrc\s*=[^>]*>[\s\S]*?</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val EXTERNAL_STYLESHEET = Regex(
        """<link\b[^>]*\brel\s*=\s*["']stylesheet["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    fun stripExternalAssets(html: String): String =
        html.replace(EXTERNAL_SCRIPT, "")
            .replace(EXTERNAL_STYLESHEET, "")

    fun buildCompositeHtml(
        htmlContent: String,
        cssContents: List<String>,
        jsContents: List<Pair<String, String>>,
    ): String {
        val stripped = stripExternalAssets(htmlContent)
        val cssInject = cssContents.joinToString("\n") { css ->
            "<style>\n$css\n</style>"
        }
        val orderedJs = jsContents.sortedBy { (name, _) ->
            when {
                name.equals("bridge.js", ignoreCase = true) -> 0
                else -> 1
            }
        }
        val jsInject = orderedJs.joinToString("\n") { (_, js) ->
            "<script>\n$js\n</script>"
        }

        return if (stripped.contains("</head>", ignoreCase = true)) {
            stripped.replace("</head>", "$cssInject\n</head>", ignoreCase = true)
                .replace("</body>", "$jsInject\n</body>", ignoreCase = true)
        } else {
            "<html><head>$cssInject</head><body>$stripped$jsInject</body></html>"
        }
    }

    fun buildCompositeHtmlFromFileReferences(files: List<FileReference>): String {
        val htmlFile = files.firstOrNull { it.fileType.equals("html", ignoreCase = true) }
            ?: return "<html><body><p>No HTML file found.</p></body></html>"

        val htmlContent = runCatching { File(htmlFile.filePath).readText() }.getOrDefault("")
        val cssContents = files
            .filter { it.fileType.equals("css", ignoreCase = true) }
            .map { runCatching { File(it.filePath).readText() }.getOrDefault("") }
        val jsContents = files
            .filter { it.fileType.equals("js", ignoreCase = true) }
            .map { ref -> ref.fileName to runCatching { File(ref.filePath).readText() }.getOrDefault("") }

        return buildCompositeHtml(
            htmlContent = htmlContent,
            cssContents = cssContents,
            jsContents = jsContents,
        )
    }

    /** Builds composite HTML from a published release directory (`panel_releases/{id}/`). */
    fun buildCompositeHtmlFromDirectory(dir: File): String? {
        if (!dir.isDirectory) return null
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        val htmlFile = files.firstOrNull { it.extension.equals("html", ignoreCase = true) }
            ?: return null
        val htmlContent = runCatching { htmlFile.readText() }.getOrDefault("")
        val cssContents = files
            .filter { it.extension.equals("css", ignoreCase = true) }
            .map { runCatching { it.readText() }.getOrDefault("") }
        val jsContents = files
            .filter { it.extension.equals("js", ignoreCase = true) }
            .map { file -> file.name to runCatching { file.readText() }.getOrDefault("") }
        return buildCompositeHtml(
            htmlContent = htmlContent,
            cssContents = cssContents,
            jsContents = jsContents,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebViewForLocalPanel(webView: WebView) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }
    }

    /**
     * Prefer [compositeHtml] (inlined assets). When only [htmlFilePath] is set, load via
     * `loadDataWithBaseURL` so relative script/css paths resolve under the project directory.
     */
    fun loadPanelContent(
        webView: WebView,
        compositeHtml: String,
        htmlFilePath: String? = null,
    ) {
        configureWebViewForLocalPanel(webView)
        if (compositeHtml.isNotBlank()) {
            val baseUrl = htmlFilePath?.let { path ->
                File(path).parentFile?.absolutePath?.let { dir ->
                    if (dir.endsWith("/")) "file://$dir" else "file://$dir/"
                }
            }
            webView.loadDataWithBaseURL(baseUrl, compositeHtml, "text/html", "UTF-8", null)
            return
        }
        if (!htmlFilePath.isNullOrBlank()) {
            val file = File(htmlFilePath)
            val parent = file.parentFile
            if (parent != null && file.exists()) {
                val baseUrl = "file://${parent.absolutePath}/"
                webView.loadDataWithBaseURL(baseUrl, file.readText(), "text/html", "UTF-8", null)
                return
            }
            webView.loadUrl("file://${file.absolutePath}")
        }
    }
}
