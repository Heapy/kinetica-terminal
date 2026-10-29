package io.heapy.kinetica.terminal

/** Physical numeric-keypad identity, separate from the layout/NumLock-dependent logical key. */
public enum class TerminalKeypadKey(internal val numeric: String, internal val application: Char) {
    ZERO("0", 'p'), ONE("1", 'q'), TWO("2", 'r'), THREE("3", 's'), FOUR("4", 't'),
    FIVE("5", 'u'), SIX("6", 'v'), SEVEN("7", 'w'), EIGHT("8", 'x'), NINE("9", 'y'),
    DECIMAL(".", 'n'), DIVIDE("/", 'o'), MULTIPLY("*", 'j'), SUBTRACT("-", 'm'), ADD("+", 'k'),
    ENTER("\r", 'M'), EQUAL("=", 'X'), SEPARATOR(",", 'l'),
}

public enum class TerminalKeyAction { PRESS, REPEAT, RELEASE }

public data class TerminalKey(
    val key: String,
    val control: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
    val keypad: TerminalKeypadKey? = null,
    val action: TerminalKeyAction = TerminalKeyAction.PRESS,
    /** Text produced by the host layout. Null lets simple callers derive it from [key]. */
    val text: String? = null,
    val unshiftedCodepoint: Int? = null,
    val baseCodepoint: Int? = null,
    /** DOM-style physical identity, especially for left/right modifiers. */
    val physicalCode: String? = null,
    val capsLock: Boolean = false,
    val numLock: Boolean = false,
    /** Kitty modifier bits consumed while producing [text], e.g. Shift or macOS Option. */
    val consumedModifiers: Int = 0,
)

/** Returns null for keys which should be handled by the platform's text/IME input system. */
public fun TerminalSession.encodeKey(event: TerminalKey): String? {
    if (kittyKeyboardFlags != 0) return encodeKittyKey(event, kittyKeyboardFlags)
    if (event.action == TerminalKeyAction.RELEASE) return ""
    if (event.key == "Backspace") {
        val points = event.text.orEmpty().terminalCodepoints()
        // A dead-key/composition event may carry printable text instead of a deletion.
        if (points.isNotEmpty() && points.all(::terminalPrintable)) return ""
    }
    if (modifyOtherKeysMode == 2) encodeModifyOtherKey(event)?.let { return it }
    if (event.meta && modifyOtherKeysMode != 2 && event.key != "Backspace") return null
    val modifier = 1 + (if (event.shift) 1 else 0) + (if (event.alt) 2 else 0) +
        (if (event.control) 4 else 0) + (if (event.meta) 8 else 0)
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
        // Modified F3 uses the tilde form to avoid colliding with cursor-position reports.
        if (event.key == "F3" && modifier > 1) return "\u001b[13;$modifier~"
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
        "Backspace" -> {
            // Ghostty's modified-key table overrides DECBKM for Shift/Alt/Super combinations.
            val swap = backarrowKeyMode && !event.shift && !event.alt && !event.meta
            if (event.control xor swap) "\b" else "\u007f"
        }
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
        ?: if (modifyOtherKeysMode == 2) event.text?.takeIf { it.isNotEmpty() } else null
}

/** Returns true for consumed keys, including those suppressed by an opted-in KAM lock. */
public fun TerminalSession.sendKey(event: TerminalKey): Boolean {
    if (keyboardInputBlocked) return true
    return encodeKey(event)?.let {
        if (it.isNotEmpty()) sendInput(it)
        true
    } ?: false
}

/** Committed typing/IME text. Raw host input and protocol replies use [sendInput] instead. */
public fun TerminalSession.sendText(text: String) {
    if (!keyboardInputBlocked) sendInput(text)
}

public fun TerminalSession.paste(text: String) {
    // Pasted escape bytes must not terminate bracketed paste and inject terminal commands.
    val normalized = text.replace("\r\n", "\n").replace('\n', '\r').replace("\u001b", "")
    sendInput(if (bracketedPaste) "\u001b[200~$normalized\u001b[201~" else normalized)
}

public fun TerminalSession.sendFocus(focused: Boolean) {
    if (focusReporting) sendInput(if (focused) "\u001b[I" else "\u001b[O")
}
