package com.example.data.provider

import org.json.JSONObject
import java.util.Locale

/**
 * Single centralized per-model price table for Runway.
 *
 * Prices are stored in USD per 1,000,000 (1M) input/output tokens so the table is easy to read
 * and update in one place, with helper properties converting to per-1,000 (1K) tokens.
 * All computed costs are labeled as "estimated" in the UI.
 */
data class ModelPriceEntry(
    val modelPattern: String,
    val displayName: String,
    val inputUsdPer1M: Double,
    val outputUsdPer1M: Double
) {
    val inputUsdPer1K: Double get() = inputUsdPer1M / 1000.0
    val outputUsdPer1K: Double get() = outputUsdPer1M / 1000.0
}

/**
 * Token usage extracted from a provider response (OpenAI, Anthropic, or Gemini).
 */
data class ProviderTokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val isPresent: Boolean
)

/**
 * Resolved usage and cost for a single assistant message or turn.
 */
data class ResolvedMessageUsage(
    val modelId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val isTokenCountEstimated: Boolean,
    val estimatedCostUsd: Double
) {
    val totalTokens: Int get() = inputTokens + outputTokens

    fun formatMessageFooter(currency: String = "USD"): String {
        return ModelPriceTable.formatMessageUsageFooter(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            estimatedCostUsd = estimatedCostUsd,
            isTokenCountEstimated = isTokenCountEstimated,
            currency = currency
        )
    }
}

/**
 * Aggregated usage summary for an entire conversation, including assistant turns,
 * MCP tool calls, and sandboxed JavaScript (`run_code`) runs.
 */
data class ConversationUsageSummary(
    val conversationId: String,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val totalEstimatedCostUsd: Double = 0.0,
    val toolCallsCount: Int = 0,
    val codeRunsCount: Int = 0,
    val hasEstimatedTokens: Boolean = false
) {
    val totalTokens: Int get() = totalInputTokens + totalOutputTokens

    fun formatConversationFooter(currency: String = "USD"): String {
        return ModelPriceTable.formatConversationUsageSummary(
            totalInputTokens = totalInputTokens,
            totalOutputTokens = totalOutputTokens,
            totalEstimatedCostUsd = totalEstimatedCostUsd,
            toolCallsCount = toolCallsCount,
            codeRunsCount = codeRunsCount,
            hasEstimatedTokens = hasEstimatedTokens,
            currency = currency
        )
    }
}

object ModelPriceTable {

    private const val USD_TO_INR_RATE = 86.5

    /**
     * Centralized per-model pricing table (USD per 1M input and output tokens).
     * Order goes from most specific model IDs to broader family prefixes.
     */
    val PRICE_TABLE: List<ModelPriceEntry> = listOf(
        // OpenAI Models
        ModelPriceEntry("gpt-4.1-nano", "GPT-4.1 Nano", inputUsdPer1M = 0.10, outputUsdPer1M = 0.40),
        ModelPriceEntry("gpt-4.1-mini", "GPT-4.1 Mini", inputUsdPer1M = 0.40, outputUsdPer1M = 1.60),
        ModelPriceEntry("gpt-4.1", "GPT-4.1", inputUsdPer1M = 2.00, outputUsdPer1M = 8.00),
        ModelPriceEntry("gpt-4o-mini", "GPT-4o Mini", inputUsdPer1M = 0.15, outputUsdPer1M = 0.60),
        ModelPriceEntry("gpt-4o", "GPT-4o", inputUsdPer1M = 2.50, outputUsdPer1M = 10.00),
        ModelPriceEntry("o3-mini", "OpenAI o3-mini", inputUsdPer1M = 1.10, outputUsdPer1M = 4.40),
        ModelPriceEntry("o1-mini", "OpenAI o1-mini", inputUsdPer1M = 1.10, outputUsdPer1M = 4.40),
        ModelPriceEntry("o1", "OpenAI o1", inputUsdPer1M = 15.00, outputUsdPer1M = 60.00),
        ModelPriceEntry("gpt-4-turbo", "GPT-4 Turbo", inputUsdPer1M = 10.00, outputUsdPer1M = 30.00),
        ModelPriceEntry("gpt-3.5-turbo", "GPT-3.5 Turbo", inputUsdPer1M = 0.50, outputUsdPer1M = 1.50),

        // Anthropic Claude Models
        ModelPriceEntry("claude-3-7-sonnet", "Claude 3.7 Sonnet", inputUsdPer1M = 3.00, outputUsdPer1M = 15.00),
        ModelPriceEntry("claude-3-5-sonnet", "Claude 3.5 Sonnet", inputUsdPer1M = 3.00, outputUsdPer1M = 15.00),
        ModelPriceEntry("claude-3-5-haiku", "Claude 3.5 Haiku", inputUsdPer1M = 0.80, outputUsdPer1M = 4.00),
        ModelPriceEntry("claude-3-haiku", "Claude 3 Haiku", inputUsdPer1M = 0.25, outputUsdPer1M = 1.25),
        ModelPriceEntry("claude-3-opus", "Claude 3 Opus", inputUsdPer1M = 15.00, outputUsdPer1M = 75.00),
        ModelPriceEntry("claude-sonnet-4", "Claude Sonnet 4", inputUsdPer1M = 3.00, outputUsdPer1M = 15.00),
        ModelPriceEntry("claude-opus-4", "Claude Opus 4", inputUsdPer1M = 15.00, outputUsdPer1M = 75.00),

        // Google Gemini Models
        ModelPriceEntry("gemini-2.5-pro", "Gemini 2.5 Pro", inputUsdPer1M = 1.25, outputUsdPer1M = 10.00),
        ModelPriceEntry("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", inputUsdPer1M = 0.075, outputUsdPer1M = 0.30),
        ModelPriceEntry("gemini-2.5-flash", "Gemini 2.5 Flash", inputUsdPer1M = 0.15, outputUsdPer1M = 0.60),
        ModelPriceEntry("gemini-2.0-flash", "Gemini 2.0 Flash", inputUsdPer1M = 0.10, outputUsdPer1M = 0.40),
        ModelPriceEntry("gemini-1.5-pro", "Gemini 1.5 Pro", inputUsdPer1M = 1.25, outputUsdPer1M = 5.00),
        ModelPriceEntry("gemini-1.5-flash", "Gemini 1.5 Flash", inputUsdPer1M = 0.075, outputUsdPer1M = 0.30),

        // Open-Weights & Router Models (Groq, OpenRouter, DeepSeek, Llama, Qwen, Mistral)
        ModelPriceEntry("deepseek-r1", "DeepSeek R1", inputUsdPer1M = 0.55, outputUsdPer1M = 2.19),
        ModelPriceEntry("deepseek-chat", "DeepSeek V3", inputUsdPer1M = 0.27, outputUsdPer1M = 1.10),
        ModelPriceEntry("deepseek", "DeepSeek", inputUsdPer1M = 0.27, outputUsdPer1M = 1.10),
        ModelPriceEntry("llama-3.3-70b", "Llama 3.3 70B", inputUsdPer1M = 0.59, outputUsdPer1M = 0.79),
        ModelPriceEntry("llama-3.1-8b", "Llama 3.1 8B", inputUsdPer1M = 0.05, outputUsdPer1M = 0.08),
        ModelPriceEntry("llama", "Llama Family", inputUsdPer1M = 0.20, outputUsdPer1M = 0.80),
        ModelPriceEntry("mixtral", "Mixtral", inputUsdPer1M = 0.24, outputUsdPer1M = 0.24),
        ModelPriceEntry("mistral-large", "Mistral Large", inputUsdPer1M = 2.00, outputUsdPer1M = 6.00),
        ModelPriceEntry("qwen", "Qwen Family", inputUsdPer1M = 0.20, outputUsdPer1M = 0.80),

        // OmniRoute & Combo Fallbacks
        ModelPriceEntry("omniroute/", "OmniRoute Combo", inputUsdPer1M = 1.00, outputUsdPer1M = 3.00),
        ModelPriceEntry("combo", "Router Combo", inputUsdPer1M = 1.00, outputUsdPer1M = 3.00)
    )

    val DEFAULT_PRICE_ENTRY = ModelPriceEntry(
        modelPattern = "default",
        displayName = "Standard LLM",
        inputUsdPer1M = 1.50,
        outputUsdPer1M = 5.00
    )

    /**
     * Looks up the per-model pricing entry for [rawModelId].
     */
    fun lookupPricing(rawModelId: String): ModelPriceEntry {
        val clean = rawModelId.trim().lowercase()
        if (clean.isEmpty()) return DEFAULT_PRICE_ENTRY

        // Strip provider prefix (e.g., "openai/gpt-4o-mini" -> "gpt-4o-mini") for matching as well
        val shortName = clean.substringAfterLast("/")

        PRICE_TABLE.firstOrNull { entry ->
            clean == entry.modelPattern || shortName == entry.modelPattern
        }?.let { return it }

        PRICE_TABLE.firstOrNull { entry ->
            clean.contains(entry.modelPattern) || shortName.contains(entry.modelPattern)
        }?.let { return it }

        return when {
            clean.contains("mini") || clean.contains("flash") || clean.contains("haiku") || clean.contains("nano") ->
                ModelPriceEntry(clean, rawModelId, inputUsdPer1M = 0.15, outputUsdPer1M = 0.60)
            clean.contains("sonnet") || clean.contains("gpt-4o") ->
                ModelPriceEntry(clean, rawModelId, inputUsdPer1M = 2.50, outputUsdPer1M = 10.00)
            clean.contains("opus") ->
                ModelPriceEntry(clean, rawModelId, inputUsdPer1M = 15.00, outputUsdPer1M = 75.00)
            else -> DEFAULT_PRICE_ENTRY
        }
    }

    /**
     * Returns (inputCostPer1K, outputCostPer1K) in USD for compatibility with [ModelDescriptorEntity].
     */
    fun lookupPricingPer1K(rawModelId: String, isCombo: Boolean = false): Pair<Double, Double> {
        if (isCombo && !rawModelId.contains("/")) {
            return 0.0010 to 0.0030
        }
        val entry = lookupPricing(rawModelId)
        return entry.inputUsdPer1K to entry.outputUsdPer1K
    }

    /**
     * Computes estimated cost in USD from the centralized model price table.
     */
    fun calculateCostUsd(
        modelId: String,
        inputTokens: Int,
        outputTokens: Int
    ): Double {
        val safeIn = inputTokens.coerceAtLeast(0)
        val safeOut = outputTokens.coerceAtLeast(0)
        val entry = lookupPricing(modelId)
        return ((safeIn / 1_000_000.0) * entry.inputUsdPer1M) +
            ((safeOut / 1_000_000.0) * entry.outputUsdPer1M)
    }

    /**
     * Estimates token count from text (~4 characters per token) when a provider
     * does not return token usage metadata.
     */
    fun estimateTokensFromText(text: String): Int {
        if (text.isBlank()) return 0
        return ((text.trim().length + 3) / 4).coerceAtLeast(1)
    }

    /**
     * Resolves token counts and cost for an assistant turn, including any tool call arguments/results
     * or sandboxed code execution input/output tokens.
     */
    fun resolveTurnUsage(
        modelId: String,
        reportedInputTokens: Int,
        reportedOutputTokens: Int,
        fallbackInputText: String,
        fallbackOutputText: String,
        additionalToolInputTokens: Int = 0,
        additionalToolOutputTokens: Int = 0
    ): ResolvedMessageUsage {
        val hasProviderUsage = reportedInputTokens > 0 || reportedOutputTokens > 0
        val baseInput = if (reportedInputTokens > 0) {
            reportedInputTokens
        } else {
            estimateTokensFromText(fallbackInputText) + additionalToolInputTokens.coerceAtLeast(0)
        }
        val baseOutput = if (reportedOutputTokens > 0) {
            reportedOutputTokens
        } else {
            estimateTokensFromText(fallbackOutputText) + additionalToolOutputTokens.coerceAtLeast(0)
        }
        val cost = calculateCostUsd(
            modelId = modelId,
            inputTokens = baseInput,
            outputTokens = baseOutput
        )
        return ResolvedMessageUsage(
            modelId = modelId,
            inputTokens = baseInput,
            outputTokens = baseOutput,
            isTokenCountEstimated = !hasProviderUsage,
            estimatedCostUsd = cost
        )
    }

    private fun extractJsonIntField(rawJson: String, fieldName: String): Int {
        val m = Regex(""""${Regex.escape(fieldName)}"\s*:\s*(\d+)""").find(rawJson)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    /**
     * Parses OpenAI-compatible `usage` object (`prompt_tokens` / `completion_tokens` or `input_tokens` / `output_tokens`).
     */
    fun parseOpenAiUsage(rootJson: JSONObject): ProviderTokenUsage {
        val usage = rootJson.optJSONObject("usage")
            ?: return ProviderTokenUsage(0, 0, isPresent = false)
        val prompt = usage.optInt("prompt_tokens", usage.optInt("input_tokens", 0))
        val completion = usage.optInt("completion_tokens", usage.optInt("output_tokens", 0))
        return ProviderTokenUsage(
            inputTokens = prompt.coerceAtLeast(0),
            outputTokens = completion.coerceAtLeast(0),
            isPresent = prompt > 0 || completion > 0
        )
    }

    fun parseOpenAiUsage(rawJson: String): ProviderTokenUsage {
        runCatching { return parseOpenAiUsage(JSONObject(rawJson)) }
        val prompt = extractJsonIntField(rawJson, "prompt_tokens")
            .takeIf { it > 0 } ?: extractJsonIntField(rawJson, "input_tokens")
        val completion = extractJsonIntField(rawJson, "completion_tokens")
            .takeIf { it > 0 } ?: extractJsonIntField(rawJson, "output_tokens")
        return ProviderTokenUsage(
            inputTokens = prompt.coerceAtLeast(0),
            outputTokens = completion.coerceAtLeast(0),
            isPresent = prompt > 0 || completion > 0
        )
    }

    /**
     * Parses Anthropic `usage` object from `message_start`, `message_delta`, or non-streaming `message` JSON.
     */
    fun parseAnthropicUsage(rootJson: JSONObject): ProviderTokenUsage {
        val messageUsage = rootJson.optJSONObject("message")?.optJSONObject("usage")
        val topUsage = rootJson.optJSONObject("usage")
        val inTokens = (messageUsage?.optInt("input_tokens", 0) ?: 0)
            .takeIf { it > 0 } ?: (topUsage?.optInt("input_tokens", 0) ?: 0)
        val outTokens = (topUsage?.optInt("output_tokens", 0) ?: 0)
            .takeIf { it > 0 } ?: (messageUsage?.optInt("output_tokens", 0) ?: 0)
        return ProviderTokenUsage(
            inputTokens = inTokens.coerceAtLeast(0),
            outputTokens = outTokens.coerceAtLeast(0),
            isPresent = inTokens > 0 || outTokens > 0
        )
    }

    fun parseAnthropicUsage(rawJson: String): ProviderTokenUsage {
        runCatching { return parseAnthropicUsage(JSONObject(rawJson)) }
        val inTokens = extractJsonIntField(rawJson, "input_tokens")
        val outTokens = extractJsonIntField(rawJson, "output_tokens")
        return ProviderTokenUsage(
            inputTokens = inTokens.coerceAtLeast(0),
            outputTokens = outTokens.coerceAtLeast(0),
            isPresent = inTokens > 0 || outTokens > 0
        )
    }

    /**
     * Parses Google Gemini `usageMetadata` object (`promptTokenCount` / `candidatesTokenCount`).
     */
    fun parseGeminiUsage(rootJson: JSONObject): ProviderTokenUsage {
        val meta = rootJson.optJSONObject("usageMetadata")
            ?: return ProviderTokenUsage(0, 0, isPresent = false)
        val prompt = meta.optInt("promptTokenCount", 0)
        val candidates = meta.optInt("candidatesTokenCount", 0)
        return ProviderTokenUsage(
            inputTokens = prompt.coerceAtLeast(0),
            outputTokens = candidates.coerceAtLeast(0),
            isPresent = prompt > 0 || candidates > 0
        )
    }

    fun parseGeminiUsage(rawJson: String): ProviderTokenUsage {
        runCatching { return parseGeminiUsage(JSONObject(rawJson)) }
        val prompt = extractJsonIntField(rawJson, "promptTokenCount")
        val candidates = extractJsonIntField(rawJson, "candidatesTokenCount")
        return ProviderTokenUsage(
            inputTokens = prompt.coerceAtLeast(0),
            outputTokens = candidates.coerceAtLeast(0),
            isPresent = prompt > 0 || candidates > 0
        )
    }

    /**
     * Formats a currency amount with an explicit "estimated" label.
     */
    fun formatEstimatedCost(costUsd: Double, currency: String = "USD"): String {
        val isInr = currency.equals("INR", ignoreCase = true)
        val amount = if (isInr) costUsd * USD_TO_INR_RATE else costUsd
        val symbol = if (isInr) "₹" else "$"
        val formatted = when {
            amount == 0.0 -> "${symbol}0.0000"
            amount < 0.01 -> String.format(Locale.US, "%s%.4f", symbol, amount)
            else -> String.format(Locale.US, "%s%.3f", symbol, amount)
        }
        return "$formatted (estimated)"
    }

    /**
     * Formats the per-message usage footer shown under every assistant reply.
     * Labels token counts as "estimated" when the provider returned no usage data,
     * and always labels cost as "estimated".
     */
    fun formatMessageUsageFooter(
        inputTokens: Int,
        outputTokens: Int,
        estimatedCostUsd: Double,
        isTokenCountEstimated: Boolean,
        currency: String = "USD"
    ): String {
        val tokenSuffix = if (isTokenCountEstimated) " (estimated)" else ""
        val costLabel = formatEstimatedCost(estimatedCostUsd, currency)
        return "$inputTokens in · $outputTokens out tokens$tokenSuffix · Cost: $costLabel"
    }

    /**
     * Formats the bottom-of-chat conversation summary line including total input tokens,
     * output tokens, tool calls, code runs, and estimated cost.
     */
    fun formatConversationUsageSummary(
        totalInputTokens: Int,
        totalOutputTokens: Int,
        totalEstimatedCostUsd: Double,
        toolCallsCount: Int,
        codeRunsCount: Int,
        hasEstimatedTokens: Boolean,
        currency: String = "USD"
    ): String {
        val tokenSuffix = if (hasEstimatedTokens) " (estimated)" else ""
        val costLabel = formatEstimatedCost(totalEstimatedCostUsd, currency)
        return buildString {
            append("Chat total: $totalInputTokens in · $totalOutputTokens out tokens$tokenSuffix")
            if (toolCallsCount > 0 || codeRunsCount > 0) {
                append(" · $toolCallsCount tool ${if (toolCallsCount == 1) "call" else "calls"}")
                append(" · $codeRunsCount code ${if (codeRunsCount == 1) "run" else "runs"}")
            }
            append(" · Cost: $costLabel")
        }
    }
}
