# Keep Input Method Service
-keep class com.smartkeyboard.ime.service.SmartInputMethodService { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
