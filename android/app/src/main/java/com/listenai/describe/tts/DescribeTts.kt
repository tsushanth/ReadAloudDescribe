package com.listenai.describe.tts

import android.content.Context
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Thin wrapper around Android's TextToSpeech client API. Prefers
 * com.listenai.voice (our companion standalone TTS engine) when
 * installed, falls back to the device's system default TTS engine
 * otherwise. The user gets the voice they picked in ReadAloud Voice
 * automatically — no setup steps in this app.
 *
 * State flow has three values:
 *   Idle      → not currently speaking, ready to start
 *   Speaking  → utterance in progress (UI shows "Speaking...")
 *   Failed    → TTS init failed or engine couldn't speak
 *
 * Usage from a Compose Activity:
 *   val tts = remember { DescribeTts(context) }
 *   DisposableEffect(Unit) { onDispose { tts.shutdown() } }
 *   LaunchedEffect(description) {
 *       description?.let { tts.speak(it) }
 *   }
 */
class DescribeTts(private val context: Context) {

    sealed class State {
        object Idle : State()
        data class Speaking(val charsTotal: Int) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    init {
        // Pick engine: com.listenai.voice if installed, else system
        // default (passing null to the constructor). This is the same
        // pattern ReadAloud Voice's own benchmark activity uses.
        val engine = if (isPackageInstalled(VOICE_ENGINE_PACKAGE))
            VOICE_ENGINE_PACKAGE else null
        Log.i(TAG, "init engine=${engine ?: "(system default)"}")

        tts = TextToSpeech(context.applicationContext, { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed status=$status")
                _state.value = State.Failed("TTS init failed (status=$status)")
                return@TextToSpeech
            }
            ready = true
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    Log.i(TAG, "TTS onStart $id")
                }
                override fun onDone(id: String?) {
                    Log.i(TAG, "TTS onDone $id")
                    _state.value = State.Idle
                }
                @Suppress("OverridingDeprecatedMember")
                override fun onError(id: String?) {
                    Log.e(TAG, "TTS onError $id (deprecated overload)")
                    _state.value = State.Failed("TTS error")
                }
                override fun onError(id: String?, code: Int) {
                    Log.e(TAG, "TTS onError $id code=$code")
                    _state.value = State.Failed("TTS error code=$code")
                }
            })
            Log.i(TAG, "TTS ready, defaultEngine=${tts?.defaultEngine}")
            // If a speak() was queued before init finished, flush now.
            pendingText?.let { queued ->
                pendingText = null
                speak(queued)
            }
        }, engine)
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            // TTS init is async — buffer the most recent request and
            // play it once the listener fires.
            pendingText = text
            return
        }
        val id = "describe-${System.currentTimeMillis()}"
        _state.value = State.Speaking(charsTotal = text.length)
        val rc = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        Log.i(TAG, "speak() rc=$rc chars=${text.length} id=$id")
    }

    /**
     * Queue [chunk] AFTER any currently-speaking utterance. Use this
     * for streaming TTS: the first chunk uses speak(QUEUE_FLUSH) via
     * speakChunkFirst, subsequent ones append via this call. The
     * engine reads the queue in order so the user hears one continuous
     * description, not gaps between sentences.
     */
    fun speakChunkAppend(chunk: String) {
        if (chunk.isBlank() || !ready) return
        val id = "describe-chunk-${System.currentTimeMillis()}"
        val rc = tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
        Log.i(TAG, "speakChunkAppend rc=$rc chars=${chunk.length}")
    }

    fun speakChunkFirst(chunk: String) {
        if (chunk.isBlank() || !ready) return
        val id = "describe-chunk-${System.currentTimeMillis()}"
        _state.value = State.Speaking(charsTotal = chunk.length)
        val rc = tts?.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, id)
        Log.i(TAG, "speakChunkFirst rc=$rc chars=${chunk.length}")
    }

    fun stop() {
        tts?.stop()
        _state.value = State.Idle
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val TAG = "DescribeTts"
        const val VOICE_ENGINE_PACKAGE = "com.listenai.voice"
    }
}
