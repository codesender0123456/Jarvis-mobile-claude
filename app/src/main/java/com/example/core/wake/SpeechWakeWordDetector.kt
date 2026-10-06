package com.example.core.wake

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.math.min

/**
 * Listens for the wake phrase with Android's SpeechRecognizer and fires [onWake] when
 * [WakePhraseMatcher] accepts what it heard. This is real phrase recognition (not a volume gate).
 *
 * Honest limits: SpeechRecognizer decides where audio is processed. We ask for on-device
 * recognition (EXTRA_PREFER_OFFLINE), but if no offline language pack is installed the system
 * recogniser may use the network. A fully offline detector (openWakeWord/Porcupine) can replace
 * this class without touching the service. Some devices play a short sound on each restart.
 *
 * Must be driven from the main thread (the service does).
 */
class SpeechWakeWordDetector(
    private val context: Context,
    private val assistantName: () -> String,
    private val onWake: () -> Unit,
    private val onSpeechHeard: () -> Unit,
    private val onUnavailable: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var errorStreak = 0

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onUnavailable("This device has no speech recognition service, so wake word cannot work.")
            return
        }
        running = true
        errorStreak = 0
        listen()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    private fun listen() {
        if (!running) return
        recognizer?.let { runCatching { it.destroy() } }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onBeginningOfSpeech() { onSpeechHeard() }
            override fun onPartialResults(partialResults: Bundle?) = check(partialResults)
            override fun onResults(results: Bundle?) {
                check(results)
                errorStreak = 0
                restartSoon(300)
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    stop(); onUnavailable("Microphone permission is missing for wake word."); return
                }
                // NO_MATCH / SPEECH_TIMEOUT are normal when nobody speaks; only real faults back off.
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (!quiet) errorStreak++ else errorStreak = 0
                restartSoon(min(8_000L, 300L * (1 + errorStreak * errorStreak)))
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { r.startListening(intent) }.onFailure {
            Log.w(TAG, "startListening failed", it); errorStreak++; restartSoon(2_000)
        }
    }

    private fun check(bundle: Bundle?) {
        if (!running) return
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (heard.any { WakePhraseMatcher.matches(it, assistantName()) }) {
            stop()
            onWake()
        }
    }

    private fun restartSoon(delayMs: Long) {
        if (!running) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listen() }, delayMs)
    }

    private companion object { const val TAG = "SpeechWakeWord" }
}
