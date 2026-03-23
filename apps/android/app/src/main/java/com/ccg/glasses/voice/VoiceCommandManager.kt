package com.ccg.glasses.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG = "VoiceCommand"

/**
 * Manages continuous voice command recognition for hands-free card interaction.
 *
 * Uses Android's [SpeechRecognizer] with partial results to detect keywords:
 *   - "approve" / "yes" -> confirm (approve action)
 *   - "reject" / "no" / "skip" -> dismiss (reject action)
 *   - "status" -> request current state
 *
 * On RayNeo X2, sets the audio source to the on-device voice assistant
 * microphone via AudioManager parameters. Automatically restarts listening
 * after each result for continuous operation.
 *
 * @param context Activity context (must be an Activity for SpeechRecognizer)
 * @param onCommand Callback invoked with the matched keyword string
 */
class VoiceCommandManager(
    private val context: Context,
    private val onCommand: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val keywords = setOf("approve", "yes", "reject", "no", "skip", "status")

    /**
     * Start continuous voice recognition.
     * Sets the RayNeo audio source to voice assistant mode and begins listening.
     */
    fun start() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }

        isListening = true

        // Route audio from the on-glasses microphone
        audioManager.setParameters("audio_source_record=voiceassistant")

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }

        startListening()
    }

    /**
     * Stop voice recognition and release resources.
     * Resets the audio source parameter.
     */
    fun stop() {
        if (!isListening) return
        isListening = false

        recognizer?.apply {
            cancel()
            destroy()
        }
        recognizer = null

        // Reset audio source
        audioManager.setParameters("audio_source_record=off")
    }

    private fun startListening() {
        if (!isListening) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
        }

        override fun onError(error: Int) {
            Log.d(TAG, "Recognition error: $error")
            // Restart listening on error (e.g. timeout, no match)
            if (isListening) {
                startListening()
            }
        }

        override fun onResults(results: Bundle?) {
            processResults(results)
            // Restart for continuous listening
            if (isListening) {
                startListening()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            processResults(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processResults(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return

        for (match in matches) {
            val words = match.lowercase().split("\\s+".toRegex())
            for (word in words) {
                if (word in keywords) {
                    Log.i(TAG, "Voice command: $word")
                    onCommand(word)
                    return
                }
            }
        }
    }
}
