#ifndef KEYBOARDENGINE_H
#define KEYBOARDENGINE_H

#include <memory>
#include <string>
#include <vector>

#include "SymSpell.h"

// Optional TFLite — compiled only if TFLITE_AVAILABLE is defined
#ifdef TFLITE_AVAILABLE
#include "tensorflow/lite/interpreter.h"
#include "tensorflow/lite/model.h"
#endif

/**
 * On-device keyboard engine:
 *  - SymSpell for auto-correct / suggestions
 *  - Optional TFLite LSTM for next-word prediction (when model loads successfully)
 */
class KeyboardEngine {
public:
    KeyboardEngine();
    ~KeyboardEngine();

    // Non-copyable
    KeyboardEngine(const KeyboardEngine&) = delete;
    KeyboardEngine& operator=(const KeyboardEngine&) = delete;

    bool loadDictionary(const std::string& dictPath);
    bool loadModel(const std::string& modelPath);

    bool isDictionaryReady() const { return dictReady_; }
    bool isModelReady() const { return modelReady_; }

    /** Top auto-correct / spelling suggestions for a typed token. */
    std::vector<std::string> getAutoCorrect(const std::string& input, int maxResults = 5);

    /**
     * Next-word prediction from 3 context token IDs (vocab indices).
     * Returns best word ID, or 0 if model not ready / invalid context.
     */
    int getNextWordID(const std::vector<int>& contextIDs);

private:
    symspell::SymSpell symSpell_;
    bool dictReady_ = false;
    bool modelReady_ = false;

#ifdef TFLITE_AVAILABLE
    std::unique_ptr<tflite::FlatBufferModel> tfliteModel_;
    std::unique_ptr<tflite::Interpreter> interpreter_;
#endif
};

#endif  // KEYBOARDENGINE_H
