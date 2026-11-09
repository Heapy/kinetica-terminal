package app.native.terminal

import platform.Foundation.NSHomeDirectory
import kotlin.test.*

class TerminalVersionTest {
    @Test fun bundledAndUnbundledRunsUseTheApplicationVersion() {
        assertEquals("2.3.4", terminalApplicationVersion("2.3.4"))
        assertEquals(TERMINAL_VERSION, terminalApplicationVersion(null))
        assertEquals(TERMINAL_VERSION, terminalApplicationVersion(""))
        assertEquals(TERMINAL_VERSION, terminalApplicationVersion("1.2.3\u001b\\"))
        val terminal = TerminalWindow(1, TerminalSettingsStore(null), NSHomeDirectory())
        assertEquals(terminalApplicationVersion(), terminal.session.terminalVersion)
        terminal.close {}
    }
}
