package com.example.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Manages Speech-to-Text input via [SpeechRecognizer] and Text-to-Speech output via [TextToSpeech].
 */
class VoiceSpeechManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                isTtsInitialized = true
            }
        }
    }

    /**
     * Starts listening for voice input, calling [onResult] with partial or final recognized text,
     * and [onError] if speech recognition fails or completes.
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        stopListening()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available on device")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error in speech recognition"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Audio permission required"
                    else -> "Speech recognition error ($error)"
                }
                onError(msg)
                stopListening()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognized = matches?.firstOrNull()?.trim().orEmpty()
                if (recognized.isNotEmpty()) {
                    onResult(recognized)
                }
                stopListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = partial?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    onResult(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Failed to start speech recognizer")
            stopListening()
        }
    }

    fun stopListening() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    /**
     * Reads aloud the assistant text if TTS is available.
     */
    fun speak(text: String) {
        if (!isTtsInitialized || text.isBlank()) return
        stopSpeaking()

        // Clean out markdown symbols and code blocks for clearer listening
        val cleaned = text
            .replace(Regex("```[\\s\\S]*?```"), " [code block omitted] ")
            .replace(Regex("[#*_`~]"), "")
            .trim()

        textToSpeech?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, "runway_assistant_reply")
    }

    /**
     * Stops current text-to-speech reading.
     */
    fun stopSpeaking() {
        runCatching {
            textToSpeech?.stop()
        }
    }

    fun release() {
        stopListening()
        stopSpeaking()
        runCatching {
            textToSpeech?.shutdown()
        }
        textToSpeech = null
    }
}
