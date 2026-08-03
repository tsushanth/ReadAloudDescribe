package com.listenai.describe.translate

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

sealed class TranslateResult {
    data class Success(val translatedText: String) : TranslateResult()
    data class Error(val message: String) : TranslateResult()
}

/**
 * On-device translation via ML Kit (see build.gradle.kts comment for
 * why this isn't a custom GGUF model). Source is always English for
 * M10. Downloads the target language pack on first use — Wi-Fi only,
 * matching the same bandwidth policy [com.listenai.describe.model.GgufModelDownloader]
 * uses for the describe models.
 */
class TranslateEngine {

    suspend fun translate(text: String, targetLanguageCode: String): TranslateResult {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetLanguageCode)
            .build()
        val translator = Translation.getClient(options)
        return try {
            val conditions = DownloadConditions.Builder().requireWifi().build()
            translator.downloadModelIfNeeded(conditions).await()
            val result = translator.translate(text).await()
            TranslateResult.Success(result)
        } catch (t: Throwable) {
            Log.e(TAG, "translate failed", t)
            TranslateResult.Error(t.message ?: "translation failed")
        } finally {
            translator.close()
        }
    }

    companion object {
        private const val TAG = "TranslateEngine"
    }
}
