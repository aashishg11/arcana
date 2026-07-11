package com.aashishgodambe.arcana.core.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import com.aashishgodambe.arcana.core.ai.model.InferenceMetadata
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The **own-model** [GeminiService]: the self-quantized Gemma 3 1B (INT4) I benchmarked in Week 6,
 * running in-process on the **CPU** via the MediaPipe LLM Inference runtime (LiteRT-LM under the hood).
 *
 * This is the shipping form of the whole ExecuTorch→LiteRT arc. Week 6 measured LiteRT q4-on-CPU at
 * 27.4 tok/s / 1077 MB — beating my ExecuTorch build on both axes — after finding the Tensor G5 TPU
 * (#7787) and PowerVR GPU are dead ends. So the config here is deliberately **CPU only**: the runtime's
 * own accelerator probe re-confirms that verdict in-app (NPU → InvalidArgument, every GPU accelerator
 * fails to load, XNNPACK/CPU carries it).
 *
 * **Model delivery is a side-load** (dev/showcase, not a production download): the ~584 MB q4 file is
 * pushed to [getExternalFilesDir]`("models")`, never bundled in the APK. Absent (or unreadable) model →
 * [isModelAvailable] is false and [generateText] emits a clean [InferenceResult.Error]; nothing crashes.
 *
 * **Concurrency:** the underlying engine is single-inference, so every call is serialized behind a
 * [Mutex] (same discipline as [HybridGeminiService]). Streaming maps MediaPipe's incremental result
 * listener onto [InferenceResult.Streaming] (cumulative text) → [InferenceResult.Success].
 */
class LiteRtGeminiService(
    private val appContext: Context,
) : OwnModelEngine {

    private val mutex = Mutex()

    /** Created once on first use and cached on success, so a later side-load can still bring it online. */
    @Volatile
    private var engine: LlmInference? = null

    fun modelFile(): File = File(appContext.getExternalFilesDir(MODELS_DIR), MODEL_FILE)

    override fun isModelAvailable(): Boolean = modelFile().let { it.exists() && it.canRead() }

    override fun generateText(prompt: String, routingHint: RoutingHint): Flow<InferenceResult> = flow {
        if (!isModelAvailable()) {
            emit(InferenceResult.Error(ModelUnavailable(modelFile().absolutePath), fallbackAvailable = false))
            return@flow
        }
        val active = engineOrNull()
        if (active == null) {
            emit(InferenceResult.Error(ModelUnavailable(modelFile().absolutePath), fallbackAvailable = false))
            return@flow
        }

        mutex.withLock {
            val start = SystemClock.elapsedRealtime()
            var firstTokenAt: Long? = null
            val sb = StringBuilder()
            val partials = Channel<String>(Channel.UNLIMITED) // cumulative snapshots
            val done = CompletableDeferred<Unit>()

            // MediaPipe streams incremental chunks on its own thread; forward cumulative snapshots to the
            // Flow. The per-call ProgressListener (0.10.35) means no shared listener state to serialize.
            val listener = ProgressListener<String> { partial, isDone ->
                if (!partial.isNullOrEmpty()) {
                    if (firstTokenAt == null) firstTokenAt = SystemClock.elapsedRealtime()
                    sb.append(partial)
                    partials.trySend(sb.toString())
                }
                if (isDone) {
                    partials.close()
                    done.complete(Unit)
                }
            }

            try {
                active.generateResponseAsync(formatPrompt(prompt), listener)
                for (snapshot in partials) emit(InferenceResult.Streaming(snapshot))
                done.await()

                val total = SystemClock.elapsedRealtime() - start
                val fullText = sb.toString()
                // Honest token count: MediaPipe's stream doesn't hand back a decode count, but the engine
                // can tokenize the finished text. If the call ever fails, report n/a (null) like Nano does.
                val tokens = runCatching { active.sizeInTokens(fullText) }.getOrNull()
                Log.i(
                    TAG,
                    "done · own-model · first-token=${firstTokenAt?.minus(start)}ms · total=${total}ms · tokens=$tokens",
                )
                emit(
                    InferenceResult.Success(
                        fullText = fullText,
                        metadata = InferenceMetadata(
                            executedOn = InferenceLocation.OnDeviceOwnModel,
                            totalLatencyMs = total,
                            firstTokenLatencyMs = firstTokenAt?.minus(start),
                            outputTokenCount = tokens,
                        ),
                    ),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "own-model inference failed", t)
                emit(InferenceResult.Error(t, fallbackAvailable = false))
            }
        }
    }

    /** Lazily builds the engine, caching only on success so a post-launch side-load can still succeed. */
    private fun engineOrNull(): LlmInference? {
        engine?.let { return it }
        if (!isModelAvailable()) return null
        return synchronized(this) {
            engine ?: runCatching { buildEngine() }
                .onFailure { Log.e(TAG, "engine init failed", it) }
                .getOrNull()
                ?.also { engine = it }
        }
    }

    private fun buildEngine(): LlmInference {
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelFile().absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .setPreferredBackend(LlmInference.Backend.CPU) // TPU #7787 + PowerVR are dead ends on G5
            .build()
        return LlmInference.createFromOptions(appContext, options)
    }

    /** Gemma 3 IT expects turn markers; the `.litertlm` reports has_prompt_templates=0, so we add them. */
    private fun formatPrompt(prompt: String): String =
        "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"

    /** Thrown when the side-loaded model is missing/unreadable — surfaced as a clean, explanatory error. */
    class ModelUnavailable(path: String) :
        IllegalStateException("Your Gemma model isn't installed. Side-load it to: $path")

    private companion object {
        const val TAG = "LiteRtGemini"
        const val MODELS_DIR = "models"
        const val MODEL_FILE = "gemma3-1b-it-int4.litertlm"
        // Ask Arcana prompts (instructions + a few grounded rows + short history) fit well under this;
        // it also bounds a runaway generation. Tune if longer grounding contexts appear.
        const val MAX_TOKENS = 1024
    }
}
