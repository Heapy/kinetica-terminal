package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalWorkingDirectoryTest {
    @Test fun reportsAreImmediateAndRepeatButResetOnlyClearsState() {
        val session = TerminalSession()
        val events = mutableListOf<String?>()
        val observation = session.onWorkingDirectory { value ->
            assertEquals(value, session.workingDirectoryUri)
            events += value
        }
        session.write("\u001b[?2026h\u001b]7;file://localhost/tmp\u0007")
        assertTrue(session.synchronizedOutput)
        session.write("\u001b]7;file://localhost/tmp\u001b\\\u001b]7;\u0007")
        assertEquals(listOf("file://localhost/tmp", "file://localhost/tmp", null), events)
        session.write("\u001b]7;file://localhost/after\u0007")
        session.reset()
        assertNull(session.workingDirectoryUri)
        assertEquals(4, events.size)
        observation.dispose()
        session.write("\u001b]7;file://localhost/detached\u0007")
        assertEquals(4, events.size)
        assertEquals("/detached", session.localWorkingDirectory("machine"))
    }

    @Test fun resolvesOnlyExplicitLocalAuthoritiesAndAbsolutePaths() {
        assertEquals("/a b/я+z", localTerminalDirectory("file://localhost/a%20b/%D1%8F+z", "machine"))
        assertEquals("/tmp", localTerminalDirectory("file://machine/tmp", "machine"))
        assertEquals("/tmp", localTerminalDirectory("file://local%68ost/tmp", "machine"))
        assertEquals("/tmp", localTerminalDirectory("file://localhost/tmp?query#fragment", "machine"))
        assertEquals("/a%20b?#raw", localTerminalDirectory("kitty-shell-cwd://localhost/a%20b?#raw", "machine"))
        for (uri in listOf(null, "", "file:///tmp", "file://remote/tmp", "file://localhost",
            "file://user@localhost/tmp", "file://localhost:22/tmp", "file://localhost.evil/tmp",
            "https://localhost/tmp", "/tmp", "file://localhost/%00", "file://localhost/%FF",
            "file://localhost/%", "file://localhost/%xz", "kitty-shell-cwd://localhost/a\u0000b")) {
            assertNull(localTerminalDirectory(uri, "machine"), uri)
        }
    }
}
