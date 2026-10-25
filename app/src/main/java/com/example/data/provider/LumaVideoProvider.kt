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
 * Dedicated adapter for Luma Dream Machine REST API (https://api.lumalabs.ai/dream-machine/v1).
 * Supports Ray 2 (`ray-2`, `ray-flash-2`) text-to-video, image-to-video keyframes, camera motion,
 * and asynchronous generation polling via `/generations/{id}`.
 */
class LumaVideoProvider(
    private val okHttpClient: OkHttpClient
) : AiProvider {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        val DEFAULT_MODELS = listOf(
            "ray-2" to "Luma Ray 2 (Cinema Flagship)",
            "ray-flash-2" to "Luma Ray Flash 2 (Fast)"
        )
    }

    override suspend fun testConnection(
        provider: ProviderInstanceEntity,
        apiKey: String
    ): Result<List<ModelDescriptorEntity>> = listModels(provider, apiKey)

    override suspend fun listModels(
        provider: ProviderInstanceEntity,
        apiKey: String
    ): Result<List<ModelDescriptorEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = provider.baseUrl.trim().trimEnd('/')
            if (apiKey.isNotBlank()) {
                val pingReq = Request.Builder()
                    .url("$cleanBase/generations?limit=1")
                    .get()
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .build()
                okHttpClient.newCall(pingReq).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        val body = resp.body?.string().orEmpty()
                        throw ProviderNormalizedException(
                            ErrorNormalizer.normalizeHttpError(resp.code, body, provider.displayName)
                        )
                    }
                }
            }
            DEFAULT_MODELS.map { (id, label) ->
                ModelCapabilityRegistry.buildDescriptor(
                    providerInstanceId = provider.id,
                    rawModelId = id,
                    displayNameOverride = label
                )
            }
        }
    }

    override fun streamChat(request: ChatCompletionRequest): Flow<ChatStreamEvent> = flow {
        emit(
            ChatStreamEvent.Error(
                NormalizedAiError(
                    category = ErrorCategory.SERVER_ERROR,
                    title = "Video-Only Provider",
                    message = "${request.provider.displayName} is a dedicated video generation provider.",
                    suggestedFix = "Open the Videos tab to generate videos with Luma Ray 2."
                )
            )
        )
    }

    override suspend fun generateImage(request: ImageGenerationRequest): Result<ImageGenerationOutput> =
        Result.failure(UnsupportedOperationException("Use the Videos tab for Luma Dream Machine."))

    override suspend fun submitVideoGeneration(
        request: VideoGenerationRequest
    ): Result<VideoSubmissionResult> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = request.provider.baseUrl.trim().trimEnd('/')
            val payload = buildLumaPayloadJson(request)

            val httpReq = Request.Builder()
                .url("$cleanBase/generations")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .apply {
                    if (request.apiKey.isNotBlank()) {
                        header("Authorization", "Bearer ${request.apiKey.trim()}")
                    }
                }
                .build()

            okHttpClient.newCall(httpReq).execute().use { response ->
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
                val genId = root.optString("id", "").ifBlank {
                    return@withContext Result.failure(
                        IllegalStateException("Luma response did not contain a generation ID.")
                    )
                }
                val state = root.optString("state", "queued").lowercase()
                val videoUrl = root.optJSONObject("assets")?.optString("video", "")?.takeIf { it.isNotBlank() }

                Result.success(
                    VideoSubmissionResult(
                        remoteJobId = genId,
                        status = if (state == "completed" && videoUrl != null) "COMPLETED" else "PROCESSING",
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
            val cleanBase = provider.baseUrl.trim().trimEnd('/')
            val pollUrl = if (remoteJobId.startsWith("http")) {
                remoteJobId
            } else {
                "$cleanBase/generations/$remoteJobId"
            }

            val httpReq = Request.Builder()
                .url(pollUrl)
                .get()
                .header("Accept", "application/json")
                .apply {
                    if (apiKey.isNotBlank()) {
                        header("Authorization", "Bearer ${apiKey.trim()}")
                    }
                }
                .build()

            okHttpClient.newCall(httpReq).execute().use { response ->
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
                val state = root.optString("state", "dreaming").lowercase()
                val videoUrl = root.optJSONObject("assets")?.optString("video", "")?.takeIf { it.isNotBlank() }
                val failureReason = root.optString("failure_reason", "").takeIf { it.isNotBlank() }

                when (state) {
                    "completed" -> Result.success(
                        VideoPollResult(
                            status = "COMPLETED",
                            progressPercent = 100,
                            videoUrl = videoUrl
                        )
                    )
                    "failed" -> Result.success(
                        VideoPollResult(
                            status = "FAILED",
                            progressPercent = 100,
                            errorMessage = failureReason ?: "Luma generation failed."
                        )
                    )
                    "queued" -> Result.success(
                        VideoPollResult(
                            status = "QUEUED",
                            progressPercent = 25
                        )
                    )
                    else -> Result.success(
                        VideoPollResult(
                            status = "PROCESSING",
                            progressPercent = 65
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

    internal fun buildLumaPayloadJson(request: VideoGenerationRequest): JSONObject {
        val enrichedPrompt = ModelCapabilityRegistry.enrichVideoPromptWithMotion(
            prompt = request.prompt,
            cameraMotion = request.cameraMotion
        )
        val model = request.modelId.substringAfterLast("/").ifBlank { "ray-2" }
        return JSONObject().apply {
            put("model", model)
            put("prompt", enrichedPrompt)
            put("aspect_ratio", request.aspectRatio)
            put("resolution", request.resolution)
            put("duration", "${request.durationSeconds}s")
            if (!request.sourceImageDataUri.isNullOrBlank()) {
                put(
                    "keyframes",
                    JSONObject().put(
                        "frame0",
                        JSONObject()
                            .put("type", "image")
                            .put("url", request.sourceImageDataUri)
                    )
                )
            }
        }
    }
}
