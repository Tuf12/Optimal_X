package com.example.optimalx.ui.components

import android.content.Intent
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View.MeasureSpec
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichText

/**
 * Optional cap for in-bubble scrolling on very long selectable messages.
 * Pass explicitly when needed; chat bubbles default to full height in the [LazyColumn].
 */
@Composable
fun rememberSelectableChatScrollMaxHeight(fractionOfScreen: Float = 0.85f): Dp {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    return (screenHeightDp * fractionOfScreen).dp
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
fun MarkdownRichText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    /** When set, caps bubble height and scrolls inside the bubble. Null = expand to full message height. */
    selectableScrollMaxHeight: Dp? = null,
    onLinkClick: ((String) -> Boolean)? = null,
) {
    val displayHtml = remember(text) { chatMarkdownToDisplayHtml(text) }

    if (!selectable) {
        val richTextState = rememberRichTextState()

        LaunchedEffect(displayHtml) {
            richTextState.setHtml(displayHtml)
        }

        BasicRichText(
            state = richTextState,
            modifier = modifier,
            style = style,
        )
        return
    }
    val textColorArgb = style.color.toArgb()
    val fontSizeSp = style.fontSize.value
    val lineSpacingMultiplier = style.lineSpacingMultiplier()

    SelectableTextAndroidHost(
        modifier = modifier,
        contentKey = displayHtml,
        scrollMaxHeight = selectableScrollMaxHeight,
        onLinkClick = onLinkClick,
    ) { textView ->
        textView.movementMethod = ChatLinkMovementMethod(onLinkClick)
        textView.text = HtmlCompat.fromHtml(displayHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
        textView.setTextColor(textColorArgb)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        textView.setLineSpacing(0f, lineSpacingMultiplier)
    }
}

/**
 * Plain read-only text with native selection handles (for reasoning blocks, etc.).
 */
@Composable
fun SelectablePlainText(
    text: String,
    color: Color,
    fontSizeSp: Float,
    lineHeightRatio: Float = 1.35f,
    modifier: Modifier = Modifier,
    scrollMaxHeight: Dp? = null,
) {
    val textColorArgb = color.toArgb()

    SelectableTextAndroidHost(
        modifier = modifier,
        contentKey = text,
        scrollMaxHeight = scrollMaxHeight,
    ) { textView ->
        textView.text = text
        textView.setTextColor(textColorArgb)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        textView.setLineSpacing(0f, lineHeightRatio)
    }
}

@Composable
private fun SelectableTextAndroidHost(
    modifier: Modifier,
    contentKey: String,
    scrollMaxHeight: Dp?,
    onLinkClick: ((String) -> Boolean)? = null,
    update: (android.widget.TextView) -> Unit,
) {
    val density = LocalDensity.current
    val maxHeightPx = scrollMaxHeight?.let { with(density) { it.roundToPx() } }
    var hostHeightPx by remember(contentKey, maxHeightPx) { mutableIntStateOf(0) }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (hostHeightPx > 0) {
                    Modifier.height(with(density) { hostHeightPx.toDp() })
                } else {
                    Modifier.wrapContentHeight(align = Alignment.Top)
                },
            ),
        factory = { context -> SelectableTextScrollContainer(context) },
        update = { container ->
            update(container.body)
            container.post {
                val width = container.width.takeIf { it > 0 }
                    ?: container.measuredWidth.takeIf { it > 0 }
                    ?: return@post
                val heightSpec = when (maxHeightPx) {
                    null -> MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    else -> MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
                }
                container.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    heightSpec,
                )
                val measured = container.measuredHeight.coerceAtLeast(0)
                if (measured != hostHeightPx) {
                    hostHeightPx = measured
                }
            }
        },
    )
}

/** GFM fenced code blocks — the richeditor markdown parser drops these entirely. */
private val FENCED_CODE_BLOCK = Regex("(?m)^ {0,3}```([^\\n]*)\\n([\\s\\S]*?)\\n?```")

/**
 * Converts chat markdown (including ``` fenced code blocks) to HTML for display.
 * Fenced blocks are preserved as `<pre><code>` because richeditor only supports inline code spans.
 */
fun chatMarkdownToDisplayHtml(text: String): String {
    if (text.isBlank()) return ""
    return runCatching {
        chatMarkdownToDisplayHtmlInternal(text)
    }.getOrElse { error ->
        Log.w(TAG, "Markdown render failed, using plain fallback: ${error.message}")
        plainTextToFallbackHtml(text)
    }
}

private fun chatMarkdownToDisplayHtmlInternal(text: String): String {
    val normalized = text.normalizeMarkdownInput()
    if (!normalized.contains("```")) {
        return richTextSegmentToHtml(normalized)
    }

    val builder = StringBuilder()
    var lastIndex = 0
    for (match in FENCED_CODE_BLOCK.findAll(normalized)) {
        val before = normalized.substring(lastIndex, match.range.first)
        if (before.isNotEmpty()) {
            builder.append(richTextSegmentToHtml(before))
        }
        val code = match.groupValues[2].trimEnd('\n')
        builder.append("<pre><code>")
        builder.append(escapeHtmlText(code))
        builder.append("</code></pre>")
        lastIndex = match.range.last + 1
    }
    val after = normalized.substring(lastIndex)
    if (after.isNotEmpty()) {
        builder.append(richTextSegmentToHtml(after))
    }
    return builder.toString()
}

@OptIn(ExperimentalRichTextApi::class)
private fun richTextSegmentToHtml(text: String): String {
    if (text.isBlank()) return ""
    return runCatching {
        val state = RichTextState()
        if (text.noteTextLooksLikeHtml()) {
            state.setHtml(text)
        } else {
            state.setMarkdown(text)
        }
        state.toHtml().ifBlank { plainTextToFallbackHtml(text) }
    }.getOrElse { error ->
        Log.w(TAG, "Markdown segment render failed, using plain fallback: ${error.message}")
        plainTextToFallbackHtml(text)
    }
}

/** Escaped plain text when richeditor's markdown/HTML parser cannot safely parse input. */
internal fun plainTextToFallbackHtml(text: String): String =
    text.lineSequence()
        .joinToString(separator = "") { line ->
            if (line.isEmpty()) "<br>" else "<p>${escapeHtmlText(line)}</p>"
        }

private fun String.normalizeMarkdownInput(): String =
    replace("\r\n", "\n").replace('\r', '\n')

private const val TAG = "OptimalX.MarkdownRichText"

internal fun escapeHtmlText(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun TextStyle.lineSpacingMultiplier(): Float {
    if (fontSize == TextUnit.Unspecified || lineHeight == TextUnit.Unspecified) return 1.35f
    return lineHeight.value / fontSize.value
}

private class ChatLinkMovementMethod(
    private val onLinkClick: ((String) -> Boolean)?,
) : LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: android.text.Spannable, event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) {
            return super.onTouchEvent(widget, buffer, event)
        }
        val x = (event.x - widget.totalPaddingLeft + widget.scrollX).toInt()
        val y = (event.y - widget.totalPaddingTop + widget.scrollY).toInt()
        val layout = widget.layout ?: return super.onTouchEvent(widget, buffer, event)
        val line = layout.getLineForVertical(y)
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        val spans = buffer.getSpans(offset, offset, URLSpan::class.java)
        if (spans.isNotEmpty()) {
            val href = spans[0].url.orEmpty()
            if (href.startsWith("optimalx://")) {
                if (onLinkClick?.invoke(href) == true) return true
            }
            if (href.startsWith("http://") || href.startsWith("https://")) {
                runCatching {
                    widget.context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(href)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
                return true
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }
}
