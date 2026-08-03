package com.listenai.describe.engine

import android.util.Log
import com.listenai.describe.llama.LlamaEngine
import com.listenai.describe.model.ModelKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-scoped owner of the native llama.cpp engine handle. Extracted
 * from DescribeActivity's former private `LlamaEngineHolder` object so
 * both the share-target Activity and the accessibility service can
 * safely call into the same warm engine instance.
 *
 * All load/free/describe calls are serialized through [mutex] — the
 * native handle is a single mutable resource; two concurrent describe
 * calls (e.g. one from a share-target describe, one from a screen-
 * reader "describe this screen" gesture) would corrupt it.
 *
 * Owned by [com.listenai.describe.DescribeApplication], one instance
 * per process.
 */
class LlamaEngineController : ImageDescriber {

    private val mutex = Mutex()

    @Volatile var handle: Long = 0L
        private set

    @Volatile var loadedKind: ModelKind? = null
        private set

    /**
     * Loads [kind]'s GGUFs if not already loaded. Frees any
     * previously-loaded different kind first. Returns true if the
     * engine is ready to describe after this call.
     *
     * MUST be called off the main thread — nativeLoadModels/
     * nativeFreeModels are both blocking native calls.
     */
    suspend fun ensureLoaded(kind: ModelKind, mmprojPath: String, textModelPath: String): Boolean =
        mutex.withLock {
            if (handle != 0L && loadedKind == kind) return@withLock true

            if (handle != 0L && loadedKind != kind) {
                Log.i(TAG, "ensureLoaded: switching from $loadedKind to $kind, freeing old handle")
                try {
                    LlamaEngine.nativeFreeModels(handle)
                } catch (t: Throwable) {
                    Log.w(TAG, "nativeFreeModels threw on switch", t)
                }
                handle = 0L
                loadedKind = null
            }

            val newHandle = try {
                LlamaEngine.nativeLoadModels(mmprojPath, textModelPath, kind.nCtx)
            } catch (t: Throwable) {
                Log.e(TAG, "nativeLoadModels threw", t)
                0L
            }
            handle = newHandle
            loadedKind = if (newHandle != 0L) kind else null
            newHandle != 0L
        }

    /**
     * Non-streaming describe. Suspends until the full description (or
     * an "(error: ...)" string) is returned. Caller must have already
     * confirmed [ensureLoaded] succeeded for the desired ModelKind.
     */
    override suspend fun describe(
        imageBytes: ByteArray,
        prompt: String,
        maxTokens: Int,
        chatTemplate: String,
    ): String = mutex.withLock {
        val h = handle
        if (h == 0L) return@withLock "(error: engine not loaded)"
        try {
            LlamaEngine.nativeDescribeImage(h, imageBytes, prompt, maxTokens, chatTemplate)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeDescribeImage threw", t)
            "(error: ${t.javaClass.simpleName}: ${t.message})"
        }
    }

    /**
     * Streaming describe — same serialization guarantee as [describe]:
     * the mutex is held for the whole blocking native call (the
     * callback fires from within it), so a second describe call queues
     * behind this one rather than racing on the shared handle.
     */
    suspend fun describeStream(
        imageBytes: ByteArray,
        prompt: String,
        maxTokens: Int,
        chatTemplate: String,
        callback: LlamaEngine.DescribeCallback,
    ) = mutex.withLock {
        val h = handle
        if (h == 0L) {
            callback.onError("engine not loaded")
            return@withLock
        }
        try {
            LlamaEngine.nativeDescribeImageStream(h, imageBytes, prompt, maxTokens, chatTemplate, callback)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeDescribeImageStream threw", t)
            callback.onError("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * M9: text-only completion (see LlamaEngine.nativeCompleteText) —
     * no image, no mtmd/vision path. Same mutex-serialization as
     * [describe]/[describeStream] since it's the same shared native
     * handle.
     */
    suspend fun completeText(prompt: String, maxTokens: Int, chatTemplate: String): String = mutex.withLock {
        val h = handle
        if (h == 0L) return@withLock "(error: engine not loaded)"
        try {
            LlamaEngine.nativeCompleteText(h, prompt, maxTokens, chatTemplate)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeCompleteText threw", t)
            "(error: ${t.javaClass.simpleName}: ${t.message})"
        }
    }

    suspend fun unload() = mutex.withLock {
        if (handle != 0L) {
            try {
                LlamaEngine.nativeFreeModels(handle)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeFreeModels threw on unload", t)
            }
        }
        handle = 0L
        loadedKind = null
    }

    companion object {
        private const val TAG = "LlamaEngineController"
    }
}
