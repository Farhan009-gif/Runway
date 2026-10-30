package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.ui.components.ChatMediaGenerateBottomSheet
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.local.ChatMediaAttachment
import com.example.data.local.ConversationEntity
import com.example.data.local.McpToolEntity
import com.example.data.local.MessageEntity
import com.example.data.local.ModelDescriptorEntity
import com.example.data.local.ProviderInstanceEntity
import com.example.data.local.RunwayConversationMetaEntity
import com.example.data.local.RunwayDocumentEntity
import com.example.data.local.RunwayFolderEntity
import com.example.data.local.RunwayMessageNodeEntity
import com.example.data.local.SkillEntity
import com.example.data.local.ToolCallLogEntity
import com.example.data.local.getAttachments
import com.example.data.mcp.SkillMarkdownParser
import com.example.data.provider.OmniRouteComboDefinition
import com.example.ui.components.CollapsibleToolCallCard
import com.example.ui.components.LucideIcons
import com.example.ui.components.MinimalButton
import com.example.ui.components.MinimalCard
import com.example.ui.components.MinimalChip
import com.example.ui.components.MinimalSheet
import com.example.ui.components.RunwayChatHistoryDrawerContent
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.MonospaceFontFamily
import com.example.ui.theme.SoftIndigo
import com.example.ui.viewmodel.OmniUiState
import kotlinx.coroutines.launch

private sealed class ThreadDisplayItem {
    abstract val key: String
    data class SingleMessage(val message: MessageEntity) : ThreadDisplayItem() {
        override val key: String = "single_${message.id}"
    }
    data class ComparisonRow(val groupId: String, val responses: List<MessageEntity>) : ThreadDisplayItem() {
        override val key: String = "cmp_$groupId"
    }
}

@Composable
fun ChatWorkspaceScreen(
    uiState: OmniUiState,
    providers: List<ProviderInstanceEntity>,
    models: List<ModelDescriptorEntity>,
    conversations: List<ConversationEntity>,
    messages: List<MessageEntity>,
    skills: List<SkillEntity> = emptyList(),
    mcpTools: List<McpToolEntity> = emptyList(),
    conversationToolLogs: List<ToolCallLogEntity> = emptyList(),
    folders: List<RunwayFolderEntity> = emptyList(),
    conversationMetas: List<RunwayConversationMetaEntity> = emptyList(),
    allMessageNodes: List<RunwayMessageNodeEntity> = emptyList(),
    allDocuments: List<RunwayDocumentEntity> = emptyList(),
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onContinueLatestConversation: () -> Unit = {},
    onTogglePinConversation: (ConversationEntity) -> Unit,
    onRenameConversation: (ConversationEntity, String) -> Unit,
    onDuplicateConversation: (String) -> Unit = {},
    onDeleteConversation: (String) -> Unit,
    onUndoDeleteConversation: () -> Unit = {},
    onExportConversation: (conversationId: String, asJson: Boolean) -> Unit,
    onExportConversationWithBranches: (conversationId: String, asJson: Boolean, includeAllBranches: Boolean) -> Unit = { id, asJson, _ ->
        onExportConversation(id, asJson)
    },
    onSelectFolderFilter: (String?) -> Unit = {},
    onMoveConversationToFolder: (conversationId: String, folderId: String?) -> Unit = { _, _ -> },
    onSaveFolder: (id: String?, name: String, colorHex: String) -> Unit = { _, _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    onHistorySearchChange: (String) -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenParameterSheet: () -> Unit,
    onOpenPromptLibrary: () -> Unit,
    onOpenSkillsAndToolsSheet: () -> Unit = {},
    onOpenGitClone: () -> Unit = {},
    onOpenCompareTargetPicker: () -> Unit,
    onToggleCompareMode: (Boolean) -> Unit,
    onOpenOnboardingGuide: () -> Unit,
    onDraftChange: (String) -> Unit,
    onAttachImage: (String?) -> Unit,
    onAddMediaAttachments: (List<ChatMediaAttachment>) -> Unit = {},
    onRemoveMediaAttachment: (Int) -> Unit = {},
    onClearMediaAttachments: () -> Unit = {},
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
    onRegenerateLast: () -> Unit,
    onStartEditMessage: (MessageEntity) -> Unit,
    onCancelEditMessage: () -> Unit,
    onApproveCleartextHostFromError: (String) -> Unit,
    onDismissError: () -> Unit,
    onCopiedToast: (String) -> Unit,
    onPushConversationToGitHub: (String) -> Unit = {},
    onPushContentToGitHub: (title: String, fileName: String, content: String) -> Unit = { _, _, _ -> },
    onSelectProvider: (String) -> Unit = {},
    onSelectModel: (String, String) -> Unit = { _, _ -> },
    onRunPreflightCheck: (String) -> Unit = {},
    onSwitchToDirectModel: (String) -> Unit = {},
    onOpenProviderSettings: () -> Unit = {},
    onOpenTerminalAndHealthSheet: () -> Unit = {},
    onOpenConnectors: () -> Unit = {},
    onGenerateMediaInChat: (
        modality: String,
        prompt: String,
        providerId: String?,
        modelId: String?,
        aspectRatio: String?,
        durationSeconds: Int?,
        sourceImageUri: String?
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onUpsertCustomCombo: (
        rawId: String,
        displayName: String,
        strategy: String,
        targetModels: List<String>,
        description: String
    ) -> Unit = { _, _, _, _, _ -> }
) {
    val activeConversation = remember(conversations, uiState.activeConversationId) {
        conversations.firstOrNull { it.id == uiState.activeConversationId } ?: conversations.firstOrNull()
    }
    val activeModelDescriptor = remember(models, activeConversation) {
        models.firstOrNull {
            it.providerInstanceId == activeConversation?.providerInstanceId &&
                it.modelId == activeConversation?.modelId
        }
    }
    val isActiveModelCombo = remember(activeModelDescriptor, activeConversation, uiState.omniRouteCombos) {
        val modelId = activeConversation?.modelId.orEmpty()
        activeModelDescriptor?.isCombo == true ||
            uiState.omniRouteCombos.any { it.id.equals(modelId, ignoreCase = true) }
    }

    val activeSkillsCount = remember(skills, activeConversation, uiState.draftMessage) {
        SkillMarkdownParser.resolveActiveSkillsForTurn(
            allSkills = skills,
            conversation = activeConversation,
            userPrompt = uiState.draftMessage
        ).size
    }
    val enabledMcpToolsCount = remember(mcpTools, activeConversation) {
        if (activeConversation?.mcpToolsEnabled == false) 0
        else mcpTools.count { it.isEnabled }
    }

    var showToolsSheet by rememberSaveable { mutableStateOf(false) }
    var showAttachSheet by rememberSaveable { mutableStateOf(false) }
    var showChatMediaGenerateSheet by rememberSaveable { mutableStateOf(false) }
    var chatMediaGenerateModality by rememberSaveable { mutableStateOf("IMAGE") }
    var showChatComboBuilderDialog by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }

    if (showChatMediaGenerateSheet) {
        ChatMediaGenerateBottomSheet(
            onDismiss = { showChatMediaGenerateSheet = false },
            initialModality = chatMediaGenerateModality,
            initialPrompt = uiState.draftMessage,
            initialImageUri = uiState.attachedImageUri ?: uiState.attachedMediaList.firstOrNull { !it.isVideo }?.uri,
            providers = providers,
            models = models,
            uiState = uiState,
            onGenerate = { mod, pr, provId, modId, aspect, dur, srcUri ->
                onGenerateMediaInChat(mod, pr, provId, modId, aspect, dur, srcUri)
            }
        )
    }

    if (showChatComboBuilderDialog) {
        OmniRouteComboBuilderDialog(
            initialCombo = null,
            defaultModality = "CHAT",
            onDismiss = { showChatComboBuilderDialog = false },
            onSave = { rawId, displayName, _, strategy, targetModels, description ->
                onUpsertCustomCombo(rawId, displayName, strategy, targetModels, description)
                showChatComboBuilderDialog = false
            }
        )
    }

    val multiPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val items = uris.map { uri ->
                ChatMediaAttachment(
                    uri = uri.toString(),
                    mimeType = "image/jpeg",
                    isVideo = false,
                    name = uri.lastPathSegment ?: "photo.jpg"
                )
            }
            onAddMediaAttachments(items)
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onAddMediaAttachments(
                listOf(
                    ChatMediaAttachment(
                        uri = uri.toString(),
                        mimeType = "video/mp4",
                        isVideo = true,
                        name = uri.lastPathSegment ?: "video.mp4"
                    )
                )
            )
        }
    }

    // Rename Conversation Dialog
    renameTarget?.let { target ->
        var newTitle by rememberSaveable(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Rename conversation", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                MinimalButton(
                    text = "Save",
                    onClick = {
                        onRenameConversation(target, newTitle)
                        renameTarget = null
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // Minimal "Tools" Bottom Sheet (Compare, Enhance, Quick Actions, Parameters, Console, Rename)
    if (showToolsSheet) {
        MinimalSheet(
            onDismiss = { showToolsSheet = false },
            title = "Tools",
            modifier = Modifier.testTag("chat_tools_bottom_sheet")
        ) {
            MinimalToolRow(
                icon = LucideIcons.GitCompare,
                title = if (uiState.compareModeEnabled) "Compare mode (On)" else "Compare",
                subtitle = "Run up to 3 models side-by-side",
                isActive = uiState.compareModeEnabled,
                onClick = {
                    showToolsSheet = false
                    onToggleCompareMode(!uiState.compareModeEnabled)
                },
                testTag = "tools_sheet_compare"
            )
            MinimalToolRow(
                icon = LucideIcons.Layers,
                title = "Custom combos",
                subtitle = "Create multi-model fallback, round-robin, or latency-first chat combos",
                isActive = isActiveModelCombo,
                onClick = {
                    showToolsSheet = false
                    showChatComboBuilderDialog = true
                },
                testTag = "tools_sheet_custom_combos"
            )
            MinimalToolRow(
                icon = LucideIcons.Sparkles,
                title = "Enhance prompt",
                subtitle = "Refine your current message draft for clarity",
                onClick = {
                    showToolsSheet = false
                    val draft = uiState.draftMessage.trim()
                    if (draft.isNotBlank()) {
                        onDraftChange("Please provide a thorough, well-structured response to:\n\n$draft")
                    } else {
                        onOpenPromptLibrary()
                    }
                },
                testTag = "tools_sheet_enhance"
            )
            MinimalToolRow(
                icon = LucideIcons.Zap,
                title = "Quick actions",
                subtitle = "Browse saved prompt templates",
                onClick = {
                    showToolsSheet = false
                    onOpenPromptLibrary()
                },
                testTag = "tools_sheet_quick_actions"
            )
            MinimalToolRow(
                icon = LucideIcons.Sliders,
                title = "Parameters",
                subtitle = "Temperature, top-p, max tokens & system prompt",
                onClick = {
                    showToolsSheet = false
                    onOpenParameterSheet()
                },
                testTag = "tools_sheet_parameters"
            )
            MinimalToolRow(
                icon = LucideIcons.Plug,
                title = "Connectors",
                subtitle = "Manage MCP connectors, tool policies & GitHub",
                onClick = {
                    showToolsSheet = false
                    onOpenConnectors()
                },
                testTag = "tools_sheet_connectors"
            )
            MinimalToolRow(
                icon = LucideIcons.GitBranch,
                title = "Git Clone & Repo Skills",
                subtitle = "Clone GitHub repo and use agent skills (SKILL.md)",
                onClick = {
                    showToolsSheet = false
                    onOpenGitClone()
                },
                testTag = "tools_sheet_git_clone"
            )
            MinimalToolRow(
                icon = LucideIcons.Sparkles,
                title = "Generate Image / Video",
                subtitle = "Synthesize images or videos right here in chat without leaving the tab",
                onClick = {
                    showToolsSheet = false
                    chatMediaGenerateModality = "IMAGE"
                    showChatMediaGenerateSheet = true
                },
                testTag = "tools_sheet_generate_media"
            )
            MinimalToolRow(
                icon = LucideIcons.Terminal,
                title = "Console",
                subtitle = "OmniRoute localhost daemon & model health check",
                onClick = {
                    showToolsSheet = false
                    onOpenTerminalAndHealthSheet()
                },
                testTag = "tools_sheet_console"
            )
            activeConversation?.let { currentConv ->
                MinimalToolRow(
                    icon = LucideIcons.Edit,
                    title = "Rename chat",
                    subtitle = currentConv.title,
                    onClick = {
                        showToolsSheet = false
                        renameTarget = currentConv
                    },
                    testTag = "tools_sheet_rename_chat"
                )
            }
        }
    }

    // "+" Attachment & Enhance Options Bottom Sheet
    if (showAttachSheet) {
        MinimalSheet(
            onDismiss = { showAttachSheet = false },
            title = "Add to message",
            modifier = Modifier.testTag("chat_attach_bottom_sheet")
        ) {
            MinimalToolRow(
                icon = LucideIcons.Image,
                title = "Generate AI image",
                subtitle = "Synthesize an image directly in chat using Flux, Imagen or SD",
                onClick = {
                    showAttachSheet = false
                    chatMediaGenerateModality = "IMAGE"
                    showChatMediaGenerateSheet = true
                },
                testTag = "attach_sheet_generate_image"
            )
            MinimalToolRow(
                icon = LucideIcons.Video,
                title = "Generate AI video",
                subtitle = "Synthesize video clips using Runway, Luma, Kling or Video Combo",
                onClick = {
                    showAttachSheet = false
                    chatMediaGenerateModality = "VIDEO"
                    showChatMediaGenerateSheet = true
                },
                testTag = "attach_sheet_generate_video"
            )
            MinimalToolRow(
                icon = LucideIcons.Image,
                title = "Attach photos",
                subtitle = "Select up to 10 images from your device",
                onClick = {
                    showAttachSheet = false
                    multiPhotoLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                testTag = "attach_sheet_photos"
            )
            MinimalToolRow(
                icon = LucideIcons.Video,
                title = "Attach video",
                subtitle = "Upload a video clip for multimodal analysis",
                onClick = {
                    showAttachSheet = false
                    videoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                testTag = "attach_sheet_video"
            )
            MinimalToolRow(
                icon = LucideIcons.Sparkles,
                title = "Enhance prompt",
                subtitle = "Expand draft or insert a prompt template",
                onClick = {
                    showAttachSheet = false
                    val draft = uiState.draftMessage.trim()
                    if (draft.isNotBlank()) {
                        onDraftChange("Act as an expert engineer. Provide a concise, high-signal solution for:\n\n$draft")
                    } else {
                        onOpenPromptLibrary()
                    }
                },
                testTag = "attach_sheet_enhance"
            )
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideTablet = maxWidth >= 840.dp

        if (isWideTablet) {
            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                ) {
                    RunwayChatHistoryDrawerContent(
                        conversations = conversations,
                        activeConversationId = activeConversation?.id,
                        folders = folders,
                        conversationMetas = conversationMetas,
                        allMessageNodes = allMessageNodes,
                        allDocuments = allDocuments,
                        selectedFolderId = uiState.selectedHistoryFolderId,
                        searchQuery = uiState.historySearchQuery,
                        hasPendingUndoDelete = uiState.hasPendingDeletedConversationUndo,
                        onSearchChange = onHistorySearchChange,
                        onSelectFolderFilter = onSelectFolderFilter,
                        onSelectConversation = onSelectConversation,
                        onNewConversation = onNewConversation,
                        onContinueLatestConversation = onContinueLatestConversation,
                        onTogglePinConversation = onTogglePinConversation,
                        onRenameConversation = { renameTarget = it },
                        onDuplicateConversation = onDuplicateConversation,
                        onMoveConversationToFolder = onMoveConversationToFolder,
                        onDeleteConversation = onDeleteConversation,
                        onUndoDeleteConversation = onUndoDeleteConversation,
                        onExportConversation = onExportConversationWithBranches,
                        onSaveFolder = onSaveFolder,
                        onDeleteFolder = onDeleteFolder,
                        onOpenConnectors = onOpenConnectors
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    MinimalChatCanvas(
                        uiState = uiState,
                        providers = providers,
                        models = models,
                        activeConversation = activeConversation,
                        activeModelName = activeModelDescriptor?.modelId
                            ?: activeConversation?.modelId
                            ?: "gpt-4o",
                        isActiveModelCombo = isActiveModelCombo,
                        activeSkillsCount = activeSkillsCount,
                        enabledMcpToolsCount = enabledMcpToolsCount,
                        messages = messages,
                        conversationToolLogs = conversationToolLogs,
                        showMenuButton = false,
                        onOpenMenu = {},
                        onOpenModelPicker = onOpenModelPicker,
                        onOpenToolsSheet = { showToolsSheet = true },
                        onOpenSkillsAndToolsSheet = onOpenSkillsAndToolsSheet,
                        onOpenAttachSheet = { showAttachSheet = true },
                        onDraftChange = onDraftChange,
                        onRemoveMediaAttachment = onRemoveMediaAttachment,
                        onClearMediaAttachments = onClearMediaAttachments,
                        onSendMessage = onSendMessage,
                        onStopGeneration = onStopGeneration,
                        onRegenerateLast = onRegenerateLast,
                        onStartEditMessage = onStartEditMessage,
                        onCancelEditMessage = onCancelEditMessage,
                        onApproveCleartextHostFromError = onApproveCleartextHostFromError,
                        onDismissError = onDismissError,
                        onCopiedToast = onCopiedToast,
                        onPushContentToGitHub = onPushContentToGitHub,
                        onOpenCompareTargetPicker = onOpenCompareTargetPicker
                    )
                }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.width(305.dp)
                    ) {
                        RunwayChatHistoryDrawerContent(
                            conversations = conversations,
                            activeConversationId = activeConversation?.id,
                            folders = folders,
                            conversationMetas = conversationMetas,
                            allMessageNodes = allMessageNodes,
                            allDocuments = allDocuments,
                            selectedFolderId = uiState.selectedHistoryFolderId,
                            searchQuery = uiState.historySearchQuery,
                            hasPendingUndoDelete = uiState.hasPendingDeletedConversationUndo,
                            onSearchChange = onHistorySearchChange,
                            onSelectFolderFilter = onSelectFolderFilter,
                            onSelectConversation = { id ->
                                onSelectConversation(id)
                                coroutineScope.launch { drawerState.close() }
                            },
                            onNewConversation = {
                                onNewConversation()
                                coroutineScope.launch { drawerState.close() }
                            },
                            onContinueLatestConversation = {
                                onContinueLatestConversation()
                                coroutineScope.launch { drawerState.close() }
                            },
                            onTogglePinConversation = onTogglePinConversation,
                            onRenameConversation = { renameTarget = it },
                            onDuplicateConversation = onDuplicateConversation,
                            onMoveConversationToFolder = onMoveConversationToFolder,
                            onDeleteConversation = onDeleteConversation,
                            onUndoDeleteConversation = onUndoDeleteConversation,
                            onExportConversation = onExportConversationWithBranches,
                            onSaveFolder = onSaveFolder,
                            onDeleteFolder = onDeleteFolder,
                            onOpenConnectors = {
                                coroutineScope.launch { drawerState.close() }
                                onOpenConnectors()
                            }
                        )
                    }
                }
            ) {
                MinimalChatCanvas(
                    uiState = uiState,
                    providers = providers,
                    models = models,
                    activeConversation = activeConversation,
                    activeModelName = activeModelDescriptor?.modelId
                        ?: activeConversation?.modelId
                        ?: "gpt-4o",
                    isActiveModelCombo = isActiveModelCombo,
                    activeSkillsCount = activeSkillsCount,
                    enabledMcpToolsCount = enabledMcpToolsCount,
                    messages = messages,
                    conversationToolLogs = conversationToolLogs,
                    showMenuButton = true,
                    onOpenMenu = { coroutineScope.launch { drawerState.open() } },
                    onOpenModelPicker = onOpenModelPicker,
                    onOpenNewComboDialog = { showChatComboBuilderDialog = true },
                    onOpenToolsSheet = { showToolsSheet = true },
                    onOpenSkillsAndToolsSheet = onOpenSkillsAndToolsSheet,
                    onOpenAttachSheet = { showAttachSheet = true },
                    onDraftChange = onDraftChange,
                    onRemoveMediaAttachment = onRemoveMediaAttachment,
                    onClearMediaAttachments = onClearMediaAttachments,
                    onSendMessage = onSendMessage,
                    onStopGeneration = onStopGeneration,
                    onRegenerateLast = onRegenerateLast,
                    onStartEditMessage = onStartEditMessage,
                    onCancelEditMessage = onCancelEditMessage,
                    onApproveCleartextHostFromError = onApproveCleartextHostFromError,
                    onDismissError = onDismissError,
                    onCopiedToast = onCopiedToast,
                    onPushContentToGitHub = onPushContentToGitHub,
                    onOpenCompareTargetPicker = onOpenCompareTargetPicker
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    messages: List<MessageEntity>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    activeModelId: String = "gpt-4o",
    attachedImageUri: String? = null,
    activeToolLoopStatus: String? = null,
    onPickVisionImage: () -> Unit = {},
    onClearVisionImage: () -> Unit = {},
    onStopGeneration: () -> Unit = {},
    topBar: @Composable () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val reversedMessages = remember(messages) { messages.asReversed() }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = topBar,
        bottomBar = {
            MinimalFloatingComposerBar(
                draft = draft,
                isStreaming = isStreaming,
                enterToSend = true,
                editingMessageId = null,
                attachments = if (!attachedImageUri.isNullOrBlank()) {
                    listOf(ChatMediaAttachment(uri = attachedImageUri))
                } else emptyList(),
                onDraftChange = onDraftChange,
                onOpenAttachOptions = onPickVisionImage,
                onRemoveAttachment = { onClearVisionImage() },
                onCancelEdit = {},
                onSend = onSendMessage,
                onStop = onStopGeneration
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("chat_screen_message_list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(reversedMessages, key = { it.id }) { msg ->
                MinimalChatMessageItem(
                    message = msg,
                    toolLogs = emptyList(),
                    isLastAssistantMessage = false,
                    activeToolLoopStatus = if (msg.isStreaming) activeToolLoopStatus else null,
                    onEditUserMessage = {},
                    onRegenerate = {},
                    onCopiedToast = {},
                    onPushToGitHub = { _, _, _ -> }
                )
            }
        }
    }
}

@Composable
private fun MinimalChatCanvas(
    uiState: OmniUiState,
    providers: List<ProviderInstanceEntity> = emptyList(),
    models: List<ModelDescriptorEntity> = emptyList(),
    activeConversation: ConversationEntity?,
    activeModelName: String,
    isActiveModelCombo: Boolean = false,
    activeSkillsCount: Int,
    enabledMcpToolsCount: Int,
    messages: List<MessageEntity>,
    conversationToolLogs: List<ToolCallLogEntity>,
    showMenuButton: Boolean,
    onOpenMenu: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenNewComboDialog: () -> Unit = {},
    onOpenToolsSheet: () -> Unit,
    onOpenSkillsAndToolsSheet: () -> Unit,
    onOpenAttachSheet: () -> Unit,
    onDraftChange: (String) -> Unit,
    onRemoveMediaAttachment: (Int) -> Unit,
    onClearMediaAttachments: () -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
    onRegenerateLast: () -> Unit,
    onStartEditMessage: (MessageEntity) -> Unit,
    onCancelEditMessage: () -> Unit,
    onApproveCleartextHostFromError: (String) -> Unit,
    onDismissError: () -> Unit,
    onCopiedToast: (String) -> Unit,
    onPushContentToGitHub: (title: String, fileName: String, content: String) -> Unit,
    onOpenCompareTargetPicker: () -> Unit
) {
    val listState = rememberLazyListState()

    val threadItems = remember(messages) {
        val result = mutableListOf<ThreadDisplayItem>()
        var idx = 0
        while (idx < messages.size) {
            val current = messages[idx]
            val groupId = current.comparisonGroupId
            if (groupId != null && current.role == "assistant") {
                val group = mutableListOf<MessageEntity>()
                while (idx < messages.size && messages[idx].comparisonGroupId == groupId) {
                    group.add(messages[idx])
                    idx++
                }
                result.add(ThreadDisplayItem.ComparisonRow(groupId, group))
            } else {
                result.add(ThreadDisplayItem.SingleMessage(current))
                idx++
            }
        }
        result.asReversed()
    }

    val lastAssistantId = remember(messages) {
        messages.lastOrNull { it.role == "assistant" }?.id
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (threadItems.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    val currentAttachments = remember(uiState.attachedMediaList, uiState.attachedImageUri) {
        if (uiState.attachedMediaList.isNotEmpty()) {
            uiState.attachedMediaList
        } else if (!uiState.attachedImageUri.isNullOrBlank()) {
            listOf(ChatMediaAttachment(uri = uiState.attachedImageUri))
        } else {
            emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // 1. Minimal Top Bar: Menu left, Model pill + Combo chip center, Overflow right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (showMenuButton) {
                IconButton(
                    onClick = onOpenMenu,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("open_drawer_button")
                ) {
                    Icon(
                        imageVector = LucideIcons.Menu,
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(44.dp))
            }

            // Center tappable model pill + quick "+ Combo" button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    onClick = onOpenModelPicker,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .widthIn(max = 210.dp)
                        .testTag("top_bar_model_selector_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = activeModelName.substringAfterLast("/"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isActiveModelCombo) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = SoftIndigo.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = "COMBO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SoftIndigo,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Icon(
                            imageVector = LucideIcons.ChevronDown,
                            contentDescription = "Select model",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                MinimalChip(
                    label = "+ Combo",
                    selected = false,
                    onClick = onOpenNewComboDialog,
                    testTag = "chat_top_bar_new_combo_button"
                )
            }

            // Right single overflow icon -> opens "Tools" bottom sheet
            IconButton(
                onClick = onOpenToolsSheet,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("top_bar_tools_overflow_button")
            ) {
                Icon(
                    imageVector = LucideIcons.MoreHorizontal,
                    contentDescription = "Tools",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 3. Quiet Status Line under the top bar: "2 skills · 0 tools ready"
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSkillsAndToolsSheet)
                .padding(bottom = 8.dp)
                .testTag("quiet_skills_tools_status_line")
        ) {
            Text(
                text = "$activeSkillsCount skills · $enabledMcpToolsCount tools ready",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Compare Mode subtle bar when Compare Mode is active
        AnimatedVisibility(
            visible = uiState.compareModeEnabled,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Comparing ${uiState.compareTargets.size} models",
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftIndigo
                )
                Text(
                    text = "Configure",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = SoftIndigo,
                    modifier = Modifier.clickable(onClick = onOpenCompareTargetPicker)
                )
            }
        }

        // Subtle error banner if present
        uiState.activeError?.let { err ->
            MinimalCard(
                borderColor = CoralError.copy(alpha = 0.4f),
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = err.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (err.unapprovedHost != null) {
                        Text(
                            text = "Approve",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = SoftIndigo,
                            modifier = Modifier
                                .clickable { onApproveCleartextHostFromError(err.unapprovedHost) }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    Icon(
                        imageVector = LucideIcons.X,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(onClick = onDismissError)
                    )
                }
            }
        }

        // 4. Main Chat Area: Empty state OR minimal message stream
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (threadItems.isEmpty()) {
                MinimalEmptyChatState(
                    onPickSuggestion = { prompt ->
                        onDraftChange(prompt)
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("chat_messages_lazy_column")
                ) {
                    items(threadItems, key = { it.key }) { item ->
                        when (item) {
                            is ThreadDisplayItem.SingleMessage -> {
                                val msg = item.message
                                val msgLogs = remember(conversationToolLogs, msg.id) {
                                    conversationToolLogs.filter { it.messageId == msg.id }
                                }
                                MinimalChatMessageItem(
                                    message = msg,
                                    providers = providers,
                                    models = models,
                                    combos = uiState.omniRouteCombos,
                                    toolLogs = msgLogs,
                                    isLastAssistantMessage = msg.id == lastAssistantId,
                                    isTokenCountEstimated = msg.id in uiState.estimatedTokenMessageIds,
                                    activeToolLoopStatus = if (msg.isStreaming) uiState.activeToolLoopStatus else null,
                                    onEditUserMessage = { onStartEditMessage(msg) },
                                    onRegenerate = onRegenerateLast,
                                    onCopiedToast = onCopiedToast,
                                    onPushToGitHub = onPushContentToGitHub
                                )
                            }

                            is ThreadDisplayItem.ComparisonRow -> {
                                MinimalComparisonRow(
                                    responses = item.responses,
                                    providers = providers,
                                    models = models,
                                    combos = uiState.omniRouteCombos,
                                    estimatedMessageIds = uiState.estimatedTokenMessageIds,
                                    onCopiedToast = onCopiedToast
                                )
                            }
                        }
                    }
                }
            }
        }

        // Conversation Usage Summary line at the bottom of each chat (includes tool calls and code runs)
        val completedAssistantMessages = remember(messages) {
            messages.filter { it.role.equals("ASSISTANT", ignoreCase = true) && !it.isStreaming }
        }
        if (completedAssistantMessages.isNotEmpty() || conversationToolLogs.isNotEmpty()) {
            val conversationSummaryText = remember(
                completedAssistantMessages,
                conversationToolLogs,
                uiState.estimatedTokenMessageIds
            ) {
                val totalIn = completedAssistantMessages.sumOf { msg ->
                    if (msg.promptTokens > 0) msg.promptTokens
                    else com.example.data.provider.ModelPriceTable.estimateTokensFromText(msg.content)
                }
                val totalOut = completedAssistantMessages.sumOf { msg ->
                    if (msg.completionTokens > 0) msg.completionTokens
                    else com.example.data.provider.ModelPriceTable.estimateTokensFromText(msg.content)
                }
                val totalCost = completedAssistantMessages.sumOf { msg ->
                    if (msg.estimatedCostUsd > 0.0) msg.estimatedCostUsd
                    else com.example.data.provider.ModelPriceTable.calculateCostUsd(
                        modelId = msg.modelId,
                        inputTokens = if (msg.promptTokens > 0) msg.promptTokens else 1,
                        outputTokens = if (msg.completionTokens > 0) msg.completionTokens else 1
                    )
                }
                val hasEstimated = completedAssistantMessages.any { msg ->
                    msg.id in uiState.estimatedTokenMessageIds || (msg.promptTokens == 0 && msg.completionTokens == 0)
                }
                val codeRunsCount = conversationToolLogs.count { log ->
                    log.toolName == com.example.data.mcp.JsSandboxExecutor.RUN_CODE_TOOL_NAME ||
                        log.serverId == com.example.data.mcp.JsSandboxExecutor.RUN_CODE_SERVER_ID
                }
                val mcpToolCallsCount = (conversationToolLogs.size - codeRunsCount).coerceAtLeast(0)
                com.example.data.provider.ModelPriceTable.formatConversationUsageSummary(
                    totalInputTokens = totalIn,
                    totalOutputTokens = totalOut,
                    totalEstimatedCostUsd = totalCost,
                    toolCallsCount = mcpToolCallsCount,
                    codeRunsCount = codeRunsCount,
                    hasEstimatedTokens = hasEstimated
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .testTag("chat_conversation_usage_summary_bar")
            ) {
                Text(
                    text = conversationSummaryText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonospaceFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Active tool turn status indicator banner while loop runs
        if (uiState.isStreaming && !uiState.activeToolLoopStatus.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .testTag("chat_active_tool_turn_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = CyberCyan
                    )
                    Text(
                        text = uiState.activeToolLoopStatus,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonospaceFontFamily),
                        color = CyberCyan,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Floating Minimal Input Bar
        MinimalFloatingComposerBar(
            draft = uiState.draftMessage,
            isStreaming = uiState.isStreaming,
            enterToSend = uiState.enterToSendEnabled,
            editingMessageId = uiState.editingMessageId,
            attachments = currentAttachments,
            onDraftChange = onDraftChange,
            onOpenAttachOptions = onOpenAttachSheet,
            onRemoveAttachment = { idx ->
                if (uiState.attachedMediaList.isNotEmpty()) {
                    onRemoveMediaAttachment(idx)
                } else {
                    onClearMediaAttachments()
                }
            },
            onCancelEdit = onCancelEditMessage,
            onSend = onSendMessage,
            onStop = onStopGeneration
        )
    }
}

@Composable
private fun MinimalEmptyChatState(
    onPickSuggestion: (String) -> Unit
) {
    val suggestions = remember {
        listOf(
            "Review my architecture for edge cases",
            "Draft a clean Kotlin coroutine pipeline",
            "Summarize key tradeoffs in 3 bullets"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        com.example.ui.components.RunwayLogoIcon(size = 44.dp)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Start a conversation",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            suggestions.forEachIndexed { idx, text ->
                MinimalChip(
                    label = text,
                    onClick = { onPickSuggestion(text) },
                    modifier = Modifier.testTag("empty_suggestion_chip_$idx")
                )
            }
        }
    }
}

/**
 * Messages:
 * - No bubbles for the AI (plain text on the #0B0D10 background)
 * - A soft rounded bubble only for user messages (#14171C surface with 1px subtle border)
 */
private data class AssistantResponseIdentity(
    val modelDisplayName: String,
    val rawModelId: String,
    val providerDisplayName: String,
    val isCombo: Boolean
)

private fun resolveAssistantIdentity(
    message: MessageEntity,
    providers: List<ProviderInstanceEntity>,
    models: List<ModelDescriptorEntity>,
    combos: List<OmniRouteComboDefinition>
): AssistantResponseIdentity {
    val rawModelId = message.modelId.ifBlank { "gpt-4o" }
    val matchedCombo = combos.firstOrNull { it.id.equals(rawModelId, ignoreCase = true) }
    val matchedModel = models.firstOrNull {
        it.providerInstanceId == message.providerInstanceId && it.modelId.equals(rawModelId, ignoreCase = true)
    } ?: models.firstOrNull { it.modelId.equals(rawModelId, ignoreCase = true) }

    val matchedProvider = providers.firstOrNull { it.id == message.providerInstanceId }
    val providerDisplayName = when {
        matchedProvider != null && matchedProvider.displayName.isNotBlank() -> matchedProvider.displayName
        rawModelId.startsWith("omniroute/", ignoreCase = true) ||
            message.providerInstanceId.contains("omniroute", ignoreCase = true) -> "OmniRoute Gateway"
        message.providerInstanceId.contains("openai", ignoreCase = true) ||
            rawModelId.startsWith("gpt-", ignoreCase = true) ||
            rawModelId.startsWith("o1", ignoreCase = true) ||
            rawModelId.startsWith("o3", ignoreCase = true) -> "OpenAI"
        message.providerInstanceId.contains("anthropic", ignoreCase = true) ||
            rawModelId.contains("claude", ignoreCase = true) -> "Anthropic"
        message.providerInstanceId.contains("gemini", ignoreCase = true) ||
            rawModelId.contains("gemini", ignoreCase = true) -> "Google Gemini"
        message.providerInstanceId.contains("groq", ignoreCase = true) -> "Groq"
        message.providerInstanceId.contains("openrouter", ignoreCase = true) -> "OpenRouter"
        message.providerInstanceId.contains("deepseek", ignoreCase = true) ||
            rawModelId.contains("deepseek", ignoreCase = true) -> "DeepSeek"
        message.providerInstanceId.isNotBlank() -> message.providerInstanceId
            .removePrefix("preset_")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        else -> "AI Provider"
    }

    val priceEntry = com.example.data.provider.ModelPriceTable.lookupPricing(rawModelId)
    val modelDisplayName = when {
        matchedCombo != null && matchedCombo.displayName.isNotBlank() -> matchedCombo.displayName
        matchedModel != null && matchedModel.displayName.isNotBlank() -> matchedModel.displayName
        priceEntry.modelPattern != "default" -> priceEntry.displayName
        else -> rawModelId.substringAfterLast("/")
    }

    return AssistantResponseIdentity(
        modelDisplayName = modelDisplayName,
        rawModelId = rawModelId,
        providerDisplayName = providerDisplayName,
        isCombo = matchedCombo != null || matchedModel?.isCombo == true || rawModelId.startsWith("omniroute/", ignoreCase = true)
    )
}

@Composable
private fun AssistantResponderHeader(
    message: MessageEntity,
    identity: AssistantResponseIdentity,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("assistant_response_ai_header_${message.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = LucideIcons.Sparkles,
            contentDescription = "AI Responder",
            tint = SoftIndigo,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = identity.modelDisplayName,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        val shortRaw = identity.rawModelId.substringAfterLast("/")
        if (!identity.modelDisplayName.equals(shortRaw, ignoreCase = true) &&
            !identity.modelDisplayName.equals(identity.rawModelId, ignoreCase = true)
        ) {
            Text(
                text = "($shortRaw)",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonospaceFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = SoftIndigo.copy(alpha = 0.14f)
        ) {
            Text(
                text = identity.providerDisplayName,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = SoftIndigo,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (message.isStreaming) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = CyberCyan.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(9.dp),
                        strokeWidth = 1.5.dp,
                        color = CyberCyan
                    )
                    Text(
                        text = "Responding…",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        } else if (message.latencyMs > 0L) {
            Text(
                text = "· ${message.latencyMs}ms",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonospaceFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun MinimalChatMessageItem(
    message: MessageEntity,
    providers: List<ProviderInstanceEntity> = emptyList(),
    models: List<ModelDescriptorEntity> = emptyList(),
    combos: List<OmniRouteComboDefinition> = emptyList(),
    toolLogs: List<ToolCallLogEntity>,
    isLastAssistantMessage: Boolean,
    isTokenCountEstimated: Boolean = false,
    activeToolLoopStatus: String? = null,
    onEditUserMessage: () -> Unit,
    onRegenerate: () -> Unit,
    onCopiedToast: (String) -> Unit,
    onPushToGitHub: (title: String, fileName: String, content: String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val isUser = message.role.equals("user", ignoreCase = true)
    val attachments = remember(message.attachmentUri, message.attachmentMimeType) {
        message.getAttachments()
    }
    val assistantIdentity = remember(message.modelId, message.providerInstanceId, providers, models, combos) {
        resolveAssistantIdentity(
            message = message,
            providers = providers,
            models = models,
            combos = combos
        )
    }
    var fullScreenAttachmentUri by remember { mutableStateOf<String?>(null) }
    var fullScreenVideoUri by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_message_${message.id}"),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            // Soft rounded bubble ONLY for user messages
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clickable(onClick = onEditUserMessage)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (attachments.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            attachments.forEach { media ->
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            if (!media.isVideo) {
                                                fullScreenAttachmentUri = media.uri
                                            } else {
                                                fullScreenVideoUri = media.uri
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (media.isVideo) {
                                        Icon(
                                            imageVector = LucideIcons.Video,
                                            contentDescription = "Video",
                                            tint = SoftIndigo,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        AsyncImage(
                                            model = media.uri,
                                            contentDescription = "Attached photo",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else {
            // Plain text on the background for AI responses (no bubble)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top header showing which AI model and provider is responding to the request
                AssistantResponderHeader(
                    message = message,
                    identity = assistantIdentity
                )

                if (toolLogs.isNotEmpty()) {
                    toolLogs.forEach { log ->
                        CollapsibleToolCallCard(log = log)
                    }
                }

                if (attachments.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        attachments.forEach { media ->
                            if (!media.isVideo) {
                                com.example.ui.components.RunwayReplyImageItem(
                                    altText = media.name ?: "Generated image",
                                    imageUrl = media.uri,
                                    onClick = { fullScreenAttachmentUri = media.uri }
                                )
                            } else {
                                ChatReplyVideoCard(
                                    media = media,
                                    onClick = { fullScreenVideoUri = media.uri }
                                )
                            }
                        }
                    }
                }

                com.example.ui.components.RunwayRichMarkdownRenderer(
                    content = if (message.content.isBlank() && message.isStreaming) "…" else message.content,
                    isStreaming = message.isStreaming,
                    onCopiedToast = onCopiedToast
                )

                if (message.isStreaming && !activeToolLoopStatus.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CyberCyan.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .testTag("message_active_tool_turn_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = CyberCyan
                            )
                            Text(
                                text = activeToolLoopStatus,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonospaceFontFamily),
                                color = CyberCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Per-message usage footer under every assistant reply (input tokens, output tokens, estimated cost)
                if (!message.isStreaming && (message.content.isNotBlank() || toolLogs.isNotEmpty())) {
                    val footerText = remember(
                        message.id,
                        message.promptTokens,
                        message.completionTokens,
                        message.estimatedCostUsd,
                        message.modelId,
                        message.content,
                        isTokenCountEstimated
                    ) {
                        val inTok = if (message.promptTokens > 0) {
                            message.promptTokens
                        } else {
                            com.example.data.provider.ModelPriceTable.estimateTokensFromText(message.content)
                        }
                        val outTok = if (message.completionTokens > 0) {
                            message.completionTokens
                        } else {
                            com.example.data.provider.ModelPriceTable.estimateTokensFromText(message.content)
                        }
                        val estTokensFlag = isTokenCountEstimated || (message.promptTokens == 0 && message.completionTokens == 0)
                        val costVal = if (message.estimatedCostUsd > 0.0) {
                            message.estimatedCostUsd
                        } else {
                            com.example.data.provider.ModelPriceTable.calculateCostUsd(
                                modelId = message.modelId,
                                inputTokens = inTok,
                                outputTokens = outTok
                            )
                        }
                        com.example.data.provider.ModelPriceTable.formatMessageUsageFooter(
                            inputTokens = inTok,
                            outputTokens = outTok,
                            estimatedCostUsd = costVal,
                            isTokenCountEstimated = estTokensFlag
                        )
                    }
                    Text(
                        text = footerText,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonospaceFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .testTag("assistant_message_usage_footer_${message.id}")
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clickable {
                                    clipboard.setText(AnnotatedString(message.content))
                                    onCopiedToast("Copied Markdown")
                                }
                                .testTag("copy_reply_markdown_${message.id}")
                        ) {
                            Icon(
                                imageVector = LucideIcons.Copy,
                                contentDescription = "Copy reply",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        if (isLastAssistantMessage) {
                            Icon(
                                imageVector = LucideIcons.RefreshCw,
                                contentDescription = "Regenerate",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable(onClick = onRegenerate)
                            )
                        }
                    }
                }
            }
        }
    }

    fullScreenAttachmentUri?.let { uri ->
        com.example.ui.components.RunwayFullScreenImageDialog(
            altText = "Attachment",
            imageUrl = uri,
            onDismiss = { fullScreenAttachmentUri = null },
            onToast = onCopiedToast
        )
    }

    fullScreenVideoUri?.let { vidUri ->
        ChatFullScreenVideoDialog(
            videoUri = vidUri,
            onDismiss = { fullScreenVideoUri = null },
            onToast = onCopiedToast
        )
    }
}

@Composable
private fun MinimalComparisonRow(
    responses: List<MessageEntity>,
    providers: List<ProviderInstanceEntity> = emptyList(),
    models: List<ModelDescriptorEntity> = emptyList(),
    combos: List<OmniRouteComboDefinition> = emptyList(),
    estimatedMessageIds: Set<String> = emptySet(),
    onCopiedToast: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        responses.forEach { resp ->
            val identity = remember(resp.modelId, resp.providerInstanceId, providers, models, combos) {
                resolveAssistantIdentity(resp, providers, models, combos)
            }
            MinimalCard(
                modifier = Modifier.width(280.dp),
                contentPadding = PaddingValues(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = LucideIcons.Sparkles,
                                contentDescription = "AI Responder",
                                tint = SoftIndigo,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = identity.modelDisplayName,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = SoftIndigo,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        Text(
                            text = if (resp.isStreaming) "${identity.providerDisplayName} · Responding…" else "${identity.providerDisplayName} · ${identity.rawModelId.substringAfterLast("/")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (resp.isStreaming) CyberCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = LucideIcons.Copy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable {
                                clipboard.setText(AnnotatedString(resp.content))
                                onCopiedToast("Copied Markdown")
                            }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                com.example.ui.components.RunwayRichMarkdownRenderer(
                    content = if (resp.content.isBlank() && resp.isStreaming) "…" else resp.content,
                    isStreaming = resp.isStreaming,
                    onCopiedToast = onCopiedToast
                )
                if (!resp.isStreaming && resp.content.isNotBlank()) {
                    val footer = com.example.data.provider.ModelPriceTable.formatMessageUsageFooter(
                        inputTokens = resp.promptTokens.takeIf { it > 0 }
                            ?: com.example.data.provider.ModelPriceTable.estimateTokensFromText(resp.content),
                        outputTokens = resp.completionTokens.takeIf { it > 0 }
                            ?: com.example.data.provider.ModelPriceTable.estimateTokensFromText(resp.content),
                        estimatedCostUsd = resp.estimatedCostUsd.takeIf { it > 0.0 }
                            ?: com.example.data.provider.ModelPriceTable.calculateCostUsd(
                                resp.modelId,
                                resp.promptTokens.coerceAtLeast(1),
                                resp.completionTokens.coerceAtLeast(1)
                            ),
                        isTokenCountEstimated = resp.id in estimatedMessageIds || (resp.promptTokens == 0 && resp.completionTokens == 0)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = footer,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonospaceFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Input bar: a single rounded floating field with a "+" button on the left
 * (the attach image, video and enhance options open from it) and an accent send button on the right.
 */
@Composable
private fun MinimalFloatingComposerBar(
    draft: String,
    isStreaming: Boolean,
    enterToSend: Boolean,
    editingMessageId: String?,
    attachments: List<ChatMediaAttachment>,
    onDraftChange: (String) -> Unit,
    onOpenAttachOptions: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onCancelEdit: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (editingMessageId != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Editing message",
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftIndigo
                )
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onCancelEdit)
                )
            }
        }

        // Attached media strip
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEachIndexed { index, item ->
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    ) {
                        if (item.isVideo) {
                            Icon(
                                imageVector = LucideIcons.Video,
                                contentDescription = "Video",
                                tint = SoftIndigo,
                                modifier = Modifier
                                    .size(20.dp)
                                    .align(Alignment.Center)
                            )
                        } else {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = "Attachment",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                                .clickable { onRemoveAttachment(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = LucideIcons.X,
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // Single rounded floating field
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // "+" button on the left
                IconButton(
                    onClick = onOpenAttachOptions,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("composer_plus_button")
                ) {
                    Icon(
                        imageVector = LucideIcons.Plus,
                        contentDescription = "Add attachment or enhance",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Clean text input
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .testTag("chat_composer_input"),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = "Message…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(SoftIndigo),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (draft.isNotBlank() || attachments.isNotEmpty()) {
                                    focusManager.clearFocus()
                                    onSend()
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Accent send / stop button on the right
                val canSend = draft.isNotBlank() || attachments.isNotEmpty()
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                Surface(
                    onClick = {
                        if (isStreaming) {
                            onStop()
                        } else if (canSend) {
                            focusManager.clearFocus()
                            onSend()
                        }
                    },
                    enabled = isStreaming || canSend,
                    shape = CircleShape,
                    color = if (isStreaming || canSend) SoftIndigo else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("chat_send_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isStreaming) LucideIcons.Square else LucideIcons.ArrowUp,
                            contentDescription = if (isStreaming) "Stop" else "Send",
                            tint = if (isStreaming || canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalToolRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) SoftIndigo.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) SoftIndigo else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isActive) SoftIndigo else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MinimalConversationDrawerContent(
    conversations: List<ConversationEntity>,
    activeConversationId: String?,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onRenameConversation: (ConversationEntity) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onExportConversation: (String, Boolean) -> Unit
) {
    val filtered = remember(conversations, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) conversations
        else conversations.filter { it.title.lowercase().contains(q) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                com.example.ui.components.RunwayLogoIcon(size = 28.dp)
                com.example.ui.components.RunwayWordmark()
            }
            MinimalButton(
                text = "New",
                icon = LucideIcons.Plus,
                onClick = onNewConversation,
                modifier = Modifier.testTag("drawer_new_chat_button")
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = {
                Text(
                    "Search…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = LucideIcons.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filtered, key = { it.id }) { conv ->
                val isSelected = conv.id == activeConversationId
                Surface(
                    onClick = { onSelectConversation(conv.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) SoftIndigo.copy(alpha = 0.14f) else Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = conv.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) SoftIndigo else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = conv.modelId.substringAfterLast("/"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = LucideIcons.Edit2,
                                contentDescription = "Rename",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { onRenameConversation(conv) }
                            )
                            Icon(
                                imageVector = LucideIcons.Trash2,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { onDeleteConversation(conv.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatReplyVideoCard(
    media: ChatMediaAttachment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("reply_inline_video")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = SoftIndigo.copy(alpha = 0.85f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = LucideIcons.Play,
                        contentDescription = "Play Video",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = LucideIcons.Video,
                        contentDescription = null,
                        tint = SoftIndigo,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "VIDEO CLIP",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(
            text = media.name?.takeIf { it.isNotBlank() } ?: "Generated Video (Tap to play)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun ChatFullScreenVideoDialog(
    videoUri: String,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = LucideIcons.Video,
                            contentDescription = null,
                            tint = SoftIndigo,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "AI Video Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = LucideIcons.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true
                            }
                        },
                        update = { view ->
                            view.player = exoPlayer
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.example.ui.components.MinimalButton(
                        text = "Copy link",
                        icon = LucideIcons.Copy,
                        onClick = {
                            clipboard.setText(AnnotatedString(videoUri))
                            onToast("Video link copied to clipboard")
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    com.example.ui.components.MinimalButton(
                        text = "Close",
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

