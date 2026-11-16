package com.droiddeck.launcher.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class KeyboardHostImeTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun attrs(purpose: Int = ImeContentType.PURPOSE_NORMAL, hint: Int = 0): EditorInfo {
        val host = KeyboardHost(context) { true }
        host.content(purpose, hint)
        host.show()
        val info = EditorInfo()
        assertNotNull(host.onCreateInputConnection(info))
        return info
    }

    private val EditorInfo.variation get() = inputType and InputType.TYPE_MASK_VARIATION
    private val EditorInfo.textClass get() = inputType and InputType.TYPE_MASK_CLASS
    private val EditorInfo.passwords get() = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
    private val EditorInfo.isPassword get() = variation in passwords

    @Test fun aDesktopTextFieldWithoutAGuestHintIsNotAPassword() {
        val info = attrs()
        assertEquals(InputType.TYPE_CLASS_TEXT, info.textClass)
        assertNotEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.variation)
        assertNotEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, info.variation)
        assertNotEquals(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, info.variation)
        assertEquals("plain text keeps the IME's own suggestions and composing", 0, info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    }

    @Test fun aGuestPasswordFieldKeepsTheSecureKeyboard() {
        val info = attrs(ImeContentType.PURPOSE_PASSWORD)
        assertEquals(InputType.TYPE_CLASS_TEXT, info.textClass)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.variation)
        assertEquals(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }

    @Test fun hiddenTextFromTheGuestIsAPasswordToo() {
        val info = attrs(ImeContentType.PURPOSE_NORMAL, ImeContentType.HINT_HIDDEN_TEXT)
        assertTrue(info.isPassword)
        assertEquals(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    }

    @Test fun sensitiveDataIsTreatedAsSensitiveButNotAsAPassword() {
        val info = attrs(ImeContentType.PURPOSE_NORMAL, ImeContentType.HINT_SENSITIVE_DATA)
        assertNotEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, info.variation)
        assertNotEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.variation)
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }

    @Test fun numericAndPhoneFieldsAskForTheirOwnKeyboard() {
        assertEquals(InputType.TYPE_CLASS_NUMBER, attrs(ImeContentType.PURPOSE_NUMBER).textClass)
        assertEquals(InputType.TYPE_CLASS_NUMBER, attrs(ImeContentType.PURPOSE_DIGITS).textClass)
        assertEquals(InputType.TYPE_CLASS_PHONE, attrs(ImeContentType.PURPOSE_PHONE).textClass)
    }

    @Test fun urlAndEmailStayPlainTextWithTheirOwnVariation() {
        val url = attrs(ImeContentType.PURPOSE_URL)
        assertEquals(InputType.TYPE_CLASS_TEXT, url.textClass)
        assertEquals(InputType.TYPE_TEXT_VARIATION_URI, url.variation)
        val email = attrs(ImeContentType.PURPOSE_EMAIL)
        assertEquals(InputType.TYPE_CLASS_TEXT, email.textClass)
        assertEquals(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, email.variation)
        assertNotEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, email.variation)
    }

    @Test fun aTerminalFieldSuppressesAutocorrectWithoutLookingLikeAPassword() {
        val info = attrs(ImeContentType.PURPOSE_TERMINAL)
        assertEquals(InputType.TYPE_CLASS_TEXT, info.textClass)
        assertTrue(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertNotEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, info.variation)
    }

    @Test fun multilineAndCapitalisationHintsReachTheIme() {
        val info = attrs(ImeContentType.PURPOSE_NORMAL, ImeContentType.HINT_MULTILINE or ImeContentType.HINT_AUTO_CAPITALIZATION)
        assertEquals(InputType.TYPE_TEXT_FLAG_MULTI_LINE, info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertEquals(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
    }

    @Test fun theKeyboardIsHiddenUntilTheSessionShowsIt() {
        val host = KeyboardHost(context) { true }
        assertEquals(null, host.onCreateInputConnection(EditorInfo()))
    }
}
