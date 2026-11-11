package me.leaver.rexpocket.ui

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.sagernet.libghostty.GhosttyTerminalView

/**
 * The pane as the phone keyboard sees it.
 *
 * libghostty's view answers `onCreateInputConnection` with `EditorInfo.inputType = TYPE_NULL`, or -
 * when forced to take characters at all - with a visible-password variation. Gboard reads that as
 * "no text here": it offers the Latin keyboard only and hides the language key, so a composing
 * keyboard such as Zhuyin, Pinyin, Cangjie or a Japanese one can never be reached. Its view class
 * is final, so the editor cannot be the view itself; this view sits under the pane and holds the
 * keyboard instead. It reports a plain text field, keeps the composing text to itself - only what
 * the IME commits goes to the program - and passes keys on to the pane the way the keyboard does on
 * a desk terminal.
 */
internal class TerminalImeView(context: Context) : View(context) {
    /** The pane what is typed is sent to, once the terminal has one. */
    var pane: GhosttyTerminalView? = null

    /** How long after [showIme] a focus change still counts as the keyboard being wanted. */
    private var requestedUntil = 0L

    init {
        // The pane is what is on screen: this view is a way in for the keyboard, not something to
        // read, tap or announce.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Raises the keyboard for the pane, the way a tap on a desk terminal's text field would. */
    fun showIme() {
        requestedUntil = SystemClock.uptimeMillis() + IME_REQUEST_TIMEOUT_MS
        requestFocus()
        raise()
    }

    private fun raise() {
        if (SystemClock.uptimeMillis() > requestedUntil) return
        if (!isAttachedToWindow || !isFocused || !hasWindowFocus()) return
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) raise()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A plain text field, with no suggestions: a shell command is not prose, and nothing here
        // may be learned or corrected. Neither flag hides the keyboard's own layouts, which is the
        // point - TYPE_NULL and the password variations do.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialCapsMode = 0
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection()
    }

    /** Keys reach the pane as they did when the pane itself held the keyboard. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = pane?.onKeyDown(keyCode, event) == true

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = pane?.onKeyUp(keyCode, event) == true

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean = pane?.onKeyPreIme(keyCode, event) == true

    /**
     * What the keyboard says, turned into what the program reads. The composing text (`ㄨㄛˇ`, `wo3`,
     * a half-typed kana) is shown in the pane and sent nowhere; a commit sends exactly the characters
     * the IME chose, and a delete during composition removes from the composition rather than from
     * the program's line.
     */
    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalImeView, false) {
        private val composing = StringBuilder()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing.setLength(0)
            showComposing("")
            if (text != null) pane?.sendText(text)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing.setLength(0)
            if (text != null) composing.append(text)
            showComposing(composing.toString())
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            composing.setLength(0)
            showComposing("")
            return true
        }

        override fun finishComposingText(): Boolean {
            composing.setLength(0)
            showComposing("")
            return true
        }

        override fun closeConnection() {
            composing.setLength(0)
            showComposing("")
            super.closeConnection()
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            delete(before = beforeLength, after = afterLength, byCodePoint = false)

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            delete(before = beforeLength, after = afterLength, byCodePoint = true)

        /** Counts of composing characters, or of code points, taken off the composition first. */
        private fun delete(before: Int, after: Int, byCodePoint: Boolean): Boolean {
            var left = before
            while (left > 0 && composing.isNotEmpty()) {
                val chars = if (byCodePoint) Character.charCount(composing.codePointBefore(composing.length)) else 1
                composing.delete(composing.length - chars, composing.length)
                left--
            }
            if (left != before) showComposing(composing.toString())
            repeat(left) { key(KeyEvent.KEYCODE_DEL) }
            repeat(after) { key(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event == null) return false
            return if (event.action == KeyEvent.ACTION_DOWN) {
                pane?.onKeyDown(event.keyCode, event)
            } else {
                pane?.onKeyUp(event.keyCode, event)
            } == true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            key(KeyEvent.KEYCODE_ENTER)
            return true
        }

        // Nothing is kept here to read back: the pane's text belongs to the program, and asking it
        // would mean a round trip over the tunnel for a suggestion strip that is turned off anyway.
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""

        override fun getSelectedText(flags: Int): CharSequence = ""

        override fun getCursorCapsMode(reqModes: Int): Int = 0
    }

    private fun key(keyCode: Int) {
        val pane = pane ?: return
        val now = SystemClock.uptimeMillis()
        pane.onKeyDown(keyCode, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        pane.onKeyUp(keyCode, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /**
     * Shows the composing text where the pane's cursor is. The view keeps that string private and
     * offers no setter; this is the accessor its own input connection calls, so the picture is the
     * one it would draw with its own editor. Null if the library ever drops it, which only costs
     * the inline preview.
     */
    private fun showComposing(text: String) {
        val pane = pane ?: return
        val setter = composingSetter ?: return
        runCatching { setter.invoke(null, pane, text) }
    }

    private companion object {
        /** Enough to cross a focus change, short enough not to raise the keyboard by surprise. */
        const val IME_REQUEST_TIMEOUT_MS = 1_000L

        val composingSetter: java.lang.reflect.Method? = runCatching {
            GhosttyTerminalView::class.java.getMethod(
                "access\$updateComposingText",
                GhosttyTerminalView::class.java,
                String::class.java,
            )
        }.getOrNull()
    }
}
