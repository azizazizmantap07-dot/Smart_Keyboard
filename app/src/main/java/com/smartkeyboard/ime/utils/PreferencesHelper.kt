package com.smartkeyboard.ime.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Centralized preferences.
 */
class PreferencesHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isTranslationModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATION, false)
        set(value) = prefs.edit { putBoolean(KEY_TRANSLATION, value) }

    /** BCP-47 target language code for ID → X translation (default English). */
    var translationTargetLang: String
        get() = prefs.getString(KEY_TARGET_LANG, "en") ?: "en"
        set(value) = prefs.edit { putString(KEY_TARGET_LANG, value) }

    /** Comma-separated list of target lang codes whose ML Kit model is downloaded. */
    var downloadedLangs: Set<String>
        get() {
            val raw = prefs.getString(KEY_DOWNLOADED_LANGS, "") ?: ""
            return if (raw.isBlank()) emptySet() else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        set(value) = prefs.edit { putString(KEY_DOWNLOADED_LANGS, value.joinToString(",")) }

    fun markLangDownloaded(code: String) {
        downloadedLangs = downloadedLangs + code
    }

    fun isLangDownloaded(code: String): Boolean = code in downloadedLangs

    var isHapticEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit { putBoolean(KEY_HAPTIC, value) }

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, false)
        set(value) = prefs.edit { putBoolean(KEY_SOUND, value) }

    var isClipboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD, true)
        set(value) = prefs.edit { putBoolean(KEY_CLIPBOARD, value) }

    /** Hybrid auto-correct (SymSpell native + personal dict + optional TFLite next-word). Default ON. */
    var isAutoCorrectEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOCORRECT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTOCORRECT, value) }

    /**
     * Capitalize the next letter at sentence start (field start, after newline,
     * or after `.` `!` `?` + space). Default ON.
     */
    var isAutoCapitalizeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAP, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CAP, value) }

    /**
     * Insert a space automatically after sentence / clause punctuation
     * (`.`, `!`, `?`, `,`, `;`, `:`). Undo with one backspace. Default ON.
     */
    var isAutoSpaceAfterPunctEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SPACE_PUNCT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SPACE_PUNCT, value) }

    /** system | light | dark */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit { putString(KEY_THEME, value) }

    var keyboardHeightPercent: Int
        get() = prefs.getInt(KEY_HEIGHT_PCT, 100).coerceIn(HEIGHT_MIN, HEIGHT_MAX)
        set(value) = prefs.edit { putInt(KEY_HEIGHT_PCT, value.coerceIn(HEIGHT_MIN, HEIGHT_MAX)) }

    var keyboardWidthPercent: Int
        get() = prefs.getInt(KEY_WIDTH_PCT, 100).coerceIn(WIDTH_MIN, WIDTH_MAX)
        set(value) = prefs.edit { putInt(KEY_WIDTH_PCT, value.coerceIn(WIDTH_MIN, WIDTH_MAX)) }

    var keyboardBottomOffsetDp: Int
        get() = prefs.getInt(KEY_BOTTOM_OFFSET, 0).coerceIn(OFFSET_MIN, OFFSET_MAX)
        set(value) = prefs.edit { putInt(KEY_BOTTOM_OFFSET, value.coerceIn(OFFSET_MIN, OFFSET_MAX)) }

    /** Scale of key face / label size (70–130%). */
    var keySizePercent: Int
        get() = prefs.getInt(KEY_KEY_SIZE_PCT, 100).coerceIn(KEY_SIZE_MIN, KEY_SIZE_MAX)
        set(value) = prefs.edit { putInt(KEY_KEY_SIZE_PCT, value.coerceIn(KEY_SIZE_MIN, KEY_SIZE_MAX)) }

    /** Spacing / density between keys (0–250%, 100 = default). */
    var keySpacingPercent: Int
        get() = prefs.getInt(KEY_KEY_SPACING_PCT, 100).coerceIn(SPACING_MIN, SPACING_MAX)
        set(value) = prefs.edit { putInt(KEY_KEY_SPACING_PCT, value.coerceIn(SPACING_MIN, SPACING_MAX)) }

    companion object {
        private const val PREFS_NAME = "smart_keyboard_prefs"
        private const val KEY_TRANSLATION = "translation_mode"
        private const val KEY_TARGET_LANG = "translation_target_lang"
        private const val KEY_DOWNLOADED_LANGS = "downloaded_langs"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_SOUND = "sound"
        private const val KEY_CLIPBOARD = "clipboard"
        private const val KEY_AUTOCORRECT = "autocorrect"
        private const val KEY_AUTO_CAP = "auto_capitalize"
        private const val KEY_AUTO_SPACE_PUNCT = "auto_space_after_punct"
        private const val KEY_THEME = "theme_mode"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        private const val KEY_HEIGHT_PCT = "keyboard_height_pct"
        private const val KEY_WIDTH_PCT = "keyboard_width_pct"
        private const val KEY_BOTTOM_OFFSET = "keyboard_bottom_offset_dp"
        private const val KEY_KEY_SIZE_PCT = "key_size_pct"
        private const val KEY_KEY_SPACING_PCT = "key_spacing_pct"

        const val HEIGHT_MIN = 50
        const val HEIGHT_MAX = 150
        const val WIDTH_MIN = 80
        const val WIDTH_MAX = 100
        const val OFFSET_MIN = 0
        const val OFFSET_MAX = 64
        const val KEY_SIZE_MIN = 70
        const val KEY_SIZE_MAX = 130
        const val SPACING_MIN = 0
        const val SPACING_MAX = 250
    }
}
