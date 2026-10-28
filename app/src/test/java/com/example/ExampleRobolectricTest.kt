package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapabilityTag
import com.example.data.local.GeneratedImageEntity
import com.example.data.local.OmniDatabase
import com.example.data.local.ProviderInstanceEntity
import com.example.data.local.ProviderPresetType
import com.example.data.provider.AnthropicProvider
import com.example.data.provider.ChatCompletionRequest
import com.example.data.provider.ChatMessagePayload
import com.example.data.provider.FalAiProvider
import com.example.data.provider.GeminiProvider
import com.example.data.provider.ImageGenerationRequest
import com.example.data.provider.ModelCapabilityRegistry
import com.example.data.provider.OpenAICompatibleProvider
import com.example.data.provider.ReplicateProvider
import com.example.data.security.TinkSecretVault
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Runway", appName)
    }

    @Test
    fun tinkSecretVault_encryptsAndMasksApiKeysWithAadBinding() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = TinkSecretVault(context)

        val providerId = "provider_omniroute_default"
        val rawKey = "test-key-000"
        vault.saveApiKey(providerId, rawKey)

        val decrypted = vault.getApiKey(providerId)
        assertEquals(rawKey, decrypted)

        val masked = vault.getMaskedApiKey(providerId)
        assertEquals("sk-o••••••••7654", masked)

        // Verify ciphertext is not plaintext and AAD prevents cross-provider decryption
        val cipher = vault.encryptString(rawKey, providerId)
        assertNotEquals(rawKey, cipher)
        val wrongProviderDecrypt = vault.decryptString(cipher, "other_provider_id")
        assertNotEquals(rawKey, wrongProviderDecrypt)
    }

    @Test
    fun roomDatabase_persistsOmniRoutePresetAndMultiInstanceProviders() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OmniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.omniDao()

        val omniPreset = ProviderInstanceEntity(
            id = "provider_omniroute_default",
            presetType = ProviderPresetType.OMNIROUTE.name,
            displayName = "OmniRoute (Default)",
            baseUrl = ProviderPresetType.OMNIROUTE.defaultBaseUrl,
            capabilitiesCsv = "CHAT,IMAGE,VIDEO",
            isDefaultForChat = true,
            defaultChatModelId = "omniroute/auto-combo"
        )
        val openAiKey1 = ProviderInstanceEntity(
            id = "provider_openai_1",
            presetType = ProviderPresetType.OPENAI.name,
            displayName = "OpenAI Personal",
            baseUrl = ProviderPresetType.OPENAI.defaultBaseUrl,
            capabilitiesCsv = "CHAT,IMAGE"
        )
        val openAiKey2 = ProviderInstanceEntity(
            id = "provider_openai_2",
            presetType = ProviderPresetType.OPENAI.name,
            displayName = "OpenAI Work",
            baseUrl = ProviderPresetType.OPENAI.defaultBaseUrl,
            capabilitiesCsv = "CHAT,IMAGE"
        )

        dao.upsertProviders(listOf(omniPreset, openAiKey1, openAiKey2))
        val stored = dao.getProvidersOnce()
        assertEquals(3, stored.size)
        // OmniRoute preset is ordered first by DAO query
        assertEquals("provider_omniroute_default", stored.first().id)
        assertTrue(CapabilityTag.VIDEO in stored.first().capabilitySet())

        db.close()
    }

    @Test
    fun openAiCompatibleProvider_parsesModelsAndOmniRouteCombos() {
        val client = OkHttpClient()
        val provider = OpenAICompatibleProvider(client)
        val sampleJson = """
            {
              "data": [
                {"id": "gpt-4o", "owned_by": "openai"},
                {"id": "omniroute/coding-fallback", "type": "combo"}
              ],
              "combos": [
                {"id": "smart-router-combo", "display_name": "Smart Router Combo"}
              ]
            }
        """.trimIndent()

        val parsed = provider.parseModelsJson("provider_omni", sampleJson)
        assertEquals(3, parsed.size)
        assertTrue(parsed.any { it.modelId == "gpt-4o" && it.supportsVision })
        assertTrue(parsed.any { it.modelId == "omniroute/coding-fallback" && it.isCombo })
        assertTrue(parsed.any { it.modelId == "smart-router-combo" && it.isCombo })
    }

    @Test
    fun anthropicProvider_buildsValidMessagesPayload() {
        val client = OkHttpClient()
        val provider = AnthropicProvider(client)
        val dummyInstance = ProviderInstanceEntity(
            id = "anthropic_1",
            displayName = "Anthropic",
            presetType = "ANTHROPIC",
            baseUrl = "https://api.anthropic.com/v1",
            capabilitiesCsv = "CHAT"
        )
        val request = ChatCompletionRequest(
            provider = dummyInstance,
            apiKey = "test-key",
            modelId = "claude-3-7-sonnet-20250219",
            messages = listOf(ChatMessagePayload(role = "user", content = "Explain quantum computing")),
            systemPrompt = "Be concise",
            temperature = 0.5f,
            maxTokens = 1024,
            topP = 0.9f,
            supportsTemperature = true,
            supportsTopP = true,
            supportsSystemPrompt = true
        )

        val payload = provider.buildAnthropicPayloadJson(request)
        assertEquals("claude-3-7-sonnet-20250219", payload.getString("model"))
        assertEquals("Be concise", payload.getString("system"))
        assertEquals(true, payload.getBoolean("stream"))
        assertEquals(1, payload.getJSONArray("messages").length())
    }

    @Test
    fun geminiProvider_buildsValidGenerateContentPayload() {
        val client = OkHttpClient()
        val provider = GeminiProvider(client)
        val dummyInstance = ProviderInstanceEntity(
            id = "gemini_1",
            displayName = "Google Gemini",
            presetType = "GEMINI",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            capabilitiesCsv = "CHAT"
        )
        val request = ChatCompletionRequest(
            provider = dummyInstance,
            apiKey = "test-key",
            modelId = "gemini-2.5-flash",
            messages = listOf(ChatMessagePayload(role = "user", content = "Write a Kotlin coroutine test")),
            systemPrompt = "Provide runnable code",
            temperature = 0.7f,
            maxTokens = 2048,
            topP = 0.95f,
            supportsTemperature = true,
            supportsTopP = true,
            supportsSystemPrompt = true
        )

        val payload = provider.buildGeminiPayloadJson(request)
        assertTrue(payload.has("systemInstruction"))
        assertEquals(1, payload.getJSONArray("contents").length())
        assertEquals(2048, payload.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test
    fun phase3_imageCapabilitiesAndAdapters_buildValidPayloadsAndPersistGallery() = runBlocking {
        // 1. Dynamic capability matrix verification
        val dalleCaps = ModelCapabilityRegistry.getImageCapabilities("dall-e-3", "OPENAI")
        assertFalse(dalleCaps.supportsNegativePrompt)
        assertTrue(dalleCaps.supportsQuality)
        assertEquals(1, dalleCaps.maxCount)

        val fluxCaps = ModelCapabilityRegistry.getImageCapabilities("black-forest-labs/flux-1.1-pro", "REPLICATE")
        assertTrue(fluxCaps.supportsSeed)
        assertTrue(fluxCaps.supportsGuidanceScale)
        assertTrue(fluxCaps.supportsImageToImage)
        assertTrue(fluxCaps.supportsUpscale)

        // 2. Replicate & fal.ai payload verification
        val client = OkHttpClient()
        val replicateProvider = ReplicateProvider(client)
        val falProvider = FalAiProvider(client)
        val dummyProvider = ProviderInstanceEntity(
            id = "provider_replicate_default",
            presetType = ProviderPresetType.REPLICATE.name,
            displayName = "Replicate",
            baseUrl = ProviderPresetType.REPLICATE.defaultBaseUrl,
            capabilitiesCsv = "IMAGE"
        )
        val imageReq = ImageGenerationRequest(
            provider = dummyProvider,
            apiKey = "r8_test_token",
            modelId = "black-forest-labs/flux-1.1-pro",
            prompt = "A brutalist concrete library in a misty pine forest",
            negativePrompt = "blurry, watermark",
            aspectRatio = "16:9",
            size = "1344x768",
            count = 1,
            seed = 424242L,
            stylePreset = "Cinematic",
            guidanceScale = 7.5f,
            steps = 28
        )
        val repJson = replicateProvider.buildReplicateInputPayload(imageReq, imageReq.modelId)
        assertTrue(repJson.getJSONObject("input").getString("prompt").contains("Cinematic style"))
        assertEquals("16:9", repJson.getJSONObject("input").getString("aspect_ratio"))
        assertEquals(424242L, repJson.getJSONObject("input").getLong("seed"))

        val falJson = falProvider.buildFalPayloadJson(imageReq)
        assertEquals("landscape_16_9", falJson.getString("image_size"))
        assertEquals(424242L, falJson.getLong("seed"))

        // 3. Room Local Image Gallery persistence verification
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OmniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.omniDao()

        val savedImg = GeneratedImageEntity(
            id = "img_test_01",
            providerInstanceId = "provider_omniroute_default",
            providerDisplayName = "OmniRoute (Default)",
            modelId = "omniroute/flux-combo",
            prompt = "A brutalist concrete library in a misty pine forest",
            negativePrompt = "blurry",
            aspectRatio = "16:9",
            sizeLabel = "1344x768",
            stylePreset = "Cinematic",
            seed = 424242L,
            guidanceScale = 7.5f,
            steps = 28,
            quality = "hd",
            imageUri = "/data/user/0/com.example/files/generated_images/test.png",
            estimatedCostUsd = 0.04
        )
        dao.upsertGeneratedImage(savedImg)
        val gallery = dao.getGeneratedImagesOnce()
        assertEquals(1, gallery.size)
        assertEquals("omniroute/flux-combo", gallery.first().modelId)
        assertEquals(424242L, gallery.first().seed)

        db.close()
    }

    @Test
    fun phase4_videoAdaptersAndRoomVideoJobQueue_buildValidPayloadsAndPersistJobs() = runBlocking {
        // 1. Video capability registry verification
        val lumaCaps = ModelCapabilityRegistry.getVideoCapabilities("ray-2", "LUMA")
        assertTrue(lumaCaps.supportsImageToVideo)
        assertTrue(lumaCaps.supportsCameraMotion)
        assertTrue(9 in lumaCaps.supportedDurations)

        // 2. Luma Dream Machine & Runway payload builders
        val client = OkHttpClient()
        val lumaProvider = com.example.data.provider.LumaVideoProvider(client)
        val runwayProvider = com.example.data.provider.RunwayVideoProvider(client)

        val lumaInstance = ProviderInstanceEntity(
            id = "provider_luma_default",
            presetType = ProviderPresetType.LUMA.name,
            displayName = "Luma Dream Machine",
            baseUrl = ProviderPresetType.LUMA.defaultBaseUrl,
            capabilitiesCsv = "VIDEO"
        )
        val videoReq = com.example.data.provider.VideoGenerationRequest(
            provider = lumaInstance,
            apiKey = "luma_test_key",
            modelId = "ray-2",
            prompt = "A futuristic maglev train gliding through snowy mountains",
            aspectRatio = "16:9",
            resolution = "1080p",
            durationSeconds = 5,
            cameraMotion = "Drone Flyover",
            sourceImageDataUri = "https://example.com/frame0.jpg"
        )

        val lumaJson = lumaProvider.buildLumaPayloadJson(videoReq)
        assertEquals("ray-2", lumaJson.getString("model"))
        assertTrue(lumaJson.getString("prompt").contains("Drone Flyover"))
        assertEquals("5s", lumaJson.getString("duration"))
        assertTrue(lumaJson.has("keyframes"))

        val runwayJson = runwayProvider.buildRunwayPayloadJson(
            videoReq.copy(modelId = "gen3a_turbo", durationSeconds = 10)
        )
        assertEquals("gen3a_turbo", runwayJson.getString("model"))
        assertEquals(10, runwayJson.getInt("duration"))
        assertEquals("1280:768", runwayJson.getString("ratio"))

        // 3. Room VideoJobEntity persistence & active queue query
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OmniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.omniDao()

        val queuedJob = com.example.data.local.VideoJobEntity(
            id = "vid_test_01",
            providerInstanceId = "provider_luma_default",
            providerDisplayName = "Luma Dream Machine",
            modelId = "ray-2",
            prompt = "A futuristic maglev train gliding through snowy mountains",
            aspectRatio = "16:9",
            resolution = "1080p",
            durationSeconds = 5,
            cameraMotion = "Drone Flyover",
            remoteJobId = "gen_998877",
            status = "PROCESSING",
            progressPercent = 65
        )
        dao.upsertVideoJob(queuedJob)

        val activeJobs = dao.getActiveVideoJobsOnce()
        assertEquals(1, activeJobs.size)
        assertEquals("gen_998877", activeJobs.first().remoteJobId)

        dao.upsertVideoJob(
            queuedJob.copy(
                status = "COMPLETED",
                progressPercent = 100,
                videoUri = "https://example.com/output.mp4"
            )
        )
        assertEquals(0, dao.getActiveVideoJobsOnce().size)
        assertEquals("COMPLETED", dao.getVideoJobById("vid_test_01")?.status)

        db.close()
    }

    @Test
    fun phase5_githubAndYouTubePkceIntegrations_buildValidPayloadsAndPersistAuditLogs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. RFC 7636 Appendix B PKCE S256 Code Challenge Verification
        val rfc7636Verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = com.example.data.integrations.YouTubeIntegrationClient.generateCodeChallengeS256(rfc7636Verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)

        val authUrl = com.example.data.integrations.YouTubeIntegrationClient.buildAuthorizationUrl(
            clientId = "123456-test.apps.googleusercontent.com",
            codeChallenge = challenge
        )
        assertTrue(authUrl.contains("code_challenge_method=S256"))
        assertTrue(authUrl.contains("youtube.upload"))

        // 2. YouTube Data API v3 Resumable Upload Metadata JSON Builder
        val ytMeta = com.example.data.integrations.YouTubeIntegrationClient.buildVideoMetadataJson(
            title = "OmniAI Studio Demo Clip",
            description = "Generated via Luma Ray 2",
            tags = listOf("OmniAIStudio", "LumaRay2"),
            privacyStatus = "private"
        )
        assertEquals("OmniAI Studio Demo Clip", ytMeta.getJSONObject("snippet").getString("title"))
        assertEquals("private", ytMeta.getJSONObject("status").getString("privacyStatus"))

        // 3. GitHub Gist & Repository Commit Payload Builders
        val gistPayload = com.example.data.integrations.GitHubIntegrationClient.buildGistPayloadJson(
            description = "Exported Chat",
            fileName = "chat.md",
            content = "# Hello OmniAI",
            isPublic = false
        )
        assertFalse(gistPayload.getBoolean("public"))
        assertEquals(
            "# Hello OmniAI",
            gistPayload.getJSONObject("files").getJSONObject("chat.md").getString("content")
        )

        val commitPayload = com.example.data.integrations.GitHubIntegrationClient.buildRepoCommitPayloadJson(
            content = "# Hello OmniAI",
            commitMessage = "Add chat.md",
            branch = "main",
            existingSha = "abc123sha"
        )
        assertEquals("Add chat.md", commitPayload.getString("message"))
        assertEquals("main", commitPayload.getString("branch"))
        assertEquals("abc123sha", commitPayload.getString("sha"))

        // 4. Tink Secret Vault Integration Token Encryption & Room IntegrationAuditLogEntity
        val vault = TinkSecretVault(context)
        vault.saveApiKey(TinkSecretVault.INTEGRATION_GITHUB_PAT_ID, "test-token-000")
        assertEquals("ghp_••••••••3456", vault.getMaskedApiKey(TinkSecretVault.INTEGRATION_GITHUB_PAT_ID))

        val db = Room.inMemoryDatabaseBuilder(context, OmniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.omniDao()

        dao.insertIntegrationLog(
            com.example.data.local.IntegrationAuditLogEntity(
                id = "audit_01",
                service = "GITHUB",
                actionType = "GIST",
                title = "Exported Chat",
                targetDestination = "Secret Gist • chat.md",
                externalUrl = "https://gist.github.com/12345",
                status = "SUCCESS",
                details = "Published chat.md"
            )
        )
        val logs = dao.getIntegrationLogsOnce()
        assertEquals(1, logs.size)
        assertEquals("GITHUB", logs.first().service)
        assertEquals("https://gist.github.com/12345", logs.first().externalUrl)

        db.close()
    }

    @Test
    fun phase6_skillMarkdownParserAndMcpJsonRpcAndToolLoop_workCorrectly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. SKILL.md Frontmatter Parser & Auto-Select Keyword Matching
        val rawSkillMd = """
            ---
            name: android-compose-architect
            description: Enforces Kotlin Coroutines Flow, MVVM, Room KSP, and Material 3 best practices
            ---
            # Android Compose Architect
            - Use StateFlow and collectAsStateWithLifecycle.
        """.trimIndent()

        val parsed = com.example.data.mcp.SkillMarkdownParser.parseSkillMarkdown(rawSkillMd)
        assertEquals("android-compose-architect", parsed.name)
        assertTrue(parsed.description.contains("Kotlin Coroutines Flow"))
        assertTrue(parsed.bodyInstructions.contains("StateFlow"))

        val skillA = com.example.data.mcp.SkillMarkdownParser.createSkillEntity(
            existingId = "skill_android",
            rawMarkdown = rawSkillMd,
            isEnabled = true
        )
        val skillB = com.example.data.mcp.SkillMarkdownParser.createSkillEntity(
            existingId = "skill_sql",
            rawMarkdown = "---\nname: postgres-tuning\ndescription: PostgreSQL query optimization and indexing\n---\nUse EXPLAIN ANALYZE.",
            isEnabled = true
        )

        val autoSelectConv = com.example.data.local.ConversationEntity(
            id = "conv_auto",
            title = "Auto Select Test",
            providerInstanceId = "prov_test",
            modelId = "gpt-4o",
            autoSelectSkills = true
        )
        val matchedForCompose = com.example.data.mcp.SkillMarkdownParser.resolveActiveSkillsForTurn(
            allSkills = listOf(skillA, skillB),
            conversation = autoSelectConv,
            userPrompt = "Help me refactor my Kotlin Coroutines StateFlow in Jetpack Compose"
        )
        assertEquals(1, matchedForCompose.size)
        assertEquals("android-compose-architect", matchedForCompose.first().name)

        val injectedPrompt = com.example.data.mcp.SkillMarkdownParser.buildSystemPromptWithSkills(
            baseSystemPrompt = "You are a helpful assistant.",
            activeSkills = matchedForCompose
        )
        assertTrue(injectedPrompt.contains("android-compose-architect"))

        // 2. Read-only vs Write/Delete Tool Safety Classifier
        assertTrue(com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool("search_repositories", "Search public GitHub repos", null))
        assertFalse(
            com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool(
                "delete_file",
                "Delete a file from disk",
                org.json.JSONObject().put("destructiveHint", true)
            )
        )
        assertFalse(com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool("execute_sql_mutation", "Write rows to table", null))

        // 3. Multi-Format Tool Definitions & Tool Call Round-Trip (OpenAI, Anthropic, Gemini)
        val client = OkHttpClient()
        val openAiProvider = OpenAICompatibleProvider(client)
        val anthropicProvider = AnthropicProvider(client)
        val geminiProvider = GeminiProvider(client)

        val dummyProv = ProviderInstanceEntity(
            id = "prov_test",
            presetType = ProviderPresetType.OMNIROUTE.name,
            displayName = "OmniRoute",
            baseUrl = "https://api.example.com/v1",
            capabilitiesCsv = "CHAT"
        )
        val mcpToolDef = com.example.data.provider.McpToolDefinition(
            serverId = "mcp_1",
            serverName = "Local Bridge",
            toolName = "get_weather",
            description = "Read current weather for a city",
            inputSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
            isReadOnlyHint = true
        )
        val toolCallReq = com.example.data.provider.ModelToolCallRequest(
            id = "call_weather_1",
            toolName = "get_weather",
            argumentsJson = """{"city":"Tokyo"}"""
        )
        val messagesWithToolLoop = listOf(
            com.example.data.provider.ChatMessagePayload(role = "user", content = "What is the weather in Tokyo?"),
            com.example.data.provider.ChatMessagePayload(
                role = "assistant",
                content = "",
                toolCalls = listOf(toolCallReq)
            ),
            com.example.data.provider.ChatMessagePayload(
                role = "tool",
                content = """{"temp_c":22,"condition":"Sunny"}""",
                toolCallId = "call_weather_1",
                toolName = "get_weather"
            )
        )
        val chatReq = com.example.data.provider.ChatCompletionRequest(
            provider = dummyProv,
            apiKey = "sk-test",
            modelId = "gpt-4o",
            messages = messagesWithToolLoop,
            systemPrompt = injectedPrompt,
            temperature = 0.7f,
            maxTokens = 1024,
            topP = 1.0f,
            supportsTemperature = true,
            supportsTopP = true,
            supportsSystemPrompt = true,
            tools = listOf(mcpToolDef)
        )

        val oaiJson = openAiProvider.buildChatPayloadJson(chatReq)
        assertEquals(1, oaiJson.getJSONArray("tools").length())
        assertEquals("get_weather", oaiJson.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))

        val antJson = anthropicProvider.buildAnthropicPayloadJson(chatReq)
        assertEquals(1, antJson.getJSONArray("tools").length())
        assertTrue(antJson.getJSONArray("tools").getJSONObject(0).has("input_schema"))

        val gemJson = geminiProvider.buildGeminiPayloadJson(chatReq)
        assertTrue(gemJson.has("tools"))
        val gemFuncDecls = gemJson.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals("get_weather", gemFuncDecls.getJSONObject(0).getString("name"))

        // 4. Room Persistence & Settings Export (excluding MCP tokens by default)
        val db = Room.inMemoryDatabaseBuilder(context, OmniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.omniDao()
        val vault = TinkSecretVault(context)
        val repo = com.example.data.repository.OmniRepository(context, dao, vault)

        val server = com.example.data.local.McpServerEntity(
            id = "mcp_test_srv",
            name = "Test HTTP MCP",
            url = "https://mcp.example.com/mcp",
            transport = com.example.data.local.McpTransportType.STREAMABLE_HTTP.name
        )
        repo.saveMcpServerWithToken(server, "mcp_secret_token_9999")
        dao.upsertSkill(skillA)

        val exportedSafeJson = org.json.JSONObject(repo.exportSettingsJson(includeKeys = false))
        assertTrue(exportedSafeJson.has("skills"))
        assertTrue(exportedSafeJson.has("mcpServers"))
        val exportedMcpObj = exportedSafeJson.getJSONArray("mcpServers").getJSONObject(0)
        assertFalse(exportedMcpObj.has("authToken"))

        db.close()
    }

    @Test
    fun providersViewModel_managesKeys_encryptsWithTink_andUpdatesUiState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OmniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.omniDao()
        val vault = TinkSecretVault(context)
        val repo = com.example.data.repository.OmniRepository(context, dao, vault)

        val openAiProv = ProviderInstanceEntity(
            id = "provider_openai_test",
            presetType = ProviderPresetType.OPENAI.name,
            displayName = "OpenAI",
            baseUrl = ProviderPresetType.OPENAI.defaultBaseUrl,
            capabilitiesCsv = "CHAT,IMAGE",
            isEnabled = true
        )
        dao.upsertProvider(openAiProv)

        val vm = com.example.ui.viewmodel.ProvidersViewModel(repo, vault, dao)
        vm.loadInitialState()

        // 1. Initial State has default providers populated
        var state = vm.uiState.value
        assertTrue("Providers should be loaded", state.providers.isNotEmpty())

        // 2. Save API key via ViewModel -> Tink AES-256-GCM encryption in DataStore
        val testRawKey = "test-key-001"
        vm.saveApiKeyAwait(openAiProv.id, testRawKey)

        state = vm.uiState.value

        val maskedKey = state.maskedKeysByProvider[openAiProv.id]
        val cipherPreview = state.cipherPreviewsByProvider[openAiProv.id]

        assertTrue("Provider should now have API key in map", !maskedKey.isNullOrBlank())
        assertEquals("sk-p••••••••5678", maskedKey)
        assertTrue("Ciphertext preview should be populated and non-empty", !cipherPreview.isNullOrBlank())
        assertNotEquals("Ciphertext should NOT be plaintext", testRawKey, cipherPreview)

        // Direct vault check
        val decryptedDirect = vault.getApiKey(openAiProv.id)
        assertEquals("Decrypted key must match original plaintext", testRawKey, decryptedDirect)

        // 3. Clear key
        vm.clearApiKeyAwait(openAiProv.id)
        state = vm.uiState.value
        val clearedMaskedKey = state.maskedKeysByProvider[openAiProv.id]
        assertTrue("Key should be cleared", clearedMaskedKey.isNullOrBlank())

        // 4. Toggle enabled
        val initialEnabled = openAiProv.isEnabled
        vm.toggleProviderEnabledAwait(openAiProv)
        state = vm.uiState.value
        val toggledProv = state.providers.first { it.id == openAiProv.id }
        assertEquals(!initialEnabled, toggledProv.isEnabled)

        db.close()
    }

    @Test
    fun chatMediaAttachment_serializesMultiplePhotosAndVideos_andBuildsMultimodalPayloads() {
        val photo1 = com.example.data.local.ChatMediaAttachment(
            uri = "content://media/photo1.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            name = "beach.jpg",
            durationMs = 0L
        )
        val photo2 = com.example.data.local.ChatMediaAttachment(
            uri = "content://media/photo2.png",
            mimeType = "image/png",
            isVideo = false,
            name = "diagram.png",
            durationMs = 0L
        )
        val video = com.example.data.local.ChatMediaAttachment(
            uri = "content://media/sample_video.mp4",
            mimeType = "video/mp4",
            isVideo = true,
            name = "clip.mp4",
            durationMs = 4200L
        )

        val attachments = listOf(photo1, photo2, video)

        // 1. Serialize list and parse back
        val serialized = com.example.data.local.ChatMediaAttachment.serializeList(attachments)
        val parsed = com.example.data.local.ChatMediaAttachment.parseList(serialized)

        assertEquals(3, parsed.size)
        assertEquals("content://media/photo1.jpg", parsed[0].uri)
        assertEquals("image/jpeg", parsed[0].mimeType)
        assertFalse(parsed[0].isVideo)

        assertEquals("content://media/photo2.png", parsed[1].uri)
        assertFalse(parsed[1].isVideo)

        assertEquals("content://media/sample_video.mp4", parsed[2].uri)
        assertTrue(parsed[2].isVideo)
        assertEquals("clip.mp4", parsed[2].name)
        assertEquals(4200L, parsed[2].durationMs)

        // 2. Backward compatibility with legacy single URI
        val legacyUri = "content://media/external/images/media/42"
        val legacyParsed = com.example.data.local.ChatMediaAttachment.parseList(legacyUri)
        assertEquals(1, legacyParsed.size)
        assertEquals(legacyUri, legacyParsed.first().uri)
        assertFalse(legacyParsed.first().isVideo)

        // 3. Multimodal ChatMessagePayload verification
        val payloads = listOf(
            com.example.data.provider.MediaAttachmentPayload(
                dataUri = "data:image/jpeg;base64,/9j/4AAQSkZJRg==",
                mimeType = "image/jpeg",
                isVideo = false,
                fileName = "beach.jpg"
            ),
            com.example.data.provider.MediaAttachmentPayload(
                dataUri = "data:video/mp4;base64,AAAAHGZ0eXBtcDQyAAAAAG1wNDJpc29t",
                mimeType = "video/mp4",
                isVideo = true,
                fileName = "clip.mp4"
            )
        )

        val userMessage = com.example.data.provider.ChatMessagePayload(
            role = "user",
            content = "Analyze these uploaded media items",
            mediaAttachments = payloads
        )

        assertEquals(2, userMessage.mediaAttachments.size)
        assertTrue(userMessage.mediaAttachments.any { it.isVideo })
        assertTrue(userMessage.mediaAttachments.any { !it.isVideo })

        // 4. Verify Gemini multimodal serialization formats inline_data for photos and video
        val client = OkHttpClient()
        val gemini = GeminiProvider(client)
        val dummyProv = ProviderInstanceEntity(
            id = "gemini_multimodal",
            presetType = ProviderPresetType.GEMINI.name,
            displayName = "Gemini",
            baseUrl = ProviderPresetType.GEMINI.defaultBaseUrl,
            capabilitiesCsv = "CHAT,IMAGE,VIDEO"
        )
        val req = ChatCompletionRequest(
            provider = dummyProv,
            apiKey = "test-gemini-key",
            modelId = "gemini-2.5-flash",
            messages = listOf(userMessage),
            systemPrompt = "You are a vision-video assistant",
            temperature = 0.5f,
            maxTokens = 1024,
            topP = 0.9f,
            supportsTemperature = true,
            supportsTopP = true,
            supportsSystemPrompt = true
        )

        val geminiJson = gemini.buildGeminiPayloadJson(req)
        val contents = geminiJson.getJSONArray("contents")
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        // parts should include text + 1 image inline_data + 1 video inline_data = 3 parts
        assertEquals(3, parts.length())
        var inlineDataCount = 0
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("inline_data")) {
                inlineDataCount++
                val inlineObj = part.getJSONObject("inline_data")
                assertTrue(inlineObj.has("mime_type"))
                assertTrue(inlineObj.has("data"))
            }
        }
        assertEquals(2, inlineDataCount)
    }

    @Test
    fun omniAutopilotAgent_executesCompoundSingleCommandInRealTime_andSupportsUndo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OmniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.omniDao()
        val vault = TinkSecretVault(context)
        val repo = com.example.data.repository.OmniRepository(context, dao, vault)

        val openAiProv = ProviderInstanceEntity(
            id = "provider_openai_default",
            presetType = ProviderPresetType.OPENAI.name,
            displayName = "OpenAI",
            baseUrl = ProviderPresetType.OPENAI.defaultBaseUrl,
            capabilitiesCsv = "CHAT,IMAGE",
            isEnabled = true,
            isDefaultForChat = true,
            defaultChatModelId = "gpt-4o"
        )
        dao.upsertProvider(openAiProv)

        val studioVm = com.example.ui.viewmodel.OmniStudioViewModel(repo, dao, autoInitialize = false)
        val providersVm = com.example.ui.viewmodel.ProvidersViewModel(repo, vault, dao)
        providersVm.loadInitialState()

        val engine = studioVm.autopilotEngine

        // Single compound natural-language command controlling 6 distinct app subsystems at once
        val compoundCommand =
            "Switch to light mode, set currency to USD, set temperature to 0.25, set max tokens to 8192, " +
                "save key for openai sk-live-agent-99998888, " +
                "create skill named Rust Safety Guard with instructions Enforce zero unsafe blocks; " +
                "switch to usage tab"

        val parsedActions = engine.parseNaturalLanguageCommand(compoundCommand)
        assertTrue("Should decompose compound command into multiple atomic actions", parsedActions.size >= 5)

        val record = engine.executeCommandInRealTime(
            rawCommand = compoundCommand,
            studioViewModel = studioVm,
            providersViewModel = providersVm
        )

        assertTrue("Execution record should contain steps", record.steps.isNotEmpty())
        assertEquals("LIGHT", studioVm.uiState.value.themeMode)
        assertEquals("USD", studioVm.uiState.value.spendCurrency)
        assertEquals(com.example.ui.viewmodel.WorkspaceTab.USAGE, studioVm.uiState.value.currentTab)

        // Verify Tink AES256-GCM encrypted key was stored in DataStore and updated in ProvidersViewModel
        assertEquals("sk-live-agent-99998888", vault.getApiKey(openAiProv.id))
        assertEquals("sk-l••••••••8888", providersVm.uiState.value.maskedKeysByProvider[openAiProv.id])

        // Verify conversation parameters were updated in Room DB
        val convs = dao.getConversationsOnce()
        assertTrue("Conversation should exist", convs.isNotEmpty())
        val updatedConv = convs.first()
        assertEquals(0.25f, updatedConv.temperature, 0.01f)
        assertEquals(8192, updatedConv.maxTokens)

        // Verify skill was created in Room DB
        val skills = dao.getSkillsOnce()
        assertTrue("Skill 'Rust Safety Guard' should exist in DB", skills.any { it.name == "Rust Safety Guard" })

        // Verify real-time Undo restores previous state
        val undone = engine.undoLastExecution(studioVm)
        assertTrue("Undo should succeed", undone)
        assertEquals("DARK", studioVm.uiState.value.themeMode)
        assertEquals("INR", studioVm.uiState.value.spendCurrency)
        assertEquals(com.example.ui.viewmodel.WorkspaceTab.CHAT, studioVm.uiState.value.currentTab)

        db.close()
    }

    @Test
    fun omniRouteGateway_supportsVideoGenerationModelsAndCustomCombos() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OmniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.omniDao()
        val vault = TinkSecretVault(context)
        val repo = com.example.data.repository.OmniRepository(context, dao, vault)

        repo.ensureDefaultPresetsSeeded()
        val serverStatus = com.example.data.provider.EmbeddedOmniRouteServer.startServer(20128)
        assertTrue("Embedded OmniRoute daemon should listen on 20128", serverStatus.isListening)

        // 1. Verify custom video combo creation & persistence
        val customCombo = repo.upsertOmniRouteCustomCombo(
            rawId = "omniroute/my-scifi-director",
            displayName = "My Sci-Fi Director Combo",
            modality = "VIDEO",
            strategy = "FALLBACK_CHAIN",
            targetModels = listOf("omniroute/wan-2.1-t2v", "omniroute/kling-v1.6-pro", "omniroute/veo-2-cinema"),
            description = "Custom video combo for sci-fi scenes"
        )
        assertEquals("omniroute/my-scifi-director", customCombo.id)
        assertEquals("VIDEO", customCombo.modality)

        val modelsInDb = dao.getModelsForProviderOnce("provider_omniroute_default")
        val comboDescriptor = modelsInDb.firstOrNull { it.modelId == "omniroute/my-scifi-director" }
        assertTrue("Custom video combo should be persisted in Room models catalog", comboDescriptor != null)
        assertTrue("Custom video combo should be marked isCombo", comboDescriptor!!.isCombo)
        assertTrue(
            "Custom video combo should have VIDEO capability",
            com.example.data.local.CapabilityTag.VIDEO in comboDescriptor.capabilitySet()
        )

        // 2. Verify video generation through OmniRoute gateway using the custom combo
        val omniProvider = dao.getProviderById("provider_omniroute_default")!!
        val openAiCompat = OpenAICompatibleProvider(OkHttpClient())
        val submitResult = openAiCompat.submitVideoGeneration(
            com.example.data.provider.VideoGenerationRequest(
                provider = omniProvider,
                apiKey = "",
                modelId = "omniroute/my-scifi-director",
                prompt = "Cyberpunk neon city in rain with flying cars",
                aspectRatio = "16:9",
                resolution = "720p",
                durationSeconds = 5
            )
        )
        assertTrue("Video generation submission to OmniRoute should succeed: ${submitResult.exceptionOrNull()}", submitResult.isSuccess)
        val submission = submitResult.getOrThrow()
        assertEquals("COMPLETED", submission.status)
        assertTrue("Video URL should be returned", !submission.videoUrl.isNullOrBlank())
        assertTrue("Video URL should point to localhost:20128 MP4 endpoint", submission.videoUrl!!.contains("localhost:20128/v1/videos/media/"))

        // 3. Verify the MP4 endpoint serves valid MP4 bytes
        val mp4Req = okhttp3.Request.Builder().url(submission.videoUrl!!).get().build()
        OkHttpClient().newCall(mp4Req).execute().use { resp ->
            assertTrue("MP4 file download should return 200 OK", resp.isSuccessful)
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            assertTrue("MP4 file should be non-empty", bytes.size >= 32)
            // Bytes 4..7 of an ISO Base Media File (MP4) are 'ftyp'
            val boxType = String(bytes.sliceArray(4..7), Charsets.US_ASCII)
            assertEquals("ftyp", boxType)
        }

        db.close()
    }
}



