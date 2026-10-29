// OSC 133 semantics adapted from Ghostty's osc/parsers/semantic_prompt.zig.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

public enum class TerminalSemanticContent { OUTPUT, INPUT, PROMPT }
public enum class TerminalPromptMarker { NONE, PROMPT, CONTINUATION }
public enum class TerminalPromptKind { INITIAL, RIGHT, CONTINUATION, SECONDARY }
public enum class TerminalPromptRedraw { ALL, NONE, LAST }
public enum class TerminalPromptClick { LINE, MULTIPLE, CONSERVATIVE_VERTICAL, SMART_VERTICAL, ABSOLUTE, RELATIVE }
public enum class TerminalPromptAction(internal val code: Char) {
    FRESH_LINE('L'), NEW_PROMPT('A'), NEW_COMMAND('N'), PROMPT('P'), INPUT('B'),
    INPUT_UNTIL_EOL('I'), OUTPUT('C'), END_COMMAND('D'),
}

/** An accepted OSC 133 command. Options remain bounded by the protocol's 2048-byte capture. */
public data class TerminalSemanticPrompt(val action: TerminalPromptAction, val options: String) {
    /** The first occurrence wins, even when its value is invalid for a typed option. */
    public fun option(name: String): String? {
        var start = 0
        while (start < options.length) {
            val end = options.indexOf(';', start).let { if (it < 0) options.length else it }
            val equals = options.indexOf('=', start)
            if (equals in start until end && options.substring(start, equals) == name) return options.substring(equals + 1, end)
            start = end + 1
        }
        return null
    }
    public val promptKind: TerminalPromptKind? get() = when (option("k")) {
        "i" -> TerminalPromptKind.INITIAL; "r" -> TerminalPromptKind.RIGHT
        "c" -> TerminalPromptKind.CONTINUATION; "s" -> TerminalPromptKind.SECONDARY; else -> null
    }
    public val redraw: TerminalPromptRedraw? get() = when (option("redraw")) {
        "0" -> TerminalPromptRedraw.NONE; "1" -> TerminalPromptRedraw.ALL; "last" -> TerminalPromptRedraw.LAST; else -> null
    }
    public val click: TerminalPromptClick? get() = when (option("click_events")) {
        "1" -> TerminalPromptClick.ABSOLUTE; "2" -> TerminalPromptClick.RELATIVE
        else -> when (option("cl")) {
            "line" -> TerminalPromptClick.LINE; "m" -> TerminalPromptClick.MULTIPLE
            "v" -> TerminalPromptClick.CONSERVATIVE_VERTICAL; "w" -> TerminalPromptClick.SMART_VERTICAL; else -> null
        }
    }
    public val commandId: String? get() = option("aid")
    public val error: String? get() = option("err")
    public val exitCode: Int? get() {
        val value = options.substringBefore(';')
        val digits = if (value.startsWith('+') || value.startsWith('-')) value.drop(1) else value
        return if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) value.toIntOrNull() else null
    }
    public val specialKey: Boolean? get() = when (option("special_key")) { "0" -> false; "1" -> true; else -> null }

    /** Decode `cmdline` (printf %q) or `cmdline_url`; null means absent or malformed. Never executed. */
    public fun commandLineBytes(): ByteArray? {
        val quoted = option("cmdline")
        if (quoted != null) {
            val prefix = when { quoted.startsWith("$'") -> 2; quoted.startsWith("'") -> 1; else -> 0 }
            if (prefix != 0 && (quoted.length <= prefix || !quoted.endsWith("'"))) return null
            val value = if (prefix == 0) quoted else quoted.substring(prefix, quoted.length - 1)
            val result = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (char != '\\') result.append(char)
                else {
                    if (index == value.length) return null
                    result.append(when (val escaped = value[index++]) {
                        ' ', '\\', '"', '\'', '$' -> escaped
                        'e' -> '\u001b'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'v' -> '\u000b'
                        else -> return null
                    })
                }
            }
            return result.toString().encodeToByteArray()
        }
        val input = option("cmdline_url")?.encodeToByteArray() ?: return null
        val output = ByteArray(input.size)
        var read = 0
        var written = 0
        while (read < input.size) {
            if (input[read] != 37.toByte()) output[written++] = input[read++]
            else {
                if (read + 2 >= input.size) return null
                val high = input[read + 1].toInt().toChar().digitToIntOrNull(16) ?: return null
                val low = input[read + 2].toInt().toChar().digitToIntOrNull(16) ?: return null
                output[written++] = (high * 16 + low).toByte()
                read += 3
            }
        }
        return output.copyOf(written)
    }
}

internal fun parseSemanticPrompt(payload: String): TerminalSemanticPrompt? {
    if (payload.isEmpty() || payload.encodeToByteArray().size > 2048) return null
    val action = TerminalPromptAction.entries.firstOrNull { it.code == payload[0] } ?: return null
    if (payload.length > 1 && (action == TerminalPromptAction.FRESH_LINE || payload[1] != ';')) return null
    return TerminalSemanticPrompt(action, if (payload.length > 1) payload.substring(2) else "")
}

internal class TerminalSemanticState {
    var content = TerminalSemanticContent.OUTPUT
    var clearEol = false
    var seen = false
    var click: TerminalPromptClick? = null
    fun reset() { content = TerminalSemanticContent.OUTPUT; clearEol = false; seen = false; click = null }
    fun copyCursor(from: TerminalSemanticState) { content = from.content; clearEol = from.clearEol }
}
