package com.example.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Manages voice input via [SpeechRecognizer] for filling the chat composer
 * and voice output via [TextToSpeech] for the "Read replies aloud" setting.
 */
class RunwayVoiceManager(
    context: Context
) {
    private val appContext: Context = context.applicationContext

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false
    private var pendingSpeechText: String? = null

    private fun ensureTtsInitialized() {
        if (tts != null) return
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            _isSpeaking.value = false
                        }
                    })
                    pendingSpeechText?.let { text ->
                        pendingSpeechText = null
                        speakNowInternal(text)
                    }
                }
            }
        }
    }

    fun startListening(
        onPartialOrFinalText: (String, Boolean) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        startListening(
            existingDraft = "",
            onPartialResult = { partial -> onPartialOrFinalText(partial, false) },
            onResult = { finalResult -> onPartialOrFinalText(finalResult, true) },
            onError = onError
        )
    }

    /**
     * Starts listening for voice input via [SpeechRecognizer] and invokes [onResult]
     * with recognized speech to fill the composer text box.
     */
    fun startListening(
        existingDraft: String = "",
        onPartialResult: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        stopSpeaking()
        runCatching {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                onError("Speech recognition is not available on this device.")
                return
            }
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer = recognizer

            val prefix = existingDraft.trim().let { if (it.isEmpty()) "" else "$it " }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {
                    _isListening.value = true
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during speech recognition."
                        else -> "Voice input stopped."
                    }
                    onError(msg)
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val recognized = matches?.firstOrNull()?.trim().orEmpty()
                    if (recognized.isNotEmpty()) {
                        onResult(prefix + recognized)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()?.trim().orEmpty()
                    if (partial.isNotEmpty()) {
                        onPartialResult(prefix + partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            _isListening.value = true
            recognizer.startListening(intent)
        }.onFailure { err ->
            _isListening.value = false
            onError(err.message ?: "Unable to start speech recognizer.")
        }
    }

    fun stopListening() {
        _isListening.value = false
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        }
    }

    /**
     * Speaks an assistant reply aloud when "Read replies aloud" is enabled.
     */
    fun speakReply(rawMarkdown: String) {
        val cleanText = stripMarkdownForSpeech(rawMarkdown)
        if (cleanText.isBlank()) return
        ensureTtsInitialized()
        if (!isTtsReady) {
            pendingSpeechText = cleanText
            return
        }
        speakNowInternal(cleanText)
    }

    private fun speakNowInternal(cleanText: String) {
        runCatching {
            val engine = tts ?: return
            _isSpeaking.value = true
            val utteranceId = "runway_reply_${UUID.randomUUID()}"
            val maxLen = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1000)
            val clipped = if (cleanText.length > maxLen) cleanText.take(maxLen) else cleanText
            engine.speak(clipped, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }.onFailure {
            _isSpeaking.value = false
        }
    }

    /**
     * Immediately stops speaking (called when a message is sent or the chat is left).
     */
    fun stopSpeaking() {
        pendingSpeechText = null
        _isSpeaking.value = false
        runCatching {
            tts?.stop()
        }
    }

    fun shutdown() {
        stopListening()
        stopSpeaking()
        runCatching {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
        runCatching {
            tts?.shutdown()
            tts = null
            isTtsReady = false
        }
    }

    companion object {
        fun stripMarkdownForSpeech(rawMarkdown: String): String {
            if (rawMarkdown.isBlank()) return ""
            return rawMarkdown
                .replace(Regex("```[\\s\\S]*?```"), " Code block omitted. ")
                .replace(Regex("`([^`]+)`"), "$1")
                .replace(Regex("!\\[([^\\]]*)\\]\\([^)]+\\)"), "$1")
                .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
                .replace(Regex("(?m)^#{1,6}\\s+"), "")
                .replace(Regex("[*_~]{1,3}"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
