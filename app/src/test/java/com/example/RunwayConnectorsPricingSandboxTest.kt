package com.example

import com.example.data.local.McpApprovalMode
import com.example.data.local.McpServerEntity
import com.example.data.local.McpToolEntity
import com.example.data.local.McpTransportType
import com.example.data.mcp.ConnectorApprovalPolicy
import com.example.data.mcp.JsSandboxExecutor
import com.example.data.mcp.RunnerLanguage
import com.example.data.mcp.ToolApprovalChoice
import com.example.data.mcp.ToolExecutionGate
import com.example.data.provider.ModelPriceTable
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunwayConnectorsPricingSandboxTest {

    private val sampleServer = McpServerEntity(
        id = "mcp_github_test",
        name = "GitHub",
        url = "https://api.githubcopilot.com/mcp/",
        transport = McpTransportType.STREAMABLE_HTTP.name,
        authHeaderName = "Authorization",
        approvalMode = McpApprovalMode.ASK_EVERY_TIME.name,
        isEnabled = true,
        lastTestStatus = "Connected"
    )

    private val readTool = McpToolEntity(
        id = "mcp_github_test:get_repository",
        serverId = "mcp_github_test",
        serverName = "GitHub",
        toolName = "get_repository",
        description = "Read repository metadata and branches",
        inputSchemaJson = """{"type":"object"}""",
        isReadOnlyHint = true,
        isEnabled = true
    )

    private val writeTool = McpToolEntity(
        id = "mcp_github_test:create_pull_request",
        serverId = "mcp_github_test",
        serverName = "GitHub",
        toolName = "create_pull_request",
        description = "Create a new pull request in the repository",
        inputSchemaJson = """{"type":"object"}""",
        isReadOnlyHint = false,
        isEnabled = true
    )

    private val deleteTool = McpToolEntity(
        id = "mcp_github_test:delete_branch",
        serverId = "mcp_github_test",
        serverName = "GitHub",
        toolName = "delete_branch",
        description = "Delete a branch from the repository",
        inputSchemaJson = """{"type":"object"}""",
        isReadOnlyHint = false,
        isEnabled = true
    )

    // =========================================================================
    // 1. CONNECTOR & TOOL APPROVAL LOGIC TESTS
    // =========================================================================

    @Test
    fun writeAndDeleteTools_areDetectedAndDefaultToAsk() {
        assertFalse(ConnectorApprovalPolicy.isWriteOrDeleteTool(readTool))
        assertTrue(ConnectorApprovalPolicy.isWriteOrDeleteTool(writeTool))
        assertTrue(ConnectorApprovalPolicy.isWriteOrDeleteTool(deleteTool))

        assertEquals(ToolApprovalChoice.ASK, ConnectorApprovalPolicy.defaultChoiceForTool(writeTool))
        assertEquals(ToolApprovalChoice.ASK, ConnectorApprovalPolicy.defaultChoiceForTool(deleteTool))
    }

    @Test
    fun writeAndDeleteTools_requireConfirmationBeforeSettingToAlways() {
        // Read-only tool does not require confirmation for Always
        assertFalse(
            ConnectorApprovalPolicy.requiresConfirmationToSetToolChoice(
                tool = readTool,
                targetChoice = ToolApprovalChoice.ALWAYS
            )
        )

        // Write and delete tools require confirmation for Always
        assertTrue(
            ConnectorApprovalPolicy.requiresConfirmationToSetToolChoice(
                tool = writeTool,
                targetChoice = ToolApprovalChoice.ALWAYS
            )
        )
        assertTrue(
            ConnectorApprovalPolicy.requiresConfirmationToSetToolChoice(
                tool = deleteTool,
                targetChoice = ToolApprovalChoice.ALWAYS
            )
        )

        // Setting write/delete tools to Ask or Never does not require confirmation
        assertFalse(
            ConnectorApprovalPolicy.requiresConfirmationToSetToolChoice(
                tool = writeTool,
                targetChoice = ToolApprovalChoice.ASK
            )
        )
        assertFalse(
            ConnectorApprovalPolicy.requiresConfirmationToSetToolChoice(
                tool = deleteTool,
                targetChoice = ToolApprovalChoice.NEVER
            )
        )
    }

    @Test
    fun unconfirmedWriteTool_fallsBackToAskEvenIfConnectorDefaultIsAlways() {
        val connectorDefaults = mapOf(sampleServer.id to ToolApprovalChoice.ALWAYS)
        val perTool = emptyMap<String, ToolApprovalChoice>()
        val confirmedWriteIds = emptySet<String>()

        // Read tool inherits ALWAYS from connector default
        assertEquals(
            ToolApprovalChoice.ALWAYS,
            ConnectorApprovalPolicy.resolveToolChoice(
                tool = readTool,
                server = sampleServer,
                connectorDefaultPolicies = connectorDefaults,
                perToolPolicies = perTool,
                confirmedWriteAlwaysToolIds = confirmedWriteIds
            )
        )

        // Write tool stays ASK until confirmed
        assertEquals(
            ToolApprovalChoice.ASK,
            ConnectorApprovalPolicy.resolveToolChoice(
                tool = writeTool,
                server = sampleServer,
                connectorDefaultPolicies = connectorDefaults,
                perToolPolicies = perTool,
                confirmedWriteAlwaysToolIds = confirmedWriteIds
            )
        )

        // Once confirmed, write tool can resolve to ALWAYS
        assertEquals(
            ToolApprovalChoice.ALWAYS,
            ConnectorApprovalPolicy.resolveToolChoice(
                tool = writeTool,
                server = sampleServer,
                connectorDefaultPolicies = connectorDefaults,
                perToolPolicies = mapOf(writeTool.id to ToolApprovalChoice.ALWAYS),
                confirmedWriteAlwaysToolIds = setOf(writeTool.id)
            )
        )
    }

    @Test
    fun setAllTools_appliesChoiceAcrossConnectorAndRespectsWriteConfirmation() {
        val allTools = listOf(readTool, writeTool, deleteTool)

        // Requires confirmation when setting all tools to ALWAYS if write/delete tools exist
        assertTrue(
            ConnectorApprovalPolicy.requiresConfirmationForSetAllTools(
                tools = allTools,
                targetChoice = ToolApprovalChoice.ALWAYS
            )
        )

        // If user applies ALWAYS without confirming write tools, read tools become ALWAYS and write/delete stay ASK
        val (unconfirmedPolicies, _) = ConnectorApprovalPolicy.applySetAllTools(
            tools = allTools,
            targetChoice = ToolApprovalChoice.ALWAYS,
            existingPerToolPolicies = emptyMap(),
            existingConfirmedWriteIds = emptySet(),
            userConfirmedWriteTools = false
        )
        assertEquals(ToolApprovalChoice.ALWAYS, unconfirmedPolicies[readTool.id])
        assertEquals(ToolApprovalChoice.ASK, unconfirmedPolicies[writeTool.id])
        assertEquals(ToolApprovalChoice.ASK, unconfirmedPolicies[deleteTool.id])

        // If user confirms write tools in confirmation dialog, all tools become ALWAYS
        val (confirmedPolicies, confirmedIds) = ConnectorApprovalPolicy.applySetAllTools(
            tools = allTools,
            targetChoice = ToolApprovalChoice.ALWAYS,
            existingPerToolPolicies = emptyMap(),
            existingConfirmedWriteIds = emptySet(),
            userConfirmedWriteTools = true
        )
        assertEquals(ToolApprovalChoice.ALWAYS, confirmedPolicies[readTool.id])
        assertEquals(ToolApprovalChoice.ALWAYS, confirmedPolicies[writeTool.id])
        assertEquals(ToolApprovalChoice.ALWAYS, confirmedPolicies[deleteTool.id])
        assertTrue(confirmedIds.contains(writeTool.id))
        assertTrue(confirmedIds.contains(deleteTool.id))
    }

    @Test
    fun evaluateToolExecutionGate_enforcesAlwaysAskAndNever() {
        // NEVER -> BLOCK
        val neverGate = ConnectorApprovalPolicy.evaluateToolExecutionGate(
            tool = readTool,
            server = sampleServer,
            connectorDefaultPolicies = emptyMap(),
            perToolPolicies = mapOf(readTool.id to ToolApprovalChoice.NEVER),
            confirmedWriteAlwaysToolIds = emptySet(),
            isConversationReadOnlyApproved = true
        )
        assertEquals(ToolExecutionGate.BLOCK, neverGate)

        // ALWAYS -> AUTO_RUN
        val alwaysGate = ConnectorApprovalPolicy.evaluateToolExecutionGate(
            tool = readTool,
            server = sampleServer,
            connectorDefaultPolicies = emptyMap(),
            perToolPolicies = mapOf(readTool.id to ToolApprovalChoice.ALWAYS),
            confirmedWriteAlwaysToolIds = emptySet(),
            isConversationReadOnlyApproved = false
        )
        assertEquals(ToolExecutionGate.AUTO_RUN, alwaysGate)

        // ASK -> ASK_USER (when not approved for session)
        val askGate = ConnectorApprovalPolicy.evaluateToolExecutionGate(
            tool = writeTool,
            server = sampleServer,
            connectorDefaultPolicies = emptyMap(),
            perToolPolicies = mapOf(writeTool.id to ToolApprovalChoice.ASK),
            confirmedWriteAlwaysToolIds = emptySet(),
            isConversationReadOnlyApproved = false
        )
        assertEquals(ToolExecutionGate.ASK_USER, askGate)
    }

    @Test
    fun validateMcpServerUrl_allowsHttpsAndApprovedHttpHostsOnly() {
        val httpsCheck = ConnectorApprovalPolicy.validateMcpServerUrl(
            rawUrl = "https://api.githubcopilot.com/mcp/",
            approvedCleartextHosts = emptySet()
        )
        assertTrue(httpsCheck.isValid)
        assertNull(httpsCheck.errorMessage)

        val unapprovedHttpCheck = ConnectorApprovalPolicy.validateMcpServerUrl(
            rawUrl = "http://192.168.1.50:8080/mcp",
            approvedCleartextHosts = emptySet()
        )
        assertFalse(unapprovedHttpCheck.isValid)
        assertTrue(unapprovedHttpCheck.isCleartextHttp)
        assertNotNull(unapprovedHttpCheck.errorMessage)

        val approvedHttpCheck = ConnectorApprovalPolicy.validateMcpServerUrl(
            rawUrl = "http://192.168.1.50:8080/mcp",
            approvedCleartextHosts = setOf("192.168.1.50")
        )
        assertTrue(approvedHttpCheck.isValid)
        assertNull(approvedHttpCheck.errorMessage)

        assertEquals("https://api.githubcopilot.com/mcp/", ConnectorApprovalPolicy.GITHUB_PRESET.serverUrl)
        assertTrue(ConnectorApprovalPolicy.GITHUB_PRESET.tokenNote.contains("read-only fine-grained personal access token"))
    }

    // =========================================================================
    // 2. MODEL PRICE CALCULATION & PROVIDER USAGE PARSING TESTS
    // =========================================================================

    @Test
    fun priceTable_calculatesExpectedCostAndResolvesModels() {
        // gpt-4o: $2.50 / 1M input, $10.00 / 1M output
        val pricing = ModelPriceTable.lookupPricing("openai/gpt-4o")
        assertEquals(2.50, pricing.inputUsdPer1M, 1e-6)
        assertEquals(10.00, pricing.outputUsdPer1M, 1e-6)

        val cost = ModelPriceTable.calculateCostUsd(
            modelId = "gpt-4o",
            inputTokens = 1_000,
            outputTokens = 500
        )
        // 1000 * (2.50 / 1_000_000) + 500 * (10.00 / 1_000_000) = 0.0025 + 0.0050 = 0.0075
        assertEquals(0.0075, cost, 1e-7)
    }

    @Test
    fun providerUsageParsers_extractTokensFromOpenAiAnthropicAndGemini() {
        val openAiJson = """{"usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165}}"""
        val openAiParsed = ModelPriceTable.parseOpenAiUsage(openAiJson)
        assertTrue(openAiParsed.isPresent)
        assertEquals(120, openAiParsed.inputTokens)
        assertEquals(45, openAiParsed.outputTokens)

        val anthropicJson = """{"type":"message_delta","usage":{"input_tokens":310,"output_tokens":95}}"""
        val anthropicParsed = ModelPriceTable.parseAnthropicUsage(anthropicJson)
        assertTrue(anthropicParsed.isPresent)
        assertEquals(310, anthropicParsed.inputTokens)
        assertEquals(95, anthropicParsed.outputTokens)

        val geminiJson = """{"usageMetadata":{"promptTokenCount":250,"candidatesTokenCount":80,"totalTokenCount":330}}"""
        val geminiParsed = ModelPriceTable.parseGeminiUsage(geminiJson)
        assertTrue(geminiParsed.isPresent)
        assertEquals(250, geminiParsed.inputTokens)
        assertEquals(80, geminiParsed.outputTokens)
    }

    @Test
    fun resolveTurnUsage_fallsBackToTextEstimationAndLabelsEstimated() {
        // Provider returned exact usage
        val exactUsage = ModelPriceTable.resolveTurnUsage(
            modelId = "claude-3-5-sonnet-latest",
            reportedInputTokens = 100,
            reportedOutputTokens = 50,
            fallbackInputText = "Hello",
            fallbackOutputText = "Hi there!"
        )
        assertFalse(exactUsage.isTokenCountEstimated)
        assertEquals(100, exactUsage.inputTokens)
        assertEquals(50, exactUsage.outputTokens)
        val exactFooter = ModelPriceTable.formatMessageUsageFooter(
            inputTokens = exactUsage.inputTokens,
            outputTokens = exactUsage.outputTokens,
            estimatedCostUsd = exactUsage.estimatedCostUsd,
            isTokenCountEstimated = exactUsage.isTokenCountEstimated
        )
        assertTrue(exactFooter.contains("100 in"))
        assertTrue(exactFooter.contains("50 out"))
        assertTrue(exactFooter.contains("(estimated)"))

        // Provider returned no usage -> estimates from text and labels tokens + cost as "estimated"
        val estimatedUsage = ModelPriceTable.resolveTurnUsage(
            modelId = "gpt-4o-mini",
            reportedInputTokens = 0,
            reportedOutputTokens = 0,
            fallbackInputText = "Explain quantum computing in simple terms for a beginner.",
            fallbackOutputText = "Quantum computing uses qubits that can represent 0 and 1 simultaneously."
        )
        assertTrue(estimatedUsage.isTokenCountEstimated)
        assertTrue(estimatedUsage.inputTokens > 0)
        assertTrue(estimatedUsage.outputTokens > 0)

        val estimatedFooter = ModelPriceTable.formatMessageUsageFooter(
            inputTokens = estimatedUsage.inputTokens,
            outputTokens = estimatedUsage.outputTokens,
            estimatedCostUsd = estimatedUsage.estimatedCostUsd,
            isTokenCountEstimated = estimatedUsage.isTokenCountEstimated
        )
        assertTrue(estimatedFooter.contains("tokens (estimated)"))
        assertTrue(estimatedFooter.contains("Cost:"))

        val summaryLine = ModelPriceTable.formatConversationUsageSummary(
            totalInputTokens = 500,
            totalOutputTokens = 250,
            totalEstimatedCostUsd = 0.0042,
            toolCallsCount = 3,
            codeRunsCount = 2,
            hasEstimatedTokens = true
        )
        assertTrue(summaryLine.contains("500 in · 250 out tokens (estimated)"))
        assertTrue(summaryLine.contains("3 tool calls"))
        assertTrue(summaryLine.contains("2 code runs"))
        assertTrue(summaryLine.contains("(estimated)"))
    }

    // =========================================================================
    // 3. JAVASCRIPT SANDBOX EXECUTION & LIMITS TESTS
    // =========================================================================

    @Test
    fun jsSandbox_executesValidJavaScriptAndDestroysSandboxAfterEveryRun() = runBlocking {
        val result = JsSandboxExecutor.executeSandboxedJs(
            context = null,
            code = """
                var a = 6;
                var b = 7;
                console.log("Product is " + (a * b));
                a * b
            """.trimIndent()
        )
        assertTrue(result.isSuccess)
        assertFalse(result.isTimedOut)
        assertFalse(result.isMemoryExceeded)
        assertTrue(result.sandboxDestroyed)
        assertTrue(result.outputText.contains("Product is 42"))

        // Verify state from first run does not leak into second run
        val secondRun = JsSandboxExecutor.executeSandboxedJs(
            context = null,
            code = "console.log(typeof a);"
        )
        assertTrue(secondRun.sandboxDestroyed)
        assertTrue(secondRun.outputText.contains("undefined"))
    }

    @Test
    fun jsSandbox_enforcesFiveSecondTimeoutOnInfiniteLoop() = runBlocking {
        val result = JsSandboxExecutor.executeSandboxedJs(
            context = null,
            code = "while (true) { }",
            timeoutMs = 300L // Fast verification of the timeout enforcement mechanism
        )
        assertFalse(result.isSuccess)
        assertTrue(result.isTimedOut)
        assertTrue(result.sandboxDestroyed)
    }

    @Test
    fun jsSandbox_enforcesMemoryCap() = runBlocking {
        val result = JsSandboxExecutor.executeSandboxedJs(
            context = null,
            code = """
                var huge = "x".repeat(1024 * 1024);
            """.trimIndent(),
            maxMemoryBytes = 64 * 1024 // 64 KB cap for unit test verification
        )
        assertFalse(result.isSuccess)
        assertTrue(result.isMemoryExceeded)
        assertTrue(result.sandboxDestroyed)
    }

    @Test
    fun jsSandbox_truncatesOutputToTenKilobytes() = runBlocking {
        val result = JsSandboxExecutor.executeSandboxedJs(
            context = null,
            code = """
                var chunk = "ABCDEFGHIJ".repeat(200);
                for (var i = 0; i < 10; i++) {
                    console.log(chunk);
                }
            """.trimIndent(),
            maxOutputBytes = JsSandboxExecutor.MAX_OUTPUT_BYTES
        )
        assertTrue(result.isSuccess)
        assertTrue(result.isOutputTruncated)
        assertTrue(result.outputText.toByteArray(Charsets.UTF_8).size <= JsSandboxExecutor.MAX_OUTPUT_BYTES)
    }

    @Test
    fun jsSandbox_blocksNetworkFileAndBridgeAccess() = runBlocking {
        val result = JsSandboxExecutor.executeSandboxedJs(
            context = null,
            code = """fetch("https://example.com/secret")"""
        )
        assertFalse(result.isSuccess)
        assertTrue(result.outputText.contains("Security Error", ignoreCase = true))
    }

    @Test
    fun htmlCssJsSandbox_rendersFullHtmlDocumentWithCssAndJs_andEnforcesFourMegabyteLimit() = runBlocking {
        val html = "<div class='card'><h1>Hello Preview</h1></div>"
        val css = ".card { color: #4F46E5; font-weight: bold; }"
        val js = "console.log('Rendered preview widget: ' + (20 + 22));"

        val result = JsSandboxExecutor.executeSandboxedHtmlCssJs(
            context = null,
            html = html,
            css = css,
            js = js
        )
        assertTrue(result.isSuccess)
        assertEquals(RunnerLanguage.HTML, result.language)
        assertNotNull(result.renderedHtmlDocument)
        assertTrue(result.renderedHtmlDocument!!.contains("<!DOCTYPE html>"))
        assertTrue(result.renderedHtmlDocument!!.contains(css))
        assertTrue(result.renderedHtmlDocument!!.contains(html))
        assertTrue(result.outputText.contains("Rendered preview widget: 42"))
        assertTrue(result.sandboxDestroyed)

        // Verify 4 MB document limit enforcement
        val oversizedHtml = "<div>" + "A".repeat(JsSandboxExecutor.MAX_MEMORY_BYTES + 1024) + "</div>"
        val overLimitResult = JsSandboxExecutor.executeSandboxedHtmlCssJs(
            context = null,
            html = oversizedHtml,
            css = "",
            js = ""
        )
        assertFalse(overLimitResult.isSuccess)
        assertTrue(overLimitResult.isMemoryExceeded)
        assertTrue(overLimitResult.sandboxDestroyed)
    }

    @Test
    fun javaRemoteRunner_whenNoRunnerConfigured_returnsClearSetupInstructions() = runBlocking {
        val result = JsSandboxExecutor.executeRemoteJava(
            code = "public class Main { public static void main(String[] args) { System.out.println(\"Hi\"); } }",
            runnerUrl = "",
            apiKey = ""
        )
        assertFalse(result.isSuccess)
        assertEquals(RunnerLanguage.JAVA, result.language)
        assertTrue(result.outputText.contains("No Java remote runner is set up"))
        assertTrue(result.outputText.contains("Settings"))
    }

    @Test
    fun javaRemoteRunner_enforcesTimeoutAndTenKilobyteOutputCap_andNeverLeaksSecretKey() = runBlocking {
        val secretApiKey = "secret_java_runner_vault_key_998877"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/ok-huge") { exchange ->
                val authHeader = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                assertEquals("Bearer $secretApiKey", authHeader)
                // Return more than 10 KB of output plus echo the secret key to verify redaction + 10 KB cap
                val hugeStdout = "Line output data 1234567890\\n".repeat(600) + " leaked=$secretApiKey"
                val jsonBytes = """{"stdout":"$hugeStdout","exitCode":0}""".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, jsonBytes.size.toLong())
                exchange.responseBody.use { it.write(jsonBytes) }
            }
            server.createContext("/slow") { exchange ->
                try {
                    Thread.sleep(1500L)
                } catch (_: InterruptedException) {
                }
                val bytes = """{"stdout":"done"}""".toByteArray(Charsets.UTF_8)
                runCatching {
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
            val port = server.address.port

            // 1. Test 10 KB output cap & key redaction
            val capResult = JsSandboxExecutor.executeRemoteJava(
                code = "public class Main { public static void main(String[] args) {} }",
                runnerUrl = "http://127.0.0.1:$port/ok-huge",
                apiKey = secretApiKey,
                timeoutMs = 5_000L,
                maxOutputBytes = JsSandboxExecutor.MAX_OUTPUT_BYTES
            )
            assertTrue(capResult.isSuccess)
            assertTrue(capResult.isOutputTruncated)
            assertTrue(capResult.outputText.toByteArray(Charsets.UTF_8).size <= JsSandboxExecutor.MAX_OUTPUT_BYTES)
            assertFalse(capResult.outputText.contains(secretApiKey))

            // 2. Test timeout enforcement
            val timeoutResult = JsSandboxExecutor.executeRemoteJava(
                code = "public class Main { public static void main(String[] args) { while(true){} } }",
                runnerUrl = "http://127.0.0.1:$port/slow",
                apiKey = secretApiKey,
                timeoutMs = 300L
            )
            assertFalse(timeoutResult.isSuccess)
            assertTrue(timeoutResult.isTimedOut)
            assertFalse(timeoutResult.outputText.contains(secretApiKey))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun codeRunner_confirmationStep_requiresConfirmationBeforeEveryRun_andSupportsHtmlCssJsAndJavaOnly() {
        // Never run code automatically: confirmation is required before every run
        assertTrue(JsSandboxExecutor.requiresUserConfirmationBeforeEveryRun())

        // Supported languages: HTML, CSS, JavaScript, Java. Python is NOT supported.
        assertTrue(JsSandboxExecutor.isSupportedRunnerLanguage("html"))
        assertTrue(JsSandboxExecutor.isSupportedRunnerLanguage("css"))
        assertTrue(JsSandboxExecutor.isSupportedRunnerLanguage("javascript"))
        assertTrue(JsSandboxExecutor.isSupportedRunnerLanguage("js"))
        assertTrue(JsSandboxExecutor.isSupportedRunnerLanguage("java"))
        assertFalse(JsSandboxExecutor.isSupportedRunnerLanguage("python"))
        assertFalse(JsSandboxExecutor.isSupportedRunnerLanguage("py"))

        // Confirmation details for HTML/CSS/JS include language, code, and 5-second time limit
        val webArgsJson = JsSandboxExecutor.buildRunCodeArgumentsJson(
            language = "html",
            code = "<h1>Hello</h1>",
            css = "h1 { color: red; }",
            js = "console.log('ready');"
        )
        val webConfirm = JsSandboxExecutor.buildRunConfirmationDetails(webArgsJson)
        assertEquals(RunnerLanguage.HTML, webConfirm.language)
        assertEquals("HTML / CSS / JavaScript", webConfirm.languageLabel)
        assertEquals(5, webConfirm.timeLimitSeconds)
        assertEquals(5_000L, webConfirm.timeLimitMs)
        assertEquals(4 * 1024 * 1024, webConfirm.memoryLimitBytes)
        assertEquals(10 * 1024, webConfirm.outputCapBytes)
        assertTrue(webConfirm.code.contains("<h1>Hello</h1>"))
        assertTrue(webConfirm.code.contains("h1 { color: red; }"))
        assertTrue(webConfirm.code.contains("console.log('ready');"))
        assertFalse(webConfirm.isRemoteExecution)
        assertNull(webConfirm.remoteServerNote)

        // Confirmation details for Java include language, code, 5-second time limit, and remote server note
        val javaCode = "public class Main { public static void main(String[] args) { System.out.println(42); } }"
        val javaArgsJson = JsSandboxExecutor.buildRunCodeArgumentsJson(
            language = "java",
            code = javaCode
        )
        val javaConfirm = JsSandboxExecutor.buildRunConfirmationDetails(javaArgsJson)
        assertEquals(RunnerLanguage.JAVA, javaConfirm.language)
        assertEquals("Java", javaConfirm.languageLabel)
        assertEquals(javaCode, javaConfirm.code)
        assertEquals(5, javaConfirm.timeLimitSeconds)
        assertEquals(5_000L, javaConfirm.timeLimitMs)
        assertEquals(10 * 1024, javaConfirm.outputCapBytes)
        assertTrue(javaConfirm.isRemoteExecution)
        assertEquals(JsSandboxExecutor.JAVA_REMOTE_SERVER_NOTE, javaConfirm.remoteServerNote)
        assertTrue(javaConfirm.remoteServerNote!!.contains("remote server"))
        assertTrue(javaConfirm.remoteServerNote!!.contains("leaves the device"))
    }

    @Test
    fun webViewPreviewer_combinesHtmlCssJsStrings_andEnforcesFourMegabyteLimit() {
        val payload = com.example.ui.components.WebViewPreviewerSecurity.preparePreviewPayload(
            html = "<main id='preview'>Hello WebViewPreviewer</main>",
            css = "#preview { color: #10B981; }",
            js = "console.log('preview active');"
        )
        assertFalse(payload.isMemoryLimitExceeded)
        assertNull(payload.errorMessage)
        assertTrue(payload.fullHtmlDocument.contains("<main id='preview'>Hello WebViewPreviewer</main>"))
        assertTrue(payload.fullHtmlDocument.contains("#preview { color: #10B981; }"))
        assertTrue(payload.fullHtmlDocument.contains("console.log('preview active');"))
        assertTrue(payload.fullHtmlDocument.contains("window.__sandboxMaxBytes"))

        val oversizedPayload = com.example.ui.components.WebViewPreviewerSecurity.preparePreviewPayload(
            html = "<p>" + "X".repeat(JsSandboxExecutor.MAX_MEMORY_BYTES + 512) + "</p>",
            css = "",
            js = ""
        )
        assertTrue(oversizedPayload.isMemoryLimitExceeded)
        assertNotNull(oversizedPayload.errorMessage)
        assertTrue(oversizedPayload.fullHtmlDocument.isEmpty())
    }

    @Test
    fun htmlCssJsTemplateCatalog_providesCenteredCard_cssGridLayout_interactiveButton_andOnlineTemplates() = runBlocking {
        val all = com.example.data.mcp.HtmlCssJsTemplateCatalog.allTemplates()
        assertTrue(all.size >= 15)

        val centeredCard = com.example.data.mcp.HtmlCssJsTemplateCatalog.findByTitleOrId("Centered Card")
        val cssGrid = com.example.data.mcp.HtmlCssJsTemplateCatalog.findByTitleOrId("CSS Grid Layout")
        val interactiveBtn = com.example.data.mcp.HtmlCssJsTemplateCatalog.findByTitleOrId("Interactive Button")

        assertNotNull(centeredCard)
        assertNotNull(cssGrid)
        assertNotNull(interactiveBtn)

        assertEquals("Centered Card", centeredCard!!.title)
        assertTrue(centeredCard.html.isNotBlank() && centeredCard.css.isNotBlank() && centeredCard.js.isNotBlank())

        assertEquals("CSS Grid Layout", cssGrid!!.title)
        assertTrue(cssGrid.css.contains("grid"))

        assertEquals("Interactive Button", interactiveBtn!!.title)
        assertTrue(interactiveBtn.js.contains("addEventListener"))

        // Verify online curated templates and category filtering
        val onlineList = com.example.data.mcp.HtmlCssJsTemplateCatalog.filterTemplates(
            category = com.example.data.mcp.HtmlCssJsTemplateCategory.ONLINE_HUB
        )
        assertTrue(onlineList.isNotEmpty())
        assertTrue(onlineList.all { it.isOnlineCurated })

        // Verify live HTTP fetching & HTML/CSS/JS extraction from an online endpoint
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/online-widget.html") { exchange ->
                val rawHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                      <title>Online Glass Widget</title>
                      <style>.widget { display: flex; color: #38bdf8; }</style>
                    </head>
                    <body>
                      <div class="widget" id="w">Hello from Online Template</div>
                      <script>console.log('online widget loaded');</script>
                    </body>
                    </html>
                """.trimIndent()
                val bytes = rawHtml.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val port = server.address.port

            val fetchedResult = com.example.data.mcp.HtmlCssJsTemplateCatalog.fetchOnlineTemplatesFromUrl(
                url = "http://127.0.0.1:$port/online-widget.html"
            )
            assertTrue(fetchedResult.isSuccess)
            val fetched = fetchedResult.getOrThrow()
            assertEquals(1, fetched.size)
            assertEquals("Online Glass Widget", fetched.first().title)
            assertTrue(fetched.first().html.contains("Hello from Online Template"))
            assertTrue(fetched.first().css.contains(".widget { display: flex; color: #38bdf8; }"))
            assertTrue(fetched.first().js.contains("console.log('online widget loaded');"))
        } finally {
            server.stop(0)
        }
    }
}


