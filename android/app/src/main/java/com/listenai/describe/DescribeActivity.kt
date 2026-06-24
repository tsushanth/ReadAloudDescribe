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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.listenai.describe.llama.LlamaEngine
import com.listenai.describe.ui.theme.ReadAloudDescribeTheme

/**
 * The one and only activity. Two entry points:
 *   1. Launcher tap → opens with no image, shows the "share an image to me"
 *      empty state.
 *   2. Share-sheet (any image type) → opens with the shared image URI in the intent,
 *      Day 5+ will kick off the llama.cpp describe pipeline against it.
 *
 * Day-4 scope: receive the intent, display the image. That's it.
 * No inference, no TTS, no downloader. Just prove the share-target wiring
 * works end-to-end and the URI hand-off is clean.
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
        // System-info is process-lifetime — only sample once. Reusing
        // the value from onCreate would require state plumbing; for a
        // Day-5 diagnostic, re-call is fine + cheap (it's a strcpy).
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
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { padding ->
        if (sharedImage != null) {
            ImageReceivedContent(sharedImage, nativeInfo, padding)
        } else {
            EmptyStateContent(nativeInfo, padding)
        }
    }
}

@Composable
private fun ImageReceivedContent(uri: Uri, nativeInfo: String, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Cap AsyncImage height — without an explicit constraint, its
        // intrinsic measurement (especially while the bitmap is still
        // loading) can blow past the column's bounds and hide the text
        // below it. Caught on Day-4 first Pixel render. ContentScale.Fit
        // so portrait images don't crop.
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
        // For Day-4 verification only — confirms the URI handed in by the
        // share sheet is what we received. Will become the description
        // text in Day 7.
        Text(
            text = "URI: $uri",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Day-5 surface check — render the llama.cpp system_info string
        // so we can visually confirm the native build worked. Goes away
        // in Day 7 when real inference output replaces the placeholder.
        Text(
            text = nativeInfo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyStateContent(nativeInfo: String, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.padding(top = 80.dp)) {
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
