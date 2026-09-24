# Smart Keyboard

IME Android (Bahasa Indonesia) dengan auto-correct on-device hybrid.

## Arsitektur Auto-Correct (v1.1)

| Layer | Teknologi | Lokasi |
|-------|-----------|--------|
| 1 | **SymSpell C++** (delete dictionary, edit distance ≤ 2) | `app/src/main/cpp/` via JNI |
| 2 | Personal Dictionary (Room, adaptive) | Kotlin |
| 3 | Next-word **TFLite LSTM** (opsional) | Native jika model `next_word_model.tflite` tersedia |

### Aset wajib
- `assets/symspell_dictionary.txt` — format `kata frekuensi` (sudah digenerate dari dict lama)
- `assets/tokenizer_dict.json` — mapping kata → id untuk next-word

### Aset opsional
- `assets/next_word_model.tflite` — model LSTM INT8 (latih sendiri mengikuti `Panduan.txt`). Tanpa file ini, next-word memakai fallback personal dictionary.

## Native (NDK)

```
app/src/main/cpp/
  CMakeLists.txt
  SymSpell.h
  KeyboardEngine.h / .cpp
  native-lib.cpp          # JNI → com.smartkeyboard.ime.nativebridge.NativeEngine
```

Build default: **SymSpell-only** (`-DTFLITE_AVAILABLE=OFF`) agar CI selalu sukses. Aktifkan TFLite C++ setelah menyiapkan prebuilt library.

## Build (GitHub Actions)

Workflow menginstal NDK 26.1 + CMake 3.22, lalu:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

## API yang dipakai IME

`SmartInputMethodService` memanggil `AutoCorrectEngine` (API tidak berubah):
- `initialize()` / `shutdown()`
- `getSuggestions(partial)`
- `correctIfNeeded(word)`
- `noteCommittedWord` / `onSuggestionAccepted` / `onCorrectionUndone`
