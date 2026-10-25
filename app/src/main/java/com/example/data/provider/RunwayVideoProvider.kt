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
 * Dedicated adapter for Runway API (https://api.dev.runwayml.com/v1).
 * Uses `X-Runway-Version: 2024-11-06` and supports Gen-3 Alpha Turbo (`gen3a_turbo`)
 * and Gen-4 Turbo (`gen4_turbo`) video generation and `/tasks/{id}` polling.
 */
class RunwayVideoProvider(
    private val okHttpClient: OkHttpClient
) : AiProvider {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val RUNWAY_API_VERSION = "2024-11-06"
        val DEFAULT_MODELS = listOf(
            "gen3a_turbo" to "Runway Gen-3 Alpha Turbo",
            "gen4_turbo" to "Runway Gen-4 Turbo"
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
                    suggestedFix = "Open the Videos tab to generate videos with Runway."
                )
            )
        )
    }

    override suspend fun generateImage(request: ImageGenerationRequest): Result<ImageGenerationOutput> =
        Result.failure(UnsupportedOperationException("Use the Videos tab for Runway."))

    override suspend fun submitVideoGeneration(
        request: VideoGenerationRequest
    ): Result<VideoSubmissionResult> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = request.provider.baseUrl.trim().trimEnd('/')
            val payload = buildRunwayPayloadJson(request)

            val httpReq = Request.Builder()
                .url("$cleanBase/image_to_video")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Runway-Version", RUNWAY_API_VERSION)
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
                val taskId = root.optString("id", "").ifBlank {
                    return@withContext Result.failure(
                        IllegalStateException("Runway response did not contain a task ID.")
                    )
                }
                Result.success(
                    VideoSubmissionResult(
                        remoteJobId = taskId,
                        status = "QUEUED"
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
                "$cleanBase/tasks/$remoteJobId"
            }

            val httpReq = Request.Builder()
                .url(pollUrl)
                .get()
                .header("Accept", "application/json")
                .header("X-Runway-Version", RUNWAY_API_VERSION)
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
                val status = root.optString("status", "RUNNING").uppercase()
                val progress = (root.optDouble("progress", 0.5) * 100).toInt().coerceIn(10, 99)
                val outputArr = root.optJSONArray("output")
                val videoUrl = if (outputArr != null && outputArr.length() > 0) {
                    outputArr.optString(0, "").takeIf { it.isNotBlank() }
                } else null
                val failure = root.optString("failure", "").takeIf { it.isNotBlank() }

                when (status) {
                    "SUCCEEDED" -> Result.success(
                        VideoPollResult(
                            status = "COMPLETED",
                            progressPercent = 100,
                            videoUrl = videoUrl
                        )
                    )
                    "FAILED", "CANCELLED" -> Result.success(
                        VideoPollResult(
                            status = "FAILED",
                            progressPercent = 100,
                            errorMessage = failure ?: "Runway video task $status"
                        )
                    )
                    "PENDING", "THROTTLED" -> Result.success(
                        VideoPollResult(
                            status = "QUEUED",
                            progressPercent = 20
                        )
                    )
                    else -> Result.success(
                        VideoPollResult(
                            status = "PROCESSING",
                            progressPercent = progress
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

    internal fun buildRunwayPayloadJson(request: VideoGenerationRequest): JSONObject {
        val enrichedPrompt = ModelCapabilityRegistry.enrichVideoPromptWithMotion(
            prompt = request.prompt,
            cameraMotion = request.cameraMotion
        )
        val model = request.modelId.substringAfterLast("/").ifBlank { "gen3a_turbo" }
        val ratio = when (request.aspectRatio) {
            "9:16" -> "768:1280"
            else -> "1280:768"
        }
        val duration = if (request.durationSeconds >= 8) 10 else 5
        return JSONObject().apply {
            put("model", model)
            put("promptText", enrichedPrompt)
            put("duration", duration)
            put("ratio", ratio)
            if (!request.sourceImageDataUri.isNullOrBlank()) {
                put("promptImage", request.sourceImageDataUri)
            }
        }
    }
}
