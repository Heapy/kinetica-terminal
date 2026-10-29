package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalChecksumTest {
    @Test fun invalidHostConfigurationPreservesPolicyAndResetDefaults() {
        val session = TerminalSession(8,2)
        val replies = StringBuilder()
        session.onInput { replies.append(it.toByteArray().decodeToString()) }
        session.write("A\u001b[1;0;1;1;1;1*y")
        assertEquals("",replies.toString(),"Screen readback requires explicit host opt-in")
        session.configureChecksumReporting(true,extension = 1)
        for (invalid in listOf(-1,32,Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { session.configureChecksumReporting(false,invalid) }
        }
        session.write("\u001b[1;0;1;1;1;1*y\u001b[0#y\u001b[2;0;1;1;1;1*y" +
            "\u001b[!p\u001b[3;0;1;1;1;1*y")
        assertEquals("\u001bP1!~0041\u001b\\\u001bP2!~FFBF\u001b\\\u001bP3!~0041\u001b\\",replies.toString())
        session.configureChecksumReporting(false)
        replies.clear()
        session.write("\u001b[1#y\u001b[4*y\u001b[!p\u001b[5*y\u001bc\u001b[6*y")
        assertEquals("",replies.toString(),"Terminal resets must not enable readback")
    }
}
