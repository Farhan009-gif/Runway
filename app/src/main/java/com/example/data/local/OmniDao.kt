package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OmniDao {
    // --- Providers ---
    @Query("SELECT * FROM provider_instances ORDER BY CASE WHEN presetType = 'OMNIROUTE' THEN 0 ELSE 1 END, createdAt ASC")
    fun observeProviders(): Flow<List<ProviderInstanceEntity>>

    @Query("SELECT * FROM provider_instances ORDER BY CASE WHEN presetType = 'OMNIROUTE' THEN 0 ELSE 1 END, createdAt ASC")
    suspend fun getProvidersOnce(): List<ProviderInstanceEntity>

    @Query("SELECT * FROM provider_instances WHERE id = :id LIMIT 1")
    suspend fun getProviderById(id: String): ProviderInstanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(provider: ProviderInstanceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviders(providers: List<ProviderInstanceEntity>)

    @Query("DELETE FROM provider_instances WHERE id = :id")
    suspend fun deleteProviderById(id: String)

    @Query("UPDATE provider_instances SET isDefaultForChat = (id = :providerId)")
    suspend fun setDefaultChatProvider(providerId: String)

    @Query("UPDATE provider_instances SET isDefaultForImage = (id = :providerId)")
    suspend fun setDefaultImageProvider(providerId: String)

    @Query("UPDATE provider_instances SET isDefaultForVideo = (id = :providerId)")
    suspend fun setDefaultVideoProvider(providerId: String)

    // --- Models ---
    @Query("SELECT * FROM model_descriptors ORDER BY isFavorite DESC, isCombo DESC, displayName ASC")
    fun observeModels(): Flow<List<ModelDescriptorEntity>>

    @Query("SELECT * FROM model_descriptors ORDER BY isFavorite DESC, isCombo DESC, displayName ASC")
    suspend fun getModelsOnce(): List<ModelDescriptorEntity>

    @Query("SELECT * FROM model_descriptors WHERE providerInstanceId = :providerId ORDER BY isFavorite DESC, isCombo DESC, displayName ASC")
    suspend fun getModelsForProviderOnce(providerId: String): List<ModelDescriptorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModels(models: List<ModelDescriptorEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModel(model: ModelDescriptorEntity)

    @Query("DELETE FROM model_descriptors WHERE providerInstanceId = :providerId")
    suspend fun deleteModelsForProvider(providerId: String)

    @Query("UPDATE model_descriptors SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setModelFavorite(id: String, isFavorite: Boolean)

    // --- Conversations ---
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getConversationsOnce(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    // --- Messages ---
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit) ORDER BY createdAt ASC")
    fun observeRecentMessages(conversationId: String, limit: Int = 100): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND createdAt < :beforeTimestamp ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getMessagesPageBefore(conversationId: String, beforeTimestamp: Long, limit: Int = 50): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND createdAt >= :fromTimestamp")
    suspend fun deleteMessagesFromTimestamp(conversationId: String, fromTimestamp: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    // --- Prompt Library ---
    @Query("SELECT * FROM prompt_templates ORDER BY isFavorite DESC, createdAt DESC")
    fun observePromptTemplates(): Flow<List<PromptTemplateEntity>>

    @Query("SELECT * FROM prompt_templates ORDER BY isFavorite DESC, createdAt DESC")
    suspend fun getPromptTemplatesOnce(): List<PromptTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPromptTemplate(prompt: PromptTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPromptTemplates(prompts: List<PromptTemplateEntity>)

    @Query("DELETE FROM prompt_templates WHERE id = :id")
    suspend fun deletePromptTemplateById(id: String)

    // --- Usage Tracker ---
    @Query("SELECT * FROM usage_records ORDER BY createdAt DESC")
    fun observeUsageRecords(): Flow<List<UsageRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageRecord(record: UsageRecordEntity)

    @Query("DELETE FROM usage_records")
    suspend fun clearAllUsageRecords()

    // --- Generated Images Gallery (Phase 3) ---
    @Query("SELECT * FROM generated_images ORDER BY createdAt DESC")
    fun observeGeneratedImages(): Flow<List<GeneratedImageEntity>>

    @Query("SELECT * FROM generated_images ORDER BY createdAt DESC")
    suspend fun getGeneratedImagesOnce(): List<GeneratedImageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGeneratedImage(image: GeneratedImageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGeneratedImages(images: List<GeneratedImageEntity>)

    @Query("DELETE FROM generated_images WHERE id = :id")
    suspend fun deleteGeneratedImageById(id: String)

    @Query("DELETE FROM generated_images")
    suspend fun deleteAllGeneratedImages()

    // --- Video Jobs & Gallery (Phase 4) ---
    @Query("SELECT * FROM video_jobs ORDER BY createdAt DESC")
    fun observeVideoJobs(): Flow<List<VideoJobEntity>>

    @Query("SELECT * FROM video_jobs ORDER BY createdAt DESC")
    suspend fun getVideoJobsOnce(): List<VideoJobEntity>

    @Query("SELECT * FROM video_jobs WHERE status IN ('QUEUED', 'PROCESSING') ORDER BY createdAt ASC")
    suspend fun getActiveVideoJobsOnce(): List<VideoJobEntity>

    @Query("SELECT * FROM video_jobs WHERE id = :id LIMIT 1")
    suspend fun getVideoJobById(id: String): VideoJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVideoJob(job: VideoJobEntity)

    @Query("DELETE FROM video_jobs WHERE id = :id")
    suspend fun deleteVideoJobById(id: String)

    @Query("DELETE FROM video_jobs")
    suspend fun deleteAllVideoJobs()

    // --- Phase 5: Integration Audit Logs (GitHub & YouTube) ---
    @Query("SELECT * FROM integration_audit_logs ORDER BY createdAt DESC")
    fun observeIntegrationLogs(): Flow<List<IntegrationAuditLogEntity>>

    @Query("SELECT * FROM integration_audit_logs ORDER BY createdAt DESC")
    suspend fun getIntegrationLogsOnce(): List<IntegrationAuditLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIntegrationLog(log: IntegrationAuditLogEntity)

    @Query("DELETE FROM integration_audit_logs")
    suspend fun clearIntegrationLogs()

    // --- Phase 6: Skills (SKILL.md) ---
    @Query("SELECT * FROM skills ORDER BY isEnabled DESC, name ASC")
    fun observeSkills(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY isEnabled DESC, name ASC")
    suspend fun getSkillsOnce(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id LIMIT 1")
    suspend fun getSkillById(id: String): SkillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkill(skill: SkillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkills(skills: List<SkillEntity>)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteSkillById(id: String)

    @Query("DELETE FROM skills")
    suspend fun deleteAllSkills()

    // --- Phase 6: Remote MCP Servers & Tools ---
    @Query("SELECT * FROM mcp_servers ORDER BY createdAt ASC")
    fun observeMcpServers(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY createdAt ASC")
    suspend fun getMcpServersOnce(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id LIMIT 1")
    suspend fun getMcpServerById(id: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMcpServer(server: McpServerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMcpServers(servers: List<McpServerEntity>)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteMcpServerById(id: String)

    @Query("DELETE FROM mcp_servers")
    suspend fun deleteAllMcpServers()

    @Query("SELECT * FROM mcp_tools ORDER BY serverName ASC, toolName ASC")
    fun observeMcpTools(): Flow<List<McpToolEntity>>

    @Query("SELECT * FROM mcp_tools ORDER BY serverName ASC, toolName ASC")
    suspend fun getMcpToolsOnce(): List<McpToolEntity>

    @Query("SELECT * FROM mcp_tools WHERE serverId = :serverId ORDER BY toolName ASC")
    suspend fun getMcpToolsForServerOnce(serverId: String): List<McpToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMcpTools(tools: List<McpToolEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMcpTool(tool: McpToolEntity)

    @Query("DELETE FROM mcp_tools WHERE serverId = :serverId")
    suspend fun deleteMcpToolsForServer(serverId: String)

    @Query("DELETE FROM mcp_tools")
    suspend fun deleteAllMcpTools()

    // --- Phase 6: Local Tool Call Audit Logs ---
    @Query("SELECT * FROM tool_call_logs ORDER BY createdAt DESC")
    fun observeToolCallLogs(): Flow<List<ToolCallLogEntity>>

    @Query("SELECT * FROM tool_call_logs WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeToolCallLogsForConversation(conversationId: String): Flow<List<ToolCallLogEntity>>

    @Query("SELECT * FROM tool_call_logs ORDER BY createdAt DESC")
    suspend fun getToolCallLogsOnce(): List<ToolCallLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToolCallLog(log: ToolCallLogEntity)

    @Query("DELETE FROM tool_call_logs WHERE conversationId = :conversationId")
    suspend fun deleteToolCallLogsForConversation(conversationId: String)

    @Query("DELETE FROM tool_call_logs")
    suspend fun clearAllToolCallLogs()

    // --- Full Wipe ---
    @Query("DELETE FROM usage_records")
    suspend fun deleteAllUsageRecords()

    @Query("DELETE FROM prompt_templates")
    suspend fun deleteAllPromptTemplates()

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("DELETE FROM model_descriptors")
    suspend fun deleteAllModels()

    @Query("DELETE FROM provider_instances")
    suspend fun deleteAllProviders()

    // =========================================================================
    // --- Runway Data Layer (Step 1: runway_* tables) ---
    // =========================================================================

    // --- 1. Runway Message Tree Nodes ---
    @Query("SELECT * FROM runway_message_nodes WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeRunwayMessageNodes(conversationId: String): Flow<List<RunwayMessageNodeEntity>>

    @Query("SELECT * FROM runway_message_nodes ORDER BY createdAt ASC")
    fun observeAllRunwayMessageNodes(): Flow<List<RunwayMessageNodeEntity>>

    @Query("SELECT * FROM runway_message_nodes WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getRunwayMessageNodesOnce(conversationId: String): List<RunwayMessageNodeEntity>

    @Query("SELECT * FROM runway_message_nodes ORDER BY createdAt ASC")
    suspend fun getAllRunwayMessageNodesOnce(): List<RunwayMessageNodeEntity>

    @Query("SELECT * FROM runway_message_nodes WHERE id = :id LIMIT 1")
    suspend fun getRunwayMessageNodeById(id: String): RunwayMessageNodeEntity?

    @Query("SELECT COUNT(*) FROM runway_message_nodes")
    suspend fun countAllRunwayMessageNodes(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayMessageNode(node: RunwayMessageNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayMessageNodes(nodes: List<RunwayMessageNodeEntity>)

    @Query("DELETE FROM runway_message_nodes WHERE id = :id")
    suspend fun deleteRunwayMessageNodeById(id: String)

    @Query("DELETE FROM runway_message_nodes WHERE conversationId = :conversationId")
    suspend fun deleteRunwayMessageNodesForConversation(conversationId: String)

    @Query("DELETE FROM runway_message_nodes")
    suspend fun deleteAllRunwayMessageNodes()

    // --- 2. Runway Conversation Metadata ---
    @Query("SELECT * FROM runway_conversation_meta")
    fun observeRunwayConversationMetas(): Flow<List<RunwayConversationMetaEntity>>

    @Query("SELECT * FROM runway_conversation_meta")
    suspend fun getRunwayConversationMetasOnce(): List<RunwayConversationMetaEntity>

    @Query("SELECT * FROM runway_conversation_meta WHERE conversationId = :conversationId LIMIT 1")
    fun observeRunwayConversationMeta(conversationId: String): Flow<RunwayConversationMetaEntity?>

    @Query("SELECT * FROM runway_conversation_meta WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getRunwayConversationMetaOnce(conversationId: String): RunwayConversationMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayConversationMeta(meta: RunwayConversationMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayConversationMetas(metas: List<RunwayConversationMetaEntity>)

    @Query("DELETE FROM runway_conversation_meta WHERE conversationId = :conversationId")
    suspend fun deleteRunwayConversationMeta(conversationId: String)

    @Query("DELETE FROM runway_conversation_meta")
    suspend fun deleteAllRunwayConversationMetas()

    // --- 3. Runway Folders ---
    @Query("SELECT * FROM runway_folders ORDER BY sortOrder ASC, createdAt ASC")
    fun observeRunwayFolders(): Flow<List<RunwayFolderEntity>>

    @Query("SELECT * FROM runway_folders ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getRunwayFoldersOnce(): List<RunwayFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayFolder(folder: RunwayFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayFolders(folders: List<RunwayFolderEntity>)

    @Query("DELETE FROM runway_folders WHERE id = :id")
    suspend fun deleteRunwayFolderById(id: String)

    @Query("DELETE FROM runway_folders")
    suspend fun deleteAllRunwayFolders()

    // --- 4. Runway Documents & Chunks ---
    @Query("SELECT * FROM runway_documents ORDER BY createdAt DESC")
    fun observeAllRunwayDocuments(): Flow<List<RunwayDocumentEntity>>

    @Query("SELECT * FROM runway_documents WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeRunwayDocumentsForConversation(conversationId: String): Flow<List<RunwayDocumentEntity>>

    @Query("SELECT * FROM runway_documents WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getRunwayDocumentsForConversationOnce(conversationId: String): List<RunwayDocumentEntity>

    @Query("SELECT * FROM runway_documents WHERE id = :id LIMIT 1")
    suspend fun getRunwayDocumentById(id: String): RunwayDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayDocument(doc: RunwayDocumentEntity)

    @Query("DELETE FROM runway_documents WHERE id = :id")
    suspend fun deleteRunwayDocumentById(id: String)

    @Query("DELETE FROM runway_documents WHERE conversationId = :conversationId")
    suspend fun deleteRunwayDocumentsForConversation(conversationId: String)

    @Query("DELETE FROM runway_documents")
    suspend fun deleteAllRunwayDocuments()

    @Query("SELECT * FROM runway_document_chunks WHERE conversationId = :conversationId ORDER BY chunkIndex ASC")
    suspend fun getRunwayDocumentChunksForConversationOnce(conversationId: String): List<RunwayDocumentChunkEntity>

    @Query("SELECT * FROM runway_document_chunks WHERE documentId = :documentId ORDER BY chunkIndex ASC")
    suspend fun getRunwayDocumentChunksForDocumentOnce(documentId: String): List<RunwayDocumentChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayDocumentChunks(chunks: List<RunwayDocumentChunkEntity>)

    @Query("DELETE FROM runway_document_chunks WHERE documentId = :documentId")
    suspend fun deleteRunwayDocumentChunksForDocument(documentId: String)

    @Query("DELETE FROM runway_document_chunks WHERE conversationId = :conversationId")
    suspend fun deleteRunwayDocumentChunksForConversation(conversationId: String)

    @Query("DELETE FROM runway_document_chunks")
    suspend fun deleteAllRunwayDocumentChunks()

    // --- 5. Runway Prompts ---
    @Query("SELECT * FROM runway_prompts ORDER BY isFavorite DESC, updatedAt DESC")
    fun observeRunwayPrompts(): Flow<List<RunwayPromptEntity>>

    @Query("SELECT * FROM runway_prompts ORDER BY isFavorite DESC, updatedAt DESC")
    suspend fun getRunwayPromptsOnce(): List<RunwayPromptEntity>

    @Query("SELECT * FROM runway_prompts WHERE id = :id LIMIT 1")
    suspend fun getRunwayPromptById(id: String): RunwayPromptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayPrompt(prompt: RunwayPromptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayPrompts(prompts: List<RunwayPromptEntity>)

    @Query("DELETE FROM runway_prompts WHERE id = :id")
    suspend fun deleteRunwayPromptById(id: String)

    @Query("DELETE FROM runway_prompts")
    suspend fun deleteAllRunwayPrompts()

    // --- 6. Runway Parameter Presets ---
    @Query("SELECT * FROM runway_parameter_presets ORDER BY isDefault DESC, isBuiltIn DESC, createdAt ASC")
    fun observeRunwayParameterPresets(): Flow<List<RunwayParameterPresetEntity>>

    @Query("SELECT * FROM runway_parameter_presets ORDER BY isDefault DESC, isBuiltIn DESC, createdAt ASC")
    suspend fun getRunwayParameterPresetsOnce(): List<RunwayParameterPresetEntity>

    @Query("SELECT * FROM runway_parameter_presets WHERE id = :id LIMIT 1")
    suspend fun getRunwayParameterPresetById(id: String): RunwayParameterPresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayParameterPreset(preset: RunwayParameterPresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayParameterPresets(presets: List<RunwayParameterPresetEntity>)

    @Query("UPDATE runway_parameter_presets SET isDefault = (id = :presetId)")
    suspend fun setDefaultRunwayParameterPreset(presetId: String)

    @Query("DELETE FROM runway_parameter_presets WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteRunwayParameterPresetById(id: String)

    @Query("DELETE FROM runway_parameter_presets")
    suspend fun deleteAllRunwayParameterPresets()

    // --- 7. Runway Usage Logs ---
    @Query("SELECT * FROM runway_usage_logs ORDER BY createdAt DESC")
    fun observeRunwayUsageLogs(): Flow<List<RunwayUsageLogEntity>>

    @Query("SELECT * FROM runway_usage_logs ORDER BY createdAt DESC")
    suspend fun getRunwayUsageLogsOnce(): List<RunwayUsageLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayUsageLog(log: RunwayUsageLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunwayUsageLogs(logs: List<RunwayUsageLogEntity>)

    @Query("DELETE FROM runway_usage_logs")
    suspend fun deleteAllRunwayUsageLogs()
}
