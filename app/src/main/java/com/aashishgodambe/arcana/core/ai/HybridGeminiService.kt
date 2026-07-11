package com.aashishgodambe.arcana.core.ai

import android.os.SystemClock
import android.util.Log
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FirebaseAIOnDeviceNotAvailableException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production [GeminiService], backed by Firebase AI Logic (Gemini Nano via AICore, cloud fallback).
 *
 * Routing: [RoutingHint.Auto] uses [InferenceMode.PREFER_ON_DEVICE], so the SDK runs on-device when
 * Nano is provisioned and transparently falls back to the cloud otherwise. We read
 * [InferenceSource] off each response to record where it *actually* ran. As defense-in-depth we also
 * catch [FirebaseAIOnDeviceNotAvailableException] (ErrorCode 606) and retry explicitly on cloud.
 *
 * Concurrency: AICore serves one inference at a time and throws BUSY on overlap, so every call is
 * serialized behind a [Mutex]. Latency is captured as first-token vs total, kept separate because
 * Nano's first call is a cold-start warm-up.
 */
@OptIn(PublicPreviewAPI::class)
class HybridGeminiService : GeminiService {

    private val mutex = Mutex()

    private val preferOnDevice: GenerativeModel by lazy { onDeviceModel(InferenceMode.PREFER_ON_DEVICE) }
    private val onlyOnDevice: GenerativeModel by lazy { onDeviceModel(InferenceMode.ONLY_ON_DEVICE) }
    private val cloudOnly: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(modelName = CLOUD_MODEL)
    }

    private fun onDeviceModel(mode: InferenceMode): GenerativeModel =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = CLOUD_MODEL, // required even for on-device; used as the fallback target
            onDeviceConfig = OnDeviceConfig(mode = mode),
        )

    override fun generateText(prompt: String, routingHint: RoutingHint): Flow<InferenceResult> = flow {
        mutex.withLock {
            val primary = when (routingHint) {
                RoutingHint.OnlyOnDevice -> onlyOnDevice
                RoutingHint.OnlyCloud -> cloudOnly
                // OnlyOwnModel is the own-model engine's lane — the DelegatingGeminiService routes it to
                // LiteRt and never here. If it somehow arrives, degrade to on-device rather than crash.
                RoutingHint.Auto, RoutingHint.PreferOnDevice, RoutingHint.OnlyOwnModel -> preferOnDevice
            }
            try {
                streamFrom(primary, prompt, routingHint)
            } catch (t: Throwable) {
                val canFallback = routingHint == RoutingHint.Auto || routingHint == RoutingHint.PreferOnDevice
                if (t is FirebaseAIOnDeviceNotAvailableException && canFallback) {
                    Log.w(TAG, "on-device unavailable (606) — retrying on cloud", t)
                    try {
                        streamFrom(cloudOnly, prompt, RoutingHint.OnlyCloud)
                    } catch (t2: Throwable) {
                        Log.e(TAG, "cloud fallback also failed", t2)
                        emit(InferenceResult.Error(t2, fallbackAvailable = false))
                    }
                } else {
                    Log.e(TAG, "inference failed (hint=$routingHint)", t)
                    emit(InferenceResult.Error(t, fallbackAvailable = canFallback))
                }
            }
        }
    }

    /**
     * Streams one model to completion, emitting [InferenceResult.Streaming] per chunk and a final
     * [InferenceResult.Success]. Throws on failure so the caller can decide whether to fall back —
     * partial [InferenceResult.Streaming] events may already have been emitted.
     */
    private suspend fun FlowCollector<InferenceResult>.streamFrom(
        model: GenerativeModel,
        prompt: String,
        hint: RoutingHint,
    ) {
        val start = SystemClock.elapsedRealtime()
        var firstTokenAt: Long? = null
        var source: InferenceSource? = null
        var outputTokens: Int? = null
        val sb = StringBuilder()

        model.generateContentStream(prompt).collect { chunk ->
            chunk.inferenceSource?.let { source = it }
            chunk.usageMetadata?.candidatesTokenCount?.let { outputTokens = it }
            val piece = chunk.text
            if (piece.isNullOrEmpty()) return@collect
            if (firstTokenAt == null) firstTokenAt = SystemClock.elapsedRealtime()
            sb.append(piece)
            emit(InferenceResult.Streaming(sb.toString()))
        }

        val total = SystemClock.elapsedRealtime() - start
        val firstToken = firstTokenAt?.minus(start)
        val location = source.toLocation(hint)
        Log.i(TAG, "done · ${location} · first-token=${firstToken}ms · total=${total}ms · tokens=$outputTokens")
        emit(
            InferenceResult.Success(
                fullText = sb.toString(),
                metadata = InferenceMetadata(
                    executedOn = location,
                    totalLatencyMs = total,
                    firstTokenLatencyMs = firstToken,
                    outputTokenCount = outputTokens,
                ),
            ),
        )
    }

    /** Prefer the SDK-reported source; fall back to intent when a response omits it. */
    private fun InferenceSource?.toLocation(hint: RoutingHint): InferenceLocation = when {
        this == InferenceSource.ON_DEVICE -> InferenceLocation.OnDevice
        this == InferenceSource.IN_CLOUD -> InferenceLocation.Cloud
        hint == RoutingHint.OnlyCloud -> InferenceLocation.Cloud
        else -> InferenceLocation.OnDevice
    }

    private companion object {
        const val TAG = "HybridGemini"
        // gemini-2.0-flash-lite is retired; 2.5 is current. Also the on-device fallback target.
        const val CLOUD_MODEL = "gemini-2.5-flash-lite"
    }
}
