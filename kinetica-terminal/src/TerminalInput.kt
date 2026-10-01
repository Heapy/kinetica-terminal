package io.heapy.kinetica.terminal

/** Physical numeric-keypad identity, separate from the layout/NumLock-dependent logical key. */
public enum class TerminalKeypadKey(internal val numeric: String, internal val application: Char) {
    ZERO("0", 'p'), ONE("1", 'q'), TWO("2", 'r'), THREE("3", 's'), FOUR("4", 't'),
    FIVE("5", 'u'), SIX("6", 'v'), SEVEN("7", 'w'), EIGHT("8", 'x'), NINE("9", 'y'),
    DECIMAL(".", 'n'), DIVIDE("/", 'o'), MULTIPLY("*", 'j'), SUBTRACT("-", 'm'), ADD("+", 'k'),
    ENTER("\r", 'M'), EQUAL("=", 'X'), SEPARATOR(",", 'l'),
}

public data class TerminalKey(
    val key: String,
    val control: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
    val keypad: TerminalKeypadKey? = null,
)

/** Returns null for keys which should be handled by the platform's text/IME input system. */
public fun TerminalSession.encodeKey(event: TerminalKey): String? {
    if (event.meta) return null
    val modifier = 1 + (if (event.shift) 1 else 0) + (if (event.alt) 2 else 0) + (if (event.control) 4 else 0)
    val final = when (event.key) {
        "ArrowUp" -> "A"; "ArrowDown" -> "B"; "ArrowRight" -> "C"; "ArrowLeft" -> "D"
        "Home" -> "H"; "End" -> "F"; "Begin" -> "E"; else -> null
    }
    if (final != null) return when {
        modifier > 1 -> "\u001b[1;$modifier$final"
        applicationCursor -> "\u001bO$final"
        else -> "\u001b[$final"
    }
    val number = when (event.key) {
        "Insert" -> 2; "Delete" -> 3; "PageUp" -> 5; "PageDown" -> 6
        "F5" -> 15; "F6" -> 17; "F7" -> 18; "F8" -> 19; "F9" -> 20; "F10" -> 21; "F11" -> 23; "F12" -> 24
        else -> null
    }
    if (number != null) return "\u001b[$number${if (modifier > 1) ";$modifier" else ""}~"
    if (event.key in listOf("F1", "F2", "F3", "F4")) {
        val f = ('P'.code + event.key.last().digitToInt() - 1).toChar()
        return if (modifier > 1) "\u001b[1;$modifier$f" else "\u001bO$f"
    }
    // NumLock navigation arrives as logical Home/End/arrows/etc and takes the paths above.
    // Ghostty's mode 1035 forces numeric keypad encoding regardless of the lock flag itself.
    event.keypad?.let { key ->
        if (applicationKeypad && !keypadNumericOverride) {
            return "\u001bO${if (modifier > 1) modifier.toString() else ""}${key.application}"
        }
        // Keep the host's decimal separator/layout. Legacy numeric keypad modifiers do not
        // turn Ctrl+keypad-2 into NUL or Alt+keypad-1 into an escape-prefixed digit.
        return if (key == TerminalKeypadKey.ENTER) {
            if (newlineMode) "\r\n" else "\r"
        } else event.key.takeIf { it.length == 1 && it[0] >= ' ' } ?: key.numeric
    }
    val value = when (event.key) {
        "Enter" -> if (newlineMode) "\r\n" else "\r"
        "Backspace" -> if (event.control) "\b" else "\u007f"
        "Tab" -> if (event.shift) "\u001b[Z" else "\t"
        "Escape" -> "\u001b"
        else -> if (event.control && event.key.length == 1) {
            val c = event.key.uppercase()[0]
            when {
                c in '@'..'_' -> (c.code and 31).toChar().toString()
                c == ' ' || c == '2' -> "\u0000"
                c == '?' || c == '8' -> "\u007f"
                else -> null
            }
        } else if (event.alt && event.key.length == 1) event.key else null
    }
    return value?.let { if (event.alt) "\u001b$it" else it }
}

public fun TerminalSession.sendKey(event: TerminalKey): Boolean = encodeKey(event)?.let { sendInput(it); true } ?: false

public fun TerminalSession.paste(text: String) {
    // Pasted escape bytes must not terminate bracketed paste and inject terminal commands.
    val normalized = text.replace("\r\n", "\n").replace('\n', '\r').replace("\u001b", "")
    sendInput(if (bracketedPaste) "\u001b[200~$normalized\u001b[201~" else normalized)
}

public fun TerminalSession.sendFocus(focused: Boolean) {
    if (focusReporting) sendInput(if (focused) "\u001b[I" else "\u001b[O")
}
