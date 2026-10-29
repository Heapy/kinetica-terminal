package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalGraphicsApcTest {
    private class Stream(limit: Int = 4096) {
        val commands = mutableListOf<TerminalGraphicsCommand>()
        val text = StringBuilder()
        val endings = mutableListOf<Int>()
        val apc = TerminalGraphicsApc(limit, commands::add)
        val parser = VtParser(object : VtParser.Handler {
            override fun print(code: Int) { text.append(code.toChar()) }
            override fun execute(code: Int) {}
            override fun escape(final: Int, intermediates: Int) {}
            override fun csi(final: Int, command: VtParser) {}
            override fun string(kind: Int, value: String, terminator: Int) {}
            override fun apcStart() = apc.start()
            override fun apcPut(code: Int) = apc.feed(code)
            override fun apcEnd(terminator: Int) { endings += terminator; apc.end(terminator) }
        })
        fun write(value: String) = value.encodeToByteArray().forEach { parser.accept(it.toInt() and 255) }
    }

    @Test fun allIntroducersUseTheSharedApcStateAndNeverPrintPayloads() {
        for (start in listOf("\u001b_", "\u001b^", "\u001bX")) {
            val stream = Stream()
            stream.write("before${start}Ga=t,i=12;YQ==\u001b\\after")
            assertEquals("beforeafter", stream.text.toString())
            assertEquals(12, stream.commands.single().imageId)
            assertContentEquals(byteArrayOf(97), stream.commands.single().data)
        }
        for (start in listOf(0x98, 0x9e, 0x9f)) {
            val stream = Stream()
            // C1 is interpreted in byte-oriented headers; ground text is UTF-8.
            stream.parser.accept(0x1b)
            stream.parser.accept(start); stream.write("Ga=p,i=7"); stream.parser.accept(0x9c)
            assertEquals(7, stream.commands.single().imageId)
        }
    }

    @Test fun onlyEscAndStCompleteCommandsWhileCancellationAndResetDiscardThem() {
        for (end in listOf(0x18, 0x1a, 0x1b, 0x80, 0x85, 0x90, 0x9b, 0x9c, 0x9d)) {
            val stream = Stream()
            stream.write("\u001b_Gi=31;YQ==")
            assertTrue(stream.commands.isEmpty(), "An incomplete APC must not execute")
            stream.parser.accept(end)
            assertEquals(listOf(end), stream.endings)
            if (end == 0x1b || end == 0x9c) assertEquals(31, stream.commands.single().imageId)
            else assertTrue(stream.commands.isEmpty(), "Abort control $end must not execute the command")
            assertEquals(0, stream.apc.retainedPayloadBytes)
        }
        val stream = Stream()
        stream.write("\u001b_Gi=31;YQ=="); stream.parser.reset()
        assertTrue(stream.commands.isEmpty()); assertEquals(listOf(-1), stream.endings)
        assertEquals(0, stream.apc.retainedPayloadBytes)
        stream.write("\u001b_Gi=32;Yg==\u001b\\")
        assertEquals(32, stream.commands.single().imageId)
    }

    @Test fun embeddedC0IsPayloadHighBytesAreIgnoredAndSameStateC1DoesNotRestart() {
        val stream = Stream()
        stream.write("\u001b_Gi=4;Y")
        for (byte in listOf(0x98, 0x9e, 0x9f, 0xa0, 0xff)) stream.parser.accept(byte)
        stream.write("Q==\u001b\\")
        assertContentEquals(byteArrayOf(97), stream.commands.single().data)
        stream.write("\u001b_Gi=9;YQ==\u0007\u001b\\")
        assertEquals(1, stream.commands.size, "BEL is APC payload, so this Base64 is invalid")
        assertEquals("", stream.text.toString())
    }

    @Test fun unsupportedAndOversizedSequencesStayBoundedAndRecoverAtTheNextSequence() {
        val stream = Stream(16)
        stream.write("\u001b_Z" + "a".repeat(100_000))
        assertEquals(0, stream.apc.retainedPayloadBytes)
        stream.write("\u001b\\\u001b_Gi=1;" + "A".repeat(100_000))
        assertEquals(0, stream.apc.retainedPayloadBytes)
        stream.write("\u001b\\\u001b_Gi=2;YQ==\u001b\\ok")
        assertEquals(2, stream.commands.single().imageId)
        assertEquals("ok", stream.text.toString())
    }
}
