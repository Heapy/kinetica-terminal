@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

internal actual fun terminalClipboardRandom(bytes: ByteArray): Boolean = bytes.isEmpty() || bytes.usePinned {
    SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), it.addressOf(0)) == 0
}
