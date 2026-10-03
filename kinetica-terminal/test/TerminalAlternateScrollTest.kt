package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalAlternateScrollTest {
    @Test fun wheelUsesCursorEncodingInBothDirectionsAndOnlyOnTheAlternateScreen() {
        val session = TerminalSession()
        val output = StringBuilder()
        session.onInput { output.append(it.toByteArray().decodeToString()) }
        assertFalse(session.sendAlternateScroll(3))
        session.write("\u001b[?1049h")
        assertTrue(session.sendAlternateScroll(2))
        assertTrue(session.sendAlternateScroll(-1))
        session.write("\u001b[?1h")
        assertTrue(session.sendAlternateScroll(1))
        assertTrue(session.sendAlternateScroll(-2))
        session.write("\u001b[?1049l")
        assertFalse(session.sendAlternateScroll(-3))
        assertEquals("\u001b[A\u001b[A\u001b[B\u001bOA\u001bOB\u001bOB", output.toString())
    }

    @Test fun mouseReportingAndShiftNeverAlsoSendCursorKeys() {
        val session = TerminalSession()
        val output = StringBuilder()
        session.onInput { output.append(it.toByteArray().decodeToString()) }
        session.write("\u001b[?1049h")
        assertFalse(session.sendAlternateScroll(1, shift = true))
        assertFalse(session.sendAlternateScroll(0))
        for (mode in listOf(1000, 1002, 1003)) {
            session.write("\u001b[?$mode;1006h")
            assertFalse(session.sendAlternateScroll(1))
            assertFalse(session.sendAlternateScroll(-1, shift = true))
            assertTrue(session.sendMouse(0, 0, 64))
            session.write("\u001b[?${mode}l")
        }
        assertEquals("\u001b[<64;1;1M".repeat(3), output.toString())
        assertTrue(session.sendAlternateScroll(1))
        assertTrue(output.endsWith("\u001b[A"))
    }

    @Test fun applicationsCanDisableQueryAndReenableAlternateScrollAndResetRestoresDefault() {
        val session = TerminalSession()
        val output = StringBuilder()
        session.onInput { output.append(it.toByteArray().decodeToString()) }
        session.write("\u001b[?1049h\u001b[?1007\$p")
        assertTrue(session.alternateScroll)
        session.write("\u001b[?1007l\u001b[?1007\$p")
        assertFalse(session.alternateScroll)
        assertFalse(session.sendAlternateScroll(3))
        session.write("\u001b[?1007h\u001b[?1007\$p")
        assertTrue(session.alternateScroll)
        assertTrue(session.sendAlternateScroll(-1))
        session.write("\u001b[?1007l\u001bc\u001b[?1007\$p")
        assertTrue(session.alternateScroll)
        assertFalse(session.sendAlternateScroll(1))
        assertEquals("\u001b[?1007;1\$y\u001b[?1007;2\$y\u001b[?1007;1\$y\u001b[B\u001b[?1007;1\$y", output.toString())
    }

    @Test fun extremeWheelDeltasProduceBoundedInputWithoutOverflow() {
        val session = TerminalSession()
        val output = StringBuilder()
        session.onInput { output.append(it.toByteArray().decodeToString()) }
        session.write("\u001b[?1049h")
        assertTrue(session.sendAlternateScroll(Int.MIN_VALUE))
        assertTrue(session.sendAlternateScroll(Int.MAX_VALUE))
        assertEquals("\u001b[B".repeat(64) + "\u001b[A".repeat(64), output.toString())
    }
}
