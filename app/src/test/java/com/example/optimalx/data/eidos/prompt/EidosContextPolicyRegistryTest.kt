package com.example.optimalx.data.eidos.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosContextPolicyRegistryTest {

    @Test
    fun mainChatProfiles_injectDailyMemory() {
        listOf(
            EidosScopeProfileIds.GENERAL_APP,
            EidosScopeProfileIds.PARENT,
            EidosScopeProfileIds.SUBFOLDER,
        ).forEach { profileId ->
            val profile = EidosScopeProfileRegistry.require(profileId)
            assertTrue("$profileId should inject daily memory", profile.contextPolicy.injectDailyMemory)
            assertTrue("$profileId should have prefetch policy enabled", profile.contextPolicy.prefetchPolicy.profileEnabled)
        }
    }

    @Test
    fun workshopProfiles_doNotInjectDailyMemory() {
        listOf(
            EidosScopeProfileIds.WORKSHOP_CHAT,
            EidosScopeProfileIds.WORKSHOP_PLAN,
            EidosScopeProfileIds.WORKSHOP_EDIT,
            EidosScopeProfileIds.WORKSHOP_INTAKE,
        ).forEach { profileId ->
            val profile = EidosScopeProfileRegistry.require(profileId)
            assertFalse("$profileId should not inject daily memory", profile.contextPolicy.injectDailyMemory)
        }
    }

    @Test
    fun widgetProfiles_havePrefetchEnabled() {
        listOf(
            EidosScopeProfileIds.WIDGET_ASK,
            EidosScopeProfileIds.WIDGET_CHAT,
        ).forEach { profileId ->
            val profile = EidosScopeProfileRegistry.require(profileId)
            assertFalse("$profileId should not blunt-inject daily memory", profile.contextPolicy.injectDailyMemory)
            assertTrue("$profileId should prefetch", profile.contextPolicy.prefetchPolicy.profileEnabled)
        }
    }

    @Test
    fun workshopProfiles_havePrefetchEnabled() {
        listOf(
            EidosScopeProfileIds.WORKSHOP_CHAT,
            EidosScopeProfileIds.WORKSHOP_PLAN,
            EidosScopeProfileIds.WORKSHOP_EDIT,
            EidosScopeProfileIds.WORKSHOP_INTAKE,
        ).forEach { profileId ->
            val profile = EidosScopeProfileRegistry.require(profileId)
            assertTrue("$profileId should prefetch", profile.contextPolicy.prefetchPolicy.profileEnabled)
        }
    }
}
