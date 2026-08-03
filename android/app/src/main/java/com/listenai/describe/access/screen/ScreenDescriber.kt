package com.listenai.describe.access.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import com.listenai.describe.DescribeApplication
import com.listenai.describe.engine.DescribePrompts
import com.listenai.describe.model.GgufModelDownloader
import com.listenai.describe.model.ModelKind
import com.listenai.describe.settings.DescribeSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * "Describe this screen" — the AI-describe gesture (M5). Screenshot →
 * on-device VLM → hedged speech. Deliberately opt-in-per-invocation
 * only; never wired to fire automatically on focus/hover events (see
 * class doc on [com.listenai.describe.engine.DescribePrompts] for why:
 * documented hallucination risk on text-dense/sensitive content).
 * Standard node-by-node navigation elsewhere in this package never
 * calls this — ground-truth AccessibilityNodeInfo text stays the only
 * source of truth for on-screen text.
 */
class ScreenDescriber(private val service: AccessibilityService) {

    /**
     * Runs the full pipeline and returns a ready-to-speak utterance
     * (already hedged, sanitized) or an explanatory message on
     * failure ("model not downloaded yet", "requires Android 11+",
     * etc — these are meant to be spoken too, so the user isn't left
     * with silence).
     */
    suspend fun describeCurrentScreen(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Describing the screen needs Android 11 or newer."
        }

        val kind = DescribeSettings.getInstance(service).selectedModel.value
        val downloader = GgufModelDownloader.getInstance(service, kind)
        if (!downloader.areAllModelsOnDisk()) {
            return "The describe model isn't downloaded yet. Open ReadAloud Describe to download it."
        }

        val bitmap = captureScreenshot() ?: return "Couldn't capture the screen."
        val jpegBytes = try {
            toJpegBytes(bitmap)
        } finally {
            bitmap.recycle()
        }

        val controller = DescribeApplication.engineController(service)
        val loaded = controller.ensureLoaded(kind, downloader.mmprojFile.absolutePath, downloader.textModelFile.absolutePath)
        if (!loaded) {
            return "Couldn't load the describe engine — check logcat."
        }

        val raw = controller.describe(
            imageBytes = jpegBytes,
            prompt = DescribePrompts.SCREEN_UI_PROMPT,
            maxTokens = 120,
            chatTemplate = kind.chatTemplate,
        )
        if (raw.startsWith("(error:")) return raw

        return hedge(sanitize(raw))
    }

    private suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            { it.run() }, // run inline — callback just resumes the continuation, thread-safe
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (t: Throwable) {
                        Log.e(TAG, "takeScreenshot: bitmap conversion failed", t)
                        null
                    } finally {
                        result.hardwareBuffer.close()
                    }
                    cont.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed, errorCode=$errorCode")
                    cont.resume(null)
                }
            },
        )
    }

    private fun toJpegBytes(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }

    /**
     * Same whitespace/control-char cleanup DescribeActivity applies to
     * photo descriptions before TTS — the VLM's byte-BPE tokenizer
     * occasionally emits NBSP/zero-width chars that make TTS
     * phonemizers stutter.
     */
    private fun sanitize(text: String): String = text
        .replace(Regex("[\\u00A0\\u2000-\\u200F\\u202F\\u205F\\u3000]"), " ")
        .replace(Regex("[\\p{Cntrl}&&[^\\n\\r\\t]]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Epistemic hedging per the project's own hallucination findings
     * (PROJECT_PLAN.md decision log: invented a passport DOB, once
     * hallucinated UI text). This is a supplementary layout summary,
     * never a transcription — phrase it as an impression, not a fact.
     */
    private fun hedge(text: String): String =
        if (text.isBlank()) "Couldn't make out the screen layout." else "This may show: $text"

    companion object {
        private const val TAG = "ScreenDescriber"
    }
}
