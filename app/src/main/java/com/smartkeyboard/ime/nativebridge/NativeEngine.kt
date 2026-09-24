package com.smartkeyboard.ime.nativebridge

import android.util.Log

/**
 * JNI wrapper for the on-device KeyboardEngine (SymSpell + optional TFLite).
 * Package/JNI names must match native-lib.cpp exactly.
 */
class NativeEngine private constructor() {

    companion object {
        private const val TAG = "NativeEngine"
        @Volatile
        private var instance: NativeEngine? = null

        init {
            try {
                System.loadLibrary("native-lib")
                Log.i(TAG, "native-lib loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native-lib", e)
            }
        }

        fun getInstance(): NativeEngine {
            return instance ?: synchronized(this) {
                instance ?: NativeEngine().also { instance = it }
            }
        }
    }

    @Volatile
    var initialized: Boolean = false
        private set

    /**
     * Load SymSpell dictionary and optional TFLite model from absolute file paths
     * (must be real filesystem paths, not asset URIs).
     */
    fun init(dictPath: String, modelPath: String?): Boolean {
        return try {
            val ok = initEngine(dictPath, modelPath ?: "")
            initialized = ok
            Log.i(TAG, "initEngine ok=$ok dictReady=${isDictionaryReady()} modelReady=${isModelReady()}")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "init failed", e)
            initialized = false
            false
        }
    }

    fun autoCorrect(input: String): Array<String> {
        if (!initialized || input.isBlank()) return emptyArray()
        return try {
            getAutoCorrect(input) ?: emptyArray()
        } catch (e: Throwable) {
            Log.w(TAG, "getAutoCorrect failed", e)
            emptyArray()
        }
    }

    fun nextWordId(contextIds: IntArray): Int {
        if (!initialized || contextIds.size < 3) return 0
        return try {
            getNextWordPrediction(contextIds)
        } catch (e: Throwable) {
            Log.w(TAG, "getNextWordPrediction failed", e)
            0
        }
    }

    fun shutdown() {
        try {
            nativeShutdown()
        } catch (e: Throwable) {
            Log.w(TAG, "nativeShutdown", e)
        }
        initialized = false
    }

    // --- JNI ---
    private external fun initEngine(dictPath: String, modelPath: String): Boolean
    private external fun getAutoCorrect(input: String): Array<String>?
    private external fun getNextWordPrediction(contextIds: IntArray): Int
    external fun isDictionaryReady(): Boolean
    external fun isModelReady(): Boolean
    private external fun nativeShutdown()
}
