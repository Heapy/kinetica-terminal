package io.heapy.kinetica.terminal

import kotlin.random.Random
import kotlin.test.*

class TerminalAdversarialTest {
    @Test fun arbitraryStreamsAndResizePreserveGridInvariants() {
        val random = Random(0x565446)
        val session = TerminalSession(17, 5, scrollback = 23)
        val recent = ArrayDeque<String>()
        val operations = listOf("界", "é", "😀", "👨‍👩‍👧‍👦", "🇺🇸", "\ufe0e", "\ufe0f", "ा",
            "\u001b[?2027h", "\u001b[?2027l", "\u001b[999999999999999999D", "\u001b[999999999999A",
            "\u001b[2;4r", "\u001b[?6h", "\u001b[?6l", "\u001b[?1049h", "\u001b[?1049l",
            "\u001b[?47h", "\u001b[?47l", "\u001b[2P", "\u001b[3@", "\u001b[2X", "\r\n",
            "\u001b[2J", "\u001b7", "\u001b8", "\u001b[3L", "\u001b[3M", "\u001b[999T", "\u001b[999S",
            "\u001b[?7l", "\u001b[?7h", "\u001b[1;1H", "\u001b[1;999H")
        repeat(3000) { step ->
            val operation = when (random.nextInt(10)) {
                0 -> {
                    val columns = random.nextInt(1, 30); val rows = random.nextInt(1, 10)
                    session.resize(columns, rows); "resize $columns $rows"
                }
                1 -> {
                    val bytes = random.nextBytes(random.nextInt(1, 30))
                    session.write(bytes); "bytes ${bytes.toHexString()}"
                }
                else -> {
                    val text = operations.random(random)
                    session.write(text); "write ${text.encodeToByteArray().toHexString()}"
                }
            }
            recent.addLast("$step: $operation")
            if (recent.size > 20) recent.removeFirst()
            fun cellInvariant(condition: Boolean, message: String) {
                if (!condition) fail("$message; grid ${session.columns}x${session.rows}, cursor ${session.cursorColumn},${session.cursorRow}, modes ${session.inspectedModes().toList()}\n${recent.joinToString("\n")}\n" +
                    (0 until session.lineCount).joinToString("\n") { y ->
                        (0 until session.columns).joinToString(" ") { x -> "${session.line(y).codePoint(x).toString(16)}:${session.line(y).width(x)}" }
                    })
            }
            assertTrue(session.cursorColumn in 0 until session.columns, "cursor column at step $step")
            assertTrue(session.cursorRow in 0 until session.rows, "cursor row at step $step")
            assertTrue(session.historySize <= session.scrollback)
            for (y in 0 until session.lineCount) {
                val line = session.line(y)
                assertEquals(session.columns, line.columns)
                for (x in 0 until line.columns) {
                    when (line.width(x)) {
                        0 -> cellInvariant(x > 0 && line.width(x - 1) == 2, "orphan tail at $step [$y,$x]")
                        2 -> cellInvariant(x < line.columns - 1 && line.width(x + 1) == 0, "orphan head at $step [$y,$x]")
                        else -> assertEquals(1, line.width(x))
                    }
                }
            }
        }
    }

    @Test fun combiningFloodHasBoundedStorageAndRecovers() {
        val session = TerminalSession(10, 2)
        session.write("x" + "́".repeat(100_000) + "A")
        assertTrue(session.screenLine(0).text(0).length <= 129)
        assertEquals("A", session.screenLine(0).text(1))
        assertEquals(2, session.cursorColumn)
    }

    @Test fun narrowReflowEvictsHistoryAndKeepsNewestText() {
        val session = TerminalSession(4096, 2, scrollback = 3)
        session.write("x".repeat(20_474) + "ABCDEF")
        session.resize(1, 2)
        assertEquals(3, session.historySize)
        assertEquals("BCDEF", (0 until session.lineCount).joinToString("") { session.line(it).text() })
        session.resize(4096, 2)
        assertEquals("BCDEF", session.screenLine(0).text())
        assertEquals(0, session.historySize)
    }

    @Test fun observerRemovalDuringDeliveryDoesNotLoseRemainingObservers() {
        val session = TerminalSession()
        var first = 0; var second = 0
        lateinit var subscription: TerminalDisposable
        subscription = session.observe { first++; subscription.dispose() }
        session.observe { second++ }
        session.write("a"); session.write("b")
        assertEquals(1, first); assertEquals(2, second)
    }
}
