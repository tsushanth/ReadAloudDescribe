package com.listenai.describe.access.speech

import android.util.Log
import com.listenai.describe.tts.DescribeTts

/**
 * Thin routing layer over [DescribeTts] for the accessibility
 * service's "landed on a new node, speak it" flow. DescribeTts.speak()
 * already uses QUEUE_FLUSH (interrupt-and-replace), which is exactly
 * the semantics navigation needs — new focus should cut off whatever
 * was being read before. The only thing added here is de-duping
 * identical consecutive utterances, since some accessibility event
 * types fire more than once per user action and would otherwise
 * restart the same utterance mid-speech.
 */
class SpeechQueue(private val tts: DescribeTts) {
    private var lastSpoken: String? = null

    fun speak(utterance: String) {
        if (utterance == lastSpoken) return
        lastSpoken = utterance
        Log.d(TAG, "speak: \"$utterance\"")
        tts.speak(utterance)
    }

    companion object {
        private const val TAG = "SpeechQueue"
    }

    fun reset() {
        lastSpoken = null
    }
}
