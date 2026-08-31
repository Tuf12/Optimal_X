package com.example.optimalx.ui.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

object NotePdfExport {
    const val MIME_PDF = "application/pdf"

    private const val LAYOUT_WIDTH_PX = 1080
    private const val PDF_PAGE_WIDTH_PT = 595
    private const val PDF_PAGE_HEIGHT_PT = 842

    suspend fun writePdfFromHtml(context: Context, html: String, outputUri: Uri) {
        withContext(Dispatchers.Main) {
            val activity = context.findActivity()
                ?: throw IOException("Cannot export PDF while the editor is not visible")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                WebView.enableSlowWholeDocumentDraw()
            }

            suspendCancellableCoroutine { cont ->
                val webView = WebView(activity)
                webView.settings.apply {
                    javaScriptEnabled = false
                    blockNetworkLoads = true
                    domStorageEnabled = false
                }

                val host = FrameLayout(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        LAYOUT_WIDTH_PX,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    alpha = 0f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                host.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        LAYOUT_WIDTH_PX,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                val root = activity.window.decorView as ViewGroup
                root.addView(host)

                fun cleanup() {
                    root.removeView(host)
                    webView.destroy()
                }
                cont.invokeOnCancellation { cleanup() }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view ?: return
                        waitForLayoutThenWrite(
                            webView = webView,
                            context = activity,
                            outputUri = outputUri,
                            onSuccess = {
                                cleanup()
                                cont.resume(Unit)
                            },
                            onFailure = { error ->
                                cleanup()
                                cont.resumeWithException(error)
                            },
                            attempt = 0,
                        )
                    }
                }

                val encoded = Base64.encodeToString(
                    html.toByteArray(Charsets.UTF_8),
                    Base64.NO_PADDING or Base64.NO_WRAP,
                )
                webView.loadData(encoded, "text/html; charset=utf-8", "base64")
            }
        }
    }

    suspend fun writePdfToCacheFile(context: Context, html: String, fileName: String): File {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(exportsDir, fileName)
        if (outFile.exists()) outFile.delete()
        outFile.createNewFile()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.exportfileprovider",
            outFile,
        )
        writePdfFromHtml(context, html, uri)
        if (!outFile.exists() || outFile.length() < 128) {
            throw IOException("PDF export produced an empty file")
        }
        return outFile
    }

    private fun waitForLayoutThenWrite(
        webView: WebView,
        context: Context,
        outputUri: Uri,
        onSuccess: () -> Unit,
        onFailure: (IOException) -> Unit,
        attempt: Int,
    ) {
        val widthMeasure = View.MeasureSpec.makeMeasureSpec(LAYOUT_WIDTH_PX, View.MeasureSpec.EXACTLY)
        val heightMeasure = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        webView.measure(widthMeasure, heightMeasure)
        val measured = webView.measuredHeight
        val content = (webView.contentHeight * webView.scale).toInt()
        val height = maxOf(measured, content, webView.height).coerceAtLeast(1)

        if (height > 80 || attempt >= 30) {
            if (height <= 0) {
                onFailure(IOException("Could not lay out note content for PDF export"))
                return
            }
            try {
                writeWebViewToPdf(context, webView, height, outputUri)
                onSuccess()
            } catch (e: Exception) {
                onFailure(
                    if (e is IOException) e
                    else IOException(e.message ?: "PDF export failed", e),
                )
            }
            return
        }
        webView.postDelayed(
            {
                waitForLayoutThenWrite(
                    webView = webView,
                    context = context,
                    outputUri = outputUri,
                    onSuccess = onSuccess,
                    onFailure = onFailure,
                    attempt = attempt + 1,
                )
            },
            100L,
        )
    }

    private fun writeWebViewToPdf(
        context: Context,
        webView: WebView,
        contentHeight: Int,
        outputUri: Uri,
    ) {
        webView.layout(0, 0, LAYOUT_WIDTH_PX, contentHeight)

        val scale = PDF_PAGE_WIDTH_PT.toFloat() / LAYOUT_WIDTH_PX.toFloat()
        val pageContentHeightPx = (PDF_PAGE_HEIGHT_PT / scale).toInt().coerceAtLeast(1)

        val pdfDocument = PdfDocument()
        var pageIndex = 1
        var yOffset = 0
        while (yOffset < contentHeight) {
            val pageInfo = PdfDocument.PageInfo.Builder(
                PDF_PAGE_WIDTH_PT,
                PDF_PAGE_HEIGHT_PT,
                pageIndex,
            ).create()
            val page = pdfDocument.startPage(pageInfo)
            page.canvas.save()
            page.canvas.scale(scale, scale)
            page.canvas.translate(0f, -yOffset.toFloat())
            webView.draw(page.canvas)
            page.canvas.restore()
            pdfDocument.finishPage(page)
            yOffset += pageContentHeightPx
            pageIndex++
        }

        context.contentResolver.openOutputStream(outputUri)?.use { out ->
            pdfDocument.writeTo(out)
        } ?: throw IOException("Could not open PDF output")
        pdfDocument.close()
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
