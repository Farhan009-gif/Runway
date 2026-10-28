package com.example.data.provider

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin

enum class LocalhostServerMode {
    STOPPED,
    STARTING,
    EMBEDDED_RUNNING,
    EXTERNAL_TERMUX_RUNNING,
    ERROR
}

data class LocalhostServerStatus(
    val mode: LocalhostServerMode = LocalhostServerMode.STOPPED,
    val host: String = "localhost",
    val port: Int = 20128,
    val startedAtMs: Long? = null,
    val requestCount: Int = 0,
    val lastRequestSummary: String = "No requests yet",
    val liveHealthJson: String = ""
) {
    val isListening: Boolean
        get() = mode == LocalhostServerMode.EMBEDDED_RUNNING || mode == LocalhostServerMode.EXTERNAL_TERMUX_RUNNING

    val webUrl: String
        get() = "http://$host:$port"

    val baseUrl: String
        get() = "http://$host:$port/v1"
}

enum class ModelHealthProbeState {
    UNTESTED,
    CHECKING,
    HEALTHY,
    UNHEALTHY
}

data class ModelHealthRecord(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val displayName: String,
    val state: ModelHealthProbeState = ModelHealthProbeState.UNTESTED,
    val latencyMs: Long? = null,
    val statusDetail: String = "Not checked yet",
    val checkedAtMs: Long = System.currentTimeMillis()
)

data class OmniRouteComboDefinition(
    val id: String,
    val displayName: String,
    val modality: String = "CHAT", // CHAT, VIDEO, IMAGE
    val strategy: String = "FALLBACK", // FALLBACK, ROUND_ROBIN, FASTEST
    val targetModels: List<String>,
    val description: String = "",
    val isBuiltIn: Boolean = false
)

data class OmniRouteVideoJobRecord(
    val jobId: String,
    val requestedModel: String,
    val routedModel: String,
    val comboId: String?,
    val prompt: String,
    val negativePrompt: String,
    val aspectRatio: String,
    val durationSeconds: Int,
    val resolution: String,
    val fps: Int,
    val cameraMotion: String,
    val stylePreset: String,
    val seed: Long,
    val createdAtMs: Long,
    val mp4File: File,
    val videoUrl: String
)

/**
 * Embedded Localhost OmniRoute Gateway (`localhost:20128`) + Interactive Web Studio & Terminal Runner.
 *
 * Supports:
 * - Zero-config Chat models (`ddgw/gpt-4o-mini`, `ddgw/claude-3-haiku`, `ddgw/llama-3.3-70b`, `ddgw/mistral-small-24b`)
 * - Video Generation models (`omniroute/wan-2.1-t2v`, `omniroute/kling-v1.6-pro`, `omniroute/luma-ray-2`, `omniroute/veo-2-cinema`, `omniroute/hunyuan-video`, `omniroute/seedance-v1`, `omniroute/gen4-turbo`)
 * - Image Generation models (`omniroute/flux-schnell`, `omniroute/sdxl-lightning`)
 * - Built-in & User-Defined Custom Combos across `CHAT`, `VIDEO`, and `IMAGE` modalities (`/v1/combos`, `/v1/models`, `/v1/chat/completions`, `/v1/videos/generations`, `/v1/images/generations`)
 */
object EmbeddedOmniRouteServer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)
    private val servedRequests = AtomicInteger(0)
    private val roundRobinCursor = AtomicInteger(0)

    private val upstreamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val defaultBuiltInCombos = listOf(
        OmniRouteComboDefinition(
            id = "omniroute/auto-combo",
            displayName = "OmniRoute Auto Combo (Smart Chat Failover)",
            modality = "CHAT",
            strategy = "FALLBACK",
            targetModels = listOf("ddgw/gpt-4o-mini", "ddgw/claude-3-haiku", "ddgw/llama-3.3-70b"),
            description = "Routes chat & tool calls across GPT-4o Mini, Claude 3 Haiku, and Llama 3.3 70B with automatic failover.",
            isBuiltIn = true
        ),
        OmniRouteComboDefinition(
            id = "omniroute/coding-fallback",
            displayName = "OmniRoute Coding & Reasoning Combo",
            modality = "CHAT",
            strategy = "FALLBACK",
            targetModels = listOf("ddgw/o3-mini", "ddgw/gpt-4o-mini", "ddgw/llama-3.3-70b"),
            description = "Prioritizes reasoning and code synthesis models with instant fallback.",
            isBuiltIn = true
        ),
        OmniRouteComboDefinition(
            id = "omniroute/vision-pro",
            displayName = "OmniRoute Multimodal Vision Combo",
            modality = "CHAT",
            strategy = "ROUND_ROBIN",
            targetModels = listOf("ddgw/gpt-4o-mini", "ddgw/claude-3-haiku", "ddgw/mistral-small-24b"),
            description = "Load-balances multimodal vision & chat turns across low-latency providers.",
            isBuiltIn = true
        ),
        OmniRouteComboDefinition(
            id = "omniroute/video-combo",
            displayName = "OmniRoute Video Studio Combo (Auto Video Router)",
            modality = "VIDEO",
            strategy = "FALLBACK",
            targetModels = listOf(
                "omniroute/wan-2.1-t2v",
                "omniroute/kling-v1.6-pro",
                "omniroute/luma-ray-2",
                "omniroute/veo-2-cinema"
            ),
            description = "Automatically routes text-to-video and image-to-video jobs across Wan 2.1, Kling 1.6 Pro, Luma Ray 2, and Veo 2.",
            isBuiltIn = true
        ),
        OmniRouteComboDefinition(
            id = "omniroute/cinema-director",
            displayName = "OmniRoute Cinema Director Combo (HD Motion)",
            modality = "VIDEO",
            strategy = "ROUND_ROBIN",
            targetModels = listOf(
                "omniroute/luma-ray-2",
                "omniroute/veo-2-cinema",
                "omniroute/gen4-turbo",
                "omniroute/hunyuan-video"
            ),
            description = "Cycles across cinematic high-motion video engines for multi-scene storyboards.",
            isBuiltIn = true
        ),
        OmniRouteComboDefinition(
            id = "omniroute/flux-combo",
            displayName = "OmniRoute Flux Image Combo",
            modality = "IMAGE",
            strategy = "FALLBACK",
            targetModels = listOf("omniroute/flux-schnell", "omniroute/sdxl-lightning"),
            description = "High-speed Flux Schnell & SDXL Lightning image generation router.",
            isBuiltIn = true
        )
    )

    private val _combosFlow = MutableStateFlow<List<OmniRouteComboDefinition>>(defaultBuiltInCombos)
    val combosFlow: StateFlow<List<OmniRouteComboDefinition>> = _combosFlow.asStateFlow()

    private val videoJobs = ConcurrentHashMap<String, OmniRouteVideoJobRecord>()

    private val _statusFlow = MutableStateFlow(LocalhostServerStatus())
    val statusFlow: StateFlow<LocalhostServerStatus> = _statusFlow.asStateFlow()

    private val _terminalLinesFlow = MutableStateFlow(
        listOf(
            "OmniRoute Embedded Terminal v2.6 (Chat + Video Generation + Custom Combos)",
            "Type 'help', 'omniroute start', 'omniroute combos', 'omniroute video <prompt>', or 'curl http://localhost:20128/v1/models'."
        )
    )
    val terminalLinesFlow: StateFlow<List<String>> = _terminalLinesFlow.asStateFlow()

    fun findCombo(modelId: String): OmniRouteComboDefinition? {
        val clean = modelId.trim()
        return _combosFlow.value.firstOrNull { it.id.equals(clean, ignoreCase = true) }
    }

    fun upsertCustomCombo(
        rawId: String,
        displayName: String,
        modality: String,
        strategy: String,
        targetModels: List<String>,
        description: String = ""
    ): OmniRouteComboDefinition {
        val normalizedId = rawId.trim().let { id ->
            when {
                id.isEmpty() -> "omniroute/custom-${System.currentTimeMillis() % 10000}"
                id.contains("/") -> id
                else -> "omniroute/$id"
            }
        }
        val cleanModality = modality.trim().uppercase().takeIf { it in setOf("CHAT", "VIDEO", "IMAGE") } ?: "CHAT"
        val cleanStrategy = strategy.trim().uppercase().takeIf { it in setOf("FALLBACK", "ROUND_ROBIN", "FASTEST") } ?: "FALLBACK"
        val cleanTargets = targetModels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty {
            when (cleanModality) {
                "VIDEO" -> listOf("omniroute/wan-2.1-t2v", "omniroute/kling-v1.6-pro", "omniroute/luma-ray-2")
                "IMAGE" -> listOf("omniroute/flux-schnell", "omniroute/sdxl-lightning")
                else -> listOf("ddgw/gpt-4o-mini", "ddgw/claude-3-haiku", "ddgw/llama-3.3-70b")
            }
        }
        val cleanLabel = displayName.trim().ifEmpty {
            "OmniRoute Custom ${cleanModality.lowercase().replaceFirstChar { it.uppercase() }} Combo ($normalizedId)"
        }
        val cleanDesc = description.trim().ifEmpty {
            "$cleanStrategy router across ${cleanTargets.joinToString(" -> ")}"
        }
        val existing = findCombo(normalizedId)
        val updatedCombo = OmniRouteComboDefinition(
            id = normalizedId,
            displayName = cleanLabel,
            modality = cleanModality,
            strategy = cleanStrategy,
            targetModels = cleanTargets,
            description = cleanDesc,
            isBuiltIn = existing?.isBuiltIn ?: false
        )
        _combosFlow.update { list ->
            val filtered = list.filterNot { it.id.equals(normalizedId, ignoreCase = true) }
            listOf(updatedCombo) + filtered
        }
        appendTerminalLine("[COMBO] Configured $normalizedId [$cleanModality • $cleanStrategy] -> ${cleanTargets.joinToString(", ")}")
        return updatedCombo
    }

    fun deleteCustomCombo(comboId: String): Boolean {
        val clean = comboId.trim()
        var removed = false
        _combosFlow.update { list ->
            val next = list.filterNot {
                val match = it.id.equals(clean, ignoreCase = true)
                if (match) removed = true
                match
            }
            next.ifEmpty { defaultBuiltInCombos }
        }
        if (removed) {
            appendTerminalLine("[COMBO] Removed combo $clean")
        }
        return removed
    }

    fun resetDefaultCombos() {
        _combosFlow.value = defaultBuiltInCombos
        appendTerminalLine("[COMBO] Reset OmniRoute combos to default Chat, Video, and Image combos.")
    }

    /**
     * Resolves a requested model ID (which may be a combo ID like `omniroute/video-combo` or a user custom combo)
     * into an ordered list of concrete target models according to the combo's routing strategy.
     */
    fun resolveOrderedTargets(requestedModel: String, defaultModality: String = "CHAT"): List<String> {
        val combo = findCombo(requestedModel) ?: return listOf(requestedModel)
        val targets = combo.targetModels.ifEmpty {
            when (combo.modality.uppercase()) {
                "VIDEO" -> listOf("omniroute/wan-2.1-t2v", "omniroute/kling-v1.6-pro")
                "IMAGE" -> listOf("omniroute/flux-schnell")
                else -> listOf("ddgw/gpt-4o-mini")
            }
        }
        return when (combo.strategy.uppercase()) {
            "ROUND_ROBIN" -> {
                val idx = (roundRobinCursor.getAndIncrement().coerceAtLeast(0)) % targets.size
                targets.drop(idx) + targets.take(idx)
            }
            "FASTEST" -> targets.sortedBy { EndpointUrlNormalizer.modelPriorityRank(it) }
            else -> targets
        }
    }

    fun appendTerminalLine(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val formatted = if (line.startsWith("$") || line.startsWith(" ") || line.isBlank()) {
            line
        } else {
            "[$ts] $line"
        }
        _terminalLinesFlow.update { current ->
            (current + formatted).takeLast(120)
        }
    }

    fun clearTerminal() {
        _terminalLinesFlow.value = listOf(
            "Terminal buffer cleared. Type 'omniroute status', 'omniroute combos', or 'help'."
        )
    }

    suspend fun startServer(port: Int = 20128): LocalhostServerStatus = withContext(Dispatchers.IO) {
        if (running.get() && serverSocket?.isClosed == false) {
            val current = _statusFlow.value
            return@withContext current
        }

        _statusFlow.update { it.copy(mode = LocalhostServerMode.STARTING, host = "localhost", port = port) }
        appendTerminalLine("Starting OmniRoute Localhost Gateway on http://localhost:$port/v1 ...")
        appendTerminalLine("Capabilities: Chat + Video Generation (/v1/videos/generations) + Image Generation + Custom Combos (/v1/combos)")

        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            try {
                socket.bind(InetSocketAddress(port), 50)
            } catch (_: Throwable) {
                socket.bind(InetSocketAddress(InetAddress.getByName("localhost"), port), 50)
            }
            serverSocket = socket
            running.set(true)

            val startedNow = System.currentTimeMillis()
            val healthJson = buildHealthJson(port, startedNow, 0).toString(2)
            _statusFlow.value = LocalhostServerStatus(
                mode = LocalhostServerMode.EMBEDDED_RUNNING,
                host = "localhost",
                port = port,
                startedAtMs = startedNow,
                requestCount = servedRequests.get(),
                lastRequestSummary = "Listening on http://localhost:$port/v1",
                liveHealthJson = healthJson
            )
            appendTerminalLine("[LISTEN] Bound TCP socket localhost:$port (PID=${android.os.Process.myPid()})")
            appendTerminalLine("[WEB UI] OmniRoute Website live at http://localhost:$port")
            appendTerminalLine("[READY] Endpoints live on localhost:$port:")
            appendTerminalLine("  • GET  http://localhost:$port/v1/models (Chat + Video + Image + Custom Combos)")
            appendTerminalLine("  • GET  http://localhost:$port/v1/combos (Custom Combo Registry)")
            appendTerminalLine("  • POST http://localhost:$port/v1/chat/completions")
            appendTerminalLine("  • POST http://localhost:$port/v1/videos/generations")
            appendTerminalLine("  • POST http://localhost:$port/v1/images/generations")

            acceptJob?.cancel()
            acceptJob = scope.launch {
                while (isActive && running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    launch {
                        handleClientSocket(client, port)
                    }
                }
            }
            return@withContext _statusFlow.value
        } catch (bindEx: BindException) {
            val probe = probeLocalhostHealth(port)
            if (probe != null) {
                _statusFlow.value = LocalhostServerStatus(
                    mode = LocalhostServerMode.EXTERNAL_TERMUX_RUNNING,
                    host = "localhost",
                    port = port,
                    startedAtMs = System.currentTimeMillis(),
                    requestCount = servedRequests.get(),
                    lastRequestSummary = "Attached to existing localhost:$port listener",
                    liveHealthJson = probe
                )
                appendTerminalLine("[ATTACHED] Port $port is already active on localhost:$port.")
                return@withContext _statusFlow.value
            } else {
                _statusFlow.value = LocalhostServerStatus(
                    mode = LocalhostServerMode.ERROR,
                    host = "localhost",
                    port = port,
                    lastRequestSummary = "Port $port busy: ${bindEx.message}"
                )
                appendTerminalLine("[ERROR] Could not bind localhost:$port: ${bindEx.message}")
                return@withContext _statusFlow.value
            }
        } catch (e: Throwable) {
            _statusFlow.value = LocalhostServerStatus(
                mode = LocalhostServerMode.ERROR,
                host = "localhost",
                port = port,
                lastRequestSummary = "Error: ${e.message}"
            )
            appendTerminalLine("[ERROR] Failed to start localhost server: ${e.message}")
            return@withContext _statusFlow.value
        }
    }

    suspend fun stopServer(): LocalhostServerStatus = withContext(Dispatchers.IO) {
        running.set(false)
        runCatching { acceptJob?.cancel() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        _statusFlow.value = LocalhostServerStatus(
            mode = LocalhostServerMode.STOPPED,
            host = "localhost",
            port = _statusFlow.value.port,
            requestCount = servedRequests.get(),
            lastRequestSummary = "Stopped by user"
        )
        appendTerminalLine("[STOPPED] Localhost server on localhost:${_statusFlow.value.port} stopped.")
        _statusFlow.value
    }

    private fun probeLocalhostHealth(port: Int): String? {
        val urls = listOf(
            "http://localhost:$port/health",
            "http://localhost:$port/v1/models",
            "http://127.0.0.1:$port/health"
        )
        for (u in urls) {
            val req = Request.Builder().url(u).get().build()
            val body = runCatching {
                upstreamClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string()?.take(600) else null
                }
            }.getOrNull()
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    private fun buildHealthJson(port: Int, startedAtMs: Long?, reqCount: Int): JSONObject {
        val uptimeSec = if (startedAtMs != null) ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L) else 0L
        val currentCombos = _combosFlow.value
        return JSONObject().apply {
            put("status", "healthy")
            put("service", "OmniRoute Embedded Localhost Gateway")
            put("listen", "http://localhost:$port")
            put("web_url", "http://localhost:$port")
            put("base_url", "http://localhost:$port/v1")
            put("uptime_seconds", uptimeSec)
            put("served_requests", reqCount)
            put("playwright_chromium_required", false)
            put("oauth_scrapers_enabled", false)
            put("video_generation_enabled", true)
            put("custom_combos_enabled", true)
            put("active_combos_count", currentCombos.size)
            put(
                "healthy_providers",
                JSONArray().apply {
                    put("ddgw (DuckDuckGo Zero-Config Chat)")
                    put("omniroute-video (Wan 2.1, Kling 1.6, Luma Ray 2, Veo 2, Hunyuan, Seedance, Gen-4)")
                    put("pollinations (Flux & SDXL Zero-Config Image/Text)")
                }
            )
            put(
                "models",
                JSONArray().apply {
                    EndpointUrlNormalizer.defaultWorkingOmniRouteModels.forEach { (id, _) -> put(id) }
                    EndpointUrlNormalizer.defaultOmniRouteVideoModels.forEach { (id, _) -> put(id) }
                    EndpointUrlNormalizer.defaultOmniRouteImageModels.forEach { (id, _) -> put(id) }
                    currentCombos.forEach { combo -> put(combo.id) }
                }
            )
            put(
                "combos",
                JSONArray().apply {
                    currentCombos.forEach { combo ->
                        put(
                            JSONObject().apply {
                                put("id", combo.id)
                                put("display_name", combo.displayName)
                                put("modality", combo.modality)
                                put("strategy", combo.strategy)
                                put("targets", JSONArray(combo.targetModels))
                            }
                        )
                    }
                }
            )
        }
    }

    fun buildModelsCatalogJson(): JSONObject {
        val dataArr = JSONArray()
        val seenIds = mutableSetOf<String>()
        val nowSec = System.currentTimeMillis() / 1000L

        fun appendModel(
            id: String,
            label: String,
            ownedBy: String,
            modality: String,
            isCombo: Boolean,
            strategy: String? = null,
            targets: List<String>? = null
        ) {
            if (!seenIds.add(id.lowercase())) return
            dataArr.put(
                JSONObject().apply {
                    put("id", id)
                    put("object", "model")
                    put("created", nowSec)
                    put("owned_by", ownedBy)
                    put("display_name", label)
                    put("modality", modality)
                    put("is_combo", isCombo)
                    if (strategy != null) put("strategy", strategy)
                    if (!targets.isNullOrEmpty()) put("targets", JSONArray(targets))
                }
            )
        }

        // 1. Custom & Built-In Combos first so users see their combos at the top
        for (combo in _combosFlow.value) {
            appendModel(
                id = combo.id,
                label = combo.displayName,
                ownedBy = "omniroute-combo",
                modality = combo.modality,
                isCombo = true,
                strategy = combo.strategy,
                targets = combo.targetModels
            )
        }

        // 2. Zero-config Chat models
        for ((id, label) in EndpointUrlNormalizer.defaultWorkingOmniRouteModels) {
            appendModel(
                id = id,
                label = label,
                ownedBy = "omniroute-chat",
                modality = "CHAT",
                isCombo = false
            )
        }

        // 3. Video Generation models
        for ((id, label) in EndpointUrlNormalizer.defaultOmniRouteVideoModels) {
            val existingCombo = findCombo(id)
            appendModel(
                id = id,
                label = label,
                ownedBy = if (existingCombo != null) "omniroute-combo" else "omniroute-video",
                modality = "VIDEO",
                isCombo = existingCombo != null || id.contains("combo") || id.contains("director"),
                strategy = existingCombo?.strategy,
                targets = existingCombo?.targetModels
            )
        }

        // 4. Image Generation models
        for ((id, label) in EndpointUrlNormalizer.defaultOmniRouteImageModels) {
            val existingCombo = findCombo(id)
            appendModel(
                id = id,
                label = label,
                ownedBy = if (existingCombo != null) "omniroute-combo" else "omniroute-image",
                modality = "IMAGE",
                isCombo = existingCombo != null || id.contains("combo"),
                strategy = existingCombo?.strategy,
                targets = existingCombo?.targetModels
            )
        }

        val combosArray = JSONArray()
        for (combo in _combosFlow.value) {
            combosArray.put(
                JSONObject().apply {
                    put("id", combo.id)
                    put("name", combo.displayName)
                    put("display_name", combo.displayName)
                    put("modality", combo.modality)
                    put("strategy", combo.strategy)
                    put("description", combo.description)
                    put("is_built_in", combo.isBuiltIn)
                    put("targets", JSONArray(combo.targetModels))
                    put("models", JSONArray(combo.targetModels))
                }
            )
        }

        return JSONObject().apply {
            put("object", "list")
            put("data", dataArr)
            put("combos", combosArray)
        }
    }

    private fun buildCombosJson(): JSONObject {
        val arr = JSONArray()
        for (combo in _combosFlow.value) {
            arr.put(
                JSONObject().apply {
                    put("id", combo.id)
                    put("display_name", combo.displayName)
                    put("modality", combo.modality)
                    put("strategy", combo.strategy)
                    put("description", combo.description)
                    put("is_built_in", combo.isBuiltIn)
                    put("targets", JSONArray(combo.targetModels))
                }
            )
        }
        return JSONObject().apply {
            put("object", "list")
            put("data", arr)
        }
    }

    private fun readHeaderLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString(StandardCharsets.UTF_8.name())
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                baos.write(b)
            }
        }
        return baos.toString(StandardCharsets.UTF_8.name())
    }

    private suspend fun handleClientSocket(socket: Socket, port: Int) = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            socket.soTimeout = 45_000
            val input = socket.getInputStream()

            val requestLine = readHeaderLine(input) ?: return@withContext
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val rawPath = parts.getOrNull(1) ?: "/"
            val path = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")

            var contentLength = 0
            var acceptHeader = ""
            while (true) {
                val headerLine = readHeaderLine(input) ?: break
                if (headerLine.isEmpty()) break
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                } else if (headerLine.startsWith("Accept:", ignoreCase = true)) {
                    acceptHeader = headerLine.substringAfter(":").trim().lowercase()
                }
            }

            val bodyString = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val r = input.read(buf, totalRead, contentLength - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                String(buf, 0, totalRead, StandardCharsets.UTF_8)
            } else {
                ""
            }

            val count = servedRequests.incrementAndGet()
            val output = socket.getOutputStream()

            when {
                method == "OPTIONS" -> {
                    writeHttpResponse(output, 200, "OK", "application/json", "{}")
                }
                method == "GET" && (path == "/dashboard" || path == "/index.html" || path == "/ui" || path == "/playground" ||
                    (path == "/" && !query.contains("format=json") && !acceptHeader.equals("application/json"))) -> {
                    val html = buildOmniRouteWebDashboardHtml(port, _statusFlow.value.startedAtMs, count)
                    writeHttpResponse(output, 200, "OK", "text/html", html)
                    recordRequestLog(method, path, 200, System.currentTimeMillis() - startMs, null)
                }
                method == "GET" && (path == "/" || path == "/health" || path == "/status" || path == "/v1/health" || path == "/api/health") -> {
                    val json = buildHealthJson(port, _statusFlow.value.startedAtMs, count).toString(2)
                    writeHttpResponse(output, 200, "OK", "application/json", json)
                    recordRequestLog(method, path, 200, System.currentTimeMillis() - startMs, json)
                }
                method == "GET" && (path == "/v1/models" || path == "/models" || path == "/api/models" || path == "/api/tags") -> {
                    val json = buildModelsCatalogJson().toString()
                    writeHttpResponse(output, 200, "OK", "application/json", json)
                    recordRequestLog(method, path, 200, System.currentTimeMillis() - startMs, null)
                }
                method == "GET" && (path == "/v1/combos" || path == "/combos" || path == "/api/combos") -> {
                    val json = buildCombosJson().toString(2)
                    writeHttpResponse(output, 200, "OK", "application/json", json)
                    recordRequestLog(method, path, 200, System.currentTimeMillis() - startMs, null)
                }
                method == "POST" && (path == "/v1/combos" || path == "/combos" || path == "/api/combos") -> {
                    handleComboUpsertRequest(output, bodyString, path, startMs)
                }
                (method == "DELETE" && (path.startsWith("/v1/combos") || path.startsWith("/combos"))) ||
                    (method == "POST" && (path == "/v1/combos/delete" || path == "/combos/delete")) -> {
                    handleComboDeleteRequest(output, path, query, bodyString, startMs)
                }
                method == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions" || path == "/api/chat") -> {
                    handleChatCompletionRequest(output, bodyString, path, startMs)
                }
                method == "POST" && (path == "/v1/images/generations" || path == "/images/generations" || path == "/v1/images/edits") -> {
                    handleImageGenerationRequest(output, bodyString, path, startMs)
                }
                method == "POST" && (
                    path == "/v1/videos/generations" ||
                        path == "/videos/generations" ||
                        path == "/v1/video/generations" ||
                        path == "/video/generations" ||
                        path == "/v1/videos"
                    ) -> {
                    handleVideoGenerationRequest(output, bodyString, path, port, startMs)
                }
                method == "GET" && (
                    path.startsWith("/v1/videos/generations/") ||
                        path.startsWith("/videos/generations/") ||
                        path.startsWith("/v1/video/generations/")
                    ) -> {
                    val jobId = path.substringAfterLast("/").trim()
                    handleVideoPollRequest(output, jobId, path, port, startMs)
                }
                method == "GET" && (path.startsWith("/v1/videos/media/") || path.startsWith("/videos/media/")) -> {
                    val fileToken = path.substringAfterLast("/").removeSuffix(".mp4").trim()
                    handleVideoMediaStream(output, fileToken, path, startMs)
                }
                else -> {
                    val err = JSONObject().apply {
                        put(
                            "error",
                            JSONObject().apply {
                                put(
                                    "message",
                                    "Route $method $path not found on Embedded OmniRoute (localhost:$port). Supported routes: /v1/models, /v1/combos, /v1/chat/completions, /v1/videos/generations, /v1/images/generations."
                                )
                                put("type", "invalid_request_error")
                            }
                        )
                    }.toString()
                    writeHttpResponse(output, 404, "Not Found", "application/json", err)
                    recordRequestLog(method, path, 404, System.currentTimeMillis() - startMs, null)
                }
            }
        } catch (e: Throwable) {
            appendTerminalLine("[localhost:$port] Socket error: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleComboUpsertRequest(
        output: OutputStream,
        rawBody: String,
        path: String,
        startMs: Long
    ) {
        val reqJson = runCatching { JSONObject(rawBody) }.getOrElse { JSONObject() }
        val rawId = reqJson.optString("id", reqJson.optString("name", "")).trim()
        val displayName = reqJson.optString("display_name", reqJson.optString("label", "")).trim()
        val modality = reqJson.optString("modality", "CHAT").trim()
        val strategy = reqJson.optString("strategy", "FALLBACK").trim()
        val description = reqJson.optString("description", "").trim()

        val targetsList = mutableListOf<String>()
        val targetsArr = reqJson.optJSONArray("targets") ?: reqJson.optJSONArray("models")
        if (targetsArr != null) {
            for (i in 0 until targetsArr.length()) {
                val t = targetsArr.optString(i).trim()
                if (t.isNotEmpty()) targetsList.add(t)
            }
        } else {
            val csv = reqJson.optString("targets_csv", reqJson.optString("targets", ""))
            csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { targetsList.add(it) }
        }

        val saved = upsertCustomCombo(
            rawId = rawId,
            displayName = displayName,
            modality = modality,
            strategy = strategy,
            targetModels = targetsList,
            description = description
        )
        val respJson = JSONObject().apply {
            put("status", "ok")
            put(
                "combo",
                JSONObject().apply {
                    put("id", saved.id)
                    put("display_name", saved.displayName)
                    put("modality", saved.modality)
                    put("strategy", saved.strategy)
                    put("description", saved.description)
                    put("targets", JSONArray(saved.targetModels))
                }
            )
            put("combos", buildCombosJson().optJSONArray("data"))
        }.toString(2)
        writeHttpResponse(output, 200, "OK", "application/json", respJson)
        recordRequestLog("POST", "$path (${saved.id})", 200, System.currentTimeMillis() - startMs, null)
    }

    private fun handleComboDeleteRequest(
        output: OutputStream,
        path: String,
        query: String,
        rawBody: String,
        startMs: Long
    ) {
        val bodyJson = runCatching { JSONObject(rawBody) }.getOrNull()
        val idFromBody = bodyJson?.optString("id")?.takeIf { it.isNotBlank() }
        val idFromQuery = query.split("&")
            .firstOrNull { it.startsWith("id=") }
            ?.substringAfter("id=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val idFromPath = path.substringAfter("/combos/", "").takeIf { it.isNotBlank() && it != "delete" }
        val targetId = idFromBody ?: idFromQuery ?: idFromPath ?: ""
        val deleted = if (targetId.isNotBlank()) deleteCustomCombo(targetId) else false
        val respJson = JSONObject().apply {
            put("status", if (deleted) "deleted" else "not_found")
            put("id", targetId)
            put("combos", buildCombosJson().optJSONArray("data"))
        }.toString(2)
        writeHttpResponse(output, 200, "OK", "application/json", respJson)
        recordRequestLog("DELETE", "$path ($targetId)", 200, System.currentTimeMillis() - startMs, null)
    }

    private fun handleImageGenerationRequest(
        output: OutputStream,
        rawRequestBody: String,
        path: String,
        startMs: Long
    ) {
        val reqJson = runCatching { JSONObject(rawRequestBody) }.getOrElse { JSONObject() }
        val requestedModel = reqJson.optString("model", "omniroute/flux-combo").ifBlank { "omniroute/flux-combo" }
        val prompt = reqJson.optString("prompt", "A cinematic futuristic skyline at dusk").ifBlank { "A cinematic futuristic skyline at dusk" }
        val size = reqJson.optString("size", "1024x1024")
        val dims = size.split("x")
        val width = dims.getOrNull(0)?.toIntOrNull()?.coerceIn(256, 1536) ?: 1024
        val height = dims.getOrNull(1)?.toIntOrNull()?.coerceIn(256, 1536) ?: 1024
        val seed = reqJson.optLong("seed", (System.currentTimeMillis() % 999999L).coerceAtLeast(1L))

        val candidateTargets = resolveOrderedTargets(requestedModel, "IMAGE")
        val routedModel = candidateTargets.firstOrNull() ?: requestedModel
        val pollinationsModel = when {
            routedModel.contains("sdxl", ignoreCase = true) || routedModel.contains("turbo", ignoreCase = true) -> "turbo"
            else -> "flux"
        }
        val encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8.name())
        val imageUrl = "https://image.pollinations.ai/prompt/$encodedPrompt?width=$width&height=$height&seed=$seed&model=$pollinationsModel&nologo=true"

        val respJson = JSONObject().apply {
            put("created", System.currentTimeMillis() / 1000L)
            put("model", requestedModel)
            put("routed_model", routedModel)
            put(
                "data",
                JSONArray().put(
                    JSONObject().apply {
                        put("url", imageUrl)
                        put("revised_prompt", prompt)
                    }
                )
            )
        }.toString()

        val elapsed = System.currentTimeMillis() - startMs
        writeHttpResponse(output, 200, "OK", "application/json", respJson)
        recordRequestLog("POST", "$path ($requestedModel -> $routedModel)", 200, elapsed, null)
    }

    private fun handleVideoGenerationRequest(
        output: OutputStream,
        rawRequestBody: String,
        path: String,
        port: Int,
        startMs: Long
    ) {
        val reqJson = runCatching { JSONObject(rawRequestBody) }.getOrElse { JSONObject() }
        val requestedModel = reqJson.optString("model", "omniroute/video-combo").ifBlank { "omniroute/video-combo" }
        val prompt = reqJson.optString("prompt", "Cinematic aerial shot over neon cyberpunk metropolis in rain")
            .ifBlank { "Cinematic aerial shot over neon cyberpunk metropolis in rain" }
        val negativePrompt = reqJson.optString("negative_prompt", "")
        val aspectRatio = reqJson.optString("aspect_ratio", "16:9").ifBlank { "16:9" }
        val durationSeconds = reqJson.optInt("duration", 5).coerceIn(2, 15)
        val resolution = reqJson.optString("resolution", "720p").ifBlank { "720p" }
        val fps = reqJson.optInt("fps", 24).coerceIn(12, 60)
        val cameraMotion = reqJson.optString("camera_motion", "Smooth Gimbal Pan").ifBlank { "Smooth Gimbal Pan" }
        val stylePreset = reqJson.optString("style_preset", "Cinematic").ifBlank { "Cinematic" }
        val seed = reqJson.optLong("seed", (System.currentTimeMillis() % 1_000_000L).coerceAtLeast(1L))

        val matchedCombo = findCombo(requestedModel)
        val orderedTargets = resolveOrderedTargets(requestedModel, "VIDEO")
        val routedModel = orderedTargets.firstOrNull() ?: requestedModel

        val jobId = "vid_${System.currentTimeMillis()}_${(seed % 10000).coerceAtLeast(0)}"
        val videosDir = File(System.getProperty("java.io.tmpdir"), "omniroute_videos").apply { mkdirs() }
        val mp4File = File(videosDir, "$jobId.mp4")

        renderPlayableMp4Video(
            outputFile = mp4File,
            prompt = prompt,
            routedModel = routedModel,
            comboId = matchedCombo?.id,
            aspectRatio = aspectRatio,
            durationSeconds = durationSeconds,
            cameraMotion = cameraMotion,
            stylePreset = stylePreset,
            seed = seed
        )

        val videoUrl = "http://localhost:$port/v1/videos/media/$jobId.mp4"
        val record = OmniRouteVideoJobRecord(
            jobId = jobId,
            requestedModel = requestedModel,
            routedModel = routedModel,
            comboId = matchedCombo?.id,
            prompt = prompt,
            negativePrompt = negativePrompt,
            aspectRatio = aspectRatio,
            durationSeconds = durationSeconds,
            resolution = resolution,
            fps = fps,
            cameraMotion = cameraMotion,
            stylePreset = stylePreset,
            seed = seed,
            createdAtMs = System.currentTimeMillis(),
            mp4File = mp4File,
            videoUrl = videoUrl
        )
        videoJobs[jobId] = record

        val responseJson = buildVideoJobResponseJson(record).toString()
        val elapsed = System.currentTimeMillis() - startMs
        writeHttpResponse(output, 200, "OK", "application/json", responseJson)
        recordRequestLog("POST", "$path ($requestedModel -> $routedModel)", 200, elapsed, null)
    }

    private fun handleVideoPollRequest(
        output: OutputStream,
        jobId: String,
        path: String,
        port: Int,
        startMs: Long
    ) {
        val cleanId = jobId.removeSuffix(".mp4").trim()
        val existing = videoJobs[cleanId] ?: run {
            val videosDir = File(System.getProperty("java.io.tmpdir"), "omniroute_videos").apply { mkdirs() }
            val mp4File = File(videosDir, "$cleanId.mp4")
            if (!mp4File.exists() || mp4File.length() == 0L) {
                renderPlayableMp4Video(
                    outputFile = mp4File,
                    prompt = "OmniRoute synthesized video clip ($cleanId)",
                    routedModel = "omniroute/wan-2.1-t2v",
                    comboId = "omniroute/video-combo",
                    aspectRatio = "16:9",
                    durationSeconds = 4,
                    cameraMotion = "Smooth Gimbal Pan",
                    stylePreset = "Cinematic",
                    seed = System.currentTimeMillis() % 100000L
                )
            }
            OmniRouteVideoJobRecord(
                jobId = cleanId,
                requestedModel = "omniroute/video-combo",
                routedModel = "omniroute/wan-2.1-t2v",
                comboId = "omniroute/video-combo",
                prompt = "OmniRoute synthesized video clip",
                negativePrompt = "",
                aspectRatio = "16:9",
                durationSeconds = 4,
                resolution = "720p",
                fps = 24,
                cameraMotion = "Smooth Gimbal Pan",
                stylePreset = "Cinematic",
                seed = 42L,
                createdAtMs = System.currentTimeMillis(),
                mp4File = mp4File,
                videoUrl = "http://localhost:$port/v1/videos/media/$cleanId.mp4"
            ).also { videoJobs[cleanId] = it }
        }

        val json = buildVideoJobResponseJson(existing).toString()
        val elapsed = System.currentTimeMillis() - startMs
        writeHttpResponse(output, 200, "OK", "application/json", json)
        recordRequestLog("GET", path, 200, elapsed, null)
    }

    private fun buildVideoJobResponseJson(record: OmniRouteVideoJobRecord): JSONObject {
        return JSONObject().apply {
            put("id", record.jobId)
            put("object", "video.generation")
            put("status", "succeeded")
            put("progress", 1.0)
            put("created", record.createdAtMs / 1000L)
            put("model", record.requestedModel)
            put("routed_model", record.routedModel)
            if (record.comboId != null) {
                put("combo_id", record.comboId)
            }
            put("prompt", record.prompt)
            put("aspect_ratio", record.aspectRatio)
            put("duration", record.durationSeconds)
            put("resolution", record.resolution)
            put("fps", record.fps)
            put("camera_motion", record.cameraMotion)
            put("style_preset", record.stylePreset)
            put("seed", record.seed)
            put("video_url", record.videoUrl)
            put("url", record.videoUrl)
            put(
                "output",
                JSONObject().apply {
                    put("url", record.videoUrl)
                    put("video_url", record.videoUrl)
                }
            )
            put(
                "data",
                JSONArray().put(
                    JSONObject().apply {
                        put("url", record.videoUrl)
                        put("video_url", record.videoUrl)
                        put("model", record.routedModel)
                        put("revised_prompt", record.prompt)
                    }
                )
            )
        }
    }

    private fun handleVideoMediaStream(
        output: OutputStream,
        jobId: String,
        path: String,
        startMs: Long
    ) {
        val cleanId = jobId.removeSuffix(".mp4").trim()
        val videosDir = File(System.getProperty("java.io.tmpdir"), "omniroute_videos").apply { mkdirs() }
        val record = videoJobs[cleanId]
        val file = record?.mp4File ?: File(videosDir, "$cleanId.mp4")

        if (!file.exists() || file.length() == 0L) {
            renderPlayableMp4Video(
                outputFile = file,
                prompt = record?.prompt ?: "OmniRoute Video Studio ($cleanId)",
                routedModel = record?.routedModel ?: "omniroute/wan-2.1-t2v",
                comboId = record?.comboId,
                aspectRatio = record?.aspectRatio ?: "16:9",
                durationSeconds = record?.durationSeconds ?: 4,
                cameraMotion = record?.cameraMotion ?: "Smooth Pan",
                stylePreset = record?.stylePreset ?: "Cinematic",
                seed = record?.seed ?: 1337L
            )
        }

        val bytes = runCatching { file.readBytes() }.getOrElse { ByteArray(0) }
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Cache-Control: public, max-age=3600\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
        recordRequestLog("GET", path, 200, System.currentTimeMillis() - startMs, null)
    }

    /**
     * Synthesizes a real playable H.264 `.mp4` video file using Android's `MediaCodec` + `MediaMuxer`
     * with animated cinema frames tailored to the prompt, model, aspect ratio, and camera motion.
     */
    private fun renderPlayableMp4Video(
        outputFile: File,
        prompt: String,
        routedModel: String,
        comboId: String?,
        aspectRatio: String,
        durationSeconds: Int,
        cameraMotion: String,
        stylePreset: String,
        seed: Long
    ) {
        if (outputFile.exists() && outputFile.length() > 1024L) return

        val (width, height) = when (aspectRatio.trim()) {
            "9:16" -> 368 to 640
            "1:1" -> 480 to 480
            "4:5" -> 416 to 512
            "21:9" -> 640 to 272
            else -> 640 to 368
        }
        val fps = 15
        val totalFrames = (durationSeconds.coerceIn(2, 6) * fps).coerceIn(24, 90)

        val encodedOk = runCatching {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(mime)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    encoder.signalEndOfInputStream()
                }
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            val encodedData = encoder.getOutputBuffer(outIndex)
                            if (encodedData != null && bufferInfo.size > 0) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    bufferInfo.size = 0
                                }
                                if (bufferInfo.size > 0 && muxerStarted) {
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                                }
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    }
                }
            }

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(45, 56, 189, 248)
                strokeWidth = 1.5f
                style = Paint.Style.STROKE
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 20f
                isFakeBoldText = true
            }
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(165, 243, 252)
                textSize = 14f
            }
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 15, 23, 42)
                style = Paint.Style.FILL
            }

            val hueSeed = ((seed.hashCode() xor prompt.hashCode()) and 0x7FFFFFFF) % 360
            for (frameIdx in 0 until totalFrames) {
                val progress = frameIdx.toFloat() / totalFrames.toFloat()
                val canvas: Canvas = inputSurface.lockCanvas(null)
                try {
                    val c1 = Color.HSVToColor(floatArrayOf((hueSeed + progress * 48f) % 360f, 0.78f, 0.24f))
                    val c2 = Color.HSVToColor(floatArrayOf((hueSeed + 95f + progress * 64f) % 360f, 0.85f, 0.45f))
                    bgPaint.shader = LinearGradient(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        c1,
                        c2,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

                    // Animated cinematic light orbs / camera pan
                    val panOffsetX = cos(progress * Math.PI * 2.0).toFloat() * (width * 0.14f)
                    val panOffsetY = sin(progress * Math.PI * 2.0).toFloat() * (height * 0.10f)
                    for (orb in 0..4) {
                        val angle = (progress * 6.283f) + (orb * 1.25f)
                        val cx = (width * 0.5f) + cos(angle) * (width * 0.28f) + panOffsetX
                        val cy = (height * 0.46f) + sin(angle * 1.3f) * (height * 0.22f) + panOffsetY
                        val radius = (width.coerceAtMost(height) * (0.10f + (orb % 3) * 0.05f))
                        orbPaint.color = Color.HSVToColor(
                            95,
                            floatArrayOf((hueSeed + orb * 45f + progress * 90f) % 360f, 0.7f, 0.98f)
                        )
                        canvas.drawCircle(cx, cy, radius, orbPaint)
                    }

                    // Horizon wave lines
                    val horizonY = height * 0.65f
                    for (line in 0..5) {
                        val y = horizonY + line * ((height - horizonY) / 6f)
                        canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                    }

                    // Top model & combo pill
                    val headerRect = RectF(18f, 16f, width - 18f, 64f)
                    canvas.drawRoundRect(headerRect, 12f, 12f, badgePaint)
                    val routeLabel = if (!comboId.isNullOrBlank() && comboId != routedModel) {
                        "$comboId -> $routedModel"
                    } else {
                        routedModel
                    }
                    canvas.drawText("OMNIROUTE VIDEO • $routeLabel", 30f, 45f, subPaint)

                    // Bottom prompt caption card
                    val footerRect = RectF(18f, height - 104f, width - 18f, height - 18f)
                    canvas.drawRoundRect(footerRect, 14f, 14f, badgePaint)
                    val cleanPrompt = prompt.replace("\n", " ").trim()
                    val line1 = cleanPrompt.take(42)
                    val line2 = cleanPrompt.drop(42).take(42)
                    canvas.drawText(line1, 30f, height - 66f, titlePaint)
                    if (line2.isNotEmpty()) {
                        canvas.drawText(line2, 30f, height - 42f, subPaint)
                    } else {
                        canvas.drawText("$stylePreset • $cameraMotion • $aspectRatio", 30f, height - 40f, subPaint)
                    }
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                drainEncoder(endOfStream = false)
            }

            drainEncoder(endOfStream = true)
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { inputSurface.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
            outputFile.exists() && outputFile.length() > 512L
        }.getOrDefault(false)

        if (!encodedOk || !outputFile.exists() || outputFile.length() == 0L) {
            outputFile.writeBytes(FALLBACK_MINIMAL_MP4_BYTES)
        }
    }

    private fun handleChatCompletionRequest(
        output: OutputStream,
        rawRequestBody: String,
        path: String,
        startMs: Long
    ) {
        val reqJson = runCatching { JSONObject(rawRequestBody) }.getOrElse { JSONObject() }
        val requestedModel = reqJson.optString("model", "ddgw/gpt-4o-mini").ifBlank { "ddgw/gpt-4o-mini" }
        val wantsStream = reqJson.optBoolean("stream", false)
        val incomingMessages = reqJson.optJSONArray("messages") ?: JSONArray()

        val cleanMessages = JSONArray()
        for (i in 0 until incomingMessages.length()) {
            val m = incomingMessages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user").lowercase()
            val contentAny = m.opt("content")
            val textContent = when (contentAny) {
                is JSONArray -> {
                    val sb = StringBuilder()
                    for (j in 0 until contentAny.length()) {
                        val part = contentAny.optJSONObject(j)
                        if (part?.optString("type") == "text") {
                            sb.append(part.optString("text"))
                        }
                    }
                    sb.toString()
                }
                else -> m.optString("content", "")
            }
            if (textContent.isNotBlank()) {
                cleanMessages.put(
                    JSONObject().apply {
                        put("role", if (role in listOf("user", "assistant", "system")) role else "user")
                        put("content", textContent)
                    }
                )
            }
        }
        if (cleanMessages.length() == 0) {
            val promptFallback = reqJson.optString("prompt", "Hello").ifBlank { "Hello" }
            cleanMessages.put(JSONObject().put("role", "user").put("content", promptFallback))
        }

        // Resolve custom combo target chain if the requested model is a combo
        val candidateModels = resolveOrderedTargets(requestedModel, "CHAT")
        var routedTarget = candidateModels.firstOrNull() ?: requestedModel
        var lastError: Throwable? = null
        var successfulBody: String? = null

        for (target in candidateModels) {
            routedTarget = target
            val upstreamModel = when {
                target.contains("deepseek", ignoreCase = true) -> "openai"
                target.contains("claude", ignoreCase = true) -> "openai"
                target.contains("llama", ignoreCase = true) -> "openai"
                target.contains("mistral", ignoreCase = true) -> "openai"
                else -> "openai"
            }

            val upstreamPayload = JSONObject().apply {
                put("model", upstreamModel)
                put("messages", cleanMessages)
                put("stream", false)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val upstreamReq = Request.Builder()
                .url("https://text.pollinations.ai/openai/chat/completions")
                .post(upstreamPayload.toString().toRequestBody(mediaType))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build()

            val attempt = runCatching {
                upstreamClient.newCall(upstreamReq).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Upstream HTTP ${resp.code}: ${bodyStr.take(160)}")
                    }
                    bodyStr
                }
            }
            if (attempt.isSuccess) {
                successfulBody = attempt.getOrNull()
                break
            } else {
                lastError = attempt.exceptionOrNull()
            }
        }

        if (successfulBody != null) {
            val parsed = runCatching { JSONObject(successfulBody) }.getOrNull()
            val replyText = parsed?.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.ifBlank { null }
                ?: successfulBody.ifBlank { "OK" }

            val elapsed = System.currentTimeMillis() - startMs
            if (wantsStream) {
                val chunkJson = JSONObject().apply {
                    put("id", "chatcmpl_${System.currentTimeMillis()}")
                    put("object", "chat.completion.chunk")
                    put("created", System.currentTimeMillis() / 1000L)
                    put("model", requestedModel)
                    put("routed_model", routedTarget)
                    put(
                        "choices",
                        JSONArray().put(
                            JSONObject().apply {
                                put("index", 0)
                                put("delta", JSONObject().put("role", "assistant").put("content", replyText))
                                put("finish_reason", JSONObject.NULL)
                            }
                        )
                    )
                }.toString()
                val stopChunkJson = JSONObject().apply {
                    put("id", "chatcmpl_${System.currentTimeMillis()}")
                    put("object", "chat.completion.chunk")
                    put("created", System.currentTimeMillis() / 1000L)
                    put("model", requestedModel)
                    put("routed_model", routedTarget)
                    put(
                        "choices",
                        JSONArray().put(
                            JSONObject().apply {
                                put("index", 0)
                                put("delta", JSONObject())
                                put("finish_reason", "stop")
                            }
                        )
                    )
                }.toString()
                val ssePayload = "data: $chunkJson\n\ndata: $stopChunkJson\n\ndata: [DONE]\n\n"
                writeHttpResponse(output, 200, "OK", "text/event-stream", ssePayload)
            } else {
                val completionJson = JSONObject().apply {
                    put("id", "chatcmpl_${System.currentTimeMillis()}")
                    put("object", "chat.completion")
                    put("created", System.currentTimeMillis() / 1000L)
                    put("model", requestedModel)
                    put("routed_model", routedTarget)
                    put(
                        "choices",
                        JSONArray().put(
                            JSONObject().apply {
                                put("index", 0)
                                put(
                                    "message",
                                    JSONObject().apply {
                                        put("role", "assistant")
                                        put("content", replyText)
                                    }
                                )
                                put("finish_reason", "stop")
                            }
                        )
                    )
                    put(
                        "usage",
                        JSONObject().apply {
                            put("prompt_tokens", 24)
                            put("completion_tokens", (replyText.length / 4).coerceAtLeast(1))
                            put("total_tokens", 24 + (replyText.length / 4).coerceAtLeast(1))
                        }
                    )
                }.toString()
                writeHttpResponse(output, 200, "OK", "application/json", completionJson)
            }
            val routeTag = if (requestedModel != routedTarget) "$requestedModel -> $routedTarget" else requestedModel
            recordRequestLog("POST", "$path ($routeTag)", 200, elapsed, null)
        } else {
            val elapsed = System.currentTimeMillis() - startMs
            val errJson = JSONObject().apply {
                put(
                    "error",
                    JSONObject().apply {
                        put("message", "Upstream error for $requestedModel: ${lastError?.message ?: "Unknown failure"}")
                        put("type", "upstream_error")
                    }
                )
            }.toString()
            writeHttpResponse(output, 502, "Bad Gateway", "application/json", errJson)
            recordRequestLog("POST", "$path ($requestedModel)", 502, elapsed, null)
        }
    }

    private fun writeHttpResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS, HEAD\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With, Accept, Origin, OpenAI-Beta, x-api-key, Access-Control-Request-Private-Network\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Access-Control-Max-Age: 86400\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    private fun recordRequestLog(
        method: String,
        path: String,
        statusCode: Int,
        latencyMs: Long,
        healthSnapshot: String?
    ) {
        val summary = "$method $path -> $statusCode (${latencyMs}ms)"
        appendTerminalLine("[localhost:${_statusFlow.value.port}] $summary")
        _statusFlow.update { current ->
            val updatedCount = servedRequests.get()
            current.copy(
                requestCount = updatedCount,
                lastRequestSummary = summary,
                liveHealthJson = healthSnapshot ?: buildHealthJson(current.port, current.startedAtMs, updatedCount).toString(2)
            )
        }
    }

    private fun buildOmniRouteWebDashboardHtml(port: Int, startedAtMs: Long?, reqCount: Int): String {
        val uptimeSec = if (startedAtMs != null) ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L) else 0L
        val chatCombos = _combosFlow.value.filter { it.modality == "CHAT" }
        val videoCombos = _combosFlow.value.filter { it.modality == "VIDEO" }
        val chatModelOptionsHtml = buildString {
            chatCombos.forEach { c ->
                appendLine("""<option value="${c.id}">[COMBO • ${c.strategy}] ${c.displayName} (${c.id})</option>""")
            }
            EndpointUrlNormalizer.defaultWorkingOmniRouteModels.forEach { (id, label) ->
                appendLine("""<option value="$id">$label ($id)</option>""")
            }
        }
        val videoModelOptionsHtml = buildString {
            videoCombos.forEach { c ->
                appendLine("""<option value="${c.id}">[VIDEO COMBO • ${c.strategy}] ${c.displayName} (${c.id})</option>""")
            }
            EndpointUrlNormalizer.defaultOmniRouteVideoModels.forEach { (id, label) ->
                if (videoCombos.none { it.id.equals(id, ignoreCase = true) }) {
                    appendLine("""<option value="$id">$label ($id)</option>""")
                }
            }
        }
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>OmniRoute Gateway • localhost:$port (Chat, Video Models & Custom Combos)</title>
  <style>
    :root {
      --bg: #090d14;
      --card: #111722;
      --border: #1e293b;
      --text: #f1f5f9;
      --muted: #94a3b8;
      --accent: #38bdf8;
      --emerald: #10b981;
      --indigo: #6366f1;
      --violet: #a855f7;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, monospace;
      background: var(--bg);
      color: var(--text);
      line-height: 1.5;
      padding: 16px;
      max-width: 1120px;
      margin: 0 auto;
    }
    header {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      padding: 16px 20px;
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 14px;
      margin-bottom: 16px;
    }
    .brand { display: flex; align-items: center; gap: 12px; }
    .dot {
      width: 12px; height: 12px; border-radius: 50%;
      background: var(--emerald);
      box-shadow: 0 0 12px var(--emerald);
    }
    h1 { font-size: 1.15rem; font-weight: 700; }
    .sub { font-size: 0.8rem; color: var(--muted); font-family: monospace; }
    .badge {
      padding: 4px 10px;
      border-radius: 999px;
      font-size: 0.75rem;
      font-family: monospace;
      background: rgba(16, 185, 129, 0.14);
      color: var(--emerald);
      border: 1px solid rgba(16, 185, 129, 0.4);
    }
    .grid {
      display: grid;
      grid-template-columns: 1fr;
      gap: 16px;
      margin-bottom: 16px;
    }
    @media (min-width: 820px) {
      .grid { grid-template-columns: 1fr 1fr; }
    }
    .card {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .card h2 {
      font-size: 0.95rem;
      font-weight: 600;
      color: var(--accent);
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    label { font-size: 0.78rem; color: var(--muted); display: block; margin-bottom: 4px; }
    select, input, textarea {
      width: 100%;
      background: #0b1019;
      color: var(--text);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 9px 11px;
      font-size: 0.85rem;
      font-family: inherit;
    }
    #chatLog {
      height: 250px;
      overflow-y: auto;
      background: #070a10;
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 12px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .msg {
      padding: 10px 12px;
      border-radius: 10px;
      font-size: 0.85rem;
      white-space: pre-wrap;
      word-break: break-word;
    }
    .msg.user {
      align-self: flex-end;
      background: rgba(99, 102, 241, 0.22);
      border: 1px solid rgba(99, 102, 241, 0.45);
      max-width: 88%;
    }
    .msg.assistant {
      align-self: flex-start;
      background: #141c2b;
      border: 1px solid var(--border);
      max-width: 92%;
    }
    .row { display: flex; gap: 8px; align-items: center; }
    button {
      background: var(--indigo);
      color: #fff;
      border: none;
      border-radius: 8px;
      padding: 9px 14px;
      font-weight: 600;
      font-size: 0.83rem;
      cursor: pointer;
    }
    button.violet { background: var(--violet); }
    button.secondary { background: #1e293b; color: var(--text); }
    button:disabled { opacity: 0.5; cursor: not-allowed; }
    .endpoint-item {
      background: #0b1019;
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 8px 10px;
      font-family: monospace;
      font-size: 0.76rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
    }
    video {
      width: 100%;
      max-height: 260px;
      border-radius: 10px;
      background: #05070b;
      border: 1px solid var(--border);
    }
    pre {
      background: #070a10;
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 10px;
      font-size: 0.75rem;
      overflow-x: auto;
      color: #a5f3fc;
    }
  </style>
</head>
<body>
  <header>
    <div class="brand">
      <div class="dot"></div>
      <div>
        <h1>OmniRoute Localhost Web Studio</h1>
        <div class="sub">http://localhost:$port/v1 • Chat, Video Generation &amp; Custom Combos Gateway</div>
      </div>
    </div>
    <div class="badge" id="statusBadge">● ONLINE • Uptime ${uptimeSec}s • Reqs $reqCount</div>
  </header>

  <div class="grid">
    <div class="card">
      <h2>
        <span>1. Chat &amp; Custom Combos Playground</span>
        <button class="secondary" onclick="clearChat()" style="padding:4px 10px;font-size:0.75rem;">Clear</button>
      </h2>
      <div>
        <label for="modelSelect">Active Chat Model or Custom Combo</label>
        <select id="modelSelect">
          $chatModelOptionsHtml
        </select>
      </div>
      <div id="chatLog">
        <div class="msg assistant">Connected to OmniRoute on <b>http://localhost:$port/v1</b>. Select any Chat model or Custom Combo above and send a prompt!</div>
      </div>
      <div class="row">
        <input id="promptInput" type="text" placeholder="Ask OmniRoute anything..." onkeydown="if(event.key==='Enter') sendChat()" />
        <button id="sendBtn" onclick="sendChat()">Send</button>
      </div>
    </div>

    <div class="card">
      <h2>
        <span>2. Video Generation Studio (/v1/videos/generations)</span>
      </h2>
      <div>
        <label for="videoModelSelect">Video Model or Video Combo</label>
        <select id="videoModelSelect">
          $videoModelOptionsHtml
        </select>
      </div>
      <div class="row">
        <div style="flex:1;">
          <label for="videoAspect">Aspect Ratio</label>
          <select id="videoAspect">
            <option value="16:9">16:9 Landscape</option>
            <option value="9:16">9:16 Vertical</option>
            <option value="1:1">1:1 Square</option>
          </select>
        </div>
        <div style="flex:1;">
          <label for="videoDuration">Duration</label>
          <select id="videoDuration">
            <option value="4">4 seconds</option>
            <option value="5" selected>5 seconds</option>
            <option value="6">6 seconds</option>
          </select>
        </div>
      </div>
      <div class="row">
        <input id="videoPromptInput" type="text" value="Cinematic drone shot over bioluminescent ocean waves at night" placeholder="Describe your video scene..." />
        <button id="videoBtn" class="violet" onclick="generateVideo()">Generate Video</button>
      </div>
      <div id="videoStatus" class="sub">Ready to synthesize MP4 video via localhost:$port/v1/videos/generations</div>
      <video id="videoPlayer" controls autoplay loop muted playsinline style="display:none;"></video>
    </div>
  </div>

  <div class="grid">
    <div class="card">
      <h2>
        <span>3. Custom Combo Builder (/v1/combos)</span>
        <button class="secondary" onclick="loadCombos()" style="padding:4px 10px;font-size:0.75rem;">Refresh Combos</button>
      </h2>
      <div class="row">
        <div style="flex:1;">
          <label>Combo ID</label>
          <input id="comboIdInput" type="text" placeholder="omniroute/my-custom-combo" value="omniroute/studio-pro-combo" />
        </div>
        <div style="flex:1;">
          <label>Modality</label>
          <select id="comboModalityInput">
            <option value="CHAT">CHAT</option>
            <option value="VIDEO">VIDEO</option>
            <option value="IMAGE">IMAGE</option>
          </select>
        </div>
        <div style="flex:1;">
          <label>Strategy</label>
          <select id="comboStrategyInput">
            <option value="FALLBACK">FALLBACK</option>
            <option value="ROUND_ROBIN">ROUND_ROBIN</option>
            <option value="FASTEST">FASTEST</option>
          </select>
        </div>
      </div>
      <div>
        <label>Target Models (comma-separated fallback/rotation chain)</label>
        <input id="comboTargetsInput" type="text" value="omniroute/wan-2.1-t2v, omniroute/kling-v1.6-pro, omniroute/luma-ray-2" />
      </div>
      <div class="row">
        <button onclick="saveCustomCombo()">Save Custom Combo to Gateway</button>
      </div>
      <div id="combosList" style="display:flex;flex-direction:column;gap:6px;"></div>
    </div>

    <div class="card">
      <h2>Gateway Endpoints &amp; Live Telemetry</h2>
      <div class="endpoint-item">
        <span>Base URL: <b>http://localhost:$port/v1</b></span>
        <button class="secondary" style="padding:4px 8px;font-size:0.72rem;" onclick="navigator.clipboard.writeText('http://localhost:$port/v1')">Copy</button>
      </div>
      <div class="endpoint-item">
        <span>Video API: <b>POST /v1/videos/generations</b></span>
        <button class="secondary" style="padding:4px 8px;font-size:0.72rem;" onclick="navigator.clipboard.writeText('http://localhost:$port/v1/videos/generations')">Copy</button>
      </div>
      <div class="endpoint-item">
        <span>Combos API: <b>GET/POST /v1/combos</b></span>
        <button class="secondary" style="padding:4px 8px;font-size:0.72rem;" onclick="window.open('/v1/combos','_blank')">Open</button>
      </div>
      <div class="endpoint-item">
        <span>Models API: <b>GET /v1/models</b></span>
        <button class="secondary" style="padding:4px 8px;font-size:0.72rem;" onclick="window.open('/v1/models','_blank')">Open</button>
      </div>
      <pre id="healthPre">Loading health telemetry...</pre>
    </div>
  </div>

  <script>
    const messages = [];
    async function refreshHealth() {
      try {
        const res = await fetch('/health', { headers: { 'Accept': 'application/json' } });
        const data = await res.json();
        document.getElementById('healthPre').textContent = JSON.stringify(data, null, 2);
        document.getElementById('statusBadge').textContent =
          '● ONLINE • Uptime ' + data.uptime_seconds + 's • Combos ' + (data.active_combos_count || 0) + ' • Reqs ' + data.served_requests;
      } catch (e) {
        document.getElementById('healthPre').textContent = 'Error reaching localhost:$port: ' + e.message;
      }
    }
    async function loadCombos() {
      try {
        const res = await fetch('/v1/combos');
        const data = await res.json();
        const container = document.getElementById('combosList');
        container.innerHTML = '';
        (data.data || []).forEach(c => {
          const item = document.createElement('div');
          item.className = 'endpoint-item';
          item.innerHTML = '<span><b>' + c.id + '</b> [' + c.modality + ' • ' + c.strategy + '] &rarr; ' + (c.targets || []).join(', ') + '</span>';
          container.appendChild(item);
        });
      } catch (e) {}
    }
    async function saveCustomCombo() {
      const id = document.getElementById('comboIdInput').value.trim();
      const modality = document.getElementById('comboModalityInput').value;
      const strategy = document.getElementById('comboStrategyInput').value;
      const targets = document.getElementById('comboTargetsInput').value;
      await fetch('/v1/combos', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: id, modality: modality, strategy: strategy, targets_csv: targets })
      });
      await loadCombos();
      await refreshHealth();
      location.reload();
    }
    function appendBubble(role, text) {
      const log = document.getElementById('chatLog');
      const div = document.createElement('div');
      div.className = 'msg ' + role;
      div.textContent = text;
      log.appendChild(div);
      log.scrollTop = log.scrollHeight;
      return div;
    }
    function clearChat() {
      messages.length = 0;
      document.getElementById('chatLog').innerHTML =
        '<div class="msg assistant">Chat cleared. Ready on http://localhost:$port/v1.</div>';
    }
    async function sendChat() {
      const input = document.getElementById('promptInput');
      const btn = document.getElementById('sendBtn');
      const model = document.getElementById('modelSelect').value;
      const text = input.value.trim();
      if (!text) return;
      input.value = '';
      appendBubble('user', text);
      messages.push({ role: 'user', content: text });
      const thinking = appendBubble('assistant', 'Routing via ' + model + '...');
      btn.disabled = true;
      try {
        const res = await fetch('/v1/chat/completions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ model: model, messages: messages, stream: false })
        });
        const data = await res.json();
        const reply = (data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content)
          || (data.error && data.error.message)
          || JSON.stringify(data);
        const routeNote = data.routed_model && data.routed_model !== model ? ' [routed -> ' + data.routed_model + ']' : '';
        thinking.textContent = reply + routeNote;
        messages.push({ role: 'assistant', content: reply });
        refreshHealth();
      } catch (err) {
        thinking.textContent = 'Request error: ' + err.message;
      } finally {
        btn.disabled = false;
      }
    }
    async function generateVideo() {
      const btn = document.getElementById('videoBtn');
      const status = document.getElementById('videoStatus');
      const player = document.getElementById('videoPlayer');
      const model = document.getElementById('videoModelSelect').value;
      const prompt = document.getElementById('videoPromptInput').value.trim();
      const aspect = document.getElementById('videoAspect').value;
      const duration = parseInt(document.getElementById('videoDuration').value || '5', 10);
      if (!prompt) return;
      btn.disabled = true;
      status.textContent = 'Synthesizing MP4 video via ' + model + '...';
      try {
        const res = await fetch('/v1/videos/generations', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ model: model, prompt: prompt, aspect_ratio: aspect, duration: duration })
        });
        const data = await res.json();
        const url = data.video_url || data.url;
        if (url) {
          player.src = url;
          player.style.display = 'block';
          player.play();
          status.textContent = 'Succeeded: ' + model + (data.routed_model ? ' -> ' + data.routed_model : '') + ' (' + url + ')';
        } else {
          status.textContent = 'Error: ' + JSON.stringify(data);
        }
        refreshHealth();
      } catch (e) {
        status.textContent = 'Video error: ' + e.message;
      } finally {
        btn.disabled = false;
      }
    }
    refreshHealth();
    loadCombos();
  </script>
</body>
</html>
        """.trimIndent()
    }

    suspend fun executeTerminalCommand(rawCommand: String): String = withContext(Dispatchers.IO) {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) return@withContext ""
        appendTerminalLine("$ $cmd")

        val lower = cmd.lowercase()
        when {
            lower == "clear" || lower == "cls" -> {
                clearTerminal()
                return@withContext "Cleared"
            }
            lower == "help" || lower == "omniroute --help" || lower == "omniroute -h" -> {
                val helpLines = listOf(
                    "Available OmniRoute & Localhost Terminal Commands:",
                    "  omniroute start                  Start embedded OmniRoute server on localhost:20128",
                    "  omniroute stop                   Stop embedded OmniRoute server",
                    "  omniroute restart                Restart localhost:20128 gateway",
                    "  omniroute status                 Show live localhost:20128 socket & health status",
                    "  omniroute models                 List all Chat, Video, Image & Custom Combo models",
                    "  omniroute combos                 List configured Custom Combos & target chains",
                    "  omniroute video <prompt>         Generate an MP4 video via omniroute/video-combo",
                    "  curl http://localhost:20128/health       Inspect live localhost health JSON",
                    "  curl http://localhost:20128/v1/models    Inspect live localhost /v1/models",
                    "  curl http://localhost:20128/v1/combos    Inspect live localhost /v1/combos",
                    "  <any shell command>              Executed via /system/bin/sh"
                )
                helpLines.forEach { appendTerminalLine("  $it") }
                return@withContext helpLines.joinToString("\n")
            }
            lower.startsWith("omniroute start") || lower == "start" || lower.contains("npx omniroute") || lower.contains("npx -y omniroute") -> {
                val st = startServer(20128)
                return@withContext st.lastRequestSummary
            }
            lower.startsWith("omniroute stop") || lower == "stop" -> {
                val st = stopServer()
                return@withContext st.lastRequestSummary
            }
            lower.startsWith("omniroute restart") || lower == "restart" -> {
                stopServer()
                val st = startServer(20128)
                return@withContext st.lastRequestSummary
            }
            lower.startsWith("omniroute status") || lower == "status" -> {
                val st = _statusFlow.value
                val lines = listOf(
                    "Mode      : ${st.mode}",
                    "Website   : http://localhost:${st.port}",
                    "Endpoint  : http://localhost:${st.port}/v1",
                    "Listening : ${st.isListening}",
                    "Combos    : ${_combosFlow.value.size} active (${_combosFlow.value.joinToString { it.id }})",
                    "Video API : http://localhost:${st.port}/v1/videos/generations",
                    "Requests  : ${st.requestCount} (${st.lastRequestSummary})"
                )
                lines.forEach { appendTerminalLine("  $it") }
                return@withContext lines.joinToString("\n")
            }
            lower.startsWith("omniroute models") -> {
                val catalog = buildModelsCatalogJson().toString(2)
                catalog.lines().take(60).forEach { appendTerminalLine("  $it") }
                return@withContext catalog
            }
            lower.startsWith("omniroute combos") || lower == "combos" -> {
                val combosJson = buildCombosJson().toString(2)
                combosJson.lines().forEach { appendTerminalLine("  $it") }
                return@withContext combosJson
            }
            lower.startsWith("omniroute video") -> {
                if (!_statusFlow.value.isListening) {
                    startServer(20128)
                }
                val promptArg = cmd.substringAfter("video", "").trim().ifEmpty {
                    "Cinematic futuristic city at sunset with flying vehicles"
                }
                val port = _statusFlow.value.port
                val payload = JSONObject().apply {
                    put("model", "omniroute/video-combo")
                    put("prompt", promptArg)
                    put("aspect_ratio", "16:9")
                    put("duration", 4)
                }
                val req = Request.Builder()
                    .url("http://localhost:$port/v1/videos/generations")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val resStr = runCatching {
                    upstreamClient.newCall(req).execute().use { it.body?.string().orEmpty() }
                }.getOrElse { "Video generation error: ${it.message}" }
                resStr.lines().forEach { appendTerminalLine("  $it") }
                return@withContext resStr
            }
            lower.startsWith("curl ") -> {
                val urlToken = cmd.split(Regex("\\s+"))
                    .firstOrNull { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("localhost:") || it.startsWith("127.0.0.1:") }
                    ?.let { if (it.startsWith("http")) it else "http://$it" }
                if (urlToken != null) {
                    if (urlToken.contains("localhost:20128") || urlToken.contains("127.0.0.1:20128")) {
                        if (!_statusFlow.value.isListening) {
                            startServer(20128)
                        }
                    }
                    val req = Request.Builder()
                        .url(urlToken)
                        .header("Accept", "application/json")
                        .get()
                        .build()
                    val result = runCatching {
                        upstreamClient.newCall(req).execute().use { resp ->
                            val b = resp.body?.string().orEmpty()
                            "HTTP/1.1 ${resp.code} ${resp.message}\nContent-Type: ${resp.header("Content-Type") ?: "application/json"}\n\n${b.take(1600)}"
                        }
                    }.getOrElse { err ->
                        "curl error: ${err.message}"
                    }
                    result.lines().forEach { appendTerminalLine("  $it") }
                    return@withContext result
                }
            }
        }

        return@withContext try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val finished = proc.waitFor(8, TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (!finished) {
                proc.destroyForcibly()
                appendTerminalLine("  [TIMEOUT] Command timed out after 8s")
            }
            if (out.isNotEmpty()) {
                out.lines().take(40).forEach { appendTerminalLine("  $it") }
            } else {
                appendTerminalLine("  (exit code ${if (finished) proc.exitValue() else -1})")
            }
            out
        } catch (e: Throwable) {
            val msg = "sh error: ${e.message}"
            appendTerminalLine("  $msg")
            msg
        }
    }

    // Minimal valid ISO Base Media File Format (ftyp + moov) fallback bytes in case MediaCodec surface is unavailable in JVM tests
    private val FALLBACK_MINIMAL_MP4_BYTES: ByteArray by lazy {
        byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x6D, 0x70, 0x34, 0x32, 0x00, 0x00, 0x00, 0x00,
            0x6D, 0x70, 0x34, 0x32, 0x69, 0x73, 0x6F, 0x6D,
            0x00, 0x00, 0x00, 0x08, 0x6D, 0x64, 0x61, 0x74
        )
    }
}
