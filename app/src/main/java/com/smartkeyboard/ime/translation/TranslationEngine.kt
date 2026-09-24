package com.smartkeyboard.ime.translation

import android.content.Context
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.smartkeyboard.ime.utils.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * High-accuracy translation: Indonesian → any ML Kit supported language.
 * Model download is manual (per language pair) from Settings or first use prompt.
 */
class TranslationEngine(private val context: Context) {

    enum class ModelStatus {
        NOT_DOWNLOADED,
        DOWNLOADING,
        READY,
        ERROR
    }

    data class LangOption(
        val code: String,
        val labelId: String,
        val labelEn: String
    )

    private val prefs = PreferencesHelper(context)
    private val dictionary = linkedMapOf<String, String>()
    private val phrases = linkedMapOf<String, String>()
    private val cache = LruCache<String, String>(128)

    private var translator: Translator? = null
    private var currentTargetCode: String = prefs.translationTargetLang
    private val modelReady = AtomicBoolean(false)
    private val modelDownloading = AtomicBoolean(false)
    @Volatile private var lastError: String? = null
    private val readyLangs = ConcurrentHashMap<String, Boolean>()

    /**
     * Guards [translator] (and [currentTargetCode]) against the same race that made
     * translation flaky: [setTargetLanguage] runs synchronously on the Main thread
     * (key events, language-picker taps) and closes + replaces [translator], while
     * [translateText] reads [translator] on Dispatchers.Default. Without a lock, a
     * language switch mid-translation could hand translateWithMlKit() a Translator
     * that gets close()d underneath it (MlKitException: already closed) or silently
     * translate the outgoing keystroke into the *new* target language instead of the
     * one that was active when the user typed it. All reads/writes of translator and
     * currentTargetCode now go through this mutex.
     */
    private val translatorMutex = Mutex()

    var statusListener: ((ModelStatus, String?) -> Unit)? = null

    init {
        loadFromAssets()
        setTargetLanguage(prefs.translationTargetLang, forceRecreate = true)
    }

    companion object {
        val SUPPORTED_TARGETS: List<LangOption> = listOf(
            LangOption("en", "Inggris", "English"),
            LangOption("zh", "Mandarin", "Chinese"),
            LangOption("ja", "Jepang", "Japanese"),
            LangOption("ko", "Korea", "Korean"),
            LangOption("ar", "Arab", "Arabic"),
            LangOption("ms", "Melayu", "Malay"),
            LangOption("th", "Thai", "Thai"),
            LangOption("vi", "Vietnam", "Vietnamese"),
            LangOption("hi", "Hindi", "Hindi"),
            LangOption("es", "Spanyol", "Spanish"),
            LangOption("fr", "Prancis", "French"),
            LangOption("de", "Jerman", "German"),
            LangOption("pt", "Portugis", "Portuguese"),
            LangOption("ru", "Rusia", "Russian"),
            LangOption("tr", "Turki", "Turkish"),
            LangOption("it", "Italia", "Italian"),
            LangOption("nl", "Belanda", "Dutch"),
            LangOption("af", "Afrikaans", "Afrikaans"),
            LangOption("sq", "Albania", "Albanian"),
            LangOption("be", "Belarusia", "Belarusian"),
            LangOption("bn", "Bengali", "Bengali"),
            LangOption("bg", "Bulgaria", "Bulgarian"),
            LangOption("ca", "Katalan", "Catalan"),
            LangOption("hr", "Kroasia", "Croatian"),
            LangOption("cs", "Ceko", "Czech"),
            LangOption("da", "Denmark", "Danish"),
            LangOption("et", "Estonia", "Estonian"),
            LangOption("eo", "Esperanto", "Esperanto"),
            LangOption("fi", "Finlandia", "Finnish"),
            LangOption("gl", "Galisia", "Galician"),
            LangOption("ka", "Georgia", "Georgian"),
            LangOption("el", "Yunani", "Greek"),
            LangOption("gu", "Gujarati", "Gujarati"),
            LangOption("ht", "Haiti", "Haitian"),
            LangOption("he", "Ibrani", "Hebrew"),
            LangOption("hu", "Hungaria", "Hungarian"),
            LangOption("is", "Islandia", "Icelandic"),
            LangOption("ga", "Irlandia", "Irish"),
            LangOption("kn", "Kannada", "Kannada"),
            LangOption("lv", "Latvia", "Latvian"),
            LangOption("lt", "Lituania", "Lithuanian"),
            LangOption("mk", "Makedonia", "Macedonian"),
            LangOption("mr", "Marathi", "Marathi"),
            LangOption("mt", "Malta", "Maltese"),
            LangOption("no", "Norwegia", "Norwegian"),
            LangOption("fa", "Persia", "Persian"),
            LangOption("pl", "Polandia", "Polish"),
            LangOption("ro", "Rumania", "Romanian"),
            LangOption("sk", "Slovakia", "Slovak"),
            LangOption("sl", "Slovenia", "Slovenian"),
            LangOption("sw", "Swahili", "Swahili"),
            LangOption("sv", "Swedia", "Swedish"),
            LangOption("tl", "Tagalog", "Tagalog"),
            LangOption("ta", "Tamil", "Tamil"),
            LangOption("te", "Telugu", "Telugu"),
            LangOption("uk", "Ukraina", "Ukrainian"),
            LangOption("ur", "Urdu", "Urdu"),
            LangOption("cy", "Wales", "Welsh")
        )

        fun labelFor(code: String): String =
            SUPPORTED_TARGETS.find { it.code == code }?.labelId ?: code.uppercase()
    }

    fun getTargetLabel(): String = labelFor(currentTargetCode)

    fun isLanguageDownloaded(code: String): Boolean =
        readyLangs[code] == true || prefs.isLangDownloaded(code)

    fun setTargetLanguage(code: String, forceRecreate: Boolean = false) = runBlocking {
        // withLock is an inline suspend function, so `return@runBlocking` here would
        // actually be a non-local return out of the *outer* runBlocking lambda — which
        // is exactly what we want (skip notifyStatus() too, matching the original
        // early-return behavior), but to avoid any ambiguity we use an explicit
        // `changed` flag instead of relying on that inlining detail.
        var changed = true
        translatorMutex.withLock {
            if (!forceRecreate && code == currentTargetCode && translator != null) {
                changed = false
            } else {
                currentTargetCode = code
                prefs.translationTargetLang = code
                cache.evictAll()
                try { translator?.close() } catch (_: Exception) { }
                translator = null
                modelReady.set(readyLangs[code] == true)
                modelDownloading.set(false)
                lastError = null

                try {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.INDONESIAN)
                        .setTargetLanguage(code)
                        .build()
                    translator = Translation.getClient(options)
                    checkModelAvailability()
                } catch (_: Exception) {
                    translator = null
                    modelReady.set(false)
                }
            }
        }
        if (changed) notifyStatus()
    }

    fun getModelStatus(): ModelStatus = when {
        modelReady.get() -> ModelStatus.READY
        modelDownloading.get() -> ModelStatus.DOWNLOADING
        lastError != null -> ModelStatus.ERROR
        else -> ModelStatus.NOT_DOWNLOADED
    }

    fun getStatusMessage(): String {
        val lang = getTargetLabel()
        return when (getModelStatus()) {
            ModelStatus.READY -> "Model ID → $lang siap (offline)"
            ModelStatus.DOWNLOADING -> "Mengunduh model ID → $lang… (~30 MB)"
            ModelStatus.ERROR -> lastError ?: "Gagal mengunduh"
            ModelStatus.NOT_DOWNLOADED -> "Belum diunduh — ID → $lang"
        }
    }

    
    /**
     * Download model for a specific target language (does not change current target permanently
     * unless [switchTo] is true).
     */
    fun downloadModelFor(code: String, switchTo: Boolean = false, onComplete: ((Boolean, String?) -> Unit)? = null) {
        val prev = currentTargetCode
        setTargetLanguage(code, forceRecreate = true)
        downloadModel { success, msg ->
            if (!switchTo && prev != code) {
                setTargetLanguage(prev, forceRecreate = true)
            }
            onComplete?.invoke(success, msg)
        }
    }

    fun downloadModel(onComplete: ((Boolean, String?) -> Unit)? = null) {
        val t = translator
        if (t == null) {
            lastError = "Translator tidak tersedia"
            notifyStatus()
            onComplete?.invoke(false, lastError)
            return
        }
        if (modelReady.get()) {
            onComplete?.invoke(true, "Model sudah siap")
            notifyStatus()
            return
        }
        if (modelDownloading.get()) {
            onComplete?.invoke(false, "Sedang mengunduh…")
            return
        }
        modelDownloading.set(true)
        lastError = null
        notifyStatus()
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady.set(true)
                modelDownloading.set(false)
                readyLangs[currentTargetCode] = true
                prefs.markLangDownloaded(currentTargetCode)
                lastError = null
                notifyStatus()
                onComplete?.invoke(true, "Model ID → ${getTargetLabel()} berhasil diunduh")
            }
            .addOnFailureListener { e ->
                modelDownloading.set(false)
                lastError = e.message ?: "Gagal mengunduh model"
                notifyStatus()
                onComplete?.invoke(false, lastError)
            }
    }

    suspend fun translateText(text: String): String? = withContext(Dispatchers.Default) {
        // Preserve intentional trailing space (auto-space after punct) for the caller,
        // but look up using a trimmed key so "halo." and "halo. " share cache entries.
        val trailingSpace = text.endsWith(' ') || text.endsWith('\n')
        val raw = text.trim()
        if (raw.isEmpty()) return@withContext if (trailingSpace) " " else null

        // Snapshot the target language + translator client atomically. Without this,
        // a language switch racing this call could mix a cache key built from the OLD
        // language with a lookup against the NEW translator (or vice versa), and could
        // hand translateWithMlKit() a Translator instance that setTargetLanguage()
        // closes concurrently.
        val (targetCode, translatorSnapshot, modelReadySnapshot) = translatorMutex.withLock {
            Triple(currentTargetCode, translator, modelReady.get())
        }

        val cacheKey = "$targetCode|${raw.lowercase(Locale.getDefault())}"
        cache.get(cacheKey)?.let { cached ->
            // Re-apply case/punct from the current input (cache stores base form)
            return@withContext preserveCaseAndPunct(raw, cached).let {
                if (trailingSpace && !it.endsWith(' ')) "$it " else it
            }
        }

        var result: String? = null
        if (targetCode == "en") {
            val key = raw.lowercase(Locale.getDefault())
            val exact = phrases[key] ?: dictionary[key]
            if (exact != null) {
                result = preserveCaseAndPunct(raw, exact)
            }
        }

        if (result == null && modelReadySnapshot && translatorSnapshot != null) {
            val mlResult = translateWithMlKit(translatorSnapshot, raw)
            if (mlResult != null && mlResult.isNotBlank()) {
                result = preserveCaseAndPunct(raw, mlResult)
            }
        }

        if (result == null && targetCode == "en") {
            result = wordByWordFallback(raw)
        }

        if (result != null) {
            // Only cache under the language that was actually used for this
            // translation — guards against caching a stale/mismatched result
            // if currentTargetCode changed again while we were translating.
            cache.put(cacheKey, result.trimEnd())
            if (trailingSpace && !result.endsWith(' ')) result = "$result "
        }
        result
    }

    fun destroy() = runBlocking {
        translatorMutex.withLock {
            try { translator?.close() } catch (_: Exception) { }
            translator = null
        }
        modelReady.set(false)
        statusListener = null
    }

    /**
     * Reflect local knowledge of model readiness only.
     * Must NOT call [Translator.downloadModelIfNeeded] here — that would silently
     * pull ~30 MB over the network without explicit user consent (download is
     * triggered only from Settings or [downloadModel]).
     */
    private fun checkModelAvailability() {
        val known = readyLangs[currentTargetCode] == true ||
            prefs.isLangDownloaded(currentTargetCode)
        modelReady.set(known)
        notifyStatus()
    }

    private fun notifyStatus() {
        statusListener?.invoke(getModelStatus(), getStatusMessage())
    }

    private suspend fun translateWithMlKit(translator: Translator, text: String): String? {
        return try {
            suspendCancellableCoroutine { cont ->
                translator.translate(text)
                    .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        } catch (_: Exception) {
            // Translator may have been close()d by a concurrent setTargetLanguage()
            // call between the snapshot and this call — treat as "no translation"
            // rather than crashing the IME.
            null
        }
    }

    private fun wordByWordFallback(raw: String): String? {
        val lower = raw.lowercase(Locale.getDefault())
        val tokens = lower.split(Regex("\\s+"))
            .map { it.trim().trim(',', '.', '!', '?', ';', ':', '"', '\'') }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val result = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            var matched = false
            val maxLen = minOf(5, tokens.size - i)
            for (len in maxLen downTo 2) {
                val slice = tokens.subList(i, i + len).joinToString(" ")
                val hit = phrases[slice]
                if (hit != null) {
                    result.add(hit)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                result.add(dictionary[tokens[i]] ?: tokens[i])
                i++
            }
        }
        return preserveCaseAndPunct(raw, result.joinToString(" "))
    }

    private fun preserveCaseAndPunct(original: String, translated: String): String {
        var out = translated
        if (original.firstOrNull()?.isUpperCase() == true && out.isNotEmpty()) {
            out = out.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        }
        val trailing = original.takeLastWhile { it in ".,!?;:" }
        if (trailing.isNotEmpty() && !out.endsWith(trailing)) out += trailing
        return out
    }

    private fun loadFromAssets() {
        try {
            context.assets.open("dict_id_en.tsv").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 2) {
                        val id = parts[0].trim().lowercase(Locale.getDefault())
                        val en = parts[1].trim()
                        if (id.isEmpty() || en.isEmpty()) return@forEach
                        if (id.contains(" ")) phrases[id] = en else dictionary[id] = en
                    }
                }
            }
            phrases.putAll(
                mapOf(
                    "saya ingin makan" to "I want to eat",
                    "siapa nama kamu" to "What is your name",
                    "siapa namamu" to "What is your name",
                    "apa kabar" to "how are you",
                    "terima kasih" to "thank you",
                    "selamat pagi" to "good morning",
                    "selamat malam" to "good evening"
                )
            )
        } catch (_: Exception) {
            phrases["siapa nama kamu"] = "What is your name"
        }
    }
}
