package app.rexpocket.ui

import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import android.widget.EditText
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.text.TextWatcher
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rexpocket.core.KeyChord
import app.rexpocket.core.PocketSession
import app.rexpocket.R
import kotlinx.coroutines.flow.first

// What sits under the terminal: the composer, the key toolbar and the arrow pad.

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onSendEnter: () -> Unit,
    onHistory: (older: Boolean) -> Unit,
    onChord: ((KeyChord) -> Unit)?,
) {
    var field by remember { mutableStateOf<EditText?>(null) }
    // The field outlives recomposition, so its callbacks read the current handlers through states.
    val send = remember { mutableStateOf(onSend) }
    send.value = onSend
    val chord = remember { mutableStateOf(onChord) }
    chord.value = onChord
    val history = remember { mutableStateOf(onHistory) }
    history.value = onHistory

    fun showIme() {
        val edit = field ?: return
        edit.requestFocus()
        edit.post { edit.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(edit, 0) }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Colors.surface),
            factory = { ctx ->
                EditText(ctx).apply {
                    background = null
                    setTextColor(Colors.ink.toArgb())
                    setHintTextColor(Colors.muted.toArgb())
                    hint = ctx.getString(R.string.composer_hint)
                    textSize = 16f
                    setPadding(28, 18, 28, 18)
                    // Commands, not prose: no suggestions, no autocorrect, no capitalised first letter.
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    // With Ctrl armed, the next character typed is a key chord for the terminal, such
                    // as Ctrl-R, and never lands in the draft.
                    filters = arrayOf(
                        InputFilter { source, start, end, _, _, _ ->
                            val armed = chord.value ?: return@InputFilter null
                            if (end - start != 1) return@InputFilter null
                            val key = KeyChord.forChar(source[start]) ?: return@InputFilter null
                            armed(key)
                            ""
                        },
                    )
                    // One line, so the keyboard offers its send key instead of a newline.
                    imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    maxLines = 1
                    isSingleLine = true
                    setOnEditorActionListener { _, _, _ ->
                        send.value()
                        true
                    }
                    // Some IMEs send the Enter key itself rather than the editor action. Up and down,
                    // from a keyboard that has them, walk through the lines sent before.
                    setOnKeyListener { _, code, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (code) {
                            KeyEvent.KEYCODE_ENTER -> send.value()
                            KeyEvent.KEYCODE_DPAD_UP -> history.value(true)
                            KeyEvent.KEYCODE_DPAD_DOWN -> history.value(false)
                            else -> return@setOnKeyListener false
                        }
                        true
                    }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            // A committed newline would otherwise ride along with the text; Enter
                            // sends the line through the listener above instead.
                            val text = s?.toString().orEmpty().replace("\n", "")
                            if (text != s?.toString()) setText(text) else onDraft(text)
                        }
                    })
                    setOnFocusChangeListener { _, focused ->
                        // An EditText reports focus before the input connection is ready.
                        if (focused) post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0) }
                    }
                    // Focus does not change when the field is already focused, which is the case
                    // after the keyboard was dismissed with Back, so taps ask for the IME too.
                    setOnClickListener {
                        requestFocus()
                        post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0) }
                    }
                    field = this
                }
            },
            update = { view ->
                if (view.text.toString() != draft) {
                    view.setText(draft)
                    view.setSelection(draft.length)
                }
            },
        )
        Spacer(Modifier.width(6.dp))
        // Long-press sends Enter alone; nothing on screen says so, so the accessibility action does.
        val sendHint = stringResource(R.string.composer_send_hint)
        Text(
            stringResource(R.string.composer_send),
            color = Colors.accent,
            fontSize = TextStyles.body,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Colors.surface)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = sendHint,
                    onClick = { onSend(); showIme() },
                    onLongClick = { onSendEnter(); showIme() },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
internal fun Toolbar(
    ctrl: Boolean,
    locked: Boolean,
    dpad: Boolean,
    onCtrl: () -> Unit,
    onKey: (code: String, mods: String, codepoint: Int) -> Unit,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
    onDpad: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Tool(stringResource(R.string.key_escape)) { onKey("escape", "", 0) }
        Tool(stringResource(if (locked) R.string.key_ctrl_lock else R.string.key_ctrl), if (ctrl) Colors.accent else Colors.ink) { onCtrl() }
        Tool(stringResource(R.string.key_tab)) { onKey("tab", "", 0) }
        // A slash command typed into pi opens its command menu, and the first Enter only accepts
        // the highlighted entry. This key sends the Enter that runs it.
        Tool(stringResource(R.string.key_enter)) { onKey("enter", "", 0) }
        Tool("/") { onKey("slash", "", '/'.code) }
        Tool(stringResource(R.string.key_up)) { onKey("arrow_up", "", 0) }
        Tool(stringResource(R.string.key_down)) { onKey("arrow_down", "", 0) }
        Tool(stringResource(R.string.key_ctrl_c), Colors.amber) { onKey("key_c", "ctrl", 99) }
        Tool(stringResource(R.string.key_ctrl_d)) { onKey("key_d", "ctrl", 100) }
        Tool(stringResource(R.string.key_ctrl_z)) { onKey("key_z", "ctrl", 122) }
        Tool(stringResource(R.string.key_ctrl_l)) { onKey("key_l", "ctrl", 108) }
        Tool(stringResource(R.string.key_paste)) { onPaste() }
        Tool(stringResource(R.string.key_copy)) { onCopy() }
        Tool(stringResource(if (dpad) R.string.key_dpad_on else R.string.key_dpad)) { onDpad() }
    }
}

@Composable
internal fun Dpad(onAct: (suspend PocketSession.() -> Unit) -> Unit) {
    fun key(code: String) = onAct { sendKey(code) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            Tool(stringResource(R.string.key_backspace)) { key("backspace") }
            Tool(stringResource(R.string.key_up)) { key("arrow_up") }
            Tool(stringResource(R.string.header_stop), Colors.amber) { onAct { sendKey("key_c", mods = "ctrl", codepoint = 99) } }
        }
        Row {
            Tool(stringResource(R.string.key_left)) { key("arrow_left") }
            Tool(stringResource(R.string.key_down)) { key("arrow_down") }
            Tool(stringResource(R.string.key_right)) { key("arrow_right") }
        }
    }
}

@Composable
internal fun Tool(label: String, color: Color = Colors.ink, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(Radius.chip)).background(Colors.surface).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
