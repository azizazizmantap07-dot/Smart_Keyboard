package com.smartkeyboard.ime.service

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.smartkeyboard.ime.R
import com.smartkeyboard.ime.keyboard.KeyboardLayoutManager
import com.smartkeyboard.ime.keyboard.KeyboardMode
import com.smartkeyboard.ime.keyboard.FieldKind
import com.smartkeyboard.ime.translation.TranslationEngine
import com.smartkeyboard.ime.clipboard.ClipboardManagerWrapper
import com.smartkeyboard.ime.emoji.EmojiPanelController
import com.smartkeyboard.ime.utils.PreferencesHelper
import com.smartkeyboard.ime.autocorrect.AutoCorrectEngine
import kotlinx.coroutines.*

/**
 * Core Input Method Service.
 *
 * This is the heart of the keyboard. It handles:
 * - Keyboard rendering & layout switching
 * - Key events and text commitment
 * - Hybrid auto-correct + word suggestions (Trie / context / personal / ONNX)
 * - Real-time translation (ID → target language)
 * - Clipboard history
 * - Emoji panel
 *
 * Designed for high responsiveness and low memory footprint.
 * Capitalization is only applied at the start of the field or after a newline.
 */
class SmartInputMethodService : InputMethodService() {

    // Managers
    private lateinit var layoutManager: KeyboardLayoutManager
    private lateinit var translationEngine: TranslationEngine
    private lateinit var clipboardWrapper: ClipboardManagerWrapper
    private lateinit var emojiController: EmojiPanelController
    private lateinit var prefs: PreferencesHelper
    private lateinit var autoCorrectEngine: AutoCorrectEngine

    /** Current word being typed (for auto-correct). */
    private val activeWord = StringBuilder()
    private var justAutoCorrected = false

    // UI
    private var keyboardView: View? = null
    private var currentMode: KeyboardMode = KeyboardMode.LETTERS

    // State
    private var isShifted = false
    private var isCapsLock = false
    private var composingText = StringBuilder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Vibration & sound
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null

    // Editor state for action / field-type handling
    private var currentEditorInfo: EditorInfo? = null
    private var currentImeAction = EditorInfo.IME_ACTION_NONE
    private var fieldKind: FieldKind = FieldKind.TEXT

    companion object {
        private const val TAG = "SmartIME"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesHelper(this)
        applyAppNightMode(prefs.themeMode)
        translationEngine = TranslationEngine(this)
        clipboardWrapper = ClipboardManagerWrapper(this)
        emojiController = EmojiPanelController(this)
        layoutManager = KeyboardLayoutManager(this)
        autoCorrectEngine = AutoCorrectEngine(this)
        serviceScope.launch(Dispatchers.Default) {
            try { autoCorrectEngine.initialize() } catch (e: Exception) {
                Log.w(TAG, "AutoCorrect init failed", e)
            }
        }

        initVibrator()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        registerClipboardListener()
    }

    override fun onCreateInputView(): View {
        keyboardView = layoutManager.getOrCreateKeyboardView(
            mode = currentMode,
            onKeyListener = ::handleKey,
            onModeChange = ::switchMode,
            onSuggestionSelected = ::onSuggestionPicked,
            initialShifted = isShifted || isCapsLock,
            forceRebuild = true
        )
        return keyboardView!!
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        composingText.clear()
        activeWord.clear()
        justAutoCorrected = false
        autoCorrectEngine.predictionJob?.cancel()
        autoCorrectEngine.clearLastCorrection()
        layoutManager.clearSuggestions()
        // Soft capture (won't re-add items user deleted from history)
        if (prefs.isClipboardEnabled) {
            try {
                clipboardWrapper.capturePrimaryClip(fromUserCopy = false)
            } catch (e: Exception) {
                Log.w(TAG, "clipboard capture failed", e)
            }
        }
        // Auto-capitalize only at start of empty field; otherwise start lowercase
        // (unless caps lock was left on — rare across field switches)
        if (shouldAutoCapitalize(currentInputConnection)) {
            isShifted = true
            isCapsLock = false
        } else if (!isCapsLock) {
            isShifted = false
        }

        fieldKind = classifyField(info)
        currentImeAction = extractImeAction(info)
        // Apply field kind / action BEFORE switchMode so cache fingerprint & layout match
        layoutManager.setFieldKind(fieldKind)
        layoutManager.updateEnterKeyForAction(currentImeAction)

        // Choose initial mode from field type
        val targetMode = when (fieldKind) {
            FieldKind.NUMBER, FieldKind.PHONE, FieldKind.DATETIME -> KeyboardMode.NUMBERS
            else -> KeyboardMode.LETTERS
        }
        currentMode = targetMode
        switchMode(targetMode)
        layoutManager.updateShiftState(isShifted || isCapsLock)
    }

    private fun classifyField(info: EditorInfo?): FieldKind {
        if (info == null) return FieldKind.TEXT
        val cls = info.inputType and EditorInfo.TYPE_MASK_CLASS
        val variation = info.inputType and EditorInfo.TYPE_MASK_VARIATION
        return when (cls) {
            EditorInfo.TYPE_CLASS_NUMBER -> FieldKind.NUMBER
            EditorInfo.TYPE_CLASS_PHONE -> FieldKind.PHONE
            EditorInfo.TYPE_CLASS_DATETIME -> FieldKind.DATETIME
            EditorInfo.TYPE_CLASS_TEXT -> when (variation) {
                EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                EditorInfo.TYPE_TEXT_VARIATION_URI -> FieldKind.URI
                EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.PASSWORD
                else -> FieldKind.TEXT
            }
            else -> FieldKind.TEXT
        }
    }

    private fun extractImeAction(info: EditorInfo?): Int {
        if (info == null) return EditorInfo.IME_ACTION_NONE
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return if (action == EditorInfo.IME_ACTION_UNSPECIFIED) EditorInfo.IME_ACTION_NONE else action
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Orientation / night mode / density change → invalidate cache & rebuild
        layoutManager.invalidateCache()
        if (keyboardView != null) {
            keyboardView = layoutManager.getOrCreateKeyboardView(
                mode = currentMode,
                onKeyListener = ::handleKey,
                onModeChange = ::switchMode,
                onSuggestionSelected = ::onSuggestionPicked,
                initialShifted = isShifted || isCapsLock,
                forceRebuild = true
            )
            setInputView(keyboardView)
            layoutManager.updateEnterKeyForAction(currentImeAction)
            layoutManager.setFieldKind(fieldKind)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        activeWord.clear()
        justAutoCorrected = false
        autoCorrectEngine.predictionJob?.cancel()
        autoCorrectEngine.clearContext()
        composingText.clear()
        // Next open should start from letters, not symbols/emoji
        currentMode = KeyboardMode.LETTERS
    }

    /**
     * When the user taps elsewhere in the text field (mid-sentence edit),
     * drop any active composing region so the caret does not jump/flicker.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd
        )
        // Cursor moved outside the composing span (or composing was cleared by host)
        val composingActive = candidatesStart >= 0 && candidatesEnd >= 0
        val cursorInsideComposing = composingActive &&
            newSelStart >= candidatesStart && newSelEnd <= candidatesEnd

        if (composingText.isNotEmpty() && !cursorInsideComposing) {
            // User relocated caret — commit/discard composing so host owns the text
            val ic = currentInputConnection
            if (ic != null) {
                try {
                    ic.finishComposingText()
                } catch (e: Exception) {
                    Log.w(TAG, "finishComposingText on selection change failed", e)
                }
            }
            composingText.clear()
            layoutManager.clearSuggestions()
            if (prefs.isTranslationModeEnabled) {
                layoutManager.updateTranslationTexts("", "")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        translationEngine.destroy()
        unregisterClipboardListener()
    }

    // -------------------------------------------------------------------------
    // Key handling
    // -------------------------------------------------------------------------

    private fun handleKey(primaryCode: Int, keyLabel: String?) {
        val ic: InputConnection = currentInputConnection ?: return

        // Sound (haptic already fired on ACTION_DOWN in layout)
        playKeyClickSound(primaryCode)

        when (primaryCode) {
            KeyboardLayoutManager.KEYCODE_SHIFT -> toggleShift()
            KeyboardLayoutManager.KEYCODE_DELETE -> handleDelete(ic)
            KeyboardLayoutManager.KEYCODE_ENTER -> handleEnter(ic)
            KeyboardLayoutManager.KEYCODE_SPACE -> handleSpace(ic)
            KeyboardLayoutManager.KEYCODE_SYMBOLS -> switchMode(KeyboardMode.SYMBOLS)
            KeyboardLayoutManager.KEYCODE_ABC -> switchMode(KeyboardMode.LETTERS)
            KeyboardLayoutManager.KEYCODE_EMOJI -> showEmojiPanel()
            KeyboardLayoutManager.KEYCODE_CLIPBOARD -> showClipboardPanel()
            KeyboardLayoutManager.KEYCODE_TRANSLATE -> toggleTranslationMode()
            KeyboardLayoutManager.KEYCODE_NUMBERS -> switchMode(KeyboardMode.NUMBERS)
            else -> {
                val raw = keyLabel ?: primaryCode.toChar().toString()
                // No auto-space after punctuation — commit character as-is
                val autoCap = !isCapsLock && shouldAutoCapitalize(ic) &&
                    (raw.firstOrNull()?.isLetter() == true)
                if (autoCap && !isShifted) {
                    // Sync visual shift so labels show capitals before key fires
                    isShifted = true
                    layoutManager.updateShiftState(true)
                }
                val ch = if (isShifted || isCapsLock || autoCap) raw.uppercase() else raw.lowercase()
                commitCharacter(ic, ch)
                if (isShifted && !isCapsLock) {
                    isShifted = false
                    layoutManager.updateShiftState(false)
                }
            }
        }
    }

    private fun commitCharacter(ic: InputConnection, char: String) {
        if (prefs.isTranslationModeEnabled) {
            // Translation mode (Gboard-style):
            // composingText = Indonesian source; real field gets English
            composingText.append(char)
            val source = composingText.toString()
            serviceScope.launch {
                val translated = translationEngine.translateText(source) ?: source
                withContext(Dispatchers.Main) {
                    if (composingText.toString() != source) return@withContext
                    // Put English into the actual app text field as composing text
                    ic.setComposingText(translated, 1)
                    layoutManager.updateTranslationTexts(source, translated)
                }
            }
            return
        }

        // Normal mode: commit immediately — track active word for auto-correct.
        if (composingText.isNotEmpty()) {
            try { ic.finishComposingText() } catch (_: Exception) { }
            composingText.clear()
        }
        ic.commitText(char, 1)
        justAutoCorrected = false
        if (char.length == 1 && char[0].isLetter()) {
            activeWord.append(char)
            scheduleSuggestions()
        } else if (char.isNotEmpty() && !char[0].isLetter()) {
            activeWord.clear()
            layoutManager.clearSuggestions()
            autoCorrectEngine.predictionJob?.cancel()
        }
    }

    private fun scheduleSuggestions() {
        if (!prefs.isAutoCorrectEnabled || prefs.isTranslationModeEnabled) return
        autoCorrectEngine.predictionJob?.cancel()
        val partial = activeWord.toString()
        autoCorrectEngine.predictionJob = serviceScope.launch {
            val suggestions = try {
                autoCorrectEngine.getSuggestions(partial, limit = 3)
            } catch (e: Exception) {
                Log.w(TAG, "suggestions failed", e)
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (activeWord.toString() != partial) return@withContext
                val words = suggestions.map { it.word }
                val primaryIdx = suggestions.indexOfFirst { it.isPrimary }.takeIf { it >= 0 } ?: 1
                layoutManager.updateSuggestions(words, primaryIndex = primaryIdx)
            }
        }
    }

    private fun handleSpace(ic: InputConnection) {
        if (prefs.isTranslationModeEnabled) {
            // Keep accumulating Indonesian source; live-update English in the field
            if (composingText.isNotEmpty()) {
                composingText.append(' ')
                val source = composingText.toString()
                serviceScope.launch {
                    val translated = translationEngine.translateText(source.trim()) ?: source
                    withContext(Dispatchers.Main) {
                        if (composingText.toString() != source) return@withContext
                        ic.setComposingText("$translated ", 1)
                        layoutManager.updateTranslationTexts(source, "$translated ")
                    }
                }
            } else {
                ic.commitText(" ", 1)
            }
            return
        }

        // Normal mode — space triggers auto-correct then commits space
        if (composingText.isNotEmpty()) {
            try { ic.finishComposingText() } catch (_: Exception) { }
            composingText.clear()
        }
        autoCorrectEngine.predictionJob?.cancel()
        layoutManager.clearSuggestions()

        val word = activeWord.toString()
        activeWord.clear()

        // Always commit space immediately for fluid typing
        ic.commitText(" ", 1)
        justAutoCorrected = false

        if (prefs.isAutoCorrectEnabled && word.length >= 2) {
            serviceScope.launch {
                val corrected = try {
                    autoCorrectEngine.correctIfNeeded(word)
                } catch (e: Exception) {
                    Log.w(TAG, "correct failed", e)
                    null
                }
                if (corrected != null && corrected != word) {
                    withContext(Dispatchers.Main) {
                        val conn = currentInputConnection ?: return@withContext
                        try {
                            conn.beginBatchEdit()
                            // Delete typed word + trailing space, insert correction + space
                            for (i in 0 until word.length + 1) {
                                conn.deleteSurroundingText(1, 0)
                            }
                            conn.commitText("$corrected ", 1)
                            conn.endBatchEdit()
                            justAutoCorrected = true
                        } catch (e: Exception) {
                            Log.w(TAG, "apply correction failed", e)
                        }
                    }
                }
            }
        }
    }

    private fun handleDelete(ic: InputConnection) {
        // 1) Selected text in the target field → replace selection with empty
        try {
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                composingText.clear()
                ic.commitText("", 1)
                layoutManager.clearSuggestions()
                if (prefs.isTranslationModeEnabled) {
                    layoutManager.updateTranslationTexts("", "")
                }
                return
            }
        } catch (e: Exception) { Log.w(TAG, "getSelectedText failed", e) }

        // 2) Composing buffer (translation mode only)
        if (composingText.isNotEmpty() && prefs.isTranslationModeEnabled) {
            composingText.deleteCharAt(composingText.length - 1)
            if (composingText.isEmpty()) {
                try { ic.finishComposingText() } catch (_: Exception) { }
                layoutManager.clearSuggestions()
                layoutManager.updateTranslationTexts("", "")
            } else {
                val source = composingText.toString()
                serviceScope.launch {
                    val translated = translationEngine.translateText(source) ?: source
                    withContext(Dispatchers.Main) {
                        if (composingText.toString() != source) return@withContext
                        ic.setComposingText(translated, 1)
                        layoutManager.updateTranslationTexts(source, translated)
                    }
                }
            }
            return
        }

        // Stale non-translation composing buffer — finish and fall through to normal delete
        if (composingText.isNotEmpty()) {
            try { ic.finishComposingText() } catch (_: Exception) { }
            composingText.clear()
            layoutManager.clearSuggestions()
        }

        // Undo auto-correct: first backspace after correction restores original word
        if (justAutoCorrected) {
            val rec = autoCorrectEngine.lastCorrection
            if (rec != null) {
                try {
                    ic.beginBatchEdit()
                    // Delete corrected word + space
                    for (i in 0 until rec.corrected.length + 1) {
                        ic.deleteSurroundingText(1, 0)
                    }
                    ic.commitText(rec.original, 1)
                    ic.endBatchEdit()
                    activeWord.clear()
                    activeWord.append(rec.original)
                    justAutoCorrected = false
                    serviceScope.launch {
                        autoCorrectEngine.onCorrectionUndone(rec.original)
                    }
                    scheduleSuggestions()
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "undo correction failed", e)
                }
            }
            justAutoCorrected = false
        }

        // Track active word for suggestions
        if (activeWord.isNotEmpty()) {
            activeWord.deleteCharAt(activeWord.length - 1)
            if (activeWord.isEmpty()) {
                layoutManager.clearSuggestions()
            } else {
                scheduleSuggestions()
            }
        } else {
            layoutManager.clearSuggestions()
        }

        // 3) Normal backspace — surrounding text, with key-event fallback
        val deleted = try {
            ic.deleteSurroundingText(1, 0)
        } catch (e: Exception) {
            Log.w(TAG, "deleteSurroundingText failed", e)
            false
        }
        if (!deleted) {
            try {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            } catch (e: Exception) {
                Log.w(TAG, "sendKeyEvent DEL failed", e)
            }
        }
    }

    private fun handleEnter(ic: InputConnection) {
        // Finish any composing text first
        if (composingText.isNotEmpty()) {
            if (prefs.isTranslationModeEnabled) {
                val source = composingText.toString()
                serviceScope.launch {
                    val translated = translationEngine.translateText(source) ?: source
                    withContext(Dispatchers.Main) {
                        ic.commitText(translated, 1)
                        composingText.clear()
                        layoutManager.updateTranslationTexts("", "")
                        layoutManager.clearSuggestions()
                        performEditorActionOrEnter(ic)
                    }
                }
                return
            } else {
                ic.commitText(composingText.toString(), 1)
                composingText.clear()
                layoutManager.clearSuggestions()
            }
        }
        performEditorActionOrEnter(ic)
    }

    /**
     * Honor EditorInfo.imeOptions: Search / Go / Send / Next / Done / etc.
     * Falls back to KEYCODE_ENTER (newline) when action is NONE or NO_ENTER_ACTION.
     */
    private fun performEditorActionOrEnter(ic: InputConnection) {
        val action = currentImeAction
        val noEnter = (currentEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noEnter && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            val performed = try {
                ic.performEditorAction(action)
            } catch (e: Exception) {
                Log.w(TAG, "performEditorAction failed action=$action", e)
                false
            }
            if (performed) {
                // Many apps dismiss the field; still prepare capitalization for next open
                if (!isCapsLock) {
                    isShifted = true
                    layoutManager.updateShiftState(true)
                }
                return
            }
        }
        // Fallback: send Enter key events (newline / chat send on some apps)
        try {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        } catch (e: Exception) {
            Log.w(TAG, "sendKeyEvent ENTER failed", e)
        }
        if (!isCapsLock) {
            isShifted = true
            layoutManager.updateShiftState(true)
        }
    }

    // -------------------------------------------------------------------------
    // Capitalization: only at start of field or after newline
    // -------------------------------------------------------------------------

    /**
     * True when cursor is at start of field, after newline, or only whitespace.
     * Does NOT auto-capitalize after period / sentence-ending punctuation.
     */
    private fun shouldAutoCapitalize(ic: InputConnection?): Boolean {
        if (ic == null) return true
        if (isCapsLock || isShifted) return true
        if (composingText.isNotEmpty()) return false
        val before = ic.getTextBeforeCursor(4, 0)?.toString() ?: return true
        if (before.isEmpty()) return true
        if (before.last() == '\n') return true
        // Only whitespace before cursor
        if (before.all { it.isWhitespace() }) return true
        return false
    }

    private fun toggleShift() {
        if (isShifted) {
            isCapsLock = !isCapsLock
            isShifted = isCapsLock
        } else {
            isShifted = true
        }
        layoutManager.updateShiftState(isShifted)
    }

    // -------------------------------------------------------------------------
    // Mode switching
    // -------------------------------------------------------------------------

    private fun applyAppNightMode(mode: String) {
        when (mode) {
            PreferencesHelper.THEME_LIGHT ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            PreferencesHelper.THEME_DARK ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun switchMode(mode: KeyboardMode) {
        currentMode = mode
        keyboardView = layoutManager.getOrCreateKeyboardView(
            mode = mode,
            onKeyListener = ::handleKey,
            onModeChange = ::switchMode,
            onSuggestionSelected = ::onSuggestionPicked,
            initialShifted = isShifted || isCapsLock,
            forceRebuild = false
        )
        setInputView(keyboardView)
        layoutManager.updateEnterKeyForAction(currentImeAction)
        layoutManager.setFieldKind(fieldKind)
        // Restore translation dual-bar if mode is active
        if (prefs.isTranslationModeEnabled) {
            val source = composingText.toString()
            if (source.isNotEmpty()) {
                serviceScope.launch {
                    val translated = translationEngine.translateText(source) ?: source
                    withContext(Dispatchers.Main) {
                        layoutManager.showTranslationModeBar(source, translated)
                    }
                }
            } else {
                layoutManager.showTranslationModeBar("", "")
            }
        }
    }

    /**
     * User tapped a suggestion chip (auto-correct / next-word or translation).
     */
    private fun onSuggestionPicked(word: String, isTranslation: Boolean) {
        val ic = currentInputConnection ?: return
        performHapticFeedback()

        // Replace active word with suggestion
        val typed = activeWord.toString()
        try {
            ic.beginBatchEdit()
            if (typed.isNotEmpty()) {
                for (i in typed.indices) {
                    ic.deleteSurroundingText(1, 0)
                }
            }
            ic.commitText("$word ", 1)
            ic.endBatchEdit()
        } catch (e: Exception) {
            Log.w(TAG, "suggestion pick failed", e)
            ic.commitText("$word ", 1)
        }
        composingText.clear()
        activeWord.clear()
        justAutoCorrected = false
        layoutManager.clearSuggestions()
        autoCorrectEngine.predictionJob?.cancel()

        if (!isTranslation) {
            serviceScope.launch {
                try { autoCorrectEngine.onSuggestionAccepted(word) } catch (_: Exception) {}
            }
        }

        if (isShifted && !isCapsLock) {
            isShifted = false
            layoutManager.updateShiftState(false)
        }
    }

    // -------------------------------------------------------------------------
    // Feature panels
    // -------------------------------------------------------------------------

    private fun keyboardBottomOffsetPx(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            prefs.keyboardBottomOffsetDp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun showEmojiPanel() {
        val c = layoutManager.currentThemeColors()
        val emojiView = emojiController.createEmojiPanel(
            onEmojiSelected = { emoji ->
                currentInputConnection?.commitText(emoji, 1)
            },
            onBack = { switchMode(KeyboardMode.LETTERS) },
            onDelete = {
                val ic = currentInputConnection ?: return@createEmojiPanel
                handleDelete(ic)
            },
            colors = com.smartkeyboard.ime.emoji.EmojiPanelController.PanelColors(
                bg = c.bg,
                surface = c.specialBg,
                text = c.text,
                muted = c.specialText,
                primary = c.primary
            ),
            bottomOffsetPx = keyboardBottomOffsetPx()
        )
        setInputView(emojiView)
    }

    private fun showClipboardPanel() {
        val colors = layoutManager.currentThemeColors()
        val clipView = clipboardWrapper.createClipboardPanel(
            onPaste = { text ->
                currentInputConnection?.commitText(text, 1)
            },
            onBack = { switchMode(KeyboardMode.LETTERS) },
            colorBg = colors.bg,
            colorChip = colors.specialBg,
            colorText = colors.text,
            colorMuted = colors.specialText,
            colorAccent = colors.primary,
            bottomOffsetPx = keyboardBottomOffsetPx()
        )
        setInputView(clipView)
    }


    private fun toggleTranslationMode() {
        val turningOn = !prefs.isTranslationModeEnabled
        if (turningOn) {
            // Show in-keyboard language panel (reliable inside IME)
            showLanguagePickerPanel()
        } else {
            prefs.isTranslationModeEnabled = false
            switchMode(currentMode)
            layoutManager.updateTranslationIndicator(false)
            val ic = currentInputConnection
            if (composingText.isNotEmpty() && ic != null) {
                val source = composingText.toString()
                serviceScope.launch {
                    val translated = translationEngine.translateText(source) ?: source
                    withContext(Dispatchers.Main) {
                        ic.commitText(translated, 1)
                        composingText.clear()
                        layoutManager.hideTranslationModeBar()
                        layoutManager.clearSuggestions()
                    }
                }
            } else {
                layoutManager.hideTranslationModeBar()
            }
            Toast.makeText(this, "Mode terjemahan OFF", Toast.LENGTH_SHORT).show()
        }
    }

    /** In-keyboard Material 3 language panel (theme-aware: Light / Dark / Monet). */
    private fun showLanguagePickerPanel() {
        val items = TranslationEngine.SUPPORTED_TARGETS.map { lang ->
            Triple(lang.code, lang.labelId, translationEngine.isLanguageDownloaded(lang.code))
        }
        val panel = layoutManager.createLanguagePickerView(
            items = items,
            currentCode = prefs.translationTargetLang,
            onLanguageSelected = { code ->
                translationEngine.setTargetLanguage(code)
                prefs.isTranslationModeEnabled = true
                switchMode(currentMode)
                layoutManager.updateTranslationIndicator(true)
                enableTranslationSession()
                val label = TranslationEngine.labelFor(code)
                Toast.makeText(
                    this,
                    "Terjemahan ON — ID → $label",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onNotDownloaded = { label ->
                Toast.makeText(
                    this,
                    "$label belum diunduh. Unduh dulu di menu aplikasi.",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onClose = {
                switchMode(currentMode)
            }
        )
        setInputView(panel)
    }

    private fun enableTranslationSession() {
        val ic = currentInputConnection
        if (composingText.isNotEmpty() && ic != null) {
            val source = composingText.toString()
            serviceScope.launch {
                val translated = translationEngine.translateText(source) ?: source
                withContext(Dispatchers.Main) {
                    ic.setComposingText(translated, 1)
                    layoutManager.showTranslationModeBar(source, translated)
                }
            }
        } else {
            layoutManager.showTranslationModeBar("", "")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun playKeyClickSound(code: Int) {
        if (!prefs.isSoundEnabled) return
        val am = audioManager ?: return
        val effect = when (code) {
            KeyboardLayoutManager.KEYCODE_DELETE -> AudioManager.FX_KEYPRESS_DELETE
            KeyboardLayoutManager.KEYCODE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyboardLayoutManager.KEYCODE_ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        try {
            am.playSoundEffect(effect, 1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "playSoundEffect failed", e)
        }
    }

    private fun performHapticFeedback() {
        if (!prefs.isHapticEnabled) return
        // Prefer system keyboard-tap haptic (lowest latency); fallback to short vibrate
        val root = window?.window?.decorView
        if (root != null && root.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        ) {
            return
        }
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(8)
            }
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!prefs.isClipboardEnabled) return@OnPrimaryClipChangedListener
        try {
            clipboardWrapper.capturePrimaryClip(fromUserCopy = true)
        } catch (e: Exception) {
            Log.w(TAG, "primary clip listener failed", e)
        }
    }

    private fun registerClipboardListener() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.addPrimaryClipChangedListener(primaryClipListener)
            // Capture whatever is already on the clipboard when IME starts
            if (prefs.isClipboardEnabled) {
                clipboardWrapper.capturePrimaryClip()
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerClipboardListener failed", e)
        }
    }

    private fun unregisterClipboardListener() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.removePrimaryClipChangedListener(primaryClipListener)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterClipboardListener failed", e)
        }
    }
}
