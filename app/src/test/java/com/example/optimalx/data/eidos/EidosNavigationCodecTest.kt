package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosNavigationCodecTest {

    @Test
    fun parseOptimalxUri_roundTripsCommonKinds() {
        val note = EidosNavigationCodec.parseOptimalxUri("optimalx://note/42")
        assertNotNull(note)
        assertEquals("note", note?.kind)
        assertEquals(42L, note?.subfolderId)

        val parent = EidosNavigationCodec.parseOptimalxUri("optimalx://parent/7")
        assertEquals("parent", parent?.kind)
        assertEquals(7L, parent?.parentFolderId)

        val workshopFile = EidosNavigationCodec.parseOptimalxUri("optimalx://workshop/5/index.html")
        assertEquals("workshop_file", workshopFile?.kind)
        assertEquals(5L, workshopFile?.subfolderId)
        assertEquals("index.html", workshopFile?.path)

        assertNull(EidosNavigationCodec.parseOptimalxUri("https://example.com"))
    }

    @Test
    fun formatOptimalxUri_roundTripsWithParse() {
        val note = EidosNavigationTarget(kind = "note", label = "Trail", subfolderId = 42L)
        assertEquals("optimalx://note/42", EidosNavigationCodec.formatOptimalxUri(note))

        val workshopFile = EidosNavigationTarget(
            kind = "workshop_file",
            label = "index.html",
            subfolderId = 5L,
            path = "src/index.html",
        )
        val uri = EidosNavigationCodec.formatOptimalxUri(workshopFile)
        assertEquals("optimalx://workshop/5/src/index.html", uri)
        val parsed = EidosNavigationCodec.parseOptimalxUri(uri)
        assertEquals("workshop_file", parsed?.kind)
        assertEquals(5L, parsed?.subfolderId)
        assertEquals("src/index.html", parsed?.path)
    }

    @Test
    fun appendNavigationMarkdownLinks_addsWhenMissing() {
        val targets = listOf(
            EidosNavigationTarget(kind = "note", label = "Trail map", subfolderId = 3L),
        )
        val out = EidosNavigationCodec.appendNavigationMarkdownLinks("Saved.", targets)
        assertTrue(out.contains("[Trail map](optimalx://note/3)"))
        assertTrue(out.startsWith("Saved."))
    }

    @Test
    fun appendNavigationMarkdownLinks_skipsWhenAlreadyPresent() {
        val targets = listOf(
            EidosNavigationTarget(kind = "note", label = "Trail map", subfolderId = 3L),
        )
        val original = "See [Trail map](optimalx://note/3)."
        assertEquals(original, EidosNavigationCodec.appendNavigationMarkdownLinks(original, targets))
    }

    @Test
    fun serializeAndParseTargets_dedupesByKey() {
        val targets = listOf(
            EidosNavigationTarget(
                kind = "note",
                label = "Trail map",
                subfolderId = 3L,
                parentFolderId = 1L,
            ),
            EidosNavigationTarget(
                kind = "note",
                label = "Trail map again",
                subfolderId = 3L,
                parentFolderId = 1L,
            ),
            EidosNavigationTarget(
                kind = "workshop_file",
                label = "index.html",
                subfolderId = 5L,
                path = "index.html",
            ),
        )
        val json = EidosNavigationCodec.serializeTargets(targets)
        assertNotNull(json)
        val parsed = EidosNavigationCodec.parseTargetsJson(json)
        assertEquals(2, parsed.size)
        assertEquals(3L, parsed[0].subfolderId)
        assertEquals("index.html", parsed[1].path)
    }

    @Test
    fun navigationActionLabel_formatsKinds() {
        assertTrue(
            EidosNavigationCodec.navigationActionLabel(
                EidosNavigationTarget(kind = "dump_edit", label = "DumpEdit"),
            ).contains("DumpEdit"),
        )
        assertEquals(
            "Open file · index.html",
            EidosNavigationCodec.navigationActionLabel(
                EidosNavigationTarget(kind = "workshop_file", label = "index.html", subfolderId = 1L),
            ),
        )
    }
}
