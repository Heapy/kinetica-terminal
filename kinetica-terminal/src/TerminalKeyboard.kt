// Kitty encoding and bounded flag stack adapted from Ghostty input/key_encode.zig,
// input/kitty.zig and terminal/kitty/key.zig at 4da7523faba68ccb4042ea20585817098a51c015.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// See ../third-party/GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal class TerminalKeyboardStack {
    private val flags = IntArray(8)
    private var index = 0
    val current: Int get() = flags[index]
    fun set(value: Int, mode: Int) {
        val supported = value and 31
        flags[index] = when (mode) {
            1 -> supported
            2 -> current or supported
            3 -> current and supported.inv()
            else -> current
        }
    }
    fun push(value: Int) { index = (index + 1) % flags.size; flags[index] = value and 31 }
    fun pop(count: Int) {
        if (count >= flags.size) { reset(); return }
        repeat(count) { flags[index] = 0; index = (index + flags.size - 1) % flags.size }
    }
    fun reset() { flags.fill(0); index = 0 }
}

internal fun String.terminalCodepoints(): List<Int> = buildList {
    var i = 0
    while (i < length) {
        val c = this@terminalCodepoints[i++]
        if (c.isHighSurrogate() && i < length && this@terminalCodepoints[i].isLowSurrogate()) {
            add(0x10000 + ((c.code - 0xd800) shl 10) + (this@terminalCodepoints[i++].code - 0xdc00))
        } else if (!c.isSurrogate()) add(c.code)
    }
}

internal fun terminalPrintable(code: Int): Boolean = code >= 32 && code !in 127..159

/** Returns null when the existing functional/control/text path owns the event. */
internal fun encodeModifyOtherKey(event: TerminalKey): String? {
    if (event.keypad != null) return null
    val bits = (if (event.shift) 1 else 0) or (if (event.alt) 2 else 0) or
        (if (event.control) 4 else 0) or (if (event.meta) 8 else 0)
    val text = event.text ?: event.key.takeIf { it.terminalCodepoints().size == 1 }.orEmpty()
    val points = text.terminalCodepoints()
    if (points.isNotEmpty() && points.all(::terminalPrintable) && event.key in listOf("Enter", "Escape", "Backspace")) {
        return if (event.key == "Backspace") "" else text
    }
    val functional = when (event.key) {
        "Enter" -> 13; "Tab" -> 9; "Escape" -> 27; "Backspace" -> 127
        else -> null
    }
    if (functional != null) {
        if (bits == 0 || event.key == "Backspace" && bits == 4) return null
        return "\u001b[27;${bits + 1};$functional~"
    }
    val codepoint = points.singleOrNull() ?: return null
    // macOS Option may produce text without acting as Alt. Shift stays in this protocol's mask.
    val modifiers = if (event.consumedModifiers and 2 != 0) bits and 2.inv() else bits
    if (modifiers == 0) return text
    if (codepoint in 0x40..0x7f || modifiers and 1.inv() != 0 || codepoint == 32) {
        return "\u001b[27;${modifiers + 1};$codepoint~"
    }
    return text
}

internal fun terminalPhysicalCodepoint(code: String?): Int? = when {
    code == null -> null
    code.startsWith("Key") && code.length == 4 -> code.last().lowercaseChar().code
    code.startsWith("Digit") && code.length == 6 -> code.last().code
    else -> when (code) {
        "Space" -> 32; "Backquote" -> 96; "Minus" -> 45; "Equal" -> 61
        "BracketLeft" -> 91; "BracketRight" -> 93; "Backslash" -> 92
        "Semicolon" -> 59; "Quote" -> 39; "Comma" -> 44; "Period" -> 46; "Slash" -> 47
        else -> null
    }
}

// Browsers expose the shifted logical key, but no synchronous layout translation API.
// Prefer a previously observed unshifted key; this fallback covers US shifted punctuation.
internal fun terminalUnshiftedCodepoint(point: Int?, base: Int?): Int? {
    val shifted = "~!@#$%^&*()_+{}|:\"<>?"
    val unshifted = "`1234567890-=[]\\;',./"
    val index = point?.toChar()?.let(shifted::indexOf) ?: -1
    return if (index >= 0 && unshifted[index].code == base) base else point
}

private data class KittyKey(val code: Int, val final: Char = 'u', val modifier: Boolean = false)

private fun kittyKey(event: TerminalKey): KittyKey? {
    event.keypad?.let { keypad ->
        val navigation = when (event.key) {
            "ArrowLeft" -> 57417; "ArrowRight" -> 57418; "ArrowUp" -> 57419; "ArrowDown" -> 57420
            "PageUp" -> 57421; "PageDown" -> 57422; "Home" -> 57423; "End" -> 57424
            "Insert" -> 57425; "Delete" -> 57426; "Begin" -> 57427
            else -> 57399 + keypad.ordinal
        }
        return KittyKey(navigation)
    }
    val identity = event.physicalCode ?: event.key
    val modifier = when (identity) {
        "Shift", "ShiftLeft" -> 57441; "Control", "ControlLeft" -> 57442
        "Alt", "AltLeft" -> 57443; "Meta", "MetaLeft" -> 57444
        "ShiftRight" -> 57447; "ControlRight" -> 57448; "AltRight" -> 57449; "MetaRight" -> 57450
        "CapsLock" -> 57358; "NumLock" -> 57360
        else -> null
    }
    if (modifier != null) return KittyKey(modifier, modifier = true)
    val functional = when (event.key) {
        "Escape" -> KittyKey(27); "Enter" -> KittyKey(13); "Tab" -> KittyKey(9); "Backspace" -> KittyKey(127)
        "Insert" -> KittyKey(2, '~'); "Delete" -> KittyKey(3, '~')
        "PageUp" -> KittyKey(5, '~'); "PageDown" -> KittyKey(6, '~')
        "ArrowLeft" -> KittyKey(1, 'D'); "ArrowRight" -> KittyKey(1, 'C')
        "ArrowUp" -> KittyKey(1, 'A'); "ArrowDown" -> KittyKey(1, 'B')
        "Home" -> KittyKey(1, 'H'); "End" -> KittyKey(1, 'F')
        "ScrollLock" -> KittyKey(57359); "PrintScreen" -> KittyKey(57361); "Pause" -> KittyKey(57362)
        "F1" -> KittyKey(1, 'P'); "F2" -> KittyKey(1, 'Q'); "F3" -> KittyKey(13, '~'); "F4" -> KittyKey(1, 'S')
        "F5" -> KittyKey(15, '~'); "F6" -> KittyKey(17, '~'); "F7" -> KittyKey(18, '~'); "F8" -> KittyKey(19, '~')
        "F9" -> KittyKey(20, '~'); "F10" -> KittyKey(21, '~'); "F11" -> KittyKey(23, '~'); "F12" -> KittyKey(24, '~')
        else -> null
    }
    if (functional != null) return functional
    event.key.removePrefix("F").toIntOrNull()?.takeIf { event.key.startsWith("F") && it in 13..35 }
        ?.let { return KittyKey(57363 + it) }
    val cp = event.unshiftedCodepoint ?: event.key.lowercase().terminalCodepoints().singleOrNull()
    return cp?.takeIf { it in 1..0x10ffff && it !in 0xd800..0xdfff }?.let { KittyKey(it) }
}

internal fun encodeKittyKey(event: TerminalKey, flags: Int): String? {
    val all = flags and 8 != 0
    val events = flags and 2 != 0
    val release = event.action == TerminalKeyAction.RELEASE
    if (release && (!events || !all && event.keypad == null && event.key in listOf("Enter", "Tab", "Backspace"))) return ""
    val entry = kittyKey(event)
    val text = event.text ?: event.key.takeIf { it.terminalCodepoints().size == 1 }.orEmpty()
    val points = text.terminalCodepoints()
    val printable = points.isNotEmpty() && points.all(::terminalPrintable)
    val mods = (if (event.shift) 1 else 0) or (if (event.alt) 2 else 0) or
        (if (event.control) 4 else 0) or (if (event.meta) 8 else 0) or
        (if (event.capsLock) 64 else 0) or (if (event.numLock) 128 else 0)
    val effective = if (text.isNotEmpty()) mods and event.consumedModifiers.inv() else mods
    val binding = effective and 15
    if (printable && event.keypad == null) {
        if (event.key == "Enter") return if (release) "" else text
        if (event.key == "Backspace") return ""
    }
    if (!all && binding == 0 && event.keypad == null) {
        when (event.key) { "Enter" -> return "\r"; "Tab" -> return "\t"; "Backspace" -> return "\u007f" }
    }
    if (!all && binding == 0 && printable && !release) return text
    if (entry == null) return if (release) "" else text
    if (entry.modifier && !all) return ""
    val eventCode = if (!events) 0 else when (event.action) {
        TerminalKeyAction.PRESS -> 1; TerminalKeyAction.REPEAT -> 2; TerminalKeyAction.RELEASE -> 3
    }
    return buildString {
        append("\u001b[")
        if (entry.final != 'u' && entry.final != '~') {
            if (eventCode != 0) append("1;${mods + 1}:$eventCode")
            else if (mods != 0) append("1;${mods + 1}")
            append(entry.final)
            return@buildString
        }
        append(entry.code)
        if (flags and 4 != 0 && terminalPrintable(entry.code)) {
            val shifted = points.firstOrNull()?.takeIf { event.shift && it != entry.code }
            val base = event.baseCodepoint?.takeIf { it != entry.code && (points.isEmpty() || points.size == 1 && points[0] != it) }
            if (shifted != null) append(":$shifted")
            if (base != null) { if (shifted == null) append(':'); append(":$base") }
        }
        val hasModifiers = eventCode > 1 || mods != 0
        if (eventCode > 1) append(";${mods + 1}:$eventCode")
        else if (mods != 0) append(";${mods + 1}")
        if (flags and 16 != 0 && !release && mods and 12 == 0 &&
            (mods and 2 == 0 || event.consumedModifiers and 2 != 0)) {
            val associated = points.filter(::terminalPrintable)
            if (associated.isNotEmpty()) {
                if (!hasModifiers) append(';')
                append(';'); append(associated.joinToString(":"))
            }
        }
        append(entry.final)
    }
}
