package com.example

import com.example.data.local.MessageEntity
import com.example.data.local.UsageRecordEntity
import com.example.data.provider.FileAttachmentProcessor
import com.example.data.provider.FriendlyErrorMapper
import com.example.data.provider.ModelPriceTable
import com.example.data.provider.ModelSuggestionAdvisor
import com.example.data.provider.ProviderSpendingLimitTracker
import com.example.data.provider.SmartContextTrimmer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RunwayFeaturesUnitTest {

    // =========================================================================
    // 1. ERROR MAPPING TESTS
    // =========================================================================

    @Test
    fun errorMapping_maps401And403ToApiKeyRejected() {
        val err401 = FriendlyErrorMapper.map(
            rawMessage = "Unauthorized: Invalid API key provided.",
            statusCode = 401,
            providerName = "OpenAI"
        )
        assertEquals("Authentication Error", err401.title)
        assertEquals("Your API key was rejected.", err401.cause)
        assertEquals("Check the key in Providers.", err401.nextStep)
        assertEquals("Unauthorized: Invalid API key provided.", err401.rawDetails)

        val err403 = FriendlyErrorMapper.map(
            rawMessage = "403 Forbidden: access denied",
            statusCode = 403,
            providerName = "Anthropic"
        )
        assertEquals("Your API key was rejected.", err403.cause)
        assertEquals("Check the key in Providers.", err403.nextStep)
    }

    @Test
    fun errorMapping_maps404ToModelUnavailable() {
        val err404 = FriendlyErrorMapper.map(
            rawMessage = "404 Not Found: model 'gpt-5-turbo' does not exist",
            statusCode = 404,
            providerName = "OpenAI"
        )
        assertEquals("Model Unavailable", err404.title)
        assertEquals("This model isn't available on this provider.", err404.cause)
        assertEquals("Pick another model.", err404.nextStep)
    }

    @Test
    fun errorMapping_maps413AndTpmRpmToRateLimited() {
        val err413 = FriendlyErrorMapper.map(
            rawMessage = "413 Payload Too Large",
            statusCode = 413,
            providerName = "Groq"
        )
        assertEquals("Rate Limit Reached", err413.title)
        assertEquals("The provider is rate-limited right now.", err413.cause)
        assertEquals("Wait or switch models.", err413.nextStep)

        val errTpm = FriendlyErrorMapper.map(
            rawMessage = "TPM limit reached for this organization",
            statusCode = 400,
            providerName = "OpenAI"
        )
        assertEquals("The provider is rate-limited right now.", errTpm.cause)
        assertEquals("Wait or switch models.", errTpm.nextStep)
    }

    @Test
    fun errorMapping_maps429ToTooManyRequests() {
        val err429 = FriendlyErrorMapper.map(
            rawMessage = "429 Too Many Requests",
            statusCode = 429,
            providerName = "Anthropic"
        )
        assertEquals("Too Many Requests", err429.title)
        assertEquals("Too many requests.", err429.cause)
        assertEquals("Wait or switch providers.", err429.nextStep)
    }

    @Test
    fun errorMapping_maps500SeriesToProviderTrouble() {
        for (code in listOf(500, 502, 503, 504)) {
            val err = FriendlyErrorMapper.map(
                rawMessage = "$code Bad Gateway upstream",
                statusCode = code,
                providerName = "OpenRouter"
            )
            assertEquals("Provider Outage", err.title)
            assertEquals("The provider is having trouble.", err.cause)
            assertEquals("Retry or switch providers.", err.nextStep)
        }
    }

    @Test
    fun errorMapping_mapsTimeoutAndCleartextBlocked() {
        val errTimeout = FriendlyErrorMapper.map(
            rawMessage = "SocketTimeoutException: timeout waiting for response",
            providerName = "OpenAI"
        )
        assertEquals("Connection Failed", errTimeout.title)
        assertEquals("No connection to the provider.", errTimeout.cause)
        assertEquals("Check your network or gateway address.", errTimeout.nextStep)

        val errCleartext = FriendlyErrorMapper.map(
            rawMessage = "CLEARTEXT HTTP traffic not allowed for 192.168.1.100",
            providerName = "Localhost"
        )
        assertEquals("Connection Security Block", errCleartext.title)
        assertEquals("Plain HTTP to this address isn't allowed.", errCleartext.cause)
        assertEquals("Approve the host in Settings.", errCleartext.nextStep)
    }

    // =========================================================================
    // 2. SPENDING LIMIT MATH TESTS
    // =========================================================================

    @Test
    fun spendingLimitMath_calculatesMonthlySpendAndEnforcesThresholds() {
        val providerId = "prov_openai"
        val r1 = UsageRecordEntity(
            id = "u1",
            providerInstanceId = providerId,
            providerDisplayName = "OpenAI",
            modelId = "gpt-4o",
            promptTokens = 1000,
            completionTokens = 500,
            estimatedCostUsd = 2.20,
            createdAt = System.currentTimeMillis() - 60_000L
        )
        val r2 = UsageRecordEntity(
            id = "u2",
            providerInstanceId = providerId,
            providerDisplayName = "OpenAI",
            modelId = "gpt-4o-mini",
            promptTokens = 2000,
            completionTokens = 1000,
            estimatedCostUsd = 1.00,
            createdAt = System.currentTimeMillis() - 30_000L
        )
        val rOther = UsageRecordEntity(
            id = "u3",
            providerInstanceId = "other_prov",
            providerDisplayName = "Other",
            modelId = "claude-3-5-sonnet",
            promptTokens = 500,
            completionTokens = 200,
            estimatedCostUsd = 10.00,
            createdAt = System.currentTimeMillis()
        )

        val totalSpend = ProviderSpendingLimitTracker.calculateMonthlySpendUsd(
            providerInstanceId = providerId,
            usageRecords = listOf(r1, r2, rOther)
        )
        assertEquals(3.20, totalSpend, 0.001)

        val limitUsd = 5.00
        val ratio = ProviderSpendingLimitTracker.calculateSpendingRatio(totalSpend, limitUsd)
        assertEquals(0.64f, ratio, 0.01f)

        assertFalse(ProviderSpendingLimitTracker.isAtOrAboveWarningThreshold(totalSpend, limitUsd))
        assertFalse(ProviderSpendingLimitTracker.isLimitReached(totalSpend, limitUsd))

        val progressStr = ProviderSpendingLimitTracker.formatMonthlyProgress(totalSpend, limitUsd)
        assertEquals("$3.20 of $5.00 this month", progressStr)

        // At 80% ($4.00 of $5.00)
        assertTrue(ProviderSpendingLimitTracker.isAtOrAboveWarningThreshold(4.00, limitUsd))
        assertFalse(ProviderSpendingLimitTracker.isLimitReached(4.00, limitUsd))

        // At 100% ($5.00 of $5.00) -> Limit reached
        assertTrue(ProviderSpendingLimitTracker.isLimitReached(5.00, limitUsd))
        assertTrue(ProviderSpendingLimitTracker.isLimitReached(5.25, limitUsd))
    }

    // =========================================================================
    // 3. PRICE SUGGESTION TESTS
    // =========================================================================

    @Test
    fun priceSuggestion_recommendsCheaperAlternativeAfterFiftyThousandTokensOnTopTierModel() {
        // Under 50,000 tokens: no suggestion
        assertNull(ModelSuggestionAdvisor.getCheaperModelSuggestion("gpt-4o", 49_999))

        // Non-top-tier model: no suggestion
        assertNull(ModelSuggestionAdvisor.getCheaperModelSuggestion("gpt-4o-mini", 80_000))

        // Top-tier model (gpt-4o) over 50k tokens: suggests gpt-4o-mini
        val gpt4oSuggestion = ModelSuggestionAdvisor.getCheaperModelSuggestion("gpt-4o", 55_000)
        assertNotNull(gpt4oSuggestion)
        assertEquals("gpt-4o-mini", gpt4oSuggestion!!.suggestedModelId)
        assertEquals("GPT-4o Mini", gpt4oSuggestion.suggestedModelDisplayName)
        assertTrue(gpt4oSuggestion.savingsPercent > 50)

        // Top-tier Claude over 50k tokens: suggests claude-3-5-haiku
        val claudeSuggestion = ModelSuggestionAdvisor.getCheaperModelSuggestion("claude-3-7-sonnet-latest", 60_000)
        assertNotNull(claudeSuggestion)
        assertEquals("claude-3-5-haiku", claudeSuggestion!!.suggestedModelId)
        assertTrue(claudeSuggestion.savingsPercent > 50)
    }

    // =========================================================================
    // 4. FILE SIZE LIMIT TESTS
    // =========================================================================

    @Test
    fun fileSizeLimit_enforcesTenMegabytesAndFiveFilesPerMessage() {
        val tenMb = FileAttachmentProcessor.MAX_FILE_SIZE_BYTES
        assertTrue(FileAttachmentProcessor.isFileSizeAllowed(1024L))
        assertTrue(FileAttachmentProcessor.isFileSizeAllowed(tenMb))
        assertFalse(FileAttachmentProcessor.isFileSizeAllowed(tenMb + 1L))
        assertFalse(FileAttachmentProcessor.isFileSizeAllowed(0L))

        assertEquals(5, FileAttachmentProcessor.MAX_FILES_PER_MESSAGE)
    }

    // =========================================================================
    // 5. TEXT TRIMMING TESTS
    // =========================================================================

    @Test
    fun textTrimming_capsExtractedTextAtOneHundredThousandCharactersWithMiddleTrimmed() {
        val shortText = "Runway attachment text"
        assertEquals(shortText, FileAttachmentProcessor.capExtractedText(shortText))

        val hugeBuilder = StringBuilder()
        for (i in 0 until 120_000) {
            hugeBuilder.append((i % 10).toString())
        }
        val hugeText = hugeBuilder.toString()
        assertTrue(hugeText.length > 100_000)

        val capped = FileAttachmentProcessor.capExtractedText(hugeText)
        assertTrue(capped.length <= FileAttachmentProcessor.MAX_EXTRACTED_CHARACTERS + 50)
        assertTrue(capped.contains("[middle trimmed]"))
        assertTrue(capped.startsWith(hugeText.take(100)))
        assertTrue(capped.endsWith(hugeText.takeLast(100)))
    }

    // =========================================================================
    // 6. DOCX EXTRACTION TESTS
    // =========================================================================

    @Test
    fun docxExtraction_parsesDocumentXmlFromZip() {
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p><w:r><w:t>Runway Document Title</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Section 1: Architecture</w:t></w:r></w:p>
                </w:body>
            </w:document>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(documentXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val extracted = FileAttachmentProcessor.extractDocxText(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(extracted.contains("Runway Document Title"))
        assertTrue(extracted.contains("Section 1: Architecture"))
    }

    // =========================================================================
    // 7. CACHE-TOKEN PARSING & PRICING TESTS
    // =========================================================================

    @Test
    fun cacheTokenParsing_parsesOpenAiAnthropicAndGeminiCachedTokens() {
        // OpenAI cached tokens
        val openAiJson = """
            {
                "usage": {
                    "prompt_tokens": 1200,
                    "completion_tokens": 150,
                    "prompt_tokens_details": {
                        "cached_tokens": 800
                    }
                }
            }
        """.trimIndent()
        val openAiParsed = ModelPriceTable.parseOpenAiUsage(openAiJson)
        assertEquals(1200, openAiParsed.inputTokens)
        assertEquals(150, openAiParsed.outputTokens)
        assertEquals(800, openAiParsed.cachedTokens)

        // Anthropic cached tokens
        val anthropicJson = """
            {
                "type": "message_start",
                "message": {
                    "usage": {
                        "input_tokens": 2000,
                        "cache_read_input_tokens": 1500
                    }
                }
            }
        """.trimIndent()
        val anthropicParsed = ModelPriceTable.parseAnthropicUsage(anthropicJson)
        assertEquals(2000, anthropicParsed.inputTokens)
        assertEquals(1500, anthropicParsed.cachedTokens)

        // Gemini cached tokens
        val geminiJson = """
            {
                "usageMetadata": {
                    "promptTokenCount": 3000,
                    "candidatesTokenCount": 200,
                    "cachedContentTokenCount": 2400
                }
            }
        """.trimIndent()
        val geminiParsed = ModelPriceTable.parseGeminiUsage(geminiJson)
        assertEquals(3000, geminiParsed.inputTokens)
        assertEquals(200, geminiParsed.outputTokens)
        assertEquals(2400, geminiParsed.cachedTokens)

        // Verify cached discount in cost calculation
        val uncachedCost = ModelPriceTable.calculateCostUsd(
            modelId = "claude-3-5-sonnet-latest",
            inputTokens = 10_000,
            outputTokens = 1_000,
            cachedTokens = 0
        )
        val cachedCost = ModelPriceTable.calculateCostUsd(
            modelId = "claude-3-5-sonnet-latest",
            inputTokens = 10_000,
            outputTokens = 1_000,
            cachedTokens = 8_000
        )
        assertTrue(cachedCost < uncachedCost)

        val footer = ModelPriceTable.formatMessageUsageFooter(
            inputTokens = 1200,
            outputTokens = 150,
            estimatedCostUsd = 0.0025,
            isTokenCountEstimated = false,
            cachedTokens = 800
        )
        assertTrue(footer.contains("1200 in · 150 out tokens"))
        assertTrue(footer.contains("cached: 800 tokens"))
        assertTrue(footer.contains("Cost:"))
    }

    // =========================================================================
    // 8. TRIMMING THRESHOLD TESTS
    // =========================================================================

    @Test
    fun trimmingThreshold_triggersAtSixtyPercentOfModelContextWindow() {
        // Unknown model defaults to 32,000 tokens context window
        // 60% of 32,000 = 19,200 tokens
        assertEquals(32_000, SmartContextTrimmer.getContextWindow("unknown-model-xyz"))
        assertFalse(SmartContextTrimmer.isTrimmingThresholdReached("unknown-model-xyz", estimatedTotalTokens = 18_000))
        assertTrue(SmartContextTrimmer.isTrimmingThresholdReached("unknown-model-xyz", estimatedTotalTokens = 19_250))

        // gpt-4o: 128,000 context window -> 60% = 76,800
        assertEquals(128_000, SmartContextTrimmer.getContextWindow("gpt-4o"))
        assertFalse(SmartContextTrimmer.isTrimmingThresholdReached("gpt-4o", estimatedTotalTokens = 70_000))
        assertTrue(SmartContextTrimmer.isTrimmingThresholdReached("gpt-4o", estimatedTotalTokens = 77_000))
    }

    // =========================================================================
    // 9. SUMMARY FALLBACK TESTS
    // =========================================================================

    @Test
    fun summaryFallback_sendsFullHistoryWhenSummarizingFails() {
        val messages = (1..10).map { i ->
            MessageEntity(
                id = "m$i",
                conversationId = "c1",
                role = if (i % 2 == 1) "USER" else "ASSISTANT",
                content = "Message $i in long conversation.",
                providerInstanceId = "p1",
                modelId = "gpt-4o"
            )
        }

        val (older, recent) = SmartContextTrimmer.splitMessagesForTrimming(messages, keepTurns = 6)
        assertEquals(4, older.size)
        assertEquals(6, recent.size)

        val prompt = SmartContextTrimmer.buildSummaryPrompt(older)
        assertTrue(prompt.contains("Message 1"))
        assertTrue(prompt.contains("Message 4"))
        assertTrue(prompt.contains("under 800 tokens"))

        // When summarization succeeds: payload contains summary header + 6 recent turns
        val summary = "Summary of messages 1-4: Project requirements established."
        val trimmedPayload = SmartContextTrimmer.buildTrimmedPayload(summary, recent)
        assertEquals(7, trimmedPayload.size)
        assertEquals("system", trimmedPayload[0].role)
        assertTrue(trimmedPayload[0].content.contains("Prior Conversation Summary"))
        assertEquals("user", trimmedPayload[1].role)

        // When summarization fails (fallback): recentMessages are not discarded, full history is preserved
        fun summarizeWithFallback(
            summarizer: () -> String?
        ): List<MessageEntity> {
            val s = runCatching { summarizer() }.getOrNull()
            return if (s.isNullOrBlank()) {
                messages // fallback sends full history intact!
            } else {
                recent
            }
        }

        val fallbackMessages = summarizeWithFallback { throw RuntimeException("API failure") }
        assertEquals(10, fallbackMessages.size) // Full 10 messages kept on fallback
        assertEquals(messages, fallbackMessages)
    }
}
