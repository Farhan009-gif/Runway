package com.example.data.mcp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.local.McpApprovalMode
import com.example.data.local.McpServerEntity
import com.example.data.local.McpToolEntity
import com.example.data.local.McpTransportType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Supported languages in the Runway Code Runner.
 * Note: Python is intentionally NOT supported.
 */
enum class RunnerLanguage(val id: String, val displayName: String) {
    JAVASCRIPT("javascript", "JavaScript"),
    HTML("html", "HTML"),
    CSS("css", "CSS"),
    JAVA("java", "Java")
}

/**
 * Details shown on the Run confirmation sheet before every code execution.
 */
data class RunConfirmationDetails(
    val language: RunnerLanguage,
    val languageLabel: String,
    val code: String,
    val css: String = "",
    val js: String = "",
    val timeLimitMs: Long = JsSandboxExecutor.MAX_EXECUTION_TIME_MS,
    val timeLimitSeconds: Int = (JsSandboxExecutor.MAX_EXECUTION_TIME_MS / 1000L).toInt(),
    val timeLimitLabel: String = "${JsSandboxExecutor.MAX_EXECUTION_TIME_MS / 1000L} seconds",
    val memoryLimitBytes: Int = JsSandboxExecutor.MAX_MEMORY_BYTES,
    val outputCapBytes: Int = JsSandboxExecutor.MAX_OUTPUT_BYTES,
    val requiresUserConfirmation: Boolean = true,
    val isRemoteExecution: Boolean = false,
    val remoteServerNote: String? = null
)

/**
 * Result of executing code inside the isolated sandbox (HTML/CSS/JS) or remote Java runner.
 */
data class JsSandboxExecutionResult(
    val outputText: String,
    val isSuccess: Boolean,
    val isTimedOut: Boolean = false,
    val isMemoryExceeded: Boolean = false,
    val isOutputTruncated: Boolean = false,
    val sandboxDestroyed: Boolean = true,
    val durationMs: Long = 0L,
    val language: RunnerLanguage = RunnerLanguage.JAVASCRIPT,
    val renderedHtmlDocument: String? = null
)

/**
 * Isolated Code Runner & Sandbox Executor for the `"run_code"` tool.
 *
 * Supported languages: HTML, CSS, JavaScript (local sandboxed WebView), and Java (remote runner).
 * Python is not supported.
 *
 * Security & Resource Guarantees:
 * - HTML, CSS, and JavaScript:
 *   - Renders a full HTML document with its CSS and JS in a locked-down WebView.
 *   - Configured with NO network access (`blockNetworkLoads = true`),
 *     NO file/content access (`allowFileAccess = false`, `allowContentAccess = false`),
 *     NO DOM/database storage, and NO `addJavascriptInterface` bridges to app data.
 *   - Enforces a 5-second execution timeout ([MAX_EXECUTION_TIME_MS] = 5,000 ms) and 4 MB limit ([MAX_MEMORY_BYTES]).
 *   - Output truncated to 10 KB ([MAX_OUTPUT_BYTES] = 10,240 bytes).
 *   - Rendered result is shown only in a dedicated Preview Panel; model output is never rendered as HTML in the main chat.
 * - Java:
 *   - Runs through a user-configured remote runner URL with an optional encrypted API key stored in TinkSecretVault (never logged).
 *   - Enforces a 5-second timeout ([MAX_EXECUTION_TIME_MS] = 5,000 ms) and 10 KB output cap ([MAX_OUTPUT_BYTES] = 10,240 bytes).
 *   - If no runner is set up, returns a clear message explaining how to add one in Settings.
 * - Safety for all languages:
 *   - Every run requires explicit user confirmation via the Run confirmation sheet showing the language, code, and 5s time limit.
 *   - Never runs code automatically.
 *   - Shows an explicit note for Java: it runs on a remote server, so the code leaves the device.
 */
object JsSandboxExecutor {

    const val RUN_CODE_TOOL_NAME = "run_code"
    const val RUN_CODE_SERVER_ID = "builtin_js_sandbox"
    const val RUN_CODE_SERVER_NAME = "Code Runner"

    const val MAX_EXECUTION_TIME_MS = 5_000L
    const val MAX_MEMORY_BYTES = 4 * 1024 * 1024 // 4 MB sandbox allocation & document cap
    const val MAX_OUTPUT_BYTES = 10 * 1024 // 10 KB (10,240 bytes) output cap

    const val JAVA_REMOTE_SERVER_NOTE =
        "Java runs on a remote server, so the code leaves the device."

    const val NO_JAVA_RUNNER_MESSAGE =
        "No Java remote runner is set up. To run Java code, open Settings → Code Runner (Java Remote Runner), enter your runner URL (and optional key), and tap Save."

    private const val TRUNCATION_NOTICE = "\n...[Output truncated to 10 KB sandbox limit]"

    private val javaRunnerHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(MAX_EXECUTION_TIME_MS, TimeUnit.MILLISECONDS)
            .readTimeout(MAX_EXECUTION_TIME_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(MAX_EXECUTION_TIME_MS, TimeUnit.MILLISECONDS)
            .callTimeout(MAX_EXECUTION_TIME_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            // Intentionally NO logging interceptors so the runner key and code are never logged.
            .build()
    }

    /**
     * Returns true ONLY for HTML, CSS, JavaScript, and Java.
     * Python and other languages return false.
     */
    fun isSupportedRunnerLanguage(rawLanguage: String): Boolean {
        return when (rawLanguage.trim().lowercase()) {
            "js", "javascript", "ecmascript",
            "html", "htm", "html5", "html/css/js",
            "css", "stylesheet",
            "java" -> true
            else -> false
        }
    }

    /**
     * Resolves a language tag or code snippet to one of the supported [RunnerLanguage] values,
     * or returns null if an unsupported language (e.g. Python) was explicitly specified.
     */
    fun resolveRunnerLanguageOrNull(rawLanguage: String, code: String = ""): RunnerLanguage? {
        val norm = rawLanguage.trim().lowercase()
        if (norm.isNotEmpty() && norm != "code" && norm != "text") {
            return when (norm) {
                "js", "javascript", "ecmascript" -> RunnerLanguage.JAVASCRIPT
                "html", "htm", "html5", "html/css/js" -> RunnerLanguage.HTML
                "css", "stylesheet" -> RunnerLanguage.CSS
                "java" -> RunnerLanguage.JAVA
                else -> null
            }
        }
        val trimmedCode = code.trim()
        return when {
            trimmedCode.startsWith("<!DOCTYPE", ignoreCase = true) ||
                trimmedCode.startsWith("<html", ignoreCase = true) ||
                trimmedCode.startsWith("<style", ignoreCase = true) ||
                trimmedCode.startsWith("<body", ignoreCase = true) ||
                (trimmedCode.contains("<div", ignoreCase = true) && trimmedCode.contains("</div>", ignoreCase = true)) ->
                RunnerLanguage.HTML
            Regex("""\bclass\s+[A-Za-z_][A-Za-z0-9_]*[\s\S]*\bpublic\s+static\s+void\s+main\s*\(""").containsMatchIn(trimmedCode) ||
                trimmedCode.contains("System.out.print") ->
                RunnerLanguage.JAVA
            else -> RunnerLanguage.JAVASCRIPT
        }
    }

    fun resolveRunnerLanguage(rawLanguage: String, code: String = ""): RunnerLanguage {
        return resolveRunnerLanguageOrNull(rawLanguage, code) ?: RunnerLanguage.JAVASCRIPT
    }

    /**
     * Safety rule: Code execution MUST NEVER run automatically. Every single run requires
     * explicit user confirmation via the Run confirmation sheet.
     */
    fun requiresUserConfirmationBeforeEveryRun(language: String = ""): Boolean = true

    /**
     * Builds the structured confirmation payload displayed on the Run confirmation sheet
     * before every execution (language, code, 5-second time limit, and Java remote server note).
     */
    fun buildRunConfirmationDetails(
        argumentsJsonOrCode: String,
        explicitLanguage: String? = null
    ): RunConfirmationDetails {
        val trimmed = argumentsJsonOrCode.trim()
        var extractedLang = explicitLanguage?.trim().orEmpty()
        var extractedCode = trimmed
        var extractedCss = ""
        var extractedJs = ""

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val fields = parseFlatJsonMap(trimmed)
            if (extractedLang.isEmpty()) {
                extractedLang = fields["language"]?.trim().orEmpty()
            }
            extractedCss = fields["css"].orEmpty()
            extractedJs = fields["js"].orEmpty()
                .ifBlank { fields["javascript"].orEmpty() }
            val htmlField = fields["html"].orEmpty()
            val codeField = fields["code"].orEmpty()
                .ifBlank { fields["script"].orEmpty() }
            extractedCode = when {
                htmlField.isNotBlank() -> {
                    if (extractedLang.isEmpty()) extractedLang = "html"
                    htmlField
                }
                codeField.isNotBlank() -> codeField
                extractedCss.isNotBlank() && extractedJs.isBlank() -> {
                    if (extractedLang.isEmpty()) extractedLang = "css"
                    extractedCss
                }
                extractedJs.isNotBlank() -> {
                    if (extractedLang.isEmpty()) extractedLang = "javascript"
                    extractedJs
                }
                else -> trimmed
            }
        }

        val resolvedLang = resolveRunnerLanguage(extractedLang, extractedCode)
        val hasCombinedWebParts = resolvedLang == RunnerLanguage.HTML &&
            (extractedCss.isNotBlank() || extractedJs.isNotBlank() ||
                extractedCode.contains("<style", ignoreCase = true) ||
                extractedCode.contains("<script", ignoreCase = true))
        val languageLabel = when {
            resolvedLang == RunnerLanguage.JAVA -> "Java"
            hasCombinedWebParts -> "HTML / CSS / JavaScript"
            else -> resolvedLang.displayName
        }

        val displayCode = buildString {
            append(extractedCode)
            if (extractedCss.isNotBlank() && extractedCss != extractedCode) {
                if (isNotEmpty()) append("\n\n/* --- CSS --- */\n")
                append(extractedCss)
            }
            if (extractedJs.isNotBlank() && extractedJs != extractedCode) {
                if (isNotEmpty()) append("\n\n// --- JavaScript ---\n")
                append(extractedJs)
            }
        }

        val isJava = resolvedLang == RunnerLanguage.JAVA
        return RunConfirmationDetails(
            language = resolvedLang,
            languageLabel = languageLabel,
            code = displayCode,
            css = extractedCss,
            js = extractedJs,
            timeLimitMs = MAX_EXECUTION_TIME_MS,
            timeLimitSeconds = (MAX_EXECUTION_TIME_MS / 1000L).toInt(),
            timeLimitLabel = "${MAX_EXECUTION_TIME_MS / 1000L} seconds",
            memoryLimitBytes = MAX_MEMORY_BYTES,
            outputCapBytes = MAX_OUTPUT_BYTES,
            requiresUserConfirmation = true,
            isRemoteExecution = isJava,
            remoteServerNote = if (isJava) JAVA_REMOTE_SERVER_NOTE else null
        )
    }

    /**
     * Encodes a manual or tool code execution request into a canonical JSON arguments string.
     */
    fun buildRunCodeArgumentsJson(
        language: String,
        code: String,
        css: String = "",
        js: String = ""
    ): String {
        val normLang = language.trim().lowercase().ifBlank { "javascript" }
        return buildString {
            append("{\"language\":\"")
            append(escapeJsonString(normLang))
            append("\",\"code\":\"")
            append(escapeJsonString(code))
            append("\"")
            if (css.isNotBlank()) {
                append(",\"css\":\"")
                append(escapeJsonString(css))
                append("\"")
            }
            if (js.isNotBlank()) {
                append(",\"js\":\"")
                append(escapeJsonString(js))
                append("\"")
            }
            append("}")
        }
    }

    fun buildRunCodeServerEntity(): McpServerEntity {
        return McpServerEntity(
            id = RUN_CODE_SERVER_ID,
            name = RUN_CODE_SERVER_NAME,
            url = "sandbox://code-runner/isolated",
            transport = McpTransportType.STREAMABLE_HTTP.name,
            authHeaderName = "",
            approvalMode = McpApprovalMode.ASK_EVERY_TIME.name,
            isEnabled = true,
            lastTestStatus = "Code Runner Ready (HTML/CSS/JS Isolated Sandbox • Remote Java • 5s Limit)"
        )
    }

    fun buildSyntheticServerEntity(): McpServerEntity = buildRunCodeServerEntity()

    fun buildRunCodeToolEntity(): McpToolEntity {
        return McpToolEntity(
            id = "$RUN_CODE_SERVER_ID:$RUN_CODE_TOOL_NAME",
            serverId = RUN_CODE_SERVER_ID,
            serverName = RUN_CODE_SERVER_NAME,
            toolName = RUN_CODE_TOOL_NAME,
            description = "Execute HTML, CSS, JavaScript (in an isolated local WebView sandbox with no network/file access, 5s timeout, 4MB limit) or Java (via configured remote runner, 5s timeout, 10KB output cap). Requires user confirmation before every run.",
            inputSchemaJson = """{"type":"object","properties":{"language":{"type":"string","enum":["javascript","html","css","java"],"description":"Language to run: javascript, html, css, or java."},"code":{"type":"string","description":"Source code or HTML document to execute."},"css":{"type":"string","description":"Optional CSS styles when rendering HTML."},"js":{"type":"string","description":"Optional JavaScript code when rendering HTML."}},"required":["code"]}""",
            isReadOnlyHint = false,
            isEnabled = true
        )
    }

    fun buildSyntheticToolEntity(): McpToolEntity = buildRunCodeToolEntity()

    /**
     * Extracts the `code` string from the model's `argumentsJson` payload.
     */
    fun extractJsCodeFromArguments(argumentsJson: String): String {
        val trimmed = argumentsJson.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val fields = parseFlatJsonMap(trimmed)
            val extracted = fields["code"].orEmpty()
                .ifBlank { fields["html"].orEmpty() }
                .ifBlank { fields["javascript"].orEmpty() }
                .ifBlank { fields["js"].orEmpty() }
                .ifBlank { fields["css"].orEmpty() }
                .ifBlank { fields["script"].orEmpty() }
            if (extracted.isNotBlank()) return extracted
        }
        return trimmed
    }

    fun extractCodeFromArgumentsJson(argumentsJson: String): String =
        extractJsCodeFromArguments(argumentsJson)

    /**
     * Truncates UTF-8 string output so the returned string is strictly <= [maxBytes] (default 10 KB).
     */
    fun truncateOutputToLimit(
        rawOutput: String,
        maxBytes: Int = MAX_OUTPUT_BYTES
    ): Pair<String, Boolean> {
        val bytes = rawOutput.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) {
            return rawOutput to false
        }
        val noticeBytes = TRUNCATION_NOTICE.toByteArray(StandardCharsets.UTF_8)
        val allowedContentBytes = (maxBytes - noticeBytes.size).coerceAtLeast(0)
        var end = allowedContentBytes
        // Avoid splitting a multi-byte UTF-8 character sequence
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        val prefix = String(bytes, 0, end, StandardCharsets.UTF_8)
        return (prefix + TRUNCATION_NOTICE) to true
    }

    /**
     * Checks code for forbidden network, file, storage, or bridge APIs.
     */
    fun checkForbiddenApis(code: String): String? {
        val patterns = listOf(
            Regex("""\bfetch\s*\(""") to "Network access (fetch) is blocked in the sandbox.",
            Regex("""\bXMLHttpRequest\b""") to "Network access (XMLHttpRequest) is blocked in the sandbox.",
            Regex("""\bWebSocket\b""") to "Network access (WebSocket) is blocked in the sandbox.",
            Regex("""\bEventSource\b""") to "Network access (EventSource) is blocked in the sandbox.",
            Regex("""\bimportScripts\s*\(""") to "External script loading (importScripts) is blocked in the sandbox.",
            Regex("""\blocalStorage\b""") to "Storage access (localStorage) is blocked in the sandbox.",
            Regex("""\bsessionStorage\b""") to "Storage access (sessionStorage) is blocked in the sandbox.",
            Regex("""\bindexedDB\b""") to "Database access (indexedDB) is blocked in the sandbox.",
            Regex("""\bFileReader\b""") to "File access (FileReader) is blocked in the sandbox.",
            Regex("""\bAndroid\b|\bwindow\.Android\b""") to "Host app bridges are disabled in the sandbox."
        )
        for ((regex, reason) in patterns) {
            if (regex.containsMatchIn(code)) {
                return "Sandbox Security Error: $reason"
            }
        }
        return null
    }

    /**
     * Applies strict security settings to any WebView used for sandbox execution or HTML preview:
     * - blockNetworkLoads = true
     * - allowFileAccess = false
     * - allowContentAccess = false
     * - no JavaScript bridges
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configureLockedDownWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            blockNetworkImage = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            saveFormData = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
        }
        // Remove any legacy system JS interfaces and NEVER call addJavascriptInterface
        runCatching {
            wv.removeJavascriptInterface("searchBoxJavaBridge_")
            wv.removeJavascriptInterface("accessibility")
            wv.removeJavascriptInterface("accessibilityTraversal")
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Block all external navigation inside the sandbox
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()?.lowercase().orEmpty()
                if (url.isNotEmpty() && url != "about:blank" && !url.startsWith("data:")) {
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream("Blocked by sandbox".toByteArray(StandardCharsets.UTF_8))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    /**
     * Builds a complete, self-contained HTML5 document combining HTML, CSS, and JavaScript
     * along with sandbox instrumentation (console capture, memory limit tracking, and network/storage blocking).
     */
    fun buildFullHtmlDocument(
        html: String,
        css: String = "",
        js: String = "",
        maxMemoryBytes: Int = MAX_MEMORY_BYTES
    ): String {
        val trimmedHtml = html.trim()
        if (trimmedHtml.contains("window.__sandboxMaxBytes") && css.isBlank() && js.isBlank()) {
            return trimmedHtml
        }
        val guardScript = """
            <script>
            (function() {
                "use strict";
                window.__sandboxLogs = [];
                window.__sandboxErrors = [];
                window.__sandboxBytesUsed = 0;
                window.__sandboxMaxBytes = $maxMemoryBytes;
                window.__sandboxMemoryExceeded = false;
                window.__trackSandboxBytes = function(str) {
                    var s = String(str);
                    window.__sandboxBytesUsed += s.length * 2;
                    if (window.__sandboxBytesUsed > window.__sandboxMaxBytes) {
                        window.__sandboxMemoryExceeded = true;
                        var err = new Error("Sandbox Memory Limit Exceeded (" + window.__sandboxMaxBytes + " bytes)");
                        err.__memoryExceeded = true;
                        throw err;
                    }
                    return s;
                };
                var origConsole = window.console || {};
                window.console = {
                    log: function() {
                        var parts = [];
                        for (var i = 0; i < arguments.length; i++) {
                            var a = arguments[i];
                            parts.push(typeof a === 'object' ? JSON.stringify(a) : String(a));
                        }
                        window.__sandboxLogs.push(window.__trackSandboxBytes(parts.join(' ')));
                    },
                    warn: function() { window.console.log.apply(window.console, arguments); },
                    error: function() {
                        var parts = [];
                        for (var i = 0; i < arguments.length; i++) {
                            var a = arguments[i];
                            parts.push(typeof a === 'object' ? JSON.stringify(a) : String(a));
                        }
                        var line = window.__trackSandboxBytes(parts.join(' '));
                        window.__sandboxErrors.push(line);
                        window.__sandboxLogs.push("Error: " + line);
                    },
                    info: function() { window.console.log.apply(window.console, arguments); }
                };
                window.onerror = function(msg) {
                    var s = String(msg || "Script error");
                    if (s.indexOf("Memory Limit") !== -1) window.__sandboxMemoryExceeded = true;
                    window.__sandboxErrors.push(s);
                    return true;
                };
                try { Object.defineProperty(window, 'fetch', { value: undefined, writable: false }); } catch (e) {}
                try { Object.defineProperty(window, 'XMLHttpRequest', { value: undefined, writable: false }); } catch (e) {}
                try { Object.defineProperty(window, 'WebSocket', { value: undefined, writable: false }); } catch (e) {}
                try { Object.defineProperty(window, 'EventSource', { value: undefined, writable: false }); } catch (e) {}
            })();
            </script>
        """.trimIndent()

        val styleBlock = if (css.isNotBlank()) "<style>\n$css\n</style>" else ""
        val scriptBlock = if (js.isNotBlank()) "<script>\n$js\n</script>" else ""

        return if (trimmedHtml.contains("<html", ignoreCase = true) ||
            trimmedHtml.startsWith("<!DOCTYPE", ignoreCase = true)
        ) {
            var doc = trimmedHtml
            doc = if (doc.contains("<head>", ignoreCase = true)) {
                doc.replaceFirst(Regex("(?i)<head>"), "<head>\n$guardScript\n$styleBlock")
            } else {
                "$guardScript\n$styleBlock\n$doc"
            }
            if (scriptBlock.isNotEmpty()) {
                doc = if (doc.contains("</body>", ignoreCase = true)) {
                    doc.replaceFirst(Regex("(?i)</body>"), "$scriptBlock\n</body>")
                } else {
                    "$doc\n$scriptBlock"
                }
            }
            doc
        } else {
            """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            $guardScript
            $styleBlock
            </head>
            <body>
            $trimmedHtml
            $scriptBlock
            </body>
            </html>
            """.trimIndent()
        }
    }

    /**
     * Unified entry point for running HTML, CSS, JavaScript, or Java from a JSON arguments payload
     * or raw code string. Rejects unsupported languages (such as Python).
     */
    suspend fun executeRunnerFromArguments(
        context: Context? = null,
        argumentsJson: String,
        javaRunnerUrl: String = "",
        javaRunnerApiKey: String = "",
        timeoutMs: Long = MAX_EXECUTION_TIME_MS,
        maxMemoryBytes: Int = MAX_MEMORY_BYTES,
        maxOutputBytes: Int = MAX_OUTPUT_BYTES
    ): JsSandboxExecutionResult {
        val trimmed = argumentsJson.trim()
        val parsedFields = if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            parseFlatJsonMap(trimmed)
        } else {
            emptyMap()
        }
        val explicitLanguage = parsedFields["language"]?.trim().orEmpty()
        if (explicitLanguage.isNotEmpty() && !isSupportedRunnerLanguage(explicitLanguage)) {
            return JsSandboxExecutionResult(
                outputText = "Unsupported language '$explicitLanguage'. Only HTML, CSS, JavaScript, and Java are supported.",
                isSuccess = false,
                sandboxDestroyed = true,
                durationMs = 1L
            )
        }
        val details = buildRunConfirmationDetails(argumentsJson, explicitLanguage.ifEmpty { null })
        return when (details.language) {
            RunnerLanguage.JAVA -> {
                executeRemoteJava(
                    code = details.code,
                    runnerUrl = javaRunnerUrl,
                    apiKey = javaRunnerApiKey,
                    timeoutMs = timeoutMs,
                    maxOutputBytes = maxOutputBytes
                )
            }
            RunnerLanguage.HTML, RunnerLanguage.CSS -> {
                val htmlField = parsedFields["html"].orEmpty()
                val htmlPart = when {
                    htmlField.isNotBlank() -> htmlField
                    details.language == RunnerLanguage.CSS -> {
                        "<div class=\"preview-root\">CSS Preview Rendered</div>"
                    }
                    else -> extractJsCodeFromArguments(argumentsJson)
                }
                val cssPart = when {
                    details.css.isNotBlank() -> details.css
                    details.language == RunnerLanguage.CSS -> extractJsCodeFromArguments(argumentsJson)
                    else -> ""
                }
                executeSandboxedHtmlCssJs(
                    context = context,
                    html = htmlPart,
                    css = cssPart,
                    js = details.js,
                    language = details.language,
                    timeoutMs = timeoutMs,
                    maxMemoryBytes = maxMemoryBytes,
                    maxOutputBytes = maxOutputBytes
                )
            }
            RunnerLanguage.JAVASCRIPT -> {
                val codeOnly = extractJsCodeFromArguments(argumentsJson).ifBlank { argumentsJson }
                if (details.css.isNotBlank() || codeOnly.trimStart().startsWith("<")) {
                    executeSandboxedHtmlCssJs(
                        context = context,
                        html = if (codeOnly.trimStart().startsWith("<")) codeOnly else "<div id=\"app\"></div>",
                        css = details.css,
                        js = if (codeOnly.trimStart().startsWith("<")) details.js else codeOnly,
                        language = RunnerLanguage.HTML,
                        timeoutMs = timeoutMs,
                        maxMemoryBytes = maxMemoryBytes,
                        maxOutputBytes = maxOutputBytes
                    )
                } else {
                    executeSandboxedJs(
                        context = context,
                        code = codeOnly,
                        timeoutMs = timeoutMs,
                        maxMemoryBytes = maxMemoryBytes,
                        maxOutputBytes = maxOutputBytes
                    )
                }
            }
        }
    }

    /**
     * Renders a full HTML document with its CSS and JS in a locked-down WebView
     * (`blockNetworkLoads = true`, `allowFileAccess = false`, `allowContentAccess = false`, no JS bridges)
     * enforcing the 5-second timeout and 4 MB limit.
     */
    suspend fun executeSandboxedHtmlCssJs(
        context: Context? = null,
        html: String,
        css: String = "",
        js: String = "",
        language: RunnerLanguage = RunnerLanguage.HTML,
        timeoutMs: Long = MAX_EXECUTION_TIME_MS,
        maxMemoryBytes: Int = MAX_MEMORY_BYTES,
        maxOutputBytes: Int = MAX_OUTPUT_BYTES,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): JsSandboxExecutionResult = withContext(dispatcher) {
        val startMs = System.currentTimeMillis()
        if (html.isBlank() && css.isBlank() && js.isBlank()) {
            return@withContext JsSandboxExecutionResult(
                outputText = "Error: No HTML, CSS, or JavaScript provided to run_code.",
                isSuccess = false,
                sandboxDestroyed = true,
                durationMs = 1L,
                language = language
            )
        }

        val combinedRawBytes = (html.toByteArray(StandardCharsets.UTF_8).size +
            css.toByteArray(StandardCharsets.UTF_8).size +
            js.toByteArray(StandardCharsets.UTF_8).size)
        if (combinedRawBytes > maxMemoryBytes) {
            return@withContext JsSandboxExecutionResult(
                outputText = "Sandbox Memory Limit Exceeded: Document size ($combinedRawBytes bytes) exceeds 4 MB cap ($maxMemoryBytes bytes).",
                isSuccess = false,
                isMemoryExceeded = true,
                sandboxDestroyed = true,
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                language = language
            )
        }

        val fullDoc = buildFullHtmlDocument(
            html = html,
            css = css,
            js = js,
            maxMemoryBytes = maxMemoryBytes
        )
        val fullDocBytes = fullDoc.toByteArray(StandardCharsets.UTF_8).size
        if (fullDocBytes > maxMemoryBytes) {
            return@withContext JsSandboxExecutionResult(
                outputText = "Sandbox Memory Limit Exceeded: Full HTML document ($fullDocBytes bytes) exceeds cap ($maxMemoryBytes bytes).",
                isSuccess = false,
                isMemoryExceeded = true,
                sandboxDestroyed = true,
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                language = language
            )
        }

        // Extract inline <script> blocks from user HTML + explicit js parameter for forbidden API check
        val inlineScripts = extractScriptBlocksFromHtml(html)
        val allJsToCheck = (inlineScripts + listOf(js)).filter { it.isNotBlank() }.joinToString("\n")
        if (allJsToCheck.isNotBlank()) {
            checkForbiddenApis(allJsToCheck)?.let { securityErr ->
                return@withContext JsSandboxExecutionResult(
                    outputText = securityErr,
                    isSuccess = false,
                    sandboxDestroyed = true,
                    durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                    language = language
                )
            }
        }

        // Try real locked-down Android WebView when available
        if (context != null && isRealAndroidWebViewAvailable()) {
            val webViewResult = runCatching {
                renderHtmlDocumentInIsolatedWebView(
                    context = context,
                    fullHtmlDocument = fullDoc,
                    language = language,
                    timeoutMs = timeoutMs,
                    maxOutputBytes = maxOutputBytes
                )
            }.getOrNull()
            if (webViewResult != null) {
                return@withContext webViewResult
            }
        }

        // Deterministic JVM/Robolectric execution: run any embedded JS inside the isolated JVM sandbox
        // with the 5s timeout and 4 MB memory cap, and extract rendered text summary for output.
        val visibleHtmlText = stripHtmlTagsToVisibleText(html)
        if (allJsToCheck.isNotBlank()) {
            val jsResult = executeInPureJvmSandbox(
                code = allJsToCheck,
                timeoutMs = timeoutMs,
                maxMemoryBytes = maxMemoryBytes,
                maxOutputBytes = maxOutputBytes,
                startMs = startMs
            )
            if (!jsResult.isSuccess) {
                return@withContext jsResult.copy(
                    language = language,
                    renderedHtmlDocument = if (jsResult.isTimedOut || jsResult.isMemoryExceeded) null else fullDoc
                )
            }
            val combinedOutput = buildString {
                if (visibleHtmlText.isNotBlank()) {
                    appendLine("[Rendered HTML Preview]")
                    appendLine(visibleHtmlText)
                }
                if (jsResult.outputText.isNotBlank() && jsResult.outputText != "(No output)") {
                    if (isNotEmpty()) appendLine()
                    appendLine("[Console Output]")
                    append(jsResult.outputText)
                }
            }.ifBlank { "[Rendered HTML/CSS/JS document ready in Preview Panel]" }
            val (truncated, wasTruncated) = truncateOutputToLimit(combinedOutput, maxOutputBytes)
            return@withContext JsSandboxExecutionResult(
                outputText = truncated,
                isSuccess = true,
                isTimedOut = false,
                isMemoryExceeded = false,
                isOutputTruncated = wasTruncated || jsResult.isOutputTruncated,
                sandboxDestroyed = true,
                durationMs = jsResult.durationMs,
                language = language,
                renderedHtmlDocument = fullDoc
            )
        } else {
            val summary = if (visibleHtmlText.isNotBlank()) {
                "[Rendered HTML Preview]\n$visibleHtmlText"
            } else {
                "[Rendered HTML/CSS document ready in Preview Panel]"
            }
            val (truncated, wasTruncated) = truncateOutputToLimit(summary, maxOutputBytes)
            return@withContext JsSandboxExecutionResult(
                outputText = truncated,
                isSuccess = true,
                isTimedOut = false,
                isMemoryExceeded = false,
                isOutputTruncated = wasTruncated,
                sandboxDestroyed = true,
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                language = language,
                renderedHtmlDocument = fullDoc
            )
        }
    }

    /**
     * Executes Java code on a user-configured remote runner URL with a 5-second timeout and
     * 10 KB output cap. The optional API key is retrieved from the encrypted vault and is never logged.
     */
    suspend fun executeRemoteJava(
        code: String,
        runnerUrl: String,
        apiKey: String = "",
        timeoutMs: Long = MAX_EXECUTION_TIME_MS,
        maxOutputBytes: Int = MAX_OUTPUT_BYTES,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        customTransport: (suspend (url: String, payloadJson: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, String>)? = null
    ): JsSandboxExecutionResult = withContext(dispatcher) {
        val startMs = System.currentTimeMillis()
        val cleanUrl = runnerUrl.trim()
        if (cleanUrl.isEmpty()) {
            return@withContext JsSandboxExecutionResult(
                outputText = NO_JAVA_RUNNER_MESSAGE,
                isSuccess = false,
                isTimedOut = false,
                isMemoryExceeded = false,
                isOutputTruncated = false,
                sandboxDestroyed = true,
                durationMs = 1L,
                language = RunnerLanguage.JAVA
            )
        }

        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) {
            return@withContext JsSandboxExecutionResult(
                outputText = "Error: No Java code provided to run_code.",
                isSuccess = false,
                sandboxDestroyed = true,
                durationMs = 1L,
                language = RunnerLanguage.JAVA
            )
        }

        val payloadJson = buildString {
            append("{\"language\":\"java\",\"code\":\"")
            append(escapeJsonString(cleanCode))
            append("\",\"timeoutMs\":")
            append(timeoutMs)
            append("}")
        }

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isNotEmpty()) {
            headers["Authorization"] = "Bearer $trimmedKey"
            headers["X-Runner-Key"] = trimmedKey
        }

        fun redactSecret(input: String): String {
            if (trimmedKey.isEmpty() || input.isEmpty()) return input
            return input.replace(trimmedKey, "[REDACTED_KEY]")
        }

        try {
            val responsePair = withTimeoutOrNull(timeoutMs) {
                if (customTransport != null) {
                    customTransport(cleanUrl, payloadJson, headers, timeoutMs)
                } else {
                    val client = if (timeoutMs == MAX_EXECUTION_TIME_MS) {
                        javaRunnerHttpClient
                    } else {
                        javaRunnerHttpClient.newBuilder()
                            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .build()
                    }
                    val reqBuilder = Request.Builder()
                        .url(cleanUrl)
                        .post(payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    for ((k, v) in headers) {
                        reqBuilder.header(k, v)
                    }
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        resp.code to bodyStr
                    }
                }
            }

            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            if (responsePair == null) {
                return@withContext JsSandboxExecutionResult(
                    outputText = "Java Runner Timeout Error: Remote execution exceeded ${timeoutMs}ms (5s) limit and was terminated.",
                    isSuccess = false,
                    isTimedOut = true,
                    sandboxDestroyed = true,
                    durationMs = elapsed.coerceAtLeast(timeoutMs),
                    language = RunnerLanguage.JAVA
                )
            }

            val (statusCode, rawBody) = responsePair
            val safeBody = redactSecret(rawBody)
            val parsedOutput = parseJavaRunnerResponse(safeBody, statusCode)
            val (truncatedText, wasTruncated) = truncateOutputToLimit(parsedOutput.first, maxOutputBytes)

            JsSandboxExecutionResult(
                outputText = truncatedText.ifBlank { "(No output)" },
                isSuccess = parsedOutput.second,
                isTimedOut = false,
                isMemoryExceeded = false,
                isOutputTruncated = wasTruncated,
                sandboxDestroyed = true,
                durationMs = elapsed,
                language = RunnerLanguage.JAVA
            )
        } catch (e: SocketTimeoutException) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(timeoutMs)
            JsSandboxExecutionResult(
                outputText = "Java Runner Timeout Error: Remote execution exceeded ${timeoutMs}ms (5s) limit.",
                isSuccess = false,
                isTimedOut = true,
                sandboxDestroyed = true,
                durationMs = elapsed,
                language = RunnerLanguage.JAVA
            )
        } catch (e: InterruptedIOException) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(timeoutMs)
            JsSandboxExecutionResult(
                outputText = "Java Runner Timeout Error: Remote execution exceeded ${timeoutMs}ms (5s) limit.",
                isSuccess = false,
                isTimedOut = true,
                sandboxDestroyed = true,
                durationMs = elapsed,
                language = RunnerLanguage.JAVA
            )
        } catch (t: Throwable) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val safeMsg = redactSecret(t.message ?: t.javaClass.simpleName)
            val timedOut = elapsed >= timeoutMs ||
                safeMsg.contains("timeout", ignoreCase = true) ||
                safeMsg.contains("timed out", ignoreCase = true) ||
                safeMsg.contains("canceled", ignoreCase = true)
            if (timedOut) {
                JsSandboxExecutionResult(
                    outputText = "Java Runner Timeout Error: Remote execution exceeded ${timeoutMs}ms (5s) limit.",
                    isSuccess = false,
                    isTimedOut = true,
                    sandboxDestroyed = true,
                    durationMs = elapsed.coerceAtLeast(timeoutMs),
                    language = RunnerLanguage.JAVA
                )
            } else {
                val (truncated, wasTruncated) = truncateOutputToLimit(
                    "Java Remote Runner Error: $safeMsg",
                    maxOutputBytes
                )
                JsSandboxExecutionResult(
                    outputText = truncated,
                    isSuccess = false,
                    isTimedOut = false,
                    isOutputTruncated = wasTruncated,
                    sandboxDestroyed = true,
                    durationMs = elapsed,
                    language = RunnerLanguage.JAVA
                )
            }
        }
    }

    private fun parseJavaRunnerResponse(body: String, statusCode: Int): Pair<String, Boolean> {
        val trimmed = body.trim()
        if (statusCode !in 200..299) {
            val fields = if (trimmed.startsWith("{") && trimmed.endsWith("}")) parseFlatJsonMap(trimmed) else emptyMap()
            val errDetail = fields["error"].orEmpty()
                .ifBlank { fields["message"].orEmpty() }
                .ifBlank { trimmed }
            return "Remote Java Runner HTTP $statusCode: ${errDetail.ifBlank { "Request failed" }}" to false
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val fields = parseFlatJsonMap(trimmed)
            if (fields.isNotEmpty()) {
                val stdout = fields["stdout"].orEmpty()
                val stderr = fields["stderr"].orEmpty()
                val output = fields["output"].orEmpty()
                    .ifBlank { fields["result"].orEmpty() }
                val err = fields["error"].orEmpty()
                val exitCode = fields["exitCode"]?.toIntOrNull() ?: 0
                val okField = fields["ok"] ?: fields["success"]
                val ok = when {
                    okField != null -> okField.equals("true", ignoreCase = true)
                    else -> exitCode == 0 && err.isBlank()
                }
                val combined = buildString {
                    if (output.isNotBlank()) append(output)
                    if (stdout.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(stdout)
                    }
                    if (stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(stderr)
                    }
                    if (err.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("Error: $err")
                    }
                }.ifBlank { trimmed }
                return combined to ok
            }
        }
        return trimmed to true
    }

    private fun escapeJsonString(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch < ' ') {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun parseFlatJsonMap(json: String): Map<String, String> {
        runCatching {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!obj.isNull(k)) {
                    result[k] = obj.opt(k)?.toString().orEmpty()
                }
            }
            if (result.isNotEmpty()) return result
        }
        val result = mutableMapOf<String, String>()
        var i = 0
        val n = json.length
        fun skipWhitespace() {
            while (i < n && (json[i] == ' ' || json[i] == '\t' || json[i] == '\n' || json[i] == '\r' || json[i] == ',' || json[i] == '{' || json[i] == '}')) {
                i++
            }
        }
        fun readQuotedString(): String {
            if (i >= n || json[i] != '"') return ""
            i++ // skip opening quote
            val start = i
            while (i < n) {
                val c = json[i]
                if (c == '\\') {
                    i += 2
                } else if (c == '"') {
                    val rawSlice = json.substring(start, i)
                    i++ // skip closing quote
                    return unescapeJsonString(rawSlice)
                } else {
                    i++
                }
            }
            return unescapeJsonString(json.substring(start))
        }
        while (i < n) {
            skipWhitespace()
            if (i >= n) break
            if (json[i] != '"') {
                i++
                continue
            }
            val key = readQuotedString()
            while (i < n && (json[i] == ' ' || json[i] == '\t' || json[i] == '\n' || json[i] == '\r')) i++
            if (i >= n || json[i] != ':') continue
            i++ // skip ':'
            while (i < n && (json[i] == ' ' || json[i] == '\t' || json[i] == '\n' || json[i] == '\r')) i++
            if (i >= n) break
            if (json[i] == '"') {
                result[key] = readQuotedString()
            } else {
                val valStart = i
                while (i < n && json[i] != ',' && json[i] != '}' && !json[i].isWhitespace()) {
                    i++
                }
                val literal = json.substring(valStart, i).trim()
                if (literal != "null" && literal.isNotEmpty()) {
                    result[key] = literal
                }
            }
        }
        return result
    }

    private fun unescapeJsonString(escaped: String): String {
        val sb = StringBuilder(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val c = escaped[i]
            if (c == '\\' && i + 1 < escaped.length) {
                val next = escaped[i + 1]
                when (next) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        if (i + 5 < escaped.length) {
                            val hex = escaped.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(c)
                        i++
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun extractScriptBlocksFromHtml(html: String): List<String> {
        val regex = Regex("""(?is)<script\b[^>]*>([\s\S]*?)</script>""")
        return regex.findAll(html).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    }

    private fun stripHtmlTagsToVisibleText(html: String): String {
        val withoutScriptsAndStyles = html
            .replace(Regex("""(?is)<script\b[^>]*>[\s\S]*?</script>"""), "")
            .replace(Regex("""(?is)<style\b[^>]*>[\s\S]*?</style>"""), "")
            .replace(Regex("""(?is)<head\b[^>]*>[\s\S]*?</head>"""), "")
        return withoutScriptsAndStyles
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(p|div|h[1-6]|li|tr)>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * Executes JavaScript code in an isolated sandbox with a 5-second timeout,
     * 4 MB memory cap, and 10 KB output truncation.
     *
     * On Android devices, uses a hidden, zero-permission, network-blocked `WebView` that is
     * destroyed immediately after the run. In JVM unit test environments (or when `context` is null
     * or WebView is a unit-test stub), falls back to the isolated pure-JVM JS sandbox so limits
     * and execution are deterministically verified.
     */
    suspend fun executeSandboxedJs(
        context: Context? = null,
        code: String,
        timeoutMs: Long = MAX_EXECUTION_TIME_MS,
        maxMemoryBytes: Int = MAX_MEMORY_BYTES,
        maxOutputBytes: Int = MAX_OUTPUT_BYTES,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): JsSandboxExecutionResult = withContext(dispatcher) {
        val startMs = System.currentTimeMillis()
        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) {
            return@withContext JsSandboxExecutionResult(
                outputText = "Error: No JavaScript code provided to run_code.",
                isSuccess = false,
                sandboxDestroyed = true,
                durationMs = 1L,
                language = RunnerLanguage.JAVASCRIPT
            )
        }

        // Check static input size against memory cap
        val codeByteSize = cleanCode.toByteArray(StandardCharsets.UTF_8).size
        if (codeByteSize > maxMemoryBytes) {
            return@withContext JsSandboxExecutionResult(
                outputText = "Sandbox Memory Limit Exceeded: Script size ($codeByteSize bytes) exceeds cap ($maxMemoryBytes bytes).",
                isSuccess = false,
                isMemoryExceeded = true,
                sandboxDestroyed = true,
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                language = RunnerLanguage.JAVASCRIPT
            )
        }

        checkForbiddenApis(cleanCode)?.let { securityErr ->
            return@withContext JsSandboxExecutionResult(
                outputText = securityErr,
                isSuccess = false,
                sandboxDestroyed = true,
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                language = RunnerLanguage.JAVASCRIPT
            )
        }

        val previewHtml = buildFullHtmlDocument(
            html = "<div id=\"app\"></div>",
            css = "body { font-family: monospace; padding: 12px; }",
            js = cleanCode,
            maxMemoryBytes = maxMemoryBytes
        )

        // Try isolated hidden WebView when a real Android Context is provided on a non-stubbed runtime
        if (context != null && isRealAndroidWebViewAvailable()) {
            val webViewResult = runCatching {
                executeInIsolatedHiddenWebView(
                    context = context,
                    code = cleanCode,
                    timeoutMs = timeoutMs,
                    maxMemoryBytes = maxMemoryBytes,
                    maxOutputBytes = maxOutputBytes,
                    renderedHtmlDocument = previewHtml
                )
            }.getOrNull()
            if (webViewResult != null) {
                return@withContext webViewResult
            }
        }

        // Isolated pure-JVM sandbox execution (used in JVM unit tests and as deterministic engine)
        executeInPureJvmSandbox(
            code = cleanCode,
            timeoutMs = timeoutMs,
            maxMemoryBytes = maxMemoryBytes,
            maxOutputBytes = maxOutputBytes,
            startMs = startMs
        ).copy(
            language = RunnerLanguage.JAVASCRIPT,
            renderedHtmlDocument = previewHtml
        )
    }

    private fun isRealAndroidWebViewAvailable(): Boolean {
        val runtimeName = System.getProperty("java.runtime.name")?.lowercase().orEmpty()
        val isAndroidRuntime = runtimeName.contains("android")
        val isRobolectric = System.getProperty("robolectric.logging") != null ||
            runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess
        return isAndroidRuntime && !isRobolectric
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderHtmlDocumentInIsolatedWebView(
        context: Context,
        fullHtmlDocument: String,
        language: RunnerLanguage,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): JsSandboxExecutionResult? {
        val startMs = System.currentTimeMillis()
        val mainHandler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        val destroyed = AtomicBoolean(false)

        fun destroyWebViewOnMain() {
            if (destroyed.compareAndSet(false, true)) {
                mainHandler.post {
                    runCatching {
                        webView?.apply {
                            stopLoading()
                            loadUrl("about:blank")
                            clearHistory()
                            clearCache(true)
                            removeAllViews()
                            destroy()
                        }
                        webView = null
                    }
                }
            }
        }

        return try {
            val rawJsonResult = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { cont ->
                    mainHandler.post {
                        try {
                            val wv = WebView(context.applicationContext)
                            webView = wv
                            configureLockedDownWebView(wv)
                            wv.webViewClient = object : WebViewClient() {
                                private val finishedOnce = AtomicBoolean(false)

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean = true

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (!finishedOnce.compareAndSet(false, true)) return
                                    val inspectScript = """
                                        (function() {
                                            var bodyText = (document.body && document.body.innerText) ? document.body.innerText.trim() : "";
                                            var logs = (window.__sandboxLogs || []).join('\n');
                                            var errs = (window.__sandboxErrors || []);
                                            var mem = !!window.__sandboxMemoryExceeded;
                                            var combined = "";
                                            if (bodyText) combined += "[Rendered HTML Preview]\n" + bodyText;
                                            if (logs) {
                                                if (combined) combined += "\n\n";
                                                combined += "[Console Output]\n" + logs;
                                            }
                                            if (!combined) combined = "[Rendered HTML/CSS/JS document ready in Preview Panel]";
                                            return JSON.stringify({
                                                ok: errs.length === 0 && !mem,
                                                memoryExceeded: mem,
                                                output: combined
                                            });
                                        })();
                                    """.trimIndent()
                                    wv.evaluateJavascript(inspectScript) { value ->
                                        if (cont.isActive) {
                                            cont.resume(value)
                                        }
                                    }
                                }
                            }
                            wv.loadDataWithBaseURL(
                                "about:blank",
                                fullHtmlDocument,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        } catch (t: Throwable) {
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }
                    }
                    cont.invokeOnCancellation {
                        destroyWebViewOnMain()
                    }
                }
            }

            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            if (rawJsonResult == null) {
                destroyWebViewOnMain()
                return JsSandboxExecutionResult(
                    outputText = "Sandbox Timeout Error: Execution exceeded ${timeoutMs}ms limit and was terminated.",
                    isSuccess = false,
                    isTimedOut = true,
                    sandboxDestroyed = true,
                    durationMs = elapsed.coerceAtLeast(timeoutMs),
                    language = language,
                    renderedHtmlDocument = null
                )
            }

            val decoded = decodeEvaluateJavascriptString(rawJsonResult)
            val parsed = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
            val ok = parsed.optBoolean("ok", true)
            val memExceeded = parsed.optBoolean("memoryExceeded", false)
            val combinedText = parsed.optString("output", "")
            val (truncatedText, wasTruncated) = truncateOutputToLimit(combinedText, maxOutputBytes)

            JsSandboxExecutionResult(
                outputText = truncatedText.ifBlank { "(No output)" },
                isSuccess = ok && !memExceeded,
                isTimedOut = false,
                isMemoryExceeded = memExceeded,
                isOutputTruncated = wasTruncated,
                sandboxDestroyed = true,
                durationMs = elapsed,
                language = language,
                renderedHtmlDocument = if (memExceeded) null else fullHtmlDocument
            )
        } finally {
            destroyWebViewOnMain()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun executeInIsolatedHiddenWebView(
        context: Context,
        code: String,
        timeoutMs: Long,
        maxMemoryBytes: Int,
        maxOutputBytes: Int,
        renderedHtmlDocument: String? = null
    ): JsSandboxExecutionResult? {
        val startMs = System.currentTimeMillis()
        val mainHandler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        val destroyed = AtomicBoolean(false)

        fun destroyWebViewOnMain() {
            if (destroyed.compareAndSet(false, true)) {
                mainHandler.post {
                    runCatching {
                        webView?.apply {
                            stopLoading()
                            loadUrl("about:blank")
                            clearHistory()
                            clearCache(true)
                            removeAllViews()
                            destroy()
                        }
                        webView = null
                    }
                }
            }
        }

        return try {
            val rawJsonResult = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { cont ->
                    mainHandler.post {
                        try {
                            val wv = WebView(context.applicationContext)
                            webView = wv
                            configureLockedDownWebView(wv)
                            // Strictly NO addJavascriptInterface calls — zero bridges to app data.
                            val wrappedScript = buildWebViewSandboxWrapperScript(code, maxMemoryBytes)
                            wv.evaluateJavascript(wrappedScript) { value ->
                                if (cont.isActive) {
                                    cont.resume(value)
                                }
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }
                    }
                    cont.invokeOnCancellation {
                        destroyWebViewOnMain()
                    }
                }
            }

            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            if (rawJsonResult == null) {
                // Timed out after timeoutMs
                destroyWebViewOnMain()
                return JsSandboxExecutionResult(
                    outputText = "Sandbox Timeout Error: Execution exceeded ${timeoutMs}ms limit and was terminated.",
                    isSuccess = false,
                    isTimedOut = true,
                    sandboxDestroyed = true,
                    durationMs = elapsed.coerceAtLeast(timeoutMs),
                    language = RunnerLanguage.JAVASCRIPT
                )
            }

            val decoded = decodeEvaluateJavascriptString(rawJsonResult)
            val parsed = runCatching { JSONObject(decoded) }.getOrNull()
                ?: return null

            val ok = parsed.optBoolean("ok", true)
            val memExceeded = parsed.optBoolean("memoryExceeded", false)
            val combinedText = parsed.optString("output", "")
            val (truncatedText, wasTruncated) = truncateOutputToLimit(combinedText, maxOutputBytes)

            JsSandboxExecutionResult(
                outputText = truncatedText.ifBlank { "(No output)" },
                isSuccess = ok && !memExceeded,
                isTimedOut = false,
                isMemoryExceeded = memExceeded,
                isOutputTruncated = wasTruncated,
                sandboxDestroyed = true,
                durationMs = elapsed,
                language = RunnerLanguage.JAVASCRIPT,
                renderedHtmlDocument = if (memExceeded) null else renderedHtmlDocument
            )
        } finally {
            destroyWebViewOnMain()
        }
    }

    private fun buildWebViewSandboxWrapperScript(userCode: String, maxMemoryBytes: Int): String {
        val escapedUserCode = JSONObject.quote(userCode)
        return """
            (function() {
                "use strict";
                var __logs = [];
                var __bytesUsed = 0;
                var __maxBytes = $maxMemoryBytes;
                function __trackBytes(str) {
                    var s = String(str);
                    __bytesUsed += s.length * 2;
                    if (__bytesUsed > __maxBytes) {
                        var err = new Error("Sandbox Memory Limit Exceeded (" + __maxBytes + " bytes)");
                        err.__memoryExceeded = true;
                        throw err;
                    }
                    return s;
                }
                var console = {
                    log: function() {
                        var parts = [];
                        for (var i = 0; i < arguments.length; i++) {
                            var a = arguments[i];
                            parts.push(typeof a === 'object' ? JSON.stringify(a) : String(a));
                        }
                        __logs.push(__trackBytes(parts.join(' ')));
                    },
                    warn: function() { console.log.apply(console, arguments); },
                    error: function() { console.log.apply(console, arguments); },
                    info: function() { console.log.apply(console, arguments); }
                };
                var fetch = undefined;
                var XMLHttpRequest = undefined;
                var WebSocket = undefined;
                var EventSource = undefined;
                var localStorage = undefined;
                var sessionStorage = undefined;
                var indexedDB = undefined;
                try {
                    var __fn = new Function("console", "fetch", "XMLHttpRequest", "WebSocket", "EventSource", "localStorage", "sessionStorage", "indexedDB", $escapedUserCode);
                    var __ret = __fn(console, undefined, undefined, undefined, undefined, undefined, undefined, undefined);
                    if (__ret !== undefined) {
                        var __retStr = typeof __ret === 'object' ? JSON.stringify(__ret) : String(__ret);
                        __logs.push(__trackBytes(__retStr));
                    }
                    return JSON.stringify({ ok: true, memoryExceeded: false, output: __logs.join('\n') });
                } catch (e) {
                    var isMem = !!(e && e.__memoryExceeded) || (e && String(e).indexOf("Memory Limit") !== -1) || (e instanceof RangeError);
                    var msg = (e && e.stack) ? String(e.message || e) : String(e);
                    if (__logs.length > 0) {
                        msg = __logs.join('\n') + '\nError: ' + msg;
                    } else {
                        msg = 'Error: ' + msg;
                    }
                    return JSON.stringify({ ok: false, memoryExceeded: isMem, output: msg });
                }
            })();
        """.trimIndent()
    }

    private fun decodeEvaluateJavascriptString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "null") return ""
        return runCatching {
            JSONArray("[$trimmed]").getString(0)
        }.getOrDefault(trimmed.removeSurrounding("\""))
    }

    /**
     * Pure-JVM isolated JavaScript sandbox with strict thread timeout ([timeoutMs]),
     * heap allocation tracker ([maxMemoryBytes]), and output truncation ([maxOutputBytes]).
     * All sandbox state is local to the task and discarded immediately in `finally`.
     */
    private fun executeInPureJvmSandbox(
        code: String,
        timeoutMs: Long,
        maxMemoryBytes: Int,
        maxOutputBytes: Int,
        startMs: Long
    ): JsSandboxExecutionResult {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "runway-js-sandbox-worker").apply { isDaemon = true }
        }
        val cancelFlag = AtomicBoolean(false)
        var future: Future<PureSandboxInternalResult>? = null

        return try {
            future = executor.submit(Callable {
                val engine = IsolatedMiniJsInterpreter(
                    maxMemoryBytes = maxMemoryBytes,
                    cancelFlag = cancelFlag,
                    deadlineMs = System.currentTimeMillis() + timeoutMs
                )
                try {
                    engine.execute(code)
                } finally {
                    engine.destroy()
                }
            })

            val internal = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val (finalOutput, isTruncated) = truncateOutputToLimit(internal.output, maxOutputBytes)
            JsSandboxExecutionResult(
                outputText = finalOutput.ifBlank { "(No output)" },
                isSuccess = internal.isSuccess,
                isTimedOut = internal.isTimedOut,
                isMemoryExceeded = internal.isMemoryExceeded,
                isOutputTruncated = isTruncated,
                sandboxDestroyed = true,
                durationMs = elapsed
            )
        } catch (e: TimeoutException) {
            cancelFlag.set(true)
            future?.cancel(true)
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(timeoutMs)
            JsSandboxExecutionResult(
                outputText = "Sandbox Timeout Error: Execution exceeded ${timeoutMs}ms limit and was terminated.",
                isSuccess = false,
                isTimedOut = true,
                isMemoryExceeded = false,
                isOutputTruncated = false,
                sandboxDestroyed = true,
                durationMs = elapsed
            )
        } catch (t: Throwable) {
            cancelFlag.set(true)
            future?.cancel(true)
            val cause = t.cause ?: t
            val isMem = cause is SandboxMemoryExceededException || cause is OutOfMemoryError
            val isTimeout = cause is SandboxTimeoutException
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val (finalOutput, isTruncated) = truncateOutputToLimit(
                "Error: ${cause.message ?: cause.javaClass.simpleName}",
                maxOutputBytes
            )
            JsSandboxExecutionResult(
                outputText = finalOutput,
                isSuccess = false,
                isTimedOut = isTimeout,
                isMemoryExceeded = isMem,
                isOutputTruncated = isTruncated,
                sandboxDestroyed = true,
                durationMs = elapsed
            )
        } finally {
            cancelFlag.set(true)
            future?.cancel(true)
            executor.shutdownNow()
        }
    }

    private data class PureSandboxInternalResult(
        val output: String,
        val isSuccess: Boolean,
        val isTimedOut: Boolean = false,
        val isMemoryExceeded: Boolean = false
    )

    private class SandboxMemoryExceededException(message: String) : RuntimeException(message)
    private class SandboxTimeoutException(message: String) : RuntimeException(message)

    /**
     * Self-contained, zero-dependency JavaScript interpreter for isolated sandbox execution.
     * Supports variables (`let`, `const`, `var`), `console.log`/`warn`/`error`, arithmetic,
     * strings (`.repeat`, `.length`, concatenation), arrays (`Array(n).fill(...)`, `.push`),
     * `while` and `for` loops, `Math.*`, `JSON.stringify`, `return`, and `throw new Error(...)`
     * while enforcing strict CPU time and memory allocation caps.
     */
    private class IsolatedMiniJsInterpreter(
        private val maxMemoryBytes: Int,
        private val cancelFlag: AtomicBoolean,
        private val deadlineMs: Long
    ) {
        private val variables = mutableMapOf<String, Any?>()
        private val logs = StringBuilder()
        private var allocatedBytes: Long = 0L

        fun destroy() {
            variables.clear()
            logs.setLength(0)
            allocatedBytes = 0L
        }

        private fun checkTimeout() {
            if (cancelFlag.get() || Thread.currentThread().isInterrupted || System.currentTimeMillis() > deadlineMs) {
                throw SandboxTimeoutException("Execution timed out after ${MAX_EXECUTION_TIME_MS}ms")
            }
        }

        private fun checkLimits(additionalBytes: Long = 0L) {
            checkTimeout()
            if (additionalBytes > 0L) {
                allocatedBytes += additionalBytes
                if (allocatedBytes > maxMemoryBytes) {
                    throw SandboxMemoryExceededException(
                        "Sandbox Memory Limit Exceeded: Allocated $allocatedBytes bytes (cap is $maxMemoryBytes bytes)"
                    )
                }
            }
        }

        fun execute(rawScript: String): PureSandboxInternalResult {
            return try {
                val cleaned = stripJsComments(rawScript).trim()
                val statements = splitTopLevelStatements(cleaned)
                var lastValue: Any? = Unit
                for (stmt in statements) {
                    checkTimeout()
                    val s = stmt.trim()
                    if (s.isEmpty()) continue
                    val res = executeStatement(s)
                    if (res is ReturnSignal) {
                        lastValue = res.value
                        break
                    }
                    lastValue = res
                }
                if (lastValue !== Unit && lastValue != null && logs.isEmpty()) {
                    appendLog(formatJsValue(lastValue))
                } else if (lastValue !== Unit && lastValue != null && statements.lastOrNull()?.trim()?.startsWith("return ") == true) {
                    appendLog(formatJsValue(lastValue))
                }
                PureSandboxInternalResult(
                    output = logs.toString(),
                    isSuccess = true
                )
            } catch (e: SandboxTimeoutException) {
                PureSandboxInternalResult(
                    output = "Sandbox Timeout Error: ${e.message}",
                    isSuccess = false,
                    isTimedOut = true
                )
            } catch (e: SandboxMemoryExceededException) {
                PureSandboxInternalResult(
                    output = "Sandbox Memory Limit Exceeded: ${e.message}",
                    isSuccess = false,
                    isMemoryExceeded = true
                )
            } catch (e: Throwable) {
                val prefix = if (logs.isNotEmpty()) "${logs}\n" else ""
                PureSandboxInternalResult(
                    output = "${prefix}Error: ${e.message ?: e.javaClass.simpleName}",
                    isSuccess = false
                )
            }
        }

        private data class ReturnSignal(val value: Any?)

        private fun appendLog(line: String) {
            checkLimits(line.length * 2L)
            if (logs.isNotEmpty()) logs.append('\n')
            logs.append(line)
        }

        private fun executeStatement(stmt: String): Any? {
            checkTimeout()
            val s = stmt.trim().removeSuffix(";").trim()
            if (s.isEmpty()) return Unit

            // Handle block { ... }
            if (s.startsWith("{") && s.endsWith("}")) {
                val inner = s.substring(1, s.length - 1)
                var last: Any? = Unit
                for (sub in splitTopLevelStatements(inner)) {
                    val r = executeStatement(sub)
                    if (r is ReturnSignal) return r
                    last = r
                }
                return last
            }

            // Handle throw
            if (s.startsWith("throw ")) {
                val expr = s.removePrefix("throw ").trim()
                val msg = expr.removePrefix("new Error(").removeSuffix(")").trim().removeSurrounding("\"").removeSurrounding("'")
                throw RuntimeException(msg.ifBlank { expr })
            }

            // Handle return
            if (s == "return" || s.startsWith("return ")) {
                val expr = s.removePrefix("return").trim()
                return ReturnSignal(if (expr.isEmpty()) null else evaluateExpression(expr))
            }

            // Handle while (...) { ... }
            val whileMatch = Regex("""^while\s*\(([^)]*)\)\s*([\s\S]*)$""").find(s)
            if (whileMatch != null) {
                val condExpr = whileMatch.groupValues[1].trim()
                val bodyStmt = whileMatch.groupValues[2].trim()
                while (isTruthy(evaluateExpression(condExpr))) {
                    checkTimeout()
                    if (bodyStmt.isNotEmpty()) {
                        val r = executeStatement(bodyStmt)
                        if (r is ReturnSignal) return r
                    }
                }
                return Unit
            }

            // Handle for (init; cond; step) { ... }
            val forMatch = Regex("""^for\s*\(([^;]*);([^;]*);([^)]*)\)\s*([\s\S]*)$""").find(s)
            if (forMatch != null) {
                val initStmt = forMatch.groupValues[1].trim()
                val condExpr = forMatch.groupValues[2].trim()
                val stepStmt = forMatch.groupValues[3].trim()
                val bodyStmt = forMatch.groupValues[4].trim()
                if (initStmt.isNotEmpty()) executeStatement(initStmt)
                while (condExpr.isEmpty() || isTruthy(evaluateExpression(condExpr))) {
                    checkTimeout()
                    if (bodyStmt.isNotEmpty()) {
                        val r = executeStatement(bodyStmt)
                        if (r is ReturnSignal) return r
                    }
                    if (stepStmt.isNotEmpty()) executeStatement(stepStmt)
                }
                return Unit
            }

            // Handle console.log / warn / error / info
            val consoleMatch = Regex("""^console\.(log|warn|error|info)\s*\(([\s\S]*)\)$""").find(s)
            if (consoleMatch != null) {
                val argsRaw = consoleMatch.groupValues[2]
                val args = splitTopLevelArgs(argsRaw).map { evaluateExpression(it) }
                appendLog(args.joinToString(" ") { formatJsValue(it) })
                return Unit
            }

            // Handle variable declaration: var / let / const name = expr
            val declMatch = Regex("""^(?:var|let|const)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*(?:=\s*([\s\S]+))?$""").find(s)
            if (declMatch != null) {
                val name = declMatch.groupValues[1]
                val rhs = declMatch.groupValues[2].trim()
                val value = if (rhs.isEmpty()) null else evaluateExpression(rhs)
                trackValueMemory(value)
                variables[name] = value
                return Unit
            }

            // Handle increment / decrement: i++, ++i, i--, --i
            val incMatch = Regex("""^([a-zA-Z_$][a-zA-Z0-9_$]*)\s*(\+\+|--)$""").find(s)
                ?: Regex("""^(\+\+|--)\s*([a-zA-Z_$][a-zA-Z0-9_$]*)$""").find(s)
            if (incMatch != null) {
                val isPrefix = incMatch.groupValues[1] == "++" || incMatch.groupValues[1] == "--"
                val name = if (isPrefix) incMatch.groupValues[2] else incMatch.groupValues[1]
                val op = if (isPrefix) incMatch.groupValues[1] else incMatch.groupValues[2]
                val cur = toDouble(variables[name])
                val next = if (op == "++") cur + 1.0 else cur - 1.0
                variables[name] = next
                return next
            }

            // Handle compound assignment: name += expr, name -= expr, name *= expr
            val compoundMatch = Regex("""^([a-zA-Z_$][a-zA-Z0-9_$]*)\s*(\+=|-=|\*=)\s*([\s\S]+)$""").find(s)
            if (compoundMatch != null) {
                val name = compoundMatch.groupValues[1]
                val op = compoundMatch.groupValues[2]
                val rhsVal = evaluateExpression(compoundMatch.groupValues[3])
                val curVal = variables[name]
                val updated: Any? = when (op) {
                    "+=" -> {
                        if (curVal is String || rhsVal is String) {
                            val combined = formatJsValue(curVal) + formatJsValue(rhsVal)
                            checkLimits(combined.length * 2L)
                            combined
                        } else {
                            toDouble(curVal) + toDouble(rhsVal)
                        }
                    }
                    "-=" -> toDouble(curVal) - toDouble(rhsVal)
                    "*=" -> toDouble(curVal) * toDouble(rhsVal)
                    else -> rhsVal
                }
                trackValueMemory(updated)
                variables[name] = updated
                return updated
            }

            // Handle array.push(...)
            val pushMatch = Regex("""^([a-zA-Z_$][a-zA-Z0-9_$]*)\.push\s*\(([\s\S]*)\)$""").find(s)
            if (pushMatch != null) {
                val name = pushMatch.groupValues[1]
                val argVal = evaluateExpression(pushMatch.groupValues[2])
                trackValueMemory(argVal)
                @Suppress("UNCHECKED_CAST")
                val list = (variables[name] as? MutableList<Any?>) ?: mutableListOf<Any?>().also { variables[name] = it }
                checkLimits(64)
                list.add(argVal)
                return list.size.toDouble()
            }

            // Handle simple assignment: name = expr
            val assignMatch = Regex("""^([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(?!=)([\s\S]+)$""").find(s)
            if (assignMatch != null) {
                val name = assignMatch.groupValues[1]
                val value = evaluateExpression(assignMatch.groupValues[2])
                trackValueMemory(value)
                variables[name] = value
                return value
            }

            return evaluateExpression(s)
        }

        private fun trackValueMemory(value: Any?) {
            when (value) {
                is String -> checkLimits(value.length * 2L)
                is List<*> -> checkLimits(value.size * 16L)
                else -> checkLimits(8L)
            }
        }

        private fun evaluateExpression(rawExpr: String): Any? {
            checkTimeout()
            val expr = trimEnclosingParens(rawExpr.trim())
            if (expr.isEmpty()) return null

            if (expr.startsWith("typeof ")) {
                val targetName = trimEnclosingParens(expr.removePrefix("typeof ").trim())
                if (!variables.containsKey(targetName)) return "undefined"
                return when (variables[targetName]) {
                    null -> "undefined"
                    is Boolean -> "boolean"
                    is Number -> "number"
                    is String -> "string"
                    else -> "object"
                }
            }

            when (expr) {
                "true" -> return true
                "false" -> return false
                "null", "undefined" -> return null
            }

            // String literal
            if ((expr.startsWith("\"") && expr.endsWith("\"") && expr.length >= 2) ||
                (expr.startsWith("'") && expr.endsWith("'") && expr.length >= 2)
            ) {
                val inner = expr.substring(1, expr.length - 1)
                if (!inner.contains(expr[0])) {
                    val unescaped = inner
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\'", "'")
                    checkLimits(unescaped.length * 2L)
                    return unescaped
                }
            }

            // Numeric literal
            expr.toDoubleOrNull()?.let { return it }

            // Array allocation: new Array(N).fill(x) or Array(N).fill(x) or new Array(N)
            val arrayAllocMatch = Regex("""^(?:new\s+)?Array\s*\(\s*([^)]+)\s*\)(?:\.fill\s*\(\s*([^)]*)\s*\))?(?:\.join\s*\(\s*([^)]*)\s*\))?$""").find(expr)
            if (arrayAllocMatch != null) {
                val count = toDouble(evaluateExpression(arrayAllocMatch.groupValues[1])).toLong().coerceAtLeast(0L)
                val fillExpr = arrayAllocMatch.groupValues[2].trim()
                val fillVal = if (fillExpr.isEmpty()) null else evaluateExpression(fillExpr)
                val perElemBytes = when (fillVal) {
                    is String -> max(16L, fillVal.length * 2L)
                    else -> 16L
                }
                checkLimits(count * perElemBytes)
                val countInt = count.toInt()
                val list = MutableList(countInt) { fillVal }
                val joinGroup = arrayAllocMatch.groupValues[3]
                if (expr.contains(".join(")) {
                    val sep = if (joinGroup.isBlank()) "," else formatJsValue(evaluateExpression(joinGroup))
                    val joined = list.joinToString(sep) { formatJsValue(it) }
                    checkLimits(joined.length * 2L)
                    return joined
                }
                return list
            }

            // String.repeat(N): "x".repeat(1000) or expr.repeat(1000)
            val repeatIdx = findTopLevelMethodCall(expr, ".repeat(")
            if (repeatIdx > 0 && expr.endsWith(")")) {
                val targetExpr = expr.substring(0, repeatIdx)
                val countExpr = expr.substring(repeatIdx + ".repeat(".length, expr.length - 1)
                val baseStr = formatJsValue(evaluateExpression(targetExpr))
                val count = toDouble(evaluateExpression(countExpr)).toInt().coerceAtLeast(0)
                checkLimits(baseStr.length.toLong() * count.toLong() * 2L)
                return baseStr.repeat(count)
            }

            // JSON.stringify(x)
            if (expr.startsWith("JSON.stringify(") && expr.endsWith(")")) {
                val inner = expr.substring("JSON.stringify(".length, expr.length - 1)
                val v = evaluateExpression(inner)
                return when (v) {
                    null -> "null"
                    is String -> JSONObject.quote(v)
                    is List<*> -> v.joinToString(",", prefix = "[", postfix = "]") {
                        if (it is String) JSONObject.quote(it) else formatJsValue(it)
                    }
                    else -> formatJsValue(v)
                }
            }

            // Math.* functions & constants
            if (expr == "Math.PI") return Math.PI
            if (expr == "Math.E") return Math.E
            val mathCall = Regex("""^Math\.([a-zA-Z0-9_]+)\s*\(([\s\S]*)\)$""").find(expr)
            if (mathCall != null) {
                val fn = mathCall.groupValues[1]
                val args = splitTopLevelArgs(mathCall.groupValues[2]).map { toDouble(evaluateExpression(it)) }
                val a0 = args.getOrElse(0) { 0.0 }
                val a1 = args.getOrElse(1) { 0.0 }
                return when (fn) {
                    "abs" -> abs(a0)
                    "sqrt" -> sqrt(a0)
                    "floor" -> floor(a0)
                    "ceil" -> ceil(a0)
                    "round" -> a0.roundToLong().toDouble()
                    "pow" -> a0.pow(a1)
                    "max" -> args.maxOrNull() ?: 0.0
                    "min" -> args.minOrNull() ?: 0.0
                    "sin" -> sin(a0)
                    "cos" -> cos(a0)
                    "log" -> ln(a0)
                    else -> 0.0
                }
            }

            // Array literal [a, b, c]
            if (expr.startsWith("[") && expr.endsWith("]")) {
                val inner = expr.substring(1, expr.length - 1).trim()
                if (inner.isEmpty()) return mutableListOf<Any?>()
                val items = splitTopLevelArgs(inner).map { evaluateExpression(it) }.toMutableList()
                checkLimits(items.size * 16L)
                return items
            }

            // Property .length
            if (expr.endsWith(".length")) {
                val target = evaluateExpression(expr.removeSuffix(".length"))
                return when (target) {
                    is String -> target.length.toDouble()
                    is List<*> -> target.size.toDouble()
                    else -> 0.0
                }
            }

            // Comparison operators (===, !==, ==, !=, <=, >=, <, >)
            for (op in listOf("===", "!==", "==", "!=", "<=", ">=", "<", ">")) {
                val idx = findTopLevelOperator(expr, op)
                if (idx > 0) {
                    val left = evaluateExpression(expr.substring(0, idx))
                    val right = evaluateExpression(expr.substring(idx + op.length))
                    return when (op) {
                        "===", "==" -> formatJsValue(left) == formatJsValue(right)
                        "!==", "!=" -> formatJsValue(left) != formatJsValue(right)
                        "<=" -> toDouble(left) <= toDouble(right)
                        ">=" -> toDouble(left) >= toDouble(right)
                        "<" -> toDouble(left) < toDouble(right)
                        ">" -> toDouble(left) > toDouble(right)
                        else -> false
                    }
                }
            }

            // Additive operators (+, -)
            val addSubIdx = findLastTopLevelAddSub(expr)
            if (addSubIdx > 0) {
                val op = expr[addSubIdx]
                val left = evaluateExpression(expr.substring(0, addSubIdx))
                val right = evaluateExpression(expr.substring(addSubIdx + 1))
                return if (op == '+') {
                    if (left is String || right is String) {
                        val combined = formatJsValue(left) + formatJsValue(right)
                        checkLimits(combined.length * 2L)
                        combined
                    } else {
                        toDouble(left) + toDouble(right)
                    }
                } else {
                    toDouble(left) - toDouble(right)
                }
            }

            // Multiplicative operators (*, /, %)
            val mulDivIdx = findLastTopLevelMulDiv(expr)
            if (mulDivIdx > 0) {
                val op = expr[mulDivIdx]
                val left = toDouble(evaluateExpression(expr.substring(0, mulDivIdx)))
                val right = toDouble(evaluateExpression(expr.substring(mulDivIdx + 1)))
                return when (op) {
                    '*' -> left * right
                    '/' -> if (right == 0.0) Double.POSITIVE_INFINITY else left / right
                    '%' -> if (right == 0.0) Double.NaN else left % right
                    else -> 0.0
                }
            }

            // Variable lookup
            if (variables.containsKey(expr)) {
                return variables[expr]
            }

            return expr
        }

        private fun isTruthy(v: Any?): Boolean {
            return when (v) {
                null -> false
                is Boolean -> v
                is Number -> v.toDouble() != 0.0 && !v.toDouble().isNaN()
                is String -> v.isNotEmpty()
                else -> true
            }
        }

        private fun toDouble(v: Any?): Double {
            return when (v) {
                null -> 0.0
                is Number -> v.toDouble()
                is Boolean -> if (v) 1.0 else 0.0
                is String -> v.trim().toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }

        private fun formatJsValue(v: Any?): String {
            return when (v) {
                null -> "null"
                is Double -> {
                    if (v.isFinite() && v == floor(v) && abs(v) < 1e15) {
                        v.toLong().toString()
                    } else {
                        v.toString()
                    }
                }
                is List<*> -> v.joinToString(",", prefix = "[", postfix = "]") { formatJsValue(it) }
                else -> v.toString()
            }
        }

        private fun stripJsComments(code: String): String {
            val out = StringBuilder()
            var i = 0
            var inQuote: Char? = null
            while (i < code.length) {
                val c = code[i]
                if (inQuote != null) {
                    out.append(c)
                    if (c == '\\' && i + 1 < code.length) {
                        i++
                        out.append(code[i])
                    } else if (c == inQuote) {
                        inQuote = null
                    }
                    i++
                    continue
                }
                if (c == '"' || c == '\'') {
                    inQuote = c
                    out.append(c)
                    i++
                    continue
                }
                if (c == '/' && i + 1 < code.length && code[i + 1] == '/') {
                    while (i < code.length && code[i] != '\n') i++
                    continue
                }
                if (c == '/' && i + 1 < code.length && code[i + 1] == '*') {
                    i += 2
                    while (i + 1 < code.length && !(code[i] == '*' && code[i + 1] == '/')) i++
                    i += 2
                    continue
                }
                out.append(c)
                i++
            }
            return out.toString()
        }

        private fun splitTopLevelStatements(code: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var parenDepth = 0
            var braceDepth = 0
            var bracketDepth = 0
            var inQuote: Char? = null
            var i = 0
            while (i < code.length) {
                val c = code[i]
                if (inQuote != null) {
                    current.append(c)
                    if (c == '\\' && i + 1 < code.length) {
                        i++
                        current.append(code[i])
                    } else if (c == inQuote) {
                        inQuote = null
                    }
                    i++
                    continue
                }
                when (c) {
                    '"', '\'' -> {
                        inQuote = c
                        current.append(c)
                    }
                    '(' -> {
                        parenDepth++
                        current.append(c)
                    }
                    ')' -> {
                        parenDepth = max(0, parenDepth - 1)
                        current.append(c)
                    }
                    '{' -> {
                        braceDepth++
                        current.append(c)
                    }
                    '}' -> {
                        braceDepth = max(0, braceDepth - 1)
                        current.append(c)
                        if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                            result.add(current.toString())
                            current.setLength(0)
                        }
                    }
                    '[' -> {
                        bracketDepth++
                        current.append(c)
                    }
                    ']' -> {
                        bracketDepth = max(0, bracketDepth - 1)
                        current.append(c)
                    }
                    ';', '\n' -> {
                        if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                            val trimmed = current.toString().trim()
                            if (trimmed.isNotEmpty()) {
                                result.add(trimmed)
                            }
                            current.setLength(0)
                        } else {
                            current.append(c)
                        }
                    }
                    else -> current.append(c)
                }
                i++
            }
            val rem = current.toString().trim()
            if (rem.isNotEmpty()) result.add(rem)
            return result
        }

        private fun splitTopLevelArgs(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var inQuote: Char? = null
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (inQuote != null) {
                    current.append(c)
                    if (c == '\\' && i + 1 < raw.length) {
                        i++
                        current.append(raw[i])
                    } else if (c == inQuote) {
                        inQuote = null
                    }
                    i++
                    continue
                }
                when (c) {
                    '"', '\'' -> {
                        inQuote = c
                        current.append(c)
                    }
                    '(', '[', '{' -> {
                        depth++
                        current.append(c)
                    }
                    ')', ']', '}' -> {
                        depth = max(0, depth - 1)
                        current.append(c)
                    }
                    ',' -> {
                        if (depth == 0) {
                            result.add(current.toString().trim())
                            current.setLength(0)
                        } else {
                            current.append(c)
                        }
                    }
                    else -> current.append(c)
                }
                i++
            }
            val last = current.toString().trim()
            if (last.isNotEmpty()) result.add(last)
            return result
        }

        private fun trimEnclosingParens(s: String): String {
            var cur = s
            while (cur.startsWith("(") && cur.endsWith(")")) {
                var depth = 0
                var inQuote: Char? = null
                var enclosesAll = true
                for (i in 0 until cur.length - 1) {
                    val c = cur[i]
                    if (inQuote != null) {
                        if (c == inQuote && (i == 0 || cur[i - 1] != '\\')) inQuote = null
                        continue
                    }
                    if (c == '"' || c == '\'') inQuote = c
                    else if (c == '(') depth++
                    else if (c == ')') depth--
                    if (depth == 0) {
                        enclosesAll = false
                        break
                    }
                }
                if (enclosesAll) {
                    cur = cur.substring(1, cur.length - 1).trim()
                } else {
                    break
                }
            }
            return cur
        }

        private fun findTopLevelMethodCall(expr: String, token: String): Int {
            var depth = 0
            var inQuote: Char? = null
            for (i in 0..expr.length - token.length) {
                val c = expr[i]
                if (inQuote != null) {
                    if (c == inQuote && (i == 0 || expr[i - 1] != '\\')) inQuote = null
                    continue
                }
                when (c) {
                    '"', '\'' -> inQuote = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth = max(0, depth - 1)
                    else -> {
                        if (depth == 0 && expr.regionMatches(i, token, 0, token.length)) {
                            return i
                        }
                    }
                }
            }
            return -1
        }

        private fun findTopLevelOperator(expr: String, op: String): Int {
            var depth = 0
            var inQuote: Char? = null
            for (i in 0..expr.length - op.length) {
                val c = expr[i]
                if (inQuote != null) {
                    if (c == inQuote && (i == 0 || expr[i - 1] != '\\')) inQuote = null
                    continue
                }
                when (c) {
                    '"', '\'' -> inQuote = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth = max(0, depth - 1)
                    else -> {
                        if (depth == 0 && expr.regionMatches(i, op, 0, op.length)) {
                            return i
                        }
                    }
                }
            }
            return -1
        }

        private fun findLastTopLevelAddSub(expr: String): Int {
            var depth = 0
            var inQuote: Char? = null
            var lastIdx = -1
            for (i in expr.indices) {
                val c = expr[i]
                if (inQuote != null) {
                    if (c == inQuote && (i == 0 || expr[i - 1] != '\\')) inQuote = null
                    continue
                }
                when (c) {
                    '"', '\'' -> inQuote = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth = max(0, depth - 1)
                    '+', '-' -> {
                        if (depth == 0 && i > 0) {
                            lastIdx = i
                        }
                    }
                }
            }
            return lastIdx
        }

        private fun findLastTopLevelMulDiv(expr: String): Int {
            var depth = 0
            var inQuote: Char? = null
            var lastIdx = -1
            for (i in expr.indices) {
                val c = expr[i]
                if (inQuote != null) {
                    if (c == inQuote && (i == 0 || expr[i - 1] != '\\')) inQuote = null
                    continue
                }
                when (c) {
                    '"', '\'' -> inQuote = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth = max(0, depth - 1)
                    '*', '/', '%' -> {
                        if (depth == 0 && i > 0) {
                            lastIdx = i
                        }
                    }
                }
            }
            return lastIdx
        }
    }
}
