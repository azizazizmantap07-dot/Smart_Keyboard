package com.smartkeyboard.ime.autocorrect

import android.content.Context
import android.util.Log
import com.smartkeyboard.ime.nativebridge.AssetFileHelper
import com.smartkeyboard.ime.nativebridge.NativeEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Hybrid auto-correct orchestrator (v2 — SymSpell C++ + optional TFLite LSTM).
 *
 * Layer 1 — SymSpell (native C++, delete-dictionary, edit distance ≤ 2)
 * Layer 2 — Personal dictionary (Room) for adaptive learning / undo
 * Layer 3 — Optional next-word via TFLite LSTM (when model asset is present)
 *
 * Public API kept compatible with [com.smartkeyboard.ime.service.SmartInputMethodService].
 */
class AutoCorrectEngine(context: Context) {

    private val appContext = context.applicationContext
    private val personal = PersonalDictionary(appContext)
    private val native = NativeEngine.getInstance()

    private val aiDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * Guards [initialize] end-to-end (dictionary/model asset copy + native.init +
     * tokenizer load). Without this, the eager init launched from onCreate() and a
     * lazy init triggered by an early getSuggestions()/correctIfNeeded() call (both
     * of which check the [ready] flag before it's set) can run concurrently on
     * different Dispatchers.Default threads and call native.init() twice at once —
     * racing the native engine's dictionary/model load against itself.
     */
    private val initMutex = Mutex()

    @Volatile
    private var ready = false

    /** word → vocab id (from tokenizer_dict.json) */
    private var wordToId: Map<String, Int> = emptyMap()
    /** vocab id → word */
    private var idToWord: Map<Int, String> = emptyMap()

    data class CorrectionRecord(
        val original: String,
        val corrected: String
    )

    @Volatile
    var lastCorrection: CorrectionRecord? = null
        private set

    /** Recent committed words for context (max 3). */
    private val contextWindow = ArrayDeque<String>(4)

    var predictionJob: Job? = null

    suspend fun initialize() = withContext(Dispatchers.Default) {
        // Fast path without the lock: once ready, every caller can skip init entirely.
        if (ready) return@withContext
        initMutex.withLock {
            // Re-check inside the lock — another coroutine may have finished
            // initializing (or failed) while we were waiting for the lock.
            if (ready) return@withLock

            try {
                personal.warmUp()
            } catch (e: Exception) {
                Log.w(TAG, "personal warmUp", e)
            }

            val dictPath = AssetFileHelper.ensureAssetCopied(appContext, AssetFileHelper.DICT_ASSET)
            val modelPath = AssetFileHelper.ensureAssetCopied(appContext, AssetFileHelper.MODEL_ASSET)

            if (dictPath == null) {
                Log.e(TAG, "SymSpell dictionary missing — auto-correct disabled")
                ready = false
                return@withLock
            }

            val modelPathOrEmpty = modelPath?.takeIf {
                java.io.File(it).length() > 100L // ignore empty placeholder
            }

            val ok = native.init(dictPath, modelPathOrEmpty)
            if (!ok) {
                Log.e(TAG, "NativeEngine.init failed")
                ready = false
                return@withLock
            }

            wordToId = AssetFileHelper.loadTokenizer(appContext)
            idToWord = AssetFileHelper.invertTokenizer(wordToId)

            ready = true
            Log.i(
                TAG,
                "AutoCorrect ready — SymSpell=true TFLite=${native.isModelReady()} " +
                    "tokenizer=${wordToId.size}"
            )
        }
    }

    /**
     * Top suggestions for the active partial word (or next-word if blank).
     * Returns up to 3 items; middle slot is the primary auto-correct candidate when possible.
     */
    suspend fun getSuggestions(
        partial: String,
        limit: Int = 3
    ): List<Suggestion> = withContext(aiDispatcher) {
        if (!ready) initialize()
        if (!ready) return@withContext emptyList()

        val lower = partial.trim().lowercase(Locale.getDefault())
        if (lower.isEmpty()) {
            return@withContext nextWordSuggestions(limit)
        }

        val scored = LinkedHashMap<String, Float>()

        fun bump(word: String, score: Float) {
            if (word == lower) return
            scored[word] = maxOf(scored[word] ?: Float.NEGATIVE_INFINITY, score)
        }

        // Personal dictionary first
        try {
            personal.prefixMatches(lower, 6).forEach { (w, f) ->
                bump(w, 200f + f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "personal prefix", e)
        }

        // SymSpell native
        val nativeHits = native.autoCorrect(lower)
        nativeHits.forEachIndexed { index, w ->
            val lw = w.lowercase(Locale.getDefault())
            // Higher rank for earlier (better) SymSpell hits
            bump(lw, 100f - index * 8f)
        }

        // If typed word is already a known suggestion top-hit, still offer alternatives
        val ranked = scored.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (w, s) -> Suggestion(word = w, score = s, isPrimary = false) }

        return@withContext layoutPrimary(ranked, lower)
    }

    /**
     * Decide auto-correction for a finished word (on space / punctuation).
     * Returns null if no correction should be applied.
     */
    suspend fun correctIfNeeded(rawWord: String): String? = withContext(aiDispatcher) {
        if (!ready) initialize()
        if (!ready) return@withContext null

        val lower = rawWord.trim().lowercase(Locale.getDefault())
        if (lower.length < 2) return@withContext null

        // Already known to personal dict → keep
        try {
            if (personal.contains(lower)) {
                pushContext(lower)
                return@withContext null
            }
        } catch (_: Exception) { }

        val suggestions = native.autoCorrect(lower)
        if (suggestions.isEmpty()) {
            pushContext(lower)
            return@withContext null
        }

        val best = suggestions.first().lowercase(Locale.getDefault())
        // If SymSpell returns the same word (exact match top), no correction
        if (best == lower) {
            pushContext(lower)
            return@withContext null
        }

        // Accept first alternative when distance is plausible (SymSpell already filtered ≤2)
        val preserved = preserveCase(rawWord, best)
        lastCorrection = CorrectionRecord(rawWord, preserved)
        pushContext(best)
        try {
            personal.learn(best, boost = 1)
        } catch (_: Exception) { }
        Log.i(TAG, "correctIfNeeded('$lower') → '$best'")
        preserved
    }

    suspend fun onSuggestionAccepted(word: String) {
        val clean = word.trim().lowercase(Locale.getDefault())
        try {
            personal.learn(clean, boost = 6)
        } catch (e: Exception) {
            Log.w(TAG, "onSuggestionAccepted", e)
        }
        pushContext(clean)
        lastCorrection = null
    }

    suspend fun onCorrectionUndone(original: String) {
        try {
            personal.learn(original, boost = 8)
        } catch (e: Exception) {
            Log.w(TAG, "onCorrectionUndone", e)
        }
        lastCorrection = null
    }

    fun clearLastCorrection() {
        lastCorrection = null
    }

    fun clearContext() {
        contextWindow.clear()
        lastCorrection = null
    }

    fun noteCommittedWord(rawWord: String) {
        val w = rawWord.trim().lowercase(Locale.getDefault())
        if (w.isEmpty()) return
        pushContext(w)
        lastCorrection = null
    }

    /**
     * Synchronous by design (called from onDestroy()). Takes [initMutex] via
     * tryLock in a short spin instead of a suspending lock so a slow asset
     * copy in [initialize] can't leave native.shutdown() racing a still-running
     * native.init() call on another thread — shutdown waits for init to either
     * finish or bail out before it tears down the engine.
     */
    fun shutdown() {
        predictionJob?.cancel()
        predictionJob = null
        ready = false
        // Best-effort wait for any in-flight initialize() to release the lock
        // before we destroy the native engine underneath it. onDestroy() runs
        // rarely and briefly blocking here is far safer than a native
        // use-after-free if init and shutdown interleave.
        var waited = 0
        while (initMutex.isLocked && waited < 2000) {
            Thread.sleep(5)
            waited += 5
        }
        try {
            native.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "native.shutdown", e)
        }
        lastCorrection = null
        contextWindow.clear()
    }

    private fun pushContext(word: String) {
        val w = word.lowercase(Locale.getDefault())
        if (w.isEmpty()) return
        contextWindow.addLast(w)
        while (contextWindow.size > 3) contextWindow.removeFirst()
    }

    private fun nextWordSuggestions(limit: Int): List<Suggestion> {
        // Prefer TFLite when ready
        if (native.isModelReady() && wordToId.isNotEmpty() && contextWindow.isNotEmpty()) {
            val ids = IntArray(3) { 0 }
            val words = contextWindow.toList()
            val start = (words.size - 3).coerceAtLeast(0)
            var filled = 0
            for (i in start until words.size) {
                val id = wordToId[words[i]] ?: wordToId["<oov>"] ?: 1
                ids[filled++] = id
                if (filled == 3) break
            }
            // Left-pad if fewer than 3 context tokens
            if (filled in 1..2) {
                val pad = IntArray(3)
                for (i in 0 until filled) {
                    pad[3 - filled + i] = ids[i]
                }
                System.arraycopy(pad, 0, ids, 0, 3)
            }
            val nextId = native.nextWordId(ids)
            val nextWord = idToWord[nextId]
            if (!nextWord.isNullOrBlank() && nextWord != "<oov>") {
                return listOf(Suggestion(nextWord, 50f, isPrimary = true))
            }
        }

        // Fallback: personal high-frequency words
        val candidates = ArrayList<Suggestion>()
        try {
            personal.cachedWords().entries
                .sortedByDescending { it.value }
                .take(30)
                .forEach { (w, f) ->
                    candidates.add(Suggestion(w, f.toFloat(), false))
                }
        } catch (_: Exception) { }
        return layoutPrimary(
            candidates.sortedByDescending { it.score }.take(limit),
            ""
        )
    }

    private fun layoutPrimary(list: List<Suggestion>, typed: String): List<Suggestion> {
        if (list.isEmpty()) return list
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
