package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.ai.rag.SentencePieceVocab.Companion.TYPE_BYTE
import com.aashishgodambe.arcana.core.ai.rag.SentencePieceVocab.Companion.TYPE_CONTROL
import com.aashishgodambe.arcana.core.ai.rag.SentencePieceVocab.Companion.TYPE_NORMAL
import com.aashishgodambe.arcana.core.ai.rag.SentencePieceVocab.Piece
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import java.io.ByteArrayOutputStream
import org.junit.Test

/**
 * JVM tests for the pure-Kotlin Gemma tokenizer over a tiny **synthetic** vocab — no gated 4 MB model.
 * These prove the machinery (protobuf parse, BPE merge priority, byte-fallback, digit split, framing);
 * agreement with the *real* Gemma ids is validated separately on-device against the side-loaded model.
 */
class GemmaSentencePieceTokenizerTest {

    // id 0..3 are Gemma's specials; then whitespace, letters, two merges, digits, and a couple byte pieces.
    private val pieces = listOf(
        Piece("<pad>", 0f, TYPE_CONTROL),   // 0
        Piece("<eos>", 0f, TYPE_CONTROL),   // 1
        Piece("<bos>", 0f, TYPE_CONTROL),   // 2
        Piece("<unk>", 0f, TYPE_NORMAL),    // 3
        Piece("▁", -2f, TYPE_NORMAL),       // 4
        Piece("a", -5f, TYPE_NORMAL),       // 5
        Piece("b", -5f, TYPE_NORMAL),       // 6
        Piece("c", -5f, TYPE_NORMAL),       // 7
        Piece("ab", -5f, TYPE_NORMAL),      // 8  (low-priority merge)
        Piece("bc", -1f, TYPE_NORMAL),      // 9  (high-priority merge)
        Piece("2", -5f, TYPE_NORMAL),       // 10
        Piece("0", -5f, TYPE_NORMAL),       // 11
        Piece("20", -1f, TYPE_NORMAL),      // 12 (should NOT form when digits are split)
        Piece("<0xE2>", 0f, TYPE_BYTE),     // 13
        Piece("<0x82>", 0f, TYPE_BYTE),     // 14
        Piece("<0xAC>", 0f, TYPE_BYTE),     // 15
    )

    private fun tokenizer(addDummyPrefix: Boolean = false, splitDigits: Boolean = true) =
        GemmaSentencePieceTokenizer(SentencePieceVocab.of(pieces), addDummyPrefix, splitDigits)

    @Test
    fun `bpe merges the highest-scoring pair first`() {
        // "abc": (b,c)→bc scores -1, (a,b)→ab scores -5. bc wins, then a+bc is not a piece → [a, bc].
        assertArrayEquals(intArrayOf(5, 9), tokenizer().tokenizeToIds("abc"))
    }

    @Test
    fun `bpe merges an adjacent pair when it is the only option`() {
        // "ab": only (a,b)→ab. → [ab] = id 8.
        assertArrayEquals(intArrayOf(8), tokenizer().tokenizeToIds("ab"))
    }

    @Test
    fun `unknown characters fall back to their UTF-8 byte pieces`() {
        // "€" = E2 82 AC → the three byte pieces, in order.
        assertArrayEquals(intArrayOf(13, 14, 15), tokenizer().tokenizeToIds("€"))
    }

    @Test
    fun `split digits never merge into a multi-digit piece`() {
        // "20" with splitDigits → [2, 0] (ids 10, 11), NOT the "20" piece (id 12).
        assertArrayEquals(intArrayOf(10, 11), tokenizer(splitDigits = true).tokenizeToIds("20"))
        // …and without splitting, the merge is allowed → [20] (id 12).
        assertArrayEquals(intArrayOf(12), tokenizer(splitDigits = false).tokenizeToIds("20"))
    }

    @Test
    fun `dummy prefix escapes a leading space into the whitespace marker`() {
        // addDummyPrefix prepends ▁ (id 4) before "ab" (id 8).
        assertArrayEquals(intArrayOf(4, 8), tokenizer(addDummyPrefix = true).tokenizeToIds("ab"))
    }

    @Test
    fun `encode frames with BOS and EOS, pads, and builds the mask`() {
        val encoding = tokenizer().encode("ab", maxLen = 5)
        assertArrayEquals(intArrayOf(2, 8, 1, 0, 0), encoding.ids)          // <bos> ab <eos> <pad> <pad>
        assertArrayEquals(intArrayOf(1, 1, 1, 0, 0), encoding.attentionMask)
    }

    @Test
    fun `encode truncates the body so the BOS-EOS frame always fits maxLen`() {
        // body [a, bc] = 2 tokens; maxLen 3 leaves room for only 1 body token between BOS and EOS.
        val encoding = tokenizer().encode("abc", maxLen = 3)
        assertArrayEquals(intArrayOf(2, 5, 1), encoding.ids)                 // <bos> a <eos>
        assertArrayEquals(intArrayOf(1, 1, 1), encoding.attentionMask)
    }

    @Test
    fun `parsePieces reads piece text, score and type from real protobuf bytes`() {
        val bytes = modelProtoBytes(
            Triple("<pad>", 0f, TYPE_CONTROL),
            Triple("a", -1.5f, TYPE_NORMAL),
        )
        val vocab = SentencePieceVocab.of(SentencePieceVocab.parsePieces(bytes))
        assertEquals(2, vocab.size)
        assertEquals(0, vocab.idOf("<pad>"))
        assertEquals(1, vocab.idOf("a"))
        assertEquals(-1.5f, vocab.scoreOf(1), 1e-6f)
        assertEquals(TYPE_CONTROL, vocab.typeOf(0))
    }

    /** Hand-encode a ModelProto: repeated field 1 = pieces{ piece(1)=string, score(2)=float, type(3)=varint }. */
    private fun modelProtoBytes(vararg pieces: Triple<String, Float, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((piece, score, type) in pieces) {
            val sub = ByteArrayOutputStream()
            sub.write(0x0A); writeLen(sub, piece.toByteArray(Charsets.UTF_8))   // field 1, string
            sub.write(0x15); writeFixed32(sub, java.lang.Float.floatToRawIntBits(score)) // field 2, float
            sub.write(0x18); writeVarint(sub, type.toLong())                    // field 3, varint
            val subBytes = sub.toByteArray()
            out.write(0x0A); writeLen(out, subBytes)                            // top-level field 1
        }
        return out.toByteArray()
    }

    private fun writeLen(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeVarint(out, bytes.size.toLong()); out.write(bytes)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { out.write(b); return }
            out.write(b or 0x80)
        }
    }

    private fun writeFixed32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF); out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF); out.write((value ushr 24) and 0xFF)
    }
}
