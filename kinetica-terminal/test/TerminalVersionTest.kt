package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalVersionTest {
    @Test fun versionQueryUsesHostMetadataAndSurvivesReset() {
        val session = TerminalSession()
        val replies = StringBuilder()
        session.onInput { replies.append(it.toByteArray().decodeToString()) }
        fun query(sequence: String = "\u001b[>q"): String {
            replies.clear()
            // Exercise the streaming parser, as the query may arrive in separate PTY reads.
            sequence.forEach { session.write(it.toString()) }
            return replies.toString()
        }
        assertEquals("\u001bP>|Kinetica\u001b\\", query())
        session.terminalVersion = "0.2.1-dev+abc123"
        assertEquals("\u001bP>|Kinetica 0.2.1-dev+abc123\u001b\\", query("\u001b[>0q"))
        session.write("\u001bc")
        assertEquals("\u001bP>|Kinetica 0.2.1-dev+abc123\u001b\\", query())
        session.terminalVersion = null
        assertEquals("\u001bP>|Kinetica\u001b\\", query())
    }

    @Test fun versionCannotInjectProtocolControlsOrUnboundedReplies() {
        val session = TerminalSession().apply { terminalVersion = "1.2.3" }
        for (invalid in listOf("", " ", "1.2.3\n", "1.2.3\u001b\\", "1.2.3\u009c", "1.2.3\u0000", "x".repeat(129))) {
            assertFailsWith<IllegalArgumentException> { session.terminalVersion = invalid }
            assertEquals("1.2.3", session.terminalVersion)
        }
    }
}
