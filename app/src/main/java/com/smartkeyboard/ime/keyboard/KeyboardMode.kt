package com.smartkeyboard.ime.keyboard

/**
 * Represents the current visual and functional mode of the keyboard.
 */
enum class KeyboardMode {
    LETTERS,    // QWERTY / Indonesian layout
    NUMBERS,    // Numeric pad + common symbols
    SYMBOLS,    // Full symbols & punctuation
    EMOJI,      // Emoji panel (handled separately)
    CLIPBOARD   // Clipboard history panel
}

/**
 * Classification of the focused text field for layout / special keys / enter action.
 */
enum class FieldKind {
    TEXT, NUMBER, PHONE, EMAIL, URI, PASSWORD, DATETIME
}
