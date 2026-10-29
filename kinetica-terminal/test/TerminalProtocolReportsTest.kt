package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalProtocolReportsTest {
    @Test fun unsupportedExtensionsAreNotAdvertisedAndDoNotChangeThePen() {
        val session = TerminalSession(8, 2)
        val output = StringBuilder()
        session.onInput { output.append(it.toByteArray().decodeToString()) }
        session.write("\u001b[31m\u001b[?65535h\u001b[?65535\$pX")
        assertEquals("\u001b[?65535;0\$y", output.toString())
        assertEquals("X", session.screenLine(0).text())
        assertEquals(session.paletteColor(1), session.screenLine(0).foreground(0))
        assertEquals(0, session.screenLine(0).style(0))
        output.clear()
        session.sendKey(TerminalKey("ArrowUp"))
        assertEquals("\u001b[A", output.toString(), "Unsupported keyboard negotiation retains legacy input")
    }
}
