package com.aashishgodambe.arcana.feature.benchmark

import com.aashishgodambe.arcana.core.ai.FakeGeminiService
import com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkEngine
import com.aashishgodambe.arcana.core.ai.benchmark.BenchmarkHarness
import com.aashishgodambe.arcana.core.ai.capability.DeviceCapabilityChecker
import com.aashishgodambe.arcana.core.ai.capability.ModelReadiness
import com.aashishgodambe.arcana.core.ai.capability.ProvisioningProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(readiness: ModelReadiness) =
        BenchmarkViewModel(BenchmarkHarness(FakeGeminiService()), FakeCapability(readiness))

    @Test
    fun `reads on-device readiness on init`() = runTest(dispatcher) {
        val vm = viewModel(ModelReadiness.Available)
        advanceUntilIdle()
        assertEquals(ModelReadiness.Available, vm.state.value.onDeviceReadiness)
    }

    @Test
    fun `runs both engines and separates the cold sample when on-device is available`() = runTest(dispatcher) {
        val vm = viewModel(ModelReadiness.Available)
        advanceUntilIdle()

        vm.runBenchmark()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(BenchmarkPhase.Done, s.phase)
        assertNull(s.progress)
        // 2 engines × 2 prompts
        assertEquals(setOf(BenchmarkEngine.OnDevice, BenchmarkEngine.Cloud), s.results.map { it.engine }.toSet())
        assertEquals(4, s.results.size)
        // The one cold call (first on-device call) is excluded from its cell's warm count.
        val odFirst = s.results.first { it.engine == BenchmarkEngine.OnDevice }
        assertEquals(7, odFirst.warmSampleCount) // 8 iterations − 1 cold
    }

    @Test
    fun `runs cloud-only when on-device is not provisioned`() = runTest(dispatcher) {
        val vm = viewModel(ModelReadiness.Downloadable)
        advanceUntilIdle()

        vm.runBenchmark()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.onDeviceIncluded)
        assertEquals(listOf(BenchmarkEngine.Cloud), s.results.map { it.engine }.distinct())
    }

    @Test
    fun `downloading the model flips readiness to available`() = runTest(dispatcher) {
        val capability = FakeCapability(ModelReadiness.Downloadable)
        val vm = BenchmarkViewModel(BenchmarkHarness(FakeGeminiService()), capability)
        advanceUntilIdle()

        vm.downloadOnDeviceModel()
        advanceUntilIdle()

        assertEquals(ModelReadiness.Available, vm.state.value.onDeviceReadiness)
        assertNull(vm.state.value.provisioning)
    }

    /** In-memory capability checker; [downloadOnDeviceModel] provisions and flips readiness to Available. */
    private class FakeCapability(private var readiness: ModelReadiness) : DeviceCapabilityChecker {
        override suspend fun onDeviceReadiness(): ModelReadiness = readiness
        override fun downloadOnDeviceModel(): Flow<ProvisioningProgress> = flow {
            emit(ProvisioningProgress.Downloading(120))
            readiness = ModelReadiness.Available
            emit(ProvisioningProgress.Completed)
        }
    }
}
