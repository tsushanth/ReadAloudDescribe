package com.listenai.describe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.listenai.describe.access.gesture.GestureAction
import com.listenai.describe.access.gesture.GestureMappingStore
import com.listenai.describe.engine.DescribePrompts
import com.listenai.describe.translate.TranslateSettings
import com.listenai.describe.engine.LlamaEngineController
import com.listenai.describe.llama.LlamaEngine
import com.listenai.describe.model.GgufModelDownloader
import com.listenai.describe.model.ModelKind
import com.listenai.describe.settings.DescribeSettings
import com.listenai.describe.tts.DescribeTts
import com.listenai.describe.ui.theme.ReadAloudDescribeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect

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

    // Selected model — observe so a toggle change re-keys the downloader
    // + engine without needing to recreate the activity.
    val settings = remember { DescribeSettings.getInstance(context) }
    val selectedModel by settings.selectedModel.collectAsState()

    // Downloader is per-ModelKind so user can have one model on disk
    // while another is downloading. remember(selectedModel) re-fetches
    // the matching instance whenever the user toggles.
    val downloader = remember(selectedModel) {
        GgufModelDownloader.getInstance(context, selectedModel)
    }
    val downloadState by downloader.state.collectAsState()

    // App-scoped controller (survives activity recreation / share-target
    // re-entry within the same process) — see DescribeApplication.
    val engineController = remember { DescribeApplication.engineController(context) }

    // Engine-load state. Initialize from the controller so the label is
    // honest after an activity re-entry (e.g. Photos share) when the
    // engine is already loaded in our long-lived process.
    var engineStatus by remember(selectedModel) {
        mutableStateOf(
            if (engineController.handle != 0L && engineController.loadedKind == selectedModel)
                "Engine: ready (${selectedModel.displayName})"
            else "Engine: not loaded"
        )
    }

    // Description state. null = nothing yet, non-null = either the
    // generated description or an "(error: …)" payload from JNI.
    var description by remember { mutableStateOf<String?>(null) }
    var describing by remember { mutableStateOf(false) }

    // Auto-trigger on first composition for the current ModelKind.
    // Idempotent — returns immediately if its GGUFs are already on disk.
    LaunchedEffect(selectedModel) {
        downloader.startIfPossible()
    }

    // When the user toggles models, reset the local engine-handle state
    // so the load LaunchedEffect below re-runs against the new model's
    // GGUFs. The actual free-old/load-new sequencing now lives in
    // LlamaEngineController.ensureLoaded, which serializes it behind a
    // mutex (needed now that both this Activity and the accessibility
    // service can call into the same controller).
    var engineHandle by remember { mutableStateOf(engineController.handle) }
    LaunchedEffect(selectedModel) {
        if (engineController.handle != 0L && engineController.loadedKind != selectedModel) {
            Log.i("DescribeActivity", "ModelKind toggled → ${selectedModel.name}; will switch engine")
            engineHandle = 0L
            description = null
            engineStatus = "Engine: switching to ${selectedModel.displayName}…"
        }
    }

    // TTS engine wrapper. Prefers com.listenai.voice when installed,
    // falls back to the device's system default. Auto-cleans up when
    // the activity disposes.
    val tts = remember { DescribeTts(context) }
    val ttsState by tts.state.collectAsState()
    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    // Compose-scoped coroutine for the JNI streaming callback to
    // marshal back onto the main thread. The callback fires from
    // an inference thread (not the main thread, not a coroutine),
    // so it can't touch Compose state directly.
    val scope = rememberCoroutineScope()

    // When the downloader settles into Ready, auto-load both models
    // via the JNI bridge on Dispatchers.IO. Re-keyed on selectedModel
    // so a toggle change re-fires this with the new GGUF paths.
    LaunchedEffect(downloadState, selectedModel) {
        if (downloadState is GgufModelDownloader.State.Ready && engineController.loadedKind != selectedModel) {
            engineStatus = "Loading ${selectedModel.displayName} engine…"
            withContext(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val ok = engineController.ensureLoaded(
                    selectedModel,
                    downloader.mmprojFile.absolutePath,
                    downloader.textModelFile.absolutePath,
                )
                val dt = System.currentTimeMillis() - t0
                engineHandle = engineController.handle   // triggers the describe LaunchedEffect
                engineStatus = if (ok)
                    "Engine: loaded ${selectedModel.displayName} (handle=${engineController.handle}, ${dt}ms)"
                else
                    "Engine: load FAILED (see logcat)"
                Log.i("DescribeActivity", engineStatus)
            }
        }
    }

    // When a shared image arrives AND the engine is loaded, auto-call
    // the STREAMING describe path on a background coroutine. Tokens
    // stream into the UI live as they're generated, but TTS waits for
    // the FULL description before speaking — per-sentence chunked TTS
    // produced audible mid-word cuts at utterance boundaries on Samsung
    // (queued tts.speak() calls have tiny gaps that sound like word
    // splits). Trade: lose ~17s time-to-first-audio, gain natural
    // continuous speech.
    LaunchedEffect(sharedImage, engineHandle) {
        val uri = sharedImage ?: return@LaunchedEffect
        if (engineHandle == 0L) return@LaunchedEffect
        if (describing) return@LaunchedEffect

        describing = true
        description = null
        Log.i("DescribeActivity", "auto-describe firing for $uri (handle=$engineHandle)")

        withContext(Dispatchers.IO) {
            var readError: Throwable? = null
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: ByteArray(0)
            } catch (t: Throwable) {
                Log.e("DescribeActivity", "failed to read image bytes from $uri", t)
                readError = t
                ByteArray(0)
            }
            if (bytes.isEmpty()) {
                // file:// URIs from some 3rd-party galleries fail with
                // EACCES on Android 10+ scoped storage. Tell the user to
                // re-share from a modern app (Photos, Files) instead of
                // a generic "couldn't read" that gives them no path
                // forward. content:// URIs from Photos/Files arrive with
                // built-in temp-read permission and just work.
                description = if (uri.scheme == "file" ||
                    readError?.cause is android.system.ErrnoException) {
                    "Couldn't open this image. Try sharing it again from Photos or Files — some gallery apps use a format Android won't let us open."
                } else {
                    "(error: couldn't read image)"
                }
                describing = false
                return@withContext
            }
            Log.i("DescribeActivity", "image read: ${bytes.size} bytes, calling nativeDescribeImageStream")

            val accumulated = StringBuilder(2048)
            val tokensDone = kotlinx.coroutines.CompletableDeferred<Int>()

            val callback = object : LlamaEngine.DescribeCallback {
                override fun onToken(piece: String) {
                    accumulated.append(piece)
                    // Live UI update — user watches text grow as tokens
                    // arrive. Marshal to main thread.
                    scope.launch(Dispatchers.Main) {
                        description = accumulated.toString()
                    }
                }
                override fun onComplete(generated: Int) {
                    val finalText = accumulated.toString().trim()
                    // Diagnostic: dump the full text as hex so we can
                    // see any zero-width / non-breaking / control chars
                    // that might be making TTS phonemizers stutter.
                    val hex = finalText.map { ch ->
                        if (ch in ' '..'~') ch.toString()
                        else "[U+%04X]".format(ch.code)
                    }.joinToString("")
                    Log.i("DescribeActivity", "describe text hex-dump: $hex")
                    // Sanitize: collapse weird whitespace + strip control
                    // chars before handing to TTS. Phi-2 byte-BPE
                    // sometimes emits NBSP / zero-width chars that
                    // confuse TTS phonemizers into spelling words letter
                    // by letter (e.g. "exp" + NBSP + "ression").
                    val sanitized = finalText
                        .replace(Regex("[\\u00A0\\u2000-\\u200F\\u202F\\u205F\\u3000]"), " ")
                        .replace(Regex("[\\p{Cntrl}&&[^\\n\\r\\t]]"), "")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (sanitized != finalText) {
                        Log.i("DescribeActivity", "sanitized: ${sanitized.length} chars (was ${finalText.length})")
                    }
                    if (sanitized.isNotBlank()) {
                        scope.launch(Dispatchers.Main) {
                            tts.speak(sanitized)
                        }
                    }
                    tokensDone.complete(generated)
                }
                override fun onError(message: String) {
                    Log.e("DescribeActivity", "describe stream error: $message")
                    scope.launch(Dispatchers.Main) {
                        description = "(error: $message)"
                    }
                    tokensDone.complete(0)
                }
            }

            val t0 = System.currentTimeMillis()
            try {
                engineController.describeStream(
                    imageBytes = bytes,
                    // See DescribePrompts.PHOTO_SCENE_PROMPT: positive-only
                    // phrasing anchored to posture + literal objects — small
                    // VLMs over-latch onto "do not X" clauses and hallucinate
                    // less when told what to look for instead.
                    prompt = DescribePrompts.PHOTO_SCENE_PROMPT,
                    maxTokens = 120,
                    chatTemplate = selectedModel.chatTemplate,
                    callback = callback,
                )
            } catch (t: Throwable) {
                Log.e("DescribeActivity", "describeStream threw", t)
                tokensDone.complete(0)
            }
            val generated = tokensDone.await()
            val dt = System.currentTimeMillis() - t0
            Log.i("DescribeActivity", "stream done in ${dt}ms, generated=$generated, chars=${accumulated.length}")
            describing = false
        }
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
            ModelSelectorCard(
                selected = selectedModel,
                onSelect = { settings.setSelectedModel(it) },
            )
            ModelStatusCard(
                kind = selectedModel,
                state = downloadState,
                onRetry = { downloader.startIfPossible() },
            )
            GestureSettingsCard()
            TranslateSettingsCard()
            MicPermissionCard()
            // Engine handle + llama system_info are dev-only diagnostics.
            // Hide in release builds so Play screenshots / shipped UI
            // stay clean.
            if (BuildConfig.DEBUG) {
                Text(
                    text = engineStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (sharedImage != null) {
                ImageReceivedContent(
                    uri = sharedImage,
                    nativeInfo = nativeInfo,
                    description = description,
                    describing = describing,
                    ttsState = ttsState,
                    onSpeak = { description?.let { tts.speak(it) } },
                    onStop = { tts.stop() },
                )
            } else {
                EmptyStateContent(nativeInfo)
            }
        }
    }
}

/**
 * Two-option model picker. Sits above the status card so the user can
 * see + change which model they're on before deciding to wait through
 * a download. Toggling the radio:
 *   - persists the choice via DescribeSettings
 *   - DescribeScreen observes that StateFlow + re-keys the downloader
 *   - if the OLD model's engine is loaded, DescribeScreen frees it
 *   - downloader auto-starts the NEW model's GGUF fetch if not on disk
 *
 * Accessibility: row is one TalkBack focus target with a single
 * "double-tap to select" action; size + name announced together.
 */
@Composable
private fun ModelSelectorCard(
    selected: ModelKind,
    onSelect: (ModelKind) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Model",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "\"Fast\" is much quicker but less detailed. \"Detailed\" is slower but recognizes more.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (kind in ModelKind.entries) {
                val isSelected = kind == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelect(kind) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,            // selectable() handles it
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = "${kind.displayName} — ${kind.sizeLabel}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    kind: ModelKind,
    state: GgufModelDownloader.State,
    onRetry: () -> Unit,
) {
    // No card when models are ready — keeps the UI clean once setup
    // is one-time-done.
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
                        text = "${kind.displayName} model not downloaded",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Required to describe images. ${kind.sizeLabel}, downloads on Wi-Fi only.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onRetry) {
                        Text("Download")
                    }
                }
                is GgufModelDownloader.State.Waiting -> {
                    Text(
                        text = "Waiting to download ${kind.displayName} model",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = state.reason,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is GgufModelDownloader.State.Downloading -> {
                    Text(
                        text = "Downloading ${kind.displayName} model — ${state.percent}%",
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

/**
 * M7 remapping UI: one row per remappable swipe direction
 * (GestureMappingStore.SLOTS). Tapping a row's button cycles through
 * the available GestureActions and persists the choice immediately —
 * no separate "save" step, consistent with ModelSelectorCard's
 * immediate-persist pattern above.
 */
@Composable
private fun GestureSettingsCard() {
    val context = LocalContext.current
    val store = remember { GestureMappingStore.getInstance(context) }
    val mapping by store.mapping.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Gestures",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Quick single-finger swipes while the screen reader is on. Tap a gesture to change what it does.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (slot in GestureMappingStore.SLOTS) {
                val current = mapping[slot.gestureId] ?: slot.default
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = slot.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        val entries = GestureAction.entries
                        val next = entries[(entries.indexOf(current) + 1) % entries.size]
                        store.setAction(slot.gestureId, next)
                    }) {
                        Text(current.displayName)
                    }
                }
            }
        }
    }
}

/**
 * M10: target-language picker for the "translate focused text"
 * action. Same cycle-on-tap immediate-persist pattern as
 * GestureSettingsCard above.
 */
@Composable
private fun TranslateSettingsCard() {
    val context = LocalContext.current
    val store = remember { TranslateSettings.getInstance(context) }
    val target by store.targetLanguage.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Translate to",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Target language for the \"translate focused text\" action.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = {
                val options = TranslateSettings.LANGUAGE_OPTIONS
                val next = options[(options.indexOf(target) + 1) % options.size]
                store.setTargetLanguage(next)
            }) {
                Text(target.displayName)
            }
        }
    }
}

/**
 * M9: the accessibility service can't request runtime permissions
 * itself (only an Activity can), so this card is the one place a user
 * grants RECORD_AUDIO for voice dictation. Hides itself once granted.
 */
@Composable
private fun MicPermissionCard() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    if (granted) return

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        granted = isGranted
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Microphone permission needed",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Voice dictation needs microphone access to hear what you say.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Grant microphone access")
            }
        }
    }
}

@Composable
private fun ImageReceivedContent(
    uri: Uri,
    nativeInfo: String,
    description: String?,
    describing: Boolean,
    ttsState: DescribeTts.State,
    onSpeak: () -> Unit,
    onStop: () -> Unit,
) {
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
        // Description surface — three states from the user's POV:
        // 1. describing == true  → "Describing image…" + progress spinner cue
        // 2. description != null → render it (or render the "(error:…)" payload)
        // 3. else (engine still loading or models missing) → placeholder
        when {
            describing -> {
                Text(
                    text = "Describing image…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            description != null -> {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                )
                // Speak / Stop control. The auto-speak LaunchedEffect
                // already fired when the description landed; this gives
                // the user a way to replay or stop.
                if (ttsState is DescribeTts.State.Speaking) {
                    Button(onClick = onStop) {
                        Text("Stop speaking")
                    }
                } else if (!description.startsWith("(error:")) {
                    Button(onClick = onSpeak) {
                        Text("Read aloud again")
                    }
                }
            }
            else -> {
                Text(
                    text = stringResource(R.string.placeholder_describe_pending),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Diagnostics — debug-only.
        if (BuildConfig.DEBUG) {
            Text(
                text = nativeInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        if (BuildConfig.DEBUG) {
            Text(
                text = nativeInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
