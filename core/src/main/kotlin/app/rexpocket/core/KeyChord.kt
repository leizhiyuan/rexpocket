package app.rexpocket.core

/**
 * A key as `client.key` names it: the physical key and the code point it types without shift,
 * which is what a binding such as ctrl+r matches on.
 */
data class KeyChord(val code: String, val codepoint: Int) {
    companion object {
        /**
         * The key that types [c] on a US layout, for sending it with a modifier held. Only the
         * characters a modifier combines with in a terminal: letters, digits and the punctuation
         * that has a control character. Anything else, such as CJK text, has no key to hold.
         */
        fun forChar(c: Char): KeyChord? {
            val lower = c.lowercaseChar()
            val code = when (lower) {
                in 'a'..'z' -> "key_$lower"
                in '0'..'9' -> "digit_$lower"
                ' ' -> "space"
                '[' -> "bracket_left"
                ']' -> "bracket_right"
                '\\' -> "backslash"
                '/' -> "slash"
                '-' -> "minus"
                '=' -> "equal"
                ',' -> "comma"
                '.' -> "period"
                ';' -> "semicolon"
                '\'' -> "quote"
                '`' -> "backquote"
                else -> return null
            }
            return KeyChord(code, lower.code)
        }
    }
}
