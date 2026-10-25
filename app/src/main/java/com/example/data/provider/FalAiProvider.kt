package com.example.data.provider

import com.example.data.local.ModelDescriptorEntity
import com.example.data.local.ProviderInstanceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Dedicated adapter for fal.ai (https://fal.run/{model_id}) using Authorization: Key <API_KEY>.
 * Supports Flux Dev, Flux Schnell, Flux Pro 1.1, Fast SDXL, Img2Img, and AuraSR Upscaling.
 */
class FalAiProvider(
    private val okHttpClient: OkHttpClient
) : AiProvider {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun testConnection(
        provider: ProviderInstanceEntity,
        apiKey: String
    ): Result<List<ModelDescriptorEntity>> = listModels(provider, apiKey)

    override suspend fun listModels(
        provider: ProviderInstanceEntity,
        apiKey: String
    ): Result<List<ModelDescriptorEntity>> = withContext(Dispatchers.IO) {
        val curated = listOf(
            ModelCapabilityRegistry.buildDescriptor(
                providerInstanceId = provider.id,
                rawModelId = "fal-ai/flux/dev",
                displayNameOverride = "Flux.1 Dev (fal.ai)",
                preserveFavorite = true
            ),
            ModelCapabilityRegistry.buildDescriptor(
                providerInstanceId = provider.id,
                rawModelId = "fal-ai/flux/schnell",
                displayNameOverride = "Flux.1 Schnell (Ultra Fast)"
            ),
            ModelCapabilityRegistry.buildDescriptor(
                providerInstanceId = provider.id,
                rawModelId = "fal-ai/flux-pro/v1.1",
                displayNameOverride = "Flux Pro 1.1 (fal.ai)"
            ),
            ModelCapabilityRegistry.buildDescriptor(
                providerInstanceId = provider.id,
                rawModelId = "fal-ai/fast-sdxl",
                displayNameOverride = "Fast SDXL (fal.ai)"
            )
        )
        Result.success(curated)
    }

    override fun streamChat(request: ChatCompletionRequest): Flow<ChatStreamEvent> = flow {
        emit(
            ChatStreamEvent.Error(
                NormalizedAiError(
                    category = ErrorCategory.SERVER_ERROR,
                    title = "Image/Video Provider",
                    message = "fal.ai is a generative media endpoint for Image and Video synthesis.",
                    suggestedFix = "Open the Images tab to generate with fal.ai Flux or Fast SDXL."
                )
            )
        )
    }

    override suspend fun generateImage(
        request: ImageGenerationRequest
    ): Result<ImageGenerationOutput> = withContext(Dispatchers.IO) {
        try {
            val base = request.provider.baseUrl.trim().trimEnd('/')
            val modelPath = when {
                request.isUpscaleRequest && !request.sourceImageDataUri.isNullOrBlank() -> "fal-ai/aura-sr"
                !request.sourceImageDataUri.isNullOrBlank() && request.modelId.contains("flux") -> "fal-ai/flux/dev/image-to-image"
                else -> request.modelId.ifBlank { "fal-ai/flux/dev" }.trimStart('/')
            }
            val endpoint = "$base/$modelPath"

            val payload = buildFalPayloadJson(request)
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")

            if (request.apiKey.isNotBlank()) {
                val cleanKey = request.apiKey.trim().removePrefix("Key ").removePrefix("Bearer ").trim()
                reqBuilder.header("Authorization", "Key $cleanKey")
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val normError = ErrorNormalizer.normalizeHttpError(
                        statusCode = response.code,
                        responseBody = rawBody,
                        providerName = request.provider.displayName
                    )
                    return@withContext Result.failure(ProviderNormalizedException(normError))
                }

                val parsed = parseFalResponseJson(rawBody, request.seed)
                if (parsed.imagePayloads.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("fal.ai response did not include any image URLs.")
                    )
                }
                Result.success(parsed)
            }
        } catch (e: Exception) {
            val normError = ErrorNormalizer.normalizeException(
                throwable = e,
                providerName = request.provider.displayName,
                baseUrl = request.provider.baseUrl
            )
            Result.failure(ProviderNormalizedException(normError))
        }
    }

    internal fun buildFalPayloadJson(request: ImageGenerationRequest): JSONObject {
        val root = JSONObject()
        if (request.isUpscaleRequest && !request.sourceImageDataUri.isNullOrBlank()) {
            root.put("image_url", request.sourceImageDataUri)
            return root
        }

        val enrichedPrompt = ModelCapabilityRegistry.enrichPromptWithStyle(request.prompt, request.stylePreset)
        root.put("prompt", enrichedPrompt)
        root.put("num_images", request.count.coerceIn(1, 4))

        val falImageSize = when (request.aspectRatio) {
            "16:9" -> "landscape_16_9"
            "9:16" -> "portrait_16_9"
            "4:3" -> "landscape_4_3"
            "3:4" -> "portrait_4_3"
            else -> "square_hd"
        }
        root.put("image_size", falImageSize)

        if (request.negativePrompt.isNotBlank()) {
            root.put("negative_prompt", request.negativePrompt.trim())
        }
        if (request.seed != null) {
            root.put("seed", request.seed)
        }
        root.put("guidance_scale", request.guidanceScale.toDouble())
        root.put("num_inference_steps", request.steps.coerceIn(1, 50))

        if (!request.sourceImageDataUri.isNullOrBlank()) {
            root.put("image_url", request.sourceImageDataUri)
            root.put("strength", request.imageToImageStrength.toDouble())
        }
        return root
    }

    internal fun parseFalResponseJson(rawJson: String, fallbackSeed: Long? = null): ImageGenerationOutput {
        val root = JSONObject(rawJson)
        val urls = mutableListOf<String>()
        val imagesArr = root.optJSONArray("images")
        if (imagesArr != null) {
            for (i in 0 until imagesArr.length()) {
                val u = imagesArr.optJSONObject(i)?.optString("url", "").orEmpty()
                if (u.isNotBlank()) urls.add(u)
            }
        }
        val singleImg = root.optJSONObject("image")?.optString("url", "").orEmpty()
        if (singleImg.isNotBlank() && singleImg !in urls) {
            urls.add(singleImg)
        }
        val seedUsed = if (root.has("seed")) root.optLong("seed") else fallbackSeed
        return ImageGenerationOutput(
            imagePayloads = urls,
            seedUsed = seedUsed
        )
    }

    override suspend fun submitVideoGeneration(
        request: VideoGenerationRequest
    ): Result<VideoSubmissionResult> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = request.provider.baseUrl.trim().trimEnd('/')
            val targetModel = request.modelId.trim().trimStart('/').ifBlank {
                "fal-ai/kling-video/v1.6/standard/text-to-video"
            }
            val endpoint = "$cleanBase/$targetModel"
            val enrichedPrompt = ModelCapabilityRegistry.enrichVideoPromptWithMotion(
                prompt = request.prompt,
                cameraMotion = request.cameraMotion
            )
            val payload = JSONObject().apply {
                put("prompt", enrichedPrompt)
                put("aspect_ratio", request.aspectRatio)
                put("duration", request.durationSeconds.toString())
                if (request.negativePrompt.isNotBlank()) {
                    put("negative_prompt", request.negativePrompt.trim())
                }
                if (!request.sourceImageDataUri.isNullOrBlank()) {
                    put("image_url", request.sourceImageDataUri)
                }
            }

            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
            if (request.apiKey.isNotBlank()) {
                val cleanKey = request.apiKey.trim().removePrefix("Key ").removePrefix("Bearer ").trim()
                reqBuilder.header("Authorization", "Key $cleanKey")
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val normError = ErrorNormalizer.normalizeHttpError(
                        statusCode = response.code,
                        responseBody = rawBody,
                        providerName = request.provider.displayName
                    )
                    return@withContext Result.failure(ProviderNormalizedException(normError))
                }
                val root = JSONObject(rawBody)
                val videoUrl = root.optJSONObject("video")?.optString("url", "")
                    ?.ifBlank { root.optString("video_url", "") }
                    ?.takeIf { it.isNotBlank() }
                val statusUrl = root.optString("status_url", root.optString("request_id", ""))
                    .ifBlank { "fal_${System.currentTimeMillis()}" }

                Result.success(
                    VideoSubmissionResult(
                        remoteJobId = statusUrl,
                        status = if (videoUrl != null) "COMPLETED" else "QUEUED",
                        videoUrl = videoUrl
                    )
                )
            }
        } catch (e: Exception) {
            val normError = ErrorNormalizer.normalizeException(
                throwable = e,
                providerName = request.provider.displayName,
                baseUrl = request.provider.baseUrl
            )
            Result.failure(ProviderNormalizedException(normError))
        }
    }

    override suspend fun pollVideoGeneration(
        provider: ProviderInstanceEntity,
        apiKey: String,
        modelId: String,
        remoteJobId: String
    ): Result<VideoPollResult> = withContext(Dispatchers.IO) {
        try {
            val pollUrl = if (remoteJobId.startsWith("http")) {
                remoteJobId
            } else {
                val cleanBase = provider.baseUrl.trim().trimEnd('/')
                "$cleanBase/$modelId/requests/$remoteJobId/status"
            }

            val reqBuilder = Request.Builder()
                .url(pollUrl)
                .get()
                .header("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                val cleanKey = apiKey.trim().removePrefix("Key ").removePrefix("Bearer ").trim()
                reqBuilder.header("Authorization", "Key $cleanKey")
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val normError = ErrorNormalizer.normalizeHttpError(
                        statusCode = response.code,
                        responseBody = rawBody,
                        providerName = provider.displayName
                    )
                    return@withContext Result.failure(ProviderNormalizedException(normError))
                }
                val root = JSONObject(rawBody)
                val status = root.optString("status", "IN_PROGRESS").uppercase()
                val videoUrl = root.optJSONObject("video")?.optString("url", "")
                    ?.ifBlank { root.optString("video_url", "") }
                    ?.takeIf { it.isNotBlank() }

                when {
                    status == "COMPLETED" || videoUrl != null -> Result.success(
                        VideoPollResult(
                            status = "COMPLETED",
                            progressPercent = 100,
                            videoUrl = videoUrl
                        )
                    )
                    status == "FAILED" -> Result.success(
                        VideoPollResult(
                            status = "FAILED",
                            progressPercent = 100,
                            errorMessage = root.optString("error", "fal.ai video generation failed")
                        )
                    )
                    else -> Result.success(
                        VideoPollResult(
                            status = "PROCESSING",
                            progressPercent = 60
                        )
                    )
                }
            }
        } catch (e: Exception) {
            val normError = ErrorNormalizer.normalizeException(
                throwable = e,
                providerName = provider.displayName,
                baseUrl = provider.baseUrl
            )
            Result.failure(ProviderNormalizedException(normError))
        }
    }
}
