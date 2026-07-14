package com.aashishgodambe.arcana.core.ai.rag

import java.io.File
import java.text.Normalizer
import java.util.PriorityQueue

/**
 * Pure-Kotlin [EmbeddingTokenizer] for Gemma's SentencePiece **BPE** vocabulary — the tokenizer
 * EmbeddingGemma was trained with. Chosen because every off-the-shelf option fails on Android: DJL
 * downloads its tokenizer `.so` at runtime, and the AI Edge RAG SDK's SentencePiece is locked inside a
 * Gecko-only JNI blob. Implementing it in Kotlin means **zero native deps, no runtime download, and full
 * JVM unit-testability** — the merge logic below is exercised against a tiny synthetic vocab, no gated
 * 4 MB model needed.
 *
 * The algorithm mirrors SentencePiece's `bpe_model.cc`: seed one symbol per character, then greedily merge
 * the adjacent pair whose combined piece has the highest vocab **score** (BPE merge priority) until none
 * remain; characters with no vocab piece fall back to their UTF-8 **`<0xNN>` byte pieces**. Gemma
 * conventions — `▁` whitespace, digit splitting, and BOS/EOS framing — are applied around the merge.
 *
 * A few conventions ([addDummyPrefix], BOS/EOS framing) are Gemma defaults to **confirm against the real
 * model on-device**; they're constructor knobs so tuning is a one-line change, not a rewrite.
 */
class GemmaSentencePieceTokenizer(
    private val vocab: SentencePieceVocab,
    private val addDummyPrefix: Boolean = true,
    private val splitDigits: Boolean = true,
) : EmbeddingTokenizer {

    /** Vocabulary size — a quick correctness signal (EmbeddingGemma is ~262k). */
    val vocabSize: Int get() = vocab.size

    private val bosId = vocab.idOf(BOS) ?: DEFAULT_BOS
    private val eosId = vocab.idOf(EOS) ?: DEFAULT_EOS
    private val padId = vocab.idOf(PAD) ?: DEFAULT_PAD
    private val unkId = vocab.idOf(UNK) ?: DEFAULT_UNK

    override fun encode(text: String, maxLen: Int): EmbeddingTokenizer.Encoding {
        val body = tokenizeToIds(text)
        // Frame with BOS…EOS, truncating the body so the frame always fits maxLen.
        val bodyBudget = (maxLen - 2).coerceAtLeast(0)
        val framed = ArrayList<Int>(maxLen)
        framed.add(bosId)
        for (i in 0 until minOf(body.size, bodyBudget)) framed.add(body[i])
        framed.add(eosId)

        val ids = IntArray(maxLen) { if (it < framed.size) framed[it] else padId }
        val mask = IntArray(maxLen) { if (it < framed.size) 1 else 0 }
        return EmbeddingTokenizer.Encoding(ids, mask)
    }

    /** The BPE-encoded token ids for [text], without BOS/EOS framing — the unit-tested core. */
    fun tokenizeToIds(text: String): IntArray {
        val ids = ArrayList<Int>()
        for (segment in preTokenize(text)) {
            bpeMerge(segment).forEach { piece -> emitPiece(piece, ids) }
        }
        return ids.toIntArray()
    }

    /**
     * Normalise and split into independently-merged segments: NFKC, spaces → `▁`, an optional leading `▁`,
     * and — when [splitDigits] — each digit as its own segment so numbers never merge into a neighbour.
     */
    private fun preTokenize(text: String): List<String> {
        var normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(' ', WHITESPACE)
        if (addDummyPrefix && normalized.isNotEmpty() && !normalized.startsWith(WHITESPACE)) {
            normalized = WHITESPACE + normalized
        }
        if (!splitDigits) return listOf(normalized).filter { it.isNotEmpty() }

        // Break the string so a run of non-digits is one segment and each digit is its own segment.
        val segments = ArrayList<String>()
        val current = StringBuilder()
        for (ch in normalized) {
            if (ch.isDigit()) {
                if (current.isNotEmpty()) { segments.add(current.toString()); current.clear() }
                segments.add(ch.toString())
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) segments.add(current.toString())
        return segments
    }

    /**
     * SentencePiece BPE over one segment: seed a symbol per character, then repeatedly merge the adjacent
     * pair whose concatenation is the highest-scoring vocab piece, via a priority queue with lazy
     * invalidation. Returns the final pieces (some may be OOV single characters → byte fallback downstream).
     */
    private fun bpeMerge(segment: String): List<String> {
        val symbols = segment.map { StringBuilder(it.toString()) }.toMutableList()
        if (symbols.size <= 1) return symbols.map { it.toString() }

        val prev = IntArray(symbols.size) { it - 1 }
        val next = IntArray(symbols.size) { if (it == symbols.size - 1) -1 else it + 1 }
        val alive = BooleanArray(symbols.size) { true }

        val queue = PriorityQueue<Merge>()
        fun tryPair(left: Int, right: Int) {
            if (left < 0 || right < 0) return
            val merged = symbols[left].toString() + symbols[right].toString()
            val id = vocab.idOf(merged) ?: return
            queue.add(Merge(left, right, vocab.scoreOf(id), merged))
        }
        for (i in 0 until symbols.size - 1) tryPair(i, i + 1)

        while (queue.isNotEmpty()) {
            val m = queue.poll()
            // Stale if either endpoint was consumed or they're no longer adjacent with the same text.
            if (!alive[m.left] || !alive[m.right] || next[m.left] != m.right) continue
            if (symbols[m.left].toString() + symbols[m.right].toString() != m.merged) continue

            symbols[m.left] = StringBuilder(m.merged)
            alive[m.right] = false
            val after = next[m.right]
            next[m.left] = after
            if (after >= 0) prev[after] = m.left
            tryPair(prev[m.left], m.left)
            tryPair(m.left, after)
        }

        // Walk from the head: index 0 is always alive (a merge only ever consumes the right symbol, and
        // right > left, so position 0 is never consumed — it only absorbs rightward).
        val result = ArrayList<String>()
        var i = 0
        while (i >= 0) {
            result.add(symbols[i].toString())
            i = next[i]
        }
        return result
    }

    /** Map a final piece to ids: direct vocab hit, else UTF-8 byte-fallback `<0xNN>`, else `<unk>`. */
    private fun emitPiece(piece: String, out: MutableList<Int>) {
        vocab.idOf(piece)?.let { out.add(it); return }
        for (b in piece.toByteArray(Charsets.UTF_8)) {
            val bytePiece = "<0x%02X>".format(b.toInt() and 0xFF)
            out.add(vocab.idOf(bytePiece) ?: unkId)
        }
    }

    /** A candidate merge, ordered by descending score then left position (SentencePiece tie-break). */
    private class Merge(val left: Int, val right: Int, val score: Float, val merged: String) : Comparable<Merge> {
        override fun compareTo(other: Merge): Int {
            val byScore = other.score.compareTo(score)   // higher score first
            return if (byScore != 0) byScore else left.compareTo(other.left)
        }
    }

    companion object {
        /** SentencePiece whitespace marker `▁` (U+2581), not an underscore. */
        const val WHITESPACE = '▁'
        private const val BOS = "<bos>"
        private const val EOS = "<eos>"
        private const val PAD = "<pad>"
        private const val UNK = "<unk>"
        // Gemma's canonical special-token ids, used only if the vocab lookup somehow misses.
        private const val DEFAULT_BOS = 2
        private const val DEFAULT_EOS = 1
        private const val DEFAULT_PAD = 0
        private const val DEFAULT_UNK = 3

        /** Load Gemma's tokenizer from a side-loaded `sentencepiece.model`. */
        fun fromFile(file: File): GemmaSentencePieceTokenizer =
            GemmaSentencePieceTokenizer(SentencePieceVocab.fromFile(file))
    }
}
