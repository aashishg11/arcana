package com.aashishgodambe.arcana.core.ai.writing

import android.util.Log
import androidx.concurrent.futures.await
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Go/no-go probe for the listing writer: does ML Kit GenAI Rewriting's safety filter **refuse a horror/
 * fantasy pop** the way the Week-8 fixed captioner did? The collection is full of horror/fantasy IP
 * (Pennywise, Annabelle, Freddy), so if Rewriting rejects them the writer needs a fallback. Runs a benign
 * listing and a horror listing through the real API on-device and logs both outcomes.
 */
@RunWith(AndroidJUnit4::class)
class RewritingSafetyTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun rewrites_a_normal_and_a_horror_listing(): Unit = runBlocking {
        val options = RewriterOptions.builder(ctx)
            .setOutputType(RewriterOptions.OutputType.ELABORATE)
            .setLanguage(RewriterOptions.Language.ENGLISH)
            .build()
        val rewriter = Rewriting.getClient(options)
        try {
            var status = rewriter.checkFeatureStatus().await()
            Log.i(TAG, "feature status = $status")
            if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                awaitDownload(rewriter)
                status = rewriter.checkFeatureStatus().await()
            }
            assumeTrue("Rewriting not AVAILABLE (status=$status) — provision + re-run", status == FeatureStatus.AVAILABLE)

            Log.i(TAG, "NORMAL → " + rewrite(rewriter, NORMAL))
            Log.i(TAG, "HORROR → " + rewrite(rewriter, HORROR))
        } finally {
            rewriter.close()
        }
    }

    private suspend fun rewrite(rewriter: Rewriter, text: String): String = try {
        rewriter.runInference(RewritingRequest.builder(text).build()).await()
            .results.firstOrNull()?.text ?: "(empty result)"
    } catch (e: Exception) {
        "REFUSED/ERROR: ${e.javaClass.simpleName}: ${e.message}"
    }

    private suspend fun awaitDownload(rewriter: Rewriter) = suspendCancellableCoroutine<Unit> { cont ->
        rewriter.downloadFeature(object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) { Log.i(TAG, "download started: $bytesToDownload bytes") }
            override fun onDownloadProgress(totalBytesDownloaded: Long) {}
            override fun onDownloadCompleted() { if (cont.isActive) cont.resume(Unit) }
            override fun onDownloadFailed(e: GenAiException) { if (cont.isActive) cont.resumeWithException(e) }
        })
    }

    private companion object {
        const val TAG = "RewritingSafety"
        const val NORMAL =
            "Funko Pop Fire Nation Aang, number 04, from Avatar The Last Airbender. Mint condition, boxed. For sale."
        const val HORROR =
            "Funko Pop Pennywise with Spider Legs, from the horror movie IT. Mint condition, boxed. For sale."
    }
}
