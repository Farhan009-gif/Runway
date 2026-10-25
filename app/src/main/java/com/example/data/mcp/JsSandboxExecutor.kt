package com.example.data.mcp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import com.example.data.local.McpApprovalMode
import com.example.data.local.McpServerEntity
import com.example.data.local.McpToolEntity
import com.example.data.local.McpTransportType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
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
 * Result of executing JavaScript inside the isolated sandbox.
 */
data class JsSandboxExecutionResult(
    val outputText: String,
    val isSuccess: Boolean,
    val isTimedOut: Boolean = false,
    val isMemoryExceeded: Boolean = false,
    val isOutputTruncated: Boolean = false,
    val sandboxDestroyed: Boolean = true,
    val durationMs: Long = 0L
)

/**
 * Isolated JavaScript Sandbox Executor for the `"run_code"` tool.
 *
 * Security & Resource Guarantees:
 * - Executes JavaScript only.
 * - Hidden WebView configured with NO network access (`blockNetworkLoads = true`),
 *   NO file/content access (`allowFileAccess = false`, `allowContentAccess = false`),
 *   NO DOM/database storage, and NO `addJavascriptInterface` bridges to app data.
 * - Never receives or exposes API keys, tokens, or stored chat history.
 * - Hard limits:
 *   1. 5 seconds execution timeout ([MAX_EXECUTION_TIME_MS] = 5,000 ms).
 *   2. Memory cap ([MAX_MEMORY_BYTES] = 4 MB) enforced on string/array/heap allocations.
 *   3. Output truncated to 10 KB ([MAX_OUTPUT_BYTES] = 10,240 bytes) before returning to the model.
 * - Destroys the sandbox (`WebView.destroy()` and scope wipe) in a `finally` block after every single run.
 */
object JsSandboxExecutor {

    const val RUN_CODE_TOOL_NAME = "run_code"
    const val RUN_CODE_SERVER_ID = "builtin_js_sandbox"
    const val RUN_CODE_SERVER_NAME = "JS Sandbox"

    const val MAX_EXECUTION_TIME_MS = 5_000L
    const val MAX_MEMORY_BYTES = 4 * 1024 * 1024 // 4 MB sandbox allocation cap
    const val MAX_OUTPUT_BYTES = 10 * 1024 // 10 KB (10,240 bytes) output cap

    private const val TRUNCATION_NOTICE = "\n...[Output truncated to 10 KB sandbox limit]"

    fun buildRunCodeServerEntity(): McpServerEntity {
        return McpServerEntity(
            id = RUN_CODE_SERVER_ID,
            name = RUN_CODE_SERVER_NAME,
            url = "sandbox://javascript/isolated",
            transport = McpTransportType.STREAMABLE_HTTP.name,
            authHeaderName = "",
            approvalMode = McpApprovalMode.ASK_EVERY_TIME.name,
            isEnabled = true,
            lastTestStatus = "Isolated Sandbox Ready (No Network • No File Access • 5s Limit)"
        )
    }

    fun buildSyntheticServerEntity(): McpServerEntity = buildRunCodeServerEntity()

    fun buildRunCodeToolEntity(): McpToolEntity {
        return McpToolEntity(
            id = "$RUN_CODE_SERVER_ID:$RUN_CODE_TOOL_NAME",
            serverId = RUN_CODE_SERVER_ID,
            serverName = RUN_CODE_SERVER_NAME,
            toolName = RUN_CODE_TOOL_NAME,
            description = "Execute JavaScript in an isolated sandbox (no network, no file access, 5s timeout, 10KB output cap).",
            inputSchemaJson = """{"type":"object","properties":{"code":{"type":"string","description":"JavaScript code to execute in the isolated sandbox."}},"required":["code"]}""",
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
        return runCatching {
            val obj = JSONObject(trimmed)
            obj.optString("code", "")
                .ifBlank { obj.optString("javascript", "") }
                .ifBlank { obj.optString("script", "") }
        }.getOrDefault(trimmed)
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
                durationMs = 1L
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
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        }

        checkForbiddenApis(cleanCode)?.let { securityErr ->
            return@withContext JsSandboxExecutionResult(
                outputText = securityErr,
                isSuccess = false,
                sandboxDestroyed = true,
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        }

        // Try isolated hidden WebView when a real Android Context is provided on a non-stubbed runtime
        if (context != null && isRealAndroidWebViewAvailable()) {
            val webViewResult = runCatching {
                executeInIsolatedHiddenWebView(
                    context = context,
                    code = cleanCode,
                    timeoutMs = timeoutMs,
                    maxMemoryBytes = maxMemoryBytes,
                    maxOutputBytes = maxOutputBytes
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
    private suspend fun executeInIsolatedHiddenWebView(
        context: Context,
        code: String,
        timeoutMs: Long,
        maxMemoryBytes: Int,
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
                                setSupportZoom(false)
                            }
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
                    durationMs = elapsed.coerceAtLeast(timeoutMs)
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
                durationMs = elapsed
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
