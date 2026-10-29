package io.heapy.kinetica.terminal

/**
 * Resolve the last OSC 7 report for a host that wants a local directory. Only `localhost`
 * and [localHostname] authorities are accepted; remote reports remain available through
 * [TerminalSession.workingDirectoryUri]. This does not check filesystem existence.
 * `file` paths are percent-decoded as UTF-8; `kitty-shell-cwd` paths are literal.
 */
public fun TerminalSession.localWorkingDirectory(localHostname: String): String? =
    localTerminalDirectory(workingDirectoryUri, localHostname)

internal fun localTerminalDirectory(uri: String?, localHostname: String): String? {
    if (uri == null) return null
    val rawPath = uri.startsWith("kitty-shell-cwd://")
    val prefix = when {
        rawPath -> "kitty-shell-cwd://"
        uri.startsWith("file://") -> "file://"
        else -> return null
    }
    val slash = uri.indexOf('/', prefix.length)
    if (slash < 0) return null
    val host = decodeDirectoryComponent(uri.substring(prefix.length, slash)) ?: return null
    if (host.isEmpty() || (host != "localhost" && host != localHostname)) return null
    val path = if (rawPath) uri.substring(slash) else {
        val end = uri.indexOfAny(charArrayOf('?', '#'), slash).let { if (it < 0) uri.length else it }
        decodeDirectoryComponent(uri.substring(slash, end)) ?: return null
    }
    return path.takeIf { it.startsWith('/') && '\u0000' !in it }
}

private fun decodeDirectoryComponent(value: String): String? {
    val input = value.encodeToByteArray()
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
    return try { output.decodeToString(0, written, throwOnInvalidSequence = true) }
    catch (_: CharacterCodingException) { null }
}
