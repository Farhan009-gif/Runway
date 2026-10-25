package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Step (1) Foundation — Runway Data Layer (`runway_*` tables & models).
 *
 * Stores tree-structured messages, conversation metadata, folders, document index & chunks,
 * prompt library templates with `{variable|default}` support, parameter presets, and usage logs.
 * All existing tables (`conversations`, `messages`, `prompt_templates`, `usage_records`) remain
 * untouched for 100% backward compatibility.
 */

@Entity(
    tableName = "runway_message_nodes",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["parentId"]),
        Index(value = ["createdAt"])
    ]
)
data class RunwayMessageNodeEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentId: String? = null,
    val childrenIdsJson: String = "[]",
    val activeChildId: String? = null,
    val role: String, // "USER" | "ASSISTANT" | "SYSTEM"
    val content: String,
    val modelUsed: String,
    val providerId: String,
    val providerName: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costUsd: Double = 0.0,
    val isEstimatedCost: Boolean = false,
    val sourcesJson: String = "[]", // JSON array of RunwayWebSource
    val citationsJson: String = "[]", // JSON array of RunwayDocCitation
    val attachmentUri: String? = null,
    val attachmentMimeType: String? = null,
    val isStreaming: Boolean = false,
    val errorText: String? = null,
    val comparisonGroupId: String? = null,
    val routedFromAuto: Boolean = false,
    val autoRoutingReason: String? = null,
    val fallbackFromProvider: String? = null,
    val latencyMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun childrenIds(): List<String> = RunwayJsonSerializer.parseStringList(childrenIdsJson)

    fun withAddedChild(childId: String, setAsActive: Boolean = true): RunwayMessageNodeEntity {
        val current = childrenIds().toMutableList()
        if (childId !in current) {
            current.add(childId)
        }
        return copy(
            childrenIdsJson = RunwayJsonSerializer.encodeStringList(current),
            activeChildId = if (setAsActive) childId else (activeChildId ?: childId)
        )
    }

    fun toLegacyMessageEntity(): MessageEntity {
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role,
            content = content,
            providerInstanceId = providerId,
            modelId = modelUsed,
            attachmentUri = attachmentUri,
            attachmentMimeType = attachmentMimeType,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            estimatedCostUsd = costUsd,
            isStreaming = isStreaming,
            errorText = errorText,
            comparisonGroupId = comparisonGroupId,
            latencyMs = latencyMs ?: 0L,
            createdAt = createdAt
        )
    }
}

@Entity(
    tableName = "runway_conversation_meta",
    indices = [Index(value = ["folderId"])]
)
data class RunwayConversationMetaEntity(
    @PrimaryKey val conversationId: String,
    val folderId: String? = null,
    val rootMessageIdsJson: String = "[]",
    val activeRootMessageId: String? = null,
    val activeLeafMessageId: String? = null,
    val parameterPresetId: String? = null,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val stopSequencesCsv: String = "",
    val webSearchEnabled: Boolean = false,
    val pinModelNoFallback: Boolean = false,
    val attachedDocumentIdsJson: String = "[]",
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun rootMessageIds(): List<String> = RunwayJsonSerializer.parseStringList(rootMessageIdsJson)
    fun attachedDocumentIds(): List<String> = RunwayJsonSerializer.parseStringList(attachedDocumentIdsJson)
}

@Entity(
    tableName = "runway_folders",
    indices = [Index(value = ["sortOrder"])]
)
data class RunwayFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorHex: String = "#6C7CFF",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "runway_documents",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["createdAt"])
    ]
)
data class RunwayDocumentEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val pageCount: Int = 1,
    val totalTokens: Int = 0,
    val parseStatus: String = "READY", // "PARSING" | "READY" | "ERROR"
    val parseProgressPercent: Int = 100,
    val errorMessage: String? = null,
    val fullText: String = "",
    val isSmallInlineDoc: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "runway_document_chunks",
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["conversationId"])
    ]
)
data class RunwayDocumentChunkEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val conversationId: String,
    val fileName: String,
    val chunkIndex: Int,
    val pageNumber: Int = 1,
    val tokenCount: Int = 0,
    val content: String,
    val termFreqJson: String = "{}"
)

@Entity(
    tableName = "runway_prompts",
    indices = [
        Index(value = ["isFavorite"]),
        Index(value = ["createdAt"])
    ]
)
data class RunwayPromptEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val tagsCsv: String = "",
    val defaultModelId: String? = null,
    val defaultPresetId: String? = null,
    val systemPromptOverride: String = "",
    val isFavorite: Boolean = false,
    val isStarter: Boolean = false,
    val lastVariableValuesJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun tags(): List<String> = tagsCsv.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun lastVariableValues(): Map<String, String> =
        RunwayJsonSerializer.parseStringMap(lastVariableValuesJson)
}

@Entity(
    tableName = "runway_parameter_presets",
    indices = [Index(value = ["isDefault"])]
)
data class RunwayParameterPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val topP: Float = 1.0f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val stopSequencesCsv: String = "",
    val isDefault: Boolean = false,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun stopSequences(): List<String> = stopSequencesCsv.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

@Entity(
    tableName = "runway_usage_logs",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["providerInstanceId"]),
        Index(value = ["createdAt"])
    ]
)
data class RunwayUsageLogEntity(
    @PrimaryKey val id: String,
    val conversationId: String? = null,
    val messageId: String? = null,
    val providerInstanceId: String,
    val providerName: String,
    val modelId: String,
    val modality: String = "CHAT", // "CHAT" | "IMAGE" | "VIDEO" | "SEARCH" | "DOCUMENT"
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val webSearchCount: Int = 0,
    val documentTokens: Int = 0,
    val costUsd: Double = 0.0,
    val isEstimated: Boolean = false,
    val latencyMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

data class RunwayWebSource(
    val index: Int,
    val title: String,
    val url: String,
    val domain: String,
    val snippet: String = ""
)

data class RunwayDocCitation(
    val documentId: String,
    val fileName: String,
    val pageNumber: Int,
    val chunkIndex: Int,
    val excerpt: String
)

data class RunwayNodeVersionInfo(
    val nodeId: String,
    val parentId: String?,
    val siblingIds: List<String>,
    val currentIndex: Int, // 1-based for UI "< 2/3 >"
    val totalVersions: Int
)

data class RunwayMigrationStatus(
    val isVerified: Boolean = false,
    val schemaVersion: Int = 8,
    val migratedConversationsCount: Int = 0,
    val migratedMessageNodesCount: Int = 0,
    val seededPromptsCount: Int = 0,
    val seededPresetsCount: Int = 0,
    val migratedUsageLogsCount: Int = 0,
    val verifiedAtMs: Long = 0L
)

object RunwayJsonSerializer {
    fun encodeStringList(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i)
                    if (item.isNotBlank()) add(item)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun encodeStringMap(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    fun parseStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.optString(key, "")
            }
            result
        }.getOrDefault(emptyMap())
    }

    fun encodeWebSources(sources: List<RunwayWebSource>): String {
        val arr = JSONArray()
        sources.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("index", s.index)
                    put("title", s.title)
                    put("url", s.url)
                    put("domain", s.domain)
                    put("snippet", s.snippet)
                }
            )
        }
        return arr.toString()
    }

    fun parseWebSources(raw: String?): List<RunwayWebSource> {
        if (raw.isNullOrBlank() || raw == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        RunwayWebSource(
                            index = o.optInt("index", i + 1),
                            title = o.optString("title", "Source ${i + 1}"),
                            url = o.optString("url", ""),
                            domain = o.optString("domain", ""),
                            snippet = o.optString("snippet", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun encodeDocCitations(citations: List<RunwayDocCitation>): String {
        val arr = JSONArray()
        citations.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("documentId", c.documentId)
                    put("fileName", c.fileName)
                    put("pageNumber", c.pageNumber)
                    put("chunkIndex", c.chunkIndex)
                    put("excerpt", c.excerpt)
                }
            )
        }
        return arr.toString()
    }

    fun parseDocCitations(raw: String?): List<RunwayDocCitation> {
        if (raw.isNullOrBlank() || raw == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        RunwayDocCitation(
                            documentId = o.optString("documentId", ""),
                            fileName = o.optString("fileName", ""),
                            pageNumber = o.optInt("pageNumber", 1),
                            chunkIndex = o.optInt("chunkIndex", 0),
                            excerpt = o.optString("excerpt", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Pure tree operations for Runway's branching message graph.
 * Linear chats are represented as a single-branch tree where each message points to the next via `activeChildId`.
 */
object RunwayMessageTreeHelper {

    /**
     * Converts a linear ordered list of `MessageEntity` rows into linked `RunwayMessageNodeEntity` nodes
     * and a corresponding `RunwayConversationMetaEntity`.
     */
    fun buildLinearTreeFromLegacyMessages(
        conversationId: String,
        messages: List<MessageEntity>,
        providerNamesById: Map<String, String> = emptyMap(),
        existingMeta: RunwayConversationMetaEntity? = null
    ): Pair<RunwayConversationMetaEntity, List<RunwayMessageNodeEntity>> {
        val sorted = messages.sortedBy { it.createdAt }
        if (sorted.isEmpty()) {
            val meta = (existingMeta ?: RunwayConversationMetaEntity(conversationId = conversationId)).copy(
                rootMessageIdsJson = "[]",
                activeRootMessageId = null,
                activeLeafMessageId = null
            )
            return meta to emptyList()
        }

        val nodes = sorted.mapIndexed { idx, msg ->
            val parentId = if (idx > 0) sorted[idx - 1].id else null
            val childId = if (idx < sorted.lastIndex) sorted[idx + 1].id else null
            RunwayMessageNodeEntity(
                id = msg.id,
                conversationId = conversationId,
                parentId = parentId,
                childrenIdsJson = if (childId != null) RunwayJsonSerializer.encodeStringList(listOf(childId)) else "[]",
                activeChildId = childId,
                role = msg.role,
                content = msg.content,
                modelUsed = msg.modelId,
                providerId = msg.providerInstanceId,
                providerName = providerNamesById[msg.providerInstanceId].orEmpty(),
                promptTokens = msg.promptTokens,
                completionTokens = msg.completionTokens,
                costUsd = msg.estimatedCostUsd,
                isEstimatedCost = false,
                attachmentUri = msg.attachmentUri,
                attachmentMimeType = msg.attachmentMimeType,
                isStreaming = msg.isStreaming,
                errorText = msg.errorText,
                comparisonGroupId = msg.comparisonGroupId,
                latencyMs = msg.latencyMs,
                createdAt = msg.createdAt
            )
        }

        val rootId = nodes.first().id
        val leafId = nodes.last().id
        val meta = (existingMeta ?: RunwayConversationMetaEntity(conversationId = conversationId)).copy(
            rootMessageIdsJson = RunwayJsonSerializer.encodeStringList(listOf(rootId)),
            activeRootMessageId = rootId,
            activeLeafMessageId = leafId,
            updatedAt = nodes.last().createdAt
        )
        return meta to nodes
    }

    /**
     * Resolves the currently active linear branch from root to leaf.
     */
    fun resolveActiveBranch(
        nodes: List<RunwayMessageNodeEntity>,
        meta: RunwayConversationMetaEntity? = null
    ): List<RunwayMessageNodeEntity> {
        if (nodes.isEmpty()) return emptyList()
        val byId = nodes.associateBy { it.id }

        // Determine starting root node
        val rootCandidates = nodes.filter { it.parentId == null }.sortedBy { it.createdAt }
        val activeRoot = meta?.activeRootMessageId?.let { byId[it] }
            ?: rootCandidates.lastOrNull()
            ?: nodes.minByOrNull { it.createdAt }
            ?: return emptyList()

        val result = mutableListOf<RunwayMessageNodeEntity>()
        val visited = mutableSetOf<String>()
        var current: RunwayMessageNodeEntity? = activeRoot

        while (current != null && current.id !in visited) {
            visited.add(current.id)
            result.add(current)
            val childrenIds = current.childrenIds()
            val nextId = current.activeChildId?.takeIf { it in byId }
                ?: childrenIds.lastOrNull { it in byId }
                ?: nodes.filter { it.parentId == current?.id }.maxByOrNull { it.createdAt }?.id
            current = nextId?.let { byId[it] }
        }
        return result
    }

    /**
     * Computes `< 2/3 >` sibling version info for every node in the active branch that has > 1 sibling.
     */
    fun computeVersionInfoMap(
        allNodes: List<RunwayMessageNodeEntity>,
        meta: RunwayConversationMetaEntity?
    ): Map<String, RunwayNodeVersionInfo> {
        if (allNodes.isEmpty()) return emptyMap()
        val byId = allNodes.associateBy { it.id }
        val rootSiblings = meta?.rootMessageIds()?.filter { it in byId }?.ifEmpty {
            allNodes.filter { it.parentId == null }.sortedBy { it.createdAt }.map { it.id }
        } ?: allNodes.filter { it.parentId == null }.sortedBy { it.createdAt }.map { it.id }

        val map = mutableMapOf<String, RunwayNodeVersionInfo>()
        for (node in allNodes) {
            val siblings = if (node.parentId == null) {
                rootSiblings
            } else {
                val parent = byId[node.parentId]
                val parentDeclared = parent?.childrenIds()?.filter { it in byId }.orEmpty()
                if (parentDeclared.isNotEmpty()) {
                    parentDeclared
                } else {
                    allNodes.filter { it.parentId == node.parentId }.sortedBy { it.createdAt }.map { it.id }
                }
            }
            if (siblings.size > 1) {
                val idx = siblings.indexOf(node.id).coerceAtLeast(0)
                map[node.id] = RunwayNodeVersionInfo(
                    nodeId = node.id,
                    parentId = node.parentId,
                    siblingIds = siblings,
                    currentIndex = idx + 1,
                    totalVersions = siblings.size
                )
            }
        }
        return map
    }
}
