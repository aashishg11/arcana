package com.aashishgodambe.arcana.core.ai.writing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.concurrent.futures.await
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import javax.inject.Inject

/**
 * ML Kit GenAI **Rewriting** impl of [ListingWriter] — the `genai-writing-assistance` capability, on-device
 * Gemini Nano. It composes a terse listing from the item's *own* data ([ListingComposer]) and rewrites it
 * with the ELABORATE tone into fuller sale copy. **No eBay data ever reaches the model** (eBay's 2025 AI
 * terms — the median is shown alongside in the UI, not fed in).
 *
 * Reuses the Week-3 GenAI discipline: lazy feature provisioning ([FeatureStatus]), streaming inference, and
 * a graceful failure path. Two on-device realities are handled honestly, not as crashes:
 * - **Foreground-only** (Nano/AICore): called from the "Draft a listing" sheet, which is always foreground.
 * - **Safety refusal** (ErrorCode 11) on horror/fantasy IP → surfaced as an [InferenceResult.Error] the sheet
 *   renders as an honest empty state.
 */
class MlKitListingWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) : ListingWriter {

    override fun draft(item: FunkoPop): Flow<InferenceResult> = channelFlow {
        val options = RewriterOptions.builder(context)
            .setOutputType(RewriterOptions.OutputType.ELABORATE)
            .setLanguage(RewriterOptions.Language.ENGLISH)
            .build()
        val rewriter = Rewriting.getClient(options)
        try {
            val status = rewriter.checkFeatureStatus().await()
            if (status != FeatureStatus.AVAILABLE) {
                // Provision for a later attempt, but don't block this one on a multi-minute download.
                if (status == FeatureStatus.DOWNLOADABLE) runCatching { rewriter.downloadFeature(NoOpDownloadCallback) }
                Log.w(TAG, "rewriting not ready (status=$status)")
                send(InferenceResult.Error(IllegalStateException("On-device writer is still setting up"), fallbackAvailable = false))
                return@channelFlow
            }

            val request = RewritingRequest.builder(ListingComposer.compose(item)).build()
            val started = SystemClock.elapsedRealtime()
            var firstTokenAt: Long? = null
            val builder = StringBuilder()
            val future = rewriter.runInference(request) { chunk ->
                if (firstTokenAt == null) firstTokenAt = SystemClock.elapsedRealtime() - started
                builder.append(chunk)
                trySend(InferenceResult.Streaming(builder.toString()))
            }
            future.await()
            send(
                InferenceResult.Success(
                    fullText = builder.toString().trim(),
                    metadata = InferenceMetadata(
                        executedOn = InferenceLocation.OnDevice,
                        totalLatencyMs = SystemClock.elapsedRealtime() - started,
                        firstTokenLatencyMs = firstTokenAt,
                        outputTokenCount = null,
                    ),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "rewriting failed: ${e.message}")
            send(InferenceResult.Error(e, fallbackAvailable = false))
        } finally {
            rewriter.close()
        }
    }

    /** Fire-and-forget provisioning trigger — AICore continues the download at the system level. */
    private object NoOpDownloadCallback : DownloadCallback {
        override fun onDownloadStarted(bytesToDownload: Long) {}
        override fun onDownloadProgress(totalBytesDownloaded: Long) {}
        override fun onDownloadCompleted() {}
        override fun onDownloadFailed(e: GenAiException) {}
    }

    private companion object {
        const val TAG = "ListingWriter"
    }
}
