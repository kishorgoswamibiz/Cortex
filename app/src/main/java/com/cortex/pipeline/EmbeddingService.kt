package com.cortex.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device embedding service backed by `all-MiniLM-L6-v2` ONNX (Tech Spec §3, §10).
 *
 * Loading is best-effort: if the model and tokenizer aren't bundled in
 * `assets/embeddings/`, we fall back to a deterministic hashed-bag embedding
 * so the rest of the Phase 2 pipeline still works end-to-end. The next agent
 * can drop in the real model files without code changes.
 *
 * Expected asset layout when bundling the real model:
 *   assets/embeddings/model.onnx        (int8-quantized MiniLM)
 *   assets/embeddings/vocab.txt         (WordPiece vocab)
 */
class EmbeddingService private constructor(
    private val session: OrtSession?,
    private val tokenizer: WordPieceTokenizer?
) {

    val modelVersion: String =
        if (session != null && tokenizer != null) "minilm-l6-v2-q-onnx" else "fallback-hash-v1"

    val dimension: Int = 384

    fun embed(text: String): FloatArray {
        if (session == null || tokenizer == null) return fallbackEmbed(text)
        return try {
            embedWithModel(text)
        } catch (t: Throwable) {
            Log.w(TAG, "ONNX embed failed, falling back: ${t.message}")
            fallbackEmbed(text)
        }
    }

    private fun embedWithModel(text: String): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val (ids, mask) = tokenizer!!.encode(text, maxLen = 128)
        val shape = longArrayOf(1, ids.size.toLong())

        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskTensor ->
                val inputs = HashMap<String, OnnxTensor>()
                inputs.put("input_ids", idTensor)
                inputs.put("attention_mask", maskTensor)
                // Some exports require token_type_ids as well.
                val tokenTypes = LongArray(ids.size) { 0L }
                OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypes), shape).use { ttTensor ->
                    if (session!!.inputNames.contains("token_type_ids")) {
                        inputs.put("token_type_ids", ttTensor)
                    }
                    session.run(inputs).use { result ->
                        // Expect last_hidden_state float[1, seqLen, hidden]
                        val firstOutput = result.iterator().next().value
                        @Suppress("UNCHECKED_CAST")
                        val raw = firstOutput.value as Array<Array<FloatArray>>
                        return meanPool(raw[0], mask).also { l2Normalize(it) }
                    }
                }
            }
        }
    }

    private fun meanPool(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = hidden[0].size
        val out = FloatArray(dim)
        var count = 0
        for (i in hidden.indices) {
            if (mask[i] == 0L) continue
            val row = hidden[i]
            for (j in 0 until dim) out[j] += row[j]
            count++
        }
        if (count == 0) return out
        val countF = count.toFloat()
        for (j in 0 until dim) out[j] = out[j] / countF
        return out
    }

    private fun l2Normalize(v: FloatArray) {
        var s = 0.0
        for (x in v) s += (x * x).toDouble()
        val n = sqrt(s).toFloat()
        if (n > 1e-8f) for (i in v.indices) v[i] /= n
    }

    /** Deterministic, non-contextual fallback so the pipeline runs without bundled assets. */
    private fun fallbackEmbed(text: String): FloatArray {
        val vec = FloatArray(dimension)
        if (text.isBlank()) return vec
        val tokens = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        for (tok in tokens) {
            val h = tok.hashCode()
            val idx = (h.toLong() and 0xFFFFFFFFL).toInt() % dimension
            // 1 if hash bit set, else -1 — random projection style.
            val sign = if (((h ushr 16) and 1) == 0) 1f else -1f
            vec[Math.abs(idx)] += sign
        }
        l2Normalize(vec)
        return vec
    }

    companion object {
        private const val TAG = "EmbeddingService"
        private const val MODEL_PATH = "embeddings/model.onnx"
        private const val VOCAB_PATH = "embeddings/vocab.txt"

        @Volatile private var INSTANCE: EmbeddingService? = null

        fun get(context: Context): EmbeddingService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): EmbeddingService {
            val assets = context.assets
            val available = try {
                assets.list("embeddings")?.toSet() ?: emptySet()
            } catch (_: Throwable) { emptySet() }

            if ("model.onnx" !in available || "vocab.txt" !in available) {
                Log.w(TAG, "MiniLM assets missing under assets/embeddings/ — using fallback embeddings.")
                return EmbeddingService(null, null)
            }
            return try {
                val modelBytes = assets.open(MODEL_PATH).use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                val session = env.createSession(modelBytes, opts)
                val vocab = assets.open(VOCAB_PATH).bufferedReader().useLines { it.toList() }
                EmbeddingService(session, WordPieceTokenizer(vocab))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to init MiniLM ONNX, falling back: ${t.message}")
                EmbeddingService(null, null)
            }
        }
    }
}

/** Minimal WordPiece tokenizer compatible with the bert-base-uncased vocab MiniLM uses. */
class WordPieceTokenizer(vocab: List<String>) {
    private val token2id: Map<String, Int> = vocab.withIndex().associate { (i, s) -> s to i }
    private val unkId = token2id["[UNK]"] ?: 100
    private val clsId = token2id["[CLS]"] ?: 101
    private val sepId = token2id["[SEP]"] ?: 102
    private val padId = token2id["[PAD]"] ?: 0

    fun encode(text: String, maxLen: Int = 128): Pair<LongArray, LongArray> {
        val pieces = mutableListOf<Int>()
        pieces.add(clsId)
        for (word in basicTokenize(text)) {
            pieces.addAll(wordpiece(word))
            if (pieces.size >= maxLen - 1) break
        }
        if (pieces.size >= maxLen) {
            pieces.subList(maxLen - 1, pieces.size).clear()
        }
        pieces.add(sepId)

        val ids = LongArray(maxLen) { padId.toLong() }
        val mask = LongArray(maxLen) { 0L }
        for (i in pieces.indices) {
            ids[i] = pieces[i].toLong()
            mask[i] = 1L
        }
        return ids to mask
    }

    private fun basicTokenize(text: String): List<String> {
        val lower = text.lowercase()
        return lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
    }

    private fun wordpiece(word: String): List<Int> {
        if (word.length > 100) return listOf(unkId)
        val out = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var curId: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = token2id[sub]
                if (id != null) { curId = id; break }
                end--
            }
            if (curId == null) return listOf(unkId)
            out.add(curId)
            start = end
        }
        return out
    }
}

object Vectors {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na.toDouble()) * sqrt(nb.toDouble())
        return if (d < 1e-8) 0f else (dot / d).toFloat()
    }

    fun toBlob(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putFloat(x)
        return bb.array()
    }

    fun fromBlob(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = bb.float
        return out
    }
}
