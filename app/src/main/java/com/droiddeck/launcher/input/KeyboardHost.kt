package com.droiddeck.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class KeyboardHost(
    context: Context,
    private val sendText: (String) -> Boolean = { false },
    private val sendKeyEvent: (KeyEvent) -> Boolean,
) : View(context) {
    constructor(activity: Activity) : this(activity, { text -> ImeTextBridge.send(activity, text) }, { activity.dispatchKeyEvent(it) })

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val map: KeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    var shown = false
        private set
    var imeShowing = false
        private set
    var onImeChanged: ((Boolean) -> Unit)? = null
    var purpose = ImeContentType.PURPOSE_NORMAL
        private set
    var hint = 0
        private set

    fun content(purpose: Int, hint: Int) {
        if (this.purpose == purpose && this.hint == hint) return
        this.purpose = purpose
        this.hint = hint
        if (shown) imm.restartInput(this)
    }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        visibility = VISIBLE
        layoutParams = android.widget.FrameLayout.LayoutParams(1, 1)
    }

    override fun onCheckIsTextEditor(): Boolean = shown

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!shown) return null
        outAttrs.inputType = ImeContentType.inputType(purpose, hint)
        outAttrs.imeOptions = ImeContentType.imeOptions(purpose, hint)
        return KeyConnection()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val visible = insets.isVisible(WindowInsets.Type.ime())
            if (visible != imeShowing) {
                imeShowing = visible
                onImeChanged?.invoke(visible)
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || keyCode != KeyEvent.KEYCODE_BACK || !shown) {
            return super.onKeyPreIme(keyCode, event)
        }
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            hide()
            onImeChanged?.invoke(false)
        }
        return true
    }

    fun toggle() = if (shown && imeVisible()) hide() else show()

    private fun imeVisible() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
        rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != false

    fun show() {
        shown = true
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        shown = false
        imm.hideSoftInputFromWindow(windowToken, 0)
        isFocusable = false
        isFocusableInTouchMode = false
        clearFocus()
    }

    fun type(text: CharSequence?) {
        if (text.isNullOrEmpty()) return
        val value = text.toString()
        val mapped = value.map { ch -> map.getEvents(charArrayOf(ch))?.takeIf { events ->
            events.any { it.action == KeyEvent.ACTION_DOWN && it.getUnicodeChar() == ch.code }
        } }
        if (mapped.all { it != null }) {
            for (events in mapped) for (event in events!!) sendKeyEvent(event)
            return
        }
        if (!secret && sendText(value)) return
        for (ch in value) sendUnicode(ch)
    }

    private val secret get() =
        purpose == ImeContentType.PURPOSE_PASSWORD || hint and ImeContentType.HINT_HIDDEN_TEXT != 0

    private fun sendUnicode(ch: Char) {
        val t = System.currentTimeMillis()
        val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, 0)
        sendKeyEvent(CharKeyEvent(down, ch))
        sendKeyEvent(CharKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), ch))
    }

    fun tap(keyCode: Int) {
        val t = System.currentTimeMillis()
        sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

    // The IME's composing text only exists on the Android side: it is held here and sent as
    // keystrokes once the IME commits or finishes it, so a preview never types into the guest.
    private inner class KeyConnection : BaseInputConnection(this, false) {
        private var composing = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = ""
            type(text)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = text?.toString().orEmpty()
            return true
        }

        override fun finishComposingText(): Boolean {
            flushComposing()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val buffered = minOf(beforeLength, composing.length)
            composing = composing.dropLast(buffered)
            repeat(beforeLength - buffered) { tap(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength) { tap(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (composing.isNotEmpty() && event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> flushComposing()
                    KeyEvent.KEYCODE_DEL -> {
                        composing = composing.dropLast(1)
                        return true
                    }
                }
            }
            return this@KeyboardHost.sendKeyEvent(event)
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            flushComposing()
            tap(KeyEvent.KEYCODE_ENTER)
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            if (n <= 0) "" else composing.takeLast(n)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null

        private fun flushComposing() {
            val pending = composing
            composing = ""
            if (pending.isNotEmpty()) type(pending)
        }
    }

    private class CharKeyEvent(origin: KeyEvent, private val ch: Char) : KeyEvent(origin) {
        override fun getUnicodeChar(): Int = ch.code
        override fun getUnicodeChar(metaState: Int): Int = ch.code
    }
}
