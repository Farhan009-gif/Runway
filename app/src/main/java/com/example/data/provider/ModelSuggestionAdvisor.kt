package com.example.data.provider

/**
 * Evaluates whether a conversation has crossed token thresholds on a top-tier model
 * and suggests a cheaper, highly-capable alternative without ever switching automatically.
 */
object ModelSuggestionAdvisor {

    const val TOKEN_THRESHOLD = 50_000

    /**
     * Determines whether the given [modelId] is considered a top-tier expensive model.
     */
    fun isTopTierModel(modelId: String): Boolean {
        val entry = ModelPriceTable.lookupPricing(modelId)
        val clean = modelId.lowercase()
        return entry.inputUsdPer1M >= 2.0 ||
            clean.contains("gpt-4o") ||
            clean.contains("gpt-4.1") ||
            clean.contains("o1") ||
            clean.contains("o3-mini") ||
            clean.contains("claude-3-7-sonnet") ||
            clean.contains("claude-3-5-sonnet") ||
            clean.contains("claude-3-opus") ||
            clean.contains("claude-sonnet") ||
            clean.contains("gemini-2.5-pro") ||
            clean.contains("gemini-1.5-pro")
    }

    /**
     * Suggests a cheaper alternative model with computed savings percentage if total tokens exceed 50k.
     */
    fun getCheaperModelSuggestion(currentModelId: String, totalTokens: Int): CheaperModelSuggestion? {
        if (totalTokens <= TOKEN_THRESHOLD) return null
        if (!isTopTierModel(currentModelId)) return null

        val currentEntry = ModelPriceTable.lookupPricing(currentModelId)
        val clean = currentModelId.lowercase()

        val (targetModelId, targetModelDisplayName) = when {
            clean.contains("claude") -> "claude-3-5-haiku" to "Claude 3.5 Haiku"
            clean.contains("gemini") -> "gemini-2.5-flash" to "Gemini 2.5 Flash"
            clean.contains("gpt-4.1") -> "gpt-4.1-mini" to "GPT-4.1 Mini"
            else -> "gpt-4o-mini" to "GPT-4o Mini"
        }

        // Avoid self-suggestion
        if (targetModelId.equals(currentModelId, ignoreCase = true) ||
            clean.contains(targetModelId) ||
            clean.contains("mini") ||
            clean.contains("haiku") ||
            clean.contains("flash")
        ) {
            return null
        }

        val targetEntry = ModelPriceTable.lookupPricing(targetModelId)
        val currentBlended = currentEntry.inputUsdPer1M + currentEntry.outputUsdPer1M
        val targetBlended = targetEntry.inputUsdPer1M + targetEntry.outputUsdPer1M

        if (targetBlended >= currentBlended) return null

        val savingsPercent = (((currentBlended - targetBlended) / currentBlended) * 100.0)
            .toInt()
            .coerceIn(5, 95)

        return CheaperModelSuggestion(
            currentModelId = currentModelId,
            suggestedModelId = targetModelId,
            suggestedDisplayName = targetModelDisplayName,
            savingsPercent = savingsPercent,
            suggestedModelDisplayName = targetModelDisplayName
        )
    }
}
