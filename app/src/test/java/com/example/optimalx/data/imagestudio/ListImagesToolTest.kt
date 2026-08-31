package com.example.optimalx.data.imagestudio

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ListImagesToolTest {

    @Test
    fun resolve_defaultsHubToAllScope() {
        val resolved = ListImagesTool.resolveListImagesRequest(
            buildJsonObject {
                put("currentScopeType", JsonPrimitive("image_studio"))
                put("imageStudioHub", JsonPrimitive(true))
                put("currentSubfolderId", JsonPrimitive(7))
            },
        )
        assertNotNull(resolved)
        assertEquals("all", resolved!!.scope)
        assertNull(resolved.subfolderId)
        assertEquals(48, resolved.limit)
    }

    @Test
    fun resolve_defaultsSubfolderTab() {
        val resolved = ListImagesTool.resolveListImagesRequest(
            buildJsonObject {
                put("currentScopeType", JsonPrimitive("image_studio"))
                put("imageStudioHub", JsonPrimitive(false))
                put("currentSubfolderId", JsonPrimitive(9))
            },
        )
        assertNotNull(resolved)
        assertEquals("subfolder", resolved!!.scope)
        assertEquals(9L, resolved.subfolderId)
    }

    @Test
    fun resolve_requiresSubfolderIdWhenScopedToSubfolder() {
        val resolved = ListImagesTool.resolveListImagesRequest(
            buildJsonObject {
                put("scope", JsonPrimitive("subfolder"))
            },
        )
        assertNull(resolved)
    }
}
