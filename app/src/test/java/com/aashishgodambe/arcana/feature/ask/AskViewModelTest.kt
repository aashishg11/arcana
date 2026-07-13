package com.aashishgodambe.arcana.feature.ask

import app.cash.turbine.test
import com.aashishgodambe.arcana.core.ai.FakeGeminiService
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.data.database.entity.CollectionGroup
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import com.aashishgodambe.arcana.core.data.importer.model.ImportedItem
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import com.aashishgodambe.arcana.core.domain.model.PortfolioPoint
import com.aashishgodambe.arcana.core.domain.model.ValueSnapshot
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `grounds the prompt in the top items and streams an on-device answer`() = runTest(dispatcher) {
        val repo = repositoryWith(
            funko(1, "Fire Nation Aang", 59_000, nft = true),
            funko(2, "Daenerys Targaryen With Egg", 59_000, nft = true),
            funko(3, "Daemon Targaryen", 45_000, nft = false),
        )
        val gemini = FakeGeminiService(
            cannedResponse = "Your most valuable item is Fire Nation Aang at \$590.",
            executedOn = InferenceLocation.OnDevice,
        )
        val vm = AskViewModel(gemini, repo)

        vm.state.test {
            assertEquals(0, awaitItem().turns.size) // initial idle state

            vm.ask("What's my most valuable item?")

            val turn = awaitSettled().turns.single()
            assertEquals("What's my most valuable item?", turn.question)
            assertTrue(turn.answer!!.contains("$590"))
            // badge reflects where it ran
            assertEquals(InferenceLocation.OnDevice, turn.metadata?.executedOn)
            // retrieved-context strip shows the grounded top 3, most valuable first
            assertEquals(3, turn.grounding.size)
            assertEquals("Fire Nation Aang · \$590", turn.grounding.first().label)
            assertNull(turn.streamingAnswer)
            assertNull(turn.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `surfaces an error and stops running when inference fails`() = runTest(dispatcher) {
        val gemini = FakeGeminiService(error = IllegalStateException("Nano did not run"))
        val vm = AskViewModel(gemini, repositoryWith())

        vm.state.test {
            awaitItem() // initial
            vm.ask("anything")

            var item = awaitItem()
            while (item.turns.lastOrNull()?.error == null) item = awaitItem()
            val turn = item.turns.single()
            assertEquals("Nano did not run", turn.error)
            assertTrue(!item.isRunning)
            assertNull(turn.streamingAnswer)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Pumps state emissions until inference finishes with an AI reply. */
    private suspend fun app.cash.turbine.ReceiveTurbine<AskUiState>.awaitSettled(): AskUiState {
        while (true) {
            val item = awaitItem()
            if (!item.isRunning && item.turns.lastOrNull()?.answer != null) return item
        }
    }

    private fun funko(id: Long, name: String, valueCents: Int, nft: Boolean) = FunkoPop(
        localId = id, name = name, brand = "Funko", imageUrl = null,
        estimatedValueCents = valueCents, lastKnownValueCents = null, quantity = 1,
        itemCondition = "Mint", packagingCondition = "Mint", series = emptyList(),
        productionTags = emptyList(), dateAdded = LocalDate.of(2023, 1, 1),
        pricePaidCents = null, storageLocation = null,
        upc = "0", popNumber = null, exclusiveTo = null, isNftRedeemable = nft,
    )

    /** Minimal fake — only [getMostValuable] matters to the ViewModel under test. */
    private fun repositoryWith(vararg items: Collectible) = object : CollectibleRepository {
        private val sorted = items.sortedByDescending { it.estimatedValueCents }
        override suspend fun getMostValuable(limit: Int): List<Collectible> = sorted.take(limit)
        override suspend fun search(query: String, limit: Int): List<Collectible> {
            val terms = query.split(" ").filter { it.length >= 3 }
            if (terms.isEmpty()) return emptyList()
            return sorted.filter { c -> terms.all { c.name.contains(it, ignoreCase = true) } }.take(limit)
        }
        override fun observeCollection(): Flow<List<Collectible>> = emptyFlow()
        override fun observeByList(listName: String): Flow<List<Collectible>> = emptyFlow()
        override fun observeCount(): Flow<Int> = emptyFlow()
        override fun observeTotalValueCents(): Flow<Int> = emptyFlow()
        override fun observeCopyCount(): Flow<Int> = emptyFlow()
        override fun observeListBreakdown(): Flow<List<CollectionGroup>> = emptyFlow()
        override suspend fun getById(localId: Long): Collectible? = null
        override suspend fun allCollectibles(): List<Collectible> = sorted
        override fun observeValueHistory(localId: Long): Flow<List<ValueSnapshot>> = emptyFlow()
        override fun observePortfolioSeries(): Flow<List<PortfolioPoint>> = emptyFlow()
        override suspend fun listValueSeries(): Map<String, List<PortfolioPoint>> = emptyMap()
        override suspend fun latestSnapshot(localId: Long): ValueSnapshot? = null
        override suspend fun recordSnapshot(
            localId: Long,
            valueCents: Int,
            source: ValueSource,
            trigger: SnapshotTrigger,
            at: Instant,
        ) = Unit
        override suspend fun isHistorySeeded(): Boolean = true
        override suspend fun replaceHistories(histories: Map<Long, List<ValueSnapshot>>) = Unit
        override suspend fun importFrom(
            items: List<ImportedItem>,
            onProgress: (written: Int, item: ImportedItem) -> Unit,
        ): Int = 0
        override suspend fun saveCaptured(item: ImportedItem): Long = 0
        override suspend fun incrementQuantity(localId: Long): Int = 0
        override suspend fun listNames(): List<String> = emptyList()
        override suspend fun delete(localId: Long) = Unit
    }
}
