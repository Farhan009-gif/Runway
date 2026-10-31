package com.example.data.provider

import kotlin.math.roundToInt

/**
 * Smart Context Trimming:
 * - When input reaches about 60% of the model's context window (default 32,000 tokens if unknown),
 *   summarises older messages while keeping the last 6 turns, using the selected model.
 * - Keeps the summary under 800 tokens.
 * - Keeps the full message history visible in the app; only the outbound request uses the summary.
 * - If summarising fails, sends the full history and shows nothing extra.
 */
object SmartContextTrimmer {

    const val DEFAULT_CONTEXT_WINDOW_TOKENS: Int = 32_000
    const val TRIMMING_THRESHOLD_RATIO: Double = 0.60
    const val KEEP_RECENT_TURNS: Int = 6
    const val MAX_SUMMARY_TOKENS: Int = 800
    const val COMPOSER_NOTICE_LINE: String = "Older messages summarised to save context."
    const val COMPOSER_NOTICE_TEXT: String = COMPOSER_NOTICE_LINE

    data class ContextTrimResult(
        val outboundMessages: List<ChatMessagePayload>,
        val wasSummarised: Boolean,
        val summaryText: String?,
        val estimatedInputTokens: Int,
        val thresholdTokens: Int
    ) {
        val requestMessages: List<ChatMessagePayload>
            get() = outboundMessages
    }

    /**
     * Resolves a model's context window in tokens, defaulting to 32,000 if unknown.
     */
    fun resolveContextWindowTokens(contextWindowTokens: Int?): Int {
        return if (contextWindowTokens != null && contextWindowTokens > 0) {
            contextWindowTokens
        } else {
            DEFAULT_CONTEXT_WINDOW_TOKENS
        }
    }

    fun resolveContextWindowTokens(
        modelId: String,
        explicitContextWindow: Int? = null
    ): Int {
        if (explicitContextWindow != null && explicitContextWindow > 0) {
            return explicitContextWindow
        }
        val lower = modelId.trim().lowercase().substringAfterLast("/")
        return when {
            lower.isEmpty() || lower == "unknown" || lower == "default" -> DEFAULT_CONTEXT_WINDOW_TOKENS
            lower.startsWith("gpt-4.1") -> 128_000
            lower.startsWith("gpt-4o") -> 128_000
            lower.startsWith("gpt-4-turbo") -> 128_000
            lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4") -> 128_000
            lower.startsWith("claude-3") || lower.startsWith("claude-sonnet") || lower.startsWith("claude-opus") -> 200_000
            lower.startsWith("gemini-2.5") || lower.startsWith("gemini-1.5") || lower.startsWith("gemini-2.0") -> 128_000
            lower.startsWith("deepseek-") -> 64_000
            lower.startsWith("llama-3") -> 128_000
            lower.startsWith("mistral-large") -> 128_000
            else -> DEFAULT_CONTEXT_WINDOW_TOKENS
        }
    }

    /**
     * Computes the 60% token threshold for a given context window (default 32,000 -> 19,200 tokens).
     */
    fun calculateTrimmingThresholdTokens(
        contextWindowTokens: Int? = DEFAULT_CONTEXT_WINDOW_TOKENS
    ): Int {
        val window = resolveContextWindowTokens(contextWindowTokens)
        return (window * TRIMMING_THRESHOLD_RATIO).roundToInt()
    }

    /**
     * Returns true when [estimatedInputTokens] reaches at least 60% of [contextWindowTokens]
     * (defaulting to 32,000 tokens if unknown or <= 0).
     */
    fun shouldTrimContext(
        estimatedInputTokens: Int,
        contextWindowTokens: Int? = DEFAULT_CONTEXT_WINDOW_TOKENS
    ): Boolean {
        val threshold = calculateTrimmingThresholdTokens(contextWindowTokens)
        return estimatedInputTokens >= threshold
    }

    /**
     * Estimates total input tokens across the system prompt and conversation messages.
     */
    fun estimateInputTokens(
        systemPrompt: String,
        messages: List<ChatMessagePayload>
    ): Int {
        var total = if (systemPrompt.isNotBlank()) {
            ModelPriceTable.estimateTokensFromText(systemPrompt)
        } else 0
        for (msg in messages) {
            total += ModelPriceTable.estimateTokensFromText(msg.content)
            for (tc in msg.toolCalls) {
                total += ModelPriceTable.estimateTokensFromText(tc.toolName + " " + tc.argumentsJson)
            }
        }
        return total
    }

    /**
     * Splits conversation history into (olderMessagesToSummarise, recentMessagesToKeep),
     * keeping the last [keepTurns] turns (default 6 turns).
     */
    fun splitOlderAndRecentTurns(
        messages: List<ChatMessagePayload>,
        keepTurns: Int = KEEP_RECENT_TURNS
    ): Pair<List<ChatMessagePayload>, List<ChatMessagePayload>> {
        if (messages.isEmpty() || keepTurns <= 0) {
            return emptyList<ChatMessagePayload>() to messages
        }

        val userIndices = messages.mapIndexedNotNull { index, msg ->
            if (msg.role.equals("user", ignoreCase = true)) index else null
        }

        val splitIndex = when {
            userIndices.size > keepTurns -> userIndices[userIndices.size - keepTurns]
            messages.size > keepTurns * 2 -> messages.size - (keepTurns * 2)
            messages.size > keepTurns -> messages.size - keepTurns
            else -> 0
        }

        if (splitIndex <= 0) {
            return emptyList<ChatMessagePayload>() to messages
        }

        val older = messages.subList(0, splitIndex)
        val recent = messages.subList(splitIndex, messages.size)
        return older to recent
    }

    /**
     * Enforces that the summary stays strictly under [maxTokens] (default 800 tokens).
     */
    fun enforceSummaryTokenLimit(
        rawSummary: String,
        maxTokens: Int = MAX_SUMMARY_TOKENS
    ): String {
        val trimmed = rawSummary.trim()
        if (trimmed.isEmpty()) return ""
        val maxSummaryBudget = (maxTokens - 1).coerceAtLeast(1) // strictly under 800 tokens
        val currentTokens = ModelPriceTable.estimateTokensFromText(trimmed)
        if (currentTokens <= maxSummaryBudget) {
            return trimmed
        }
        val maxChars = (maxSummaryBudget * 4).coerceAtLeast(4)
        return trimmed.take(maxChars).trimEnd()
    }

    /**
     * Builds the prompt sent to the selected model to summarise older messages under 800 tokens.
     */
    fun buildSummarisationPrompt(olderMessages: List<ChatMessagePayload>): String {
        return buildString {
            append("Summarise the following earlier conversation messages concisely in under 600 tokens. ")
            append("Preserve key facts, decisions, user preferences, file names, and code context:\n\n")
            olderMessages.forEach { msg ->
                append(msg.role.uppercase()).append(": ").append(msg.content.take(4000)).append("\n\n")
            }
        }
    }

    /**
     * Checks whether input tokens reach ~60% of the context window and, if so and [trimmingEnabledForChat]
     * is true, summarises older messages keeping the last 6 turns.
     *
     * If summarising fails or returns blank, sends the full history and shows nothing extra.
     */
    suspend fun trimIfNeeded(
        fullMessages: List<ChatMessagePayload>,
        systemPrompt: String = "",
        modelContextWindowTokens: Int? = DEFAULT_CONTEXT_WINDOW_TOKENS,
        trimmingEnabledForChat: Boolean = true,
        existingCachedSummary: String? = null,
        keepTurns: Int = KEEP_RECENT_TURNS,
        summariseOlderMessages: suspend (String) -> String
    ): ContextTrimResult {
        val windowTokens = resolveContextWindowTokens(modelContextWindowTokens)
        val threshold = calculateTrimmingThresholdTokens(windowTokens)
        val estimatedInput = estimateInputTokens(systemPrompt, fullMessages)

        if (!trimmingEnabledForChat || !shouldTrimContext(estimatedInput, windowTokens)) {
            return ContextTrimResult(
                outboundMessages = fullMessages,
                wasSummarised = false,
                summaryText = null,
                estimatedInputTokens = estimatedInput,
                thresholdTokens = threshold
            )
        }

        val (older, recent) = splitOlderAndRecentTurns(fullMessages, keepTurns)
        if (older.isEmpty()) {
            return ContextTrimResult(
                outboundMessages = fullMessages,
                wasSummarised = false,
                summaryText = null,
                estimatedInputTokens = estimatedInput,
                thresholdTokens = threshold
            )
        }

        val prompt = buildSummarisationPrompt(older)
        val summaryResult = runCatching { summariseOlderMessages(prompt) }

        val rawSummary = summaryResult.getOrNull()?.trim().orEmpty()
        if (summaryResult.isFailure || rawSummary.isBlank()) {
            // If summarising fails, send the full history and show nothing extra
            return ContextTrimResult(
                outboundMessages = fullMessages,
                wasSummarised = false,
                summaryText = null,
                estimatedInputTokens = estimatedInput,
                thresholdTokens = threshold
            )
        }

        val cappedSummary = enforceSummaryTokenLimit(rawSummary, MAX_SUMMARY_TOKENS)
        val summaryPayload = ChatMessagePayload(
            role = "user",
            content = "[Summary of earlier messages]\n$cappedSummary"
        )
        return ContextTrimResult(
            outboundMessages = listOf(summaryPayload) + recent,
            wasSummarised = true,
            summaryText = cappedSummary,
            estimatedInputTokens = estimatedInput,
            thresholdTokens = threshold
        )
    }
}
