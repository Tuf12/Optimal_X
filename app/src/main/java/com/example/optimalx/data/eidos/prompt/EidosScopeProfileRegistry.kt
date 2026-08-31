package com.example.optimalx.data.eidos.prompt

object EidosScopeProfileRegistry {

    private val generalAppTools = setOf(
        "search_semantic",
        "search_folders",
        "read_file",
        "read_note",
        "list_folder_contents",
        "create_parent_folder",
        "create_subfolder",
        "write_note",
        "edit_note_section",
        "rename_folder",
        "move_to_trash",
        "write_daily_memory",
        "write_long_term_memory",
    )

    private val parentTools = generalAppTools + setOf("write_note_summary")

    private val subfolderTools = setOf(
        "search_semantic",
        "read_file",
        "read_note",
        "read_note_section",
        "read_conversation",
        "create_subfolder",
        "write_note",
        "edit_note_section",
        "write_note_summary",
        "write_daily_memory",
        "write_long_term_memory",
        "create_parent_folder",
    )

    private val widgetSurfaceTools = setOf(
        "search_semantic",
        "search_folders",
        "read_file",
        "read_note",
        "create_parent_folder",
        "create_subfolder",
        "write_note",
        "edit_note_section",
        "write_daily_memory",
        "write_long_term_memory",
    )

    private val quickNotesDayTools = setOf(
        "search_semantic",
        "write_quick_note",
        "read_conversation",
    )

    private val quickNotesRootTools = setOf(
        "search_semantic",
        "search_folders",
        "list_folder_contents",
        "read_conversation",
    )

    private val dumpEditTools = setOf(
        "search_semantic",
        "read_dump_edit",
        "write_note",
        "edit_note_section",
    )

    private val webWidgetTools = setOf("search_semantic")

    private val webEditorTools = setOf(
        "search_semantic",
        "read_file",
        "read_note",
        "write_note",
        "edit_note_section",
    )

    private val imageStudioTools = setOf(
        "search_semantic",
        "search_folders",
        "list_images",
        "list_folder_contents",
        "describe_image",
    )

    private val workshopChatTools = setOf(
        "search_semantic",
        "workshop_read_file",
        "read_file",
    )

    private val workshopPlanTools = setOf(
        "search_semantic",
        "workshop_read_file",
        "workshop_write_file",
        "workshop_edit_file",
        "workshop_append_file",
    )

    private val profiles: Map<String, EidosScopeProfile> = listOf(
        EidosScopeProfile(
            id = EidosScopeProfileIds.GENERAL_APP,
            ontologyBlock = """
                General chat is a place to communicate with the user about any topic or just for fun — questions, planning, or open conversation. It is not tied to a specific parent folder or subfolder.
            """.trimIndent(),
            locationBlock = "User opened General chat from the main app — not anchored to a parent folder or subfolder. Help across OptimalX; retrieve on demand.",
            toolNames = generalAppTools,
            contextPolicy = EidosContextPolicy.CHAT_WITH_DAILY_MEMORY,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.PARENT,
            ontologyBlock = """
                A parent folder is a project container. User projects live in parent folders; inside a parent folder, subfolders organize the components of that project.
            """.trimIndent(),
            locationBlock = "User is browsing a parent folder (subfolder list). Discuss and act across this project's subfolders.",
            toolNames = parentTools,
            contextPolicy = EidosContextPolicy.CHAT_WITH_DAILY_MEMORY,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.SUBFOLDER,
            ontologyBlock = """
                Subfolders sit inside parent folders. Inside a subfolder the user works with standard panels — Note, Files, and Web browser — and can add custom panels created in the panel workshop.
            """.trimIndent(),
            locationBlock = "User is inside a subfolder — Note, Files, and Web panels are available for this workspace.",
            toolNames = subfolderTools,
            contextPolicy = EidosContextPolicy.CHAT_WITH_DAILY_MEMORY,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.WIDGET_ASK,
            ontologyBlock = """
                Ask Eidos from the widget is a lightweight question-and-answer surface — often one question and a follow-up, but regular conversation is fine also.
            """.trimIndent(),
            locationBlock = "User invoked Ask Eidos from the widget — short Q&A, often voice, limited follow-ups. Not folder-anchored.",
            toolNames = widgetSurfaceTools,
            // Quick voice Q&A — prioritize fast first token over deep reasoning (disable Kimi thinking).
            thinkingEnabled = false,
            contextPolicy = EidosContextPolicy.WIDGET_WITH_PREFETCH,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.WIDGET_CHAT,
            ontologyBlock = """
                Widget Chat is a place to communicate with the user about any topic or just for fun — questions, planning, or open conversation. It is not tied to a specific parent folder or subfolder.
            """.trimIndent(),
            locationBlock = "User opened Widget Chat — full chat UI from the widget for ongoing conversation. Not folder-anchored.",
            toolNames = widgetSurfaceTools,
            contextPolicy = EidosContextPolicy.WIDGET_WITH_PREFETCH,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.QUICK_NOTES_DAY,
            ontologyBlock = """
                Quick Notes is a daily capture feature. The user uses it to save things for later — lists, reminders, ideas, or anything they want written down without opening a full project note. Each calendar day has its own Quick Notes page; entries are timestamped and appended to that day. Your main job here is to capture what they said using write_quick_note.
            """.trimIndent(),
            locationBlock = "User is in a Quick Notes dated inbox. Append-only capture for that calendar day.",
            toolNames = quickNotesDayTools,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.QUICK_NOTES_ROOT,
            ontologyBlock = """
                Quick Notes root is the directory of dated day folders with Quick note entries — browse past days and open an inbox; it is not the live capture surface for today's entries.
            """.trimIndent(),
            locationBlock = "User is browsing the Quick Notes folder list — not inside a specific day's inbox.",
            toolNames = quickNotesRootTools,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.DUMP_EDIT,
            ontologyBlock = """
                DumpEdit is a temporary scratch pad for draft content you and the user can work on together before it becomes a saved note in a folder — or before the user discards it.
            """.trimIndent(),
            locationBlock = "User is in the DumpEdit scratch buffer — unsaved staging text, not a folder note.",
            toolNames = dumpEditTools,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.WEB_WIDGET,
            ontologyBlock = """
                Web-widget chat is for questions about the open web page or live URL. Provider web fetch/search is the primary way to read page content.
            """.trimIndent(),
            locationBlock = "User is in a web-widget chat thread — focused on a loaded page or URL context.",
            toolNames = webWidgetTools,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.WEB_EDITOR,
            ontologyBlock = """
                The Web panel is a standard subfolder panel for browsing. Eidos can discuss the loaded page and, when needed, relate findings back to the subfolder's Note and Files.
            """.trimIndent(),
            locationBlock = "User has the Web browser panel open inside a subfolder; chat is scoped to that browsing context.",
            toolNames = webEditorTools,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.PANEL_GALLERY,
            ontologyBlock = """
                Panel Gallery is the library of panels and workshop projects — metadata and discovery, not live panel runtime or file editing. A panel is a custom mini-app built in Panel Workshop and run inside OptimalX.
            """.trimIndent(),
            locationBlock = "User is browsing the Panel Gallery — catalog of panels and workshop projects.",
            toolNames = setOf("search_semantic"),
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.PANEL_RUNNER,
            ontologyBlock = """
                You are viewing a panel in Panel Runner. A panel is a custom mini-app built in Panel Workshop and run inside OptimalX. Panel Runner is the live runtime for a built panel. Eidos can read workshop project files and optionally invoke panel JavaScript (getState / runAction) when available.
            """.trimIndent(),
            locationBlock = "User is running a panel live in Panel Runner — bridge to panel JavaScript is active when preview/tab is open.",
            toolNames = setOf("search_semantic", "workshop_read_file", "call_panel_function"),
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.IMAGE_STUDIO,
            ontologyBlock = """
                Image Studio is OptimalX's cloud image generation workspace. Eidos helps refine prompts, browse existing images, and hand off drafts to the panel — generation always happens in the Image Studio UI, not from chat tools.
            """.trimIndent(),
            locationBlock = "User is in Image Studio — browse images with list_images; when ready, output an Image Studio draft block for the user to apply.",
            toolNames = imageStudioTools,
            contextPolicy = EidosContextPolicy.CHAT_WITH_DAILY_MEMORY,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.WORKSHOP_CHAT,
            ontologyBlock = """
                Panel Workshop is a separate build system for interactive panels (HTML/CSS/JS projects) inside OptimalX. Chat mode is for discussion and research — including pulling context from normal parent/subfolder notes and files when the panel serves a real project. No project file writes in Chat — just have a conversation with the user.
            """.trimIndent(),
            locationBlock = "Panel Workshop Chat mode — discuss the project; retrieve from workshop files and from the parent/subfolder project this panel serves when gathering context.",
            toolNames = workshopChatTools,
            loopPolicy = ToolLoopPolicy(EidosScopeProfile.WORKSHOP_CHAT_MAX_TOOL_ROUNDS),
            contextPolicy = EidosContextPolicy.WORKSHOP_CHAT,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.WORKSHOP_PLAN,
            ontologyBlock = """
                Workshop Plan mode — shape specs and scaffold project files; write workshop files; no live panel bridge required.
            """.trimIndent(),
            locationBlock = "Panel Workshop Plan mode — write and shape spec files; no live panel bridge required.",
            toolNames = workshopPlanTools,
            loopPolicy = ToolLoopPolicy(EidosScopeProfile.WORKSHOP_BUILD_MAX_TOOL_ROUNDS),
            contextPolicy = EidosContextPolicy.WORKSHOP_BUILD,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.WORKSHOP_EDIT,
            ontologyBlock = """
                Workshop Edit/Build — implement and fix panel code in the project; Diff Review applies writes; call_panel_function optional when Preview is open.
            """.trimIndent(),
            locationBlock = "Panel Workshop Edit/Build mode — implement and fix panel code; Diff Review applies writes.",
            toolNames = emptySet(),
            loopPolicy = ToolLoopPolicy(EidosScopeProfile.WORKSHOP_BUILD_MAX_TOOL_ROUNDS),
            contextPolicy = EidosContextPolicy.WORKSHOP_BUILD,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.WORKSHOP_INTAKE,
            ontologyBlock = """
                Workshop Intake — clarify project before build; chat-only tools.
            """.trimIndent(),
            locationBlock = "Panel Workshop Intake — clarify the project before spec generation; chat-only, no file writes.",
            toolNames = setOf("search_semantic", "workshop_read_file"),
            loopPolicy = ToolLoopPolicy(EidosScopeProfile.WORKSHOP_BUILD_MAX_TOOL_ROUNDS),
            contextPolicy = EidosContextPolicy.WORKSHOP_INTAKE,
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.INTERNAL_CONTENT_SUMMARY,
            ontologyBlock = "Batch summarization of note or workshop content — fixed output shape, no user chat.",
            toolNames = emptySet(),
        ),
        EidosScopeProfile(
            id = EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER,
            ontologyBlock = "Nightly memory rollover — not user-facing chat.",
            toolNames = emptySet(),
        ),
    ).associateBy { it.id }

    fun require(profileId: String): EidosScopeProfile =
        profiles[profileId] ?: error("Unknown Eidos scope profile: $profileId")

    fun allProfiles(): Collection<EidosScopeProfile> = profiles.values
}
