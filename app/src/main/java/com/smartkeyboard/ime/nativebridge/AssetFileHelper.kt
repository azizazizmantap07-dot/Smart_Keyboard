package com.smartkeyboard.ime.nativebridge

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Copies native assets from APK assets/ to internal filesDir so C++ can open
 * real filesystem paths. Also loads tokenizer_dict.json into memory.
 */
object AssetFileHelper {

    private const val TAG = "AssetFileHelper"

    const val DICT_ASSET = "symspell_dictionary.txt"
    const val MODEL_ASSET = "next_word_model.tflite"
    const val TOKENIZER_ASSET = "tokenizer_dict.json"

    /**
     * Ensure [assetName] exists under filesDir; copy from assets if missing or empty.
     * Returns absolute path, or null on failure.
     */
    fun ensureAssetCopied(context: Context, assetName: String): String? {
        val out = File(context.filesDir, assetName)
        try {
            if (out.exists() && out.length() > 0L) {
                return out.absolutePath
            }
            context.assets.open(assetName).use { input ->
                FileOutputStream(out).use { output ->
                    input.copyTo(output)
                }
            }
            if (!out.exists() || out.length() == 0L) {
                Log.w(TAG, "Asset $assetName copied but empty or missing")
                // Still return path for model (optional); for dict we treat as failure below
            }
            Log.i(TAG, "Copied $assetName → ${out.absolutePath} (${out.length()} bytes)")
            return out.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $assetName", e)
            return if (out.exists()) out.absolutePath else null
        }
    }

    /**
     * word → id map from tokenizer_dict.json (ids start at 1; 0 unused / padding).
     */
    fun loadTokenizer(context: Context): Map<String, Int> {
        val path = ensureAssetCopied(context, TOKENIZER_ASSET) ?: return emptyMap()
        return try {
            val text = File(path).readText(Charsets.UTF_8)
            val json = JSONObject(text)
            val map = HashMap<String, Int>(json.length())
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.optInt(k, 0)
            }
            Log.i(TAG, "Tokenizer loaded: ${map.size} tokens")
            map
        } catch (e: Exception) {
            Log.e(TAG, "loadTokenizer failed", e)
            emptyMap()
        }
    }

    /** Inverse map id → word for converting TFLite output ids. */
    fun invertTokenizer(wordToId: Map<String, Int>): Map<Int, String> {
        val inv = HashMap<Int, String>(wordToId.size)
        for ((w, id) in wordToId) {
            if (id > 0) inv[id] = w
        }
        return inv
    }
}
