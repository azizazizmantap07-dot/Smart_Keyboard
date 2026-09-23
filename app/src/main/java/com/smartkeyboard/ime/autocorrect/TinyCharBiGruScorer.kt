package com.smartkeyboard.ime.autocorrect

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * On-device candidate scorer using the lightweight TinyChar-BiGRU ONNX model
 * (char-level BiGRU + hashed word-context features, INT8, ~230 KB).
 *
 * Unlike the previous DistilRoBERTa MLM ranker, this model does not need a
 * download step or a BPE tokenizer/vocab: it is small enough to ship directly
 * inside `assets/` and is loaded once at startup.
 *
 * Model I/O (see assets/tiny_char_bigru_int8.onnx):
 *   char_ids      int64 [batch, MAX_CHARS]  — per-character vocab ids of the candidate word
 *   char_mask     float32 [batch, MAX_CHARS] — 1.0 for real chars, 0.0 for padding
 *   ctx_hash_ids  int64 [batch, 2]          — hashed ids of the two preceding context words
 *                                              (EMPTY_CTX_BUCKET when no context word is present)
 *   -> score      float32 [batch]           — higher = candidate fits better (raw logit, unbounded)
 *
 * Character vocabulary and hashing scheme come from the training data
 * (`data/char_vocab.json`, bundled as `assets/char_vocab.json`) and must match
 * exactly what the model was trained with.
 */
class TinyCharBiGruScorer(context: Context) {

    private val appContext = context.applicationContext

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    private var charVocab: Map<Char, Int> = emptyMap()
    private var unkId: Int = 1
    private var padId: Int = 0

    @Volatile
    private var ready = false

    /** Model ships inside the APK, so it is always available once the class exists. */
    fun isModelAvailable(): Boolean = true

    /**
     * Load the ONNX model and character vocabulary from assets.
     * Call once (preferably on a background dispatcher) before the first [score] call.
     */
    suspend fun initialize() = withContext(Dispatchers.Default) {
        if (ready) return@withContext
        try {
            val vocabJson = readVocabJson()
            charVocab = parseCharVocab(vocabJson)
            padId = vocabJson.optInt("<pad>", 0)
            unkId = vocabJson.optInt("<unk>", 1)

            env = OrtEnvironment.getEnvironment()
            val opts = SessionOptions().apply {
                setIntraOpNumThreads(1)
                setOptimizationLevel(SessionOptions.OptLevel.BASIC_OPT)
            }
            val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
            session = env!!.createSession(modelBytes, opts)
            ready = true
            Log.i(TAG, "TinyCharBiGruScorer ready (${modelBytes.size / 1024} KB, vocab=${charVocab.size})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TinyChar-BiGRU model", e)
            ready = false
        }
    }

    /**
     * Score a single [candidate] word given up to two preceding context words
     * (most recent last, e.g. `listOf("aku", "mau")`). Higher score = better fit.
     * Returns null if the model isn't ready (caller should fall back to non-AI ranking).
     */
    suspend fun score(candidate: String, previousWords: List<String>): Float? =
        scoreBatch(listOf(candidate), previousWords).firstOrNull()

    /**
     * Score several [candidates] against the same context in a single inference pass.
     * Returns one score per candidate, in the same order, or an empty list if unavailable.
     */
    suspend fun scoreBatch(
        candidates: List<String>,
        previousWords: List<String>
    ): List<Float> = withContext(Dispatchers.Default) {
        if (candidates.isEmpty()) return@withContext emptyList()
        if (!ready) {
            try { initialize() } catch (_: Exception) { }
        }
        val sess = session
        val ortEnv = env
        if (!ready || sess == null || ortEnv == null) {
            return@withContext emptyList()
        }

        try {
            val n = candidates.size
            val charIds = LongArray(n * MAX_CHARS)
            val charMask = FloatArray(n * MAX_CHARS)
            for (i in candidates.indices) {
                encodeWord(candidates[i], charIds, charMask, i * MAX_CHARS)
            }

            val ctx = contextHashIds(previousWords)
            val ctxIds = LongArray(n * 2)
            for (i in 0 until n) {
                ctxIds[i * 2] = ctx[0]
                ctxIds[i * 2 + 1] = ctx[1]
            }

            val charIdsTensor = OnnxTensor.createTensor(
                ortEnv, java.nio.LongBuffer.wrap(charIds), longArrayOf(n.toLong(), MAX_CHARS.toLong())
            )
            val charMaskTensor = OnnxTensor.createTensor(
                ortEnv, java.nio.FloatBuffer.wrap(charMask), longArrayOf(n.toLong(), MAX_CHARS.toLong())
            )
            val ctxTensor = OnnxTensor.createTensor(
                ortEnv, java.nio.LongBuffer.wrap(ctxIds), longArrayOf(n.toLong(), 2L)
            )

            val inputs = mapOf(
                "char_ids" to charIdsTensor,
                "char_mask" to charMaskTensor,
                "ctx_hash_ids" to ctxTensor
            )

            sess.run(inputs).use { results ->
                val scoreTensor = results[0] as OnnxTensor
                val buf = scoreTensor.floatBuffer
                List(n) { buf.get(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scoreBatch inference failed", e)
            emptyList()
        }
    }

    /**
     * Rank [candidates] given the preceding context and return the single best one.
     * Falls back to the first candidate if the model is unavailable.
     */
    suspend fun rankCandidates(candidates: List<String>, previousWords: List<String>): String {
        if (candidates.isEmpty()) return ""
        if (candidates.size == 1) return candidates[0]
        val scores = scoreBatch(candidates, previousWords)
        if (scores.size != candidates.size) return candidates.first()
        var bestIdx = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in candidates.indices) {
            if (scores[i] > bestScore) {
                bestScore = scores[i]
                bestIdx = i
            }
        }
        return candidates[bestIdx]
    }

    private fun encodeWord(word: String, out: LongArray, mask: FloatArray, offset: Int) {
        val lower = word.lowercase(Locale.getDefault())
        for (i in 0 until MAX_CHARS) {
            if (i < lower.length) {
                out[offset + i] = (charVocab[lower[i]] ?: unkId).toLong()
                mask[offset + i] = 1f
            } else {
                out[offset + i] = padId.toLong()
                mask[offset + i] = 0f
            }
        }
    }

    /**
     * Hash the two most recent context words into the model's 4096-bucket table,
     * using the JVM's built-in `String.hashCode()` (Kotlin/Java are equivalent to
     * the polynomial hash used when the training pairs were generated). An absent
     * context word maps to bucket 0, matching training data where empty context
     * columns were left blank.
     */
    private fun contextHashIds(previousWords: List<String>): LongArray {
        val ctx = previousWords.map { it.lowercase(Locale.getDefault()) }.takeLast(2)
        val w1 = ctx.getOrNull(ctx.size - 2) ?: ""
        val w2 = ctx.getOrNull(ctx.size - 1) ?: ""
        return longArrayOf(hashBucket(w1), hashBucket(w2))
    }

    private fun hashBucket(word: String): Long {
        if (word.isEmpty()) return EMPTY_CTX_BUCKET.toLong()
        val h = word.hashCode()
        val mod = h % CTX_BUCKETS
        return (if (mod < 0) mod + CTX_BUCKETS else mod).toLong()
    }

    private fun readVocabJson(): JSONObject {
        appContext.assets.open(VOCAB_ASSET).use { stream ->
            return JSONObject(stream.bufferedReader().readText())
        }
    }

    private fun parseCharVocab(json: JSONObject): Map<Char, Int> {
        val map = HashMap<Char, Int>(32)
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.length == 1) {
                map[k[0]] = json.getInt(k)
            }
        }
        return map
    }

    fun close() {
        try {
            session?.close()
            session = null
            env?.close()
            env = null
            ready = false
        } catch (e: Exception) {
            Log.w(TAG, "close", e)
        }
    }

    companion object {
        private const val TAG = "TinyCharBiGruScorer"

        /** Bundled ONNX model (ships inside the APK, no download needed). */
        const val MODEL_ASSET = "tiny_char_bigru_int8.onnx"
        const val VOCAB_ASSET = "char_vocab.json"

        /** Max characters per word the model was trained with. */
        private const val MAX_CHARS = 16

        /** Number of hash buckets for context words (must match training: context_embed size). */
        private const val CTX_BUCKETS = 4096

        /** Bucket used for a missing/empty context word. */
        private const val EMPTY_CTX_BUCKET = 0
    }
}
