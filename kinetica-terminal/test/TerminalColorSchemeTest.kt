package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalColorSchemeTest {
    @Test fun queryAndSubscriptionsFollowHostConfigurationNotOscOverrides() {
        val s = TerminalSession()
        val output = StringBuilder()
        s.onInput { output.append(it.toByteArray().decodeToString()) }
        s.write("\u001b[?996n\u001b[996n\u001b[?2031\$p")
        assertEquals("\u001b[?997;1n\u001b[?2031;2\$y", output.toString())
        output.clear()
        s.configureTheme(TerminalTheme(background = 0xffffff))
        assertTrue(output.isEmpty())
        s.write("\u001b[?996n\u001b[?2031h\u001b[?2031\$p")
        assertEquals("\u001b[?997;2n\u001b[?2031;1\$y", output.toString())
        output.clear()
        s.configureTheme(TerminalTheme(background = 0x101010))
        assertEquals("\u001b[?997;1n", output.toString())
        output.clear()
        s.write("\u001b]11;#ffffff\u0007\u001b[?996n")
        assertEquals(0xffffff, s.theme.background)
        assertEquals("\u001b[?997;1n", output.toString())
        output.clear()
        s.write("\u001b[?1049h")
        s.configureColorScheme(TerminalColorScheme.LIGHT)
        assertEquals("\u001b[?997;2n", output.toString())
        output.clear()
        s.reset()
        s.write("\u001b[?2031\$p\u001b[?996n")
        assertEquals("\u001b[?2031;2\$y\u001b[?997;2n", output.toString())
    }

    @Test fun explicitSchemeCanDifferFromBackgroundAndReportsDoNotWaitForPresentation() {
        val s = TerminalSession(colorScheme = TerminalColorScheme.LIGHT)
        val output = StringBuilder()
        s.onInput { output.append(it.toByteArray().decodeToString()) }
        s.write("\u001b[?2031h\u001b[?2026h")
        s.configureTheme(TerminalTheme(background = 0xffffff), TerminalColorScheme.DARK)
        assertTrue(s.synchronizedOutput)
        assertEquals("\u001b[?997;1n", output.toString())
        output.clear()
        s.write("\u001b[?2031l")
        s.configureColorScheme(TerminalColorScheme.LIGHT)
        assertTrue(output.isEmpty())
    }
}
