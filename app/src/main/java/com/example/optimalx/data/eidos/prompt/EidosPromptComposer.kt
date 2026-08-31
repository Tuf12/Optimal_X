package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.dumpedit.DumpEditContext
import com.example.optimalx.data.eidos.DailyMemoryContext
import com.example.optimalx.data.eidos.DailyMemoryPromptResolver
import com.example.optimalx.data.eidos.EidosContextLimits
import com.example.optimalx.data.eidos.PanelBridgeContext
import com.example.optimalx.data.eidos.NotePromptContext
import com.example.optimalx.data.eidos.prefetch.EidosPrefetchMetrics
import com.example.optimalx.data.eidos.prefetch.EidosPrefetchResult
import com.example.optimalx.data.eidos.prefetch.EidosPrefetchService
import com.example.optimalx.data.eidos.prefetch.EidosRetrievalQuery
import com.example.optimalx.data.eidos.ImageStudioContext
import com.example.optimalx.data.eidos.PanelGalleryContext
import com.example.optimalx.data.eidos.PanelPlatformSpec
import com.example.optimalx.data.eidos.PanelRunnerContext
import com.example.optimalx.data.eidos.ParentFolderContext
import com.example.optimalx.data.eidos.QuickNotesContext
import com.example.optimalx.data.eidos.SubfolderContext
import com.example.optimalx.data.eidos.WorkshopDocAlignScope
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopPanelContext
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.model.ConversationScopes
import com.example.optimalx.data.preferences.FolderListDisplayPreferences
import com.example.optimalx.data.preferences.FolderListScope
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.semantic.ContentSectionRetriever
import kotlinx.coroutines.flow.first

/**
 * Composes system prompts from scope profiles.
 *
 * Migrated profiles use registry ontology + location + profile rule blocks.
 * All registry profiles compose here. Legacy [assembleSystemPrompt] is fallback only for unknown profile ids.
 */
object EidosPromptComposer {

    suspend fun compose(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt = when (resolved.profileId) {
        EidosScopeProfileIds.PANEL_GALLERY -> composePanelGallery(resolved, ctx)
        EidosScopeProfileIds.IMAGE_STUDIO -> composeImageStudio(resolved, ctx)
        EidosScopeProfileIds.DUMP_EDIT -> composeDumpEdit(resolved, ctx)
        EidosScopeProfileIds.WIDGET_ASK,
        EidosScopeProfileIds.WIDGET_CHAT,
        -> composeWidgetSurface(resolved, ctx)
        EidosScopeProfileIds.QUICK_NOTES_DAY -> composeQuickNotesDay(resolved, ctx)
        EidosScopeProfileIds.QUICK_NOTES_ROOT -> composeQuickNotesRoot(resolved, ctx)
        EidosScopeProfileIds.GENERAL_APP -> composeGeneralApp(resolved, ctx)
        EidosScopeProfileIds.PARENT -> composeParent(resolved, ctx)
        EidosScopeProfileIds.SUBFOLDER -> composeSubfolder(resolved, ctx)
        EidosScopeProfileIds.WEB_WIDGET -> composeWebWidget(resolved, ctx)
        EidosScopeProfileIds.WEB_EDITOR -> composeWebEditor(resolved, ctx)
        EidosScopeProfileIds.PANEL_RUNNER -> composePanelRunner(resolved, ctx)
        EidosScopeProfileIds.WORKSHOP_CHAT -> composeWorkshopChat(resolved, ctx)
        EidosScopeProfileIds.WORKSHOP_PLAN -> composeWorkshopPlan(resolved, ctx)
        EidosScopeProfileIds.WORKSHOP_EDIT -> composeWorkshopEdit(resolved, ctx)
        EidosScopeProfileIds.WORKSHOP_INTAKE -> composeWorkshopIntake(resolved, ctx)
        EidosScopeProfileIds.INTERNAL_CONTENT_SUMMARY -> composeInternalJob(resolved, ctx)
        EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER -> composeInternalRollover(resolved, ctx)
        else -> composeLegacyPassthrough(
            legacySystemPrompt = ctx.legacyAssembler(),
            resolved = resolved,
        )
    }

    fun composeLegacyPassthrough(
        legacySystemPrompt: String,
        resolved: ResolvedEidosScope,
    ): ComposedPrompt = ComposedPrompt(
        systemPrompt = legacySystemPrompt,
        profileId = resolved.profileId,
        entrySurface = resolved.entrySurface,
    )

    internal fun panelGalleryPromptText(
        profile: EidosScopeProfile,
        projectContext: String,
    ): String = joinSections(
        userFacingPromptSections(profile) + listOf(
            projectContext,
            PanelPlatformSpec.eidosPanelGalleryRules(),
        ),
    )

    internal fun dumpEditPromptText(
        profile: EidosScopeProfile,
        bufferContext: String,
    ): String = joinSections(
        userFacingPromptSections(profile) + listOf(
            bufferContext,
            EidosSystemPromptLayers.DUMP_EDIT_RULES,
        ),
    )

    internal fun widgetSurfacePromptText(
        profile: EidosScopeProfile,
        retrievedContextBlock: String? = null,
    ): String = joinSections(
        buildList {
            addAll(userFacingPromptSections(profile))
            // Widget Chat is a separate general thread (not main General chat) — same folder
            // resolution rules so writes can resolve destinations without a scoped subfolderId.
            if (profile.id == EidosScopeProfileIds.WIDGET_CHAT) {
                add(EidosSystemPromptLayers.GENERAL_SCOPE_RULES)
                add(
                    """
                    Navigation: after you create or update a note/folder, the app shows Open chips automatically.
                    You may also add a markdown link with an optimalx:// URI (e.g. [Meeting notes](optimalx://note/42)).
                    """.trimIndent(),
                )
            }
            retrievedContextBlock?.let { add(it) }
        },
    )

    internal fun quickNotesDayPromptText(
        profile: EidosScopeProfile,
        dayContext: String,
        editorSurfaceHint: String?,
    ): String = joinSections(
        buildList {
            addAll(userFacingPromptSections(profile))
            add(dayContext)
            add(EidosSystemPromptLayers.QUICK_NOTES_DAY_RULES)
            editorSurfaceHint?.trim()?.takeIf { it.isNotEmpty() }?.let { hint ->
                add("Optional UI context (use only when relevant; the user may discuss any topic):\n$hint")
            }
        },
    )

    internal fun quickNotesRootPromptText(
        profile: EidosScopeProfile,
        rootContext: String,
    ): String = joinSections(
        userFacingPromptSections(profile) + listOf(
            rootContext,
            EidosSystemPromptLayers.QUICK_NOTES_ROOT_RULES,
        ),
    )

    internal fun generalAppPromptText(
        profile: EidosScopeProfile,
        webPanelPageUrl: String?,
        dailyMemoryBlock: String? = null,
        retrievedContextBlock: String? = null,
    ): String = joinSections(
        buildList {
            addAll(userFacingPromptSections(profile))
            retrievedContextBlock?.let { add(it) }
            dailyMemoryBlock?.let { add(it) }
            add(EidosSystemPromptLayers.GENERAL_SCOPE_RULES)
            EidosSystemPromptLayers.webPanelUrlBlock(webPanelPageUrl.orEmpty())?.let { add(it) }
        },
    )

    internal fun parentPromptText(
        profile: EidosScopeProfile,
        parentContext: String,
        webPanelPageUrl: String?,
        dailyMemoryBlock: String? = null,
        retrievedContextBlock: String? = null,
    ): String = joinSections(
        buildList {
            addAll(userFacingPromptSections(profile))
            retrievedContextBlock?.let { add(it) }
            dailyMemoryBlock?.let { add(it) }
            add(parentContext)
            add(EidosSystemPromptLayers.PARENT_SCOPE_RULES)
            EidosSystemPromptLayers.webPanelUrlBlock(webPanelPageUrl.orEmpty())?.let { add(it) }
        },
    )

    internal fun subfolderPromptText(
        profile: EidosScopeProfile,
        subfolderContext: String,
        panelBridgeBlock: String?,
        activeParentLine: String,
        editorSurfaceHint: String?,
        webPanelPageUrl: String?,
        dailyMemoryBlock: String? = null,
        retrievedContextBlock: String? = null,
    ): String = joinSections(
        buildList {
            addAll(userFacingPromptSections(profile))
            retrievedContextBlock?.let { add(it) }
            dailyMemoryBlock?.let { add(it) }
            add(subfolderContext)
            add(EidosSystemPromptLayers.NOTE_WRITE_RULES)
            panelBridgeBlock?.let { add(it) }
            add(activeParentLine)
            editorSurfaceHint?.trim()?.takeIf { it.isNotEmpty() }?.let { hint ->
                add("Optional UI context (use only when relevant; the user may discuss any topic):\n$hint")
            }
            EidosSystemPromptLayers.webPanelUrlBlock(webPanelPageUrl.orEmpty())?.let { add(it) }
        },
    )

    internal fun webWidgetPromptText(
        profile: EidosScopeProfile,
        webPanelPageUrl: String?,
    ): String = joinSections(
        buildList {
            addAll(userFacingPromptSections(profile))
            EidosSystemPromptLayers.webPanelUrlBlock(webPanelPageUrl.orEmpty())?.let { add(it) }
            add(EidosSystemPromptLayers.webScopeRulesBlock())
        },
    )

    internal fun webEditorPromptText(
        profile: EidosScopeProfile,
        subfolderContext: String,
        webPanelPageUrl: String?,
        panelBridgeBlock: String?,
        activeParentLine: String,
    ): String = joinSections(
        buildList {
            addAll(userFacingPromptSections(profile))
            EidosSystemPromptLayers.webPanelUrlBlock(webPanelPageUrl.orEmpty())?.let { add(it) }
            add(EidosSystemPromptLayers.webScopeRulesBlock())
            add(subfolderContext)
            add(EidosSystemPromptLayers.NOTE_WRITE_RULES)
            panelBridgeBlock?.let { add(it) }
            add(activeParentLine)
        },
    )

    internal fun panelRunnerPromptText(
        profile: EidosScopeProfile,
        runnerContext: String,
        panelBridgeBlock: String,
        activeParentLine: String,
        editorSurfaceHint: String?,
        webPanelPageUrl: String?,
    ): String = joinSections(
        buildList {
            addAll(userFacingPromptSections(profile))
            add(runnerContext)
            add(panelBridgeBlock)
            add(activeParentLine)
            editorSurfaceHint?.trim()?.takeIf { it.isNotEmpty() }?.let { hint ->
                add("Optional UI context (use only when relevant; the user may discuss any topic):\n$hint")
            }
            EidosSystemPromptLayers.webPanelUrlBlock(webPanelPageUrl.orEmpty())?.let { add(it) }
        },
    )

    internal fun workshopModePromptText(
        profile: EidosScopeProfile,
        workshopContext: String,
        activeParentLine: String,
        editorSurfaceHint: String?,
        webPanelPageUrl: String?,
        panelBridgeBlock: String? = null,
        retrievedContextBlock: String? = null,
    ): String = joinSections(
        buildList {
            addAll(userFacingPromptSections(profile))
            retrievedContextBlock?.let { add(it) }
            add(workshopContext)
            panelBridgeBlock?.let { add(it) }
            add(activeParentLine)
            editorSurfaceHint?.trim()?.takeIf { it.isNotEmpty() }?.let { hint ->
                add("Optional UI context (use only when relevant; the user may discuss any topic):\n$hint")
            }
            EidosSystemPromptLayers.webPanelUrlBlock(webPanelPageUrl.orEmpty())?.let { add(it) }
        },
    )

    internal fun workshopChatPromptText(
        profile: EidosScopeProfile,
        workshopContext: String,
        activeParentLine: String,
        editorSurfaceHint: String?,
        webPanelPageUrl: String?,
        retrievedContextBlock: String? = null,
    ): String = workshopModePromptText(
        profile = profile,
        workshopContext = workshopContext,
        activeParentLine = activeParentLine,
        editorSurfaceHint = editorSurfaceHint,
        webPanelPageUrl = webPanelPageUrl,
        retrievedContextBlock = retrievedContextBlock,
    )

    internal fun userFacingPromptSections(profile: EidosScopeProfile): List<String> = buildList {
        add(EidosIdentityPrompt.TEXT)
        add(EidosIdentityPrompt.APP_MODEL)
        add(profile.ontologyBlock)
        if (profile.locationBlock.isNotBlank()) add(profile.locationBlock)
        if (profile.contextPolicy.prefetchPolicy.profileEnabled) {
            add(EidosContextLimits.PREFETCH_RETRIEVAL_RULES)
        }
    }

    private suspend fun composePanelGallery(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.PANEL_GALLERY)
        val projectContext = PanelGalleryContext.buildProjectContext(ctx.androidContext, ctx.database)
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        return composedWithStableVolatileSplit(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = panelGalleryPromptText(profile, projectContext),
        )
    }

    private suspend fun composeImageStudio(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.IMAGE_STUDIO)
        val hub = ctx.imageStudioHub == true
        val saveSubfolderId = ctx.imageStudioSaveSubfolderId ?: ctx.currentSubfolderId
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val studioContext = ImageStudioContext.buildVolatileContext(
            hub = hub,
            saveSubfolderId = saveSubfolderId,
            activePreviewFileName = ctx.imageStudioActivePreviewFileName,
        )
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        return composedWithStableVolatileSplit(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = imageStudioPromptText(profile, studioContext),
        )
    }

    internal fun imageStudioPromptText(
        profile: EidosScopeProfile,
        studioContext: String,
    ): String = joinSections(
        userFacingPromptSections(profile) + listOf(studioContext),
    )

    private suspend fun composeDumpEdit(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.DUMP_EDIT)
        val app = ctx.androidContext.applicationContext as OptimalXApplication
        val bufferContext = DumpEditContext.buildVolatileContext(
            context = ctx.androidContext,
            userMessage = ctx.dumpEditUserMessage,
            contentSectionRetriever = ContentSectionRetriever(app.embeddingEngine),
        )
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        return composedWithStableVolatileSplit(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = dumpEditPromptText(profile, bufferContext),
        )
    }

    private suspend fun composeWidgetSurface(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(resolved.profileId)
        val prefetchResult = resolvePrefetchResult(profile, resolved.profileId, ctx)
        val retrievedBlock = prefetchResult.block
        val prefetchMetrics = prefetchResult.metrics
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        val systemPrompt = widgetSurfacePromptText(
            profile = profile,
            retrievedContextBlock = retrievedBlock,
        )
        return buildUserFacingComposedPrompt(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = systemPrompt,
            retrievedBlock = retrievedBlock,
            prefetchMetrics = prefetchMetrics,
            volatileCharCount = systemPrompt.length - stablePrefix.length - (retrievedBlock?.length ?: 0),
        )
    }

    private suspend fun composeQuickNotesDay(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.QUICK_NOTES_DAY)
        val subfolderId = ctx.currentSubfolderId
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val dayContext = QuickNotesContext.buildDayVolatileContext(ctx.database, subfolderId)
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        return composedWithStableVolatileSplit(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = quickNotesDayPromptText(
                profile = profile,
                dayContext = dayContext,
                editorSurfaceHint = ctx.subfolderEditorSurfaceHint,
            ),
        )
    }

    private suspend fun composeQuickNotesRoot(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.QUICK_NOTES_ROOT)
        val parentFolderId = ctx.currentParentFolderId
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val rootContext = QuickNotesContext.buildRootVolatileContext(ctx.database, parentFolderId)
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        return composedWithStableVolatileSplit(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = quickNotesRootPromptText(profile, rootContext),
        )
    }

    private suspend fun composeGeneralApp(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.GENERAL_APP)
        val prefetchResult = resolvePrefetchResult(profile, resolved.profileId, ctx)
        val dailyMemoryBlock = dailyMemoryBlockForProfile(profile, ctx, prefetchResult)
        val retrievedBlock = prefetchResult.block
        val prefetchMetrics = prefetchResult.metrics
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        val systemPrompt = generalAppPromptText(
            profile = profile,
            webPanelPageUrl = ctx.webPanelPageUrl,
            dailyMemoryBlock = dailyMemoryBlock,
            retrievedContextBlock = retrievedBlock,
        )
        return buildUserFacingComposedPrompt(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = systemPrompt,
            retrievedBlock = retrievedBlock,
            prefetchMetrics = prefetchMetrics,
            volatileCharCount = systemPrompt.length - stablePrefix.length - (retrievedBlock?.length ?: 0),
        )
    }

    private suspend fun composeParent(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.PARENT)
        val parentFolderId = ctx.currentParentFolderId
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val sortOrder = FolderListDisplayPreferences
            .sortOrderFlow(ctx.androidContext, FolderListScope.Subfolder(parentFolderId))
            .first()
        val parentContext = ParentFolderContext.buildVolatileContext(
            database = ctx.database,
            parentFolderId = parentFolderId,
            sortOrder = sortOrder,
        )
        val prefetchResult = resolvePrefetchResult(profile, resolved.profileId, ctx)
        val dailyMemoryBlock = dailyMemoryBlockForProfile(profile, ctx, prefetchResult)
        val retrievedBlock = prefetchResult.block
        val prefetchMetrics = prefetchResult.metrics
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        val systemPrompt = parentPromptText(
            profile = profile,
            parentContext = parentContext,
            webPanelPageUrl = ctx.webPanelPageUrl,
            dailyMemoryBlock = dailyMemoryBlock,
            retrievedContextBlock = retrievedBlock,
        )
        return buildUserFacingComposedPrompt(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = systemPrompt,
            retrievedBlock = retrievedBlock,
            prefetchMetrics = prefetchMetrics,
            volatileCharCount = systemPrompt.length - stablePrefix.length - (retrievedBlock?.length ?: 0),
        )
    }

    private suspend fun composeSubfolder(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.SUBFOLDER)
        val subfolderId = ctx.currentSubfolderId
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val subfolder = ctx.database.subfolderDao().getById(subfolderId)
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val subfolderContext = SubfolderContext.buildVolatileContext(ctx.database, subfolder)
        val panelBridgeBlock = PanelBridgeContext.buildPromptBlock(
            registry = ctx.panelBridgeRegistry,
            currentSubfolderId = subfolderId,
            currentScopeType = ConversationScopes.SUBFOLDER,
        )
        val prefetchResult = resolvePrefetchResult(profile, resolved.profileId, ctx)
        val dailyMemoryBlock = dailyMemoryBlockForProfile(profile, ctx, prefetchResult)
        val activeParentLine = ParentFolderContext.resolveActiveParentLine(
            ctx.database,
            subfolder.parentFolderId,
        )
        val retrievedBlock = prefetchResult?.block
        val prefetchMetrics = prefetchResult?.metrics
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        val systemPrompt = subfolderPromptText(
            profile = profile,
            subfolderContext = subfolderContext,
            panelBridgeBlock = panelBridgeBlock,
            activeParentLine = activeParentLine,
            editorSurfaceHint = ctx.subfolderEditorSurfaceHint,
            webPanelPageUrl = ctx.webPanelPageUrl,
            dailyMemoryBlock = dailyMemoryBlock,
            retrievedContextBlock = retrievedBlock,
        )
        return buildUserFacingComposedPrompt(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = systemPrompt,
            retrievedBlock = retrievedBlock,
            prefetchMetrics = prefetchMetrics,
            volatileCharCount = systemPrompt.length - stablePrefix.length - (retrievedBlock?.length ?: 0),
        )
    }

    private fun composeWebWidget(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WEB_WIDGET)
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        return composedWithStableVolatileSplit(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = webWidgetPromptText(
                profile = profile,
                webPanelPageUrl = ctx.webPanelPageUrl,
            ),
        )
    }

    private suspend fun composeWebEditor(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WEB_EDITOR)
        val subfolderId = ctx.currentSubfolderId
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val subfolder = ctx.database.subfolderDao().getById(subfolderId)
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val subfolderContext = SubfolderContext.buildVolatileContext(ctx.database, subfolder)
        val panelBridgeBlock = PanelBridgeContext.buildPromptBlock(
            registry = ctx.panelBridgeRegistry,
            currentSubfolderId = subfolderId,
            currentScopeType = ConversationScopes.WEB_EDITOR,
        )
        val activeParentLine = ParentFolderContext.resolveActiveParentLine(
            ctx.database,
            subfolder.parentFolderId,
        )
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        return composedWithStableVolatileSplit(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = webEditorPromptText(
                profile = profile,
                subfolderContext = subfolderContext,
                webPanelPageUrl = ctx.webPanelPageUrl,
                panelBridgeBlock = panelBridgeBlock,
                activeParentLine = activeParentLine,
            ),
        )
    }

    private suspend fun composePanelRunner(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.PANEL_RUNNER)
        val subfolderId = ctx.currentSubfolderId
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val subfolder = ctx.database.subfolderDao().getById(subfolderId)
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val runnerContext = PanelRunnerContext.buildPromptBlock(
            context = ctx.androidContext,
            database = ctx.database,
            subfolder = subfolder,
        )
        val panelBridgeBlock = PanelBridgeContext.runnerBridgeBlock(
            registry = ctx.panelBridgeRegistry,
            currentSubfolderId = subfolderId,
        )
        val activeParentLine = ParentFolderContext.resolveActiveParentLine(
            ctx.database,
            subfolder.parentFolderId,
        )
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        return composedWithStableVolatileSplit(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = panelRunnerPromptText(
                profile = profile,
                runnerContext = runnerContext,
                panelBridgeBlock = panelBridgeBlock,
                activeParentLine = activeParentLine,
                editorSurfaceHint = ctx.subfolderEditorSurfaceHint,
                webPanelPageUrl = ctx.webPanelPageUrl,
            ),
        )
    }

    private suspend fun composeWorkshopChat(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt = composeWorkshopSurface(
        resolved = resolved,
        ctx = ctx,
        profileId = EidosScopeProfileIds.WORKSHOP_CHAT,
        workshopEidosMode = ctx.workshopEidosMode ?: WorkshopEidosMode.CHAT,
        workshopProjectPhase = ctx.workshopProjectPhase,
        useChatPromptText = true,
    )

    private suspend fun composeWorkshopPlan(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt = composeWorkshopSurface(
        resolved = resolved,
        ctx = ctx,
        profileId = EidosScopeProfileIds.WORKSHOP_PLAN,
        workshopEidosMode = ctx.workshopEidosMode ?: WorkshopEidosMode.PLAN,
        workshopProjectPhase = ctx.workshopProjectPhase,
    )

    private suspend fun composeWorkshopEdit(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val subfolderId = ctx.currentSubfolderId
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val workshopMode = ctx.workshopEidosMode ?: WorkshopEidosMode.EDIT
        val workshopPhase = ctx.workshopProjectPhase
            ?: WorkshopProjectPreferences.getProjectPhase(ctx.androidContext, subfolderId)
        val panelBridgeBlock = PanelBridgeContext.workshopEditBridgeBlock(
            registry = ctx.panelBridgeRegistry,
            currentSubfolderId = subfolderId,
            workshopEidosMode = workshopMode,
            workshopProjectPhase = workshopPhase,
        )
        return composeWorkshopSurface(
            resolved = resolved,
            ctx = ctx,
            profileId = EidosScopeProfileIds.WORKSHOP_EDIT,
            workshopEidosMode = workshopMode,
            workshopProjectPhase = workshopPhase,
            panelBridgeBlock = panelBridgeBlock,
        )
    }

    private suspend fun composeWorkshopIntake(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt = composeWorkshopSurface(
        resolved = resolved,
        ctx = ctx,
        profileId = EidosScopeProfileIds.WORKSHOP_INTAKE,
        workshopEidosMode = WorkshopEidosMode.CHAT,
        workshopProjectPhase = WorkshopProjectPhase.INTAKE,
        workshopDocAlignScope = null,
    )

    private suspend fun composeWorkshopSurface(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
        profileId: String,
        workshopEidosMode: WorkshopEidosMode,
        workshopProjectPhase: WorkshopProjectPhase?,
        workshopDocAlignScope: WorkshopDocAlignScope? = ctx.workshopDocAlignScope,
        panelBridgeBlock: String? = null,
        useChatPromptText: Boolean = false,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(profileId)
        val subfolderId = ctx.currentSubfolderId
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val subfolder = ctx.database.subfolderDao().getById(subfolderId)
            ?: return composeLegacyPassthrough(ctx.legacyAssembler(), resolved)
        val prefetchResult = resolvePrefetchResult(profile, profileId, ctx)
        val slimHeavy = profile.contextPolicy.prefetchPolicy.profileEnabled
        val workshopContext = WorkshopPanelContext.buildVolatileContext(
            context = ctx.androidContext,
            database = ctx.database,
            subfolder = subfolder,
            workshopOpenFileName = ctx.workshopOpenFileName,
            workshopOpenFileContent = ctx.workshopOpenFileContent,
            workshopEidosMode = workshopEidosMode,
            workshopProjectPhase = workshopProjectPhase,
            workshopDocAlignScope = workshopDocAlignScope,
            workshopUpdateSection = ctx.workshopUpdateSection,
            workshopUserTurns = ctx.workshopUserTurns,
            slimHeavyBlocks = slimHeavy,
        )
        val activeParentLine = ParentFolderContext.resolveActiveParentLine(
            ctx.database,
            subfolder.parentFolderId,
        )
        val retrievedBlock = prefetchResult.block
        val prefetchMetrics = prefetchResult.metrics
        val stablePrefix = joinSections(userFacingPromptSections(profile))
        val systemPrompt = if (useChatPromptText) {
            workshopChatPromptText(
                profile = profile,
                workshopContext = workshopContext,
                activeParentLine = activeParentLine,
                editorSurfaceHint = ctx.subfolderEditorSurfaceHint,
                webPanelPageUrl = ctx.webPanelPageUrl,
                retrievedContextBlock = retrievedBlock,
            )
        } else {
            workshopModePromptText(
                profile = profile,
                workshopContext = workshopContext,
                activeParentLine = activeParentLine,
                editorSurfaceHint = ctx.subfolderEditorSurfaceHint,
                webPanelPageUrl = ctx.webPanelPageUrl,
                panelBridgeBlock = panelBridgeBlock,
                retrievedContextBlock = retrievedBlock,
            )
        }
        return buildUserFacingComposedPrompt(
            resolved = resolved,
            stablePrefix = stablePrefix,
            systemPrompt = systemPrompt,
            retrievedBlock = retrievedBlock,
            prefetchMetrics = prefetchMetrics,
            volatileCharCount = systemPrompt.length - stablePrefix.length - (retrievedBlock?.length ?: 0),
        )
    }

    internal fun joinSections(sections: List<String>): String =
        sections.filter { it.isNotBlank() }.joinToString("\n\n")

    /** Volatile tail after [stablePrefix] inside a joined system prompt. */
    internal fun volatileSuffixAfterStablePrefix(systemPrompt: String, stablePrefix: String): String {
        val stable = stablePrefix.trim()
        if (stable.isEmpty()) return ""
        val full = systemPrompt.trim()
        if (full == stable) return ""
        if (!full.startsWith(stable)) return ""
        return full.removePrefix(stable).trim()
    }

    private fun composedWithStableVolatileSplit(
        resolved: ResolvedEidosScope,
        stablePrefix: String,
        systemPrompt: String,
        stablePrefixSha256: String = EidosPromptTrace.sha256(stablePrefix),
        sectionCharCountsJson: String = "{}",
        prefetchMetrics: EidosPrefetchMetrics? = null,
    ): ComposedPrompt = ComposedPrompt(
        systemPrompt = systemPrompt,
        profileId = resolved.profileId,
        entrySurface = resolved.entrySurface,
        stableSystemPrefix = stablePrefix,
        volatileSystemSuffix = volatileSuffixAfterStablePrefix(systemPrompt, stablePrefix),
        stablePrefixSha256 = stablePrefixSha256,
        sectionCharCountsJson = sectionCharCountsJson,
        prefetchMetrics = prefetchMetrics,
    )

    private fun composeInternalJob(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(resolved.profileId)
        val taskBlock = EidosInternalPromptBlocks.taskBlockForProfile(resolved.profileId)
        return buildInternalPrompt(profile, resolved, taskBlock = taskBlock, volatileBlock = null)
    }

    private fun composeInternalRollover(
        resolved: ResolvedEidosScope,
        ctx: EidosPromptComposeContext,
    ): ComposedPrompt {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER)
        val volatileBlock = ctx.internalVolatilePrompt?.trim().orEmpty()
        require(volatileBlock.isNotEmpty()) {
            "internalVolatilePrompt is required for internal.memory_rollover"
        }
        return buildInternalPrompt(profile, resolved, taskBlock = "", volatileBlock = volatileBlock)
    }

    internal fun composeInternalJobPreview(profile: EidosScopeProfile): ComposedPrompt =
        buildInternalPrompt(
            profile = profile,
            resolved = ResolvedEidosScope(profile.id, EidosEntrySurface.INTERNAL),
            taskBlock = EidosInternalPromptBlocks.taskBlockForProfile(profile.id),
            volatileBlock = null,
        )

    internal fun composeInternalRolloverPreview(
        profile: EidosScopeProfile,
        volatileBlock: String,
    ): ComposedPrompt = buildInternalPrompt(
        profile = profile,
        resolved = ResolvedEidosScope(profile.id, EidosEntrySurface.INTERNAL),
        taskBlock = "",
        volatileBlock = volatileBlock,
    )

    private fun buildInternalPrompt(
        profile: EidosScopeProfile,
        resolved: ResolvedEidosScope,
        taskBlock: String,
        volatileBlock: String?,
    ): ComposedPrompt {
        val stablePrefix = joinSections(
            listOfNotNull(
                profile.ontologyBlock.takeIf { it.isNotBlank() },
                taskBlock.takeIf { it.isNotBlank() },
            ),
        )
        val systemPrompt = if (volatileBlock.isNullOrBlank()) {
            stablePrefix
        } else {
            joinSections(listOf(stablePrefix, volatileBlock))
        }
        val sectionCounts = buildMap {
            if (profile.ontologyBlock.isNotBlank()) put("ontology", profile.ontologyBlock.length)
            if (taskBlock.isNotBlank()) put("task", taskBlock.length)
            volatileBlock?.takeIf { it.isNotBlank() }?.let { put("volatile", it.length) }
        }
        return ComposedPrompt(
            systemPrompt = systemPrompt,
            profileId = resolved.profileId,
            entrySurface = resolved.entrySurface,
            stableSystemPrefix = stablePrefix,
            volatileSystemSuffix = volatileBlock.orEmpty(),
            stablePrefixSha256 = EidosPromptTrace.sha256(stablePrefix),
            sectionCharCountsJson = EidosPromptTrace.sectionCharCountsJson(sectionCounts),
        )
    }

    private suspend fun dailyMemoryBlockForProfile(
        profile: EidosScopeProfile,
        ctx: EidosPromptComposeContext,
        prefetchResult: EidosPrefetchResult?,
    ): String? {
        val snapshot = DailyMemoryContext.loadTodaySnapshot(ctx.database)
        return DailyMemoryPromptResolver.resolve(
            injectDailyMemory = profile.contextPolicy.injectDailyMemory,
            snapshot = snapshot,
            prefetchResult = prefetchResult,
        )
    }

    private suspend fun resolvePrefetchResult(
        profile: EidosScopeProfile,
        profileId: String,
        ctx: EidosPromptComposeContext,
    ): EidosPrefetchResult {
        val policy = profile.contextPolicy.prefetchPolicy
        if (!policy.profileEnabled) {
            return EidosPrefetchResult(
                block = null,
                metrics = EidosPrefetchMetrics.skipped("profile_off"),
                serviceRan = false,
            )
        }
        val indexer = ctx.semanticIndexer
            ?: return EidosPrefetchResult(
                block = null,
                metrics = EidosPrefetchMetrics.skipped("no_indexer"),
                serviceRan = false,
            )
        val userMessage = ctx.userMessage?.trim().orEmpty()
        if (!EidosRetrievalQuery.shouldPrefetch(userMessage)) {
            return EidosPrefetchResult(
                block = null,
                metrics = EidosPrefetchMetrics.skipped("greeting_or_short"),
                serviceRan = false,
            )
        }
        val query = EidosRetrievalQuery.build(userMessage)
        val excludeInlinedSubfolderId = resolveExcludeInlinedSubfolderId(profileId, ctx)
        return EidosPrefetchService(ctx.database, indexer).prefetch(
            policy = policy,
            profileId = profileId,
            query = query,
            subfolderId = ctx.currentSubfolderId,
            parentFolderId = ctx.currentParentFolderId,
            excludeInlinedSubfolderId = excludeInlinedSubfolderId,
            excludeConversationId = ctx.conversationId,
            conversationId = ctx.conversationId,
        )
    }

    private suspend fun resolveExcludeInlinedSubfolderId(
        profileId: String,
        ctx: EidosPromptComposeContext,
    ): Long? {
        if (profileId != EidosScopeProfileIds.SUBFOLDER) return null
        val subfolderId = ctx.currentSubfolderId ?: return null
        val note = ctx.database.noteDao().getBySubfolderOnce(subfolderId) ?: return null
        val body = note.content?.let { NotePromptContext.normalizeBody(it) }.orEmpty()
        return if (NotePromptContext.resolveTier(body.length) == NotePromptContext.InjectTier.INLINE_FULL) {
            subfolderId
        } else {
            null
        }
    }

    private fun buildUserFacingComposedPrompt(
        resolved: ResolvedEidosScope,
        stablePrefix: String,
        systemPrompt: String,
        retrievedBlock: String?,
        prefetchMetrics: EidosPrefetchMetrics?,
        volatileCharCount: Int,
    ): ComposedPrompt {
        val sectionCounts = buildMap {
            put("stable", stablePrefix.length)
            retrievedBlock?.let { put("retrieved", it.length) }
            if (volatileCharCount > 0) put("volatile", volatileCharCount)
            prefetchMetrics?.toTraceCounts()?.forEach { (key, value) -> put(key, value) }
        }
        return ComposedPrompt(
            systemPrompt = systemPrompt,
            profileId = resolved.profileId,
            entrySurface = resolved.entrySurface,
            stableSystemPrefix = stablePrefix,
            volatileSystemSuffix = volatileSuffixAfterStablePrefix(systemPrompt, stablePrefix),
            stablePrefixSha256 = EidosPromptTrace.sha256(stablePrefix),
            sectionCharCountsJson = EidosPromptTrace.sectionCharCountsJson(sectionCounts),
            prefetchMetrics = prefetchMetrics,
        )
    }
}
