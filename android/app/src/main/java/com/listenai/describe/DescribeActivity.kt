package com.listenai.describe

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.listenai.describe.llama.LlamaEngine
import com.listenai.describe.model.GgufModelDownloader
import com.listenai.describe.ui.theme.ReadAloudDescribeTheme

/**
 * The one and only activity. Two entry points:
 *   1. Launcher tap → opens with no image, shows the "share an image to me"
 *      empty state.
 *   2. Share-sheet (any image type) → opens with the shared image URI in
 *      the intent.
 *
 * Day-6 surface adds the GGUF model download status card at the top of
 * both screens — auto-starts on first launch via a LaunchedEffect.
 * Inference itself still lands in Day 7.
 */
class DescribeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedImage = extractSharedImage(intent)
        Log.i(TAG, "onCreate sharedImage=$sharedImage")

        // Day-5 surface check — call into libdescribe_jni.so to verify
        // the native llama.cpp build is alive. Captured to a string we
        // render in the UI; if the JNI load fails (e.g. .so missing,
        // wrong ABI, unresolved symbols), we catch + display the error
        // instead of crashing so we can debug from the UI.
        val nativeInfo = try {
            LlamaEngine.nativeSystemInfo()
        } catch (t: Throwable) {
            Log.e(TAG, "LlamaEngine.nativeSystemInfo failed", t)
            "LlamaEngine FAILED: ${t.javaClass.simpleName}: ${t.message}"
        }
        Log.i(TAG, "nativeSystemInfo => $nativeInfo")

        setContent {
            ReadAloudDescribeTheme {
                DescribeScreen(sharedImage = sharedImage, nativeInfo = nativeInfo)
            }
        }
    }

    /**
     * onNewIntent fires when the activity is already alive and the user
     * shares another image to us. We don't recreate the activity (cheaper
     * + lets us reuse the warmed-up llama engine), just re-render with
     * the new URI.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sharedImage = extractSharedImage(intent)
        Log.i(TAG, "onNewIntent sharedImage=$sharedImage")
        val nativeInfo = try {
            LlamaEngine.nativeSystemInfo()
        } catch (t: Throwable) {
            "LlamaEngine FAILED: ${t.javaClass.simpleName}: ${t.message}"
        }
        setContent {
            ReadAloudDescribeTheme {
                DescribeScreen(sharedImage = sharedImage, nativeInfo = nativeInfo)
            }
        }
    }

    /**
     * Pull the first image URI out of either an ACTION_SEND or
     * ACTION_SEND_MULTIPLE intent. Returns null for launcher-direct
     * entry (no intent extras).
     */
    private fun extractSharedImage(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.type?.startsWith("image/") != true) return null

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
                }
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "DescribeActivity"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DescribeScreen(sharedImage: Uri?, nativeInfo: String) {
    val context = LocalContext.current
    val downloader = remember { GgufModelDownloader.getInstance(context) }
    val downloadState by downloader.state.collectAsState()

    // Auto-trigger on first composition. Idempotent — the orchestrator
    // returns immediately if the models are already on disk.
    LaunchedEffect(Unit) {
        downloader.startIfPossible()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ModelStatusCard(state = downloadState, onRetry = { downloader.startIfPossible() })
            if (sharedImage != null) {
                ImageReceivedContent(sharedImage, nativeInfo)
            } else {
                EmptyStateContent(nativeInfo)
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    state: GgufModelDownloader.State,
    onRetry: () -> Unit,
) {
    // No card when models are ready — keeps the UI clean once setup
    // is one-time-done. Day 7 will surface inference progress here instead.
    if (state is GgufModelDownloader.State.Ready) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (state) {
                is GgufModelDownloader.State.Idle -> {
                    Text(
                        text = "Voice model not downloaded",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Required to describe images. ~3.5 GB, downloads on Wi-Fi only.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onRetry) {
                        Text("Download")
                    }
                }
                is GgufModelDownloader.State.Waiting -> {
                    Text(
                        text = "Waiting to download",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = state.reason,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is GgufModelDownloader.State.Downloading -> {
                    Text(
                        text = "Downloading voice model — ${state.percent}%",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${state.fileLabel} (file ${state.fileIndex} of ${state.fileCount}): ${state.downloadedMb} of ${state.totalMb} MB",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is GgufModelDownloader.State.Failed -> {
                    Text(
                        text = "Download failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
                is GgufModelDownloader.State.Ready -> Unit // handled above
            }
        }
    }
}

@Composable
private fun ImageReceivedContent(uri: Uri, nativeInfo: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = uri,
            contentDescription = stringResource(R.string.shared_image_content_description),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            onError = { state ->
                android.util.Log.e(
                    "DescribeActivity",
                    "Coil failed to load $uri: ${state.result.throwable.message}",
                    state.result.throwable
                )
            },
        )
        Text(
            text = stringResource(R.string.placeholder_describe_pending),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "URI: $uri",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = nativeInfo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyStateContent(nativeInfo: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.padding(top = 40.dp)) {
            Text(
                text = stringResource(R.string.empty_state_share_hint),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Text(
            text = nativeInfo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
