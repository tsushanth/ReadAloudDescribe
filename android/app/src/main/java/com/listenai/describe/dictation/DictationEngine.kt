package com.listenai.describe.dictation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.listenai.describe.engine.LlamaEngineController
import com.listenai.describe.settings.DescribeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

sealed class DictationResult {
    data class Success(val cleanedText: String, val rawTranscript: String) : DictationResult()
    object PermissionMissing : DictationResult()
    object NoSpeechRecognized : DictationResult()
    data class Error(val message: String) : DictationResult()
}

/**
 * Speech-to-text capture via Android's system SpeechRecognizer (no
 * on-device ASR model bundled — that's a distinct concern from the
 * describe engine's VLM), followed by an on-device LLM cleanup pass
 * using the M9 [LlamaEngineController.completeText] addition
 * (punctuation, filler-word removal). This is the "natural reuse
 * point tying dictation back to the on-device differentiator infra"
 * called out in the project plan.
 */
class DictationEngine(private val context: Context) {

    suspend fun captureAndClean(engineController: LlamaEngineController): DictationResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return DictationResult.PermissionMissing
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return DictationResult.Error("No speech recognizer available on this device.")
        }
        // Must match whichever model is actually loaded in the shared
        // handle, not necessarily the current settings selection — those
        // can differ if the user switched models without reopening the
        // app to trigger a reload. Falls back to the settings selection
        // only if nothing is loaded yet (completeText will then itself
        // return "(error: engine not loaded)").
        val chatTemplate = (engineController.loadedKind ?: DescribeSettings.getInstance(context).selectedModel.value)
            .chatTemplate

        val raw = captureTranscript()
        if (raw.isNullOrBlank()) return DictationResult.NoSpeechRecognized
        val cleaned = engineController.completeText(
            prompt = DictationPrompts.cleanupPrompt(raw),
            maxTokens = 120,
            chatTemplate = chatTemplate,
        )
        if (cleaned.startsWith("(error:")) {
            // Cleanup failed — fall back to the raw transcript rather
            // than losing the user's dictation entirely.
            Log.w(TAG, "completeText failed ($cleaned), falling back to raw transcript")
            return DictationResult.Success(cleanedText = raw, rawTranscript = raw)
        }
        return DictationResult.Success(cleanedText = cleaned.trim(), rawTranscript = raw)
    }

    /** SpeechRecognizer must be created/driven from the main looper. */
    private suspend fun captureTranscript(): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            var resumed = false
            fun finish(result: String?) {
                if (resumed) return
                resumed = true
                recognizer.destroy()
                if (cont.isActive) cont.resume(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    finish(matches?.firstOrNull())
                }
                override fun onError(error: Int) {
                    Log.w(TAG, "SpeechRecognizer error=$error")
                    finish(null)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.startListening(intent)

            cont.invokeOnCancellation { recognizer.destroy() }
        }
    }

    companion object {
        private const val TAG = "DictationEngine"
    }
}
