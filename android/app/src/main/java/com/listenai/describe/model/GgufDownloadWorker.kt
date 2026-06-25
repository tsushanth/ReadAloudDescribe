package com.listenai.describe.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * WorkManager Worker for the two-file GGUF download.
 *
 * Each file is downloaded with byte-range resume support so a dropped
 * connection or process kill mid-download doesn't restart from scratch.
 * Progress is published every ~250ms via setProgress() so the UI can
 * render a smooth bar.
 *
 * Foreground promotion uses dataSync type (Android 14 requirement) and
 * shows an ongoing notification with the current file + percent.
 */
class GgufDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Which model to download is passed via input data so the
            // user can pick between (currently) Moondream2 and SmolVLM2
            // and each gets its own unique-work-name + own GGUF files
            // on disk. Default to Moondream2 if no kind is set so the
            // pre-toggle code paths still work.
            val kind = ModelKind.fromName(inputData.getString(KEY_MODEL_KIND))
            setForeground(createForegroundInfo("Preparing ${kind.displayName} model…", 0))

            val targets = listOf(
                FileTarget(
                    url = kind.mmprojUrl,
                    fileName = kind.mmprojFileName,
                    expectedMinBytes = kind.expectedMmprojMinBytes,
                    expectedMaxBytes = kind.expectedMmprojMaxBytes,
                    expectedSha256 = kind.mmprojSha256,
                    label = "${kind.displayName} vision model",
                ),
                FileTarget(
                    url = kind.textUrl,
                    fileName = kind.textFileName,
                    expectedMinBytes = kind.expectedTextMinBytes,
                    expectedMaxBytes = kind.expectedTextMaxBytes,
                    expectedSha256 = kind.textSha256,
                    label = "${kind.displayName} language model",
                ),
            )

            // Total bytes across both files — for overall progress.
            // Headcount via HEAD before we start so the percent is honest.
            val client = newClient()
            var grandTotal = 0L
            val sizes = mutableMapOf<String, Long>()
            for (t in targets) {
                val size = headSize(client, t.url)
                sizes[t.fileName] = size
                grandTotal += size
                Log.i(TAG, "HEAD ${t.fileName} = ${size / 1024 / 1024} MB")
            }

            var grandDownloaded = 0L
            for ((index, t) in targets.withIndex()) {
                val dest = File(applicationContext.filesDir, t.fileName)
                val expectedSize = sizes[t.fileName] ?: t.expectedMinBytes

                if (dest.exists() && dest.length() == expectedSize) {
                    Log.i(TAG, "${t.fileName} already complete (${dest.length()} bytes)")
                    grandDownloaded += dest.length()
                    continue
                }
                // If a previous failed attempt left a file LARGER than
                // the expected size (e.g. HEAD returned a wrong value
                // earlier and we wrote junk past the real EOF), our
                // byte-range resume would set Range: bytes=<too-large>-
                // and the server would 416. Truncate to 0 and re-fetch
                // from scratch.
                if (dest.exists() && dest.length() > expectedSize) {
                    Log.w(TAG, "${t.fileName} on disk (${dest.length()}) > expected ($expectedSize); truncating + restarting")
                    dest.delete()
                }

                downloadOne(
                    client = client,
                    target = t,
                    dest = dest,
                    expectedSize = expectedSize,
                    fileIndex = index + 1,
                    fileCount = targets.size,
                    grandTotalBefore = grandDownloaded,
                    grandTotal = grandTotal,
                )
                grandDownloaded += dest.length()
            }

            Log.i(TAG, "all downloads complete (${grandDownloaded / 1024 / 1024} MB total)")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "download failed: ${t.message}", t)
            Result.failure(Data.Builder().putString(KEY_ERROR, t.message ?: "unknown").build())
        }
    }

    private suspend fun downloadOne(
        client: OkHttpClient,
        target: FileTarget,
        dest: File,
        expectedSize: Long,
        fileIndex: Int,
        fileCount: Int,
        grandTotalBefore: Long,
        grandTotal: Long,
    ) {
        val partial = dest.exists()
        val startByte = if (partial) dest.length() else 0L
        Log.i(TAG, "GET ${target.fileName} from=$startByte expected=$expectedSize")

        val reqBuilder = Request.Builder().url(target.url)
        if (partial && startByte < expectedSize) {
            reqBuilder.addHeader("Range", "bytes=$startByte-")
        }

        val response = client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw RuntimeException("HTTP ${response.code} for ${target.fileName}")
        }
        val body = response.body ?: throw RuntimeException("empty body for ${target.fileName}")

        val raf = RandomAccessFile(dest, "rw")
        try {
            raf.seek(startByte)
            val source = body.byteStream()
            val buf = ByteArray(256 * 1024)
            var written = startByte
            var lastReport = System.currentTimeMillis()
            while (true) {
                val n = source.read(buf)
                if (n <= 0) break
                raf.write(buf, 0, n)
                written += n

                val now = System.currentTimeMillis()
                if (now - lastReport > 250) {
                    val pct = ((grandTotalBefore + (written - startByte)) * 100 / grandTotal).toInt().coerceIn(0, 100)
                    val mb = written / 1024 / 1024
                    val totalMb = expectedSize / 1024 / 1024
                    setProgress(
                        Data.Builder()
                            .putInt(KEY_PERCENT, pct)
                            .putLong(KEY_DOWNLOADED_BYTES, grandTotalBefore + (written - startByte))
                            .putLong(KEY_TOTAL_BYTES, grandTotal)
                            .putInt(KEY_FILE_INDEX, fileIndex)
                            .putInt(KEY_FILE_COUNT, fileCount)
                            .putString(KEY_FILE_LABEL, target.label)
                            .build()
                    )
                    setForeground(
                        createForegroundInfo(
                            "${target.label} ($fileIndex of $fileCount): $mb / $totalMb MB",
                            pct,
                        )
                    )
                    lastReport = now
                }
            }
        } finally {
            raf.close()
            response.close()
        }
        Log.i(TAG, "${target.fileName} done, ${dest.length()} bytes (expected $expectedSize)")

        // SHA-256 verification — guards against the silent-corruption
        // failure mode that bit one tester's Samsung (mmproj file was
        // partially overwritten, llama.cpp crashed natively at load
        // with no obvious cause). Re-reading 1 GB on a phone is ~3–5 s
        // of additional IO at the end of a multi-minute download —
        // a fair price for a confident "the file you just downloaded
        // is byte-identical to what we published" guarantee.
        // Empty expectedSha256 = skip (defensive; should never happen
        // in production since ModelKind hardcodes them).
        if (target.expectedSha256.isNotBlank()) {
            setForeground(createForegroundInfo("Verifying ${target.label}…", 100))
            val actual = sha256OfFile(dest)
            if (!actual.equals(target.expectedSha256, ignoreCase = true)) {
                Log.w(TAG, "${target.fileName} SHA mismatch — expected ${target.expectedSha256}, got $actual; deleting")
                dest.delete()
                throw RuntimeException(
                    "Downloaded ${target.label} is corrupted (SHA mismatch). " +
                        "It will be re-downloaded next time."
                )
            }
            Log.i(TAG, "${target.fileName} SHA verified")
        }
    }

    /**
     * Streaming SHA-256 of a file. Reads in 256 KB chunks so a 1 GB
     * file uses constant memory. Returns lowercase hex.
     */
    private fun sha256OfFile(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { stream ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val bytes = md.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }

    // ----------------------------------------------------------------
    // HTTP helpers
    // ----------------------------------------------------------------
    private fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // no overall ceiling for big files
        .followRedirects(true)
        .build()

    private fun headSize(client: OkHttpClient, url: String): Long {
        val response = client.newCall(Request.Builder().url(url).head().build()).execute()
        response.use {
            val length = it.header("Content-Length")?.toLongOrNull() ?: 0L
            return length
        }
    }

    // ----------------------------------------------------------------
    // Foreground notification
    // ----------------------------------------------------------------
    private fun createForegroundInfo(caption: String, percent: Int): ForegroundInfo {
        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            mgr.getNotificationChannel(CHANNEL_ID) == null
        ) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Voice model download",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while ReadAloud Describe is downloading its AI model."
                    setShowBadge(false)
                }
            )
        }

        val openIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.let {
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ReadAloud Describe")
            .setContentText(caption)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private data class FileTarget(
        val url: String,
        val fileName: String,
        val expectedMinBytes: Long,
        val expectedMaxBytes: Long,
        val expectedSha256: String,
        val label: String,
    )

    companion object {
        private const val TAG = "GgufDownloadWorker"

        const val CHANNEL_ID = "describe_gguf_download"
        const val NOTIFICATION_ID = 0x44D1  // arbitrary; "DD" for Describe Download

        // Input key: tells the worker which ModelKind to fetch.
        const val KEY_MODEL_KIND = "model_kind"

        // Output / progress keys — read by GgufModelDownloader via WorkInfo.
        const val KEY_PERCENT = "percent"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_FILE_INDEX = "file_index"
        const val KEY_FILE_COUNT = "file_count"
        const val KEY_FILE_LABEL = "file_label"
        const val KEY_ERROR = "error"

        /** Unique work name per kind — pause/cancel one without affecting the other. */
        fun workName(kind: ModelKind): String = "gguf_download_${kind.name}"
    }
}
