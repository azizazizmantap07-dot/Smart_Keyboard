package com.smartkeyboard.ime.autocorrect

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Hybrid auto-correct orchestrator.
 *
 * Layer 1 — Spatial (Trie + Weighted Levenshtein / QWERTY)
 * Layer 2 — Context (n-gram bigrams)
 * Layer 3 — Personal dictionary (Room)
 * Layer 4 — On-device TinyChar-BiGRU ONNX scorer (INT8, bundled in APK) for final
 *           candidate selection
 *
 * All heavy work runs off the main thread. Callers should cancel [predictionJob]
 * on every new keystroke (dynamic debouncing).
 */
class AutoCorrectEngine(context: Context) {

    private val appContext = context.applicationContext
    private val trie = TrieDictionary()
    private val personal = PersonalDictionary(appContext)
    private val contextModel: ContextLanguageModel = NgramContextModel(appContext)
    private val aiScorer = TinyCharBiGruScorer(appContext)

    /** Isolated AI / fuzzy dispatcher (single thread). */
    private val aiDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "AutoCorrect-Engine").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    @Volatile
    private var ready = false

    /** Last auto-corrected pair for undo-on-backspace. */
    data class CorrectionRecord(
        val original: String,
        val corrected: String,
        val beforeLength: Int
    )

    @Volatile
    var lastCorrection: CorrectionRecord? = null
        private set

    /** Recent committed words for context (max 3). */
    private val contextWindow = ArrayDeque<String>(4)

    var predictionJob: Job? = null

    suspend fun initialize() = withContext(Dispatchers.Default) {
        if (ready) return@withContext
        // Load large frequency-ranked dictionaries with decreasing score so common
        // words rank higher. Order in files is already frequency-sorted where possible.
        loadAssetDictRanked("dict_formal_id.txt", startFreq = 80, minFreq = 12)
        loadAssetDictRanked("dict_informal_id.txt", startFreq = 55, minFreq = 18)
        loadAssetDictRanked("dict_slang_id.txt", startFreq = 90, minFreq = 40)
        // Seed from optional user-downloaded frequency file (even larger)
        try {
            val freq = java.io.File(appContext.filesDir, "dict_id_freq.txt")
            if (freq.exists()) {
                var rank = 60_000
                freq.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val w = line.trim().split(Regex("\\s+")).firstOrNull()
                            ?.lowercase(Locale.getDefault()) ?: return@forEach
                        if (w.length >= 2) {
                            trie.insert(w, max(rank / 600, 6))
                            rank = (rank - 1).coerceAtLeast(1)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "freq dict load", e)
        }
        personal.warmUp()
        personal.cachedWords().forEach { (w, f) -> trie.insert(w, f + 60) }
        // Load bundled TinyChar-BiGRU ONNX model (non-blocking – ranker falls back if it fails)
        try {
            aiScorer.initialize()
        } catch (e: Exception) {
            Log.w(TAG, "AI scorer init failed, continuing without Layer 4", e)
        }
        ready = true
        Log.i(TAG, "AutoCorrect ready — trie size=${trie.size}")
    }

    /**
     * Load dictionary assigning decreasing frequency by line order.
     * First words (most common) get higher score → better ranking & lower latency
     * for typical typing because high-freq candidates surface earlier.
     */
    private fun loadAssetDictRanked(name: String, startFreq: Int, minFreq: Int) {
        try {
            var freq = startFreq
            val step = max(1, (startFreq - minFreq) / 8000) // gentle decay
            appContext.assets.open(name).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val w = line.trim().lowercase(Locale.getDefault())
                    if (w.length >= 2) {
                        trie.insert(w, freq)
                        freq = (freq - step).coerceAtLeast(minFreq)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "asset $name", e)
        }
    }

    /**
     * Top suggestions for the active partial word (or next-word if blank).
     * Returns up to 3 items; index 1 (middle) is the primary auto-correct candidate.
     */
    suspend fun getSuggestions(
        partial: String,
        limit: Int = 3
    ): List<Suggestion> = withContext(aiDispatcher) {
        if (!ready) initialize()
        val lower = partial.trim().lowercase(Locale.getDefault())
        if (lower.isEmpty()) {
            return@withContext nextWordSuggestions(limit)
        }

        val scored = LinkedHashMap<String, Float>()

        fun bump(word: String, score: Float) {
            if (word == lower) return
            scored[word] = maxOf(scored[word] ?: Float.NEGATIVE_INFINITY, score)
        }

        // Personal dict (Room) receives highest priority – appear first in suggestion strip
        val personalMatches = personal.prefixMatches(lower, 6)
        personalMatches.forEach { (w, f) ->
            bump(w, 200f + f + contextModel.score(w, contextWindow.toList()))
        }

        // Exact prefix from trie (Layer 1)
        trie.prefixSearch(lower, 12).forEach { (w, f) ->
            val ctx = contextModel.score(w, contextWindow.toList())
            bump(w, 60f + f * 0.5f + ctx)
        }

        // Spatial fuzzy (Layer 1) when partial length >= 2
        if (lower.length >= 2) {
            val maxDist = when {
                lower.length <= 3 -> 1.2f
                lower.length <= 6 -> 1.8f
                else -> 2.2f
            }
            trie.fuzzySearch(lower, maxDist, 16).forEach { (w, cost) ->
                val freq = trie.frequencyOf(w).toFloat()
                val ctx = contextModel.score(w, contextWindow.toList())
                // Lower spatial cost → higher score
                val spatialScore = 40f - cost * 18f + freq * 0.3f + ctx
                bump(w, spatialScore)
            }
        }

        // Distinct ranking: personal words already have elevated scores so they surface first
        val ranked = scored.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (w, s) ->
                Suggestion(
                    word = w,
                    score = s,
                    isPrimary = false
                )
            }

        // Ensure primary is the middle slot when we have 3
        return@withContext layoutPrimary(ranked, lower)
    }

    /**
     * Decide auto-correction for a finished word (on space).
     * Layer 1 produces spatial candidates; Layer 4 (ONNX) ranks them when available.
     * Returns null if no correction should be applied.
     */
    suspend fun correctIfNeeded(rawWord: String): String? = withContext(aiDispatcher) {
        if (!ready) initialize()
        val lower = rawWord.trim().lowercase(Locale.getDefault())
        if (lower.length < 2) return@withContext null
        if (trie.contains(lower) || personal.contains(lower)) {
            pushContext(lower)
            return@withContext null
        }

        val maxDist = when {
            lower.length <= 3 -> 1.2f
            lower.length <= 6 -> 1.8f
            else -> 2.2f
        }
        val spatial = trie.fuzzySearch(lower, maxDist, 10)
        if (spatial.isEmpty()) {
            pushContext(lower)
            return@withContext null
        }

        val ctxList = contextWindow.toList()
        // Pre-filter by classic score to keep AI input small (top 5)
        val rankedSpatial = spatial
            .map { (w, cost) ->
                val freq = trie.frequencyOf(w).toFloat()
                val ctx = contextModel.score(w, ctxList)
                val score = freq * 0.4f + ctx * 3f - cost * 20f
                Triple(w, cost, score)
            }
            .sortedByDescending { it.third }
            .take(5)

        if (rankedSpatial.isEmpty()) {
            pushContext(lower)
            return@withContext null
        }

        val candidateWords = rankedSpatial.map { it.first }

        val bestWord = try {
            aiScorer.rankCandidates(candidateWords, ctxList)
        } catch (e: Exception) {
            Log.w(TAG, "AI rank failed, using spatial best", e)
            rankedSpatial.first().first
        }

        val spatialEntry = rankedSpatial.find { it.first == bestWord } ?: rankedSpatial.first()
        val cost = spatialEntry.second
        val confidence = spatialEntry.third

        // Threshold: only correct when clearly better
        if (confidence < 8f || cost > 1.6f) {
            pushContext(lower)
            return@withContext null
        }

        val preserved = preserveCase(rawWord, bestWord)
        lastCorrection = CorrectionRecord(rawWord, preserved, rawWord.length)
        pushContext(bestWord)
        (contextModel as? NgramContextModel)?.learnBigram(
            contextWindow.getOrNull(contextWindow.size - 2) ?: "",
            bestWord
        )
        preserved
    }

    /** User accepted a suggestion chip. */
    suspend fun onSuggestionAccepted(word: String) {
        val clean = word.trim().lowercase(Locale.getDefault())
        personal.learn(clean, boost = 6)
        trie.insert(clean, trie.frequencyOf(clean) + 10)
        pushContext(clean)
        lastCorrection = null
    }

    /**
     * User rejected a correction (backspace right after auto-correct).
     * Learn original so we don't keep forcing the same correction.
     */
    suspend fun onCorrectionUndone(original: String) {
        personal.learn(original, boost = 8)
        trie.insert(original.lowercase(Locale.getDefault()), 60)
        lastCorrection = null
    }

    fun clearLastCorrection() {
        lastCorrection = null
    }

    fun clearContext() {
        contextWindow.clear()
        lastCorrection = null
    }

    private fun pushContext(word: String) {
        val w = word.lowercase(Locale.getDefault())
        if (w.isEmpty()) return
        contextWindow.addLast(w)
        while (contextWindow.size > 3) contextWindow.removeFirst()
    }

    private suspend fun nextWordSuggestions(limit: Int): List<Suggestion> {
        val prev = contextWindow.lastOrNull() ?: return emptyList()
        // Rank by bigram from ngram model via score
        val candidates = ArrayList<Suggestion>()
        // Sample from personal + high-freq prefix of empty is weak; use context score on recent trie seeds
        personal.cachedWords().entries
            .sortedByDescending { it.value }
            .take(30)
            .forEach { (w, f) ->
                val s = f + contextModel.score(w, contextWindow.toList()) * 5f
                candidates.add(Suggestion(w, s, false))
            }
        return layoutPrimary(
            candidates.sortedByDescending { it.score }.take(limit),
            ""
        )
    }

    private fun layoutPrimary(list: List<Suggestion>, typed: String): List<Suggestion> {
        if (list.isEmpty()) return list
        // Order for strip: [alt1, PRIMARY, alt2] when 3 available
        return when (list.size) {
            1 -> listOf(list[0].copy(isPrimary = true))
            2 -> listOf(list[1].copy(isPrimary = false), list[0].copy(isPrimary = true))
            else -> listOf(
                list.getOrNull(1)?.copy(isPrimary = false) ?: list[0],
                list[0].copy(isPrimary = true),
                list.getOrNull(2)?.copy(isPrimary = false) ?: list[0]
            )
        }
    }

    private fun preserveCase(original: String, replacement: String): String {
        if (original.isEmpty()) return replacement
        return when {
            original.all { it.isUpperCase() } -> replacement.uppercase(Locale.getDefault())
            original.first().isUpperCase() ->
                replacement.replaceFirstChar { it.uppercase(Locale.getDefault()) }
            else -> replacement
        }
    }

    data class Suggestion(
        val word: String,
        val score: Float,
        val isPrimary: Boolean
    )

    companion object {
        private const val TAG = "AutoCorrect"
    }
}
