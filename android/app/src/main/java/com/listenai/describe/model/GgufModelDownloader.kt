package com.listenai.describe.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
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
 * Orchestrator around [GgufDownloadWorker], scoped to a single
 * [ModelKind]. Exposes a StateFlow the UI can observe and a single
 * startIfPossible() entry point.
 *
 * One instance per ModelKind (cached in [instances]), so the user can
 * have Moondream2 already on disk while SmolVLM2 is downloading
 * without one downloader's state overwriting the other's.
 *
 * Mirrors the shape of ReadAloud Voice's KokoroModelDownloader — same
 * resumable-download + foreground-promotion + specific "why are we
 * waiting" reason pattern.
 */
class GgufModelDownloader private constructor(
    private val context: Context,
    private val kind: ModelKind,
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

    private val _state = MutableStateFlow<State>(
        if (areAllModelsOnDisk()) State.Ready else State.Idle
    )
    val state: StateFlow<State> = _state.asStateFlow()

    val mmprojFile: File
        get() = File(context.filesDir, kind.mmprojFileName)

    val textModelFile: File
        get() = File(context.filesDir, kind.textFileName)

    fun areAllModelsOnDisk(): Boolean {
        // Best-effort: just check the files exist and have non-trivial
        // size matching this kind's expected range. Full GGUF-magic-
        // number validation happens at llama.cpp load time — a corrupt
        // file will surface as a load failure with a clear error.
        val mmproj = mmprojFile
        val text = textModelFile
        return mmproj.exists() && mmproj.length() >= kind.expectedMmprojMinBytes &&
            text.exists() && text.length() >= kind.expectedTextMinBytes
    }

    fun startIfPossible() {
        if (areAllModelsOnDisk()) {
            Log.i(TAG, "startIfPossible[${kind.name}]: both models already on disk")
            _state.value = State.Ready
            return
        }

        Log.i(TAG, "startIfPossible[${kind.name}]: enqueuing GgufDownloadWorker (unmetered)")
        // Wi-Fi only — users don't want to burn 1.9 GB over LTE
        // accidentally. (Same rationale as KokoroModelDownloader.)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val request = OneTimeWorkRequestBuilder<GgufDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                Data.Builder()
                    .putString(GgufDownloadWorker.KEY_MODEL_KIND, kind.name)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            GgufDownloadWorker.workName(kind),
            ExistingWorkPolicy.KEEP,
            request,
        )
        startObservingWorkState()
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(GgufDownloadWorker.workName(kind))
        _state.value = if (areAllModelsOnDisk()) State.Ready else State.Idle
    }

    private var observing = false

    private fun startObservingWorkState() {
        if (observing) return
        observing = true
        val liveData = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(GgufDownloadWorker.workName(kind))
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
                    _state.value = State.Waiting(currentWaitReason(context, kind))
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

        private val instances = mutableMapOf<ModelKind, GgufModelDownloader>()
        private val lock = Any()

        fun getInstance(context: Context, kind: ModelKind): GgufModelDownloader {
            synchronized(lock) {
                instances[kind]?.let { return it }
                val created = GgufModelDownloader(context.applicationContext, kind)
                instances[kind] = created
                return created
            }
        }

        /**
         * Best-effort device-state inspection — same pattern as
         * ReadAloud Voice's KokoroModelDownloader. When WorkManager
         * parks the job in ENQUEUED/BLOCKED, surface the specific
         * cause rather than always saying "Waiting for Wi-Fi".
         */
        private fun currentWaitReason(context: Context, kind: ModelKind): String {
            if (!isOnUnmeteredNetwork(context)) {
                return "Waiting for Wi-Fi — the ${kind.sizeLabel} model will download as soon as you connect."
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
