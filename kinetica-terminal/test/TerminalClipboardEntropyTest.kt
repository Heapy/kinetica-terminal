package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class TerminalClipboardEntropyTest {
    @Test fun rejectionSamplingAndFailuresMatchOriginalGhostty() {
        var checked = 0
        for (encoded in terminalKittyClipboardCoreFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val chunks = case["otp"]?.jsonArray?.map { it.jsonPrimitive.content.hexToByteArray() } ?: continue
            var calls = 0
            var raw: ByteArray? = null
            val password = generateKittyClipboardPassword { bytes ->
                assertEquals(44, bytes.size)
                raw = bytes
                val source = chunks.getOrNull(calls++)
                if (source == null) false else { source.copyInto(bytes); true }
            }
            val name = case.getValue("name").jsonPrimitive.content
            assertEquals(case.getValue("result").jsonPrimitive.contentOrNull, password, name)
            assertEquals(case.getValue("calls").jsonPrimitive.int, calls, name)
            assertTrue(assertNotNull(raw).all { it == 0.toByte() }, "Entropy buffer must be wiped: $name")
            checked++
        }
        assertEquals(324, checked)
    }

    @Test fun thrownEntropyFailureCannotReturnAPartialPassword() {
        var calls = 0
        var raw: ByteArray? = null
        assertNull(generateKittyClipboardPassword { bytes ->
            raw = bytes
            if (calls++ > 0) error("Entropy unavailable")
            bytes.fill(-1); bytes[0] = 0
            true
        })
        assertEquals(2, calls)
        assertTrue(assertNotNull(raw).all { it == 0.toByte() })
    }

    @Test fun platformSecureSourceProducesValidFreshPasswords() {
        val passwords = List(64) { assertNotNull(generateKittyClipboardPassword()) }
        val alphabet = "23456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
        assertTrue(passwords.all { it.length == 22 && it.all { char -> char in alphabet } })
        // Smoke check for a disconnected/constant platform adapter, not an entropy audit.
        assertEquals(passwords.size, passwords.toSet().size)
    }
}
