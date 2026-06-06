package com.example.optimalx.data.eidos

/**
 * Layout contract for Panel Workshop HTML/CSS/JS running inside an Android WebView.
 * Referenced from DESIGN.md seeds and code scaffolds.
 *
 * Full platform contract (bundling, bridge, Eidos): [PanelPlatformSpec].
 */
object WorkshopAndroidLayoutRules {

    val MARKDOWN_SECTION: String = """
        ## Android WebView layout (required)

        Panels run on an Android phone inside OptimalX (touch, narrow viewport). Design to fit the screen — not a desktop browser.

        ### HTML rules
        - `viewport` meta must include: `width=device-width`, `initial-scale=1.0`, `user-scalable=no`
        - Set the canvas **internal resolution** with `width` and `height` **attributes** on `<canvas>` (pixel dimensions)
        - Put **controls below the canvas** in the DOM, never inside the canvas wrapper

        ### CSS layout rules
        - `body`: `min-height: 100vh`, flex column, centered — do **not** use `100dvh`, `position: fixed`, or `safe-area-inset` for main layout unless the user asks
        - Game/panel shell (e.g. `.game-shell`): flex column, `max-width` matching the logical canvas width, `padding: 12px`, `gap: 10px`
        - `canvas`: `width: 100%`, `height: auto`, `aspect-ratio: W / H` where **W** and **H** match the canvas attribute dimensions — never set a fixed pixel `width`/`height` on the canvas in CSS
        - Controls: plain flex row or grid **below** the canvas; large touch targets (`min-height: 56px`, adequate horizontal padding); `touch-action: manipulation`
        - No overlapping elements for layout, no z-index tricks, no `position: absolute` for the main structure

        ### Visual style (project-specific)
        - Colors, typography, and game art direction — document here and mirror in `style.css`
        """.trimIndent()

    val EIDOS_CONTEXT_SUMMARY: String = """
        Android WebView layout: phone touch UI. HTML viewport width=device-width, initial-scale=1.0, user-scalable=no. Canvas pixel size via width/height attributes; CSS uses width:100%, height:auto, aspect-ratio W/H (match attributes). Controls below canvas, min ~56px touch targets, touch-action:manipulation. body: min-height:100vh flex column center — avoid 100dvh, fixed positioning, safe-area-inset for layout. No overlap/z-index/absolute main layout. Full rules in DESIGN.md.
        """.trimIndent()

    val HTML_FILE_HEADER_COMMENT: String = """
        OptimalX panel — runs in an Android WebView (touch, phone-sized viewport).
        HTML: viewport meta per DESIGN.md; canvas pixel size via width/height attributes; controls after canvas in DOM.
        """.trimIndent()

    val CSS_FILE_HEADER_COMMENT: String = """
        OptimalX panel styles — Android WebView layout (see DESIGN.md).
        body: min-height 100vh, flex column, centered. Canvas: width 100%, height auto, aspect-ratio from canvas attrs.
        Controls below canvas; min 56px touch targets; touch-action manipulation. No fixed canvas px size in CSS.
        """.trimIndent()
}
