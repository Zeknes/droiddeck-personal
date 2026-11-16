package com.droiddeck.launcher.input

import android.text.InputType
import android.view.inputmethod.EditorInfo

// The guest app's zwp_text_input_v3 purpose and hint reach Android through KWin's
// virtual keyboard (droiddeck-ime writes ime.json). Android's IME only knows what this
// mapping produces, so nothing may look like a password unless the guest says so.
internal object ImeContentType {
    const val PURPOSE_NORMAL = 0
    const val PURPOSE_ALPHA = 1
    const val PURPOSE_DIGITS = 2
    const val PURPOSE_NUMBER = 3
    const val PURPOSE_PHONE = 4
    const val PURPOSE_URL = 5
    const val PURPOSE_EMAIL = 6
    const val PURPOSE_NAME = 7
    const val PURPOSE_PASSWORD = 8
    const val PURPOSE_DATE = 9
    const val PURPOSE_TIME = 10
    const val PURPOSE_DATETIME = 11
    const val PURPOSE_TERMINAL = 12

    const val HINT_COMPLETION = 1
    const val HINT_SPELLCHECK = 2
    const val HINT_AUTO_CAPITALIZATION = 4
    const val HINT_LOWERCASE = 8
    const val HINT_UPPERCASE = 16
    const val HINT_TITLECASE = 32
    const val HINT_HIDDEN_TEXT = 64
    const val HINT_SENSITIVE_DATA = 128
    const val HINT_LATIN = 256
    const val HINT_MULTILINE = 512

    fun inputType(purpose: Int, hint: Int): Int {
        if (purpose == PURPOSE_PASSWORD || hint and HINT_HIDDEN_TEXT != 0) {
            return InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        when (purpose) {
            PURPOSE_DIGITS, PURPOSE_NUMBER -> return InputType.TYPE_CLASS_NUMBER
            PURPOSE_PHONE -> return InputType.TYPE_CLASS_PHONE
            PURPOSE_URL -> return InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            PURPOSE_EMAIL -> return InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            PURPOSE_DATE -> return InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
            PURPOSE_TIME -> return InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            PURPOSE_DATETIME -> return InputType.TYPE_CLASS_DATETIME
            PURPOSE_TERMINAL -> return InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        var type = InputType.TYPE_CLASS_TEXT
        if (hint and HINT_MULTILINE != 0) type = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        type = type or when {
            hint and HINT_AUTO_CAPITALIZATION != 0 -> InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint and HINT_TITLECASE != 0 -> InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint and HINT_UPPERCASE != 0 -> InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            else -> 0
        }
        return type
    }

    fun imeOptions(purpose: Int, hint: Int): Int {
        var options = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        if (purpose == PURPOSE_PASSWORD || hint and (HINT_HIDDEN_TEXT or HINT_SENSITIVE_DATA) != 0) {
            options = options or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        return options
    }
}
