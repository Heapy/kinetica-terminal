@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import io.heapy.kinetica.terminal.keyboard.kinetica_copy_keyboard_input_source_id
import kotlinx.cinterop.toKString
import platform.posix.free

internal fun terminalNativeKeyboardLayoutId(): String? {
    val identifier = kinetica_copy_keyboard_input_source_id() ?: return null
    return try { identifier.toKString() } finally { free(identifier) }
}
