# Keep Input Method Service
-keep class com.smartkeyboard.ime.service.SmartInputMethodService { *; }

# Native JNI bridge
-keep class com.smartkeyboard.ime.nativebridge.NativeEngine { *; }
-keepclassmembers class com.smartkeyboard.ime.nativebridge.NativeEngine {
    native <methods>;
}

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
