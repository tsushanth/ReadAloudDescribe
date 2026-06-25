package com.listenai.describe.settings

import android.content.Context
import android.content.SharedPreferences
import com.listenai.describe.model.ModelKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny SharedPreferences-backed settings store for ReadAloud Describe.
 * The only setting today is which VLM the user wants to use. Exposed
 * as a StateFlow so the UI + DescribeActivity engine-loader both
 * react to changes without manual wiring.
 *
 * Singleton — one instance per process so all observers see the same
 * state. Persistence is synchronous (SharedPreferences.commit is
 * intentional here): the cost of losing the toggle setting across a
 * crash is worse than the tiny IO cost of commit.
 */
class DescribeSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _selectedModel = MutableStateFlow(loadSelectedModel())
    val selectedModel: StateFlow<ModelKind> = _selectedModel.asStateFlow()

    fun setSelectedModel(kind: ModelKind) {
        if (kind == _selectedModel.value) return
        prefs.edit().putString(KEY_SELECTED_MODEL, kind.name).apply()
        _selectedModel.value = kind
    }

    private fun loadSelectedModel(): ModelKind =
        ModelKind.fromName(prefs.getString(KEY_SELECTED_MODEL, null))

    companion object {
        private const val PREFS_NAME = "describe_settings"
        private const val KEY_SELECTED_MODEL = "selected_model"

        @Volatile
        private var instance: DescribeSettings? = null

        fun getInstance(context: Context): DescribeSettings {
            return instance ?: synchronized(this) {
                instance ?: DescribeSettings(context.applicationContext)
                    .also { instance = it }
            }
        }
    }
}
