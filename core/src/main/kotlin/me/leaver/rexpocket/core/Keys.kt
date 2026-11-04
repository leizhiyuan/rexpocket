package me.leaver.rexpocket.core

import java.net.URI

// The modifier keys of the key row, and how a typed character becomes a key Rex can modify.

/** A modifier on the key row, in the order Rex writes them in a key's `mods`. */
enum class Mod(val wire: String) { Shift("shift"), Ctrl("ctrl"), Alt("alt") }

/** Off; on for the next key only; or on until tapped again. */
enum class Latch { Off, Once, Locked }

/**
 * The modifiers the key row holds. A tap turns one on for the next key, a second tap soon after
 * keeps it on, and a tap on a held one lets it go - the way a phone keyboard's shift works.
 */
data class KeyModifiers(
    private val latches: Map<Mod, Latch> = emptyMap(),
    private val tappedAt: Map<Mod, Long> = emptyMap(),
) {
    operator fun get(mod: Mod): Latch = latches[mod] ?: Latch.Off

    val any: Boolean get() = latches.values.any { it != Latch.Off }

    fun tap(mod: Mod, now: Long): KeyModifiers {
        val next = when (get(mod)) {
            Latch.Off -> Latch.Once
            Latch.Once -> if (now - (tappedAt[mod] ?: Long.MIN_VALUE / 2) < DOUBLE_TAP_MS) Latch.Locked else Latch.Off
            Latch.Locked -> Latch.Off
        }
        return copy(latches = latches + (mod to next), tappedAt = tappedAt + (mod to now))
    }

    /** After a key: the modifiers that were on for it alone are off again. */
    fun consume(): KeyModifiers =
        copy(latches = latches.mapValues { (_, latch) -> if (latch == Latch.Once) Latch.Off else latch })

    /** The modifiers for a key that carries [own] itself, such as `ctrl` for C-c, joined as Rex wants. */
    fun mods(own: String = ""): String {
        val held = own.split('+').filter { it.isNotEmpty() }.toSet()
        return Mod.entries.filter { get(it) != Latch.Off || it.wire in held }.joinToString("+") { it.wire }
    }

    /**
     * [press] as Rex's `client.key` wants it, with these modifiers and the ones it carries itself.
     * Rex makes a printable character only from the key's text, so a printable key always sends its
     * character: shifted, with shift marked as used for it, when shift is on.
     */
    fun event(press: KeyPress, own: String = ""): KeyEvent {
        val extra = if (press.shifted) "$own+shift" else own
        val mods = mods(extra)
        val shift = Mod.Shift.wire in mods.split('+')
        val base = press.text.lowercase()
        val text = if (base.isEmpty()) "" else if (shift) shifted(base) else base
        val consumed = if (shift && text.isNotEmpty()) Mod.Shift.wire else ""
        return KeyEvent(press.code, mods, consumed, text, press.codepoint)
    }

    private companion object {
        /** Two taps closer than this hold the modifier, as the platform's double tap does. */
        const val DOUBLE_TAP_MS = 320L
    }
}

/**
 * One key on a US layout: Rex's name for it, the character it makes without shift as a code point,
 * and as [text] when it prints one. [shifted] says the keyboard typed it with shift, as a capital.
 */
data class KeyPress(val code: String, val codepoint: Int, val shifted: Boolean = false, val text: String = "")

/** The fields of one `client.key` press. */
data class KeyEvent(val code: String, val mods: String, val consumedMods: String, val text: String, val codepoint: Int)

/** What shift makes of [base] on a US layout. */
private fun shifted(base: String): String {
    if (base.length != 1) return base
    val c = base[0]
    return if (c in 'a'..'z') c.uppercase() else SHIFTED[c]?.toString() ?: base
}

private val SHIFTED = mapOf(
    '1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%', '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')',
    '-' to '_', '=' to '+', '[' to '{', ']' to '}', '\\' to '|', ';' to ':', '\'' to '"', ',' to '<', '.' to '>', '/' to '?', '`' to '~',
    ' ' to ' ',
)

/**
 * The key that types [text], when it is one key: what the phone keyboard sends while a modifier on
 * the key row is on, so C-r or Alt-b can be made from the keyboard's letters. Anything else -
 * several characters, a symbol that needs shift, an escape sequence - stays text.
 */
fun keyPressFor(text: String): KeyPress? {
    if (text.length != 1) return null
    val c = text[0]
    return when (c) {
        in 'a'..'z' -> KeyPress("key_$c", c.code, text = text)
        in 'A'..'Z' -> KeyPress("key_${c.lowercaseChar()}", c.lowercaseChar().code, shifted = true, text = text)
        in '0'..'9' -> KeyPress("digit_$c", c.code, text = text)
        // Return, Tab and Backspace print nothing; the key alone says what they are.
        '\r', '\t', '\u007f' -> PLAIN_KEYS[c]?.let { KeyPress(it, c.code) }
        else -> PLAIN_KEYS[c]?.let { KeyPress(it, c.code, text = text) }
    }
}

private val PLAIN_KEYS = mapOf(
    ' ' to "space", '\r' to "enter", '\t' to "tab", '\u007f' to "backspace",
    '-' to "minus", '=' to "equal", '[' to "bracket_left", ']' to "bracket_right", '\\' to "backslash",
    ';' to "semicolon", '\'' to "quote", ',' to "comma", '.' to "period", '/' to "slash", '`' to "backquote",
)

/** True for an http or https link with a host: the only kind the app hands to a browser. */
fun isWebLink(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}
