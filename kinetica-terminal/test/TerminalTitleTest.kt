package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalTitleTest {
    @Test fun stringAndByteFeedsPreserveExactTruncatedTitleReplies() {
        for (prefix in 1021..1024) for (feed in 0..3) {
            val session = TerminalSession()
            val replies = mutableListOf<ByteArray>()
            session.onInput { replies += it.toByteArray() }
            session.configureTitleReporting(true)
            val title = "x".repeat(prefix) + "😀"
            val input = "\u001b]2;$title\u001b\\\u001b[21t"
            when (feed) {
                0 -> session.write(input)
                1 -> input.forEach { session.write(it.toString()) }
                2 -> session.write(input.encodeToByteArray())
                else -> input.encodeToByteArray().forEach { session.write(byteArrayOf(it)) }
            }
            val bytes = title.encodeToByteArray().copyOf(1024)
            assertEquals(bytes.decodeToString(), session.title)
            assertContentEquals(byteArrayOf(27, 93, 108) + bytes + byteArrayOf(27, 92), replies.single())
            session.write("\u001b]2;next\u0007\u001b[21t")
            assertContentEquals(byteArrayOf(27, 93, 108) + bytes + byteArrayOf(27, 92), replies.first())
            assertEquals("\u001b]lnext\u001b\\", replies.last().decodeToString())
        }
    }
}
