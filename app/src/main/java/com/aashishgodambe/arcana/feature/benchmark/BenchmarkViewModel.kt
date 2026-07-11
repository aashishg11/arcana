package com.aashishgodambe.arcana.feature.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkAggregator
import com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkEngine
import com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkHarness
import com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkProgress
import com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkResult
import com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkSample
import com.aashishgodambe.arcana.core.ai.OwnModelEngine
import com.aashishgodambe.arcana.core.ai.capability.DeviceCapabilityChecker
import com.aashishgodambe.arcana.core.ai.capability.ModelReadiness
import com.aashishgodambe.arcana.core.ai.capability.ProvisioningProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class BenchmarkPhase { Idle, Running, Done }

data class BenchmarkUiState(
    val phase: BenchmarkPhase = BenchmarkPhase.Idle,
    val onDeviceReadiness: ModelReadiness = ModelReadiness.Unknown,
    val progress: BenchmarkProgress? = null,
    val results: List<BenchmarkResult> = emptyList(),
    val provisioning: ProvisioningProgress? = null,
) {
    /** The sweep runs on-device only when Nano is provisioned; otherwise it's an honest cloud-only run. */
    val onDeviceIncluded: Boolean get() = onDeviceReadiness == ModelReadiness.Available
    val isRunning: Boolean get() = phase == BenchmarkPhase.Running
}

/**
 * Drives the benchmark screen: reads on-device readiness (the gate), runs the sweep through [BenchmarkHarness]
 * with cloud pacing, and aggregates to [BenchmarkResult]s. When Nano isn't provisioned the sweep is cloud-only
 * (no column of 606s), and the UI offers to download the model.
 */
@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val harness: BenchmarkHarness,
    private val capability: DeviceCapabilityChecker,
    private val ownModel: OwnModelEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(BenchmarkUiState())
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    init {
        refreshReadiness()
    }

    fun refreshReadiness() {
        viewModelScope.launch {
            _state.update { it.copy(onDeviceReadiness = capability.onDeviceReadiness()) }
        }
    }

    fun runBenchmark() {
        if (_state.value.isRunning) return
        viewModelScope.launch {
            // Gate each on-device column on real availability: Nano on provisioning (else OnlyOnDevice 606s),
            // the own-model on the side-loaded file being present (else every call errors). Cloud always runs.
            val readiness = capability.onDeviceReadiness()
            val engines = buildList {
                if (readiness == ModelReadiness.Available) add(BenchmarkEngine.OnDevice)
                if (ownModel.isModelAvailable()) add(BenchmarkEngine.OwnModel)
                add(BenchmarkEngine.Cloud)
            }

            _state.update {
                it.copy(
                    phase = BenchmarkPhase.Running,
                    onDeviceReadiness = readiness,
                    results = emptyList(),
                    progress = null,
                )
            }

            var samples = emptyList<BenchmarkSample>()
            harness.run(
                engines = engines,
                iterations = ON_DEVICE_ITERATIONS,
                cloudIterations = CLOUD_ITERATIONS,
                cloudPaceMs = CLOUD_PACE_MS,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
            ).collect { samples = it }

            _state.update {
                it.copy(
                    phase = BenchmarkPhase.Done,
                    progress = null,
                    results = BenchmarkAggregator.aggregate(samples),
                )
            }
        }
    }

    fun downloadOnDeviceModel() {
        if (_state.value.onDeviceReadiness == ModelReadiness.Downloading) return
        viewModelScope.launch {
            _state.update { it.copy(onDeviceReadiness = ModelReadiness.Downloading, provisioning = null) }
            capability.downloadOnDeviceModel().collect { p ->
                _state.update { it.copy(provisioning = p) }
            }
            // Re-check after the download flow terminates (or errors) — status is the source of truth.
            _state.update { it.copy(onDeviceReadiness = capability.onDeviceReadiness(), provisioning = null) }
        }
    }

    private companion object {
        /** On-device samples per cell — indicative, not production-grade statistics; labeled as such in-app. */
        const val ON_DEVICE_ITERATIONS = 8

        /**
         * Cloud runs far fewer iterations — cloud latency is low-variance so a small N gives a fine p50, and
         * every cloud call spends a scarce free-tier daily budget. Conserve it. See conserve-cloud-tokens.
         */
        const val CLOUD_ITERATIONS = 4

        /** ~3.2 s between cloud calls keeps the sweep under the free-tier 20 req/min. */
        const val CLOUD_PACE_MS = 3_200L
    }
}
