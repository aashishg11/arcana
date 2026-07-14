package com.aashishgodambe.arcana.feature.ask

import app.cash.turbine.test
import com.aashishgodambe.arcana.core.ai.FakeGeminiService
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.rag.CollectionRetriever
import com.aashishgodambe.arcana.core.ai.rag.Grounding
import com.aashishgodambe.arcana.core.ai.rag.RetrievalStrategy
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun `grounds the prompt in the retrieved items and streams an on-device answer`() = runTest(dispatcher) {
        val grounding = Grounding(
            items = listOf(
                funko(1, "Fire Nation Aang", 59_000),
                funko(2, "Daenerys Targaryen With Egg", 59_000),
                funko(3, "Daemon Targaryen", 45_000),
            ),
            strategy = RetrievalStrategy.Structured,
        )
        val gemini = FakeGeminiService(
            cannedResponse = "Your most valuable item is Fire Nation Aang at \$590.",
            executedOn = InferenceLocation.OnDevice,
        )
        val vm = AskViewModel(gemini, retrieverReturning(grounding))

        vm.state.test {
            assertEquals(0, awaitItem().turns.size) // initial idle state

            vm.ask("What's my most valuable item?")

            val turn = awaitSettled().turns.single()
            assertEquals("What's my most valuable item?", turn.question)
            assertTrue(turn.answer!!.contains("$590"))
            assertEquals(InferenceLocation.OnDevice, turn.metadata?.executedOn)
            assertEquals(RetrievalStrategy.Structured, turn.strategy)
            // retrieved-context strip shows the grounded items, most relevant first
            assertEquals(3, turn.grounding.size)
            assertEquals("Fire Nation Aang · \$590", turn.grounding.first().label)
            assertNull(turn.streamingAnswer)
            assertNull(turn.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a follow-up reuses the previous turn's grounding`() = runTest(dispatcher) {
        val first = Grounding(listOf(funko(1, "Deadpool", 30_000)), strategy = RetrievalStrategy.Semantic)
        // The retriever returns FollowUp (no items) for the second turn; the VM must reuse the first grounding.
        val retriever = object : CollectionRetriever {
            var calls = 0
            override suspend fun retrieve(query: String): Grounding {
                calls++
                return if (calls == 1) first else Grounding(emptyList(), strategy = RetrievalStrategy.FollowUp)
            }
        }
        val vm = AskViewModel(FakeGeminiService(cannedResponse = "ok"), retriever)

        vm.state.test {
            awaitItem()
            vm.ask("any deadpool?")
            awaitSettled()
            vm.ask("tell me more")
            val turn = awaitSettled().turns.last()
            assertEquals(RetrievalStrategy.FollowUp, turn.strategy)
            assertEquals(listOf("Deadpool · \$300"), turn.grounding.map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `surfaces an error and stops running when inference fails`() = runTest(dispatcher) {
        val gemini = FakeGeminiService(error = IllegalStateException("Nano did not run"))
        val vm = AskViewModel(gemini, retrieverReturning(Grounding(emptyList(), strategy = RetrievalStrategy.Semantic)))

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

    private fun funko(id: Long, name: String, valueCents: Int) = FunkoPop(
        localId = id, name = name, brand = "Funko", imageUrl = null,
        estimatedValueCents = valueCents, lastKnownValueCents = null, quantity = 1,
        itemCondition = "Mint", packagingCondition = "Mint", series = emptyList(),
        productionTags = emptyList(), dateAdded = LocalDate.of(2023, 1, 1),
        pricePaidCents = null, storageLocation = null,
        upc = "0", popNumber = null, exclusiveTo = null, isNftRedeemable = false,
    )

    private fun retrieverReturning(grounding: Grounding) = object : CollectionRetriever {
        override suspend fun retrieve(query: String): Grounding = grounding
    }
}
