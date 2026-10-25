package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.data.local.ProviderInstanceEntity
import com.example.data.local.ProviderPresetType
import com.example.data.provider.ProviderHealth
import com.example.ui.components.LucideIcons
import com.example.ui.components.MinimalButton
import com.example.ui.components.MinimalCard
import com.example.ui.components.MinimalChip
import com.example.ui.components.MinimalStatusDot
import com.example.ui.components.MinimalToggle
import com.example.ui.theme.CoralError
import com.example.ui.theme.SoftIndigo
import com.example.ui.viewmodel.ProvidersUiState
import com.example.ui.viewmodel.ProvidersViewModel

/**
 * Minimal Keys screen: a clean list of providers, masked keys, an edit icon, and a "Test" button.
 * Never shows the full key.
 */
@Composable
fun ProvidersScreen(
    uiState: ProvidersUiState,
    viewModel: ProvidersViewModel,
    modifier: Modifier = Modifier
) {
    val filteredProviders = remember(uiState.providers, uiState.selectedFilter, uiState.searchQuery) {
        uiState.providers.filter { prov ->
            val matchesSearch = uiState.searchQuery.isBlank() ||
                prov.displayName.contains(uiState.searchQuery, ignoreCase = true) ||
                prov.presetType.contains(uiState.searchQuery, ignoreCase = true) ||
                prov.baseUrl.contains(uiState.searchQuery, ignoreCase = true)

            val matchesFilter = when (uiState.selectedFilter) {
                "ENABLED" -> prov.isEnabled
                "CHAT" -> CapabilityTag.CHAT in prov.capabilitySet()
                "IMAGE" -> CapabilityTag.IMAGE in prov.capabilitySet()
                "VIDEO" -> CapabilityTag.VIDEO in prov.capabilitySet()
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    if (uiState.showAddProviderDialog || uiState.editingProvider != null) {
        AddEditProviderDialog(
            initialProvider = uiState.editingProvider,
            maskedKey = uiState.editingProvider?.let { uiState.maskedKeysByProvider[it.id] },
            onDismiss = {
                viewModel.closeAddProviderDialog()
                viewModel.closeEditProviderDialog()
            },
            onSave = { existingId, preset, name, url, key, caps, enabled, chatModel, imgModel, vidModel ->
                viewModel.saveProviderInstance(
                    existingId = existingId,
                    preset = preset,
                    displayName = name,
                    baseUrl = url,
                    rawApiKey = key,
                    capabilities = caps,
                    isEnabled = enabled,
                    defaultChatModel = chatModel,
                    defaultImageModel = imgModel,
                    defaultVideoModel = vidModel
                )
            }
        )
    }

    uiState.quickEditKeyProvider?.let { targetProv ->
        QuickEditApiKeyDialog(
            provider = targetProv,
            currentMaskedKey = uiState.maskedKeysByProvider[targetProv.id],
            onDismiss = viewModel::closeQuickEditKeyDialog,
            onSave = { newRawKey ->
                viewModel.saveApiKey(targetProv.id, newRawKey)
            },
            onClear = {
                viewModel.clearApiKey(targetProv.id)
                viewModel.closeQuickEditKeyDialog()
            }
        )
    }

    if (uiState.showVaultDetailsDialog) {
        TinkVaultDetailsDialog(
            status = uiState.vaultSecurityStatus,
            onDismiss = viewModel::closeVaultDetailsDialog
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("providers_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Keys",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${uiState.vaultSecurityStatus.encryptedKeyCount} encrypted · AES-256-GCM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable(onClick = viewModel::openVaultDetailsDialog)
                            .testTag("tink_vault_security_card")
                    )
                }

                MinimalButton(
                    text = "Add provider",
                    onClick = viewModel::openAddProviderDialog,
                    icon = LucideIcons.Plus,
                    filled = false,
                    testTag = "add_provider_button"
                )
            }
        }

        items(filteredProviders, key = { it.id }) { provider ->
            val maskedKey = uiState.maskedKeysByProvider[provider.id]
            val health = uiState.providerHealthById[provider.id] ?: ProviderHealth.Unknown
            val isBusyTesting = uiState.busyTestingProviderId == provider.id

            ProviderCardItem(
                provider = provider,
                maskedKey = maskedKey,
                health = health,
                isBusyTesting = isBusyTesting,
                onToggleEnabled = { viewModel.toggleProviderEnabled(provider) },
                onEditFull = { viewModel.openEditProviderDialog(provider) },
                onQuickEditKey = { viewModel.openQuickEditKeyDialog(provider) },
                onTestConnection = { viewModel.testConnection(provider) }
            )
        }
    }
}

@Composable
private fun ProviderCardItem(
    provider: ProviderInstanceEntity,
    maskedKey: String?,
    health: ProviderHealth,
    isBusyTesting: Boolean,
    onToggleEnabled: () -> Unit,
    onEditFull: () -> Unit,
    onQuickEditKey: () -> Unit,
    onTestConnection: () -> Unit
) {
    val hasKey = !maskedKey.isNullOrBlank()
    val safeMaskedDisplay = remember(maskedKey) {
        if (maskedKey.isNullOrBlank()) {
            "••••••••••••"
        } else {
            val suffix = maskedKey.takeLast(4)
            "••••••••$suffix"
        }
    }

    MinimalCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provider_card_${provider.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Status dot + Provider Name + Masked Key
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onEditFull)
            ) {
                MinimalStatusDot(isConnected = provider.isEnabled && (hasKey || provider.presetEnum() == ProviderPresetType.OMNIROUTE))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (hasKey) safeMaskedDisplay else "No key set",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasKey) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        when (health) {
                            is ProviderHealth.Healthy -> {
                                Text(
                                    text = "· ${health.latencyMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SoftIndigo
                                )
                            }
                            is ProviderHealth.Unhealthy -> {
                                Text(
                                    text = "· Failed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CoralError
                                )
                            }
                            else -> Unit
                        }
                    }
                }
            }

            // Right: Edit icon + "Test" button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onQuickEditKey,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("edit_key_button_${provider.id}")
                ) {
                    Icon(
                        imageVector = LucideIcons.Edit,
                        contentDescription = "Edit Key",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Surface(
                    onClick = onTestConnection,
                    enabled = !isBusyTesting,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .height(36.dp)
                        .testTag("test_ping_button_${provider.id}")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isBusyTesting) "..." else "Test",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickEditApiKeyDialog(
    provider: ProviderInstanceEntity,
    currentMaskedKey: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit
) {
    var rawKey by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = provider.displayName,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!currentMaskedKey.isNullOrBlank()) {
                    Text(
                        text = "Current: ••••••••${currentMaskedKey.takeLast(4)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = rawKey,
                    onValueChange = { rawKey = it },
                    placeholder = { Text("Enter secret key...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_edit_api_key_input")
                )
                Text(
                    text = "Encrypted with AES-256-GCM before saving. Full keys are never displayed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            MinimalButton(
                text = "Save",
                onClick = { onSave(rawKey) },
                enabled = rawKey.isNotBlank(),
                filled = true,
                testTag = "confirm_save_quick_key_button"
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!currentMaskedKey.isNullOrBlank()) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("clear_key_button_${provider.id}")
                    ) {
                        Text("Clear", color = CoralError)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun AddEditProviderDialog(
    initialProvider: ProviderInstanceEntity?,
    maskedKey: String?,
    onDismiss: () -> Unit,
    onSave: (
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
    ) -> Unit
) {
    var selectedPreset by rememberSaveable {
        mutableStateOf(initialProvider?.presetEnum() ?: ProviderPresetType.OPENAI)
    }
    var displayName by rememberSaveable {
        mutableStateOf(initialProvider?.displayName ?: selectedPreset.displayName)
    }
    var baseUrl by rememberSaveable {
        mutableStateOf(initialProvider?.baseUrl ?: selectedPreset.defaultBaseUrl)
    }
    var rawApiKey by rememberSaveable { mutableStateOf("") }
    var isEnabled by rememberSaveable { mutableStateOf(initialProvider?.isEnabled ?: true) }
    var capabilities by rememberSaveable {
        mutableStateOf(initialProvider?.capabilitySet() ?: setOf(CapabilityTag.CHAT, CapabilityTag.IMAGE))
    }
    var defaultChatModel by rememberSaveable {
        mutableStateOf(initialProvider?.defaultChatModelId ?: "gpt-4o")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialProvider != null) initialProvider.displayName else "Add Provider",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ProviderPresetType.values().forEach { preset ->
                        MinimalChip(
                            label = preset.displayName,
                            selected = selectedPreset == preset,
                            onClick = {
                                selectedPreset = preset
                                if (initialProvider == null) {
                                    displayName = preset.displayName
                                    baseUrl = preset.defaultBaseUrl
                                    defaultChatModel = when (preset) {
                                        ProviderPresetType.OPENAI -> "gpt-4o"
                                        ProviderPresetType.ANTHROPIC -> "claude-3-5-sonnet-20241022"
                                        ProviderPresetType.GEMINI -> "gemini-1.5-pro"
                                        ProviderPresetType.OMNIROUTE -> "omniroute/auto-combo"
                                        else -> "default-model"
                                    }
                                }
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = defaultChatModel,
                    onValueChange = { defaultChatModel = it },
                    label = { Text("Default Model ID") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = rawApiKey,
                    onValueChange = { rawApiKey = it },
                    label = { Text(if (maskedKey != null) "API Key (leave empty to keep)" else "API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                    MinimalToggle(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it }
                    )
                }
            }
        },
        confirmButton = {
            MinimalButton(
                text = "Save",
                onClick = {
                    onSave(
                        initialProvider?.id,
                        selectedPreset,
                        displayName,
                        baseUrl,
                        rawApiKey.takeIf { it.isNotBlank() },
                        capabilities,
                        isEnabled,
                        defaultChatModel,
                        initialProvider?.defaultImageModelId ?: "",
                        initialProvider?.defaultVideoModelId ?: ""
                    )
                },
                enabled = displayName.isNotBlank() && baseUrl.isNotBlank(),
                filled = true
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun TinkVaultDetailsDialog(
    status: com.example.data.security.VaultSecurityStatus,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Keystore Vault", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Google Tink AES-256-GCM AEAD",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Master Key: ${status.masterKeyUri}\nEncrypted Keys: ${status.encryptedKeyCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            MinimalButton(text = "Done", onClick = onDismiss, filled = true)
        }
    )
}
