package com.akash.aside.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Android's own speech recognition, listening once. Words arrive as they are recognised; when the person stops
 * speaking the final text arrives. Must be used on the main thread.
 */
class VoiceInput(private val ctx: Context, private val listener: Listener) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** `nothingHeard` is true when the person simply did not say anything usable. */
        fun onError(message: String, nothingHeard: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    val listening get() = recognizer != null

    fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            listener.onError("Speech recognition is not available on this phone", false)
            return false
        }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partial: Bundle?) {
                first(partial)?.let(listener::onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = first(results)
                finish()
                if (text.isNullOrBlank()) listener.onError("Didn't catch that", true) else listener.onFinal(text)
            }

            override fun onError(error: Int) {
                finish()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.onError("Didn't catch that", true)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.onError("Allow the microphone for Aside first", false)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> listener.onError("Speech recognition needs a connection", false)
                    else -> listener.onError("Speech recognition stopped (code $error)", false)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = r
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        )
        return true
    }

    fun stop() = finish()

    private fun finish() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    private fun first(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
