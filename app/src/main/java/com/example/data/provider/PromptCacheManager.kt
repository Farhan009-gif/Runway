package com.example.data.provider

import com.example.data.local.ProviderInstanceEntity
import com.example.data.local.ProviderPresetType
import com.example.data.local.SkillEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages prompt caching across providers:
 * - Anthropic: marks system prompt, enabled skills, and tool definitions with
 *   `cache_control: {"type": "ephemeral"}` in a deterministic order each request.
 * - OpenAI-compatible providers that support prefix caching: structures the system prompt,
 *   enabled skills, and tool definitions as a deterministic, stable prefix on every request.
 * - Providers without caching: leaves the request unchanged with no error.
 */
object PromptCacheManager {

    private val OPENAI_PREFIX_CACHE_PRESETS: Set<ProviderPresetType> = setOf(
        ProviderPresetType.OPENAI,
        ProviderPresetType.OPENROUTER,
        ProviderPresetType.OMNIROUTE
    )

    fun supportsAnthropicPromptCaching(provider: ProviderInstanceEntity, modelId: String = ""): Boolean {
        val preset = provider.presetEnum()
        if (preset == ProviderPresetType.ANTHROPIC) return true
        val lowerUrl = provider.baseUrl.lowercase()
        val lowerModel = modelId.lowercase()
        return lowerUrl.contains("anthropic.com") && lowerModel.contains("claude")
    }

    fun supportsOpenAiPrefixCaching(provider: ProviderInstanceEntity, modelId: String = ""): Boolean {
        val preset = provider.presetEnum()
        if (preset in OPENAI_PREFIX_CACHE_PRESETS) return true
        val lowerUrl = provider.baseUrl.lowercase()
        return lowerUrl.contains("api.openai.com") ||
            lowerUrl.contains("api.deepseek.com") ||
            lowerUrl.contains("openrouter.ai")
    }

    fun supportsPromptCaching(provider: ProviderInstanceEntity, modelId: String = ""): Boolean {
        return supportsAnthropicPromptCaching(provider, modelId) ||
            supportsOpenAiPrefixCaching(provider, modelId)
    }

    /**
     * Sorts enabled skills deterministically by id then name so the cached prefix
     * remains in the exact same order across requests.
     */
    fun sortSkillsDeterministically(skills: List<SkillEntity>): List<SkillEntity> {
        return skills.sortedWith(
            compareBy<SkillEntity> { it.id.lowercase() }
                .thenBy { it.name.lowercase() }
        )
    }

    /**
     * Formats enabled skills in a deterministic order for prompt caching.
     */
    fun formatDeterministicSkillsBlock(skills: List<SkillEntity>): String {
        val sorted = sortSkillsDeterministically(skills.filter { it.isEnabled })
        if (sorted.isEmpty()) return ""
        return buildString {
            append("--- ACTIVE AGENT SKILLS (SKILL.md) ---\n")
            sorted.forEachIndexed { idx, skill ->
                if (idx > 0) append("\n\n")
                append("## Skill: ").append(skill.name.trim()).append('\n')
                if (skill.description.isNotBlank()) {
                    append("Description: ").append(skill.description.trim()).append('\n')
                }
                val body = skill.bodyInstructions.ifBlank { skill.rawMarkdown }.trim()
                append(body)
            }
        }
    }

    /**
     * Sorts tool definitions deterministically by serverId then toolName so the tool prefix
     * remains in the exact same order on every request.
     */
    fun sortToolsDeterministically(tools: List<McpToolDefinition>): List<McpToolDefinition> {
        return tools.sortedWith(
            compareBy<McpToolDefinition> { it.serverId.lowercase() }
                .thenBy { it.toolName.lowercase() }
        )
    }

    /**
     * Builds the Anthropic `system` content blocks array with `cache_control: {"type": "ephemeral"}`
     * on the base system prompt block and the enabled skills block, in deterministic order.
     */
    fun buildAnthropicCachedSystemBlocks(
        baseSystemPrompt: String,
        enabledSkillsBlock: String
    ): JSONArray {
        val blocks = JSONArray()
        val cleanBase = baseSystemPrompt.trim()
        val cleanSkills = enabledSkillsBlock.trim()

        if (cleanBase.isNotEmpty()) {
            blocks.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", cleanBase)
                    put("cache_control", JSONObject().put("type", "ephemeral"))
                }
            )
        }
        if (cleanSkills.isNotEmpty()) {
            blocks.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", cleanSkills)
                    put("cache_control", JSONObject().put("type", "ephemeral"))
                }
            )
        }
        return blocks
    }

    /**
     * Builds the stable OpenAI-compatible system prompt prefix keeping
     * baseSystemPrompt followed by enabledSkillsBlock in the exact same order each request.
     */
    fun buildStableSystemPrefix(
        baseSystemPrompt: String,
        enabledSkillsBlock: String = "",
        skillsBlock: String = ""
    ): String {
        val cleanBase = baseSystemPrompt.trim()
        val cleanSkills = enabledSkillsBlock.ifBlank { skillsBlock }.trim()
        return when {
            cleanBase.isNotEmpty() && cleanSkills.isNotEmpty() -> "$cleanBase\n\n$cleanSkills"
            cleanBase.isNotEmpty() -> cleanBase
            cleanSkills.isNotEmpty() -> cleanSkills
            else -> ""
        }
    }
}
