package com.smartkeyboard.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.smartkeyboard.ime.R
import com.smartkeyboard.ime.utils.PreferencesHelper

/**
 * Keyboard UI: adaptive size, responsive keys, theme-aware colors,
 * suggestion bar with translate, numbers layout matching standard IME.
 */
class KeyboardLayoutManager(private val context: Context) {

    companion object {
        const val KEYCODE_SHIFT = -1
        const val KEYCODE_DELETE = -2
        const val KEYCODE_ENTER = -3
        const val KEYCODE_SPACE = -4
        const val KEYCODE_SYMBOLS = -5
        const val KEYCODE_ABC = -6
        const val KEYCODE_EMOJI = -7
        const val KEYCODE_CLIPBOARD = -9
        const val KEYCODE_TRANSLATE = -10
        const val KEYCODE_NUMBERS = -11

        private const val BASE_KEY_HEIGHT_DP = 46
        private const val BASE_SUGGESTION_HEIGHT_DP = 48
        private const val BASE_KEY_MARGIN_DP = 4
        private const val BASE_LETTER_TEXT_SP = 18f
        private const val BASE_SPECIAL_TEXT_SP = 15f
        private const val DELETE_INITIAL_DELAY_MS = 280L
        private const val DELETE_REPEAT_MS = 35L
        private const val KEY_CORNER_RADIUS_DP = 10f
        private const val CHIP_CORNER_RADIUS_DP = 20f
        private const val TAG_SUGGESTION_CONTAINER = "suggestion_container"
        private const val TAG_TRANSLATE_INDICATOR = "translate_indicator"
    }

    private val prefs = PreferencesHelper(context)
    private val handler = Handler(Looper.getMainLooper())

    private var suggestionContainer: LinearLayout? = null
    private var translationIndicator: TextView? = null
    private var translationBarRoot: LinearLayout? = null
    private var translationSourceView: TextView? = null
    private var translationResultView: TextView? = null
    private var isTranslationUiActive = false
    /** Letter keys show uppercase when true. */
    private var currentShiftState = false
    /** ⇧ key uses primary color when true (manual shift / caps-lock only). */
    private var currentShiftHighlight = false
    private val letterKeys = mutableListOf<Pair<TextView, String>>()
    /** Per-mode letter key refs so cache hit still updates capitalization. */
    private val letterKeysByMode = mutableMapOf<KeyboardMode, MutableList<Pair<TextView, String>>>()
    private val shiftKeyByMode = mutableMapOf<KeyboardMode, TextView?>()
    private var shiftKeyView: TextView? = null
    private var activeMode: KeyboardMode = KeyboardMode.LETTERS

    private var onKeyListener: ((Int, String?) -> Unit)? = null
    private var onSuggestionSelected: ((String, Boolean) -> Unit)? = null

    private var heightScale = 1f
    private var keyHeightPx = 0
    private var suggestionHeightPx = 0
    private var keyMarginPx = 0
    private var letterTextSp = BASE_LETTER_TEXT_SP
    private var specialTextSp = BASE_SPECIAL_TEXT_SP
    private var bottomOffsetPx = 0
    private var widthScaleCached = 1f

    // Theme-resolved colors
    private var colorKbBg = 0
    private var colorKeyBg = 0
    private var colorKeySpecialBg = 0
    private var colorKeyText = 0
    private var colorKeySpecialText = 0
    private var colorSuggestionBg = 0
    private var colorSuggestionText = 0
    private var colorPrimary = 0

    private var deleteRepeating = false
    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            if (deleteRepeating) {
                onKeyListener?.invoke(KEYCODE_DELETE, null)
                handler.postDelayed(this, DELETE_REPEAT_MS)
            }
        }
    }

    // ---- View cache (letters / numbers / symbols) ----
    private val cachedViews = mutableMapOf<KeyboardMode, View>()
    private var cacheFingerprint: String? = null
    private val enterKeyViews = mutableListOf<TextView>()
    private var currentFieldKind: FieldKind = FieldKind.TEXT
    private var currentImeAction: Int = EditorInfo.IME_ACTION_NONE

    // Reusable drawables keyed by (color shl 32) or (color + radius bits)
    private val TAG = "KbLayout"

    private fun isDarkTheme(): Boolean {
        return when (prefs.themeMode) {
            PreferencesHelper.THEME_LIGHT -> false
            PreferencesHelper.THEME_DARK -> true
            else -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                night == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    /**
     * Context forced to light/dark so resource colors match the chosen theme,
     * even if the IME process still follows system night mode.
     */
    private fun themedContext(): Context {
        val config = Configuration(context.resources.configuration)
        val nightFlag = if (isDarkTheme()) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightFlag
        return context.createConfigurationContext(config)
    }

    /**
     * Resolve keyboard palette for Light / Dark / System (Monet).
     * Called on every keyboard rebuild so Settings theme changes apply immediately.
     */
    private fun resolveThemeColors() {
        val dark = isDarkTheme()
        // Baseline palettes (always valid fallback)
        if (dark) {
            colorKbBg = 0xFF1C1C1E.toInt()
            colorKeyBg = 0xFF2C2C2E.toInt()
            colorKeySpecialBg = 0xFF3A3A3C.toInt()
            colorKeyText = 0xFFFFFFFF.toInt()
            colorKeySpecialText = 0xFFE5E5EA.toInt()
            colorSuggestionBg = 0xFF1C1C1E.toInt()
            colorSuggestionText = 0xFFE5E5EA.toInt()
            colorPrimary = 0xFF0A84FF.toInt()
        } else {
            colorKbBg = 0xFFE8EAED.toInt()
            colorKeyBg = 0xFFFFFFFF.toInt()
            colorKeySpecialBg = 0xFFD1D5DB.toInt()
            colorKeyText = 0xFF1F1F1F.toInt()
            colorKeySpecialText = 0xFF3C4043.toInt()
            colorSuggestionBg = 0xFFE8EAED.toInt()
            colorSuggestionText = 0xFF3C4043.toInt()
            colorPrimary = 0xFF1A73E8.toInt()
        }

        val themed = themedContext()
        try {
            // App resource colors (values / values-night via forced config)
            colorPrimary = ContextCompat.getColor(themed, R.color.primary)
            colorKbBg = ContextCompat.getColor(themed, R.color.keyboard_background)
            colorKeyBg = ContextCompat.getColor(themed, R.color.key_background)
            colorKeySpecialBg = ContextCompat.getColor(themed, R.color.key_special_background)
            colorKeyText = ContextCompat.getColor(themed, R.color.key_text)
            colorKeySpecialText = ContextCompat.getColor(themed, R.color.key_special_text)
            colorSuggestionBg = ContextCompat.getColor(themed, R.color.suggestion_background)
            colorSuggestionText = ContextCompat.getColor(themed, R.color.suggestion_text)
        } catch (e: Exception) { Log.w(TAG, "resolve resource colors failed", e) }

        // System / Monet: pull live Material You colors when theme is "system"
        if (prefs.themeMode == PreferencesHelper.THEME_SYSTEM) {
            tryApplyMonetColors()
        }
    }

    /**
     * Android 12+ Material You (Monet) dynamic colors for keyboard surfaces.
     * Uses multiple strategies so it works reliably inside InputMethodService
     * (where plain DynamicColors.wrapContextIfAvailable often fails).
     */
    private fun tryApplyMonetColors() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        try {
            // Strategy 1: ContextThemeWrapper with Material3 DynamicColors theme
            // then wrap with DynamicColors so seed color from wallpaper is applied.
            val baseTheme = if (isDarkTheme()) {
                com.google.android.material.R.style.Theme_Material3_DynamicColors_Dark
            } else {
                com.google.android.material.R.style.Theme_Material3_DynamicColors_Light
            }
            var monetCtx: Context = ContextThemeWrapper(context, baseTheme)
            try {
                monetCtx = DynamicColors.wrapContextIfAvailable(monetCtx)
            } catch (e: Exception) { Log.w(TAG, "resolve resource colors failed", e) }

            // Strategy 2: also try wrapping the original context (some OEMs need it)
            val altCtx = try {
                DynamicColors.wrapContextIfAvailable(context)
            } catch (_: Exception) {
                monetCtx
            }

            fun resolveColor(ctx: Context, attr: Int, fallback: Int): Int {
                return try {
                    MaterialColors.getColor(ctx, attr, fallback)
                } catch (_: Exception) {
                    try {
                        val tv = TypedValue()
                        if (ctx.theme.resolveAttribute(attr, tv, true)) {
                            if (tv.resourceId != 0) {
                                ContextCompat.getColor(ctx, tv.resourceId)
                            } else {
                                tv.data
                            }
                        } else fallback
                    } catch (_: Exception) {
                        fallback
                    }
                }
            }

            // Prefer MaterialColors (most reliable with dynamic themes)
            val surface = resolveColor(monetCtx, com.google.android.material.R.attr.colorSurface, colorKbBg)
            val onSurface = resolveColor(monetCtx, com.google.android.material.R.attr.colorOnSurface, colorKeyText)
            val surfaceVariant = resolveColor(monetCtx, com.google.android.material.R.attr.colorSurfaceVariant, colorKeySpecialBg)
            val onSurfaceVariant = resolveColor(monetCtx, com.google.android.material.R.attr.colorOnSurfaceVariant, colorKeySpecialText)
            val primary = resolveColor(monetCtx, com.google.android.material.R.attr.colorPrimary, colorPrimary)
            val primaryContainer = resolveColor(monetCtx, com.google.android.material.R.attr.colorPrimaryContainer, colorKeyBg)
            val onPrimaryContainer = resolveColor(monetCtx, com.google.android.material.R.attr.colorOnPrimaryContainer, colorKeyText)

            // Fallback check: if still identical to static fallbacks, try alt context
            val usedAlt = (primary == colorPrimary && surface == colorKbBg)
            val finalCtx = if (usedAlt) altCtx else monetCtx

            val finalSurface = if (usedAlt) resolveColor(finalCtx, com.google.android.material.R.attr.colorSurface, surface) else surface
            val finalOnSurface = if (usedAlt) resolveColor(finalCtx, com.google.android.material.R.attr.colorOnSurface, onSurface) else onSurface
            val finalSurfaceVariant = if (usedAlt) resolveColor(finalCtx, com.google.android.material.R.attr.colorSurfaceVariant, surfaceVariant) else surfaceVariant
            val finalOnSurfaceVariant = if (usedAlt) resolveColor(finalCtx, com.google.android.material.R.attr.colorOnSurfaceVariant, onSurfaceVariant) else onSurfaceVariant
            val finalPrimary = if (usedAlt) resolveColor(finalCtx, com.google.android.material.R.attr.colorPrimary, primary) else primary
            val finalPrimaryContainer = if (usedAlt) resolveColor(finalCtx, com.google.android.material.R.attr.colorPrimaryContainer, primaryContainer) else primaryContainer
            val finalOnPrimaryContainer = if (usedAlt) resolveColor(finalCtx, com.google.android.material.R.attr.colorOnPrimaryContainer, onPrimaryContainer) else onPrimaryContainer

            // Map Material roles → keyboard parts (Material 3 tonal mapping)
            colorKbBg = finalSurface
            colorSuggestionBg = finalSurface
            colorKeyBg = finalPrimaryContainer
            colorKeyText = finalOnPrimaryContainer
            colorKeySpecialBg = finalSurfaceVariant
            colorKeySpecialText = finalOnSurfaceVariant
            colorSuggestionText = finalOnSurface
            colorPrimary = finalPrimary
        } catch (e: Exception) {
            Log.w(TAG, "Monet color resolve failed", e)
            // Keep resource / baseline colors — never crash the IME
        }
    }

    private fun refreshMetrics() {
        heightScale = prefs.keyboardHeightPercent / 100f
        widthScaleCached = prefs.keyboardWidthPercent / 100f
        val sizeScale = prefs.keySizePercent / 100f
        val spacingScale = prefs.keySpacingPercent / 100f
        // Height of each key row
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val landscapeFactor = if (isLandscape) 0.72f else 1f
        keyHeightPx = dp((BASE_KEY_HEIGHT_DP * heightScale * sizeScale * landscapeFactor).toInt().coerceIn(28, 90))
        suggestionHeightPx = dp((BASE_SUGGESTION_HEIGHT_DP * heightScale * landscapeFactor).toInt().coerceAtLeast(32))
        // Gap between keys (kerapatan)
        keyMarginPx = dp((BASE_KEY_MARGIN_DP * spacingScale).toInt().coerceIn(0, 12))
        // Label size follows "ukuran tombol"
        letterTextSp = (BASE_LETTER_TEXT_SP * sizeScale * (0.85f + 0.15f * heightScale)).coerceIn(11f, 30f)
        specialTextSp = (BASE_SPECIAL_TEXT_SP * sizeScale * (0.85f + 0.15f * heightScale)).coerceIn(10f, 24f)
        bottomOffsetPx = dp(prefs.keyboardBottomOffsetDp)
        resolveThemeColors()
    }

    data class ThemeColors(
        val bg: Int,
        val keyBg: Int,
        val specialBg: Int,
        val text: Int,
        val specialText: Int,
        val primary: Int
    )

    fun currentThemeColors(): ThemeColors {
        // Ensure palette is resolved
        if (colorKbBg == 0) refreshMetrics()
        return ThemeColors(
            bg = colorKbBg,
            keyBg = colorKeyBg,
            specialBg = colorKeySpecialBg,
            text = colorKeyText,
            specialText = colorKeySpecialText,
            primary = colorPrimary
        )
    }

    fun invalidateCache() {
        cachedViews.clear()

        cacheFingerprint = null
        enterKeyViews.clear()
        letterKeys.clear()
        letterKeysByMode.clear()
        shiftKeyByMode.clear()
        shiftKeyView = null
        suggestionContainer = null
        translationIndicator = null
        translationBarRoot = null
        translationSourceView = null
        translationResultView = null
    }

    private fun computeFingerprint(): String {
        val orient = context.resources.configuration.orientation
        val night = isDarkTheme()
        // fieldKind + imeAction affect bottom-row keys and enter label
        return "${prefs.themeMode}|${prefs.keyboardHeightPercent}|${prefs.keyboardWidthPercent}|${prefs.keySizePercent}|${prefs.keySpacingPercent}|${prefs.keyboardBottomOffsetDp}|$orient|$night|${prefs.isTranslationModeEnabled}|$currentFieldKind|$currentImeAction"
    }

    /**
     * Returns a cached keyboard view for [mode] when fingerprint matches,
     * otherwise rebuilds. Rebuild only on size / theme / orientation change.
     */
    fun getOrCreateKeyboardView(
        mode: KeyboardMode,
        onKeyListener: (Int, String?) -> Unit,
        onModeChange: (KeyboardMode) -> Unit,
        onSuggestionSelected: (word: String, isTranslation: Boolean) -> Unit = { _, _ -> },
        initialShifted: Boolean = false,
        forceRebuild: Boolean = false
    ): View {
        this.onKeyListener = onKeyListener
        this.onSuggestionSelected = onSuggestionSelected
        val fp = computeFingerprint()
        if (forceRebuild || cacheFingerprint != fp) {
            invalidateCache()
            cacheFingerprint = fp
        }
        currentShiftState = initialShifted
        // Don't force ⇧ highlight from initialShifted alone — caller follows up with
        // updateShiftState(lettersUpper, highlightKey) for the correct indicator.
        activeMode = mode
        val cached = cachedViews[mode]
        if (cached != null) {
            // Restore key refs for this mode so shift / enter / suggestion bar updates work
            letterKeys.clear()
            letterKeysByMode[mode]?.let { letterKeys.addAll(it) }
            shiftKeyView = shiftKeyByMode[mode]
            // CRITICAL: rebind suggestion/translation bar refs to THIS mode's tree.
            // Without this, suggestionContainer still points at another mode's bar
            // and updateTranslationTexts silently no-ops → preview looks "stuck".
            rebindSuggestionBarRefs(cached)
            resetKeyBackgrounds(cached)
            updateShiftState(currentShiftState, highlightKey = currentShiftHighlight)
            updateEnterKeyForAction(currentImeAction)
            return cached
        }
        val built = buildKeyboardView(mode, onModeChange)
        // Snapshot letter/shift refs for this mode
        letterKeysByMode[mode] = letterKeys.toMutableList()
        shiftKeyByMode[mode] = shiftKeyView
        cachedViews[mode] = built
        return built
    }

    /**
     * Walk a keyboard root and re-attach [suggestionContainer], [translationIndicator],
     * and translation panel TextViews so updates target the visible tree.
     */
    private fun rebindSuggestionBarRefs(root: View) {
        val container = findViewWithTagRecursive(root, TAG_SUGGESTION_CONTAINER) as? LinearLayout
        if (container != null) {
            suggestionContainer = container
        }
        val indicator = findViewWithTagRecursive(root, TAG_TRANSLATE_INDICATOR) as? TextView
        if (indicator != null) {
            translationIndicator = indicator
        }
        // Re-bind translation panel views if present inside the container
        val panel = container?.let { c ->
            (0 until c.childCount).map { c.getChildAt(it) }.firstOrNull { it.tag == "trans_panel" }
        } as? LinearLayout
        if (panel != null && panel.childCount >= 2) {
            translationResultView = panel.getChildAt(0) as? TextView
            translationSourceView = panel.getChildAt(1) as? TextView
            translationBarRoot = panel
        } else {
            translationResultView = null
            translationSourceView = null
            translationBarRoot = null
        }
    }

    private fun findViewWithTagRecursive(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findViewWithTagRecursive(root.getChildAt(i), tag)
                if (found != null) return found
            }
        }
        return null
    }

    /** Walk tree and re-apply normal special/letter backgrounds after reattach. */
    private fun resetKeyBackgrounds(root: View) {
        if (root is TextView && root.isClickable) {
            val label = root.text?.toString().orEmpty()
            val isLetter = label.length == 1 && label[0].isLetter()
            val special = !isLetter || label in listOf(",", ".")
            // Only reset if it looks like a key (has our rounded bg already)
            if (root.background is GradientDrawable || root.background is StateListDrawable) {
                val bg = if (special || root === shiftKeyView) colorKeySpecialBg else colorKeyBg
                // Preserve manual/caps shift highlight only
                if (root === shiftKeyView && currentShiftHighlight) {
                    applyRoundedBg(root, colorPrimary)
                } else {
                    applyRoundedBg(root, bg)
                }
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                resetKeyBackgrounds(root.getChildAt(i))
            }
        }
    }

    private fun buildKeyboardView(mode: KeyboardMode, onModeChange: (KeyboardMode) -> Unit): View {
        letterKeys.clear()
        shiftKeyView = null
        enterKeyViews.clear()
        activeMode = mode
        stopDeleteRepeat()
        refreshMetrics()

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorKbBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, bottomOffsetPx)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (widthScaleCached < 0.99f) {
                val sidePad = ((1f - widthScaleCached) / 2f * context.resources.displayMetrics.widthPixels).toInt()
                setPadding(sidePad, 0, sidePad, 0)
            }
        }

        root.addView(createSuggestionBar())

        when (mode) {
            KeyboardMode.LETTERS -> root.addView(createLettersLayout())
            KeyboardMode.NUMBERS -> root.addView(createNumbersLayout())
            KeyboardMode.SYMBOLS -> root.addView(createSymbolsLayout())
            else -> root.addView(createLettersLayout())
        }

        outer.addView(root)
        return outer
    }

    fun setFieldKind(kind: FieldKind) {
        currentFieldKind = kind
    }

    fun updateEnterKeyForAction(imeAction: Int) {
        currentImeAction = imeAction
        val label = enterLabelFor(imeAction)
        enterKeyViews.forEach { it.text = label }
    }

    private fun enterLabelFor(action: Int): String {
        return when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_NEXT -> "⇥"
            EditorInfo.IME_ACTION_DONE -> "✓"
            EditorInfo.IME_ACTION_PREVIOUS -> "⇤"
            else -> "↵"
        }
    }

    fun updateSuggestions(suggestions: List<String>, primaryIndex: Int = -1) {
        // Keep action buttons (translate) — only refresh chips after them
        val container = suggestionContainer ?: return
        val toRemove = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.getTag() == "chip") toRemove.add(v)
        }
        toRemove.forEach { container.removeView(it) }
        suggestions.take(3).forEachIndexed { index, word ->
            val primary = if (primaryIndex >= 0) index == primaryIndex else index == suggestions.take(3).size / 2
            container.addView(createSuggestionChip(word, isTranslation = false, isPrimary = primary))
        }
    }

    fun clearSuggestions() {
        val container = suggestionContainer ?: return
        val toRemove = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.getTag() == "chip") toRemove.add(v)
        }
        toRemove.forEach { container.removeView(it) }
    }

    /**
     * @param lettersUpper show uppercase labels on letter keys
     * @param highlightKey color the ⇧ key (manual shift / caps-lock only —
     *        auto-cap at sentence start does NOT highlight so the indicator stays clear)
     */
    fun updateShiftState(lettersUpper: Boolean, highlightKey: Boolean = lettersUpper) {
        currentShiftState = lettersUpper
        currentShiftHighlight = highlightKey
        letterKeys.forEach { (btn, baseLabel) ->
            btn.text = if (lettersUpper) baseLabel.uppercase() else baseLabel.lowercase()
        }
        shiftKeyView?.let { sk ->
            if (highlightKey) {
                applyRoundedBg(sk, colorPrimary)
                sk.setTextColor(0xFFFFFFFF.toInt())
            } else {
                applyRoundedBg(sk, colorKeySpecialBg)
                sk.setTextColor(colorKeySpecialText)
            }
        }
    }

    fun updateTranslationIndicator(enabled: Boolean) {
        translationIndicator?.text = if (enabled) "ON" else "⇄"
        translationIndicator?.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else colorKeySpecialText)
        if (enabled) {
            translationIndicator?.let { applyRoundedBg(it, colorPrimary, CHIP_CORNER_RADIUS_DP) }
        } else {
            translationIndicator?.let { applyRoundedBg(it, colorKeySpecialBg, CHIP_CORNER_RADIUS_DP) }
        }
        isTranslationUiActive = enabled
        // Rebuild bar appearance when toggling
        if (enabled) {
            showTranslationModeBar("", "")
        } else {
            hideTranslationModeBar()
        }
    }

    /**
     * Gboard-style translation bar:
     *  - Top: English result (hasil konversi) — this goes into the real text field
     *  - Bottom: Indonesian source being typed
     */
    fun showTranslationModeBar(sourceId: String, resultEn: String) {
        val container = suggestionContainer ?: return
        isTranslationUiActive = true
        // Clear normal chips + any previous panel
        val toRemove = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.getTag() == "chip" || v.getTag() == "trans_panel") toRemove.add(v)
        }
        toRemove.forEach { container.removeView(it) }

        // Ensure middle area uses full width so text can stretch toward emoji buttons
        (container.parent as? android.widget.HorizontalScrollView)?.let { hsv ->
            hsv.isFillViewport = true
        }
        container.layoutParams = container.layoutParams?.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        } ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val panel = LinearLayout(context).apply {
            tag = "trans_panel"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        // Result (translated) — START ellipsize so latest words stay visible
        val resultTv = TextView(context).apply {
            text = if (resultEn.isNotEmpty()) resultEn else "…"
            setTextColor(colorPrimary)
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (15f * heightScale).coerceIn(12f, 20f))
            isSingleLine = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        translationResultView = resultTv
        panel.addView(resultTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Source (Indonesian) — START ellipsize: hide oldest chars, show current typing
        val sourceTv = TextView(context).apply {
            text = if (sourceId.isNotEmpty()) sourceId else "Ketik bahasa Indonesia…"
            setTextColor(colorSuggestionText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (13f * heightScale).coerceIn(11f, 17f))
            isSingleLine = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            alpha = if (sourceId.isEmpty()) 0.55f else 1f
        }
        translationSourceView = sourceTv
        panel.addView(sourceTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        container.addView(panel)
        translationBarRoot = panel
    }

    fun updateTranslationTexts(sourceId: String, resultEn: String) {
        if (!isTranslationUiActive) return
        // Views may be null after mode switch / cache reuse — rebuild the panel
        if (translationSourceView == null || translationResultView == null) {
            showTranslationModeBar(sourceId, resultEn)
            return
        }
        // Views detached from window (stale refs) → rebuild
        if (translationSourceView?.isAttachedToWindow == false ||
            translationResultView?.isAttachedToWindow == false
        ) {
            showTranslationModeBar(sourceId, resultEn)
            return
        }
        translationSourceView?.let {
            it.text = if (sourceId.isNotEmpty()) sourceId else "Ketik bahasa Indonesia…"
            it.alpha = if (sourceId.isEmpty()) 0.55f else 1f
            it.ellipsize = android.text.TextUtils.TruncateAt.START
        }
        translationResultView?.let {
            it.text = if (resultEn.isNotEmpty()) resultEn else "…"
            it.ellipsize = android.text.TextUtils.TruncateAt.START
        }
    }

    fun hideTranslationModeBar() {
        isTranslationUiActive = false
        val container = suggestionContainer
        if (container != null) {
            val toRemove = mutableListOf<View>()
            for (i in 0 until container.childCount) {
                val v = container.getChildAt(i)
                if (v.getTag() == "trans_panel") toRemove.add(v)
            }
            toRemove.forEach { container.removeView(it) }
        }
        translationBarRoot = null
        translationSourceView = null
        translationResultView = null
    }

    /**
     * Material 3 language picker panel — uses current keyboard theme
     * (Light / Dark / Monet) and rounded chips.
     *
     * @param items Triple(code, label, downloaded)
     * @param currentCode currently selected target language code
     */
    fun createLanguagePickerView(
        items: List<Triple<String, String, Boolean>>,
        currentCode: String,
        onLanguageSelected: (code: String) -> Unit,
        onNotDownloaded: (label: String) -> Unit,
        onClose: () -> Unit
    ): View {
        refreshMetrics() // ensure theme colors (incl. Monet) are up to date

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorKbBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(10), dp(10), dp(10), dp(12))
        }

        // Header card
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(10))
        }
        header.addView(TextView(context).apply {
            text = "Terjemahkan ke…"
            setTextColor(colorKeyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            text = "Tutup"
            setTextColor(colorPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            applyRoundedBg(this, colorKeySpecialBg, CHIP_CORNER_RADIUS_DP)
            setOnClickListener { onClose() }
        })
        root.addView(header)

        val scroll = android.widget.ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(280)
            )
            isVerticalScrollBarEnabled = false
        }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        items.forEach { (code, label, downloaded) ->
            val isCurrent = code == currentCode && downloaded
            val row = TextView(context).apply {
                text = buildString {
                    append(label)
                    if (isCurrent) append("  ✓")
                    if (!downloaded) append("  · belum diunduh")
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                gravity = Gravity.CENTER_VERTICAL
                if (downloaded) {
                    setTextColor(if (isCurrent) 0xFFFFFFFF.toInt() else colorKeyText)
                    applyRoundedBg(
                        this,
                        if (isCurrent) colorPrimary else colorKeyBg,
                        KEY_CORNER_RADIUS_DP
                    )
                    setOnClickListener { onLanguageSelected(code) }
                } else {
                    setTextColor(colorKeySpecialText)
                    alpha = 0.45f
                    applyRoundedBg(this, colorKeySpecialBg, KEY_CORNER_RADIUS_DP)
                    setOnClickListener { onNotDownloaded(label) }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                }
            }
            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll)
        return root
    }


    private fun createSuggestionChip(
        display: String,
        isTranslation: Boolean,
        rawWord: String? = null,
        isPrimary: Boolean = false
    ): TextView {
        val word = rawWord ?: display
        return TextView(context).apply {
            tag = "chip"
            text = display
            setTextColor(if (isTranslation) colorPrimary else colorSuggestionText)
            if (isTranslation || isPrimary) setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (16f * heightScale).coerceIn(12f, 22f))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            applyRoundedBg(this, when {
                isTranslation -> colorPrimary
                isPrimary -> colorPrimary
                else -> colorKeySpecialBg
            }, CHIP_CORNER_RADIUS_DP)
            if (isTranslation || isPrimary) setTextColor(0xFFFFFFFF.toInt())
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { v.alpha = 0.5f; true }
                    MotionEvent.ACTION_UP -> {
                        v.alpha = 1f
                        onSuggestionSelected?.invoke(word, isTranslation)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; true }
                    else -> false
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun createSuggestionBar(): View {
        // Taller bar so dual-line translation UI fits comfortably
        val barHeight = if (prefs.isTranslationModeEnabled) {
            (suggestionHeightPx * 1.55f).toInt().coerceAtLeast(dp(56))
        } else {
            suggestionHeightPx
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colorSuggestionBg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight
            )
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        // Translate toggle (left)
        val translateBtn = createBarActionButton(
            if (prefs.isTranslationModeEnabled) "ON" else "⇄",
            KEYCODE_TRANSLATE
        )
        if (prefs.isTranslationModeEnabled) {
            applyRoundedBg(translateBtn, colorPrimary, CHIP_CORNER_RADIUS_DP)
            translateBtn.setTextColor(0xFFFFFFFF.toInt())
        }
        translateBtn.tag = TAG_TRANSLATE_INDICATOR
        translationIndicator = translateBtn
        bar.addView(translateBtn)

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true  // needed so translation preview can use full middle width
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        suggestionContainer = LinearLayout(context).apply {
            tag = TAG_SUGGESTION_CONTAINER
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        scroll.addView(suggestionContainer)
        bar.addView(scroll)

        // Emoji + Clipboard fixed on the right
        bar.addView(createBarActionButton("😊", KEYCODE_EMOJI))
        bar.addView(createBarActionButton("📋", KEYCODE_CLIPBOARD))

        // If translation already ON when building keyboard, show empty dual bar
        if (prefs.isTranslationModeEnabled) {
            isTranslationUiActive = true
            // Will be filled by showTranslationModeBar after return
        }
        return bar
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBarActionButton(label: String, code: Int): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(colorKeySpecialText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            applyRoundedBg(this, colorKeySpecialBg, CHIP_CORNER_RADIUS_DP)
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { v.alpha = 0.5f; true }
                    MotionEvent.ACTION_UP -> {
                        v.alpha = 1f
                        onKeyListener?.invoke(code, label)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; true }
                    else -> false
                }
            }
        }
    }

    private fun createLettersLayout(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        container.addView(createKeyRow(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")))
        val sideInset = (18 * heightScale).toInt().coerceAtLeast(8)
        container.addView(createKeyRow(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"), sideInset))

        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val shiftBtn = createSpecialKey("⇧", KEYCODE_SHIFT, weight = 1.4f)
        shiftKeyView = shiftBtn
        // Only highlight on manual shift / caps-lock — not on auto-cap alone
        if (currentShiftHighlight) {
            applyRoundedBg(shiftBtn, colorPrimary)
            shiftBtn.setTextColor(0xFFFFFFFF.toInt())
        }
        row3.addView(shiftBtn)
        listOf("z", "x", "c", "v", "b", "n", "m").forEach { c -> row3.addView(createLetterKey(c)) }
        row3.addView(createDeleteKey(weight = 1.4f))
        container.addView(row3)

        // Bottom: ?123 | , | [long space] | . | enter
        // For email / URI fields, expose @ and / (or .com) shortcuts
        val row4 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = keyMarginPx }
        }
        row4.addView(createSpecialKey("?123", KEYCODE_NUMBERS, weight = 1.4f))
        when (currentFieldKind) {
            FieldKind.EMAIL -> {
                row4.addView(createSpecialKey("@", '@'.code, weight = 1.2f, isChar = true))
                row4.addView(createSpecialKey(" ", KEYCODE_SPACE, weight = 4.5f))
                row4.addView(createSpecialKey(".com", 0, weight = 1.6f, isChar = true))
            }
            FieldKind.URI -> {
                row4.addView(createSpecialKey("/", '/'.code, weight = 1.2f, isChar = true))
                row4.addView(createSpecialKey(" ", KEYCODE_SPACE, weight = 4.5f))
                row4.addView(createSpecialKey(".", '.'.code, weight = 1.2f, isChar = true))
            }
            else -> {
                row4.addView(createSpecialKey(",", ','.code, weight = 1.0f, isChar = true))
                row4.addView(createSpecialKey(" ", KEYCODE_SPACE, weight = 6.5f))
                row4.addView(createSpecialKey(".", '.'.code, weight = 1.0f, isChar = true))
            }
        }
        row4.addView(createEnterKey(weight = 1.5f))
        container.addView(row4)
        return container
    }

    /**
     * Numbers layout matching standard IME (screenshot):
     * 1 2 3 4 5 6 7 8 9 0
     * @ # $ _ & - + ( ) /
     * =\< * " ' : ; ! ?  ⌫
     * ABC  ,  [space]  .  🔍
     */
    private fun createNumbersLayout(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        // Row 1: 1-0 with long-press → superscript (kuadrat)
        container.addView(createNumberRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")))
        // Row 2
        container.addView(createKeyRow(listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")))

        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row3.addView(createSpecialKey("=\\<", KEYCODE_SYMBOLS, weight = 1.4f))
        listOf("*", "\"", "'", ":", ";", "!", "?").forEach { c ->
            row3.addView(createLetterKey(c))
        }
        row3.addView(createDeleteKey(weight = 1.4f))
        container.addView(row3)

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = keyMarginPx }
        }
        bottom.addView(createSpecialKey("ABC", KEYCODE_ABC, weight = 1.5f))
        bottom.addView(createSpecialKey(",", ','.code, weight = 1.0f, isChar = true))
        bottom.addView(createSpecialKey(" ", KEYCODE_SPACE, weight = 4.0f))
        bottom.addView(createSpecialKey(".", '.'.code, weight = 1.0f, isChar = true))
        bottom.addView(createEnterKey(weight = 1.5f))
        container.addView(bottom)
        return container
    }

    /**
     * Symbols layout matching reference screenshot:
     * ~ ` | • √ π ÷ × ¶ △
     * £ ¢ € ¥ ^ ° = { } \
     * ?123 % © ® ™ ℅ [ ] ⌫
     * ABC  <  [space]  >  🔍
     */
    private fun createSymbolsLayout(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        container.addView(createKeyRow(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "△")))
        container.addView(createKeyRow(listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\")))

        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row3.addView(createSpecialKey("?123", KEYCODE_NUMBERS, weight = 1.4f))
        listOf("%", "©", "®", "™", "℅", "[", "]").forEach { c ->
            row3.addView(createLetterKey(c))
        }
        row3.addView(createDeleteKey(weight = 1.4f))
        container.addView(row3)

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = keyMarginPx }
        }
        bottom.addView(createSpecialKey("ABC", KEYCODE_ABC, weight = 1.5f))
        bottom.addView(createSpecialKey("<", '<'.code, weight = 1.0f, isChar = true))
        bottom.addView(createSpecialKey(" ", KEYCODE_SPACE, weight = 4.0f))
        bottom.addView(createSpecialKey(">", '>'.code, weight = 1.0f, isChar = true))
        bottom.addView(createEnterKey(weight = 1.5f))
        container.addView(bottom)
        return container
    }

    /** Number row: tap = digit, long-press = superscript (¹ ² ³ …) */
    private fun createNumberRow(keys: List<String>): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            keys.forEach { addView(createNumberKey(it)) }
        }
    }

    private fun createKeyRow(keys: List<String>, horizontalPadding: Int = 0): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (horizontalPadding > 0) {
                    leftMargin = dp(horizontalPadding)
                    rightMargin = dp(horizontalPadding)
                }
            }
            keys.forEach { addView(createLetterKey(it)) }
        }
    }

    /**
     * Lightweight key (TextView, not Button) for minimal latency.
     * Fires on ACTION_DOWN; expanded touch via small margins only.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createLetterKey(label: String): TextView {
        val base = label
        val isLetter = label.length == 1 && label[0].isLetter()
        val display = if (isLetter && currentShiftState) base.uppercase() else base
        return TextView(context).apply {
            text = display
            gravity = Gravity.CENTER
            setTextColor(colorKeyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, letterTextSp)
            applyRoundedBg(this, colorKeyBg)
            // Slight corner feel via padding; touch area = full layout slot
            layoutParams = LinearLayout.LayoutParams(0, keyHeightPx, 1f).apply {
                setMargins(keyMarginPx, keyMarginPx, keyMarginPx, keyMarginPx)
            }
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = false
            isLongClickable = false
            isSoundEffectsEnabled = false
            // Disable slow state animations
            stateListAnimator = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = base

            setOnTouchListener(createKeyTouchListener(base[0].code, base, this))
            if (isLetter) letterKeys.add(this to base.lowercase())
        }
    }

    private val superscriptMap = mapOf(
        "0" to "⁰", "1" to "¹", "2" to "²", "3" to "³", "4" to "⁴",
        "5" to "⁵", "6" to "⁶", "7" to "⁷", "8" to "⁸", "9" to "⁹"
    )

    /**
     * Digit key: short press → number, long press → superscript (kuadrat).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createNumberKey(label: String): TextView {
        val superChar = superscriptMap[label] ?: label
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(colorKeyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, letterTextSp)
            applyRoundedBg(this, colorKeyBg)
            layoutParams = LinearLayout.LayoutParams(0, keyHeightPx, 1f).apply {
                setMargins(keyMarginPx, keyMarginPx, keyMarginPx, keyMarginPx)
            }
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = false
            isLongClickable = true
            isSoundEffectsEnabled = false
            stateListAnimator = null
            contentDescription = label

            var longPressed = false
            var trackedPointerId = -1
            val longPressRunnable = Runnable {
                longPressed = true
                applyRoundedBg(this@apply, colorPrimary)
                onKeyListener?.invoke(superChar[0].code, superChar)
            }

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        // Multi-touch fix: a second finger landing here while another
                        // key is still held reports ACTION_POINTER_DOWN, not
                        // ACTION_DOWN — previously unhandled, so the tap was dropped
                        // during fast typing. See createKeyTouchListener for details.
                        if (trackedPointerId != -1) return@setOnTouchListener true
                        trackedPointerId = event.getPointerId(event.actionIndex)
                        longPressed = false
                        applyRoundedBg(v, colorPrimary)
                        if (prefs.isHapticEnabled) {
                            v.performHapticFeedback(
                                android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                            )
                        }
                        handler.removeCallbacks(longPressRunnable)
                        handler.postDelayed(longPressRunnable, 350)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        trackedPointerId = -1
                        handler.removeCallbacks(longPressRunnable)
                        applyRoundedBg(v, colorKeyBg)
                        if (!longPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                            onKeyListener?.invoke(label[0].code, label)
                        }
                        longPressed = false
                        true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.getPointerId(event.actionIndex) == trackedPointerId) {
                            trackedPointerId = -1
                            handler.removeCallbacks(longPressRunnable)
                            applyRoundedBg(v, colorKeyBg)
                            if (!longPressed) {
                                onKeyListener?.invoke(label[0].code, label)
                            }
                            longPressed = false
                        }
                        true
                    }
                    else -> true
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createEnterKey(weight: Float = 1.5f): TextView {
        val label = enterLabelFor(currentImeAction)
        return createSpecialKey(label, KEYCODE_ENTER, weight = weight).also {
            enterKeyViews.add(it)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createSpecialKey(
        label: String,
        code: Int,
        weight: Float = 1f,
        isChar: Boolean = false
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(colorKeySpecialText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, specialTextSp)
            applyRoundedBg(this, colorKeySpecialBg)
            layoutParams = LinearLayout.LayoutParams(0, keyHeightPx, weight).apply {
                setMargins(keyMarginPx, keyMarginPx, keyMarginPx, keyMarginPx)
            }
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = false
            isLongClickable = false
            isSoundEffectsEnabled = false
            stateListAnimator = null
            contentDescription = label
            setOnTouchListener(createKeyTouchListener(code, label, this))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDeleteKey(weight: Float = 1.4f): TextView {
        return TextView(context).apply {
            text = "⌫"
            gravity = Gravity.CENTER
            setTextColor(colorKeySpecialText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, specialTextSp)
            applyRoundedBg(this, colorKeySpecialBg)
            layoutParams = LinearLayout.LayoutParams(0, keyHeightPx, weight).apply {
                setMargins(keyMarginPx, keyMarginPx, keyMarginPx, keyMarginPx)
            }
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = false
            isSoundEffectsEnabled = false
            stateListAnimator = null
            contentDescription = "delete"

            var trackedPointerId = -1
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        // Multi-touch fix: the old check compared the pointer ID to a
                        // hard-coded 0, which is only ever true for the very first
                        // finger placed anywhere on screen since the gesture began —
                        // not "the first finger on this key". That made delete silently
                        // no-op whenever another finger was already down elsewhere
                        // (e.g. fast typing where the previous key hadn't lifted yet).
                        if (trackedPointerId != -1) return@setOnTouchListener true
                        trackedPointerId = event.getPointerId(event.actionIndex)
                        applyRoundedBg(v, colorPrimary)
                        if (prefs.isHapticEnabled) {
                            v.performHapticFeedback(
                                android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                            )
                        }
                        onKeyListener?.invoke(KEYCODE_DELETE, null)
                        deleteRepeating = true
                        handler.removeCallbacks(deleteRepeatRunnable)
                        handler.postDelayed(deleteRepeatRunnable, DELETE_INITIAL_DELAY_MS)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        trackedPointerId = -1
                        applyRoundedBg(v, colorKeySpecialBg)
                        stopDeleteRepeat()
                        true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.getPointerId(event.actionIndex) == trackedPointerId) {
                            trackedPointerId = -1
                            applyRoundedBg(v, colorKeySpecialBg)
                            stopDeleteRepeat()
                        }
                        true
                    }
                    else -> true
                }
            }
        }
    }

    /**
     * Shared touch handler: commit on DOWN only (once per gesture),
     * ignore MOVE jitter, reset visual on UP/CANCEL.
     * Returns true always so parent never steals the gesture.
     *
     * Multi-touch fix: when typing fast, a second finger frequently lands on the
     * next key BEFORE the first finger lifts off the previous one. For every
     * pointer after the first, Android reports ACTION_POINTER_DOWN/UP instead of
     * ACTION_DOWN/UP on that view — this handler used to fall through those into
     * `else -> true` and silently swallow the tap, which is why keys were dropped
     * specifically during fast typing. We now key off the pointer *index* rather
     * than the action name, and track "our" pointer id so a later MOVE/UP for a
     * different pointer on this same view (possible when two fingers briefly
     * overlap the same key) doesn't fire the key twice or reset the wrong state.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createKeyTouchListener(
        code: Int,
        label: String,
        view: TextView
    ): View.OnTouchListener {
        val normalBg = if (
            code == KEYCODE_SHIFT || code == KEYCODE_DELETE || code == KEYCODE_ENTER ||
            code == KEYCODE_SYMBOLS || code == KEYCODE_ABC || code == KEYCODE_EMOJI ||
            code == KEYCODE_CLIPBOARD || code == KEYCODE_TRANSLATE ||
            code == KEYCODE_NUMBERS || code == KEYCODE_SPACE ||
            label in listOf(",", ".")
        ) colorKeySpecialBg else colorKeyBg

        // Which pointer id this view is currently tracking (-1 = none). Needed so
        // that once a pointer has been accepted for this key, a stray extra
        // pointer moving across the same view doesn't re-trigger or interfere.
        var trackedPointerId = -1

        return View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Only react if we're not already tracking a pointer on this key
                    // (guards against a rare duplicate pointer-down for the same id).
                    if (trackedPointerId != -1) return@OnTouchListener true
                    trackedPointerId = event.getPointerId(event.actionIndex)
                    // Prevent parent layouts from stealing rapid taps
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    applyRoundedBg(v, colorPrimary)
                    if (prefs.isHapticEnabled) {
                        v.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                            android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                        )
                    }
                    // Immediate key event — no wait for UP
                    onKeyListener?.invoke(code, label)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    trackedPointerId = -1
                    restoreKeyBackgroundAfterPress(v, code, normalBg)
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Only clear tracking if the pointer that lifted is the one we own;
                    // an unrelated second pointer briefly grazing this view must not
                    // reset our pressed-state early.
                    if (event.getPointerId(event.actionIndex) == trackedPointerId) {
                        trackedPointerId = -1
                        restoreKeyBackgroundAfterPress(v, code, normalBg)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun stopDeleteRepeat() {
        deleteRepeating = false
        handler.removeCallbacks(deleteRepeatRunnable)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
    /**
     * Create a fresh GradientDrawable for [color]/[radiusDp].
     * IMPORTANT: never assign the same drawable instance to multiple views —
     * GradientDrawable bounds/state are view-local and sharing causes
     * collapsed space bar / wrong corners until the next press.
     */
    private fun roundedBg(color: Int, radiusDp: Float = KEY_CORNER_RADIUS_DP): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp,
                context.resources.displayMetrics
            )
        }
    }

    private fun applyRoundedBg(view: View, color: Int, radiusDp: Float = KEY_CORNER_RADIUS_DP) {
        // Always new instance (or mutate of template) so bounds stay per-view
        view.background = roundedBg(color, radiusDp)
    }

    /**
     * After a key press, restore the correct resting background.
     * Shift is special: if caps-lock / manual-shift is active, keep the primary
     * highlight — otherwise ACTION_UP would wipe the indicator every tap.
     */
    private fun restoreKeyBackgroundAfterPress(view: View, code: Int, normalBg: Int) {
        if (code == KEYCODE_SHIFT && currentShiftHighlight) {
            applyRoundedBg(view, colorPrimary)
            (view as? TextView)?.setTextColor(0xFFFFFFFF.toInt())
        } else if (code == KEYCODE_SHIFT) {
            applyRoundedBg(view, colorKeySpecialBg)
            (view as? TextView)?.setTextColor(colorKeySpecialText)
        } else {
            applyRoundedBg(view, normalBg)
        }
    }
}
