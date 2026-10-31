package com.example

import com.example.data.local.FileAttachmentProcessor
import com.example.data.local.SkillEntity
import com.example.data.local.UsageRecordEntity
import com.example.data.provider.ChatMessagePayload
import com.example.data.provider.ErrorNormalizer
import com.example.data.provider.McpToolDefinition
import com.example.data.provider.ModelPriceTable
import com.example.data.provider.PromptCacheManager
import com.example.data.provider.ProviderErrorMapper
import com.example.data.provider.ProviderSpendingLimitEvaluator
import com.example.data.provider.SmartContextTrimmer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownServiceException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunwaySevenFeaturesUnitTest {

    // -------------------------------------------------------------------------
    // 1. ERROR MAPPING
    // -------------------------------------------------------------------------

    @Test
    fun `error mapping maps 401 and 403 to plain cause next step and details`() {
        val err401 = ErrorNormalizer.normalizeHttpError(401, """{"error":"invalid_api_key"}""", "OpenAI")
        assertEquals("Your API key was rejected.", err401.message)
        assertEquals("Check the key in Providers.", err401.suggestedFix)
        assertTrue(err401.details.contains("HTTP 401"))

        val err403 = ErrorNormalizer.normalizeHttpError(403, """{"error":"permission_denied"}""", "Anthropic")
        assertEquals("Your API key was rejected.", err403.message)
        assertEquals("Check the key in Providers.", err403.suggestedFix)
        assertTrue(err403.details.contains("HTTP 403"))
    }

    @Test
    fun `error mapping maps 404 to model unavailable`() {
        val err404 = ErrorNormalizer.normalizeHttpError(404, """{"error":"model_not_found"}""", "OpenAI")
        assertEquals("This model isn't available on this provider.", err404.message)
        assertEquals("Pick another model.", err404.suggestedFix)
        assertTrue(err404.details.contains("HTTP 404"))
    }

    @Test
    fun `error mapping maps 413 and TPM or RPM text to rate-limited right now`() {
        val err413 = ErrorNormalizer.normalizeHttpError(413, """{"error":"payload too large"}""", "OpenAI")
        assertEquals("The provider is rate-limited right now.", err413.message)
        assertEquals("Wait or switch models.", err413.suggestedFix)

        val errTpm = ErrorNormalizer.normalizeHttpError(
            429,
            """{"error":{"message":"Rate limit reached for gpt-4o on tokens per min (TPM): Limit 30000"}}""",
            "OpenAI"
        )
        assertEquals("The provider is rate-limited right now.", errTpm.message)
        assertEquals("Wait or switch models.", errTpm.suggestedFix)

        val rawRpmError = ProviderErrorMapper.fromHttpResponse(
            statusCode = 413,
            responseBody = """{"error":{"message":"Exceeded RPM limit for this model"}}""",
            providerName = "Groq"
        )
        val normRpm = ProviderErrorMapper.toNormalizedAiError(rawRpmError, "Groq")
        assertEquals("The provider is rate-limited right now.", normRpm.message)
        assertEquals("Wait or switch models.", normRpm.suggestedFix)
    }

    @Test
    fun `error mapping maps 429 without TPM or RPM to too many requests`() {
        val err429 = ErrorNormalizer.normalizeHttpError(429, """{"error":"too_many_requests"}""", "OpenRouter")
        assertEquals("Too many requests.", err429.message)
        assertEquals("Wait or switch providers.", err429.suggestedFix)
    }

    @Test
    fun `error mapping maps 500 502 503 504 to provider is having trouble`() {
        for (code in listOf(500, 502, 503, 504)) {
            val err = ErrorNormalizer.normalizeHttpError(code, "upstream error", "Anthropic")
            assertEquals("The provider is having trouble.", err.message)
            assertEquals("Retry or switch providers.", err.suggestedFix)
            assertTrue(err.details.contains("HTTP $code"))
        }
    }

    @Test
    fun `error mapping maps timeout and cleartext blocked exceptions`() {
        val timeoutErr = ErrorNormalizer.normalizeException(
            SocketTimeoutException("Read timed out"),
            "OpenAI",
            "https://api.openai.com/v1"
        )
        assertEquals("No connection to the provider.", timeoutErr.message)
        assertEquals("Check your network or gateway address.", timeoutErr.suggestedFix)
        assertTrue(timeoutErr.details.contains("SocketTimeoutException"))

        val cleartextErr = ErrorNormalizer.normalizeException(
            UnknownServiceException("CLEARTEXT communication to 192.168.1.50 not permitted by network security policy"),
            "Local Gateway",
            "http://192.168.1.50:20128/v1"
        )
        assertEquals("Plain HTTP to this address isn't allowed.", cleartextErr.message)
        assertEquals("Approve the host in Settings.", cleartextErr.suggestedFix)
        assertEquals("192.168.1.50", cleartextErr.unapprovedHost)
    }

    // -------------------------------------------------------------------------
    // 2. SPENDING LIMIT MATH
    // -------------------------------------------------------------------------

    @Test
    fun `spending limit math formats monthly progress and evaluates 80 percent warning and 100 percent block with one override`() {
        val now = 1_750_000_000_000L
        val records = listOf(
            UsageRecordEntity(
                id = "u1",
                providerInstanceId = "prov_openai",
                providerDisplayName = "OpenAI",
                modelId = "gpt-4o",
                modality = "CHAT",
                promptTokens = 1000,
                completionTokens = 1000,
                estimatedCostUsd = 2.00,
                latencyMs = 400,
                createdAt = now - 10_000L
            ),
            UsageRecordEntity(
                id = "u2",
                providerInstanceId = "prov_openai",
                providerDisplayName = "OpenAI",
                modelId = "gpt-4o",
                modality = "CHAT",
                promptTokens = 1000,
                completionTokens = 1000,
                estimatedCostUsd = 1.20,
                latencyMs = 400,
                createdAt = now - 5_000L
            )
        )

        val spend = ProviderSpendingLimitEvaluator.calculateMonthlySpendUsd(
            usageRecords = records,
            providerInstanceId = "prov_openai",
            nowMillis = now
        )
        assertEquals(3.20, spend, 0.0001)
        assertEquals(
            "$3.20 of $5.00 this month",
            ProviderSpendingLimitEvaluator.formatMonthlyProgressText(spend, 5.00)
        )

        // At 64% ($3.20 of $5.00): neither warning nor blocked
        val eval64 = ProviderSpendingLimitEvaluator.evaluate(
            providerInstanceId = "prov_openai",
            providerDisplayName = "OpenAI",
            monthlySpendUsd = 3.20,
            monthlyLimitUsd = 5.00
        )
        assertFalse(eval64.isWarning)
        assertFalse(eval64.isBlocked)
        assertEquals("$3.20 of $5.00 this month", eval64.progressText)

        // At 80% ($4.00 of $5.00): warning banner, not blocked
        val eval80 = ProviderSpendingLimitEvaluator.evaluate(
            providerInstanceId = "prov_openai",
            providerDisplayName = "OpenAI",
            monthlySpendUsd = 4.00,
            monthlyLimitUsd = 5.00
        )
        assertTrue(eval80.isWarning)
        assertFalse(eval80.isBlocked)
        assertNotNull(eval80.warningMessage)

        // At 100% ($5.00 of $5.00): blocked with exact required message and canOverrideOnce = true
        val eval100 = ProviderSpendingLimitEvaluator.evaluate(
            providerInstanceId = "prov_openai",
            providerDisplayName = "OpenAI",
            monthlySpendUsd = 5.00,
            monthlyLimitUsd = 5.00,
            hasActiveOverrideForChat = false,
            hasUsedOverrideInChat = false
        )
        assertTrue(eval100.isBlocked)
        assertTrue(eval100.canOverrideOnce)
        assertEquals(
            "Monthly limit reached for OpenAI. Raise the limit in Settings to continue.",
            eval100.blockedMessage
        )

        // When one-time override is active for this chat -> not blocked
        val evalOverridden = ProviderSpendingLimitEvaluator.evaluate(
            providerInstanceId = "prov_openai",
            providerDisplayName = "OpenAI",
            monthlySpendUsd = 5.25,
            monthlyLimitUsd = 5.00,
            hasActiveOverrideForChat = true,
            hasUsedOverrideInChat = false
        )
        assertFalse(evalOverridden.isBlocked)

        // After the one override in this chat has been used -> blocked again and canOverrideOnce = false
        val evalAfterOverrideUsed = ProviderSpendingLimitEvaluator.evaluate(
            providerInstanceId = "prov_openai",
            providerDisplayName = "OpenAI",
            monthlySpendUsd = 5.50,
            monthlyLimitUsd = 5.00,
            hasActiveOverrideForChat = false,
            hasUsedOverrideInChat = true
        )
        assertTrue(evalAfterOverrideUsed.isBlocked)
        assertFalse(evalAfterOverrideUsed.canOverrideOnce)
    }

    // -------------------------------------------------------------------------
    // 3. PRICE SUGGESTION
    // -------------------------------------------------------------------------

    @Test
    fun `price suggestion triggers only above 50000 tokens on top-tier models`() {
        // Below or equal to 50,000 tokens -> no suggestion
        val belowThreshold = ModelPriceTable.evaluateCheaperModelSuggestion(
            currentModelId = "gpt-4o",
            totalConversationTokens = 50_000
        )
        assertNull(belowThreshold)

        // Non-top-tier model even over 50,000 tokens -> no suggestion
        val cheaperModel = ModelPriceTable.evaluateCheaperModelSuggestion(
            currentModelId = "gpt-4o-mini",
            totalConversationTokens = 85_000
        )
        assertNull(cheaperModel)

        // Top-tier model (gpt-4o) over 50,000 tokens -> suggests gpt-4o-mini with savings %
        val suggestion = ModelPriceTable.evaluateCheaperModelSuggestion(
            currentModelId = "gpt-4o",
            totalConversationTokens = 62_000
        )
        assertNotNull(suggestion)
        assertEquals("gpt-4o-mini", suggestion!!.suggestedModelId)
        assertTrue(suggestion.savingsPercent >= 90)
        assertEquals(
            "Try ${suggestion.suggestedDisplayName} to save about ${suggestion.savingsPercent}%",
            suggestion.bannerText
        )
    }

    // -------------------------------------------------------------------------
    // 4. FILE SIZE LIMIT & COUNT LIMIT
    // -------------------------------------------------------------------------

    @Test
    fun `file attachment limits enforce 10 MB per file and 5 files per message`() {
        val tenMb = 10L * 1024L * 1024L
        assertTrue(FileAttachmentProcessor.isFileSizeWithinLimit(tenMb))
        assertFalse(FileAttachmentProcessor.isFileSizeWithinLimit(tenMb + 1L))

        val overSizeValidation = FileAttachmentProcessor.validateAttachmentLimits(
            existingCount = 0,
            newFileSizeBytes = tenMb + 1024L,
            newFileName = "large.pdf"
        )
        assertFalse(overSizeValidation.isValid)
        assertTrue(overSizeValidation.errorMessage!!.contains("10 MB"))

        assertTrue(FileAttachmentProcessor.isFileCountWithinLimit(existingCount = 4, addingCount = 1))
        assertFalse(FileAttachmentProcessor.isFileCountWithinLimit(existingCount = 5, addingCount = 1))

        val overCountValidation = FileAttachmentProcessor.validateAttachmentLimits(
            existingCount = 5,
            newFileSizeBytes = 1024L,
            newFileName = "sixth.txt"
        )
        assertFalse(overCountValidation.isValid)
        assertTrue(overCountValidation.errorMessage!!.contains("5 files"))
    }

    // -------------------------------------------------------------------------
    // 5. TEXT TRIMMING
    // -------------------------------------------------------------------------

    @Test
    fun `text trimming caps extracted text at 100000 characters keeping start and end with middle trimmed marker`() {
        val shortText = "Hello world"
        assertEquals(shortText, FileAttachmentProcessor.trimExtractedText(shortText))

        val prefix = "START_MARKER_" + "A".repeat(60_000)
        val suffix = "B".repeat(60_000) + "_END_MARKER"
        val longText = prefix + suffix // > 120,000 chars

        val trimmed = FileAttachmentProcessor.trimExtractedText(longText, maxChars = 100_000)
        assertEquals(100_000, trimmed.length)
        assertTrue(trimmed.startsWith("START_MARKER_"))
        assertTrue(trimmed.endsWith("_END_MARKER"))
        assertTrue(trimmed.contains("[middle trimmed]"))

        // Also verify "--- File: name ---" formatting and first-time provider notice
        val formatted = FileAttachmentProcessor.formatFileAttachmentSection("notes.md", "Line 1")
        assertTrue(formatted.startsWith("--- File: notes.md ---"))
        assertEquals(
            "Attached files are sent to Anthropic",
            FileAttachmentProcessor.formatFirstTimeNotice("Anthropic")
        )
    }

    // -------------------------------------------------------------------------
    // 6. DOCX EXTRACTION
    // -------------------------------------------------------------------------

    @Test
    fun `docx extraction reads paragraphs from word document xml zip entry`() {
        val docxBytes = FileAttachmentProcessor.createDocxBytesFromParagraphs(
            listOf(
                "Runway Architecture Overview",
                "Second paragraph with <escaped> & special characters.",
                "Final summary line."
            )
        )
        val extracted = FileAttachmentProcessor.extractDocxTextFromBytes(docxBytes)
        assertTrue(extracted.contains("Runway Architecture Overview"))
        assertTrue(extracted.contains("Second paragraph with <escaped> & special characters."))
        assertTrue(extracted.contains("Final summary line."))
    }

    // -------------------------------------------------------------------------
    // 7. CACHE-TOKEN PARSING & PRICING
    // -------------------------------------------------------------------------

    @Test
    fun `cache token parsing extracts cached tokens for OpenAI Anthropic and Gemini and applies cached input pricing`() {
        val openAiJson = """
            {
              "usage": {
                "prompt_tokens": 2000,
                "completion_tokens": 500,
                "prompt_tokens_details": {
                  "cached_tokens": 1500
                }
              }
            }
        """.trimIndent()
        val openAiUsage = ModelPriceTable.parseOpenAiUsage(openAiJson)
        assertNotNull(openAiUsage)
        assertEquals(2000, openAiUsage.inputTokens)
        assertEquals(500, openAiUsage.outputTokens)
        assertEquals(1500, openAiUsage.cachedInputTokens)

        val anthropicJson = """
            {
              "usage": {
                "input_tokens": 500,
                "output_tokens": 300,
                "cache_creation_input_tokens": 0,
                "cache_read_input_tokens": 1500
              }
            }
        """.trimIndent()
        val anthropicUsage = ModelPriceTable.parseAnthropicUsage(anthropicJson)
        assertNotNull(anthropicUsage)
        assertEquals(2000, anthropicUsage.inputTokens)
        assertEquals(300, anthropicUsage.outputTokens)
        assertEquals(1500, anthropicUsage.cachedInputTokens)

        val geminiJson = """
            {
              "usageMetadata": {
                "promptTokenCount": 4000,
                "candidatesTokenCount": 600,
                "cachedContentTokenCount": 3000
              }
            }
        """.trimIndent()
        val geminiUsage = ModelPriceTable.parseGeminiUsage(geminiJson)
        assertNotNull(geminiUsage)
        assertEquals(4000, geminiUsage.inputTokens)
        assertEquals(600, geminiUsage.outputTokens)
        assertEquals(3000, geminiUsage.cachedInputTokens)

        // Cost calculation with cached tokens uses cachedInputUsdPer1M
        val uncachedCost = ModelPriceTable.calculateCostUsd(
            modelId = "gpt-4o",
            inputTokens = 1_000_000,
            outputTokens = 0,
            cachedInputTokens = 0
        )
        val cachedCost = ModelPriceTable.calculateCostUsd(
            modelId = "gpt-4o",
            inputTokens = 1_000_000,
            outputTokens = 0,
            cachedInputTokens = 800_000
        )
        assertTrue(cachedCost < uncachedCost)

        // Footer includes "cached: N tokens"
        val footer = ModelPriceTable.formatMessageUsageFooter(
            inputTokens = 2000,
            outputTokens = 500,
            estimatedCostUsd = cachedCost,
            isTokenCountEstimated = false,
            cachedInputTokens = 1500
        )
        assertTrue(footer.contains("cached: 1500 tokens"))

        // Verify deterministic ordering of skills & tools for prompt caching
        val skills = listOf(
            SkillEntity(id = "s2", name = "Zebra", description = "", rawMarkdown = "B", bodyInstructions = "B"),
            SkillEntity(id = "s1", name = "Alpha", description = "", rawMarkdown = "A", bodyInstructions = "A")
        )
        val sortedSkills = PromptCacheManager.sortSkillsDeterministically(skills)
        assertEquals(listOf("Alpha", "Zebra"), sortedSkills.map { it.name })

        val tools = listOf(
            McpToolDefinition(serverId = "srv", serverName = "S", toolName = "z_tool", description = "", inputSchemaJson = "{}"),
            McpToolDefinition(serverId = "srv", serverName = "S", toolName = "a_tool", description = "", inputSchemaJson = "{}")
        )
        val sortedTools = PromptCacheManager.sortToolsDeterministically(tools)
        assertEquals(listOf("a_tool", "z_tool"), sortedTools.map { it.toolName })
    }

    // -------------------------------------------------------------------------
    // 8. TRIMMING THRESHOLD
    // -------------------------------------------------------------------------

    @Test
    fun `smart context trimming triggers at 60 percent of context window with 32000 default and keeps last 6 turns`() = runBlocking {
        // Default context window when unknown is 32,000 -> 60% threshold is 19,200 tokens
        assertEquals(32_000, SmartContextTrimmer.resolveContextWindowTokens(null))
        assertEquals(32_000, SmartContextTrimmer.resolveContextWindowTokens(0))
        assertEquals(19_200, SmartContextTrimmer.calculateTrimmingThresholdTokens(null))
        assertEquals(76_800, SmartContextTrimmer.calculateTrimmingThresholdTokens(128_000))

        // Build 10 turns (20 messages) whose total token estimate exceeds 60% of a 2,000-token test window (threshold = 1,200)
        val messages = (1..10).flatMap { turn ->
            listOf(
                ChatMessagePayload(role = "user", content = "Turn $turn user message: " + "x".repeat(350)),
                ChatMessagePayload(role = "assistant", content = "Turn $turn assistant reply: " + "y".repeat(350))
            )
        }

        val result = SmartContextTrimmer.trimIfNeeded(
            fullMessages = messages,
            systemPrompt = "System instructions",
            modelContextWindowTokens = 2_000,
            trimmingEnabledForChat = true,
            summariseOlderMessages = { _ ->
                "Concise summary of turns 1 to 4."
            }
        )

        assertTrue(result.wasSummarised)
        assertEquals("Concise summary of turns 1 to 4.", result.summaryText)
        // Request messages = 1 summary message + last 6 turns (12 messages) = 13 messages
        assertEquals(13, result.requestMessages.size)
        assertTrue(result.requestMessages.first().content.contains("Concise summary of turns 1 to 4."))
        assertTrue(result.requestMessages[1].content.startsWith("Turn 5 user message:"))
        assertTrue(result.requestMessages.last().content.startsWith("Turn 10 assistant reply:"))

        // Summary token limit enforcement (< 800 tokens)
        val hugeSummary = "word ".repeat(2000)
        val cappedSummary = SmartContextTrimmer.enforceSummaryTokenLimit(hugeSummary, maxTokens = 800)
        assertTrue(cappedSummary.length <= 800 * 4)
    }

    // -------------------------------------------------------------------------
    // 9. SUMMARY FALLBACK
    // -------------------------------------------------------------------------

    @Test
    fun `smart context trimming falls back to full history and shows nothing extra when summarising fails`() = runBlocking {
        val messages = (1..8).flatMap { turn ->
            listOf(
                ChatMessagePayload(role = "user", content = "Question $turn " + "a".repeat(400)),
                ChatMessagePayload(role = "assistant", content = "Answer $turn " + "b".repeat(400))
            )
        }

        val fallbackResult = SmartContextTrimmer.trimIfNeeded(
            fullMessages = messages,
            systemPrompt = "System prompt",
            modelContextWindowTokens = 1_500,
            trimmingEnabledForChat = true,
            summariseOlderMessages = { _ ->
                throw RuntimeException("Provider timeout during summary")
            }
        )

        assertFalse(fallbackResult.wasSummarised)
        assertNull(fallbackResult.summaryText)
        assertEquals(messages.size, fallbackResult.requestMessages.size)
        assertEquals(messages, fallbackResult.requestMessages)
    }
}
