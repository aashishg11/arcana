package com.aashishgodambe.arcana.core.ai.rag

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * On-device [CollectionEmbedder]: EmbeddingGemma-300M run directly on the **LiteRT interpreter**. This is
 * the raw path chosen in Gate A — the AI Edge RAG SDK ships no EmbeddingGemma embedder (Gecko only) and
 * its vector store over-engineers a 504-item corpus, so we run the `.tflite` ourselves and keep storage +
 * brute-force cosine in our own code.
 *
 * **Model delivery is a side-load** (gated Gemma, same discipline as [com.aashishgodambe.arcana.core.ai
 * .LiteRtGeminiService]): the ~200 MB `.tflite` is pushed to [getExternalFilesDir]`("models")`, never
 * bundled. Absent model *or* absent [tokenizer] → [isModelAvailable] is false and Ask falls back to
 * lexical retrieval; nothing crashes.
 *
 * **Signature-agnostic on purpose.** EmbeddingGemma exports vary (single vs multi input; pooled `[1,768]`
 * vs token `[1,seq,768]` output), so the interpreter is driven by **runtime tensor introspection** —
 * inputs are filled by tensor name, the sequence length is read from the model, and a token-level output
 * is mean-pooled with the attention mask. Returns the raw native-dimension vector; MRL truncation to the
 * shipping dimension is the caller's ([EmbeddingMath.truncate]).
 *
 * **Concurrency:** the interpreter isn't re-entrant, so calls serialize behind a [Mutex] (same discipline
 * as the own-model engine).
 */
class EmbeddingGemmaEncoder(
    private val appContext: Context,
) : CollectionEmbedder {

    override val nativeDimension = NATIVE_DIM

    private val mutex = Mutex()

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var tokenizer: EmbeddingTokenizer? = null

    fun modelFile(): File = File(appContext.getExternalFilesDir(MODELS_DIR), MODEL_FILE)

    /** The Gemma SentencePiece vocab, side-loaded alongside the `.tflite`. Both are required. */
    fun tokenizerFile(): File = File(appContext.getExternalFilesDir(MODELS_DIR), TOKENIZER_FILE)

    override fun isModelAvailable(): Boolean =
        modelFile().let { it.exists() && it.canRead() } &&
            tokenizerFile().let { it.exists() && it.canRead() }

    override suspend fun embedQuery(text: String): FloatArray? =
        embed(CollectionDocument.queryPrompt(text))

    override suspend fun embedDocument(text: String): FloatArray? =
        embed(CollectionDocument.documentPrompt(text))

    private suspend fun embed(prompt: String): FloatArray? {
        val tok = tokenizerOrNull() ?: return null
        val itp = interpreterOrNull() ?: return null
        return mutex.withLock {
            runCatching {
                val started = SystemClock.elapsedRealtime()
                val seqLen = sequenceLengthOf(itp)
                val encoding = tok.encode(prompt, seqLen)

                val inputs = buildInputs(itp, encoding)
                val outputs = HashMap<Int, Any>()
                val containers = allocateOutputs(itp, outputs)
                itp.runForMultipleInputsOutputs(inputs, outputs)

                val vector = pool(itp, containers, encoding.attentionMask)
                Log.i(TAG, "embed · seq=$seqLen · dim=${vector.size} · ${SystemClock.elapsedRealtime() - started}ms")
                vector
            }.onFailure { Log.w(TAG, "embed failed", it) }.getOrNull()
        }
    }

    /** The model's input sequence length, read from whichever input carries the token ids. */
    private fun sequenceLengthOf(itp: Interpreter): Int {
        for (i in 0 until itp.inputTensorCount) {
            val shape = itp.getInputTensor(i).shape()
            if (shape.size == 2 && shape[0] == 1) return shape[1]
        }
        return DEFAULT_SEQ_LEN
    }

    /** Fill each input tensor by name: a "mask" input gets the attention mask; everything else gets ids. */
    private fun buildInputs(itp: Interpreter, encoding: EmbeddingTokenizer.Encoding): Array<Any> =
        Array(itp.inputTensorCount) { i ->
            val tensor = itp.getInputTensor(i)
            val name = tensor.name().lowercase()
            val source = when {
                "mask" in name -> encoding.attentionMask
                "type" in name || "segment" in name -> IntArray(encoding.ids.size) // token-type ids: all 0
                else -> encoding.ids
            }
            // Match the tensor's declared int width (Gemma exports use int32; some use int64).
            if (tensor.dataType() == DataType.INT64) {
                arrayOf(LongArray(source.size) { source[it].toLong() })
            } else {
                arrayOf(source.copyOf())
            }
        }

    /** Allocate a float container for every output tensor; returns them by index for reading after run. */
    private fun allocateOutputs(itp: Interpreter, outputs: MutableMap<Int, Any>): Map<Int, Array<*>> {
        val containers = HashMap<Int, Array<*>>()
        for (i in 0 until itp.outputTensorCount) {
            val shape = itp.getOutputTensor(i).shape()
            val container = newFloatContainer(shape)
            containers[i] = container
            outputs[i] = container
        }
        return containers
    }

    /** Reduce the model outputs to a single embedding row: prefer a pooled `[1,D]`, else mean-pool `[1,seq,D]`. */
    private fun pool(itp: Interpreter, containers: Map<Int, Array<*>>, attentionMask: IntArray): FloatArray {
        // Prefer a rank-2 [1, D] pooled embedding.
        for (i in 0 until itp.outputTensorCount) {
            val shape = itp.getOutputTensor(i).shape()
            if (shape.size == 2 && shape[0] == 1) {
                @Suppress("UNCHECKED_CAST")
                return (containers.getValue(i) as Array<FloatArray>)[0]
            }
        }
        // Otherwise mean-pool a rank-3 [1, seq, D] token-embeddings output over the real tokens.
        for (i in 0 until itp.outputTensorCount) {
            val shape = itp.getOutputTensor(i).shape()
            if (shape.size == 3 && shape[0] == 1) {
                @Suppress("UNCHECKED_CAST")
                val tokens = (containers.getValue(i) as Array<Array<FloatArray>>)[0]
                return meanPool(tokens, attentionMask)
            }
        }
        error("no [1,D] or [1,seq,D] float output tensor found")
    }

    private fun meanPool(tokens: Array<FloatArray>, attentionMask: IntArray): FloatArray {
        val dim = tokens.first().size
        val sum = FloatArray(dim)
        var count = 0
        for (t in tokens.indices) {
            if (attentionMask.getOrElse(t) { 0 } == 0) continue
            count++
            val row = tokens[t]
            for (d in 0 until dim) sum[d] += row[d]
        }
        if (count == 0) return sum
        for (d in 0 until dim) sum[d] /= count
        return sum
    }

    /** Build a nested FloatArray matching [shape] for TFLite to write into (ranks 2 and 3 cover embedders). */
    private fun newFloatContainer(shape: IntArray): Array<*> = when (shape.size) {
        2 -> Array(shape[0]) { FloatArray(shape[1]) }
        3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
        else -> error("unsupported output rank ${shape.size} (shape ${shape.toList()})")
    }

    /** Lazily builds the tokenizer from the side-loaded vocab, caching only on success (like the interpreter). */
    private fun tokenizerOrNull(): EmbeddingTokenizer? {
        tokenizer?.let { return it }
        val file = tokenizerFile()
        if (!file.exists() || !file.canRead()) return null
        return synchronized(this) {
            tokenizer ?: runCatching { GemmaSentencePieceTokenizer.fromFile(file) }
                .onFailure { Log.e(TAG, "tokenizer init failed", it) }
                .getOrNull()
                ?.also { tokenizer = it; Log.i(TAG, "tokenizer ready · vocab=${it.vocabSize}") }
        }
    }

    /** Lazily builds the interpreter, caching only on success so a post-launch side-load can still succeed. */
    private fun interpreterOrNull(): Interpreter? {
        interpreter?.let { return it }
        if (!isModelAvailable()) return null
        return synchronized(this) {
            interpreter ?: runCatching { Interpreter(modelFile(), Interpreter.Options().apply { setNumThreads(NUM_THREADS) }) }
                .onFailure { Log.e(TAG, "interpreter init failed", it) }
                .getOrNull()
                ?.also { interpreter = it }
        }
    }

    private companion object {
        const val TAG = "EmbeddingGemma"
        const val MODELS_DIR = "models"
        const val MODEL_FILE = "embeddinggemma-300m.tflite"
        const val TOKENIZER_FILE = "sentencepiece.model"
        const val NATIVE_DIM = 768
        const val DEFAULT_SEQ_LEN = 512
        const val NUM_THREADS = 4
    }
}
