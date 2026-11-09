package app.native.terminal

import io.heapy.kinetica.terminal.TerminalSession
import kotlin.test.*

class TerminalEmojiTest {
    private val emoji = listOf("👍🏽", "🇬🇧", "👨‍👩‍👧", "👨‍👩‍👧‍👦", "👩‍💻", "❤️", "1️⃣")

    @Test fun compoundEmojiOccupyTwoCellsEvenWhenUtf8ArrivesByteByByte() = withSession { session ->
        for (text in emoji) {
            session.reset()
            for (byte in (text + "x").encodeToByteArray()) session.write(byteArrayOf(byte))
            assertEquals(text, session.screenLine(0).text(0), text)
            assertEquals(2, session.screenLine(0).width(0), text)
            assertEquals(0, session.screenLine(0).width(1), text)
            assertEquals("x", session.screenLine(0).text(2), text)
            assertEquals(3, session.cursorColumn, text)
        }
    }

    @Test fun compoundEmojiStayTogetherAtTheRightMargin() = withSession { session ->
        session.resize(6, 3)
        for (text in emoji) {
            session.reset()
            session.write("abcd")
            for (byte in text.encodeToByteArray()) session.write(byteArrayOf(byte))
            session.write("x")
            assertEquals("abcd$text", session.screenLine(0).text(), text)
            assertEquals(text, session.screenLine(0).text(4), text)
            assertEquals(2, session.screenLine(0).width(4), text)
            assertEquals("x", session.screenLine(1).text(0), text)
            assertEquals(1, session.cursorRow, text)
            assertEquals(1, session.cursorColumn, text)
        }
    }

    @Test fun applicationsCanToggleGraphemeModeAndResetRestoresTheAppDefault() = withSession { session ->
        val replies = mutableListOf<String>()
        val subscription = session.onInput { replies += it.toByteArray().decodeToString() }
        try {
            session.write("\u001b[?2027\$p")
            assertEquals("\u001b[?2027;1\$y", replies.last())
            session.write("\u001b[?2027l👍🏽")
            assertEquals(4, session.cursorColumn)
            session.write("\u001bc👍🏽\u001b[6n")
            assertTrue(session.graphemeClustering)
            assertEquals(2, session.cursorColumn)
            assertEquals("\u001b[1;3R", replies.last())
        } finally { subscription.dispose() }
    }

    private fun withSession(block: (TerminalSession) -> Unit) {
        val terminal = TerminalWindow(1, TerminalSettingsStore(null), "/tmp")
        try { block(terminal.session) } finally { terminal.close {} }
    }
}
