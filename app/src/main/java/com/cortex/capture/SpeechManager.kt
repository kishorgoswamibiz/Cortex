package com.cortex.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Wraps Android's on-device [SpeechRecognizer] with a robust, single-instance
 * lifecycle. Common error codes (7/8/11) come from re-creating the recognizer
 * too aggressively or starting a second session before the first one had
 * finished — this class avoids both by holding one recogniser for the app's
 * lifetime and routing user taps through a small state machine:
 *
 *     Idle  -> Starting  -> Listening  -> Stopping  -> Finished
 *                                     `-> Error -> Idle
 *
 * Starting and Stopping cover the (small but real) gaps where the recogniser
 * is warming up or finalising the transcript. The UI uses them to show a
 * loader so the screen never appears frozen.
 */
class SpeechManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listenerInstalled = false
    private var busyRetries = 0

    private val _speechState = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (recognizer == null) {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                _speechState.value = SpeechState.Error("On-device speech recognition isn't available on this device.")
                return null
            }
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            listenerInstalled = false
        }
        if (!listenerInstalled) {
            recognizer?.setRecognitionListener(listener)
            listenerInstalled = true
        }
        return recognizer
    }

    fun startListening() {
        val r = ensureRecognizer() ?: return

        // If we're already in a session, cancel cleanly and try again on a tick —
        // never call startListening() twice back-to-back (that's what produces
        // ERROR_RECOGNIZER_BUSY = 8).
        val current = _speechState.value
        if (current is SpeechState.Listening || current is SpeechState.Starting || current is SpeechState.Stopping) {
            try { r.cancel() } catch (_: Throwable) {}
            mainHandler.postDelayed({ startListening() }, 120)
            return
        }

        _speechState.value = SpeechState.Starting
        busyRetries = 0
        try {
            r.startListening(buildIntent())
        } catch (e: Exception) {
            Log.e(TAG, "startListening threw", e)
            _speechState.value = SpeechState.Error("Couldn't start the microphone. Try again.")
        }
    }

    fun stopListening() {
        val r = recognizer ?: return
        // Two cases: we may be mid-utterance (Listening) or already buffering a
        // final result. Either way moving to Stopping is the right hint — the
        // recogniser will deliver onResults shortly.
        when (_speechState.value) {
            is SpeechState.Listening -> {
                _speechState.value = SpeechState.Stopping
                try { r.stopListening() } catch (_: Throwable) {}
            }
            is SpeechState.Starting -> {
                // User tapped to stop before we ever started receiving audio.
                try { r.cancel() } catch (_: Throwable) {}
                _speechState.value = SpeechState.Idle
            }
            else -> { /* no-op */ }
        }
    }

    fun reset() {
        try { recognizer?.cancel() } catch (_: Throwable) {}
        _speechState.value = SpeechState.Idle
    }

    fun enterReview(text: String) {
        try { recognizer?.cancel() } catch (_: Throwable) {}
        _speechState.value = SpeechState.Finished(text)
    }

    fun destroy() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        listenerInstalled = false
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Don't end on first pause — let the user finish a sentence.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _speechState.value = SpeechState.Listening(partialText = "")
        }
        override fun onBeginningOfSpeech() {
            val s = _speechState.value
            if (s is SpeechState.Listening) {
                _speechState.value = s.copy(partialText = s.partialText)
            } else {
                _speechState.value = SpeechState.Listening(partialText = "")
            }
        }
        override fun onRmsChanged(rmsdB: Float) {
            val s = _speechState.value
            if (s is SpeechState.Listening) _speechState.value = s.copy(rms = rmsdB)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            // The recogniser is now finalising. Surface that as Stopping so the
            // UI can show a loader instead of an empty-looking screen.
            val s = _speechState.value
            if (s is SpeechState.Listening) _speechState.value = SpeechState.Stopping
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Recogniser error: $error")
            // Transient: try once more silently before bothering the user.
            if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                 error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) && busyRetries < 1
            ) {
                busyRetries++
                mainHandler.postDelayed({
                    try { recognizer?.cancel() } catch (_: Throwable) {}
                    mainHandler.postDelayed({
                        _speechState.value = SpeechState.Starting
                        try { recognizer?.startListening(buildIntent()) } catch (_: Throwable) {
                            _speechState.value = SpeechState.Error(friendly(error))
                        }
                    }, 250)
                }, 100)
                return
            }
            // No-match while stopping: keep the partial we already have, if any.
            if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                val pending = (_speechState.value as? SpeechState.Listening)?.partialText.orEmpty()
                if (pending.isNotBlank()) {
                    _speechState.value = SpeechState.Finished(pending)
                    return
                }
                _speechState.value = SpeechState.Idle
                return
            }
            _speechState.value = SpeechState.Error(friendly(error))
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _speechState.value = SpeechState.Finished(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val s = _speechState.value
            if (s is SpeechState.Listening) _speechState.value = s.copy(partialText = text)
            else if (s is SpeechState.Starting) _speechState.value = SpeechState.Listening(partialText = text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun friendly(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK -> "No network for speech."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech took too long. Try again."
        SpeechRecognizer.ERROR_AUDIO -> "Couldn't read the mic. Try again."
        SpeechRecognizer.ERROR_SERVER -> "Speech server unreachable."
        SpeechRecognizer.ERROR_CLIENT -> "Speech client error. Try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything."
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic is still busy — try again in a second."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many speech requests. Wait a moment."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected. Try again."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "This language isn't supported."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language pack not installed."
        else -> "Speech recognizer error."
    }

    companion object {
        private const val TAG = "SpeechManager"
    }
}

sealed class SpeechState {
    object Idle : SpeechState()
    /** Recogniser is warming up — show a loader. */
    object Starting : SpeechState()
    data class Listening(val partialText: String, val rms: Float = 0f) : SpeechState()
    /** Recogniser is finalising the transcript — show a loader. */
    object Stopping : SpeechState()
    data class Finished(val text: String) : SpeechState()
    data class Error(val message: String) : SpeechState()
}
