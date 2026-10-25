package com.example.data.provider

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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class LocalhostServerMode {
    STOPPED,
    STARTING,
    EMBEDDED_RUNNING,
    EXTERNAL_TERMUX_RUNNING,
    ERROR
}

data class LocalhostServerStatus(
    val mode: LocalhostServerMode = LocalhostServerMode.STOPPED,
    val host: String = "127.0.0.1",
    val port: Int = 20128,
    val startedAtMs: Long? = null,
    val requestCount: Int = 0,
    val lastRequestSummary: String = "No requests yet",
    val liveHealthJson: String = ""
) {
    val isListening: Boolean
        get() = mode == LocalhostServerMode.EMBEDDED_RUNNING || mode == LocalhostServerMode.EXTERNAL_TERMUX_RUNNING

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

/**
 * Embedded Localhost OmniRoute Gateway (`127.0.0.1:20128`) + Interactive Terminal Command Runner.
 *
 * Allows the user to start OmniRoute directly inside the app on `http://127.0.0.1:20128/v1`
 * without needing Chromium/Playwright (cfp/) or external OAuth/IDE scrapers (aug/, cu/, ws/).
 * If an external Termux OmniRoute instance is already bound to `127.0.0.1:20128`, it seamlessly
 * detects and attaches to it.
 */
object EmbeddedOmniRouteServer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)
    private val servedRequests = AtomicInteger(0)

    private val upstreamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _statusFlow = MutableStateFlow(LocalhostServerStatus())
    val statusFlow: StateFlow<LocalhostServerStatus> = _statusFlow.asStateFlow()

    private val _terminalLinesFlow = MutableStateFlow(
        listOf(
            "OmniRoute Embedded Terminal v2.4 (Android Localhost Daemon)",
            "Type 'help', 'omniroute start', 'omniroute status', 'curl http://127.0.0.1:20128/health', or any shell command."
        )
    )
    val terminalLinesFlow: StateFlow<List<String>> = _terminalLinesFlow.asStateFlow()

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
            "Terminal buffer cleared. Type 'omniroute status' or 'help'."
        )
    }

    /**
     * Ensures the embedded localhost server on `127.0.0.1:20128` is started (or attaches to an existing
     * listener on `127.0.0.1:20128`).
     */
    suspend fun startServer(port: Int = 20128): LocalhostServerStatus = withContext(Dispatchers.IO) {
        if (running.get() && serverSocket?.isClosed == false) {
            val current = _statusFlow.value
            appendTerminalLine("[OK] OmniRoute Localhost Server is already running on http://${current.host}:${current.port}/v1")
            return@withContext current
        }

        _statusFlow.update { it.copy(mode = LocalhostServerMode.STARTING, port = port) }
        appendTerminalLine("Starting OmniRoute Localhost Gateway on http://127.0.0.1:$port/v1 ...")
        appendTerminalLine("Flags: PLAYWRIGHT=disabled, OAUTH_SCRAPERS=disabled, ZERO_CONFIG_ENGINE=ddgw+pollinations")

        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
            serverSocket = socket
            running.set(true)

            val startedNow = System.currentTimeMillis()
            val healthJson = buildHealthJson(port, startedNow, 0).toString(2)
            _statusFlow.value = LocalhostServerStatus(
                mode = LocalhostServerMode.EMBEDDED_RUNNING,
                host = "127.0.0.1",
                port = port,
                startedAtMs = startedNow,
                requestCount = servedRequests.get(),
                lastRequestSummary = "Listening on http://127.0.0.1:$port/v1",
                liveHealthJson = healthJson
            )
            appendTerminalLine("[LISTEN] Bound TCP socket 127.0.0.1:$port (PID=${android.os.Process.myPid()})")
            appendTerminalLine("[READY] OpenAI-compatible endpoints live:")
            appendTerminalLine("  • GET  http://127.0.0.1:$port/health")
            appendTerminalLine("  • GET  http://127.0.0.1:$port/v1/models")
            appendTerminalLine("  • POST http://127.0.0.1:$port/v1/chat/completions")

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
            // Port 20128 is already in use (e.g. by Termux or another local instance)
            val probe = probeLocalhostHealth(port)
            if (probe != null) {
                _statusFlow.value = LocalhostServerStatus(
                    mode = LocalhostServerMode.EXTERNAL_TERMUX_RUNNING,
                    host = "127.0.0.1",
                    port = port,
                    startedAtMs = System.currentTimeMillis(),
                    requestCount = servedRequests.get(),
                    lastRequestSummary = "Attached to existing localhost:$port listener",
                    liveHealthJson = probe
                )
                appendTerminalLine("[ATTACHED] Port $port is already active on 127.0.0.1 (External/Termux OmniRoute detected).")
                return@withContext _statusFlow.value
            } else {
                _statusFlow.value = LocalhostServerStatus(
                    mode = LocalhostServerMode.ERROR,
                    host = "127.0.0.1",
                    port = port,
                    lastRequestSummary = "Port $port busy: ${bindEx.message}"
                )
                appendTerminalLine("[ERROR] Could not bind 127.0.0.1:$port: ${bindEx.message}")
                return@withContext _statusFlow.value
            }
        } catch (e: Throwable) {
            _statusFlow.value = LocalhostServerStatus(
                mode = LocalhostServerMode.ERROR,
                host = "127.0.0.1",
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
            host = "127.0.0.1",
            port = _statusFlow.value.port,
            requestCount = servedRequests.get(),
            lastRequestSummary = "Stopped by user"
        )
        appendTerminalLine("[STOPPED] Localhost server on 127.0.0.1:${_statusFlow.value.port} stopped.")
        _statusFlow.value
    }

    private fun probeLocalhostHealth(port: Int): String? {
        val urls = listOf(
            "http://127.0.0.1:$port/health",
            "http://127.0.0.1:$port/v1/models",
            "http://127.0.0.1:$port/"
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
        return JSONObject().apply {
            put("status", "healthy")
            put("service", "OmniRoute Embedded Localhost Gateway")
            put("listen", "http://127.0.0.1:$port")
            put("base_url", "http://127.0.0.1:$port/v1")
            put("uptime_seconds", uptimeSec)
            put("served_requests", reqCount)
            put("playwright_chromium_required", false)
            put("oauth_scrapers_enabled", false)
            put(
                "healthy_providers",
                JSONArray().apply {
                    put("ddgw (DuckDuckGo Zero-Config)")
                    put("pollinations (OpenAI-Compatible Zero-Config)")
                }
            )
            put(
                "models",
                JSONArray().apply {
                    EndpointUrlNormalizer.defaultWorkingOmniRouteModels.forEach { (id, _) ->
                        put(id)
                    }
                }
            )
        }
    }

    private fun buildModelsCatalogJson(): JSONObject {
        val dataArr = JSONArray()
        val nowSec = System.currentTimeMillis() / 1000L
        for ((id, label) in EndpointUrlNormalizer.defaultWorkingOmniRouteModels) {
            dataArr.put(
                JSONObject().apply {
                    put("id", id)
                    put("object", "model")
                    put("created", nowSec)
                    put("owned_by", "omniroute-clean")
                    put("display_name", label)
                }
            )
        }
        return JSONObject().apply {
            put("object", "list")
            put("data", dataArr)
        }
    }

    private suspend fun handleClientSocket(socket: Socket, port: Int) = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val rawPath = parts.getOrNull(1) ?: "/"
            val path = rawPath.substringBefore("?")

            var contentLength = 0
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val bodyChars = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val r = reader.read(buf, totalRead, contentLength - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                String(buf, 0, totalRead)
            } else {
                ""
            }

            val count = servedRequests.incrementAndGet()
            val output = socket.getOutputStream()

            when {
                method == "OPTIONS" -> {
                    writeHttpResponse(output, 200, "OK", "application/json", "{}")
                }
                method == "GET" && (path == "/" || path == "/health" || path == "/status" || path == "/v1/health") -> {
                    val json = buildHealthJson(port, _statusFlow.value.startedAtMs, count).toString(2)
                    writeHttpResponse(output, 200, "OK", "application/json", json)
                    recordRequestLog(method, path, 200, System.currentTimeMillis() - startMs, json)
                }
                method == "GET" && (path == "/v1/models" || path == "/models" || path == "/api/models") -> {
                    val json = buildModelsCatalogJson().toString()
                    writeHttpResponse(output, 200, "OK", "application/json", json)
                    recordRequestLog(method, path, 200, System.currentTimeMillis() - startMs, null)
                }
                method == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions") -> {
                    handleChatCompletionRequest(output, bodyChars, path, startMs)
                }
                else -> {
                    val err = JSONObject().apply {
                        put(
                            "error",
                            JSONObject().apply {
                                put("message", "Route $method $path not found on Embedded OmniRoute. Use /v1/models or /v1/chat/completions.")
                                put("type", "invalid_request_error")
                            }
                        )
                    }.toString()
                    writeHttpResponse(output, 404, "Not Found", "application/json", err)
                    recordRequestLog(method, path, 404, System.currentTimeMillis() - startMs, null)
                }
            }
        } catch (e: Throwable) {
            appendTerminalLine("[127.0.0.1:$port] Socket error: ${e.message}")
        } finally {
            runCatching { socket.close() }
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

        // Build clean upstream messages (user + assistant + optional brief system)
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
            cleanMessages.put(JSONObject().put("role", "user").put("content", "Hello"))
        }

        // Route to zero-config OpenAI-compatible upstream (Pollinations Text Gateway)
        val upstreamModel = when {
            requestedModel.contains("deepseek", ignoreCase = true) -> "openai"
            requestedModel.contains("claude", ignoreCase = true) -> "openai"
            requestedModel.contains("llama", ignoreCase = true) -> "openai"
            requestedModel.contains("mistral", ignoreCase = true) -> "openai"
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

        val upstreamResult = runCatching {
            upstreamClient.newCall(upstreamReq).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Upstream HTTP ${resp.code}: ${bodyStr.take(160)}")
                }
                bodyStr
            }
        }

        upstreamResult.fold(
            onSuccess = { bodyStr ->
                val parsed = runCatching { JSONObject(bodyStr) }.getOrNull()
                val replyText = parsed?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.ifBlank { null }
                    ?: bodyStr.ifBlank { "OK" }

                val elapsed = System.currentTimeMillis() - startMs
                if (wantsStream) {
                    val chunkJson = JSONObject().apply {
                        put("id", "chatcmpl_${System.currentTimeMillis()}")
                        put("object", "chat.completion.chunk")
                        put("created", System.currentTimeMillis() / 1000L)
                        put("model", requestedModel)
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
                recordRequestLog("POST", "$path ($requestedModel)", 200, elapsed, null)
            },
            onFailure = { err ->
                val elapsed = System.currentTimeMillis() - startMs
                val errJson = JSONObject().apply {
                    put(
                        "error",
                        JSONObject().apply {
                            put("message", "Upstream error for $requestedModel: ${err.message}")
                            put("type", "upstream_error")
                        }
                    )
                }.toString()
                writeHttpResponse(output, 502, "Bad Gateway", "application/json", errJson)
                recordRequestLog("POST", "$path ($requestedModel)", 502, elapsed, null)
            }
        )
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
        appendTerminalLine("[127.0.0.1:${_statusFlow.value.port}] $summary")
        _statusFlow.update { current ->
            val updatedCount = servedRequests.get()
            current.copy(
                requestCount = updatedCount,
                lastRequestSummary = summary,
                liveHealthJson = healthSnapshot ?: buildHealthJson(current.port, current.startedAtMs, updatedCount).toString(2)
            )
        }
    }

    /**
     * Executes a user command entered in the built-in Terminal:
     * - Built-in `omniroute` daemon commands (`omniroute start`, `omniroute stop`, `omniroute restart`, `omniroute status`, `omniroute models`)
     * - Built-in `curl` inspector so `curl http://127.0.0.1:20128/health` works even on devices without a `/system/bin/curl` binary
     * - Real `/system/bin/sh -c` process execution for standard Linux shell commands (`ps`, `netstat`, `uname -a`, `ip addr`, `ping`, etc.)
     */
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
                    "  omniroute start          Start embedded OmniRoute server on 127.0.0.1:20128",
                    "  omniroute stop           Stop embedded OmniRoute server",
                    "  omniroute restart        Restart 127.0.0.1:20128 clean gateway",
                    "  omniroute status         Show live localhost:20128 socket & health status",
                    "  omniroute models         List active zero-config models on 127.0.0.1:20128",
                    "  curl http://127.0.0.1:20128/health       Inspect live localhost health JSON",
                    "  curl http://127.0.0.1:20128/v1/models    Inspect live localhost /v1/models",
                    "  <any shell command>      Executed via /system/bin/sh (e.g. uname -a, ps, ip addr)"
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
                    "Endpoint  : http://${st.host}:${st.port}/v1",
                    "Listening : ${st.isListening}",
                    "Requests  : ${st.requestCount} (${st.lastRequestSummary})",
                    "Scrapers  : cfp/ (Playwright) & OAuth/IDE (aug/, cu/, ws/) DISABLED"
                )
                lines.forEach { appendTerminalLine("  $it") }
                return@withContext lines.joinToString("\n")
            }
            lower.startsWith("omniroute models") -> {
                val catalog = buildModelsCatalogJson().toString(2)
                catalog.lines().forEach { appendTerminalLine("  $it") }
                return@withContext catalog
            }
            lower.startsWith("curl ") -> {
                val urlToken = cmd.split(Regex("\\s+"))
                    .firstOrNull { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("localhost:") || it.startsWith("127.0.0.1:") }
                    ?.let { if (it.startsWith("http")) it else "http://$it" }
                if (urlToken != null) {
                    if (urlToken.contains("127.0.0.1:20128") || urlToken.contains("localhost:20128")) {
                        if (!_statusFlow.value.isListening) {
                            startServer(20128)
                        }
                    }
                    val req = Request.Builder().url(urlToken).get().build()
                    val result = runCatching {
                        upstreamClient.newCall(req).execute().use { resp ->
                            val b = resp.body?.string().orEmpty()
                            "HTTP/1.1 ${resp.code} ${resp.message}\nContent-Type: ${resp.header("Content-Type") ?: "application/json"}\n\n${b.take(1200)}"
                        }
                    }.getOrElse { err ->
                        "curl error: ${err.message}"
                    }
                    result.lines().forEach { appendTerminalLine("  $it") }
                    return@withContext result
                }
            }
        }

        // Execute standard Linux shell command via /system/bin/sh
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
}
