package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalPasteConfirmationTest {
    @Test fun policiesMatchOriginalGhosttySafetyPredicates() {
        for (encoded in terminalPasteFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val text = case.getValue("text").jsonPrimitive.content
            for (bracketed in listOf(false, true)) for (trusted in listOf(false, true)) {
                val field = if (!trusted) "safe" else if (bracketed) "safeBracketed" else "safePlain"
                val unsafe = !case.getValue(field).jsonPrimitive.boolean
                assertEquals(unsafe, TerminalPastePolicy(bracketedSafe = trusted).requiresConfirmation(text, bracketed),
                    "${case.getValue("name")} bracketed=$bracketed trusted=$trusted")
                assertFalse(TerminalPastePolicy(protection = false, bracketedSafe = trusted).requiresConfirmation(text, bracketed))
            }
        }
    }

    private class Harness {
        val session = TerminalSession()
        val output = mutableListOf<String>()
        var accepted = 0
        var available = true
        class Dialog(val text: String, val answer: (Boolean) -> Unit) { var disposed = false }
        val dialogs = mutableListOf<Dialog>()
        val paste = TerminalClipboardPaste(session, { available }, { text, answer ->
            val dialog = Dialog(text, answer); dialogs += dialog
            // A host may deliver its callback while its dialog is being dismissed.
            TerminalDisposable { dialog.disposed = true; answer(true) }
        }, { accepted++ })
        init { session.onInput { output += it.toByteArray().decodeToString() } }
        fun request(text: String, policy: TerminalPastePolicy = TerminalPastePolicy()) = paste.paste(text, policy)
    }

    @Test fun cancellationAndReplacementDoNotPasteOrChangeViewport() = with(Harness()) {
        request("first\n"); val first = dialogs.single()
        assertEquals("first\n", first.text); assertTrue(output.isEmpty()); assertEquals(0, accepted)
        request("second\r"); val second = dialogs.last()
        assertTrue(first.disposed); first.answer(true)
        assertTrue(output.isEmpty()); assertEquals(0, accepted)
        second.answer(false); second.answer(true)
        assertTrue(second.disposed); assertTrue(output.isEmpty()); assertEquals(0, accepted)
        request("third\n"); request("safe")
        dialogs.last().answer(true)
        assertEquals(listOf("safe"), output); assertEquals(1, accepted)
        request("fourth\n"); request("")
        dialogs.last().answer(true)
        assertEquals(listOf("safe"), output)
        request("fifth\n"); paste.cancel(); dialogs.last().answer(true)
        assertEquals(listOf("safe"), output)
    }

    @Test fun approvalIsOneShotAndEncodesUsingCurrentModeAndOriginalText() = with(Harness()) {
        request("a\u001b[201~b\r\n")
        val dialog = dialogs.single()
        assertEquals("a\u001b[201~b\r\n", dialog.text)
        session.write("\u001b[?2004h")
        dialog.answer(true); dialog.answer(true)
        assertEquals(listOf("\u001b[200~a [201~b\r\n\u001b[201~"), output)
        assertEquals(1, accepted)
        request("x\n", TerminalPastePolicy(bracketedSafe = false))
        session.write("\u001bc")
        dialogs.last().answer(true)
        assertEquals("x\r", output.last(), "RIS during the prompt restores plain encoding")
    }

    @Test fun safeBracketedAndDisabledProtectionBypassPromptButNotEncoding() = with(Harness()) {
        session.write("\u001b[?2004h")
        request("a\r\nb")
        request("\u001b[201~", TerminalPastePolicy(protection = false))
        assertTrue(dialogs.isEmpty())
        assertEquals(listOf("\u001b[200~a\r\nb\u001b[201~", "\u001b[200~ [201~\u001b[201~"), output)
        request("\u001b[201~")
        assertEquals(1, dialogs.size); assertEquals(2, accepted)
        paste.dispose()
    }

    @Test fun detachedAndDisposedViewsRejectLateCallbacks() = with(Harness()) {
        request("one\n"); available = false; dialogs.last().answer(true)
        assertTrue(output.isEmpty()); assertEquals(0, accepted)
        request("safe"); assertEquals(1, dialogs.size)
        available = true; request("two\n"); paste.dispose()
        dialogs.last().answer(true); request("safe"); request("three\n")
        assertTrue(dialogs.all { it.disposed }); assertEquals(2, dialogs.size)
        assertTrue(output.isEmpty()); assertEquals(0, accepted)
    }

    @Test fun synchronousPresenterIsDisposedAndCannotCompleteTwice() {
        val session = TerminalSession()
        val output = mutableListOf<String>()
        session.onInput { output += it.toByteArray().decodeToString() }
        var disposals = 0
        val paste = TerminalClipboardPaste(session, { true }, { _, answer ->
            answer(true); answer(false)
            TerminalDisposable { disposals++; answer(true) }
        }, {})
        paste.paste("hello\n", TerminalPastePolicy()); paste.dispose()
        assertEquals(listOf("hello\r"), output); assertEquals(1, disposals)
    }
}
