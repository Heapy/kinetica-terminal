package io.heapy.kinetica.terminal

import kotlin.test.*

class BrowserClipboardEntropyTest {
    @Test fun browserAdapterUsesTypedBytesAndFailsClosedWhenCryptoIsMissingOrThrows() {
        val global: dynamic = js("globalThis")
        val descriptor: dynamic = js("Object.getOwnPropertyDescriptor(globalThis, 'crypto')")
        fun replace(value: dynamic) {
            val property: dynamic = js("({configurable:true})")
            property.value = value
            global.Object.defineProperty(global, "crypto", property)
        }
        try {
            replace(null)
            assertNull(generateKittyClipboardPassword())
            replace(js("({})"))
            assertNull(generateKittyClipboardPassword())
            val crypto: dynamic = js("({})")
            var calls = 0
            var buffer: ByteArray? = null
            crypto.getRandomValues = { bytes: ByteArray ->
                calls++; buffer = bytes
                assertEquals(44, bytes.size)
                assertTrue(js("bytes instanceof Int8Array") as Boolean)
                bytes.fill(171.toByte())
                bytes
            }
            replace(crypto)
            assertEquals("2".repeat(22), generateKittyClipboardPassword())
            assertEquals(1, calls)
            assertTrue(assertNotNull(buffer).all { it == 0.toByte() })
            crypto.getRandomValues = js("(function() { throw new Error('Entropy unavailable'); })")
            assertNull(generateKittyClipboardPassword())
            crypto.getRandomValues = js("(function() { throw new DOMException('Entropy unavailable', 'OperationError'); })")
            assertNull(generateKittyClipboardPassword())
        } finally {
            if (descriptor == null) js("delete globalThis.crypto")
            else global.Object.defineProperty(global, "crypto", descriptor)
        }
    }
}
