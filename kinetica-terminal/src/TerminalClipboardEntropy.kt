// OTP encoding follows Ghostty 1.4.0 terminal/kitty/clipboard_grants.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
package io.heapy.kinetica.terminal

/** Fill from the platform CSPRNG. Failure must never fall back to predictable randomness. */
internal expect fun terminalClipboardRandom(bytes: ByteArray): Boolean

internal fun generateKittyClipboardPassword(random: (ByteArray) -> Boolean = ::terminalClipboardRandom): String? {
    val alphabet = "23456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
    val limit = 256 / alphabet.length * alphabet.length
    val raw = ByteArray(44)
    val password = StringBuilder(22)
    try {
        while (password.length < 22) {
            if (!random(raw)) return null
            for (byte in raw) {
                val value = byte.toInt() and 255
                if (value >= limit) continue
                password.append(alphabet[value % alphabet.length])
                if (password.length == 22) break
            }
        }
        return password.toString()
    } catch (_: Exception) { return null }
    finally { raw.fill(0) }
}
