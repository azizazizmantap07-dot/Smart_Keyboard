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
 * - Hybrid auto-correct (SymSpell C++ + Personal Dict + optional TFLite LSTM)
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
    /**
     * Bumped on every edit that changes cursor position (character, space, delete,
     * enter, suggestion pick, ...). A pending async auto-correct from [handleSpace]
     * captures the generation at launch time and only applies its edit if the
     * generation is unchanged when it completes — otherwise the user has since typed
     * more text and the correction's deleteSurroundingText would land on the wrong
     * place and corrupt whatever was typed after the corrected word.
     */
    private var editGeneration = 0

    // UI
    private var keyboardView: View? = null
    private var currentMode: KeyboardMode = KeyboardMode.LETTERS

    // State
    private var isShifted = false
    private var isCapsLock = false
    /**
     * True when [isShifted] was armed by auto-capitalize (sentence start), not by the
     * user tapping ⇧. While true the shift key is NOT highlighted — only letter labels
     * go uppercase — so manual shift / caps-lock stay visually distinct.
     */
    private var shiftByAutoCap = false
    private var lastShiftTapTime = 0L
    /** Reset on each onStartInputView; used by shouldAutoCapitalize's TYPE_NULL/no-context fallback. */
    private var hasTypedSinceFieldStart = false
    /**
     * True right after auto-space was inserted following punctuation.
     * First backspace undoes that space only (punctuation stays).
     */
    private var justAutoSpaced = false
    private var composingText = StringBuilder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** Latest translation request; cancelled on each new keystroke so results stay in order. */
    private var translationJob: Job? = null
    /**
     * Last English text we pushed via [InputConnection.setComposingText] in translation mode.
     * When the host finishes the composing span (common after punctuation — the underline
     * disappears), that text becomes regular committed text. We must delete it before
     * re-applying setComposingText, otherwise the field duplicates and the preview looks stuck.
     */
    private var lastTranslatedField: String = ""
    /** True while we believe the host still has our composing span active (underline visible). */
    private var translationComposingAlive: Boolean = false

    // Vibration & sound
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null

    // Editor state for action / field-type handling
    private var currentEditorInfo: EditorInfo? = null
    private var currentImeAction = EditorInfo.IME_ACTION_NONE
    private var fieldKind: FieldKind = FieldKind.TEXT

    companion object {
        private const val TAG = "SmartIME"
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        /** Characters that end a word and should trigger auto-correct like space. */
        private val WORD_END_PUNCT = setOf('.', ',', '!', '?', ':', ';', '…')
        /** Punctuation that gets an automatic trailing space when the feature is on. */
        private val AUTO_SPACE_PUNCT = setOf('.', ',', '!', '?', ':', ';')
        /** Sentence terminators that arm auto-capitalize for the next letter. */
        private val SENTENCE_END_PUNCT = setOf('.', '!', '?')
    }

    /** Letter keys uppercase + optional ⇧ highlight (manual / caps only). */
    private fun refreshShiftUi() {
        val lettersUpper = isShifted || isCapsLock
        val highlight = isCapsLock || (isShifted && !shiftByAutoCap)
        layoutManager.updateShiftState(lettersUpper, highlightKey = highlight)
    }

    /** Arm one-shot shift for the next letter via auto-capitalize (no ⇧ highlight). */
    private fun armAutoCap() {
        if (!prefs.isAutoCapitalizeEnabled) return
        if (isCapsLock) return
        if (fieldKind == FieldKind.PASSWORD) return
        isShifted = true
        shiftByAutoCap = true
        refreshShiftUi()
    }

    private fun clearOneShotShift() {
        if (isShifted && !isCapsLock) {
            isShifted = false
            shiftByAutoCap = false
            refreshShiftUi()
        }
    }

    /**
     * Remove whatever the previous translation left in the field (composing or committed).
     * Tries exact match and common trailing-space variants because hosts often rewrite
     * the text when finishing the composing span on punctuation.
     */
    private fun removePreviousTranslation(ic: InputConnection) {
        if (lastTranslatedField.isEmpty()) {
            try { ic.finishComposingText() } catch (_: Exception) { }
            return
        }
        // Finish any live composing first so the text becomes regular content we can delete
        try { ic.finishComposingText() } catch (_: Exception) { }
        val candidates = linkedSetOf(
            lastTranslatedField,
            lastTranslatedField.trimEnd(),
            lastTranslatedField.trimEnd() + " ",
            lastTranslatedField.trimStart(),
        ).filter { it.isNotEmpty() }
        for (candidate in candidates) {
            val before = try {
                ic.getTextBeforeCursor(candidate.length, 0)?.toString()
            } catch (_: Exception) {
                null
            }
            if (before == candidate) {
                try {
                    ic.deleteSurroundingText(candidate.length, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "delete previous translation failed", e)
                }
                return
            }
        }
    }

    /**
     * Push [translated] into the target field as composing text, and update the preview bar.
     *
     * Host apps often finish the composing span when the user types punctuation
     * (underline disappears, text becomes committed). Without re-sync, the next
     * setComposingText inserts a second copy → field/preview appear stuck.
     *
     * Strategy:
     *  1. If composing was killed by the host, delete [lastTranslatedField] from the
     *     field when it still matches the text before the cursor.
     *  2. setComposingText with the new full translation (underline returns).
     *  3. Always refresh the dual-language preview.
     */
    private fun applyTranslatedToField(ic: InputConnection, source: String, translated: String) {
        try {
            ic.beginBatchEdit()
            // ALWAYS strip the previous translation first — whether the host still has
            // our composing span or already finished it after punctuation. This is what
            // stops the field/preview from going stale or duplicating.
            removePreviousTranslation(ic)
            if (translated.isEmpty()) {
                lastTranslatedField = ""
                translationComposingAlive = false
            } else {
                ic.setComposingText(translated, 1)
                lastTranslatedField = translated
                translationComposingAlive = true
            }
            ic.endBatchEdit()
        } catch (e: Exception) {
            Log.w(TAG, "applyTranslatedToField failed", e)
            try {
                removePreviousTranslation(ic)
                if (translated.isNotEmpty()) {
                    ic.setComposingText(translated, 1)
                    lastTranslatedField = translated
                    translationComposingAlive = true
                } else {
                    lastTranslatedField = ""
                    translationComposingAlive = false
                }
            } catch (e2: Exception) {
                Log.w(TAG, "setComposingText fallback failed", e2)
            }
        }
        // Preview update is independent of field success — never skip
        layoutManager.updateTranslationTexts(source, translated)
    }

    /**
     * Commit the current translation segment into the field (if any) and clear
     * session tracking so a non-translated insertion (emoji, clipboard paste)
     * does not desync Indonesian source vs English field.
     */
    private fun commitTranslationSegmentIfNeeded(ic: InputConnection?) {
        if (!prefs.isTranslationModeEnabled) return
        if (ic == null) return
        translationJob?.cancel()
        translationJob = null
        try {
            if (translationComposingAlive) {
                ic.finishComposingText()
            }
        } catch (e: Exception) {
            Log.w(TAG, "commitTranslationSegment failed", e)
        }
        composingText.clear()
        lastTranslatedField = ""
        translationComposingAlive = false
        justAutoSpaced = false
        layoutManager.updateTranslationTexts("", "")
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
        // Fresh session — discard any leftover translation source/preview from last field
        translationJob?.cancel()
        translationJob = null
        composingText.clear()
        lastTranslatedField = ""
        translationComposingAlive = false
        activeWord.clear()
        justAutoCorrected = false
        justAutoSpaced = false
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
        hasTypedSinceFieldStart = false
        currentEditorInfo = info
        fieldKind = classifyField(info)
        currentImeAction = extractImeAction(info)

        // Seed shift from auto-cap (field start / after newline) when enabled
        if (prefs.isAutoCapitalizeEnabled && shouldAutoCapitalize(currentInputConnection)) {
            isShifted = true
            isCapsLock = false
            shiftByAutoCap = true
        } else if (!isCapsLock) {
            isShifted = false
            shiftByAutoCap = false
        }
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
        refreshShiftUi()

        // Translation mode should already be OFF after previous close. If still on
        // (e.g. toggled from Settings while closed), show a clean empty preview.
        if (prefs.isTranslationModeEnabled) {
            layoutManager.updateTranslationIndicator(true)
            layoutManager.showTranslationModeBar("", "")
        } else {
            layoutManager.hideTranslationModeBar()
            layoutManager.updateTranslationIndicator(false)
        }
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
        cleanupInputSession(commitTranslation = true)
        // Next open should start from letters, not symbols/emoji
        currentMode = KeyboardMode.LETTERS
    }

    /**
     * Field target gone (app closed the editor). Mirror view cleanup so no
     * stale composing/translation state leaks into the next field.
     */
    override fun onFinishInput() {
        super.onFinishInput()
        cleanupInputSession(commitTranslation = true)
        currentMode = KeyboardMode.LETTERS
    }

    /**
     * Shared teardown for [onFinishInputView] / [onFinishInput]:
     * cancel async work, commit live translation, return to plain Indonesian.
     */
    private fun cleanupInputSession(commitTranslation: Boolean) {
        editGeneration++
        activeWord.clear()
        justAutoCorrected = false
        justAutoSpaced = false
        autoCorrectEngine.predictionJob?.cancel()
        autoCorrectEngine.clearContext()
        translationJob?.cancel()
        translationJob = null

        if (prefs.isTranslationModeEnabled) {
            val ic = currentInputConnection
            if (commitTranslation && ic != null) {
                try {
                    if (translationComposingAlive) {
                        ic.finishComposingText()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "finishComposing on session end failed", e)
                }
            }
            prefs.isTranslationModeEnabled = false
            layoutManager.hideTranslationModeBar()
            layoutManager.updateTranslationIndicator(false)
            layoutManager.invalidateCache()
        }
        composingText.clear()
        lastTranslatedField = ""
        translationComposingAlive = false
        shiftByAutoCap = false
        if (!isCapsLock) {
            isShifted = false
        }
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

        if (prefs.isTranslationModeEnabled && composingText.isNotEmpty()) {
            if (composingActive && cursorInsideComposing) {
                // Host still has our underline — composing is alive
                translationComposingAlive = true
            } else if (!cursorInsideComposing) {
                // Host finished composing (typical after . , ! ? ; :) OR user moved caret.
                // Mark dead so the next applyTranslatedToField re-syncs instead of duplicating.
                translationComposingAlive = false
                val jumpedAway = newSelStart != newSelEnd || run {
                    val ic = currentInputConnection
                    val beforeLen = try {
                        ic?.getTextBeforeCursor(1000, 0)?.length ?: -1
                    } catch (_: Exception) {
                        -1
                    }
                    beforeLen >= 0 && newSelStart < beforeLen
                }
                if (jumpedAway) {
                    // User truly left the end of the field — abandon translation session
                    composingText.clear()
                    lastTranslatedField = ""
                    translationComposingAlive = false
                    layoutManager.updateTranslationTexts("", "")
                }
                // else: keep source + lastTranslatedField; next keystroke re-syncs
            }
        } else if (composingText.isNotEmpty() && !cursorInsideComposing) {
            // Non-translation composing — commit/discard so host owns the text
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
        }
        // Keep activeWord only while it still matches the text immediately before
        // the caret. Our own keystrokes also fire onUpdateSelection; in that case
        // the text before the cursor still ends with activeWord so we keep it.
        // A user tap elsewhere (or selecting a range) breaks the match → reset.
        //
        // IMPORTANT: do NOT bump editGeneration on every selection callback.
        // commitText() often triggers onUpdateSelection synchronously or right
        // after handleSpace/scheduleCorrection snapshots the generation — an
        // extra bump would cancel a legitimate pending auto-correct. Only bump
        // when the user actually relocated the caret (activeWord reset).
        if (activeWord.isNotEmpty()) {
            val shouldReset = when {
                newSelStart != newSelEnd -> true // user selected a range
                else -> {
                    val ic = currentInputConnection
                    val before = try {
                        ic?.getTextBeforeCursor(activeWord.length, 0)?.toString()
                    } catch (_: Exception) {
                        null
                    }
                    before == null || !before.regionMatches(
                        0, activeWord.toString(), 0, activeWord.length, ignoreCase = true
                    )
                }
            }
            if (shouldReset) {
                activeWord.clear()
                layoutManager.clearSuggestions()
                autoCorrectEngine.predictionJob?.cancel()
                justAutoCorrected = false
                editGeneration++ // cancel pending async correction — cursor moved
            }
        } else if (newSelStart != newSelEnd || oldSelStart != newSelStart) {
            // No active word, but caret moved/selected — still cancel pending correction
            // only when the move is not a trivial +0/+1 from our own last commit.
            // Large jumps indicate a user tap.
            val jump = kotlin.math.abs(newSelStart - oldSelStart)
            if (newSelStart != newSelEnd || jump > 1) {
                justAutoCorrected = false
                editGeneration++
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        translationJob?.cancel()
        autoCorrectEngine.predictionJob?.cancel()
        autoCorrectEngine.shutdown()
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
                // isShifted/isCapsLock already reflect auto-cap (set in onStartInputView /
                // after space+newline) AND any manual toggle/cancel the user made via the
                // shift key — shouldAutoCapitalize() must NOT be re-queried here, or a
                // manual cancel gets silently overridden on the very next letter.
                // Multi-character shortcut labels (e.g. ".com") are literal snippets, not
                // single letters — only apply shift casing to genuine single-letter keys,
                // otherwise ".com" turns into ".COM" whenever shift/auto-cap is active.
                val ch = if (raw.length == 1 && (isShifted || isCapsLock)) {
                    raw.uppercase()
                } else if (raw.length == 1) {
                    raw.lowercase()
                } else {
                    raw
                }
                commitCharacter(ic, ch)
                // One-shot shift (manual or auto-cap) drops after a letter is typed
                if (raw.length == 1 && raw[0].isLetter()) {
                    clearOneShotShift()
                }
            }
        }
    }

    /** Auto-correct / suggestions are disabled for password fields and translation mode. */
    private fun shouldRunAutoCorrect(): Boolean {
        if (!prefs.isAutoCorrectEnabled) return false
        if (prefs.isTranslationModeEnabled) return false
        if (fieldKind == FieldKind.PASSWORD) return false
        return true
    }

    private fun commitCharacter(ic: InputConnection, char: String) {
        if (prefs.isTranslationModeEnabled) {
            // Translation mode (Gboard-style):
            // composingText = Indonesian source; real field gets English via composing span.
            // After punctuation many hosts kill the span (underline goes away) — we re-sync
            // via applyTranslatedToField so preview + field stay in lockstep.
            val isPunct = char.length == 1 && char[0] in WORD_END_PUNCT
            val addSpace = isPunct &&
                prefs.isAutoSpaceAfterPunctEnabled &&
                char[0] in AUTO_SPACE_PUNCT
            composingText.append(if (addSpace) "$char " else char)
            justAutoSpaced = addSpace
            val source = composingText.toString()
            translationJob?.cancel()
            translationJob = serviceScope.launch {
                val translated = translationEngine.translateText(source) ?: source.trimEnd()
                withContext(Dispatchers.Main) {
                    if (composingText.toString() != source) return@withContext
                    val fieldText = if (addSpace && !translated.endsWith(' ')) {
                        "$translated "
                    } else {
                        translated
                    }
                    applyTranslatedToField(ic, source, fieldText)
                }
            }
            return
        }

        // Normal mode: commit immediately — track active word for auto-correct.
        if (composingText.isNotEmpty()) {
            try { ic.finishComposingText() } catch (_: Exception) { }
            composingText.clear()
        }

        // Word-ending punctuation: auto-correct pending word, optional auto-space,
        // optional auto-cap for the next sentence.
        val isWordEndPunct = char.length == 1 && char[0] in WORD_END_PUNCT
        if (isWordEndPunct) {
            val word = activeWord.toString()
            activeWord.clear()
            layoutManager.clearSuggestions()
            autoCorrectEngine.predictionJob?.cancel()

            val punct = char
            val addSpace = prefs.isAutoSpaceAfterPunctEnabled &&
                punct[0] in AUTO_SPACE_PUNCT &&
                fieldKind != FieldKind.PASSWORD
            val trailing = if (addSpace) "$punct " else punct

            ic.commitText(trailing, 1)
            editGeneration++
            justAutoCorrected = false
            justAutoSpaced = addSpace
            hasTypedSinceFieldStart = true

            if (word.isNotEmpty()) {
                if (shouldRunAutoCorrect() && word.length >= 2) {
                    // Replaces (word + trailing) with (corrected + trailing)
                    scheduleCorrection(word, trailing = trailing)
                } else {
                    autoCorrectEngine.noteCommittedWord(word)
                }
            }

            // After sentence terminator + space, arm auto-cap for the next word
            if (addSpace && punct[0] in SENTENCE_END_PUNCT) {
                armAutoCap()
            }
            return
        }

        ic.commitText(char, 1)
        editGeneration++
        justAutoCorrected = false
        justAutoSpaced = false
        hasTypedSinceFieldStart = true
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
        if (!shouldRunAutoCorrect()) return
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

    /**
     * Async auto-correct for a finished word. [trailing] is the character already
     * committed after the word (space or punctuation). On success we delete
     * (word + trailing) and re-insert (corrected + trailing).
     *
     * Safety:
     *  1. [editGeneration] must be unchanged (user didn't keep typing / move caret).
     *  2. Text immediately before the cursor must still equal word+trailing
     *     (case-insensitive) — protects against host apps that rearrange text.
     */
    private fun scheduleCorrection(word: String, trailing: String) {
        val generationAtLaunch = editGeneration
        val expectedTail = word + trailing
        serviceScope.launch {
            val corrected = try {
                autoCorrectEngine.correctIfNeeded(word)
            } catch (e: Exception) {
                Log.w(TAG, "correct failed for '$word'", e)
                null
            }
            // Case-sensitive: "Syaa"→"Saya" must still apply. Skip only true no-ops.
            if (corrected.isNullOrEmpty() || corrected == word) {
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (editGeneration != generationAtLaunch) {
                    Log.d(TAG, "skip correction '$word'→'$corrected': generation changed")
                    return@withContext
                }
                val conn = currentInputConnection ?: return@withContext
                val deleteCount = expectedTail.length
                // Verify the text under the cursor is still the word we intended to fix.
                val before = try {
                    conn.getTextBeforeCursor(deleteCount, 0)?.toString()
                } catch (e: Exception) {
                    Log.w(TAG, "getTextBeforeCursor failed", e)
                    null
                }
                if (before == null || !before.regionMatches(
                        0, expectedTail, 0, expectedTail.length, ignoreCase = true
                    )
                ) {
                    Log.d(
                        TAG,
                        "skip correction '$word'→'$corrected': tail mismatch before='$before' expected='$expectedTail'"
                    )
                    return@withContext
                }
                try {
                    conn.beginBatchEdit()
                    val deleted = conn.deleteSurroundingText(deleteCount, 0)
                    if (!deleted) {
                        // Fallback: delete one char at a time
                        for (i in 0 until deleteCount) {
                            conn.deleteSurroundingText(1, 0)
                        }
                    }
                    conn.commitText("$corrected$trailing", 1)
                    conn.endBatchEdit()
                    editGeneration++
                    justAutoCorrected = trailing == " "
                    Log.i(TAG, "applied correction '$word' → '$corrected'")
                } catch (e: Exception) {
                    Log.w(TAG, "apply correction failed", e)
                }
            }
        }
    }

    private fun handleSpace(ic: InputConnection) {
        if (prefs.isTranslationModeEnabled) {
            // Keep accumulating Indonesian source; live-update English in the field
            justAutoSpaced = false
            if (composingText.isNotEmpty()) {
                composingText.append(' ')
                val source = composingText.toString()
                translationJob?.cancel()
                translationJob = serviceScope.launch {
                    val translated = translationEngine.translateText(source) ?: source.trimEnd()
                    withContext(Dispatchers.Main) {
                        if (composingText.toString() != source) return@withContext
                        val fieldText = if (translated.endsWith(' ')) translated else "$translated "
                        applyTranslatedToField(ic, source, fieldText)
                    }
                }
            } else {
                try { ic.commitText(" ", 1) } catch (_: Exception) { }
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
        editGeneration++
        justAutoCorrected = false
        justAutoSpaced = false

        if (shouldRunAutoCorrect() && word.length >= 2) {
            scheduleCorrection(word, trailing = " ")
        } else if (word.isNotEmpty()) {
            // Even when auto-correct is off / password / short word, feed the
            // context model so next-word suggestions stay useful.
            autoCorrectEngine.noteCommittedWord(word)
        }

        // Space after a sentence terminator → arm auto-cap for the next letter
        if (prefs.isAutoCapitalizeEnabled && fieldKind != FieldKind.PASSWORD) {
            val before = try {
                ic.getTextBeforeCursor(3, 0)?.toString()
            } catch (_: Exception) {
                null
            }
            // before ends with ". " / "! " / "? " (we just inserted the space)
            if (before != null && before.length >= 2) {
                val punct = before[before.length - 2]
                if (punct in SENTENCE_END_PUNCT && before.last().isWhitespace()) {
                    armAutoCap()
                }
            }
        }
    }

    private fun handleDelete(ic: InputConnection) {
        editGeneration++
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

        // 2) Composing buffer (translation mode only) — also covers undo auto-space
        //    after punct (last char is the auto-inserted space).
        if (composingText.isNotEmpty() && prefs.isTranslationModeEnabled) {
            composingText.deleteCharAt(composingText.length - 1)
            justAutoSpaced = false
            if (composingText.isEmpty()) {
                applyTranslatedToField(ic, "", "")
                layoutManager.clearSuggestions()
            } else {
                val source = composingText.toString()
                translationJob?.cancel()
                translationJob = serviceScope.launch {
                    val translated = translationEngine.translateText(source) ?: source
                    withContext(Dispatchers.Main) {
                        if (composingText.toString() != source) return@withContext
                        applyTranslatedToField(ic, source, translated)
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

        // Undo auto-space: first backspace after auto-space removes only the space
        if (justAutoSpaced) {
            justAutoSpaced = false
            // Drop pending auto-cap that was armed by the auto-space path
            if (shiftByAutoCap) {
                isShifted = false
                shiftByAutoCap = false
                refreshShiftUi()
            }
            try {
                ic.deleteSurroundingText(1, 0)
            } catch (e: Exception) {
                Log.w(TAG, "undo auto-space failed", e)
            }
            return
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
        editGeneration++
        // Finish any composing text first
        if (composingText.isNotEmpty()) {
            if (prefs.isTranslationModeEnabled) {
                val source = composingText.toString()
                translationJob?.cancel()
                translationJob = serviceScope.launch {
                    val translated = translationEngine.translateText(source) ?: source
                    withContext(Dispatchers.Main) {
                        try {
                            if (translationComposingAlive) {
                                // Replace composing with final committed text
                                ic.finishComposingText()
                            } else if (lastTranslatedField.isNotEmpty()) {
                                // Host already committed lastTranslatedField — if it matches
                                // current translation, leave it; otherwise replace.
                                val before = try {
                                    ic.getTextBeforeCursor(lastTranslatedField.length, 0)?.toString()
                                } catch (_: Exception) {
                                    null
                                }
                                if (before == lastTranslatedField && lastTranslatedField != translated) {
                                    ic.deleteSurroundingText(lastTranslatedField.length, 0)
                                    ic.commitText(translated, 1)
                                }
                                // else already correct
                            } else {
                                ic.commitText(translated, 1)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "enter commit translation failed", e)
                            try { ic.commitText(translated, 1) } catch (_: Exception) { }
                        }
                        composingText.clear()
                        lastTranslatedField = ""
                        translationComposingAlive = false
                        justAutoSpaced = false
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

        // Apply auto-correct to the pending word (same safe path as space).
        // Enter/Send runs immediately so chat stays responsive; generation +
        // text-before-cursor checks abort a late correction if the field closed.
        val word = activeWord.toString()
        activeWord.clear()
        layoutManager.clearSuggestions()
        autoCorrectEngine.predictionJob?.cancel()
        justAutoCorrected = false

        if (shouldRunAutoCorrect() && word.length >= 2) {
            scheduleCorrection(word, trailing = "")
        } else if (word.isNotEmpty()) {
            autoCorrectEngine.noteCommittedWord(word)
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
                // Many apps dismiss the field; arm auto-cap for the next open
                if (!isCapsLock && prefs.isAutoCapitalizeEnabled) {
                    isShifted = true
                    shiftByAutoCap = true
                    refreshShiftUi()
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
        // Newline → next line starts with capital when auto-cap is on
        if (!isCapsLock && prefs.isAutoCapitalizeEnabled) {
            isShifted = true
            shiftByAutoCap = true
            refreshShiftUi()
        }
    }

    // -------------------------------------------------------------------------
    // Capitalization: only at start of field or after newline
    // -------------------------------------------------------------------------

    /**
     * True when the next character should be capitalized:
     *  - start of empty field / only whitespace
     *  - after a newline
     *  - after sentence-end punctuation (`.?!`) optionally followed by whitespace
     *
     * TYPE_NULL fields (Termux etc.): only the very first character of the session.
     */
    private fun shouldAutoCapitalize(ic: InputConnection?): Boolean {
        if (!prefs.isAutoCapitalizeEnabled) return false
        if (fieldKind == FieldKind.PASSWORD) return false
        if (ic == null) return true
        if (composingText.isNotEmpty()) return false
        val info = currentEditorInfo
        val hasNoTextClass = info != null &&
            (info.inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_NULL
        if (hasNoTextClass) {
            return !hasTypedSinceFieldStart
        }
        val before = ic.getTextBeforeCursor(4, 0)?.toString()
        if (before == null) {
            return !hasTypedSinceFieldStart
        }
        if (before.isEmpty()) return true
        if (before.last() == '\n') return true
        if (before.all { it.isWhitespace() }) return true
        // After ". " / "! " / "? " or bare `.`/`!`/`?` at end
        val trimmed = before.trimEnd()
        if (trimmed.isNotEmpty() && trimmed.last() in SENTENCE_END_PUNCT) return true
        return false
    }

    private fun toggleShift() {
        val now = System.currentTimeMillis()
        val isDoubleTap = !isCapsLock && (now - lastShiftTapTime) <= DOUBLE_TAP_TIMEOUT_MS
        lastShiftTapTime = now

        // Any manual shift interaction leaves auto-cap mode
        shiftByAutoCap = false

        when {
            isCapsLock -> {
                isCapsLock = false
                isShifted = false
            }
            // Two quick taps → caps lock
            isShifted && isDoubleTap -> {
                isCapsLock = true
                isShifted = true
            }
            // Cancel one-shot shift (manual or leftover auto-cap)
            isShifted -> {
                isShifted = false
            }
            else -> {
                isShifted = true
            }
        }
        refreshShiftUi()
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
        refreshShiftUi()
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
        editGeneration++
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

        clearOneShotShift()
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
                val ic = currentInputConnection
                // Finish any live translation segment first so emoji is not mixed
                // into the Indonesian source / English composing span.
                commitTranslationSegmentIfNeeded(ic)
                ic?.commitText(emoji, 1)
                // Emoji breaks the current word — drop partial tracking
                activeWord.clear()
                justAutoCorrected = false
                justAutoSpaced = false
                layoutManager.clearSuggestions()
                autoCorrectEngine.predictionJob?.cancel()
                editGeneration++
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
                val ic = currentInputConnection
                // Commit live translation first so paste doesn't desync source/field
                commitTranslationSegmentIfNeeded(ic)
                ic?.commitText(text, 1)
                activeWord.clear()
                justAutoCorrected = false
                justAutoSpaced = false
                layoutManager.clearSuggestions()
                autoCorrectEngine.predictionJob?.cancel()
                editGeneration++
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
            val ic = currentInputConnection
            if (composingText.isNotEmpty() && ic != null) {
                val source = composingText.toString()
                serviceScope.launch {
                    val translated = translationEngine.translateText(source) ?: source
                    withContext(Dispatchers.Main) {
                        try {
                            if (translationComposingAlive) {
                                ic.finishComposingText()
                            } else if (lastTranslatedField.isEmpty()) {
                                ic.commitText(translated, 1)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "commit on translation off failed", e)
                        }
                        composingText.clear()
                        lastTranslatedField = ""
                        translationComposingAlive = false
                        layoutManager.hideTranslationModeBar()
                        layoutManager.clearSuggestions()
                    }
                }
            } else {
                composingText.clear()
                lastTranslatedField = ""
                translationComposingAlive = false
                layoutManager.hideTranslationModeBar()
            }
            switchMode(currentMode)
            layoutManager.updateTranslationIndicator(false)
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
        lastTranslatedField = ""
        translationComposingAlive = false
        val ic = currentInputConnection
        if (composingText.isNotEmpty() && ic != null) {
            val source = composingText.toString()
            serviceScope.launch {
                val translated = translationEngine.translateText(source) ?: source
                withContext(Dispatchers.Main) {
                    layoutManager.showTranslationModeBar(source, translated)
                    applyTranslatedToField(ic, source, translated)
                }
            }
        } else {
            composingText.clear()
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
