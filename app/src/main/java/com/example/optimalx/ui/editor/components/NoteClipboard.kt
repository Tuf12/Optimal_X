package com.example.optimalx.ui.editor.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.example.optimalx.ui.components.chatMarkdownToDisplayHtml

/** Reads both `text/plain` and `text/html` from the system clipboard. */
fun Context.clipboardPlainAndHtml(): Pair<String?, String?> {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null to null
    val item = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null to null
    val plain = item.coerceToText(this)?.toString()
    val html = item.htmlText
    return plain to html
}

/** Puts markdown on `text/plain` and rendered HTML on `text/html` for other apps. */
fun Context.copyMarkdownWithRenderedHtml(markdown: String, label: String = "note") {
    if (markdown.isEmpty()) return
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val html = chatMarkdownToDisplayHtml(markdown)
    manager.setPrimaryClip(ClipData.newHtmlText(label, markdown, html))
}
