package com.example.data.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data sample representing a measured network round-trip response latency
 * for a specific provider and AI model.
 */
data class LatencyBenchmarkSample(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val latencyMs: Long,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestampMs)
        put("providerId", providerId)
        put("providerName", providerName)
        put("modelId", modelId)
        put("latencyMs", latencyMs)
        put("isSuccess", isSuccess)
        put("error", errorMessage ?: "")
    }

    companion object {
        fun listToJsonArray(samples: List<LatencyBenchmarkSample>): JSONArray {
            val array = JSONArray()
            for (sample in samples) {
                array.put(sample.toJson())
            }
            return array
        }
    }
}

/**
 * Speed rank aggregating latency metrics across samples for a provider.
 */
data class ProviderSpeedRank(
    val rank: Int,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val latestLatencyMs: Long,
    val avgLatencyMs: Long,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val sampleCount: Int,
    val isFastest: Boolean
)
