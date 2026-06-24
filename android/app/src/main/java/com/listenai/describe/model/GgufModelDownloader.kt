package com.listenai.describe.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Orchestrator around [GgufDownloadWorker]. Exposes a StateFlow the
 * UI can observe and a single startIfPossible() entry point.
 *
 * Mirrors the shape of ReadAloud Voice's KokoroModelDownloader so the
 * same patterns apply: idempotent enqueue, lazy auto-start when
 * inference is requested but the model isn't on disk, surface a
 * specific "why are we waiting" reason rather than the misleading
 * "Waiting for Wi-Fi" message.
 */
class GgufModelDownloader private constructor(
    private val context: Context,
) {
    sealed class State {
        object Idle : State()
        data class Waiting(val reason: String) : State()
        data class Downloading(
            val percent: Int,
            val downloadedMb: Int,
            val totalMb: Int,
            val fileIndex: Int,
            val fileCount: Int,
            val fileLabel: String,
        ) : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(if (areAllModelsOnDisk()) State.Ready else State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val mmprojFile: File
        get() = File(context.filesDir, GgufDownloadWorker.MMPROJ_FILE_NAME)

    val textModelFile: File
        get() = File(context.filesDir, GgufDownloadWorker.TEXT_MODEL_FILE_NAME)

    fun areAllModelsOnDisk(): Boolean {
        // Best-effort: just check the files exist and have non-trivial
        // size. Full GGUF-magic-number validation comes in Day 7 when
        // we actually load them via llama.cpp — a corrupt file will
        // surface as a load failure with a clear error then.
        val mmproj = mmprojFile
        val text = textModelFile
        return mmproj.exists() && mmproj.length() > 100L * 1024 * 1024 &&
            text.exists() && text.length() > 1000L * 1024 * 1024
    }

    fun startIfPossible() {
        if (areAllModelsOnDisk()) {
            Log.i(TAG, "startIfPossible: both models already on disk")
            _state.value = State.Ready
            return
        }

        Log.i(TAG, "startIfPossible: enqueuing GgufDownloadWorker (unmetered)")
        // For now: Wi-Fi only. No allow-cellular pref yet; users won't
        // want to download 1.9 GB over LTE accidentally. Add a settings
        // toggle in Day 8 once the rest of the app works.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            // Intentionally NO setRequiresBatteryNotLow(true) — this is
            // an explicit user-initiated download; Samsung/Pixel battery
            // throttling shouldn't block it. (Same rationale as ReadAloud
            // Voice's KokoroModelDownloader.)
            .build()

        val request = OneTimeWorkRequestBuilder<GgufDownloadWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            GgufDownloadWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        startObservingWorkState()
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(GgufDownloadWorker.WORK_NAME)
        _state.value = if (areAllModelsOnDisk()) State.Ready else State.Idle
    }

    private var observing = false

    private fun startObservingWorkState() {
        if (observing) return
        observing = true
        val liveData = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(GgufDownloadWorker.WORK_NAME)
        liveData.observeForever { infos ->
            val info = infos?.firstOrNull() ?: return@observeForever
            when (info.state) {
                WorkInfo.State.RUNNING -> {
                    val p = info.progress
                    val pct = p.getInt(GgufDownloadWorker.KEY_PERCENT, -1)
                    if (pct >= 0) {
                        _state.value = State.Downloading(
                            percent = pct,
                            downloadedMb = (p.getLong(GgufDownloadWorker.KEY_DOWNLOADED_BYTES, 0) / 1024 / 1024).toInt(),
                            totalMb = (p.getLong(GgufDownloadWorker.KEY_TOTAL_BYTES, 0) / 1024 / 1024).toInt(),
                            fileIndex = p.getInt(GgufDownloadWorker.KEY_FILE_INDEX, 1),
                            fileCount = p.getInt(GgufDownloadWorker.KEY_FILE_COUNT, 2),
                            fileLabel = p.getString(GgufDownloadWorker.KEY_FILE_LABEL) ?: "Model",
                        )
                    }
                }
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    _state.value = State.Waiting(currentWaitReason(context))
                }
                WorkInfo.State.SUCCEEDED -> {
                    _state.value = State.Ready
                }
                WorkInfo.State.FAILED -> {
                    val msg = info.outputData.getString(GgufDownloadWorker.KEY_ERROR)
                        ?: "Download failed"
                    _state.value = State.Failed(msg)
                }
                WorkInfo.State.CANCELLED -> {
                    _state.value = if (areAllModelsOnDisk()) State.Ready else State.Idle
                }
            }
        }
    }

    companion object {
        private const val TAG = "GgufModelDownloader"

        @Volatile
        private var instance: GgufModelDownloader? = null

        fun getInstance(context: Context): GgufModelDownloader {
            return instance ?: synchronized(this) {
                instance ?: GgufModelDownloader(context.applicationContext)
                    .also { instance = it }
            }
        }

        /**
         * Best-effort device-state inspection — same pattern as
         * ReadAloud Voice's KokoroModelDownloader. When WorkManager
         * parks the job in ENQUEUED/BLOCKED, surface the specific
         * cause rather than always saying "Waiting for Wi-Fi".
         */
        private fun currentWaitReason(context: Context): String {
            if (!isOnUnmeteredNetwork(context)) {
                return "Waiting for Wi-Fi — the 1.9 GB voice model will download as soon as you connect."
            }
            val bm = context.getSystemService(Context.BATTERY_SERVICE)
                as? android.os.BatteryManager
            val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct in 0..15) {
                return "Battery low ($pct%) — connect to a charger to start the download."
            }
            val pm = context.getSystemService(Context.POWER_SERVICE)
                as? android.os.PowerManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && pm != null) {
                val thermal = pm.currentThermalStatus
                if (thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
                    return "Device is overheating — download will start once it cools down."
                }
            }
            if (pm?.isPowerSaveMode == true) {
                return "Power-saving mode is on — turn it off to start the download."
            }
            return "System is queueing the download — should start in a few seconds."
        }

        private fun isOnUnmeteredNetwork(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
