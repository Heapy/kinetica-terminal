package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalKittyColorTest {
    @Test fun resetsUseHostDefaultsAndOscOverridesDoNotNotifyThemeSubscribers() {
        val session = TerminalSession()
        val output = StringBuilder()
        session.onInput { output.append(it.toByteArray().decodeToString()) }
        session.configureTheme(TerminalTheme(foreground = 0x123456, background = 0xffffff, cursor = 0xabcdef))
        session.write("\u001b[?2031h\u001b]21;foreground=red;background=black;cursor=blue\u001b\\")
        assertTrue(output.isEmpty())
        session.write("\u001b[?996n\u001b]21;foreground;cursor;background;foreground=?;background=?;cursor=?\u001b\\")
        assertEquals("\u001b[?997;2n\u001b]21;foreground=rgb:12/34/56;background=rgb:ff/ff/ff;cursor=rgb:ab/cd/ef\u001b\\", output.toString())
        assertEquals(TerminalColorScheme.LIGHT, session.colorScheme)
        assertEquals(0x123456, session.theme.foreground)
        assertEquals(0xffffff, session.theme.background)
        assertEquals(0xabcdef, session.theme.cursor)
    }
}
