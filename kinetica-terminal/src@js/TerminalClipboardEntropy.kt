package io.heapy.kinetica.terminal

internal actual fun terminalClipboardRandom(bytes: ByteArray): Boolean = try {
    val crypto: dynamic = js("globalThis.crypto")
    if (crypto?.getRandomValues == null) false else {
        crypto.getRandomValues(bytes)
        true
    }
} catch (_: Throwable) { false } // Web Crypto throws JavaScript errors, not Kotlin Exception subclasses.
