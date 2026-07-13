package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.aashishgodambe.arcana.core.ai.catalog.CatalogProviderChain
import com.aashishgodambe.arcana.core.ai.catalog.CatalogQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import javax.inject.Inject

/**
 * The capture-cascade engine: one entry point that turns a captured frame into a sourced identity,
 * emitting per-stage [CascadeState] so the Week-9 UI just subscribes. Pipeline:
 *   segment → describe (on-device LLM, best-effort, streaming) → OCR + layout → fuse hints → catalog
 *   chain (local → … → cloud) → settle.
 *
 * Robustness is deliberate: segmentation falling back to the full frame and a null/refused description
 * are normal, not errors — identification is carried by the OCR Pop number + catalog, with the LLM read a
 * corroborating hint. Only a hard failure in a required stage emits [CascadeState.Failed].
 */
class CaptureCascade @Inject constructor(
    private val segmenter: ImageSegmenter,
    private val describer: MultimodalDescriber,
    private val textExtractor: TextExtractor,
    private val barcodeScanner: BarcodeScanner,
    private val catalogChain: CatalogProviderChain,
) {

    /**
     * The demoted barcode fallback: decode the box's UPC and go straight to the catalog chain, skipping
     * segmentation, description, and OCR. A confident UPC hit in the local collection resolves instantly
     * and offline; a miss escalates like any other query.
     */
    fun identifyFromBarcode(bitmap: Bitmap): Flow<CascadeState> = channelFlow {
        val timings = LinkedHashMap<String, Long>()
        val started = SystemClock.elapsedRealtime()
        try {
            send(CascadeState.Matching)
            val upc = stage(timings, "barcode") { barcodeScanner.scan(bitmap) }
            if (upc == null) {
                send(CascadeState.Failed(stage = "barcode", cause = IllegalStateException("no barcode found")))
                return@channelFlow
            }
            val entry = stage(timings, "catalog") {
                catalogChain.identify(CatalogQuery(popNumber = null, franchise = null, character = null, upc = upc, image = bitmap))
            }
            send(
                CascadeState.Settled(
                    CascadeResult(
                        entry = entry,
                        confident = entry != null && entry.confidence >= CONFIDENCE_GATE,
                        owned = entry?.matchedLocalId != null,
                        telemetry = CascadeTelemetry(
                            totalMs = SystemClock.elapsedRealtime() - started,
                            perStageMs = timings.toMap(),
                            resolvedOn = entry?.executedOn,
                        ),
                    ),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "barcode cascade failed", e)
            send(CascadeState.Failed(stage = timings.keys.lastOrNull() ?: "barcode", cause = e))
        }
    }

    /** Single-frame convenience — the common path and what the tests + dev harness drive. */
    fun identify(bitmap: Bitmap): Flow<CascadeState> = identify(listOf(bitmap))

    /**
     * Identify from a small burst of frames. The first is the primary; the rest are the escalation burst
     * the engine consults **only** when the primary's OCR gives an uncorroborated Pop number (Week-8 bug:
     * a lone digit misreads silently under backlight). On the common corroborated read the extras are never
     * OCR'd, so the happy path pays no burst cost; when they are, OCR runs concurrently and the majority
     * vote picks the winning frame that feeds every downstream stage.
     */
    fun identify(frames: List<Bitmap>): Flow<CascadeState> = channelFlow {
        require(frames.isNotEmpty()) { "identify needs at least one frame" }
        val timings = LinkedHashMap<String, Long>()
        val started = SystemClock.elapsedRealtime()
        try {
            // Stage ordering is deliberate. Everything functional reads the FULL frame (box text lives on
            // the box, not the isolated figure). OCR runs FIRST: it's the deterministic, load-bearing
            // signal, and on this device invoking AICore (Nano) or subject segmentation first breaks the
            // ML Kit text recognizer process-wide. The on-device description and the UI-only segmentation
            // are best-effort and run after, where they can never block identification.
            send(CascadeState.Segmenting())

            // 1. OCR + positional layout — burst-and-vote when the primary read is weak. The winning frame
            //    becomes the one every later stage (describe, catalog, segment) reads.
            val (bitmap, layout) = stage(timings, "ocr") { resolveFrame(frames) }
            send(CascadeState.Read(layout))

            // 2. Describe (on-device LLM) on the full frame — best-effort, streaming, never fatal.
            val llm = runCatching {
                stage(timings, "describe") {
                    describer.describe(bitmap) { partial -> trySend(CascadeState.Describing(partial)) }
                }
            }.onFailure { Log.i(TAG, "on-device description unavailable: ${it.message}") }.getOrNull()

            // 3. Fuse the reads and walk the catalog chain (cloud escalation sees the full frame).
            send(CascadeState.Matching)
            val query = CascadeHintFusion.toQuery(layout, llm, image = bitmap)
            val entry = stage(timings, "catalog") { catalogChain.identify(query) }

            // 4. Segment last — best-effort masked subject for the UI outline; never blocks identification.
            val subject = stage(timings, "segment") {
                runCatching { segmenter.segment(bitmap).subjectBitmap }.getOrNull()
            }
            send(CascadeState.Segmenting(subject))

            // 5. Settle.
            val telemetry = CascadeTelemetry(
                totalMs = SystemClock.elapsedRealtime() - started,
                perStageMs = timings.toMap(),
                resolvedOn = entry?.executedOn,
            )
            send(
                CascadeState.Settled(
                    CascadeResult(
                        entry = entry,
                        confident = entry != null && entry.confidence >= CONFIDENCE_GATE,
                        owned = entry?.matchedLocalId != null,
                        telemetry = telemetry,
                    ),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "cascade failed", e)
            send(CascadeState.Failed(stage = timings.keys.lastOrNull() ?: "segment", cause = e))
        }
    }

    /**
     * OCR the primary frame; if its Pop number is uncorroborated (or absent) and a burst is available, OCR
     * the rest concurrently and majority-vote. Returns the winning (frame, layout) — the frame every
     * downstream stage then reads. Keeps the vote in the engine so the UI stays a pure renderer.
     */
    private suspend fun resolveFrame(frames: List<Bitmap>): Pair<Bitmap, BoxLayout> {
        val primary = frames.first()
        val primaryLayout = BoxLayoutParser.parse(textExtractor.extract(primary).lines)
        if (frames.size == 1 || OcrBurstVote.isCorroborated(primaryLayout)) return primary to primaryLayout

        Log.i(TAG, "primary read uncorroborated (#${primaryLayout.popNumber}); bursting ${frames.size - 1} more")
        val rest = coroutineScope {
            frames.drop(1).map { frame ->
                async { frame to BoxLayoutParser.parse(textExtractor.extract(frame).lines) }
            }.awaitAll()
        }
        val candidates = listOf(primary to primaryLayout) + rest
        val winner = candidates[OcrBurstVote.pick(candidates.map { it.second })]
        Log.i(TAG, "burst vote → #${winner.second.popNumber}")
        return winner
    }

    private suspend fun <T> ProducerScope<*>.stage(
        timings: MutableMap<String, Long>,
        name: String,
        block: suspend () -> T,
    ): T {
        val start = SystemClock.elapsedRealtime()
        return block().also { timings[name] = SystemClock.elapsedRealtime() - start }
    }

    private companion object {
        const val TAG = "CaptureCascade"
        // The composed-confidence gate (mirrors CatalogProviderChain): a Pop number needs a corroborating
        // signal to count as a confident, on-device identification.
        const val CONFIDENCE_GATE = 0.7f
    }
}
