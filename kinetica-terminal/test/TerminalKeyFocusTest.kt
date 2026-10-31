package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalKeyFocusTest {
    private fun bytes(state: TerminalKeyFocus, flags: Int = 31): String = buildString {
        state.release { append(encodeKittyKey(it, flags).orEmpty()) }
    }

    @Test fun lastKeyThenRightAndLeftModifiersInGhosttyOrder() {
        val state = TerminalKeyFocus()
        state.record(TerminalKey("Enter", shift = true, control = true, alt = true, meta = true))
        assertEquals("\u001b[13;16:3u" +
            "\u001b[57447;15:3u\u001b[57441;15:3u" +
            "\u001b[57448;11:3u\u001b[57442;11:3u" +
            "\u001b[57449;9:3u\u001b[57443;9:3u" +
            "\u001b[57450;1:3u\u001b[57444;1:3u", bytes(state))
        assertEquals("", bytes(state), "Repeated loss of focus has no pending release")
    }

    @Test fun regularReleaseRetainsOnlyModifiersAndDoesNotRepeatTheKey() {
        val state = TerminalKeyFocus()
        val key = TerminalKey("a", control = true, physicalCode = "KeyA")
        state.record(key); state.record(key.copy(action = TerminalKeyAction.RELEASE))
        assertEquals("\u001b[57448;1:3u\u001b[57442;1:3u", bytes(state))
        state.record(key.copy(control = false)); state.record(key.copy(control = false, action = TerminalKeyAction.RELEASE))
        assertEquals("", bytes(state))
        state.record(key.copy(action = TerminalKeyAction.RELEASE))
        assertEquals("", bytes(state), "Orphan release does not create held state")
    }

    @Test fun modifierOriginalIsNotRepeatedAndKeypadIdentityDoesNotLeakIntoModifiers() {
        val state = TerminalKeyFocus()
        state.record(TerminalKey("Shift", shift = true, physicalCode = "ShiftLeft"))
        assertEquals("\u001b[57441;2:3u\u001b[57447;1:3u", bytes(state))
        state.record(TerminalKey("Enter", control = true, keypad = TerminalKeypadKey.ENTER))
        assertEquals("\u001b[57414;5:3u\u001b[57448;1:3u\u001b[57442;1:3u", bytes(state))
    }

    @Test fun retainsLastEventWithNoBorrowedTextAndUsesCurrentProtocolFlags() {
        val state = TerminalKeyFocus()
        state.record(TerminalKey("b", physicalCode = "KeyB"))
        state.record(TerminalKey("A", shift = true, text = "A", unshiftedCodepoint = 97,
            physicalCode = "KeyA", baseCodepoint = 97, consumedModifiers = 1, action = TerminalKeyAction.REPEAT))
        assertEquals("\u001b[97;2:3u\u001b[57447;1:3u\u001b[57441;1:3u", bytes(state))
        state.record(TerminalKey("Enter", control = true))
        assertEquals("", bytes(state, 1), "No event-reporting flag means no releases")
        state.record(TerminalKey("Enter")); state.clear(); assertEquals("", bytes(state))
    }
}
