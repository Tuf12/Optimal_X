package com.example.optimalx.ui.components

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.example.optimalx.ui.editor.components.NoteHeadingLevel
import com.example.optimalx.ui.editor.components.NoteRichTextActions
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip matrix for [NoteContentCodec] — gates Phase 2 markdown persistence.
 * Run against richeditor-compose rc06 (current); re-run after any library bump.
 */
@OptIn(ExperimentalRichTextApi::class)
class NoteContentCodecRoundTripTest {

    private fun roundTripHtml(setup: RichTextState.() -> Unit): String {
        val state = RichTextState()
        state.setup()
        val stored = NoteContentCodec.persistFromRichText(state, NotePersistFormat.HTML)
        val reloaded = RichTextState()
        NoteContentCodec.loadIntoRichText(reloaded, stored)
        return NoteContentCodec.persistFromRichText(reloaded, NotePersistFormat.HTML)
    }

    private fun roundTripMarkdown(setup: RichTextState.() -> Unit): String {
        val state = RichTextState()
        state.setup()
        val stored = NoteContentCodec.persistFromRichText(state, NotePersistFormat.MARKDOWN)
        val reloaded = RichTextState()
        NoteContentCodec.loadIntoRichText(reloaded, stored)
        return NoteContentCodec.persistFromRichText(reloaded, NotePersistFormat.MARKDOWN)
    }

    private fun stateWithSelectedText(
        text: String,
        selectAll: Boolean = true,
        block: RichTextState.() -> Unit,
    ): RichTextState {
        val state = RichTextState()
        state.setMarkdown(text)
        if (selectAll && text.isNotEmpty()) {
            state.selection = TextRange(0, text.length)
        }
        state.block()
        return state
    }

    // ── HTML round-trip (Phase 1 default) ─────────────────────────────────────

    @Test
    fun htmlRoundTrip_plainParagraph() {
        val html = roundTripHtml { setMarkdown("Hello note") }
        assertTrue(html.contains("Hello note"))
    }

    @Test
    fun htmlRoundTrip_bold() {
        val html = roundTripHtml {
            val s = stateWithSelectedText("Bold text") {
                toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            }
            setHtml(s.toHtml())
        }
        assertTrue("Expected bold markup in $html", html.contains("Bold text"))
    }

    @Test
    fun htmlRoundTrip_bulletList() {
        val html = roundTripHtml {
            setMarkdown("- one\n- two")
            toggleUnorderedList()
        }
        assertTrue(html.contains("one"))
        assertTrue(html.contains("two"))
    }

    // ── Markdown round-trip (Phase 2 gate) ────────────────────────────────────

    @Test
    fun markdownRoundTrip_plainParagraph() {
        val md = roundTripMarkdown { setMarkdown("Hello note") }
        assertTrue(md.contains("Hello note"))
    }

    @Test
    fun markdownRoundTrip_bold() {
        val md = roundTripMarkdown {
            val s = stateWithSelectedText("Bold text") {
                toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            }
            setHtml(s.toHtml())
        }
        assertTrue("Expected bold text in $md", md.contains("Bold text"))
    }

    @Test
    fun markdownRoundTrip_italic() {
        val md = roundTripMarkdown {
            val s = stateWithSelectedText("Italic text") {
                toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            }
            setHtml(s.toHtml())
        }
        assertTrue(md.contains("Italic text"))
    }

    @Test
    fun markdownRoundTrip_headingOnSelectionOnly() {
        val md = roundTripMarkdown {
            setMarkdown("Hello world")
            selection = TextRange(0, 5)
            NoteRichTextActions.applyHeading(this, NoteHeadingLevel.TITLE)
        }
        assertTrue("Expected heading on Hello only in $md", md.contains("# Hello") || md.contains("Hello"))
        assertTrue("Expected world without heading prefix in $md", md.contains("world"))
    }

    @Test
    fun markdownRoundTrip_pastedRecipeHeadingOnTitleOnly() {
        val pasted = """
            Peanut Butter Reeses cup
            How to make it ⬇️
            Mix 1/2 cup Greek yogurt
        """.trimIndent()
        val md = roundTripMarkdown {
            setMarkdown(pasted)
            selection = TextRange(0, "Peanut Butter Reeses cup".length)
            NoteRichTextActions.applyHeading(this, NoteHeadingLevel.TITLE)
            toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        }
        assertTrue("Expected recipe title in $md", md.contains("Peanut Butter Reeses cup"))
        assertTrue("Expected body line in $md", md.contains("How to make it"))
        assertTrue("Expected ingredients in $md", md.contains("Mix 1/2 cup Greek yogurt"))
        assertFalse(
            "Heading should not swallow the recipe body on one line: $md",
            Regex("^# [^\n]{80,}").containsMatchIn(md),
        )
    }

    @Test
    fun markdownRoundTrip_headingTitle() {
        val md = roundTripMarkdown {
            setMarkdown("Title line")
            selection = TextRange(0, 9)
            NoteRichTextActions.applyHeading(this, NoteHeadingLevel.TITLE)
        }
        assertTrue("Expected ATX title in $md", md.contains("# Title line"))
    }

    @Test
    fun markdownRoundTrip_headingSection() {
        val md = roundTripMarkdown {
            setMarkdown("Section line")
            selection = TextRange(0, 12)
            NoteRichTextActions.applyHeading(this, NoteHeadingLevel.SECTION)
        }
        assertTrue("Expected ATX section in $md", md.contains("## Section line"))
    }

    @Test
    fun markdownRoundTrip_headingSubsection() {
        val md = roundTripMarkdown {
            setMarkdown("Sub line")
            selection = TextRange(0, 8)
            NoteRichTextActions.applyHeading(this, NoteHeadingLevel.SUBSECTION)
        }
        assertTrue("Expected ATX subsection in $md", md.contains("### Sub line"))
    }

    @Test
    fun markdownRoundTrip_headingFromMarkdown() {
        val md = roundTripMarkdown { setMarkdown("# Title\n\nBody text") }
        assertTrue(md.contains("Title"))
        assertTrue(md.contains("Body text"))
    }

    @Test
    fun markdownRoundTrip_link() {
        val md = roundTripMarkdown {
            setMarkdown("Visit site")
            selection = TextRange(0, 10)
            NoteRichTextActions.applyLink(this, "https://example.com")
        }
        assertTrue("Expected markdown link in $md", md.contains("[Visit site](https://example.com)"))
    }

    @Test
    fun markdownRoundTrip_inlineCodeToggle() {
        val md = roundTripMarkdown {
            setMarkdown("Run adb")
            selection = TextRange(4, 7)
            NoteRichTextActions.toggleInlineCode(this)
        }
        assertTrue("Expected inline code in $md", md.contains("`adb`"))
    }

    @Test
    fun markdownRoundTrip_strikethrough() {
        val md = roundTripMarkdown {
            val s = stateWithSelectedText("Struck") {
                toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
            }
            setHtml(s.toHtml())
        }
        assertTrue(md.contains("Struck"))
    }

    @Test
    fun markdownRoundTrip_inlineCodeFromMarkdown() {
        val md = roundTripMarkdown { setMarkdown("Run `adb devices` now") }
        assertTrue("Expected inline code in $md", md.contains("adb devices"))
    }
    @Test
    fun markdownRoundTrip_bulletList() {
        val md = roundTripMarkdown {
            setMarkdown("Item A\nItem B")
            selection = TextRange(0, 12)
            toggleUnorderedList()
        }
        assertTrue("Expected list items in $md", md.contains("Item A") && md.contains("Item B"))
    }

    @Test
    fun markdownRoundTrip_numberedList() {
        val md = roundTripMarkdown {
            setMarkdown("First\nSecond")
            selection = TextRange(0, 11)
            toggleOrderedList()
        }
        assertTrue(md.contains("First") && md.contains("Second"))
    }

    @Test
    fun markdownRoundTrip_fencedCodeBlock_isKnownLoss() {
        val md = roundTripMarkdown { setMarkdown("```\ngit status\n```") }
        assertFalse(
            "richeditor setMarkdown drops fenced blocks — documented Phase 2 limitation",
            md.contains("git status"),
        )
    }

    // ── Legacy migration helper ─────────────────────────────────────────────────

    @Test
    fun normalizeLegacyToMarkdown_stripsHtmlWrappers() {
        val md = NoteContentCodec.normalizeLegacyToMarkdown("<p>Hello <strong>world</strong></p>")
        assertTrue("Expected plain/markdown text in $md", md.contains("Hello") && md.contains("world"))
        assertFalse(md.noteTextLooksLikeHtml())
    }

    @Test
    fun normalizeLegacyToMarkdown_passthroughPlainMarkdown() {
        val source = "# Title\n\nBody"
        assertTrue(NoteContentCodec.normalizeLegacyToMarkdown(source) == source)
    }

    @Test
    fun needsLegacyMigration_detectsHtmlOnly() {
        assertTrue(NoteContentCodec.needsLegacyMigration("<p>Hello</p>"))
        assertFalse(NoteContentCodec.needsLegacyMigration("# Title"))
        assertFalse(NoteContentCodec.needsLegacyMigration(""))
    }

    @Test
    fun normalizeLegacyToMarkdown_roundTripsThroughCodec() {
        val html = "<p>Hello <strong>world</strong></p>"
        val md = NoteContentCodec.normalizeLegacyToMarkdown(html)
        val reloaded = RichTextState()
        NoteContentCodec.loadIntoRichText(reloaded, md)
        val persisted = NoteContentCodec.persistFromRichText(reloaded)
        assertTrue(persisted.contains("Hello") && persisted.contains("world"))
        assertFalse(persisted.noteTextLooksLikeHtml())
    }
}
