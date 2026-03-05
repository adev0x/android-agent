package com.agentphone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * SpeechInputHandler — Phase 3
 *
 * Wraps Android's SpeechRecognizer into a single suspend function [listen].
 * Returns the transcribed text, or null if:
 *   - no speech was detected
 *   - the user cancelled
 *   - the recognizer returned an error
 *
 * Usage:
 *   val handler = SpeechInputHandler(context)
 *   val text = handler.listen(onPartial = { partial -> updateUI(partial) })
 *   handler.destroy()
 *
 * Threading:
 *   SpeechRecognizer must be created and used on the main thread.
 *   [listen] is a suspend function — call it from a coroutine dispatched to Main.
 *   [destroy] must also be called on the main thread when done.
 */
class SpeechInputHandler(private val context: Context) {

    companion object {
        private const val TAG = "SpeechInput"

        // Maximum time to wait for the recognizer to return a result.
        // SpeechRecognizer can stall if the system's recognition service hangs,
        // so we cap the total wait time to avoid blocking the caller indefinitely.
        private const val LISTEN_TIMEOUT_MS = 10_000L

        // Maps SpeechRecognizer error codes to human-readable strings
        private fun errorMessage(code: Int) = when (code) {
            SpeechRecognizer.ERROR_AUDIO              -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT             -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
            SpeechRecognizer.ERROR_NETWORK            -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT    -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH           -> "No speech matched"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY    -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER             -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT     -> "No speech detected"
            else                                      -> "Unknown error ($code)"
        }
    }

    private var recognizer: SpeechRecognizer? = null

    /**
     * Start listening for a spoken task.
     *
     * Must be called from the **main thread** (SpeechRecognizer requirement).
     *
     * @param onPartial  Called with partial transcription results as the user speaks.
     *                   Called on the main thread.
     * @param onState    Called with status updates ("Listening...", "Processing...").
     *                   Called on the main thread.
     *
     * @return The final transcription string, or null on failure/cancellation/timeout.
     */
    suspend fun listen(
        onPartial: (String) -> Unit = {},
        onState: (String) -> Unit = {}
    ): String? = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
        listenInternal(onPartial, onState)
    }.also { result ->
        if (result == null) Log.w(TAG, "listen() timed out after ${LISTEN_TIMEOUT_MS}ms")
    }

    private suspend fun listenInternal(
        onPartial: (String) -> Unit,
        onState: (String) -> Unit
    ): String? = suspendCancellableCoroutine { cont ->

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                onState("Listening...")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Volume level — not used but required by interface
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
                onState("Processing...")
            }

            override fun onError(error: Int) {
                val msg = errorMessage(error)
                Log.w(TAG, "Recognition error: $msg")
                // NO_MATCH and SPEECH_TIMEOUT are non-fatal — return null (user gets to retry)
                if (cont.isActive) cont.resume(null)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                Log.d(TAG, "Final result: \"$text\"")
                if (cont.isActive) cont.resume(text.takeIf { !it.isNullOrBlank() })
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                if (partial.isNotBlank()) {
                    Log.d(TAG, "Partial: \"$partial\"")
                    onPartial(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Prompt shown in the recognizer UI (some devices show it, most don't)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your task...")
        }

        sr.startListening(intent)

        cont.invokeOnCancellation {
            Log.d(TAG, "listenInternal cancelled — stopping recognizer")
            sr.stopListening()
            sr.destroy()
            recognizer = null
        }
    }

    /**
     * Stop any active listening session early.
     * Safe to call even if not currently listening.
     */
    fun stop() {
        recognizer?.stopListening()
    }

    /**
     * Release the SpeechRecognizer. Must be called when done (e.g. in onDestroy).
     * Must be called on the main thread.
     */
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
