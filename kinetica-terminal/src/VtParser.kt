// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors (MIT).
// Adapted for Kotlin from Ghostty Parser.zig and parse_table.zig, commit
// 4da7523faba68ccb4042ea20585817098a51c015. See third-party/GHOSTTY-LICENSE.
// Changes: decoded Unicode input, callback dispatch, bounded string payloads,
// and 64 parameters with explicit omitted values. No native memory dependencies.
package io.heapy.kinetica.terminal

/** Incremental DEC/ECMA-48 state machine. Command storage is reused after dispatch. */
internal class VtParser(private val handler: Handler) {
    interface Handler {
        fun print(code: Int)
        fun execute(code: Int)
        fun escape(final: Int, intermediates: Int)
        fun csi(final: Int, command: VtParser)
        fun string(kind: Int, value: String, terminator: Int)
    }

    val parameters = IntArray(64)
    val colon = BooleanArray(64)
    var count = 0
        private set
    var prefix = 0
        private set
    var intermediates = 0
        private set
    private var state = GROUND
    private var intermediateCount = 0
    private var index = 0
    private var overflow = false
    private var stringKind = 0
    private val payload = StringBuilder()

    // UTF-8 is decoded for printable text and supported string payloads. Escape/CSI/DCS
    // headers (and ignored APC/SOS/PM) follow the byte transition table instead.
    val rawByteState: Boolean get() = state != GROUND && (state != STRING || stringKind == IGNORED)

    fun reset() { state = GROUND; payload.clear(); clear() }

    fun accept(code: Int) {
        if (code == 0x18 || code == 0x1a) {
            // Ghostty unhooks a DCS on every exit, including CAN/SUB. A complete
            // DECRQSS request can therefore reply here; OSC cancellation discards it.
            if (state == STRING && stringKind == DCS) finishString(code)
            state = GROUND; payload.clear(); return
        }
        if (code == 27) {
            if (state == STRING) finishString(code)
            state = ESCAPE; clear(); return
        }
        if (state == DCS_IGNORE && code >= 32) return
        if (rawByteState && code in 0x80..0x9f) {
            when (code) {
                0x90 -> { state = DCS_ENTRY; clear() }
                0x9b -> { state = CSI_ENTRY; clear() }
                0x9d -> beginString(OSC)
                0x98, 0x9e, 0x9f -> beginString(IGNORED)
                0x9c -> { state = GROUND; payload.clear() }
                else -> { state = GROUND; payload.clear(); handler.execute(code) }
            }
            return
        }
        if (state == STRING) {
            if (stringKind == OSC && code == 7) { finishString(code); state = GROUND }
            else if (stringKind != IGNORED && (code >= 32 || stringKind == DCS)) {
                if (payload.length + (if (code > 0xffff) 2 else 1) <= STRING_LIMIT) payload.append(codePointString(code))
                else overflow = true
            }
            return
        }
        if (code < 32) {
            if (state !in DCS_ENTRY..DCS_IGNORE) handler.execute(code)
            return
        }
        if (code == 127) return
        when (state) {
            GROUND -> if (code !in 0x80..0x9f) handler.print(code)
            ESCAPE -> when (code) {
                '['.code -> { state = CSI_ENTRY; clear() }
                ']'.code -> beginString(OSC)
                'P'.code -> { state = DCS_ENTRY; clear() }
                'X'.code, '^'.code, '_'.code -> beginString(IGNORED)
                in 0x20..0x2f -> { collect(code); state = ESCAPE_INTERMEDIATE }
                in 0x30..0x7e -> { state = GROUND; handler.escape(code, intermediates) }
            }
            ESCAPE_INTERMEDIATE -> when (code) {
                in 0x20..0x2f -> collect(code)
                in 0x30..0x7e -> { state = GROUND; if (!overflow) handler.escape(code, intermediates) }
            }
            CSI_ENTRY, CSI_PARAM, CSI_INTERMEDIATE, CSI_IGNORE -> csiByte(code)
            DCS_ENTRY, DCS_PARAM, DCS_INTERMEDIATE, DCS_IGNORE -> dcsByte(code)
        }
    }

    private fun csiByte(code: Int) {
        when (code) {
            in 0x40..0x7e -> {
                val valid = state != CSI_IGNORE && !overflow && (code == 'm'.code || !colon.any { it })
                count = if (index == 0 && parameters[0] < 0) 0 else index + 1
                state = GROUND
                if (valid) handler.csi(code, this)
            }
            in 0x30..0x39, ';'.code, ':'.code -> {
                if (state == CSI_INTERMEDIATE || state == CSI_IGNORE || code == ':'.code && state == CSI_ENTRY) state = CSI_IGNORE
                else { parameter(code); state = CSI_PARAM }
            }
            in 0x3c..0x3f -> {
                if (state == CSI_ENTRY) { prefix = code; state = CSI_PARAM } else state = CSI_IGNORE
            }
            in 0x20..0x2f -> if (state != CSI_IGNORE) { collect(code); state = CSI_INTERMEDIATE }
            // Unassigned high bytes leave the header state unchanged (Ghostty parse table).
            in 0xa0..0xff -> Unit
            else -> state = CSI_IGNORE
        }
    }

    private fun dcsByte(code: Int) {
        when (code) {
            in 0x40..0x7e -> {
                if (state == DCS_IGNORE || overflow) beginString(IGNORED)
                else {
                    // Keep the header in the bounded payload for DECRQSS/XTGETTCAP.
                    val header = buildString {
                        if (prefix != 0) append(prefix.toChar())
                        if (index > 0 || parameters[0] >= 0) for (i in 0..index) {
                            if (i > 0) append(';')
                            if (parameters[i] >= 0) append(parameters[i])
                        }
                        for (i in intermediateCount - 1 downTo 0) append((intermediates ushr (i * 8) and 255).toChar())
                        append(code.toChar())
                    }
                    beginString(DCS); payload.append(header)
                }
            }
            in 0x30..0x39, ';'.code -> if (state == DCS_ENTRY || state == DCS_PARAM) { parameter(code); state = DCS_PARAM } else state = DCS_IGNORE
            in 0x3c..0x3f -> if (state == DCS_ENTRY) { prefix = code; state = DCS_PARAM } else state = DCS_IGNORE
            in 0x20..0x2f -> if (state != DCS_IGNORE) { collect(code); state = DCS_INTERMEDIATE }
            in 0xa0..0xff -> Unit
            else -> state = DCS_IGNORE
        }
    }

    private fun parameter(code: Int) {
        if (code == ';'.code || code == ':'.code) {
            colon[index] = code == ':'.code
            if (index == parameters.lastIndex) overflow = true else index++
        } else parameters[index] = (parameters[index].coerceAtLeast(0) * 10 + code - 48).coerceAtMost(65535)
    }

    private fun collect(code: Int) {
        if (intermediateCount == 4) overflow = true
        else { intermediates = (intermediates shl 8) or code; intermediateCount++ }
    }
    private fun clear() {
        parameters.fill(-1); colon.fill(false); index = 0; count = 0
        prefix = 0; intermediates = 0; intermediateCount = 0; overflow = false
    }
    private fun beginString(kind: Int) { state = STRING; stringKind = kind; payload.clear(); overflow = false }
    private fun finishString(terminator: Int) {
        if (!overflow && stringKind != IGNORED) handler.string(stringKind, payload.toString(), terminator)
        payload.clear()
    }

    companion object {
        const val OSC = 1
        const val DCS = 2
        private const val IGNORED = 3
        const val STRING_LIMIT = 4096
        private const val GROUND = 0
        private const val ESCAPE = 1
        private const val ESCAPE_INTERMEDIATE = 2
        private const val CSI_ENTRY = 3
        private const val CSI_PARAM = 4
        private const val CSI_INTERMEDIATE = 5
        private const val CSI_IGNORE = 6
        private const val DCS_ENTRY = 7
        private const val DCS_PARAM = 8
        private const val DCS_INTERMEDIATE = 9
        private const val DCS_IGNORE = 10
        private const val STRING = 11
    }
}
