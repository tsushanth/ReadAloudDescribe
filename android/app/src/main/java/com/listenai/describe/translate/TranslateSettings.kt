package com.listenai.describe.translate

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One entry in the target-language picker. ML Kit language codes (see com.google.mlkit.nl.translate.TranslateLanguage). */
data class TranslateLanguageOption(val code: String, val displayName: String)

/**
 * SharedPreferences-backed target-language setting, same pattern as
 * [com.listenai.describe.settings.DescribeSettings]. Source language
 * is always English for M10 — auto-detection is a possible later
 * addition, not needed to prove the pipeline.
 */
class TranslateSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _targetLanguage = MutableStateFlow(loadTargetLanguage())
    val targetLanguage: StateFlow<TranslateLanguageOption> = _targetLanguage.asStateFlow()

    fun setTargetLanguage(option: TranslateLanguageOption) {
        prefs.edit().putString(KEY_TARGET_LANGUAGE, option.code).apply()
        _targetLanguage.value = option
    }

    private fun loadTargetLanguage(): TranslateLanguageOption {
        val code = prefs.getString(KEY_TARGET_LANGUAGE, null)
        return LANGUAGE_OPTIONS.firstOrNull { it.code == code } ?: DEFAULT_LANGUAGE
    }

    companion object {
        private const val PREFS_NAME = "translate_settings"
        private const val KEY_TARGET_LANGUAGE = "target_language"

        val LANGUAGE_OPTIONS: List<TranslateLanguageOption> = listOf(
            TranslateLanguageOption("es", "Spanish"),
            TranslateLanguageOption("fr", "French"),
            TranslateLanguageOption("de", "German"),
            TranslateLanguageOption("zh", "Chinese"),
            TranslateLanguageOption("hi", "Hindi"),
            TranslateLanguageOption("ar", "Arabic"),
        )
        val DEFAULT_LANGUAGE = LANGUAGE_OPTIONS.first()

        @Volatile
        private var instance: TranslateSettings? = null

        fun getInstance(context: Context): TranslateSettings =
            instance ?: synchronized(this) {
                instance ?: TranslateSettings(context.applicationContext).also { instance = it }
            }
    }
}
