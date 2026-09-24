#include <jni.h>
#include <string>
#include <vector>
#include <mutex>

#include "KeyboardEngine.h"
#include <android/log.h>

#define LOG_TAG "NativeEngineJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Single process-wide engine (IME is typically one process).
//
// KeyboardEngine methods can be invoked from multiple Kotlin coroutine
// threads concurrently (autocorrect suggestions + next-word prediction can
// race on Dispatchers.Default), and initEngine()/nativeShutdown() can run on
// yet another thread relative to those calls (init happens on a background
// scope launched from onCreate(), shutdown happens on onDestroy()).
//
// g_engine is therefore guarded end-to-end by g_engineMutex:
//  - construction/destruction (initEngine / nativeShutdown) take an exclusive
//    lock so a lookup can never observe a half-constructed or freed engine.
//  - every read/inference call also takes the same lock. This is coarser
//    than strictly necessary (SymSpell::Lookup is const/read-only and would
//    be fine to run in parallel), but TFLite's Interpreter::Invoke() is NOT
//    safe to call concurrently from multiple threads because it mutates
//    internal tensor buffers in place. Serializing all native calls behind
//    one mutex keeps both engines safe without needing separate locks that
//    could themselves race during teardown.
static std::mutex g_engineMutex;
static KeyboardEngine* g_engine = nullptr;

// Caller must hold g_engineMutex.
static void ensureEngineLocked() {
    if (g_engine == nullptr) {
        g_engine = new KeyboardEngine();
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_smartkeyboard_ime_nativebridge_NativeEngine_initEngine(
        JNIEnv* env, jobject /*thiz*/, jstring dict_path, jstring model_path) {
    if (dict_path == nullptr) {
        LOGE("initEngine: dict_path is null");
        return JNI_FALSE;
    }

    // Read the Java strings BEFORE taking the lock — GetStringUTFChars can
    // call back into the JVM and there is no reason to hold g_engineMutex
    // while doing so.
    const char* dictPathChars = env->GetStringUTFChars(dict_path, nullptr);
    if (dictPathChars == nullptr) return JNI_FALSE;
    std::string dictPath(dictPathChars);
    env->ReleaseStringUTFChars(dict_path, dictPathChars);

    bool haveModelPath = false;
    std::string modelPath;
    if (model_path != nullptr) {
        const char* modelPathChars = env->GetStringUTFChars(model_path, nullptr);
        if (modelPathChars != nullptr) {
            modelPath = modelPathChars;
            env->ReleaseStringUTFChars(model_path, modelPathChars);
            haveModelPath = true;
        }
    }

    std::lock_guard<std::mutex> lock(g_engineMutex);
    ensureEngineLocked();

    bool dictLoaded = g_engine->loadDictionary(dictPath);
    bool modelLoaded = haveModelPath && g_engine->loadModel(modelPath);

    // Dictionary is required; model is optional
    LOGI("initEngine dict=%d model=%d", dictLoaded ? 1 : 0, modelLoaded ? 1 : 0);
    return dictLoaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_smartkeyboard_ime_nativebridge_NativeEngine_getAutoCorrect(
        JNIEnv* env, jobject /*thiz*/, jstring input) {
    if (input == nullptr) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    const char* nativeInputChars = env->GetStringUTFChars(input, nullptr);
    if (nativeInputChars == nullptr) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    std::string nativeInput(nativeInputChars);
    env->ReleaseStringUTFChars(input, nativeInputChars);

    std::vector<std::string> suggestions;
    {
        std::lock_guard<std::mutex> lock(g_engineMutex);
        ensureEngineLocked();
        suggestions = g_engine->getAutoCorrect(nativeInput, 5);
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray ret = env->NewObjectArray(
            static_cast<jsize>(suggestions.size()), stringClass, nullptr);
    if (ret == nullptr) return nullptr;

    for (size_t i = 0; i < suggestions.size(); ++i) {
        jstring js = env->NewStringUTF(suggestions[i].c_str());
        if (js != nullptr) {
            env->SetObjectArrayElement(ret, static_cast<jsize>(i), js);
            env->DeleteLocalRef(js);
        }
    }
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_smartkeyboard_ime_nativebridge_NativeEngine_getNextWordPrediction(
        JNIEnv* env, jobject /*thiz*/, jintArray context_ids) {
    if (context_ids == nullptr) return 0;

    jsize len = env->GetArrayLength(context_ids);
    if (len < 3) return 0;

    jint* elements = env->GetIntArrayElements(context_ids, nullptr);
    if (elements == nullptr) return 0;

    std::vector<int> ctx(3);
    ctx[0] = elements[0];
    ctx[1] = elements[1];
    ctx[2] = elements[2];
    env->ReleaseIntArrayElements(context_ids, elements, JNI_ABORT);

    // Serialized with all other engine access: TFLite's Interpreter::Invoke()
    // mutates interpreter-owned tensor buffers in place and is not safe to
    // call from two threads at once (this can otherwise race with a
    // concurrent getAutoCorrect() call on another coroutine thread, or with
    // a concurrent init/shutdown).
    std::lock_guard<std::mutex> lock(g_engineMutex);
    ensureEngineLocked();
    return g_engine->getNextWordID(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_smartkeyboard_ime_nativebridge_NativeEngine_isDictionaryReady(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    ensureEngineLocked();
    return g_engine->isDictionaryReady() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_smartkeyboard_ime_nativebridge_NativeEngine_isModelReady(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    ensureEngineLocked();
    return g_engine->isModelReady() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_smartkeyboard_ime_nativebridge_NativeEngine_nativeShutdown(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    delete g_engine;
    g_engine = nullptr;
}

}  // extern "C"
