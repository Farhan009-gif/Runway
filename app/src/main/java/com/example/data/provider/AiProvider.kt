package com.example.data.provider

import com.example.data.local.CapabilityTag
import com.example.data.local.ModelDescriptorEntity
import com.example.data.local.ProviderInstanceEntity
import com.example.data.security.CleartextHostNotApprovedException
import kotlinx.coroutines.flow.Flow
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class ErrorCategory(val badgeLabel: String) {
    INVALID_KEY("Invalid API Key"),
    RATE_LIMIT("Rate Limit"),
    QUOTA_EXCEEDED("Quota / Billing"),
    CONTENT_POLICY("Content Policy"),
    CLEARTEXT_UNAPPROVED("Unapproved HTTP Host"),
    NETWORK_UNREACHABLE("Network / Gateway Unreachable"),
    SERVER_ERROR("Provider Error")
}

data class NormalizedAiError(
    val category: ErrorCategory,
    val title: String,
    val message: String,
    val suggestedFix: String,
    val unapprovedHost: String? = null
)

data class McpToolDefinition(
    val serverId: String,
    val serverName: String,
    val toolName: String,
    val description: String,
    val inputSchemaJson: String = """{"type":"object","properties":{}}""",
    val isReadOnlyHint: Boolean = true
)

data class ModelToolCallRequest(
    val id: String,
    val toolName: String,
    val argumentsJson: String
)

data class MediaAttachmentPayload(
    val dataUri: String,
    val mimeType: String = "image/jpeg",
    val isVideo: Boolean = false,
    val fileName: String? = null
)

data class ChatMessagePayload(
    val role: String, // "system", "user", "assistant", "tool"
    val content: String,
    val imageDataUri: String? = null,
    val mediaAttachments: List<MediaAttachmentPayload> = emptyList(),
    val toolCalls: List<ModelToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
)

data class ChatCompletionRequest(
    val provider: ProviderInstanceEntity,
    val apiKey: String,
    val modelId: String,
    val messages: List<ChatMessagePayload>,
    val systemPrompt: String,
    val temperature: Float,
    val maxTokens: Int,
    val topP: Float,
    val supportsTemperature: Boolean = true,
    val supportsTopP: Boolean = true,
    val supportsSystemPrompt: Boolean = true,
    val tools: List<McpToolDefinition> = emptyList()
)

sealed class ChatStreamEvent {
    data class Delta(val textChunk: String) : ChatStreamEvent()
    data class ToolCall(val call: ModelToolCallRequest) : ChatStreamEvent()
    data class Usage(val promptTokens: Int, val completionTokens: Int) : ChatStreamEvent()
    data class Error(val error: NormalizedAiError) : ChatStreamEvent()
    data object Done : ChatStreamEvent()
}

data class ImageGenerationRequest(
    val provider: ProviderInstanceEntity,
    val apiKey: String,
    val modelId: String,
    val prompt: String,
    val negativePrompt: String = "",
    val aspectRatio: String = "1:1",
    val size: String = "1024x1024",
    val count: Int = 1,
    val seed: Long? = null,
    val stylePreset: String = "None",
    val guidanceScale: Float = 7.5f,
    val steps: Int = 28,
    val quality: String = "standard",
    val sourceImageDataUri: String? = null,
    val imageToImageStrength: Float = 0.75f,
    val isUpscaleRequest: Boolean = false
)

data class ImageGenerationOutput(
    val imagePayloads: List<String>, // URLs or data:image/...;base64,...
    val revisedPrompt: String? = null,
    val seedUsed: Long? = null
)

data class ImageModelCapabilities(
    val supportsNegativePrompt: Boolean,
    val supportsSeed: Boolean,
    val supportsStylePresets: Boolean,
    val supportsGuidanceAndSteps: Boolean,
    val supportsQualityToggle: Boolean,
    val supportsImageToImage: Boolean,
    val supportsUpscale: Boolean,
    val maxCount: Int,
    val costPerImageUsd: Double
) {
    val supportsQuality: Boolean get() = supportsQualityToggle
    val supportsGuidanceScale: Boolean get() = supportsGuidanceAndSteps
    val supportsSteps: Boolean get() = supportsGuidanceAndSteps
}

data class VideoGenerationRequest(
    val provider: ProviderInstanceEntity,
    val apiKey: String,
    val modelId: String,
    val prompt: String,
    val negativePrompt: String = "",
    val aspectRatio: String = "16:9",
    val resolution: String = "720p",
    val durationSeconds: Int = 5,
    val cameraMotion: String = "None",
    val sourceImageDataUri: String? = null
)

data class VideoSubmissionResult(
    val remoteJobId: String,
    val status: String = "QUEUED",
    val videoUrl: String? = null
)

data class VideoPollResult(
    val status: String, // "QUEUED", "PROCESSING", "COMPLETED", "FAILED"
    val progressPercent: Int = 50,
    val videoUrl: String? = null,
    val errorMessage: String? = null
)

data class VideoModelCapabilities(
    val supportsNegativePrompt: Boolean,
    val supportsImageToVideo: Boolean,
    val supportsCameraMotion: Boolean,
    val supportedDurations: List<Int>,
    val supportedAspectRatios: List<String>,
    val supportedResolutions: List<String>,
    val costPerVideoUsd: Double
)

interface AiProvider {
    suspend fun testConnection(
        provider: ProviderInstanceEntity,
        apiKey: String
    ): Result<List<ModelDescriptorEntity>>

    suspend fun listModels(
        provider: ProviderInstanceEntity,
        apiKey: String
    ): Result<List<ModelDescriptorEntity>>

    fun streamChat(request: ChatCompletionRequest): Flow<ChatStreamEvent>

    suspend fun generateImage(
        request: ImageGenerationRequest
    ): Result<ImageGenerationOutput>

    suspend fun submitVideoJob(
        provider: ProviderInstanceEntity,
        apiKey: String,
        modelId: String,
        prompt: String
    ): Result<String> = submitVideoGeneration(
        VideoGenerationRequest(
            provider = provider,
            apiKey = apiKey,
            modelId = modelId,
            prompt = prompt
        )
    ).map { it.remoteJobId }

    suspend fun submitVideoGeneration(
        request: VideoGenerationRequest
    ): Result<VideoSubmissionResult> = Result.failure(
        UnsupportedOperationException("${request.provider.displayName} does not support video generation.")
    )

    suspend fun pollVideoGeneration(
        provider: ProviderInstanceEntity,
        apiKey: String,
        modelId: String,
        remoteJobId: String
    ): Result<VideoPollResult> = Result.failure(
        UnsupportedOperationException("${provider.displayName} does not support video polling.")
    )
}

object ModelCapabilityRegistry {

    val STYLE_PRESETS = listOf(
        "None",
        "Photorealistic",
        "Cinematic",
        "Anime",
        "Digital Art",
        "3D Render",
        "Pixel Art",
        "Isometric"
    )

    val ASPECT_RATIOS = listOf(
        "1:1" to "1024x1024",
        "16:9" to "1344x768",
        "9:16" to "768x1344",
        "4:3" to "1152x896",
        "3:4" to "896x1152"
    )

    val VIDEO_ASPECT_RATIOS = listOf("16:9", "9:16", "1:1")
    val VIDEO_RESOLUTIONS = listOf("720p", "1080p")
    val VIDEO_DURATIONS = listOf(5, 8, 10)
    val CAMERA_MOTIONS = listOf(
        "None",
        "Pan Left",
        "Pan Right",
        "Zoom In",
        "Zoom Out",
        "Orbit",
        "Drone Flyover"
    )

    fun getVideoCapabilities(modelId: String, presetType: String = ""): VideoModelCapabilities {
        val lower = modelId.lowercase()
        val preset = presetType.uppercase()
        val isRunway = preset == "RUNWAY" || lower.contains("runway") || lower.contains("gen3") || lower.contains("gen4")
        val isLuma = preset == "LUMA" || lower.contains("luma") || lower.contains("ray")
        val isVeo = preset == "GEMINI" || lower.contains("veo")
        val isWanOrKling = lower.contains("wan") || lower.contains("kling") || preset == "REPLICATE" || preset == "FAL_AI"

        return VideoModelCapabilities(
            supportsNegativePrompt = isWanOrKling || isVeo || preset == "OMNIROUTE",
            supportsImageToVideo = true,
            supportsCameraMotion = true,
            supportedDurations = when {
                isRunway -> listOf(5, 10)
                isLuma -> listOf(5, 9)
                isVeo -> listOf(5, 8)
                else -> listOf(5, 8, 10)
            },
            supportedAspectRatios = if (isVeo) listOf("16:9", "9:16") else listOf("16:9", "9:16", "1:1"),
            supportedResolutions = listOf("720p", "1080p"),
            costPerVideoUsd = when {
                isRunway || isVeo -> 0.45
                isLuma -> 0.35
                else -> 0.25
            }
        )
    }

    fun enrichVideoPromptWithMotion(prompt: String, cameraMotion: String): String {
        val clean = prompt.trim()
        if (cameraMotion.isBlank() || cameraMotion.equals("None", ignoreCase = true)) {
            return clean
        }
        return "$clean. Camera motion: smooth $cameraMotion, cinematic stabilization"
    }

    fun getImageCapabilities(modelId: String, presetType: String = ""): ImageModelCapabilities {
        val lower = modelId.lowercase()
        val preset = presetType.uppercase()
        val isDalle3 = lower.contains("dall-e-3")
        val isGptImage = lower.contains("gpt-image") || lower.contains("dall-e")
        val isImagen = lower.contains("imagen") || preset == "GEMINI"
        val isStability = preset == "STABILITY" || lower.contains("sd3") || lower.contains("stable-") || lower.contains("sdxl")
        val isFlux = lower.contains("flux")
        val isReplicateOrFal = preset == "REPLICATE" || preset == "FAL_AI" || preset == "OMNIROUTE"

        return ImageModelCapabilities(
            supportsNegativePrompt = !isDalle3 && !isFlux,
            supportsSeed = !isDalle3,
            supportsStylePresets = true,
            supportsGuidanceAndSteps = isStability || isFlux || isReplicateOrFal,
            supportsQualityToggle = isGptImage || preset == "OMNIROUTE",
            supportsImageToImage = isStability || isFlux || isReplicateOrFal || lower.contains("gpt-image"),
            supportsUpscale = isStability || isReplicateOrFal || isFlux,
            maxCount = if (isDalle3) 1 else 4,
            costPerImageUsd = when {
                lower.contains("schnell") || lower.contains("turbo") || lower.contains("core") -> 0.003
                isDalle3 || lower.contains("ultra") || lower.contains("pro") -> 0.040
                else -> 0.020
            }
        )
    }

    fun enrichPromptWithStyle(prompt: String, stylePreset: String): String {
        val clean = prompt.trim()
        if (stylePreset.isBlank() || stylePreset.equals("None", ignoreCase = true)) {
            return clean
        }
        return "$clean, $stylePreset style, high detail, crisp lighting"
    }

    fun buildDescriptor(
        providerInstanceId: String,
        rawModelId: String,
        displayNameOverride: String? = null,
        explicitCombo: Boolean = false,
        preserveFavorite: Boolean = false,
        explicitCapabilities: Set<CapabilityTag>? = null
    ): ModelDescriptorEntity {
        val lower = rawModelId.lowercase()
        val registeredCombo = EmbeddedOmniRouteServer.findCombo(rawModelId)
        val isCombo = explicitCombo ||
            registeredCombo != null ||
            lower.contains("combo") ||
            lower.contains("fallback") ||
            lower.contains("router") ||
            lower == "omniroute/auto-combo" ||
            lower == "omniroute/coding-fallback" ||
            lower == "omniroute/vision-pro" ||
            lower == "omniroute/video-combo" ||
            lower == "omniroute/cinema-director" ||
            lower == "omniroute/flux-combo" ||
            lower.startsWith("auto-")

        val caps = mutableSetOf<CapabilityTag>()
        if (!explicitCapabilities.isNullOrEmpty()) {
            caps.addAll(explicitCapabilities)
        } else if (registeredCombo != null) {
            when (registeredCombo.modality.uppercase()) {
                "VIDEO" -> caps.add(CapabilityTag.VIDEO)
                "IMAGE" -> caps.add(CapabilityTag.IMAGE)
                "ALL", "MULTIMODAL" -> {
                    caps.add(CapabilityTag.CHAT)
                    caps.add(CapabilityTag.IMAGE)
                    caps.add(CapabilityTag.VIDEO)
                }
                else -> caps.add(CapabilityTag.CHAT)
            }
        } else {
            when {
                lower.contains("sora") || lower.contains("veo") || lower.contains("runway") ||
                    lower.contains("luma") || lower.contains("kling") || lower.contains("wan") ||
                    lower.contains("video") || lower.contains("ray") || lower.contains("hunyuan") ||
                    lower.contains("seedance") || lower.contains("cinema") || lower.contains("t2v") ||
                    lower.contains("i2v") || lower.contains("gen3") || lower.contains("gen4") ||
                    lower.contains("pika") || lower.contains("hailuo") || lower.contains("mochi") ||
                    lower.contains("cogvideo") -> {
                    caps.add(CapabilityTag.VIDEO)
                }
                lower.startsWith("aihorde/") || lower.contains("dall-e") || lower.contains("flux") ||
                    lower.contains("sdxl") || lower.contains("stable-") || lower.contains("sd3") ||
                    lower.contains("imagen") || lower.contains("recraft") || lower.contains("image") ||
                    lower.contains("esrgan") || lower.contains("aura-sr") || lower.contains("upscale") ||
                    lower.contains("midjourney") || lower.contains("ideogram") || lower.contains("playground-v2") ||
                    lower.contains("kandinsky") || lower.contains("kolors") -> {
                    caps.add(CapabilityTag.IMAGE)
                }
                else -> {
                    caps.add(CapabilityTag.CHAT)
                }
            }
        }
        if (isCombo && caps.isEmpty()) {
            caps.add(CapabilityTag.CHAT)
        }

        val supportsVision = (isCombo && caps.contains(CapabilityTag.CHAT)) ||
            lower.contains("gpt-4o") ||
            lower.contains("gpt-4.1") ||
            lower.contains("claude-3") ||
            lower.contains("gemini") ||
            lower.contains("vision") ||
            lower.contains("vl") ||
            lower.contains("pixtral") ||
            lower.contains("llava") ||
            lower.contains("llama-3.2")

        // Reasoning models like o1 / o3-mini often restrict temperature/top_p
        val isReasoningRestricted = lower.startsWith("o1-") || lower == "o1" || lower.startsWith("o3-")

        val (inCost, outCost) = estimatePricingPer1k(lower, isCombo)
        val resolvedDisplayName = displayNameOverride?.takeIf { it.isNotBlank() }
            ?: registeredCombo?.displayName?.takeIf { it.isNotBlank() }
            ?: rawModelId

        return ModelDescriptorEntity(
            id = "$providerInstanceId:$rawModelId",
            providerInstanceId = providerInstanceId,
            modelId = rawModelId,
            displayName = resolvedDisplayName,
            capabilitiesCsv = caps.joinToString(",") { it.name },
            supportsVision = supportsVision,
            supportsStreaming = true,
            supportsSystemPrompt = !lower.startsWith("o1-mini"),
            supportsTemperature = !isReasoningRestricted,
            supportsTopP = !isReasoningRestricted,
            isCombo = isCombo,
            isFavorite = preserveFavorite,
            inputCostPer1k = inCost,
            outputCostPer1k = outCost
        )
    }

    private fun estimatePricingPer1k(lowerModelId: String, isCombo: Boolean): Pair<Double, Double> {
        return ModelPriceTable.lookupPricingPer1K(lowerModelId, isCombo)
    }

    fun calculateCostUsd(
        promptTokens: Int,
        completionTokens: Int,
        model: ModelDescriptorEntity?
    ): Double {
        if (model != null) {
            return ModelPriceTable.calculateCostUsd(
                modelId = model.modelId,
                inputTokens = promptTokens,
                outputTokens = completionTokens
            )
        }
        return ModelPriceTable.calculateCostUsd(
            modelId = "default",
            inputTokens = promptTokens,
            outputTokens = completionTokens
        )
    }

    fun estimateTokens(text: String): Int {
        return ModelPriceTable.estimateTokensFromText(text)
    }
}

object ErrorNormalizer {

    fun normalizeHttpError(
        statusCode: Int,
        responseBody: String,
        providerName: String,
        attemptedMethod: String = "POST",
        attemptedUrl: String = ""
    ): NormalizedAiError {
        val redactedBody = SecretRedactor.redactText(responseBody)
        val lowerBody = redactedBody.lowercase()
        return when {
            statusCode == 401 || statusCode == 403 || lowerBody.contains("invalid_api_key") || lowerBody.contains("incorrect api key") -> {
                NormalizedAiError(
                    category = ErrorCategory.INVALID_KEY,
                    title = "Authentication Failed ($statusCode)",
                    message = "$providerName rejected the API key or authorization header.",
                    suggestedFix = "Open Settings -> Providers, verify your API key for $providerName, and tap Test Connection."
                )
            }
            statusCode == 405 || statusCode == 404 -> {
                val routeErr = ProviderErrorMapper.fromHttpResponse(
                    statusCode = statusCode,
                    responseBody = redactedBody,
                    providerName = providerName,
                    attemptedMethod = attemptedMethod,
                    attemptedUrl = attemptedUrl
                )
                ProviderErrorMapper.toNormalizedAiError(routeErr, providerName, attemptedUrl)
            }
            statusCode == 402 || lowerBody.contains("insufficient_quota") || lowerBody.contains("credit balance") || lowerBody.contains("quota") -> {
                NormalizedAiError(
                    category = ErrorCategory.QUOTA_EXCEEDED,
                    title = "Quota or Billing Limit Reached",
                    message = "$providerName reported insufficient quota or credits.",
                    suggestedFix = "Switch to another provider or top up your $providerName account."
                )
            }
            statusCode == 429 || lowerBody.contains("rate_limit") -> {
                NormalizedAiError(
                    category = ErrorCategory.RATE_LIMIT,
                    title = "Rate Limit Reached (429)",
                    message = "Too many requests sent to $providerName in a short window.",
                    suggestedFix = "Wait a few seconds and tap Regenerate, or switch to another provider."
                )
            }
            lowerBody.contains("content_policy") || lowerBody.contains("safety") || lowerBody.contains("moderation") || lowerBody.contains("content_filter") -> {
                NormalizedAiError(
                    category = ErrorCategory.CONTENT_POLICY,
                    title = "Blocked by Safety / Content Policy",
                    message = "$providerName declined to fulfill this prompt due to content moderation filters.",
                    suggestedFix = "Rephrase your prompt or switch models."
                )
            }
            else -> {
                val snippet = redactedBody.take(180).ifBlank { "HTTP $statusCode" }
                NormalizedAiError(
                    category = ErrorCategory.SERVER_ERROR,
                    title = "$providerName Error (HTTP $statusCode)",
                    message = snippet,
                    suggestedFix = "Check that the selected model is supported by $providerName's endpoint."
                )
            }
        }
    }

    fun normalizeException(
        throwable: Throwable,
        providerName: String,
        baseUrl: String
    ): NormalizedAiError {
        if (throwable is ProviderErrorException) return throwable.normalizedError
        if (throwable is ProviderNormalizedException) return throwable.normalizedError
        return when (throwable) {
            is CleartextHostNotApprovedException -> {
                NormalizedAiError(
                    category = ErrorCategory.CLEARTEXT_UNAPPROVED,
                    title = "Unapproved HTTP Host (${throwable.host})",
                    message = "Runway blocks unencrypted HTTP requests by default until you whitelist the host.",
                    suggestedFix = "Tap 'Approve Host' to trust '${throwable.host}' for local LAN / Tailscale traffic, or switch the URL to HTTPS.",
                    unapprovedHost = throwable.host
                )
            }
            is ConnectException, is SocketTimeoutException, is UnknownHostException -> {
                val isLocalhost = baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
                val hint = if (isLocalhost) {
                    "On a physical phone, 'localhost' points to the phone itself. Use your computer's LAN IP (e.g. http://192.168.1.x:20128/v1) or Tailscale IP, or http://10.0.2.2:20128/v1 on the Android Emulator."
                } else {
                    "Verify that $providerName (${SecretRedactor.redactUrl(baseUrl)}) is running, reachable on your Wi-Fi LAN / Tailscale VPN, and listening on the configured port."
                }
                NormalizedAiError(
                    category = ErrorCategory.NETWORK_UNREACHABLE,
                    title = "Cannot Reach $providerName",
                    message = SecretRedactor.redactText(throwable.message ?: "Connection timed out or host unreachable."),
                    suggestedFix = hint
                )
            }
            is SSLException -> {
                NormalizedAiError(
                    category = ErrorCategory.NETWORK_UNREACHABLE,
                    title = "TLS / Certificate Error",
                    message = "Could not establish a secure HTTPS connection to $providerName.",
                    suggestedFix = "If connecting to a local OmniRoute server without TLS, use http:// and approve the LAN/Tailscale host."
                )
            }
            else -> {
                NormalizedAiError(
                    category = ErrorCategory.SERVER_ERROR,
                    title = "Request Failed",
                    message = SecretRedactor.redactText(throwable.message ?: "Unexpected error communicating with $providerName."),
                    suggestedFix = "Check your network connection and provider base URL in Settings."
                )
            }
        }
    }
}
