package com.example.data.mcp

import com.example.data.local.McpApprovalMode
import com.example.data.local.McpServerEntity
import com.example.data.local.McpToolEntity
import com.example.data.local.McpTransportType
import java.net.URI

/**
 * Per-tool and per-connector approval choice shown in the Connectors screen:
 * - ALWAYS: Runs the tool without asking.
 * - ASK: Shows an approval dialog before each call.
 * - NEVER: Blocks the tool.
 */
enum class ToolApprovalChoice(val label: String, val description: String) {
    ALWAYS("Always", "Runs the tool without asking"),
    ASK("Ask", "Shows an approval dialog before each call"),
    NEVER("Never", "Blocks the tool from running");

    companion object {
        fun fromStored(value: String?, fallback: ToolApprovalChoice = ASK): ToolApprovalChoice {
            if (value.isNullOrBlank()) return fallback
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: fallback
        }
    }
}

/**
 * Result of evaluating whether a tool call should run automatically, prompt for approval, or be blocked.
 */
enum class ToolExecutionGate {
    AUTO_RUN,
    ASK_USER,
    BLOCK
}

/**
 * Preset definition for adding a remote MCP connector in the "+" flow.
 */
data class McpConnectorPreset(
    val id: String,
    val name: String,
    val serverUrl: String,
    val transport: McpTransportType,
    val authHeaderName: String,
    val authTypeLabel: String,
    val tokenNote: String,
    val description: String
)

/**
 * Validation result for a remote MCP server URL.
 */
data class McpUrlValidationResult(
    val isValid: Boolean,
    val isCleartextHttp: Boolean = false,
    val host: String? = null,
    val errorMessage: String? = null
)

object ConnectorApprovalPolicy {

    const val GITHUB_MCP_SERVER_URL = "https://api.githubcopilot.com/mcp/"

    val GITHUB_PRESET = McpConnectorPreset(
        id = "preset_github_mcp",
        name = "GitHub",
        serverUrl = GITHUB_MCP_SERVER_URL,
        transport = McpTransportType.STREAMABLE_HTTP,
        authHeaderName = "Authorization",
        authTypeLabel = "Bearer token",
        tokenNote = "Please use a read-only fine-grained personal access token (PAT) for least-privilege access.",
        description = "Official GitHub Copilot MCP server for repository search, issues, pull requests, and code context."
    )

    val DIRECTORY_PRESETS: List<McpConnectorPreset> = listOf(
        GITHUB_PRESET
    )

    private val WRITE_DELETE_KEYWORDS = listOf(
        "write", "delete", "remove", "destroy", "drop", "update", "create",
        "insert", "modify", "mutate", "overwrite", "commit", "push", "merge",
        "close", "edit", "patch", "post", "put", "exec", "execute", "send"
    )

    private val READ_ONLY_PREFIXES = listOf(
        "get_", "list_", "read_", "search_", "find_", "fetch_", "view_", "inspect_", "describe_", "query_"
    )

    /**
     * Returns true if [tool] is a write or delete tool.
     * Any tool with `isReadOnlyHint == false` or whose name indicates a write/delete mutation
     * without an explicit read-only prefix is treated as a write/delete tool.
     */
    fun isWriteOrDeleteTool(tool: McpToolEntity): Boolean {
        if (!tool.isReadOnlyHint) return true
        val lowerName = tool.toolName.trim().lowercase()
        if (READ_ONLY_PREFIXES.any { lowerName.startsWith(it) }) {
            return false
        }
        return WRITE_DELETE_KEYWORDS.any { keyword ->
            lowerName == keyword ||
                lowerName.startsWith("${keyword}_") ||
                lowerName.endsWith("_$keyword") ||
                lowerName.contains("_${keyword}_")
        }
    }

    /**
     * Write and delete tools ALWAYS default to [ToolApprovalChoice.ASK].
     * Read-only tools default to the connector's default choice (or [ToolApprovalChoice.ASK] if unset).
     */
    fun defaultChoiceForTool(
        tool: McpToolEntity,
        connectorDefault: ToolApprovalChoice = ToolApprovalChoice.ASK
    ): ToolApprovalChoice {
        if (!tool.isEnabled) return ToolApprovalChoice.NEVER
        return if (isWriteOrDeleteTool(tool)) {
            if (connectorDefault == ToolApprovalChoice.NEVER) ToolApprovalChoice.NEVER else ToolApprovalChoice.ASK
        } else {
            connectorDefault
        }
    }

    /**
     * Resolves the effective [ToolApprovalChoice] for a specific [tool].
     *
     * Rules:
     * 1. If the user has set an explicit per-tool policy in [perToolPolicies]:
     *    - If it is [ToolApprovalChoice.ALWAYS] on a write/delete tool, it is only honored if
     *      that tool ID (or wildcard) is present in [confirmedWriteAlwaysToolIds]; otherwise it falls back to [ToolApprovalChoice.ASK].
     *    - Otherwise the explicit per-tool policy is used.
     * 2. If no per-tool policy is stored, uses [defaultChoiceForTool] with the connector's default.
     */
    fun resolveToolChoice(
        tool: McpToolEntity,
        server: McpServerEntity? = null,
        connectorDefaultPolicies: Map<String, ToolApprovalChoice> = emptyMap(),
        perToolPolicies: Map<String, ToolApprovalChoice> = emptyMap(),
        confirmedWriteAlwaysToolIds: Set<String> = emptySet()
    ): ToolApprovalChoice {
        if (!tool.isEnabled) return ToolApprovalChoice.NEVER

        val isWriteDelete = isWriteOrDeleteTool(tool)
        val explicitChoice = perToolPolicies[tool.id]
        if (explicitChoice != null) {
            if (explicitChoice == ToolApprovalChoice.ALWAYS && isWriteDelete) {
                return if (tool.id in confirmedWriteAlwaysToolIds) {
                    ToolApprovalChoice.ALWAYS
                } else {
                    ToolApprovalChoice.ASK
                }
            }
            return explicitChoice
        }

        val serverDefault = connectorDefaultPolicies[tool.serverId]
            ?: server?.let { mapLegacyServerModeToChoice(it.approvalModeEnum()) }
            ?: ToolApprovalChoice.ASK

        return if (isWriteDelete) {
            when (serverDefault) {
                ToolApprovalChoice.NEVER -> ToolApprovalChoice.NEVER
                ToolApprovalChoice.ALWAYS -> {
                    if (tool.id in confirmedWriteAlwaysToolIds) ToolApprovalChoice.ALWAYS else ToolApprovalChoice.ASK
                }
                ToolApprovalChoice.ASK -> ToolApprovalChoice.ASK
            }
        } else {
            serverDefault
        }
    }

    /**
     * Returns true if changing [tool] to [targetChoice] requires a confirmation dialog.
     * Write and delete tools cannot be set to [ToolApprovalChoice.ALWAYS] without user confirmation.
     */
    fun requiresConfirmationToSetToolChoice(
        tool: McpToolEntity,
        targetChoice: ToolApprovalChoice
    ): Boolean {
        return targetChoice == ToolApprovalChoice.ALWAYS && isWriteOrDeleteTool(tool)
    }

    /**
     * Returns true if applying "Set all tools" -> [targetChoice] for [tools] requires a confirmation dialog
     * because at least one tool in the connector is a write or delete tool.
     */
    fun requiresConfirmationForSetAllTools(
        tools: List<McpToolEntity>,
        targetChoice: ToolApprovalChoice
    ): Boolean {
        return targetChoice == ToolApprovalChoice.ALWAYS && tools.any { isWriteOrDeleteTool(it) }
    }

    /**
     * Applies "Set all tools" -> [targetChoice] across all [tools] in a connector.
     * If [targetChoice] is [ToolApprovalChoice.ALWAYS] and [userConfirmedWriteTools] is false,
     * write/delete tools are kept at [ToolApprovalChoice.ASK] while read-only tools are set to [ToolApprovalChoice.ALWAYS].
     */
    fun applySetAllTools(
        tools: List<McpToolEntity>,
        targetChoice: ToolApprovalChoice,
        existingPerToolPolicies: Map<String, ToolApprovalChoice>,
        existingConfirmedWriteIds: Set<String>,
        userConfirmedWriteTools: Boolean
    ): Pair<Map<String, ToolApprovalChoice>, Set<String>> {
        val updatedPolicies = existingPerToolPolicies.toMutableMap()
        val updatedConfirmed = existingConfirmedWriteIds.toMutableSet()

        for (tool in tools) {
            val isWrite = isWriteOrDeleteTool(tool)
            when (targetChoice) {
                ToolApprovalChoice.ALWAYS -> {
                    if (!isWrite || userConfirmedWriteTools) {
                        updatedPolicies[tool.id] = ToolApprovalChoice.ALWAYS
                        if (isWrite && userConfirmedWriteTools) {
                            updatedConfirmed.add(tool.id)
                        }
                    } else {
                        updatedPolicies[tool.id] = ToolApprovalChoice.ASK
                        updatedConfirmed.remove(tool.id)
                    }
                }
                ToolApprovalChoice.ASK -> {
                    updatedPolicies[tool.id] = ToolApprovalChoice.ASK
                    updatedConfirmed.remove(tool.id)
                }
                ToolApprovalChoice.NEVER -> {
                    updatedPolicies[tool.id] = ToolApprovalChoice.NEVER
                    updatedConfirmed.remove(tool.id)
                }
            }
        }
        return updatedPolicies to updatedConfirmed
    }

    /**
     * Evaluates whether a tool call in the chat loop should auto-run (`Always`), prompt (`Ask`),
     * or be blocked (`Never`).
     */
    fun evaluateToolExecutionGate(
        tool: McpToolEntity,
        server: McpServerEntity,
        connectorDefaultPolicies: Map<String, ToolApprovalChoice> = emptyMap(),
        perToolPolicies: Map<String, ToolApprovalChoice> = emptyMap(),
        confirmedWriteAlwaysToolIds: Set<String> = emptySet(),
        isConversationReadOnlyApproved: Boolean = false
    ): ToolExecutionGate {
        if (!server.isEnabled || !tool.isEnabled) {
            return ToolExecutionGate.BLOCK
        }
        val choice = resolveToolChoice(
            tool = tool,
            server = server,
            connectorDefaultPolicies = connectorDefaultPolicies,
            perToolPolicies = perToolPolicies,
            confirmedWriteAlwaysToolIds = confirmedWriteAlwaysToolIds
        )
        return when (choice) {
            ToolApprovalChoice.NEVER -> ToolExecutionGate.BLOCK
            ToolApprovalChoice.ALWAYS -> ToolExecutionGate.AUTO_RUN
            ToolApprovalChoice.ASK -> {
                if (!isWriteOrDeleteTool(tool) &&
                    server.approvalModeEnum() == McpApprovalMode.ASK_ONCE_PER_CONVERSATION &&
                    isConversationReadOnlyApproved
                ) {
                    ToolExecutionGate.AUTO_RUN
                } else {
                    ToolExecutionGate.ASK_USER
                }
            }
        }
    }

    /**
     * Maps legacy [McpApprovalMode] to [ToolApprovalChoice].
     */
    fun mapLegacyServerModeToChoice(mode: McpApprovalMode): ToolApprovalChoice {
        return when (mode) {
            McpApprovalMode.ALWAYS_ALLOW_READ_ONLY -> ToolApprovalChoice.ALWAYS
            McpApprovalMode.ASK_EVERY_TIME -> ToolApprovalChoice.ASK
            McpApprovalMode.ASK_ONCE_PER_CONVERSATION -> ToolApprovalChoice.ASK
        }
    }

    /**
     * Validates a remote MCP server URL.
     * HTTPS is always allowed; HTTP is allowed ONLY if the host is in [approvedCleartextHosts].
     */
    fun validateMcpServerUrl(
        rawUrl: String,
        approvedCleartextHosts: Set<String>
    ): McpUrlValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return McpUrlValidationResult(
                isValid = false,
                errorMessage = "Server URL cannot be empty."
            )
        }
        val parsed = runCatching { URI(trimmed) }.getOrNull()
            ?: return McpUrlValidationResult(
                isValid = false,
                errorMessage = "Invalid URL format."
            )
        val scheme = parsed.scheme?.lowercase().orEmpty()
        val host = parsed.host?.lowercase()?.trim().orEmpty()
        if (host.isEmpty()) {
            return McpUrlValidationResult(
                isValid = false,
                errorMessage = "URL must include a valid hostname."
            )
        }
        return when (scheme) {
            "https" -> McpUrlValidationResult(
                isValid = true,
                isCleartextHttp = false,
                host = host
            )
            "http" -> {
                val normalizedApproved = approvedCleartextHosts.map { it.trim().lowercase() }.toSet()
                if (host in normalizedApproved) {
                    McpUrlValidationResult(
                        isValid = true,
                        isCleartextHttp = true,
                        host = host
                    )
                } else {
                    McpUrlValidationResult(
                        isValid = false,
                        isCleartextHttp = true,
                        host = host,
                        errorMessage = "HTTP host '$host' is not in Approved HTTP Hosts. Use HTTPS or approve '$host' first."
                    )
                }
            }
            else -> McpUrlValidationResult(
                isValid = false,
                errorMessage = "Only HTTPS (or HTTP for approved hosts) is supported."
            )
        }
    }
}
