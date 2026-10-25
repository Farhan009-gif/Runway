package com.example.data.provider

import android.util.Base64
import com.example.data.local.ModelDescriptorEntity
import com.example.data.local.ProviderInstanceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Dedicated adapter for Stability AI (/v2beta/stable-image/generate/sd3, /core, /ultra, and /upscale/fast).
 * Returns JSON containing base64-encoded image bytes when Accept: application/json is passed.
 */
class StabilityProvider(
    private val okHttpClient: OkHttpClient
) : AiProvider {

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
                rawModelId = "sd3.5-large",
                displayNameOverride = "Stable Diffusion 3.5 Large",
                preserveFavorite = true
            ),
            ModelCapabilityRegistry.buildDescriptor(
                providerInstanceId = provider.id,
                rawModelId = "sd3.5-large-turbo",
                displayNameOverride = "Stable Diffusion 3.5 Large Turbo"
            ),
            ModelCapabilityRegistry.buildDescriptor(
                providerInstanceId = provider.id,
                rawModelId = "sd3.5-medium",
                displayNameOverride = "Stable Diffusion 3.5 Medium"
            ),
            ModelCapabilityRegistry.buildDescriptor(
                providerInstanceId = provider.id,
                rawModelId = "stable-image-core",
                displayNameOverride = "Stable Image Core"
            ),
            ModelCapabilityRegistry.buildDescriptor(
                providerInstanceId = provider.id,
                rawModelId = "stable-image-ultra",
                displayNameOverride = "Stable Image Ultra"
            )
        )
        Result.success(curated)
    }

    override fun streamChat(request: ChatCompletionRequest): Flow<ChatStreamEvent> = flow {
        emit(
            ChatStreamEvent.Error(
                NormalizedAiError(
                    category = ErrorCategory.SERVER_ERROR,
                    title = "Image/Video Only Provider",
                    message = "Stability AI does not host conversational chat endpoints.",
                    suggestedFix = "Use the Images tab to generate artwork with Stability AI, or switch to OmniRoute/OpenAI/Anthropic/Gemini for chat."
                )
            )
        )
    }

    override suspend fun generateImage(
        request: ImageGenerationRequest
    ): Result<ImageGenerationOutput> = withContext(Dispatchers.IO) {
        try {
            val base = request.provider.baseUrl.trim().trimEnd('/')
            val lowerModel = request.modelId.lowercase()

            val endpoint = when {
                request.isUpscaleRequest && !request.sourceImageDataUri.isNullOrBlank() ->
                    "$base/stable-image/upscale/fast"
                lowerModel.contains("ultra") ->
                    "$base/stable-image/generate/ultra"
                lowerModel.contains("core") ->
                    "$base/stable-image/generate/core"
                else ->
                    "$base/stable-image/generate/sd3"
            }

            val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            val enrichedPrompt = ModelCapabilityRegistry.enrichPromptWithStyle(request.prompt, request.stylePreset)

            if (request.isUpscaleRequest && !request.sourceImageDataUri.isNullOrBlank()) {
                val rawB64 = request.sourceImageDataUri.substringAfter("base64,", "")
                val imgBytes = Base64.decode(rawB64, Base64.DEFAULT)
                multipartBuilder.addFormDataPart(
                    "image",
                    "source.jpg",
                    imgBytes.toRequestBody("image/jpeg".toMediaType())
                )
                multipartBuilder.addFormDataPart("output_format", "jpeg")
            } else {
                multipartBuilder.addFormDataPart("prompt", enrichedPrompt)
                multipartBuilder.addFormDataPart("output_format", "jpeg")

                if (lowerModel.contains("sd3")) {
                    multipartBuilder.addFormDataPart("model", request.modelId)
                }
                if (request.negativePrompt.isNotBlank()) {
                    multipartBuilder.addFormDataPart("negative_prompt", request.negativePrompt.trim())
                }
                if (request.seed != null && request.seed >= 0L) {
                    multipartBuilder.addFormDataPart("seed", request.seed.toString())
                }

                if (!request.sourceImageDataUri.isNullOrBlank()) {
                    val rawB64 = request.sourceImageDataUri.substringAfter("base64,", "")
                    val imgBytes = Base64.decode(rawB64, Base64.DEFAULT)
                    multipartBuilder.addFormDataPart("mode", "image-to-image")
                    multipartBuilder.addFormDataPart(
                        "image",
                        "reference.jpg",
                        imgBytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    multipartBuilder.addFormDataPart(
                        "strength",
                        request.imageToImageStrength.coerceIn(0.05f, 0.95f).toString()
                    )
                } else {
                    multipartBuilder.addFormDataPart("aspect_ratio", request.aspectRatio.ifBlank { "1:1" })
                }

                if (request.stylePreset.isNotBlank() && !request.stylePreset.equals("None", ignoreCase = true) && !lowerModel.contains("sd3")) {
                    val stabilityPreset = request.stylePreset.lowercase().replace(" ", "-")
                    multipartBuilder.addFormDataPart("style_preset", stabilityPreset)
                }
            }

            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post(multipartBuilder.build())
                .header("Accept", "application/json")

            if (request.apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${request.apiKey.trim()}")
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

                val json = JSONObject(rawBody)
                val b64 = json.optString("image", "")
                val seedUsed = if (json.has("seed")) json.optLong("seed") else request.seed
                if (b64.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Stability AI response did not include base64 image data.")
                    )
                }
                Result.success(
                    ImageGenerationOutput(
                        imagePayloads = listOf("data:image/jpeg;base64,$b64"),
                        seedUsed = seedUsed
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

    override suspend fun submitVideoJob(
        provider: ProviderInstanceEntity,
        apiKey: String,
        modelId: String,
        prompt: String
    ): Result<String> = Result.failure(
        UnsupportedOperationException("Video generation is scheduled for Phase 4.")
    )
}
