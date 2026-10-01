package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalProtocolReportsTest {
    @Test fun unsupportedExtensionsAreNotAdvertisedAndDoNotChangeThePen() {
        val session = TerminalSession(8, 2)
        val output = StringBuilder()
        session.onInput { output.append(it.toByteArray().decodeToString()) }
        session.write("\u001b[31m\u001b[?69h\u001b[?2031h\u001b[?2048h" +
            "\u001b[?69\$p\u001b[?2031\$p\u001b[?2048\$p\u001b[?u\u001b[>3u\u001b[?u\u001b[>4;2mX")
        assertEquals("\u001b[?69;0\$y\u001b[?2031;0\$y\u001b[?2048;0\$y", output.toString())
        assertEquals("X", session.screenLine(0).text())
        assertEquals(session.paletteColor(1), session.screenLine(0).foreground(0))
        assertEquals(0, session.screenLine(0).style(0))
        output.clear()
        session.sendKey(TerminalKey("ArrowUp"))
        assertEquals("\u001b[A", output.toString(), "Unsupported keyboard negotiation retains legacy input")
    }
}
