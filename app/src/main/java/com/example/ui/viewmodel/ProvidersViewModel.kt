package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.CapabilityTag
import com.example.data.local.ModelDescriptorEntity
import com.example.data.local.OmniDao
import com.example.data.local.ProviderInstanceEntity
import com.example.data.local.ProviderPresetType
import com.example.data.provider.ModelCapabilityRegistry
import com.example.data.provider.ProviderHealth
import com.example.data.repository.OmniRepository
import com.example.data.security.TinkSecretVault
import com.example.data.security.VaultSecurityStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ProvidersUiState(
    val providers: List<ProviderInstanceEntity> = emptyList(),
    val models: List<ModelDescriptorEntity> = emptyList(),
    val maskedKeysByProvider: Map<String, String> = emptyMap(),
    val cipherPreviewsByProvider: Map<String, String> = emptyMap(),
    val vaultSecurityStatus: VaultSecurityStatus = VaultSecurityStatus(),
    val providerHealthById: Map<String, ProviderHealth> = emptyMap(),
    val selectedFilter: String = "ALL", // "ALL", "CHAT", "IMAGE", "VIDEO", "LOCAL", "CLOUD"
    val searchQuery: String = "",
    val busyTestingProviderId: String? = null,
    val statusToast: String? = null,
    val showAddProviderDialog: Boolean = false,
    val editingProvider: ProviderInstanceEntity? = null,
    val quickEditKeyProvider: ProviderInstanceEntity? = null,
    val showVaultDetailsDialog: Boolean = false
)

/**
 * Dedicated ViewModel for managing AI Providers, Base URLs, Models, and BYOK credentials.
 * Every API key is encrypted using Google Tink AES-256-GCM AEAD before writing to Jetpack DataStore,
 * with provider-bound Associated Authenticated Data (AAD) and Android Keystore protection.
 */
class ProvidersViewModel(
    private val repository: OmniRepository,
    private val secretVault: TinkSecretVault,
    private val dao: OmniDao,
    private val coroutineScopeOverride: kotlinx.coroutines.CoroutineScope? = null
) : ViewModel() {

    private val scope: kotlinx.coroutines.CoroutineScope
        get() = coroutineScopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    val providers: StateFlow<List<ProviderInstanceEntity>> = dao.observeProviders()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val models: StateFlow<List<ModelDescriptorEntity>> = dao.observeModels()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            repository.ensureDefaultPresetsSeeded()
            loadInitialState()

            dao.observeProviders().collect { provs ->
                _uiState.update { it.copy(providers = provs) }
                refreshMaskedKeys(provs)
            }
        }
        scope.launch {
            dao.observeModels().collect { mods ->
                _uiState.update { it.copy(models = mods) }
            }
        }
    }

    suspend fun loadInitialState() {
        val initialProvs = dao.getProvidersOnce()
        val initialMods = dao.getModelsOnce()
        _uiState.update { it.copy(providers = initialProvs, models = initialMods) }
        refreshVaultStatus()
        refreshMaskedKeys(initialProvs)
    }

    suspend fun refreshVaultStatus() {
        val status = secretVault.getVaultSecurityStatus()
        _uiState.update { it.copy(vaultSecurityStatus = status) }
    }

    private suspend fun refreshMaskedKeys(providerList: List<ProviderInstanceEntity>? = null) {
        val targets = providerList ?: _uiState.value.providers.ifEmpty { dao.getProvidersOnce() }
        val maskedMap = _uiState.value.maskedKeysByProvider.toMutableMap()
        val cipherMap = _uiState.value.cipherPreviewsByProvider.toMutableMap()
        for (prov in targets) {
            val masked = secretVault.getMaskedApiKey(prov.id)
            if (masked.isNotBlank()) {
                maskedMap[prov.id] = masked
                val cipherPreview = secretVault.getEncryptedCiphertextPreview(prov.id)
                if (cipherPreview.isNotBlank()) {
                    cipherMap[prov.id] = cipherPreview
                }
            } else {
                maskedMap.remove(prov.id)
                cipherMap.remove(prov.id)
            }
        }
        _uiState.update {
            it.copy(
                maskedKeysByProvider = maskedMap,
                cipherPreviewsByProvider = cipherMap
            )
        }
    }

    /**
     * Encrypts the provided raw API key using Google Tink AES-256-GCM AEAD and stores it in DataStore.
     */
    fun saveApiKey(providerId: String, rawApiKey: String) {
        scope.launch {
            saveApiKeyAwait(providerId, rawApiKey)
        }
    }

    suspend fun saveApiKeyAwait(providerId: String, rawApiKey: String) {
        secretVault.saveApiKey(providerId, rawApiKey)
        val masked = secretVault.getMaskedApiKey(providerId)
        val cipherPreview = secretVault.getEncryptedCiphertextPreview(providerId)
        _uiState.update { state ->
            val newMasked = state.maskedKeysByProvider.toMutableMap()
            val newCipher = state.cipherPreviewsByProvider.toMutableMap()
            if (masked.isNotBlank()) {
                newMasked[providerId] = masked
                if (cipherPreview.isNotBlank()) {
                    newCipher[providerId] = cipherPreview
                }
            } else {
                newMasked.remove(providerId)
                newCipher.remove(providerId)
            }
            val msg = if (rawApiKey.isBlank()) {
                "API key cleared from Tink DataStore"
            } else {
                "API key encrypted with Google Tink AES256-GCM & stored in DataStore"
            }
            state.copy(
                maskedKeysByProvider = newMasked,
                cipherPreviewsByProvider = newCipher,
                statusToast = msg,
                quickEditKeyProvider = null
            )
        }
        refreshVaultStatus()
    }

    fun clearApiKey(providerId: String) {
        scope.launch {
            clearApiKeyAwait(providerId)
        }
    }

    suspend fun clearApiKeyAwait(providerId: String) {
        secretVault.deleteApiKey(providerId)
        _uiState.update { state ->
            state.copy(
                maskedKeysByProvider = state.maskedKeysByProvider - providerId,
                cipherPreviewsByProvider = state.cipherPreviewsByProvider - providerId,
                statusToast = "API key removed from vault"
            )
        }
        refreshVaultStatus()
    }

    fun saveProviderInstance(
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
    ) {
        scope.launch {
            val providerId = existingId ?: "provider_${preset.name.lowercase()}_${UUID.randomUUID().toString().take(6)}"
            val entity = ProviderInstanceEntity(
                id = providerId,
                presetType = preset.name,
                displayName = displayName.ifBlank { preset.displayName },
                baseUrl = baseUrl.ifBlank { preset.defaultBaseUrl },
                capabilitiesCsv = capabilities.joinToString(",") { it.name },
                isEnabled = isEnabled,
                defaultChatModelId = defaultChatModel,
                defaultImageModelId = defaultImageModel,
                defaultVideoModelId = defaultVideoModel
            )
            dao.upsertProvider(entity)

            if (rawApiKey != null) {
                secretVault.saveApiKey(providerId, rawApiKey)
            }

            val updatedProvs = dao.getProvidersOnce()
            _uiState.update { it.copy(providers = updatedProvs) }
            refreshMaskedKeys(updatedProvs)
            refreshVaultStatus()

            _uiState.update {
                it.copy(
                    showAddProviderDialog = false,
                    editingProvider = null,
                    statusToast = "Provider '${entity.displayName}' saved (Tink AES-256-GCM protected)"
                )
            }
        }
    }

    fun deleteProviderInstance(providerId: String) {
        scope.launch {
            dao.deleteProviderById(providerId)
            secretVault.deleteApiKey(providerId)
            val updatedProvs = dao.getProvidersOnce()
            _uiState.update {
                it.copy(
                    providers = updatedProvs,
                    maskedKeysByProvider = it.maskedKeysByProvider - providerId,
                    cipherPreviewsByProvider = it.cipherPreviewsByProvider - providerId,
                    statusToast = "Provider removed"
                )
            }
            refreshVaultStatus()
        }
    }

    fun duplicateProviderInstance(provider: ProviderInstanceEntity) {
        scope.launch {
            val newId = "provider_${provider.presetType.lowercase()}_copy_${UUID.randomUUID().toString().take(4)}"
            val duplicated = provider.copy(
                id = newId,
                displayName = "${provider.displayName} (Copy)",
                isDefaultForChat = false,
                isDefaultForImage = false,
                isDefaultForVideo = false
            )
            dao.upsertProvider(duplicated)
            val existingKey = secretVault.getApiKey(provider.id)
            if (existingKey.isNotBlank()) {
                secretVault.saveApiKey(newId, existingKey)
            }
            val updatedProvs = dao.getProvidersOnce()
            _uiState.update { it.copy(providers = updatedProvs) }
            refreshMaskedKeys(updatedProvs)
            refreshVaultStatus()
            _uiState.update { it.copy(statusToast = "Duplicated provider: ${duplicated.displayName}") }
        }
    }

    fun toggleProviderEnabled(provider: ProviderInstanceEntity) {
        scope.launch {
            toggleProviderEnabledAwait(provider)
        }
    }

    suspend fun toggleProviderEnabledAwait(provider: ProviderInstanceEntity) {
        val updated = provider.copy(isEnabled = !provider.isEnabled)
        dao.upsertProvider(updated)
        _uiState.update { state ->
            state.copy(
                providers = state.providers.map { if (it.id == provider.id) updated else it }
            )
        }
    }

    fun updateProviderBaseUrl(provider: ProviderInstanceEntity, newUrl: String) {
        scope.launch {
            dao.upsertProvider(provider.copy(baseUrl = newUrl.trim()))
            _uiState.update { it.copy(statusToast = "Updated base URL for ${provider.displayName}") }
        }
    }

    fun updateProviderCapabilities(provider: ProviderInstanceEntity, capabilities: Set<CapabilityTag>) {
        scope.launch {
            dao.upsertProvider(provider.copy(capabilitiesCsv = capabilities.joinToString(",") { it.name }))
        }
    }

    fun setDefaultForFeature(providerId: String, capability: CapabilityTag) {
        scope.launch {
            when (capability) {
                CapabilityTag.CHAT -> dao.setDefaultChatProvider(providerId)
                CapabilityTag.IMAGE -> dao.setDefaultImageProvider(providerId)
                CapabilityTag.VIDEO -> dao.setDefaultVideoProvider(providerId)
                else -> Unit
            }
            _uiState.update { it.copy(statusToast = "Default updated for ${capability.name}") }
        }
    }

    fun testConnection(
        provider: ProviderInstanceEntity,
        rawApiKeyOverride: String? = null,
        baseUrlOverride: String? = null
    ) {
        scope.launch {
            _uiState.update {
                it.copy(
                    busyTestingProviderId = provider.id,
                    providerHealthById = it.providerHealthById + (provider.id to ProviderHealth.Checking)
                )
            }
            val key = rawApiKeyOverride ?: secretVault.getApiKey(provider.id)
            val effectiveUrl = baseUrlOverride ?: provider.baseUrl
            val tempProv = provider.copy(baseUrl = effectiveUrl)
            val start = System.currentTimeMillis()
            val result = repository.testProviderConnection(tempProv, key)
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)

            result.onSuccess {
                _uiState.update {
                    it.copy(
                        busyTestingProviderId = null,
                        providerHealthById = it.providerHealthById + (provider.id to ProviderHealth.Healthy(latencyMs = elapsed)),
                        statusToast = "Ping OK: ${elapsed}ms (${provider.displayName})"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        busyTestingProviderId = null,
                        statusToast = "Ping Failed: ${err.message}"
                    )
                }
            }
        }
    }

    fun fetchModelsForProvider(provider: ProviderInstanceEntity) {
        scope.launch {
            _uiState.update { it.copy(busyTestingProviderId = provider.id) }
            val key = secretVault.getApiKey(provider.id)
            val result = repository.fetchAndPersistModels(provider, key)
            result.onSuccess { fetched ->
                _uiState.update {
                    it.copy(
                        busyTestingProviderId = null,
                        statusToast = "Discovered ${fetched.size} models from ${provider.displayName}"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        busyTestingProviderId = null,
                        statusToast = "Fetch models failed: ${err.message}"
                    )
                }
            }
        }
    }

    fun addManualModel(providerId: String, modelId: String, isCombo: Boolean = false) {
        scope.launch {
            val trimmed = modelId.trim()
            if (trimmed.isEmpty()) return@launch
            val added = repository.addManualModel(providerId, trimmed, isCombo)
            _uiState.update { it.copy(statusToast = "Added model: ${added.displayName}") }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilter(filter: String) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun openAddProviderDialog() {
        _uiState.update { it.copy(showAddProviderDialog = true, editingProvider = null) }
    }

    fun closeAddProviderDialog() {
        _uiState.update { it.copy(showAddProviderDialog = false, editingProvider = null) }
    }

    fun openEditProviderDialog(provider: ProviderInstanceEntity) {
        _uiState.update { it.copy(editingProvider = provider, showAddProviderDialog = false) }
    }

    fun closeEditProviderDialog() {
        _uiState.update { it.copy(editingProvider = null) }
    }

    fun openQuickEditKeyDialog(provider: ProviderInstanceEntity) {
        _uiState.update { it.copy(quickEditKeyProvider = provider) }
    }

    fun closeQuickEditKeyDialog() {
        _uiState.update { it.copy(quickEditKeyProvider = null) }
    }

    fun openVaultDetailsDialog() {
        _uiState.update { it.copy(showVaultDetailsDialog = true) }
    }

    fun closeVaultDetailsDialog() {
        _uiState.update { it.copy(showVaultDetailsDialog = false) }
    }

    fun clearToast() {
        _uiState.update { it.copy(statusToast = null) }
    }

    class Factory(
        private val repository: OmniRepository,
        private val secretVault: TinkSecretVault,
        private val dao: OmniDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ProvidersViewModel::class.java)) {
                return ProvidersViewModel(repository, secretVault, dao) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
