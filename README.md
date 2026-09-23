# Smart Keyboard

IME Android (Indonesia) dengan fitur setara smart keyboard modern.

## Fitur aktif

- **Auto-koreksi hybrid 4 lapis**
  - Layer 1: Trie + QWERTY-weighted Levenshtein (fuzzy)
  - Layer 2: N-gram bigram context
  - Layer 3: Personal Dictionary (Room, adaptive)
  - Layer 4: On-device TinyChar-BiGRU ONNX INT8 scorer (char-level, ~230 KB, bundled langsung di APK)
- Mode terjemahan ID → bahasa target (ML Kit)
- Clipboard history (pin / hapus / persist)
- Emoji panel (kategori + recently used)
- Tema Light / Dark / System + Material You
- Pengaturan ukuran & kerapatan keyboard
- Kapital otomatis hanya di awal field / setelah baris baru
- Undo Auto-Correct via Backspace (belajar kata asli ke Personal Dictionary)

## Model AI Auto-Correct (bundled di APK)

Model ONNX **sudah di-bundle** langsung di dalam APK, tidak perlu diunduh:

- File model: `assets/tiny_char_bigru_int8.onnx` (~230 KB, char-level BiGRU + context hash)
- Vocab karakter: `assets/char_vocab.json`
- Model dimuat otomatis saat `AutoCorrectEngine.initialize()` dipanggil — tidak ada langkah setup tambahan dari pengguna
- Tanpa perlu koneksi internet maupun izin penyimpanan tambahan

Jika suatu saat model gagal dimuat (file corrupt, dsb.), Layer 4 di-skip secara graceful; Layer 1–3 tetap berfungsi.

## Dependensi AI

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
```

## Arsitektur AI Core

- `TinyCharBiGruScorer` – memuat model + vocab karakter dari assets; encode kata kandidat per-karakter (maks 16 char) + hash 2 kata konteks sebelumnya (`String.hashCode()` mod 4096); `setIntraOpNumThreads(1)`
- Integrasi di `AutoCorrectEngine.correctIfNeeded()`: kandidat dari Trie (Layer 1) di-skor oleh model, skor tertinggi dipilih sebagai koreksi final

## Personal Dictionary (Room)

- Entity `PersonalWord` (`word` PK, `frequency`, `updatedAt`)
- DAO: `find`, `upsert`, `prefix` (limit 3–20, sorted by frequency)
- Belajar otomatis saat user Undo koreksi AI (`onCorrectionUndone`)
- Prioritas tertinggi di suggestion strip

## Build

```bash
./gradlew assembleRelease
```

Model AI sudah termasuk di dalam APK sejak instalasi — tidak perlu diunduh terpisah.
