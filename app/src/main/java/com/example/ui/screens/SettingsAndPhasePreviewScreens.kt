package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import com.example.data.local.ClonedRepoEntity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.CapabilityTag
import com.example.data.local.IntegrationAuditLogEntity
import com.example.data.local.McpApprovalMode
import com.example.data.local.McpServerEntity
import com.example.data.local.McpToolEntity
import com.example.data.local.McpTransportType
import com.example.data.local.ModelDescriptorEntity
import com.example.data.local.ProviderInstanceEntity
import com.example.data.local.ProviderPresetType
import com.example.data.local.SkillEntity
import com.example.data.local.ToolCallLogEntity
import com.example.data.provider.RequestDiagnosticEntry
import com.example.ui.components.BadgePill
import com.example.ui.components.IntegrationsSettingsSection
import com.example.ui.components.LucideIcons
import com.example.ui.components.MinimalButton
import com.example.ui.components.MinimalCard
import com.example.ui.components.MinimalChip
import com.example.ui.components.MinimalSectionHeader
import com.example.ui.components.MinimalStatusDot
import com.example.ui.components.MinimalToggle
import com.example.ui.components.SkillsAndMcpSection
import com.example.ui.theme.CoralError
import com.example.ui.theme.SoftIndigo
import com.example.ui.viewmodel.OmniUiState

@Composable
fun SettingsWorkspaceScreen(
    uiState: OmniUiState,
    providers: List<ProviderInstanceEntity>,
    models: List<ModelDescriptorEntity>,
    diagnosticEntries: List<RequestDiagnosticEntry> = emptyList(),
    onClearDiagnostics: () -> Unit = {},
    onRunOmniRouteCleanTerminal: (String?) -> Unit = {},
    onSelectDefaultChatModel: (ProviderInstanceEntity, String) -> Unit = { _, _ -> },
    onSaveProvider: (
        existingId: String?,
        preset: ProviderPresetType,
        displayName: String,
        baseUrl: String,
        rawApiKey: String?,
        capabilities: Set<CapabilityTag>,
        isEnabled: Boolean,
        defaultChatModel: String,
        defaultImageModel: String,
        defaultVideoModel: String
    ) -> Unit,
    onDuplicateProvider: (ProviderInstanceEntity) -> Unit,
    onSaveProviderApiKeyOnly: (ProviderInstanceEntity, String) -> Unit,
    onClearProviderApiKey: (ProviderInstanceEntity) -> Unit,
    onUpdateProviderBaseUrl: (ProviderInstanceEntity, String) -> Unit,
    onToggleProviderEnabled: (ProviderInstanceEntity) -> Unit,
    onUpdateProviderCapabilities: (ProviderInstanceEntity, Set<CapabilityTag>) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSetDefaultForFeature: (providerId: String, capability: CapabilityTag) -> Unit,
    onTestConnectionOnly: (ProviderInstanceEntity, String?, String?) -> Unit,
    onTestAndFetchModels: (ProviderInstanceEntity, String?, String?) -> Unit,
    onAddManualModel: (providerId: String, modelId: String, isCombo: Boolean) -> Unit,
    onToggleFavoriteModel: (ModelDescriptorEntity) -> Unit,
    onApproveCleartextHostManually: (String) -> Unit,
    onRevokeCleartextHost: (String) -> Unit,
    onSetThemeMode: (String) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetBiometricGate: (Boolean) -> Unit,
    onSetEnterToSend: (Boolean) -> Unit,
    onSetReadRepliesAloud: (Boolean) -> Unit = {},
    onSetSpendCurrency: (String) -> Unit = {},
    onOpenOnboardingSheet: () -> Unit,
    onExportSettings: (includeKeys: Boolean) -> Unit,
    onImportSettings: (String) -> Unit,
    onClearAllData: () -> Unit,
    onOpenConnectors: () -> Unit = {}
) {
    MinimalSettingsAndThemeScreen(
        uiState = uiState,
        onSetThemeMode = onSetThemeMode,
        onSetBiometricGate = onSetBiometricGate,
        onSetEnterToSend = onSetEnterToSend,
        onSetReadRepliesAloud = onSetReadRepliesAloud,
        onSetSpendCurrency = onSetSpendCurrency,
        onOpenOnboardingSheet = onOpenOnboardingSheet,
        onExportSettings = onExportSettings,
        onClearAllData = onClearAllData,
        onOpenConnectors = onOpenConnectors
    )
}

/**
 * Clean, minimal Settings & Theme panel used inside the More tab.
 */
@Composable
fun MinimalSettingsAndThemeScreen(
    uiState: OmniUiState,
    onSetThemeMode: (String) -> Unit,
    onSetBiometricGate: (Boolean) -> Unit,
    onSetEnterToSend: (Boolean) -> Unit,
    onSetReadRepliesAloud: (Boolean) -> Unit = {},
    onSetSpendCurrency: (String) -> Unit,
    onOpenOnboardingSheet: () -> Unit,
    onExportSettings: (includeKeys: Boolean) -> Unit,
    onClearAllData: () -> Unit,
    onOpenConnectors: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isLightMode = uiState.themeMode.equals("LIGHT", ignoreCase = true)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("minimal_settings_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    com.example.ui.components.RunwayLogoIcon(size = 32.dp)
                    com.example.ui.components.RunwayWordmark()
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Theme Card
        item {
            MinimalSectionHeader(title = "Appearance")
            Spacer(modifier = Modifier.height(8.dp))
            MinimalCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isLightMode) LucideIcons.Sun else LucideIcons.Moon,
                                contentDescription = "Theme",
                                tint = SoftIndigo,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = "Light theme",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isLightMode) "Light surfaces active" else "Near-black #0B0D10 active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        MinimalToggle(
                            checked = isLightMode,
                            onCheckedChange = { light ->
                                onSetThemeMode(if (light) "LIGHT" else "DARK")
                            },
                            testTag = "theme_mode_toggle"
                        )
                    }
                }
            }
        }

        // Preferences Card
        item {
            MinimalSectionHeader(title = "Preferences")
            Spacer(modifier = Modifier.height(8.dp))
            MinimalCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Read replies aloud",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Speak assistant responses aloud using TextToSpeech",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MinimalToggle(
                            checked = uiState.readRepliesAloud,
                            onCheckedChange = onSetReadRepliesAloud,
                            testTag = "read_replies_aloud_toggle"
                        )
                    }

                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enter to send",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Send chat messages with keyboard return key",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MinimalToggle(
                            checked = uiState.enterToSendEnabled,
                            onCheckedChange = onSetEnterToSend,
                            testTag = "enter_to_send_toggle"
                        )
                    }

                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Biometric vault lock",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Require authentication on launch",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MinimalToggle(
                            checked = uiState.biometricGateEnabled,
                            onCheckedChange = onSetBiometricGate,
                            testTag = "biometric_gate_toggle"
                        )
                    }

                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Currency",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Display usage cost in USD or INR",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("USD", "INR").forEach { curr ->
                                MinimalChip(
                                    label = curr,
                                    selected = uiState.spendCurrency == curr,
                                    onClick = { onSetSpendCurrency(curr) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Connectors Section Card (Reachable from Settings)
        item {
            MinimalSectionHeader(title = "Connectors")
            Spacer(modifier = Modifier.height(8.dp))
            MinimalCard(
                onClick = onOpenConnectors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_open_connectors_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = LucideIcons.Plug,
                            contentDescription = "Connectors",
                            tint = SoftIndigo,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Connectors",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Manage MCP connectors, GitHub preset, and Always / Ask / Never tool rules",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = LucideIcons.ChevronRight,
                        contentDescription = "Open Connectors",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Workspace Actions Card
        item {
            MinimalSectionHeader(title = "Workspace")
            Spacer(modifier = Modifier.height(8.dp))
            MinimalCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val mig = uiState.runwayMigrationStatus
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("runway_migration_status_row"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Runway local storage (v${mig.schemaVersion})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${mig.migratedConversationsCount} chats · ${mig.migratedMessageNodesCount} tree nodes · ${mig.seededPromptsCount} prompts · ${mig.seededPresetsCount} presets",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MinimalStatusDot(isConnected = mig.isVerified)
                            Text(
                                text = if (mig.isVerified) "Verified" else "Migrating",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MinimalButton(
                            text = "OmniRoute setup",
                            onClick = onOpenOnboardingSheet,
                            filled = false,
                            modifier = Modifier.weight(1f)
                        )
                        MinimalButton(
                            text = "Export config",
                            onClick = { onExportSettings(false) },
                            filled = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    MinimalButton(
                        text = "Clear workspace data",
                        onClick = onClearAllData,
                        icon = LucideIcons.Trash,
                        filled = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Minimal Connect screen: a clean list of services with a status dot (green or gray)
 * and a Connect or Disconnect button.
 */
@Composable
fun IntegrationsWorkspaceScreen(
    uiState: OmniUiState,
    skills: List<SkillEntity>,
    mcpServers: List<McpServerEntity>,
    mcpTools: List<McpToolEntity>,
    toolCallLogs: List<ToolCallLogEntity>,
    integrationLogs: List<IntegrationAuditLogEntity>,
    onSaveSkillMarkdown: (existingId: String?, rawMarkdown: String, fallbackName: String, fallbackDesc: String, sourceUrl: String) -> Unit,
    onToggleSkillEnabled: (SkillEntity) -> Unit,
    onDeleteSkill: (String) -> Unit,
    onImportSkillFromUrl: (String) -> Unit,
    onImportSkillFromUri: (String) -> Unit,
    onSaveMcpServer: (
        existingId: String?,
        name: String,
        url: String,
        transport: McpTransportType,
        authHeaderName: String,
        rawToken: String?,
        approvalMode: McpApprovalMode,
        isEnabled: Boolean
    ) -> Unit,
    onToggleMcpServerEnabled: (McpServerEntity) -> Unit,
    onUpdateMcpServerApprovalMode: (McpServerEntity, McpApprovalMode) -> Unit,
    onClearMcpServerToken: (McpServerEntity) -> Unit,
    onDeleteMcpServer: (String) -> Unit,
    onTestAndDiscoverMcpServer: (McpServerEntity, String?) -> Unit,
    onToggleMcpToolEnabled: (McpToolEntity) -> Unit,
    onClearToolCallLogs: () -> Unit,
    onSaveGitHubPat: (String) -> Unit,
    onClearGitHubPat: () -> Unit,
    onVerifyGitHub: (String?) -> Unit,
    onOpenQuickGitHubGist: () -> Unit,
    onSaveYouTubeCredentials: (clientId: String, accessToken: String) -> Unit,
    onClearYouTubeCredentials: () -> Unit,
    onRegeneratePkce: () -> Unit,
    onExchangePkceCode: (clientId: String, authCode: String) -> Unit,
    onVerifyYouTubeChannel: (String?) -> Unit,
    onClearIntegrationLogs: () -> Unit,
    onOpenSafetyApprovalSheet: () -> Unit = {},
    onOpenConnectors: () -> Unit = {},
    clonedRepos: List<ClonedRepoEntity> = emptyList(),
    onOpenGitClone: () -> Unit = {},
    onSyncClonedRepo: (String) -> Unit = {},
    onDeleteClonedRepo: (String) -> Unit = {},
    onExploreRepoFiles: (ClonedRepoEntity) -> Unit = {},
    onActivateRepoSkillsInChat: (ClonedRepoEntity) -> Unit = {}
) {
    var showGitHubDialog by rememberSaveable { mutableStateOf(false) }
    var showYouTubeDialog by rememberSaveable { mutableStateOf(false) }
    var showAddMcpDialog by rememberSaveable { mutableStateOf(false) }
    var showMcpStudioSheet by rememberSaveable { mutableStateOf(false) }
    var showAdvancedHub by rememberSaveable { mutableStateOf(false) }

    val isGitHubConnected = !uiState.maskedGitHubPat.isNullOrBlank()
    val isYouTubeConnected = !uiState.maskedYouTubeAccessToken.isNullOrBlank()

    if (showMcpStudioSheet) {
        com.example.ui.components.McpSystemStudioSheet(
            onDismiss = { showMcpStudioSheet = false },
            onPersistServerToWorkspace = { name, url, transport, authHeaderName, rawToken, approvalMode ->
                onSaveMcpServer(
                    null,
                    name,
                    url,
                    transport,
                    authHeaderName,
                    rawToken,
                    approvalMode,
                    true
                )
            }
        )
    }

    if (showGitHubDialog) {
        var patInput by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGitHubDialog = false },
            title = { Text("Connect GitHub", style = MaterialTheme.typography.titleLarge) },
            text = {
                OutlinedTextField(
                    value = patInput,
                    onValueChange = { patInput = it },
                    placeholder = { Text("ghp_...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                MinimalButton(
                    text = "Connect",
                    onClick = {
                        onSaveGitHubPat(patInput)
                        onVerifyGitHub(patInput)
                        showGitHubDialog = false
                    },
                    enabled = patInput.isNotBlank(),
                    filled = true
                )
            },
            dismissButton = {
                TextButton(onClick = { showGitHubDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showYouTubeDialog) {
        var clientIdInput by rememberSaveable { mutableStateOf(uiState.youTubeClientId) }
        var tokenInput by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showYouTubeDialog = false },
            title = { Text("Connect YouTube", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = clientIdInput,
                        onValueChange = { clientIdInput = it },
                        placeholder = { Text("OAuth Client ID") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        placeholder = { Text("OAuth Access Token (ya29...)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                MinimalButton(
                    text = "Connect",
                    onClick = {
                        onSaveYouTubeCredentials(clientIdInput, tokenInput)
                        onVerifyYouTubeChannel(tokenInput)
                        showYouTubeDialog = false
                    },
                    enabled = tokenInput.isNotBlank(),
                    filled = true
                )
            },
            dismissButton = {
                TextButton(onClick = { showYouTubeDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddMcpDialog) {
        var mcpName by rememberSaveable { mutableStateOf("") }
        var mcpUrl by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddMcpDialog = false },
            title = { Text("Add MCP Server", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = mcpName,
                        onValueChange = { mcpName = it },
                        placeholder = { Text("Server name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mcpUrl,
                        onValueChange = { mcpUrl = it },
                        placeholder = { Text("https://example.com/mcp") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                MinimalButton(
                    text = "Connect",
                    onClick = {
                        onSaveMcpServer(
                            null,
                            mcpName.trim(),
                            mcpUrl.trim(),
                            McpTransportType.STREAMABLE_HTTP,
                            "Authorization",
                            null,
                            McpApprovalMode.ASK_EVERY_TIME,
                            true
                        )
                        showAddMcpDialog = false
                    },
                    enabled = mcpName.isNotBlank() && mcpUrl.isNotBlank(),
                    filled = true
                )
            },
            dismissButton = {
                TextButton(onClick = { showAddMcpDialog = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .testTag("integrations_workspace_screen")
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connect",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MinimalChip(
                        label = "Connectors",
                        selected = true,
                        onClick = onOpenConnectors,
                        testTag = "connect_tab_open_connectors_chip"
                    )
                    MinimalChip(
                        label = ".mcp Studio",
                        selected = showMcpStudioSheet,
                        onClick = { showMcpStudioSheet = true },
                        testTag = "connect_tab_open_mcp_studio_chip"
                    )
                    MinimalChip(
                        label = "+ Clone Repo",
                        selected = false,
                        onClick = onOpenGitClone,
                        testTag = "connect_tab_clone_repo_chip"
                    )
                    MinimalChip(
                        label = "+ MCP",
                        selected = false,
                        onClick = { showAddMcpDialog = true }
                    )
                    MinimalChip(
                        label = if (showAdvancedHub) "Simple view" else "Manage skills",
                        selected = showAdvancedHub,
                        onClick = { showAdvancedHub = !showAdvancedHub }
                    )
                }
            }
        }

        // 1. GitHub Service Row
        item {
            ConnectServiceRow(
                name = "GitHub",
                subtitle = if (isGitHubConnected) {
                    uiState.gitHubProfileSummary
                        .replace(" (null)", "")
                        .replace("(null)", "")
                        .replace(" null", "")
                        .ifBlank { "Connected (${uiState.gitHubRepos.size} repos)" }
                } else {
                    "Gists & repository commits"
                },
                isConnected = isGitHubConnected,
                onAction = {
                    if (isGitHubConnected) {
                        onClearGitHubPat()
                    } else {
                        showGitHubDialog = true
                    }
                },
                testTag = "connect_service_github"
            )
        }

        // 2. Cloned Git Repositories & Extracted Agent Skills
        item {
            com.example.ui.components.ClonedReposSection(
                clonedRepos = clonedRepos,
                allSkills = skills,
                onCloneNewRepo = onOpenGitClone,
                onSyncRepo = onSyncClonedRepo,
                onDeleteRepo = onDeleteClonedRepo,
                onExploreFiles = onExploreRepoFiles,
                onActivateSkillsInChat = onActivateRepoSkillsInChat,
                onToggleSkillEnabled = onToggleSkillEnabled
            )
        }

        // 3. YouTube Studio Service Row
        item {
            ConnectServiceRow(
                name = "YouTube",
                subtitle = if (isYouTubeConnected) {
                    uiState.youTubeChannelSummary.ifBlank { "Connected" }
                } else {
                    "Direct video publishing via OAuth 2.0"
                },
                isConnected = isYouTubeConnected,
                onAction = {
                    if (isYouTubeConnected) {
                        onClearYouTubeCredentials()
                    } else {
                        showYouTubeDialog = true
                    }
                },
                testTag = "connect_service_youtube"
            )
        }

        // 3. MCP Servers List
        mcpServers.forEach { server ->
            item(key = "mcp_${server.id}") {
                ConnectServiceRow(
                    name = server.name,
                    subtitle = server.url,
                    isConnected = server.isEnabled,
                    onAction = { onToggleMcpServerEnabled(server) },
                    testTag = "connect_service_mcp_${server.id}"
                )
            }
        }

        // 4. Skills List
        skills.forEach { skill ->
            item(key = "skill_${skill.id}") {
                ConnectServiceRow(
                    name = skill.name,
                    subtitle = skill.description.ifBlank { "SKILL.md instruction pack" },
                    isConnected = skill.isEnabled,
                    onAction = { onToggleSkillEnabled(skill) },
                    testTag = "connect_service_skill_${skill.id}"
                )
            }
        }

        // Expandable full Skills & MCP / Integrations manager if user taps "Manage skills"
        if (showAdvancedHub) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SkillsAndMcpSection(
                    uiState = uiState,
                    skills = skills,
                    mcpServers = mcpServers,
                    mcpTools = mcpTools,
                    toolCallLogs = toolCallLogs,
                    onSaveSkillMarkdown = onSaveSkillMarkdown,
                    onToggleSkillEnabled = onToggleSkillEnabled,
                    onDeleteSkill = onDeleteSkill,
                    onImportSkillFromUrl = onImportSkillFromUrl,
                    onImportSkillFromUri = onImportSkillFromUri,
                    onSaveMcpServer = onSaveMcpServer,
                    onToggleMcpServerEnabled = onToggleMcpServerEnabled,
                    onUpdateMcpServerApprovalMode = onUpdateMcpServerApprovalMode,
                    onClearMcpServerToken = onClearMcpServerToken,
                    onDeleteMcpServer = onDeleteMcpServer,
                    onTestAndDiscoverMcpServer = onTestAndDiscoverMcpServer,
                    onToggleMcpToolEnabled = onToggleMcpToolEnabled,
                    onClearToolCallLogs = onClearToolCallLogs,
                    onOpenSafetyApprovalSheet = onOpenSafetyApprovalSheet
                )
            }

            item {
                IntegrationsSettingsSection(
                    uiState = uiState,
                    integrationLogs = integrationLogs,
                    onSaveGitHubPat = onSaveGitHubPat,
                    onClearGitHubPat = onClearGitHubPat,
                    onVerifyGitHub = onVerifyGitHub,
                    onOpenQuickGitHubGist = onOpenQuickGitHubGist,
                    onSaveYouTubeCredentials = onSaveYouTubeCredentials,
                    onClearYouTubeCredentials = onClearYouTubeCredentials,
                    onRegeneratePkce = onRegeneratePkce,
                    onExchangePkceCode = onExchangePkceCode,
                    onVerifyYouTubeChannel = onVerifyYouTubeChannel,
                    onClearIntegrationLogs = onClearIntegrationLogs
                )
            }
        }
    }
}

@Composable
private fun ConnectServiceRow(
    name: String,
    subtitle: String,
    isConnected: Boolean,
    onAction: () -> Unit,
    testTag: String
) {
    MinimalCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                MinimalStatusDot(isConnected = isConnected)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle.replace(" (null)", "").replace("(null)", "").replace(" null", ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Surface(
                onClick = onAction,
                shape = CircleShape,
                color = if (isConnected) MaterialTheme.colorScheme.background else SoftIndigo,
                border = if (isConnected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                modifier = Modifier.height(36.dp)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isConnected) "Disconnect" else "Connect",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = if (isConnected) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun PhasePreviewScreen(
    modalityTitle: String,
    phaseLabel: String,
    description: String,
    configuredProviders: List<ProviderInstanceEntity>,
    onGoToSettings: () -> Unit,
    onGoToChat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        MinimalCard(modifier = Modifier.widthIn(max = 520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BadgePill(text = phaseLabel, color = SoftIndigo)
                Text(text = modalityTitle, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MinimalButton(
                        text = "Configure",
                        onClick = onGoToSettings,
                        filled = false,
                        modifier = Modifier.weight(1f)
                    )
                    MinimalButton(
                        text = "Back to Chat",
                        onClick = onGoToChat,
                        filled = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
