package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalBackpressureTest {
    private val paletteRequest = "\u001b]4;" + List(1023) { "0;?" }.joinToString(";") + "\u001b\\"
    private val paletteReply = "\u001b]4;0;rgb:0000/0000/0000\u001b\\".repeat(1023)

    @Test fun reservedSpaceCoversPendingPaletteAndAllOtherQueryFamilies() {
        val session = TerminalSession(4096, 1)
        val output = StringBuilder()
        session.onInput { output.append(it.toByteArray().decodeToString()) }
        // The longest pending OSC finishes in the first byte of the next transport slice.
        val prefix = paletteRequest.dropLast(2)
        session.write(prefix)
        assertEquals("", output.toString())
        val commands = listOf("\u001b[c", "\u001b[>c", "\u001b[=c", "\u001bZ", "\u001b[>q",
            "\u001b[5n", "\u001b[?6n", "\u001b[6n", "\u001b[?65535\$p",
            "\u001b[65535\$p", "\u001bP\$q q\u001b\\", "\u001bP\$qm\u001b\\", "\u001bP\$qr\u001b\\",
            "\u001bP\$qx\u001b\\", "\u001b]10;?;?;?\u0007")
        for (command in commands) {
            val next = ("\u001b\\" + command.repeat(TERMINAL_READ_SLICE)).take(TERMINAL_READ_SLICE)
            session.reset()
            session.write("\u001b[1;2;3;4:5;7;8;9;38:2::255:255:255;48:2::255:255:255m")
            session.write(prefix); output.clear()
            session.write(next)
            assertTrue(output.startsWith(paletteReply))
            assertTrue(output.length < TERMINAL_REPLY_RESERVE, "reply reserve for ${command.encodeToByteArray().toHexString()}")
        }
    }

    @Test fun pausedParsingDrainsAndResumesWithoutDuplicatingOrDroppingReplies() {
        val session = TerminalSession(80, 24)
        val queue = TerminalInputBuffer(TERMINAL_REPLY_RESERVE + 1024)
        session.onInput { assertTrue(queue.tryAdd(it), "the transport reserved space before parsing") }
        val stream = (paletteRequest.repeat(12) + "\u001b[5n".repeat(1000) + "界😀END").encodeToByteArray()
        val received = StringBuilder()
        var offset = 0
        var pauses = 0
        while (offset < stream.size) {
            if (queue.remaining < TERMINAL_REPLY_RESERVE) {
                pauses++
                val count = minOf(997, queue.contiguousSize)
                received.append(queue.buffer!!.decodeToString(queue.offset, queue.offset + count))
                queue.consume(count)
            } else {
                offset += session.writeAvailable(stream, offset, stream.size - offset) {
                    queue.remaining >= TERMINAL_REPLY_RESERVE
                }
            }
        }
        while (queue.size > 0) {
            val count = queue.contiguousSize
            received.append(queue.buffer!!.decodeToString(queue.offset, queue.offset + count))
            queue.consume(count)
        }
        assertTrue(pauses > 100)
        assertEquals(paletteReply.repeat(12) + "\u001b[0n".repeat(1000), received.toString())
        assertEquals("界😀END", session.screenLine(0).text())
    }

    @Test fun resumableWritesPreserveSplitUtf8AndPublishOneDamageNotification() {
        val session = TerminalSession(100, 2)
        var notifications = 0
        session.observe { notifications++ }
        val bytes = ("a".repeat(63) + "😀Z").encodeToByteArray()
        assertEquals(0, session.writeAvailable(bytes, 0, bytes.size) { false })
        assertEquals(0, notifications)
        var checkpoints = 0
        val consumed = session.writeAvailable(bytes, 0, bytes.size) { checkpoints++ == 0 }
        assertEquals(64, consumed)
        assertEquals("a".repeat(63), session.screenLine(0).text())
        assertEquals(1, notifications)
        assertEquals(bytes.size - consumed,
            session.writeAvailable(bytes, consumed, bytes.size - consumed) { true })
        assertEquals("a".repeat(63) + "😀Z", session.screenLine(0).text())
        assertEquals(2, notifications)
    }

    @Test fun expensiveCommandsGetACheckpointImmediatelyAndTextUpdatesCoalesce() {
        val session = TerminalSession(100, 2)
        val clears = "\u001b[2J".repeat(1000).encodeToByteArray()
        var checkpoints = 0
        assertEquals(4, session.writeAvailable(clears, 0, clears.size) { checkpoints++ == 0 })
        var notifications = 0
        session.observe { notifications++ }
        val text = "x".repeat(4096).encodeToByteArray()
        assertEquals(text.size, session.writeAvailable(text, 0, text.size) { true })
        assertEquals(1, notifications)
    }

    @Test fun failedInputAdmissionDoesNotChangeTheQueuedPrefix() {
        val queue = TerminalInputBuffer(8)
        assertTrue(queue.tryAdd("abc"))
        assertFalse(queue.tryAdd("界界"))
        assertFalse(queue.tryAdd("a".repeat(10_000)))
        assertEquals(3, queue.size)
        assertEquals(5, queue.remaining)
        assertTrue(queue.tryAdd("😀!"))
        assertEquals("abc😀!", queue.buffer!!.decodeToString())
    }
}
