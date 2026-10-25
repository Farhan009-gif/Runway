package com.example.data.mcp

import com.example.data.local.ConversationEntity
import com.example.data.local.SkillEntity
import com.example.data.provider.ModelCapabilityRegistry
import org.json.JSONObject
import java.util.UUID

data class ParsedSkillMarkdown(
    val name: String,
    val description: String,
    val bodyInstructions: String,
    val canonicalRawMarkdown: String,
    val estimatedTokens: Int
)

object SkillMarkdownParser {

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "when", "use",
        "your", "you", "are", "can", "will", "how", "what", "why", "who", "please",
        "help", "make", "build", "write", "create", "about", "using", "only", "any"
    )

    private val WRITE_OR_DELETE_KEYWORDS = listOf(
        "write", "delete", "remove", "drop", "destroy", "truncate", "update", "create",
        "insert", "modify", "mutate", "patch", "post", "put", "exec", "execute",
        "run_command", "shell", "bash", "commit", "push", "upload", "publish", "send",
        "chmod", "chown", "rm", "mv", "kill", "format", "wipe", "deploy", "purge", "erase", "stop"
    )

    /**
     * Parses a SKILL.md string with YAML frontmatter (`name` and `description`) and extracts
     * the instruction body. If frontmatter is omitted, derives name/description from headings
     * or provided fallbacks.
     */
    fun parseSkillMarkdown(
        rawInput: String,
        fallbackName: String = "",
        fallbackDescription: String = ""
    ): ParsedSkillMarkdown {
        val normalized = rawInput.replace("\r\n", "\n").trim()
        var extractedName = fallbackName.trim()
        var extractedDescription = fallbackDescription.trim()
        var body = normalized

        if (normalized.startsWith("---")) {
            val endDelimiterIdx = normalized.indexOf("\n---", startIndex = 3)
            if (endDelimiterIdx != -1) {
                val frontmatter = normalized.substring(3, endDelimiterIdx).trim()
                body = normalized.substring(endDelimiterIdx + 4).trim()

                val lines = frontmatter.lines()
                var i = 0
                while (i < lines.size) {
                    val line = lines[i].trim()
                    when {
                        line.startsWith("name:", ignoreCase = true) -> {
                            val rawVal = line.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                            if (rawVal.isNotBlank()) extractedName = rawVal
                            i++
                        }
                        line.startsWith("description:", ignoreCase = true) -> {
                            val afterColon = line.substringAfter(":").trim()
                            if (afterColon == "|" || afterColon == ">-" || afterColon == ">" || afterColon.isEmpty()) {
                                val descBuilder = StringBuilder()
                                i++
                                while (i < lines.size) {
                                    val subLine = lines[i]
                                    if (subLine.startsWith(" ") || subLine.startsWith("\t")) {
                                        if (descBuilder.isNotEmpty()) descBuilder.append(" ")
                                        descBuilder.append(subLine.trim())
                                        i++
                                    } else {
                                        break
                                    }
                                }
                                val multiDesc = descBuilder.toString().trim()
                                if (multiDesc.isNotBlank()) extractedDescription = multiDesc
                            } else {
                                val singleDesc = afterColon.removeSurrounding("\"").removeSurrounding("'")
                                if (singleDesc.isNotBlank()) extractedDescription = singleDesc
                                i++
                            }
                        }
                        else -> i++
                    }
                }
            }
        }

        if (extractedName.isBlank()) {
            val headingLine = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("#") }
                ?.trimStart('#')
                ?.trim()
            extractedName = headingLine?.takeIf { it.isNotBlank() } ?: "custom-skill"
        }

        if (extractedDescription.isBlank()) {
            val firstParagraph = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?.take(160)
            extractedDescription = firstParagraph ?: "Custom SKILL.md instruction pack"
        }

        if (body.isBlank()) {
            body = normalized
        }

        val canonicalRaw = formatCanonicalSkillMarkdown(
            name = extractedName,
            description = extractedDescription,
            bodyInstructions = body
        )
        val tokenEst = ModelCapabilityRegistry.estimateTokens(body)

        return ParsedSkillMarkdown(
            name = extractedName,
            description = extractedDescription,
            bodyInstructions = body,
            canonicalRawMarkdown = canonicalRaw,
            estimatedTokens = tokenEst
        )
    }

    fun formatCanonicalSkillMarkdown(
        name: String,
        description: String,
        bodyInstructions: String
    ): String {
        val cleanName = name.trim().ifBlank { "custom-skill" }
        val cleanDesc = description.trim().replace("\n", " ").ifBlank { "Skill instructions" }
        val cleanBody = bodyInstructions.trim()
        return buildString {
            appendLine("---")
            appendLine("name: \"$cleanName\"")
            appendLine("description: \"$cleanDesc\"")
            appendLine("---")
            appendLine()
            append(cleanBody)
        }
    }

    fun createSkillEntity(
        existingId: String? = null,
        rawMarkdown: String,
        fallbackName: String = "",
        fallbackDescription: String = "",
        sourceUrl: String = "",
        isEnabled: Boolean = true
    ): SkillEntity {
        val parsed = parseSkillMarkdown(rawMarkdown, fallbackName, fallbackDescription)
        val now = System.currentTimeMillis()
        return SkillEntity(
            id = existingId ?: "skill_${UUID.randomUUID().toString().take(8)}",
            name = parsed.name,
            description = parsed.description,
            rawMarkdown = parsed.canonicalRawMarkdown,
            bodyInstructions = parsed.bodyInstructions,
            sourceUrl = sourceUrl.trim(),
            isEnabled = isEnabled,
            estimatedTokens = parsed.estimatedTokens,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * Resolves which skills are enabled for a given conversation (respecting per-conversation toggles),
     * and if `conversation.autoSelectSkills` is true, filters to skills whose name or description
     * matches the user's request keywords to save tokens.
     */
    fun resolveActiveSkillsForTurn(
        allSkills: List<SkillEntity>,
        conversation: ConversationEntity?,
        userPrompt: String
    ): List<SkillEntity> {
        val enabledForConv = getConversationEnabledSkills(allSkills, conversation)
        if (enabledForConv.isEmpty()) return emptyList()

        val autoSelect = conversation?.autoSelectSkills ?: false
        if (!autoSelect || userPrompt.isBlank()) {
            return enabledForConv
        }

        val promptKeywords = tokenizeKeywords(userPrompt)
        if (promptKeywords.isEmpty()) return emptyList()

        return enabledForConv.filter { skill ->
            matchesSkillToPrompt(skill, userPrompt, promptKeywords)
        }
    }

    fun getConversationEnabledSkills(
        allSkills: List<SkillEntity>,
        conversation: ConversationEntity?
    ): List<SkillEntity> {
        val overrideCsv = conversation?.enabledSkillIdsCsv
        if (overrideCsv == null) {
            return allSkills.filter { it.isEnabled }
        }
        if (overrideCsv.isBlank()) return emptyList()
        val allowedIds = overrideCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return allSkills.filter { it.id in allowedIds }
    }

    internal fun matchesSkillToPrompt(
        skill: SkillEntity,
        rawUserPrompt: String,
        promptKeywords: Set<String> = tokenizeKeywords(rawUserPrompt)
    ): Boolean {
        val lowerPrompt = rawUserPrompt.lowercase()
        val lowerSkillName = skill.name.lowercase().replace("-", " ").replace("_", " ")
        if (lowerSkillName.isNotBlank() && lowerPrompt.contains(lowerSkillName)) {
            return true
        }
        val skillTokens = tokenizeKeywords("${skill.name} ${skill.description}")
        return promptKeywords.intersect(skillTokens).isNotEmpty()
    }

    private fun tokenizeKeywords(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
    }

    /**
     * Builds the composite system prompt combining the conversation's base system prompt
     * and the injected Markdown bodies of active skills.
     */
    fun buildSystemPromptWithSkills(
        baseSystemPrompt: String,
        activeSkills: List<SkillEntity>
    ): String {
        if (activeSkills.isEmpty()) return baseSystemPrompt.trim()
        return buildString {
            if (baseSystemPrompt.isNotBlank()) {
                appendLine(baseSystemPrompt.trim())
                appendLine()
            }
            appendLine("## Active Workspace Skills (SKILL.md)")
            appendLine("Follow the domain-specific instructions from these enabled skills when relevant:")
            appendLine()
            for (skill in activeSkills) {
                appendLine("### Skill: ${skill.name}")
                appendLine("Description: ${skill.description}")
                appendLine(skill.bodyInstructions.trim())
                appendLine()
            }
        }.trim()
    }

    /**
     * Inspects an MCP tool definition and annotations to determine if it is strictly read-only.
     * Any tool that writes, modifies, executes commands, or deletes data returns `false` so
     * it is NEVER auto-executed without explicit user confirmation.
     */
    fun isReadOnlyTool(
        toolName: String,
        description: String,
        annotationsJson: JSONObject? = null
    ): Boolean {
        if (annotationsJson != null) {
            if (annotationsJson.optBoolean("destructiveHint", false)) return false
            if (annotationsJson.has("readOnlyHint") && !annotationsJson.optBoolean("readOnlyHint", true)) {
                return false
            }
        }
        val lowerName = toolName.lowercase()
        val lowerDesc = description.lowercase()
        for (keyword in WRITE_OR_DELETE_KEYWORDS) {
            val regex = Regex("(^|[_\\-\\s])$keyword($|[_\\-\\s]|s\\b|ed\\b|ing\\b)")
            if (regex.containsMatchIn(lowerName) || regex.containsMatchIn(lowerDesc)) {
                return false
            }
        }
        return true
    }
}
