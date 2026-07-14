package com.aashishgodambe.arcana.core.ai.rag

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the real EmbeddingGemma encoder against the **side-loaded** model + tokenizer
 * (skips cleanly when they aren't installed). Proves the pure-Kotlin tokenizer + LiteRT interpreter
 * actually embed on the Pixel, and — the point of RAG — that a **keyword-free** semantic query lands the
 * right item: "dragons" must rank *Drogon* (whose descriptor never says "dragon") above *Batman*. Also
 * emits the [EmbeddingBenchmark] report (cold/warm latency + 768/256/128 top-1) to logcat for Decision C.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingGemmaEncoderTest {

    private val encoder = EmbeddingGemmaEncoder(
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
    )

    @Test
    fun embeds_on_device_and_semantically_matches_dragons_to_drogon() = runBlocking {
        assumeTrue("EmbeddingGemma not side-loaded — skipping", encoder.isModelAvailable())

        val report = EmbeddingBenchmark.run(encoder)
        Log.i(TAG, "\n$report")

        val query = encoder.embedQuery("any pops with dragons?")
        val drogon = encoder.embedDocument("Drogon. Series: Game of Thrones")
        val batman = encoder.embedDocument("Batman. Series: DC Super Heroes")
        assertTrue("embeddings should be non-null on-device", query != null && drogon != null && batman != null)
        assertEquals(768, query!!.size)

        val toDrogon = EmbeddingMath.cosine(query, drogon!!)
        val toBatman = EmbeddingMath.cosine(query, batman!!)
        Log.i(TAG, "cos(dragons, Drogon)=$toDrogon  cos(dragons, Batman)=$toBatman")
        assertTrue("dragons should be nearer Drogon ($toDrogon) than Batman ($toBatman)", toDrogon > toBatman)
    }

    private companion object {
        const val TAG = "EmbeddingBench"
    }
}
