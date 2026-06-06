package com.example.optimalx.ui.workshop

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PanelHtmlComposerReleaseTest {

    @Test
    fun buildCompositeHtmlFromDirectory_inlinesJsAndCss() {
        val dir = File.createTempFile("panel_release", "").apply {
            delete()
            mkdirs()
        }
        try {
            File(dir, "index.html").writeText("<html><head></head><body><p>Run</p></body></html>")
            File(dir, "style.css").writeText("body { color: red; }")
            File(dir, "bridge.js").writeText("window.bridge = 1;")
            File(dir, "script.js").writeText("window.app = 1;")

            val html = PanelHtmlComposer.buildCompositeHtmlFromDirectory(dir)
            assertNotNull(html)
            assertTrue(html!!.contains("Run"))
            assertTrue(html.contains("color: red"))
            assertTrue(html.contains("window.bridge = 1"))
            assertTrue(html.contains("window.app = 1"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
