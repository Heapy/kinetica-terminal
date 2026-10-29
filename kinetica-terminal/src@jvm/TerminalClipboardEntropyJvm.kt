package io.heapy.kinetica.terminal

import java.security.SecureRandom

private val clipboardRandom by lazy { SecureRandom() }

internal actual fun terminalClipboardRandom(bytes: ByteArray): Boolean = try {
    clipboardRandom.nextBytes(bytes)
    true
} catch (_: Exception) { false }
