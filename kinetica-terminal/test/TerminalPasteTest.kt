package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalPasteTest {
    @Test fun encodingMatchesOriginalGhosttyForEveryAsciiControlAndUnicodeText() {
        val session = TerminalSession()
        val output = mutableListOf<ByteArray>()
        val subscription = session.onInput { output += it.toByteArray() }
        try {
            for (encoded in terminalPasteFixtures()) {
                val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
                val text = case.getValue("text").jsonPrimitive.content
                for (bracketed in listOf(false, true)) {
                    session.write(if (bracketed) "\u001b[?2004h" else "\u001b[?2004l")
                    val expected = case.getValue(if (bracketed) "bracketed" else "plain").jsonPrimitive.content
                    val label = "${case.getValue("name")} bracketed=$bracketed"
                    assertEquals(expected, terminalEncodePaste(text, bracketed), label)
                    output.clear(); session.paste(text)
                    assertContentEquals(expected.encodeToByteArray(), output.flatMap { it.asList() }.toByteArray(), label)
                    assertTrue(output.size <= 1, "Paste framing and text are submitted atomically")
                }
            }
        } finally { subscription.dispose() }
    }

    @Test fun keyboardLockDoesNotBlockPasteAndImeIsNotPasteEncoded() {
        val session = TerminalSession()
        val output = mutableListOf<String>()
        session.onInput { output += it.toByteArray().decodeToString() }
        session.keyboardLockAllowed = true
        session.write("\u001b[2h\u001b[?2004h")
        session.paste("a\u0003\r\nb")
        assertEquals(listOf("\u001b[200~a \r\nb\u001b[201~"), output)
        session.write("\u001b[2l")
        session.sendText("a\n\u0003b")
        assertEquals("a\n\u0003b", output.last(), "Committed IME text keeps its existing input path")
        session.write("\u001bc")
        session.paste("a\r\nb")
        assertEquals("a\r\rb", output.last(), "RIS restores unbracketed encoding")
    }
}
