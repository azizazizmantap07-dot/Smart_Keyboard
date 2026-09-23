package com.smartkeyboard.ime.autocorrect

import android.content.Context
import android.util.Log
import java.util.Locale
import kotlin.math.ln

/**
 * Layer 2 — context / language model interface.
 * Lightweight n-gram bigram scorer (no neural / ONNX).
 */
interface ContextLanguageModel {
    /** Higher score = more likely given [previousWords] context. */
    fun score(candidate: String, previousWords: List<String>): Float
    fun close() {}
}

/**
 * Fast bigram/trigram context scorer.
 */
class NgramContextModel(context: Context) : ContextLanguageModel {

    private val bigrams = HashMap<String, HashMap<String, Int>>(256)
    private val unigram = HashMap<String, Int>(512)

    init {
        try {
            context.assets.open("bigrams_id.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split("\t")
                    if (p.size >= 3) {
                        val a = p[0].trim().lowercase(Locale.getDefault())
                        val b = p[1].trim().lowercase(Locale.getDefault())
                        val f = p[2].toIntOrNull() ?: 1
                        bigrams.getOrPut(a) { HashMap() }[b] = f
                        unigram[b] = (unigram[b] ?: 0) + f
                        unigram[a] = (unigram[a] ?: 0) + f
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bigrams", e)
        }
    }

    fun learnBigram(prev: String, next: String, boost: Int = 3) {
        val a = prev.lowercase(Locale.getDefault())
        val b = next.lowercase(Locale.getDefault())
        if (a.isEmpty() || b.isEmpty()) return
        val m = bigrams.getOrPut(a) { HashMap() }
        m[b] = (m[b] ?: 0) + boost
        unigram[b] = (unigram[b] ?: 0) + boost
    }

    override fun score(candidate: String, previousWords: List<String>): Float {
        val cand = candidate.lowercase(Locale.getDefault())
        val prev = previousWords.lastOrNull()?.lowercase(Locale.getDefault()).orEmpty()
        val bi = if (prev.isNotEmpty()) bigrams[prev]?.get(cand) ?: 0 else 0
        val uni = unigram[cand] ?: 0
        val biScore = if (bi > 0) ln(1.0 + bi).toFloat() * 2.5f else 0f
        val uniScore = if (uni > 0) ln(1.0 + uni).toFloat() * 0.4f else 0f
        return biScore + uniScore
    }

    companion object {
        private const val TAG = "NgramContext"
    }
}
