// Default URL/path grammar adapted from Ghostty src/config/url.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Character classes are generated from Ghostty's pinned Oniguruma; see third-party notices.
package io.heapy.kinetica.terminal

internal data class TerminalLinkMatch(val start: Int, val end: Int)

/** UTF-16 offsets, like Kotlin strings; Unicode classification is pinned rather than platform-dependent. */
internal fun terminalLinkMatches(text: String): List<TerminalLinkMatch> = TerminalLinkMatcher(text).matches()

private class TerminalLinkMatcher(private val text: String) {
    private var environmentFailureEnd = 0
    private val dots = text.indices.filter { text[it] == '.' }.toIntArray()
    private var pathStart = -1
    private var pathStop = -1

    fun matches(): List<TerminalLinkMatch> = buildList {
        var start = 0
        while (start < text.length) {
            val end = scheme(start).takeIf { it >= 0 } ?: rooted(start).takeIf { it >= 0 } ?: relative(start)
            if (end > start) { add(TerminalLinkMatch(start, end)); start = end }
            else start = next(start)
        }
    }

    private fun scheme(start: Int): Int {
        val prefix = schemes.firstOrNull { text.startsWith(it, start) } ?: return -1
        val body = start + prefix.length
        var end = body
        while (end < text.length) {
            if (at(end) == '[') {
                var x = end + 1
                while (at(x) == ':' || at(x) in '0'..'9' || at(x) in 'a'..'f' || at(x) in 'A'..'F') x++
                if (x == end + 1 || at(x) != ']') break
                end = x + 1
                if (at(end) == ':' && at(end + 1) in '0'..'9') {
                    end++
                    while (at(end) in '0'..'9') end++
                }
            } else {
                val before = end
                while (schemeChar(end)) end = next(end)
                if (end == before) break
                if (at(end) == '(' || at(end) == '[') {
                    var x = end + 1
                    while (word(x)) x = next(x)
                    if (at(x) == ')' || at(x) == ']') end = x + 1
                }
            }
        }
        while (end > body && (at(end - 1) == ',' || at(end - 1) == '.')) end--
        return if (end > body) end else -1
    }

    private fun rooted(start: Int): Int {
        fun suffix(prefix: String): Int = if (text.startsWith(prefix, start)) pathSuffix(start + prefix.length) else -1
        suffix("../").takeIf { it >= 0 }?.let { return it }
        suffix("./").takeIf { it >= 0 }?.let { return it }
        if (!word(previous(start))) suffix("~/").takeIf { it >= 0 }?.let { return it }
        val environment = environmentPrefix(start)
        if (environment >= 0) pathSuffix(environment).takeIf { it >= 0 }?.let { return it }
        if (at(start) == '.' && classWord(start + 1)) {
            val end = segmentEnd(start + 1)
            if (at(end) == '/') pathSuffix(end + 1).takeIf { it >= 0 }?.let { return it }
        }
        val previous = previous(start)
        if (at(start) == '/' && at(start + 1) != '/' && !classWord(previous) && at(previous) != '~' && at(previous) != '/')
            return pathSuffix(start + 1)
        return -1
    }

    private fun environmentPrefix(start: Int): Int {
        if (classWord(start) && start < environmentFailureEnd) return -1
        var end = start
        var inspected = start
        while (classWord(end)) {
            val segment = segmentEnd(end)
            inspected = segment
            if (at(segment) != '/') break
            end = segment + 1
        }
        if (at(end) != '$' || word(previous(end)) || !(at(end + 1) in 'A'..'Z' || at(end + 1) in 'a'..'z' || at(end + 1) == '_')) {
            environmentFailureEnd = maxOf(environmentFailureEnd, inspected)
            return -1
        }
        end += 2
        while (word(end)) end = next(end)
        return if (at(end) == '/') end + 1 else -1
    }

    private fun pathSuffix(start: Int): Int {
        var end = pathEnd(start)
        if (end == start) return -1
        val dotted = dotBetween(start, end)
        var segmentStart = start
        var beforeSpace = start
        while (at(end) == ' ' && at(end - 1) != ':') {
            val from = end + 1
            if (text.startsWith("/", from) || text.startsWith("./", from) || text.startsWith("../", from) || text.startsWith("~/", from)) break
            var schemeEnd = from
            while (word(schemeEnd)) schemeEnd = next(schemeEnd)
            if (schemeEnd > from && text.startsWith("://", schemeEnd)) break
            var stop = pathEnd(from)
            if (dotted) {
                while (stop > from && at(stop - 1) != '/' && at(stop - 1) != '.') stop = previous(stop)
            }
            if (stop == from) break
            beforeSpace = end; segmentStart = from; end = stop
        }
        while (end > segmentStart && at(end - 1) == ':') end--
        if (end == segmentStart && segmentStart != start) end = beforeSpace
        return if (end > start) end else -1
    }

    private fun relative(start: Int): Int {
        if (!classWord(start) || word(previous(start))) return -1
        var before = previous(start)
        while (digit(before)) before = previous(before)
        if (at(before) == '$') return -1
        val prefix = segmentEnd(start)
        if (at(prefix) != '/') return -1
        val body = prefix + 1
        var end = pathEnd(body)
        if (end == body || !dotBetween(start, end)) return -1
        while (end > body && at(end - 1) == ':') end--
        return if (end > body) end else -1
    }

    private fun segmentEnd(start: Int): Int {
        var end = start
        while (classWord(end) || at(end) == '-' || at(end) == '.') end = next(end)
        return end
    }
    private fun pathEnd(start: Int): Int {
        if (start >= pathStart && start < pathStop) return pathStop
        var end = start
        while (pathChar(end)) end = next(end)
        if (end > start) { pathStart = start; pathStop = end }
        return end
    }
    private fun dotBetween(start: Int, end: Int): Boolean {
        var low = 0
        var high = dots.size
        while (low < high) {
            val middle = (low + high) / 2
            if (dots[middle] < start) low = middle + 1 else high = middle
        }
        return low < dots.size && dots[low] < end
    }
    private fun pathChar(index: Int): Boolean = classWord(index) || at(index) in "-.~:/?#@!$&*+;=%"
    private fun schemeChar(index: Int): Boolean = pathChar(index) || at(index) == ','
    private fun at(index: Int): Char = if (index in text.indices) text[index] else '\u0000'
    private fun next(index: Int): Int = index + if (at(index).isHighSurrogate() && at(index + 1).isLowSurrogate()) 2 else 1
    private fun previous(index: Int): Int = index - if (at(index - 1).isLowSurrogate() && at(index - 2).isHighSurrogate()) 2 else 1
    private fun code(index: Int): Int {
        if (index !in text.indices) return -1
        val first = text[index].code
        return if (text[index].isHighSurrogate() && at(index + 1).isLowSurrogate())
            0x10000 + ((first - 0xd800) shl 10) + text[index + 1].code - 0xdc00 else first
    }
    private fun word(index: Int): Boolean = contains(terminalLinkWordRanges, code(index))
    private fun classWord(index: Int): Boolean = contains(terminalLinkClassWordRanges, code(index))
    private fun digit(index: Int): Boolean = contains(terminalLinkDigitRanges, code(index))
    private fun contains(ranges: IntArray, value: Int): Boolean {
        var low = 0
        var high = ranges.size / 2
        while (low < high) {
            val middle = (low + high) / 2
            if (value < ranges[middle * 2]) high = middle
            else if (value >= ranges[middle * 2 + 1]) low = middle + 1
            else return true
        }
        return false
    }
}

private val schemes = listOf("http://", "https://", "mailto:", "ftp://", "file:", "ssh:", "git://", "ssh://",
    "tel:", "magnet:", "ipfs://", "ipns://", "gemini://", "gopher://", "news:")
