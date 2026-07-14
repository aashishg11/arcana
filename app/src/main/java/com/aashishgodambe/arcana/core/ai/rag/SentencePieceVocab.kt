package com.aashishgodambe.arcana.core.ai.rag

import java.io.File

/**
 * The vocabulary of a SentencePiece model, parsed straight from the `sentencepiece.model` protobuf with a
 * tiny hand-rolled wire reader — no protobuf runtime dependency. Only what the tokenizer needs is read:
 * the ordered `pieces` (field 1 of `ModelProto`), each a `{piece: string (1), score: float (2),
 * type: enum (3)}` submessage whose **index is its token id**.
 *
 * Kept separate from [GemmaSentencePieceTokenizer] so the merge logic can be unit-tested against a tiny
 * synthetic vocab (no 4 MB gated file needed), and so a different SentencePiece model could be dropped in.
 */
class SentencePieceVocab private constructor(
    private val idToPiece: Array<String>,
    private val pieceToId: HashMap<String, Int>,
    private val scores: FloatArray,
    private val types: IntArray,
) {
    val size: Int get() = idToPiece.size

    fun idOf(piece: String): Int? = pieceToId[piece]
    fun pieceOf(id: Int): String = idToPiece[id]
    fun scoreOf(id: Int): Float = scores[id]
    fun contains(piece: String): Boolean = pieceToId.containsKey(piece)

    /** SentencePiece piece types (from the proto enum); [BYTE] pieces back byte-fallback. */
    fun typeOf(id: Int): Int = types[id]

    /** A parsed piece: its text, its log-probability score (BPE merge priority), and its type enum. */
    data class Piece(val piece: String, val score: Float, val type: Int)

    companion object {
        const val TYPE_NORMAL = 1
        const val TYPE_UNKNOWN = 2
        const val TYPE_CONTROL = 3
        const val TYPE_USER_DEFINED = 4
        const val TYPE_BYTE = 6

        fun fromFile(file: File): SentencePieceVocab = of(parsePieces(file.readBytes()))

        /** Build directly from pieces — the JVM-test entry point (id == list index). */
        fun of(pieces: List<Piece>): SentencePieceVocab {
            val idToPiece = Array(pieces.size) { pieces[it].piece }
            val pieceToId = HashMap<String, Int>(pieces.size * 2)
            // First occurrence wins: SentencePiece ids are unique, but guard against a malformed dup.
            for ((id, p) in pieces.withIndex()) pieceToId.putIfAbsent(p.piece, id)
            val scores = FloatArray(pieces.size) { pieces[it].score }
            val types = IntArray(pieces.size) { pieces[it].type }
            return SentencePieceVocab(idToPiece, pieceToId, scores, types)
        }

        /**
         * Parse the ordered `ModelProto.pieces` (field 1) from the raw protobuf bytes. SentencePiece writes
         * the pieces contiguously at the front, then `trainer_spec`/`normalizer_spec` — which carry a
         * precompiled charsmap and can use encodings we neither need nor decode — so we stop at the first
         * non-piece field rather than skipping through them.
         */
        internal fun parsePieces(bytes: ByteArray): List<Piece> {
            val reader = ProtoReader(bytes)
            val pieces = ArrayList<Piece>()
            while (!reader.exhausted()) {
                val tag = reader.readTag()
                val field = tag ushr 3
                val wire = tag and 0x7
                if (field == 1 && wire == WIRE_LEN) {
                    pieces.add(parsePiece(reader.readLengthDelimited()))
                } else {
                    break   // past the pieces (trainer_spec et al.) — we have everything we need
                }
            }
            return pieces
        }

        private fun parsePiece(bytes: ByteArray): Piece {
            val reader = ProtoReader(bytes)
            var piece = ""
            var score = 0f
            var type = TYPE_NORMAL
            while (!reader.exhausted()) {
                val tag = reader.readTag()
                when (val field = tag ushr 3) {
                    1 -> piece = String(reader.readLengthDelimited(), Charsets.UTF_8)
                    2 -> score = Float.fromBits(reader.readFixed32())
                    3 -> type = reader.readVarint().toInt()
                    else -> reader.skip(tag and 0x7)
                }
            }
            return Piece(piece, score, type)
        }

        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_LEN = 2
        private const val WIRE_FIXED32 = 5

        /** Minimal protobuf wire reader: varints, fixed32, length-delimited, and field skipping. */
        private class ProtoReader(private val buf: ByteArray) {
            private var pos = 0

            fun exhausted(): Boolean = pos >= buf.size
            fun readTag(): Int = readVarint().toInt()

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = buf[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b < 0x80) return result
                    shift += 7
                }
            }

            fun readFixed32(): Int {
                val b0 = buf[pos++].toInt() and 0xFF
                val b1 = buf[pos++].toInt() and 0xFF
                val b2 = buf[pos++].toInt() and 0xFF
                val b3 = buf[pos++].toInt() and 0xFF
                return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            }

            fun readLengthDelimited(): ByteArray {
                val len = readVarint().toInt()
                val out = buf.copyOfRange(pos, pos + len)
                pos += len
                return out
            }

            /** Advance past a field of the given wire type without decoding it. */
            fun skip(wire: Int) {
                when (wire) {
                    WIRE_VARINT -> readVarint()
                    WIRE_FIXED64 -> pos += 8
                    WIRE_LEN -> pos += readVarint().toInt()
                    WIRE_FIXED32 -> pos += 4
                    else -> error("unsupported wire type $wire")
                }
            }
        }
    }
}
