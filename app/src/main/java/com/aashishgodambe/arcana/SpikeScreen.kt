package com.aashishgodambe.arcana

/*
 * THROWAWAY Week-2 Day-1 spike, moved out of MainActivity on Day 4 (no longer the app entry).
 * De-risked the Gemini Nano on-device path; kept orphaned until Day 5 when it's replaced by the
 * real GeminiService abstraction and this file is deleted. No DI, no Room — one button, one prompt.
 *
 * InferenceMode.ONLY_ON_DEVICE is deliberate: a thrown exception is an unambiguous "Nano did not
 * run" signal rather than a silent cloud fallback. Success == on-device by construction.
 */

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.Firebase
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ArcanaSpike"
private const val PROMPT = "In two sentences, explain why running AI on-device matters for privacy."

// Required cloud fallback model. gemini-2.0-flash-lite is retired; 2.5 is current.
// Unused under ONLY_ON_DEVICE, but the SDK requires a modelName.
private const val CLOUD_FALLBACK_MODEL = "gemini-2.5-flash-lite"

data class SpikeUiState(
    val running: Boolean = false,
    val output: String = "",
    val status: String = "Idle — tap Run to stream from Gemini Nano (ONLY_ON_DEVICE).",
)

@OptIn(PublicPreviewAPI::class)
class SpikeViewModel : ViewModel() {

    private val _state = MutableStateFlow(SpikeUiState())
    val state: StateFlow<SpikeUiState> = _state.asStateFlow()

    private val model = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel(
            modelName = CLOUD_FALLBACK_MODEL,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_ON_DEVICE),
        )

    fun checkAndPrepare() {
        if (_state.value.running) return
        _state.value = _state.value.copy(running = true, status = "Checking on-device model status…")

        viewModelScope.launch {
            try {
                val ext = model.onDeviceExtension
                if (ext == null) {
                    _state.value = _state.value.copy(
                        running = false,
                        status = "No on-device extension — model was not built with on-device config.",
                    )
                    return@launch
                }
                when (ext.checkStatus()) {
                    OnDeviceModelStatus.AVAILABLE -> {
                        Log.i(TAG, "model status = AVAILABLE")
                        _state.value = _state.value.copy(running = false, status = "Model AVAILABLE — tap Run.")
                    }
                    OnDeviceModelStatus.UNAVAILABLE -> {
                        Log.w(TAG, "model status = UNAVAILABLE (feature not provisioned on this device)")
                        _state.value = _state.value.copy(
                            running = false,
                            status = "Model UNAVAILABLE — AICore has no Nano feature for this device yet.",
                        )
                    }
                    else -> { // DOWNLOADABLE or DOWNLOADING
                        Log.i(TAG, "model status = downloadable/downloading — starting download()")
                        var total = 0L
                        ext.download().collect { ds ->
                            when (ds) {
                                is DownloadStatus.DownloadStarted -> {
                                    total = ds.bytesToDownload
                                    Log.i(TAG, "download started: total=$total bytes")
                                    _state.value = _state.value.copy(status = "Downloading… 0 / ${mb(total)} MB")
                                }
                                is DownloadStatus.DownloadInProgress -> {
                                    val done = ds.totalBytesDownloaded
                                    val pct = if (total > 0) done * 100 / total else 0
                                    _state.value = _state.value.copy(status = "Downloading… ${mb(done)} / ${mb(total)} MB ($pct%)")
                                }
                                is DownloadStatus.DownloadCompleted -> {
                                    Log.i(TAG, "download completed")
                                    _state.value = _state.value.copy(running = false, status = "Download complete — model AVAILABLE. Tap Run.")
                                }
                                is DownloadStatus.DownloadFailed -> {
                                    Log.e(TAG, "download FAILED", ds.exception)
                                    _state.value = _state.value.copy(running = false, status = "Download FAILED: ${ds.exception.message}")
                                }
                                else -> {}
                            }
                        }
                        if (_state.value.running) _state.value = _state.value.copy(running = false)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "checkStatus/download failed", t)
                _state.value = _state.value.copy(running = false, status = "Prepare failed: ${t::class.simpleName}: ${t.message}")
            }
        }
    }

    private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_000_000.0)

    fun run() {
        if (_state.value.running) return
        _state.value = SpikeUiState(running = true, status = "Starting on-device inference…")

        viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            var firstTokenAt: Long? = null
            val sb = StringBuilder()
            try {
                model.generateContentStream(PROMPT).collect { chunk ->
                    val piece = chunk.text ?: return@collect
                    if (firstTokenAt == null) {
                        firstTokenAt = SystemClock.elapsedRealtime()
                        Log.i(TAG, "first-token latency = ${firstTokenAt!! - start} ms")
                    }
                    sb.append(piece)
                    _state.value = _state.value.copy(output = sb.toString())
                }
                val total = SystemClock.elapsedRealtime() - start
                val ttft = firstTokenAt?.minus(start)
                Log.i(TAG, "DONE on-device · first-token=${ttft} ms · total=${total} ms")
                _state.value = _state.value.copy(
                    running = false,
                    status = "Done · on-device · first-token ${ttft ?: "?"} ms · total $total ms",
                )
            } catch (t: Throwable) {
                val total = SystemClock.elapsedRealtime() - start
                Log.e(TAG, "on-device inference FAILED after $total ms (Nano did not run)", t)
                _state.value = _state.value.copy(
                    running = false,
                    status = "FAILED after $total ms (Nano did not run): " +
                        "${t::class.simpleName}: ${t.message}",
                )
            }
        }
    }
}

@Composable
fun SpikeScreen(vm: SpikeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Arcana — Gemini Nano spike", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = vm::checkAndPrepare, enabled = !state.running) {
                Text("Check / Prepare model")
            }
            Button(onClick = vm::run, enabled = !state.running) {
                Text(if (state.running) "Running…" else "Run on-device inference")
            }
            Text(state.status, style = MaterialTheme.typography.labelLarge)
            HorizontalDivider()
            Text(state.output.ifEmpty { "(no output yet)" })
        }
    }
}
