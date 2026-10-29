// Cases adapted from pinned Ghostty kitty/graphics_command.zig (MIT).
package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsCommandTest {
    private fun parse(value: String, max: Int = 1024 * 1024): TerminalGraphicsCommand? =
        TerminalGraphicsCommandParser(max).let { it.feed(value.encodeToByteArray()); it.complete() }

    @Test fun transmissionFieldsAndDefaultsMatchGhostty() {
        val command = assertNotNull(parse("f=24,s=10,v=20,N=1,i=4294967295,I=3,p=7,S=40,O=5,m=2;oA=="))
        assertEquals('t', command.action); assertEquals(24, command.format)
        assertEquals(10, command.unsigned('s')); assertEquals(20, command.unsigned('v'))
        assertEquals(4294967295, command.imageId); assertEquals(3, command.imageNumber); assertEquals(7, command.placementId)
        assertEquals(40, command.unsigned('S')); assertEquals(5, command.unsigned('O'))
        assertEquals(1, command.unsigned('N')); assertTrue(command.moreChunks)
        assertContentEquals(byteArrayOf(0xa0.toByte()), command.data)
        val defaults = assertNotNull(parse(";"))
        assertEquals('t', defaults.action); assertEquals(32, defaults.format); assertEquals('d', defaults.medium)
        assertFalse(defaults.compressed); assertFalse(defaults.moreChunks); assertEquals(0, defaults.quiet)
        for ((wire, format) in listOf(0 to 32, 24 to 24, 32 to 32, 100 to 100, 42 to -1)) {
            assertEquals(format, assertNotNull(parse("f=$wire")).format)
        }
        for (medium in "fts") assertFalse(assertNotNull(parse("t=$medium,m=1")).moreChunks)
        assertTrue(assertNotNull(parse("o=z")).compressed)
    }

    @Test fun everyActionRetainsItsFieldsAndFlagSemantics() {
        for (action in "qtTpdfac") {
            val value = assertNotNull(parse("a=$action,i=31,p=8,q=9,x=1,y=2,w=3,h=4,X=5,Y=6,c=7,r=8,z=-2000000000,H=-1,V=-2147483648,C=2,U=2,P=10,Q=11"))
            assertEquals(action, value.action); assertEquals(2, value.quiet)
            assertEquals(-2000000000, value.signed('z')); assertEquals(-1, value.signed('H')); assertEquals(Int.MIN_VALUE, value.signed('V'))
            assertTrue(value.moveCursor); assertTrue(value.virtual)
            assertEquals(10, value.unsigned('P')); assertEquals(11, value.unsigned('Q'))
        }
        for (delete in "aAiInNcCfFpPqQrRxXyYzZ") assertEquals(delete, assertNotNull(parse("a=d,d=$delete")).deleteAction)
        assertFalse(assertNotNull(parse("a=p,C=1")).moveCursor)
        assertEquals(1, assertNotNull(parse("q=1")).quiet)
        for (input in listOf("a=z,i=31", "a=d,d=w,i=31", "a=t,t=q,i=31", "a=t,o=q,i=31", "a=4294967295", "t=256", "d=256,a=d")) {
            assertNull(parse(input), input)
        }
    }

    @Test fun malformedHeadersUnknownKeysAndDuplicateFieldsMatchUpstream() {
        assertEquals(20, assertNotNull(parse("f=24,s=10,v=20,hello=world")).unsigned('v'))
        assertEquals(20, assertNotNull(parse("f=24,s=10,v=20,!=1")).unsigned('v'))
        assertEquals(0, assertNotNull(parse("f=24,s=10,v=2000000000000000000000")).unsigned('v'))
        assertEquals(4, assertNotNull(parse("i=3,i=4")).imageId)
        // Once an oversized/unknown multicharacter key enters ignore mode, later fields
        // are ignored through the payload delimiter; this is the pinned parser's behavior.
        val ignored = assertNotNull(parse("i=3,unknown=value,i=4;YQ=="))
        assertEquals(3, ignored.imageId); assertContentEquals(byteArrayOf(97), ignored.data)
        assertContentEquals(byteArrayOf(97), assertNotNull(parse("loose-key;YQ==")).data)
        assertTrue(assertNotNull(parse("abcdefghijkl;YQ==")).data.isEmpty())
        for (input in listOf("", "i", "i=", "a=t,", "a=t,s", "a=t,s=garbage", "a=t,!=bad", "abcdefghijkl;YQ")) {
            assertNull(parse(input), input)
        }
    }

    @Test fun integerParsingPreservesBitPatternsAndRejectsOverflow() {
        for ((text, expected) in listOf("4294967295" to 4294967295L, "+42" to 42L, "-0" to 0L,
            "4_2" to 42L, "4__2" to 42L, "-" to 45L, "a" to 97L)) {
            assertEquals(expected, assertNotNull(parse("i=$text")).imageId, text)
        }
        assertEquals(Int.MAX_VALUE, assertNotNull(parse("z=2147483647")).signed('z'))
        for (input in listOf("i=4294967296", "i=10000000000", "z=-9999999999", "z=2147483648", "z=-2147483649",
            "i=-1", "i=_42", "i=42_", "i= 42", "i=0x2a")) assertNull(parse(input), input)
    }

    @Test fun payloadDecodeAcceptsOptionalPaddingAndNoncanonicalTailBitsOnly() {
        for (encoded in listOf("YQ==", "YQ", "YR==", "YR")) assertContentEquals(byteArrayOf(97), assertNotNull(parse(";$encoded")).data)
        for (encoded in listOf("YWI=", "YWI", "YWL=", "YWL")) assertContentEquals(byteArrayOf(97, 98), assertNotNull(parse(";$encoded")).data)
        for (encoded in listOf("Y", "YQ=", "YQ===", "=YQ=", "YQ==x", "Y Q==", "YQ==\n", "-_==", "é")) {
            assertNull(parse(";$encoded"), encoded)
        }
    }

    @Test fun chunkBoundariesDoNotChangeTheCommandAndCompletedDataIsOwned() {
        val bytes = "a=T,f=24,s=2,v=1,i=12,z=-2;AAECAwQF".encodeToByteArray()
        for (split in 0..bytes.size) {
            val parser = TerminalGraphicsCommandParser(64)
            parser.feed(bytes, 0, split); parser.feed(bytes, split, bytes.size - split)
            val command = assertNotNull(parser.complete())
            assertEquals(12, command.imageId); assertEquals(-2, command.signed('z'))
            assertContentEquals(byteArrayOf(0, 1, 2, 3, 4, 5), command.data)
            assertEquals(0, parser.retainedPayloadBytes)
            assertNull(parser.complete())
            parser.reset(); parser.feed("i=99;////".encodeToByteArray()); assertNotNull(parser.complete())
            assertEquals(12, command.imageId); assertContentEquals(byteArrayOf(0, 1, 2, 3, 4, 5), command.data)
        }
        val single = TerminalGraphicsCommandParser(64)
        bytes.forEach { single.feed(it.toInt() and 255) }
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4, 5), assertNotNull(single.complete()).data)
    }

    @Test fun payloadBudgetFailureAndResetReleaseLargeBuffers() {
        val data = ByteArray(150_000) { it.toByte() }
        val encoded = Base64.Default.encode(data).encodeToByteArray()
        val parser = TerminalGraphicsCommandParser(encoded.size)
        parser.feed(';'.code); parser.feed(encoded)
        assertEquals(encoded.size, parser.retainedPayloadBytes)
        assertContentEquals(data, assertNotNull(parser.complete()).data)
        assertEquals(0, parser.retainedPayloadBytes)
        parser.reset(); parser.feed(';'.code); parser.feed(encoded); parser.feed('A'.code)
        assertTrue(parser.failed); assertEquals(0, parser.retainedPayloadBytes)
        parser.feed("i=3;YQ==".encodeToByteArray()); assertNull(parser.complete())
        parser.reset(); parser.feed(';'.code); parser.feed(encoded); parser.reset()
        assertEquals(0, parser.retainedPayloadBytes)
        assertNotNull(parse("a=d", 0)); assertNotNull(parse(";", 0)); assertNull(parse(";YQ==", 0))
    }
}
