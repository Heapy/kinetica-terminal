package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsAnimationTest {
    @Test fun animationCoreMatchesOriginalGhosttyExecutionAndClock() {
        for (encoded in terminalGraphicsAnimationFixtures()) for (mode in 0..2) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession(10,6).also { it.setGraphicsStorageLimit(10_000_000) }
            var animation: TerminalGraphicsAnimation? = null
            var placed = false
            var previous: Any? = null
            for ((step, value) in case.getValue("events").jsonArray.withIndex()) {
                val event = value.jsonObject
                event["text"]?.jsonPrimitive?.content?.let { text ->
                    if (mode > 0) {
                        if (mode == 1) session.write(text) else text.encodeToByteArray().forEach { session.write(byteArrayOf(it)) }
                        animation = assertNotNull(session.graphicsState.storage.image(1)).animation
                        return@let
                    }
                    val command = parse(text.removePrefix("\u001b_G").removeSuffix("\u001b\\"))
                    try {
                        when (command.action) {
                            't', 'T' -> { animation = TerminalGraphicsAnimation(decode(command)); placed = command.action == 'T' }
                            'f' -> assertNotNull(animation).load(command, decode(command)) { true }
                            'a' -> assertNotNull(animation).control(command)
                            'c' -> assertNotNull(animation).compose(command)
                            'd' -> assertFalse(assertNotNull(animation).delete(command.unsigned('r'), command.unsigned('d') == 'F'.code.toLong()))
                            'p' -> placed = true
                            else -> error("Unexpected fixture action ${command.action}")
                        }
                    } catch (_: TerminalGraphicsAnimationException) {
                        // This component oracle compares error-side state too; wire replies belong to session tests.
                    }
                }
                val current = assertNotNull(animation)
                val delay = event["tick"]?.jsonPrimitive?.long?.let {
                    if (mode == 0) current.tick(it, placed) else session.advanceGraphicsAnimations(it)
                }
                val actual = buildJsonObject {
                    put("pixels", buildJsonArray { for (n in 1..current.frameCount) add(current.frame(n.toLong())!!.data.toHexString()) })
                    put("gaps", buildJsonArray { for (n in 0 until current.frameCount) add(current.gap(n)) })
                    put("format", if (current.rootPixels.format == 32) 1 else 0); put("initialized", current.initialized)
                    put("current", current.currentIndex); put("state", current.state.name.lowercase())
                    put("loops", current.currentLoop); put("maxLoops", current.maxLoops)
                    put("shownAt", current.shownAt?.let(::JsonPrimitive) ?: JsonNull)
                    put("bytes", current.byteCount); put("contentChanged", previous !== current.contentGeneration)
                    put("delay", delay?.let(::JsonPrimitive) ?: JsonNull)
                }
                assertEquals(case.getValue("snapshots").jsonArray[step], actual, "${case.getValue("name")} step=$step mode=$mode")
                if (mode > 0) assertEquals(current.byteCount,session.graphicsState.storage.totalBytes)
                previous = current.contentGeneration
            }
        }
    }

    @Test fun editsCompositionAndRootPromotionRetainPublishedPixels() {
        val root = TerminalGraphicsPixels(2,1,32,byteArrayOf(10,20,30,-1,40,50,60,-1))
        val animation = TerminalGraphicsAnimation(root)
        animation.load(parse("a=f,c=1,s=1,v=1"), TerminalGraphicsPixels(1,1,32,byteArrayOf(90,80,70,-1))) { true }
        val extra = assertNotNull(animation.frame(2))
        animation.compose(parse("a=c,r=2,c=1,w=1,h=1"))
        assertContentEquals(byteArrayOf(10,20,30,-1,40,50,60,-1), root.data)
        val composed = animation.rootPixels
        animation.load(parse("a=f,r=2,s=1,v=1"), TerminalGraphicsPixels(1,1,32,byteArrayOf(1,2,3,-1))) { error("Editing must not reserve another frame") }
        assertContentEquals(byteArrayOf(90,80,70,-1,40,50,60,-1), extra.data)
        animation.delete(1,false)
        assertContentEquals(byteArrayOf(90,80,70,-1,40,50,60,-1), composed.data)
        assertContentEquals(byteArrayOf(1,2,3,-1,40,50,60,-1), animation.rootPixels.data)
        assertEquals(8L,animation.byteCount)
    }

    @Test fun failedAdmissionPreservesRootConversionWithoutAddingFrame() {
        val root = TerminalGraphicsPixels(1,1,24,byteArrayOf(1,2,3))
        val animation = TerminalGraphicsAnimation(root)
        assertFailsWith<TerminalGraphicsAnimationException> {
            animation.load(parse("a=f"), root) { bytes ->
                assertEquals(4,bytes); assertEquals(4L,animation.byteCount); false
            }
        }
        assertEquals(1,animation.frameCount); assertEquals(32,animation.rootPixels.format)
        assertContentEquals(byteArrayOf(1,2,3),root.data)
        val before = animation.contentGeneration
        assertFailsWith<TerminalGraphicsAnimationException> { animation.compose(parse("a=c,r=1,c=1,x=4294967295,w=1,h=1")) }
        assertSame(before,animation.contentGeneration)
    }

    @Test fun clockSaturatesAndGaplessAnimationsDoNotScheduleBusyLoops() {
        val root = TerminalGraphicsPixels(1,1,32,byteArrayOf(1,2,3,-1))
        val animation = TerminalGraphicsAnimation(root)
        animation.load(parse("a=f,z=-1"), root) { true }
        animation.control(parse("a=a,s=3"))
        assertNull(animation.tick(0,true))
        animation.control(parse("a=a,r=1,z=10"))
        assertEquals(5L,animation.tick(Long.MAX_VALUE-5,true))
        assertNull(animation.tick(Long.MAX_VALUE,true))
        assertFailsWith<IllegalArgumentException> { animation.tick(-1,true) }
    }

    @Test fun renderSnapshotsKeepOldPixelsAndCacheKeysAcrossFrameSelectionAndEdits() {
        val root = TerminalGraphicsPixels(1,1,32,byteArrayOf(10,20,30,-1))
        val animation = TerminalGraphicsAnimation(root)
        fun snapshot() = TerminalImageQuad(TerminalGraphicsImageContent(1,animation.currentPixels),
            TerminalGraphicsLayer.BELOW_TEXT,0.0,0.0,8.0,16.0,0.0,0.0,1.0,1.0).tiles().single()
        val first = snapshot()
        animation.load(parse("a=f"),TerminalGraphicsPixels(1,1,32,byteArrayOf(40,50,60,-1))) { true }
        animation.control(parse("a=a,c=2"))
        val second = snapshot()
        assertNotEquals(first.tile.key,second.tile.key)
        animation.control(parse("a=a,c=1"))
        assertEquals(first.tile.key,snapshot().tile.key,"Revisiting an unchanged frame should reuse its texture")
        animation.load(parse("a=f,r=1"),TerminalGraphicsPixels(1,1,32,byteArrayOf(70,80,90,-1))) { true }
        val edited = snapshot()
        assertNotEquals(first.tile.key,edited.tile.key)
        assertContentEquals(byteArrayOf(10,20,30,-1),first.quad.image.pixels.tileBytes(first.tile,true))
        assertContentEquals(byteArrayOf(40,50,60,-1),second.quad.image.pixels.tileBytes(second.tile,true))
        assertContentEquals(byteArrayOf(70,80,90,-1),edited.quad.image.pixels.tileBytes(edited.tile,true))
        animation.delete(1,false)
        assertEquals(second.tile.key,snapshot().tile.key,"Root promotion retains immutable frame content")
    }

    @Test fun sessionTicksFreezePixelsUntilSynchronizedOutputEndsAndRespectPlacementGates() {
        val session = TerminalSession(10,6).also { it.setGraphicsStorageLimit(100); it.resize(10,6,TerminalCellSize(8,16)) }
        val viewport = TerminalViewport(session,{})
        val changes = mutableListOf<TerminalChange>()
        val observation = session.observe(changes::add)
        fun write(header: String) = session.write("\u001b_G$header\u001b\\")
        try {
            write("a=T,i=1,q=2,f=32,s=1,v=1,C=1;AQID/w==")
            write("a=f,i=1,q=2,f=32,s=1,v=1,z=10;BAUG/w==")
            val first = viewport.graphicsFrame().single().image
            write("a=a,i=1,r=1,z=5,s=3")
            assertEquals(5L,session.advanceGraphicsAnimations(0))
            session.write("\u001b[?2026h")
            changes.clear()
            assertEquals(10L,session.advanceGraphicsAnimations(5))
            assertTrue(changes.isEmpty())
            assertSame(first,viewport.graphicsFrame().single().image)
            assertContentEquals(byteArrayOf(1,2,3,-1),first.pixels.data)
            session.write("\u001b[?2026l")
            assertTrue(changes.any { it.full },"Releasing the hold must publish the completed image frame")
            assertContentEquals(byteArrayOf(4,5,6,-1),viewport.graphicsFrame().single().image.pixels.data)
            write("a=d,d=i,i=1")
            assertNull(session.advanceGraphicsAnimations(100))
            write("a=p,i=1,C=1")
            changes.clear()
            assertEquals(5L,session.advanceGraphicsAnimations(100))
            assertEquals(1,changes.size,"A clock-driven frame advance must publish damage without more PTY bytes")
            write("a=d,d=I,i=1")
            assertNull(session.advanceGraphicsAnimations(200))
            assertEquals(0L,session.graphicsState.storage.totalBytes)
        } finally { observation.dispose(); viewport.dispose() }
    }

    @Test fun failedFrameAppendRetainsChunksAndQuietButAbortReleasesThem() {
        val storage = TerminalGraphicsStorage(100,TerminalGraphicsDecodeLimits(maxEncodedBytes=4))
        storage.transmit(parse("a=t,i=1,f=32,s=1,v=1;AQID/w=="))
        assertTrue(storage.transmit(parse("a=f,i=1,f=32,s=1,v=1,m=1,r=7;BA==")).pending)
        assertEquals("\u001b_Gi=1;EINVAL: invalid data\u001b\\",storage.transmit(parse("a=T,m=1,q=1;BQYHCA==")).reply())
        assertEquals(1,storage.loadingBytes)
        assertNull(storage.transmit(parse("a=t;BQb/" )).reply())
        assertEquals(2,storage.image(1)!!.animation.frameCount)
        assertEquals(0,storage.retainedLoadingBytes)
        storage.transmit(parse("a=f,i=1,f=32,s=1,v=1,m=1;AQ=="))
        storage.abortTransfer()
        assertFalse(storage.hasPendingTransfer); assertEquals(0,storage.retainedLoadingBytes)
        assertEquals(8L,storage.totalBytes)
    }

    private fun parse(wire: String): TerminalGraphicsCommand {
        val parser = TerminalGraphicsCommandParser(1024 * 1024)
        parser.feed(wire.encodeToByteArray())
        return assertNotNull(parser.complete(),wire)
    }
    private fun decode(command: TerminalGraphicsCommand) = decodeTerminalGraphicsImage(
        command.unsigned('f',32).toInt(),command.unsigned('s'),command.unsigned('v'),
        command.unsigned('o') == 'z'.code.toLong(),command.data,animationFrame = command.action == 'f')
}
