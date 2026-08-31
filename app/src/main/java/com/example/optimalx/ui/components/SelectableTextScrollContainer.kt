package com.example.optimalx.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import kotlin.math.min

/**
 * [TextView] inside a vertical [NestedScrollView] so text selection can auto-scroll
 * when the user drags selection handles past the visible area. Required for long chat
 * messages embedded in [androidx.compose.foundation.lazy.LazyColumn], where the list
 * does not scroll during an active selection.
 */
internal class SelectableTextScrollContainer(context: Context) : NestedScrollView(context) {

    val body: TextView = TextView(context).apply {
        setTextIsSelectable(true)
        setBackgroundColor(AndroidColor.TRANSPARENT)
        includeFontPadding = true
        isNestedScrollingEnabled = false
    }

    init {
        isFillViewport = false
        isNestedScrollingEnabled = true
        clipToPadding = true
        clipChildren = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        isVerticalScrollBarEnabled = true
        scrollBarStyle = SCROLLBARS_INSIDE_INSET
        addView(
            body,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        body.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val contentHeight = body.measuredHeight + paddingTop + paddingBottom
        val maxHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> contentHeight
            else -> MeasureSpec.getSize(heightMeasureSpec)
        }
        val resolvedHeight = min(contentHeight, maxHeight)
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(resolvedHeight, MeasureSpec.EXACTLY),
        )
    }
}
