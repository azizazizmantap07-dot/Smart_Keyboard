#include "KeyboardEngine.h"

#include <algorithm>
#include <android/log.h>

#define LOG_TAG "KeyboardEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef TFLITE_AVAILABLE
#include "tensorflow/lite/kernels/register.h"
#endif

KeyboardEngine::KeyboardEngine()
    : symSpell_(82765, 2, 7) {}

KeyboardEngine::~KeyboardEngine() {
#ifdef TFLITE_AVAILABLE
    interpreter_.reset();
    tfliteModel_.reset();
#endif
}

bool KeyboardEngine::loadDictionary(const std::string& dictPath) {
    dictReady_ = false;
    if (dictPath.empty()) {
        LOGE("loadDictionary: empty path");
        return false;
    }
    bool ok = symSpell_.LoadDictionary(dictPath, 0, 1);
    if (!ok) {
        LOGE("loadDictionary failed: %s", dictPath.c_str());
        return false;
    }
    dictReady_ = true;
    LOGI("SymSpell dictionary loaded (%zu words) from %s",
         symSpell_.wordCount(), dictPath.c_str());
    return true;
}

bool KeyboardEngine::loadModel(const std::string& modelPath) {
    modelReady_ = false;
#ifdef TFLITE_AVAILABLE
    if (modelPath.empty()) {
        LOGW("loadModel: empty path — next-word disabled");
        return false;
    }
    tfliteModel_ = tflite::FlatBufferModel::BuildFromFile(modelPath.c_str());
    if (!tfliteModel_) {
        LOGW("TFLite model load failed: %s", modelPath.c_str());
        return false;
    }
    tflite::ops::builtin::BuiltinOpResolver resolver;
    tflite::InterpreterBuilder(*tfliteModel_, resolver)(&interpreter_);
    if (!interpreter_) {
        LOGW("TFLite InterpreterBuilder failed");
        tfliteModel_.reset();
        return false;
    }
    if (interpreter_->AllocateTensors() != kTfLiteOk) {
        LOGW("TFLite AllocateTensors failed");
        interpreter_.reset();
        tfliteModel_.reset();
        return false;
    }
    modelReady_ = true;
    LOGI("TFLite next-word model loaded from %s", modelPath.c_str());
    return true;
#else
    (void)modelPath;
    LOGW("TFLite not compiled in — next-word prediction disabled");
    return false;
#endif
}

std::vector<std::string> KeyboardEngine::getAutoCorrect(const std::string& input,
                                                        int maxResults) {
    std::vector<std::string> results;
    if (!dictReady_ || input.empty()) return results;

    auto suggestions = symSpell_.Lookup(input, symspell::Verbosity::All, 2);
    for (const auto& s : suggestions) {
        if (static_cast<int>(results.size()) >= maxResults) break;
        // Skip exact same token when we already have alternatives
        if (s.term == input && !results.empty()) continue;
        results.push_back(s.term);
    }
    // Ensure original is not the only result when we want corrections
    return results;
}

int KeyboardEngine::getNextWordID(const std::vector<int>& contextIDs) {
#ifdef TFLITE_AVAILABLE
    if (!modelReady_ || !interpreter_) return 0;
    if (contextIDs.size() != 3) return 0;

    float* input_tensor = interpreter_->typed_input_tensor<float>(0);
    if (!input_tensor) {
        // Try int32 input
        int32_t* input_i = interpreter_->typed_input_tensor<int32_t>(0);
        if (!input_i) return 0;
        for (int i = 0; i < 3; ++i) input_i[i] = contextIDs[i];
    } else {
        for (int i = 0; i < 3; ++i) {
            input_tensor[i] = static_cast<float>(contextIDs[i]);
        }
    }

    if (interpreter_->Invoke() != kTfLiteOk) {
        LOGW("TFLite Invoke failed");
        return 0;
    }

    float* output = interpreter_->typed_output_tensor<float>(0);
    if (!output) return 0;

    int best_id = 0;
    float max_prob = output[0];
    // Vocab size assumed 10000 as in training guide
    const int kVocab = 10000;
    for (int i = 1; i < kVocab; ++i) {
        if (output[i] > max_prob) {
            max_prob = output[i];
            best_id = i;
        }
    }
    return best_id;
#else
    (void)contextIDs;
    return 0;
#endif
}
