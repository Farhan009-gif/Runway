package com.example.data.provider

import com.example.data.local.MessageEntity

/**
 * Smart context window management: triggers when total input reaches ~60% of the model's context window,
 * condensing earlier messages into a high-signal summary under 800 tokens while preserving the most recent turns.
 */
object SmartContextTrimmer {

    const val DEFAULT_CONTEXT_WINDOW = 32_000
    const val THRESHOLD_RATIO = 0.60
    const val KEEP_RECENT_TURNS = 6
    const val MAX_SUMMARY_TOKENS = 800

    /**
     * Retrieves the known context window size for [modelId], or defaults to 32,000 tokens.
     */
    fun getContextWindow(modelId: String): Int {
        val clean = modelId.lowercase()
        return when {
            clean.contains("gemini-2") || clean.contains("gemini-1.5") -> 1_000_000
            clean.contains("claude-3-7") || clean.contains("claude-3-5") || clean.contains("claude-3-opus") || clean.contains("claude-3-haiku") -> 200_000
            clean.contains("gpt-4o") || clean.contains("gpt-4.1") || clean.contains("o1") || clean.contains("o3") -> 128_000
            clean.contains("llama-3.3") || clean.contains("llama-3.1") -> 128_000
            clean.contains("deepseek") -> 64_000
            clean.contains("gpt-4-turbo") -> 128_000
            clean.contains("gpt-3.5-turbo") -> 16_384
            else -> DEFAULT_CONTEXT_WINDOW
        }
    }

    /**
     * Evaluates if total tokens exceed 60% of the model context window.
     */
    fun isTrimmingThresholdReached(
        modelId: String,
        estimatedTotalTokens: Int,
        enabledForChat: Boolean = true
    ): Boolean {
        if (!enabledForChat) return false
        val window = getContextWindow(modelId)
        val threshold = (window * THRESHOLD_RATIO).toInt()
        return estimatedTotalTokens >= threshold
    }

    /**
     * Splits a list of messages into older messages (to be summarized) and recent turns (to preserve intact).
     */
    fun splitMessagesForTrimming(
        messages: List<MessageEntity>,
        keepTurns: Int = KEEP_RECENT_TURNS
    ): Pair<List<MessageEntity>, List<MessageEntity>> {
        if (messages.size <= keepTurns) {
            return emptyList<MessageEntity>() to messages
        }
        val older = messages.take(messages.size - keepTurns)
        val recent = messages.takeLast(keepTurns)
        return older to recent
    }

    /**
     * Generates a prompt for summarizing the older conversation turns under 800 tokens.
     */
    fun buildSummaryPrompt(olderMessages: List<MessageEntity>): String {
        val transcript = olderMessages.joinToString("\n\n") { msg ->
            val roleLabel = if (msg.role.equals("USER", ignoreCase = true)) "User" else "Assistant"
            "$roleLabel: ${msg.content.trim()}"
        }
        return """
            You are a context compression assistant. Please write a dense, factual summary of the following prior conversation transcript in strictly under $MAX_SUMMARY_TOKENS tokens. Preserve all key decisions, user preferences, technical constraints, and unresolved questions. Output only the summary without preamble:
            
            $transcript
        """.trimIndent()
    }

    /**
     * Prepares the condensed message payload using the summary plus recent turns.
     */
    fun buildTrimmedPayload(
        summaryText: String,
        recentMessages: List<MessageEntity>
    ): List<ChatMessagePayload> {
        val summaryHeader = ChatMessagePayload(
            role = "system",
            content = "[Prior Conversation Summary - Condensed to preserve context]:\n$summaryText"
        )
        val recentPayloads = recentMessages.map { m ->
            ChatMessagePayload(
                role = m.role.lowercase(),
                content = m.content
            )
        }
        return listOf(summaryHeader) + recentPayloads
    }
}
