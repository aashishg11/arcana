package com.aashishgodambe.arcana.core.ai.summary

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.concurrent.futures.await
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.domain.model.WeeklyDeltas
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.SummarizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import javax.inject.Inject

/**
 * ML Kit GenAI Summarization impl of [CollectionSummarizer] — the `genai-summarization` sample's API,
 * on-device Gemini Nano. The API summarizes *prose* into bullets, so the structured weekly deltas are
 * first rendered into an "article" (≥400 chars, the ARTICLE minimum) and then summarized.
 *
 * Emits the same [InferenceResult] stream as the Gemini path so the card and badge are engine-agnostic.
 * The summarization feature (AICore feature 2004) provisions lazily; when it isn't yet `AVAILABLE` this
 * emits an [InferenceResult.Error] *immediately* (kicking off provisioning in the background for a later
 * render) rather than blocking on a multi-minute model download — the fallback engine takes this render.
 */
class MlKitCollectionSummarizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : CollectionSummarizer {

    override fun summarize(deltas: WeeklyDeltas): Flow<InferenceResult> = channelFlow {
        val options = SummarizerOptions.builder(context)
            .setInputType(SummarizerOptions.InputType.ARTICLE)
            .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
            .setLanguage(SummarizerOptions.Language.ENGLISH)
            .build()
        val summarizer = Summarization.getClient(options)
        try {
            val status = summarizer.checkFeatureStatus().await()
            if (status != FeatureStatus.AVAILABLE) {
                // Feature 2004 provisions lazily. Trigger the download for a future render, but don't
                // block this one — signal not-ready so the caller falls back to the Gemini path now.
                if (status == FeatureStatus.DOWNLOADABLE) {
                    runCatching { summarizer.downloadFeature(NoOpDownloadCallback) }
                }
                Log.w(TAG, "summarization feature not ready (status=$status); falling back")
                send(InferenceResult.Error(IllegalStateException("ML Kit summarization not ready"), fallbackAvailable = true))
                return@channelFlow
            }

            val request = SummarizationRequest.builder(renderArticle(deltas)).build()
            val started = SystemClock.elapsedRealtime()
            var firstTokenAt: Long? = null
            val builder = StringBuilder()
            val future = summarizer.runInference(request) { chunk ->
                if (firstTokenAt == null) firstTokenAt = SystemClock.elapsedRealtime() - started
                builder.append(chunk)
                trySend(InferenceResult.Streaming(builder.toString()))
            }
            val result = future.await()
            send(
                InferenceResult.Success(
                    fullText = result.summary.trim(),
                    metadata = InferenceMetadata(
                        executedOn = InferenceLocation.OnDevice,   // ML Kit GenAI runs on Gemini Nano
                        totalLatencyMs = SystemClock.elapsedRealtime() - started,
                        firstTokenLatencyMs = firstTokenAt,
                        outputTokenCount = null,
                    ),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit summarization failed; falling back: ${e.message}")
            send(InferenceResult.Error(e, fallbackAvailable = true))
        } finally {
            summarizer.close()
        }
    }

    /**
     * Renders the deltas into a prose "article" for the summarizer to condense into bullets. The ARTICLE
     * input type requires ≥400 characters, so this describes *every* list's move with full sentences plus
     * framing — the structured deltas expanded into enough prose for the summarizer to work on.
     */
    private fun renderArticle(deltas: WeeklyDeltas): String {
        val total = deltas.totalDeltaCents
        val overall = if (total >= 0) "rose by ${dollars(total)}" else "fell by ${dollars(-total)}"
        val sentences = deltas.lists.map { d ->
            when {
                d.deltaCents > 0 ->
                    "The ${d.listName} list gained ${dollars(d.deltaCents)}, rising from ${dollars(d.previousCents)} to ${dollars(d.currentCents)}."
                d.deltaCents < 0 ->
                    "The ${d.listName} list lost ${dollars(-d.deltaCents)}, slipping from ${dollars(d.previousCents)} to ${dollars(d.currentCents)}."
                else ->
                    "The ${d.listName} list held steady at ${dollars(d.currentCents)}."
            }
        }
        return buildString {
            append("This is the weekly value report for a personal Funko Pop collection tracked on-device. ")
            append("Over the past week, the collection's overall value $overall. ")
            append("Here is how each list moved over the week. ")
            append(sentences.joinToString(" "))
        }
    }

    private fun dollars(cents: Int): String = "$" + "%,d".format(cents / 100)

    /** Fire-and-forget provisioning trigger — AICore continues the download at the system level. */
    private object NoOpDownloadCallback : DownloadCallback {
        override fun onDownloadStarted(bytesToDownload: Long) {}
        override fun onDownloadProgress(totalBytesDownloaded: Long) {}
        override fun onDownloadCompleted() {}
        override fun onDownloadFailed(e: GenAiException) {}
    }

    private companion object {
        const val TAG = "ArcanaMlKitSummary"
    }
}
