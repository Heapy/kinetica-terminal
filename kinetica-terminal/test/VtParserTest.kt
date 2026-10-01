// Behavioral cases adapted from Ghostty Parser.zig / UTF8Decoder.zig (MIT),
// commit 4da7523faba68ccb4042ea20585817098a51c015. See third-party/GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import kotlin.test.*

class VtParserTest {
    private class Capture : VtParser.Handler {
        val printed = StringBuilder()
        val controls = mutableListOf<Int>()
        val escapes = mutableListOf<Pair<Int, Int>>()
        val commands = mutableListOf<Command>()
        val strings = mutableListOf<Triple<Int, String, Int>>()
        override fun print(code: Int) { printed.append(codePointString(code)) }
        override fun execute(code: Int) { controls += code }
        override fun escape(final: Int, intermediates: Int) { escapes += final to intermediates }
        override fun csi(final: Int, command: VtParser) {
            commands += Command(final.toChar(), command.prefix, command.intermediates,
                command.parameters.take(command.count).map { it.coerceAtLeast(0) }, command.colon.take(command.count))
        }
        override fun string(kind: Int, value: String, terminator: Int) { strings += Triple(kind, value, terminator) }
        fun parse(value: String) { val parser = VtParser(this); value.forEach { parser.accept(it.code) } }
    }
    private data class Command(val final: Char, val prefix: Int, val intermediates: Int, val params: List<Int>, val colon: List<Boolean>)

    @Test fun ghosttyEscIntermediate() {
        val capture = Capture().apply { parse("\u001b(B") }
        assertEquals(listOf('B'.code to '('.code), capture.escapes)
        assertEquals("", capture.printed.toString())
    }

    @Test fun ghosttyCsiParametersAndColonSeparators() {
        val cases = listOf(
            "H" to emptyList(), "1;4H" to listOf(1, 4),
            "38:2m" to listOf(38, 2), "38:5:1;48:5:0m" to listOf(38, 5, 1, 48, 5, 0),
            "48:2:240:143:104m" to listOf(48, 2, 240, 143, 104), "4:3m" to listOf(4, 3),
            "58:2::240:143:104m" to listOf(58, 2, 0, 240, 143, 104),
            ";4:3;38;2;175;175;215;58:2::190:80:70m" to listOf(0, 4, 3, 38, 2, 175, 175, 215, 58, 2, 0, 190, 80, 70),
            "4:3;38;2;51;51;51;48;2;170;170;170;58;2;255;97;136m" to listOf(4, 3, 38, 2, 51, 51, 51, 48, 2, 170, 170, 170, 58, 2, 255, 97, 136),
        )
        for ((input, params) in cases) {
            val command = Capture().apply { parse("\u001b[$input") }.commands.single()
            assertEquals(params, command.params, input)
            val separators = input.filter { it == ':' || it == ';' }.map { it == ':' }
            assertEquals(if (params.isEmpty()) emptyList() else separators + false, command.colon, input)
        }
    }

    @Test fun ghosttyPrivatePrefixAndIntermediateAreIndependent() {
        val capture = Capture().apply { parse("\u001b[?2026\$p\u001b[3 q") }
        assertEquals(Command('p', '?'.code, '$'.code, listOf(2026), listOf(false)), capture.commands[0])
        assertEquals(Command('q', 0, ' '.code, listOf(3), listOf(false)), capture.commands[1])
    }

    @Test fun invalidSequencesCannotTurnIntoDifferentCommands() {
        for (input in listOf("38:2h", "1?2H", "1 2H", ":2m", "?1?2h", "1" + ";1".repeat(100) + "m")) {
            val capture = Capture().apply { parse("\u001b[$input!") }
            assertTrue(capture.commands.isEmpty(), input)
            assertEquals("!", capture.printed.toString(), input)
        }
    }

    @Test fun c0ExecutesInsideCsiWhileDelIsIgnored() {
        val capture = Capture().apply { parse("\u001b[1\u0008;2\u007fH") }
        assertEquals(listOf(8), capture.controls)
        assertEquals(listOf(1, 2), capture.commands.single().params)
    }

    @Test fun cancelAndEscapeRecoverFromEveryCommandFamily() {
        for (start in listOf("\u001b[12;", "\u001b]2;title", "\u001bP1;2qdata", "\u001b_ignored")) {
            for (cancel in listOf('\u0018', '\u001a')) {
                val capture = Capture().apply { parse(start + cancel + "OK") }
                assertEquals("OK", capture.printed.toString())
                if (start.startsWith("\u001bP"))
                    assertEquals(listOf(Triple(VtParser.DCS, "1;2qdata", cancel.code)), capture.strings)
                else assertTrue(capture.strings.isEmpty())
            }
            val capture = Capture().apply { parse(start + "\u001b[H!") }
            assertEquals('H', capture.commands.single().final)
            assertEquals("!", capture.printed.toString())
        }
    }

    @Test fun boundedStringsConsumeOversizedPayloadUntilTerminator() {
        for (start in listOf("\u001b]2;", "\u001bP\$q", "\u001b_")) {
            val capture = Capture().apply { parse(start + "x".repeat(100_000) + "\u001b\\OK") }
            assertTrue(capture.strings.isEmpty())
            assertEquals("OK", capture.printed.toString())
        }
    }

    @Test fun dcsUnicodeAndIgnoredHeadersNeverStartCsiInsidePayload() {
        for (header in listOf("\u001bPq", "\u001bP:bad")) {
            val capture = Capture().apply { parse(header + "Ü\u009b31m\u001b\\OK") }
            assertTrue(capture.commands.isEmpty())
            assertEquals("OK", capture.printed.toString())
        }
    }

    @Test fun ghosttyInvalidUtf8MaximalSubparts() {
        val bytes = byteArrayOf(0xf0.toByte(), 0x9f.toByte()) + "😄".encodeToByteArray() +
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())
        for (split in 0..bytes.size) {
            val terminal = TerminalSession(20, 2)
            terminal.write(bytes, 0, split); terminal.write(bytes, split)
            assertEquals("�😄���", terminal.screenLine(0).text())
        }
    }

    @Test fun invalidUtf8BoundaryRangesDoNotSwallowFollowingText() {
        for (bytes in listOf(byteArrayOf(0xe0.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()))) {
            val session = TerminalSession(20, 2)
            bytes.forEach { session.write(byteArrayOf(it)) }; session.write("A")
            assertEquals("�".repeat(bytes.size) + "A", session.screenLine(0).text())
        }
    }

    @Test fun privateUnknownCommandsCannotExecuteAnsiCommands() {
        val terminal = TerminalSession(20, 2)
        terminal.write("\u001b[?3g\tA")
        assertEquals("A", terminal.screenLine(0).text(8))
        terminal.write("\u001b[?2X")
        assertEquals("A", terminal.screenLine(0).text(8))
    }

    @Test fun utf8StringAndByteWritesAgreeInsideControlHeaders() {
        for (prefix in listOf("\u001b", "\u001b ", "\u001b[31", "\u001bP1;", "\u001b_")) {
            for (value in listOf("\u0080", "Ü", "\u00a0", "界", "😀")) {
                val text = prefix + value + "mABC\u0018!"
                val bytes = TerminalSession(20, 3)
                bytes.write(text.encodeToByteArray())
                for (split in 0..text.length) {
                    val string = TerminalSession(20, 3)
                    var changes = 0
                    string.observe { changes++ }
                    string.write(text.substring(0, split)); string.write(text.substring(split))
                    assertEquals(2, changes, "String writes must not publish per-byte nested damage")
                    assertEquals(bytes.cursorColumn to bytes.cursorRow, string.cursorColumn to string.cursorRow)
                    for (row in 0 until 3) for (column in 0 until 20) {
                        assertEquals(bytes.screenLine(row).text(column), string.screenLine(row).text(column),
                            "prefix=${prefix.encodeToByteArray().toHexString()} value=$value split=$split")
                        assertEquals(bytes.screenLine(row).foreground(column), string.screenLine(row).foreground(column))
                    }
                }
            }
        }
    }
}
