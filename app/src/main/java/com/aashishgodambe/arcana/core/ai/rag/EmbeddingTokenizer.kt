package com.aashishgodambe.arcana.core.ai.rag

/**
 * The tokenizer seam for [EmbeddingGemmaEncoder]. EmbeddingGemma's `.tflite` consumes token *ids*, not
 * strings, so the text has to be tokenized with the model's SentencePiece vocabulary before inference —
 * the real cost of running the model on the raw LiteRT interpreter (the plan: "you tokenize explicitly").
 *
 * Kept behind an interface because the *how* is the open question of the raw path: the DJL HuggingFace
 * tokenizer (the documented reference) downloads its native `.so` at runtime, which doesn't fit a shipped
 * Android app, so the concrete impl is resolved against an Android-native SentencePiece separately. Until
 * one is wired, [EmbeddingGemmaEncoder] is constructed with a null tokenizer and presence-gates to the
 * lexical fallback — the app stays fully working.
 */
interface EmbeddingTokenizer {

    /**
     * Tokenize [text] (special tokens added per the model's convention) and pad/truncate to [maxLen],
     * returning the padded ids and the matching attention mask (1 for real tokens, 0 for padding).
     */
    fun encode(text: String, maxLen: Int): Encoding

    /** Padded token ids and the attention mask that tells the model which positions are real. */
    data class Encoding(val ids: IntArray, val attentionMask: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is Encoding && ids.contentEquals(other.ids) && attentionMask.contentEquals(other.attentionMask)

        override fun hashCode(): Int = 31 * ids.contentHashCode() + attentionMask.contentHashCode()
    }
}
