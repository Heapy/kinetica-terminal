// Color operations and parsing adapted from Ghostty color.zig, fraction.zig,
// osc/parsers/color.zig and stream_terminal.zig. Copyright (c) 2024 Mitchell
// Hashimoto, Ghostty contributors. MIT; see third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

// RGB occupies the low 24 bits. Indexed colors retain their identity across palette changes.
internal const val INDEXED_COLOR = 1 shl 24

/** Session-wide state: screens, saved cursors and RIS never reset the color overrides. */
internal class TerminalColors(private var original: TerminalTheme) {
    private val palette = IntArray(256, ::terminalPalette)
    var theme: TerminalTheme = original
        private set
    fun palette(index: Int): Int = palette[index]
    fun snapshot(): TerminalColors = TerminalColors(theme).also { palette.copyInto(it.palette) }
    fun configure(value: TerminalTheme) { original = value; theme = value }

    /** Returns whether the surface needs repainting. Replies retain the request terminator. */
    fun operation(command: Int, payload: String, terminator: Int, reply: (String) -> Unit): Boolean {
        val tokens = payload.split(';').filter { it.isNotEmpty() }
        var changed = false
        val responses = StringBuilder()
        fun index(value: String): Int? = if (value.all { it in '0'..'9' })
            value.toIntOrNull()?.takeIf { it in 0..260 } else null
        fun query(target: Int, color: Int) {
            responses.append("\u001b]").append(if (command == 4) "4;$target" else target.toString()).append(";rgb:")
            for (shift in 16 downTo 0 step 8) {
                if (shift != 16) responses.append('/')
                responses.append(((color ushr shift and 255) * 257).toString(16).padStart(4, '0'))
            }
            responses.append(if (terminator == 7) "\u0007" else "\u001b\\")
        }
        when (command) {
            4 -> {
                var i = 0
                while (i + 1 < tokens.size) {
                    val target = index(tokens[i]) ?: break
                    val spec = tokens[i + 1]
                    if (spec == "?") {
                        if (target < 256) query(target, palette[target])
                    } else {
                        val color = parseTerminalColor(spec) ?: break
                        if (target < 256) { palette[target] = color; changed = true }
                    }
                    i += 2
                }
            }
            104 -> {
                var recognized = false
                for (token in tokens) {
                    val index = index(token) ?: continue
                    recognized = true
                    if (index < 256) { palette[index] = terminalPalette(index); changed = true }
                }
                // Ghostty also resets all when the list contains only invalid entries.
                if (!recognized) { for (i in palette.indices) palette[i] = terminalPalette(i); changed = true }
            }
            in 10..19 -> {
                for ((i, spec) in tokens.withIndex()) {
                    val target = command + i
                    if (target > 19) break
                    if (spec == "?") {
                        when (target) {
                            10 -> query(target, theme.foreground)
                            11 -> query(target, theme.background)
                            12 -> query(target, theme.cursor)
                        }
                    } else {
                        val color = parseTerminalColor(spec) ?: break
                        when (target) {
                            10 -> theme = theme.copy(foreground = color)
                            11 -> theme = theme.copy(background = color)
                            12 -> theme = theme.copy(cursor = color)
                        }
                        if (target <= 12) changed = true
                    }
                }
            }
            in 110..112 -> if (tokens.isEmpty()) {
                theme = when (command) {
                    110 -> theme.copy(foreground = original.foreground)
                    111 -> theme.copy(background = original.background)
                    else -> theme.copy(cursor = original.cursor)
                }
                changed = true
            }
        }
        if (responses.isNotEmpty()) reply(responses.toString())
        return changed
    }
}

internal fun parseTerminalColor(value: String): Int? {
    val input = value.trim(' ', '\t')
    if (input.isEmpty()) return null
    fun hex(value: String): Int? {
        if (value.length !in 1..4 || value.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return value.toInt(16) * 255 / ((1 shl (value.length * 4)) - 1)
    }
    fun packed(r: Int?, g: Int?, b: Int?): Int? = if (r == null || g == null || b == null) null else
        (r shl 16) or (g shl 8) or b
    if (input[0] == '#') {
        if (input.length !in listOf(4, 7, 10, 13)) return null
        val n = (input.length - 1) / 3
        return packed(hex(input.substring(1, 1 + n)), hex(input.substring(1 + n, 1 + 2 * n)), hex(input.substring(1 + 2 * n)))
    }
    terminalX11Color(input)?.let { return it }
    if (input.length == 3 || input.length == 6) {
        val n = input.length / 3
        return packed(hex(input.substring(0, n)), hex(input.substring(n, n * 2)), hex(input.substring(n * 2)))
    }
    val intensity = input.startsWith("rgbi:")
    if (!intensity && !input.startsWith("rgb:")) return null
    val parts = input.substring(if (intensity) 5 else 4).split('/')
    if (parts.size != 3) return null
    fun channel(value: String): Int? = if (intensity) terminalColorIntensity(value) else hex(value)
    return packed(channel(parts[0]), channel(parts[1]), channel(parts[2]))
}

/** Restricted decimal fraction: no exponent/NaN, at most 15 accumulated fractional digits. */
private fun terminalColorIntensity(value: String): Int? {
    var i = if (value.startsWith('+') || value.startsWith('-')) 1 else 0
    val negative = value.startsWith('-')
    var integer = 0.0
    var digits = 0
    while (i < value.length && value[i] != '.') {
        val digit = value[i++].takeIf { it in '0'..'9' } ?: return null
        integer = integer * 10 + (digit - '0'); digits++
    }
    var fraction = 0.0
    var scale = 1.0
    if (i < value.length) {
        i++
        while (i < value.length) {
            val digit = value[i++].takeIf { it in '0'..'9' } ?: return null
            if (scale < 1_000_000_000_000_000.0) { fraction = fraction * 10 + (digit - '0'); scale *= 10 }
            digits++
        }
    }
    val magnitude = integer + fraction / scale
    val result = if (negative) -magnitude else magnitude
    return if (digits > 0 && result >= 0.0 && result <= 1.0) (result * 255).toInt() else null
}
