package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsAnimationTest {
    @Test fun animationCoreMatchesOriginalGhosttyExecutionAndClock() {
        for (encoded in terminalGraphicsAnimationFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            var animation: TerminalGraphicsAnimation? = null
            var placed = false
            var previous: Any? = null
            for ((step, value) in case.getValue("events").jsonArray.withIndex()) {
                val event = value.jsonObject
                event["text"]?.jsonPrimitive?.content?.let { text ->
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
                val delay = event["tick"]?.jsonPrimitive?.long?.let { current.tick(it, placed) }
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
                assertEquals(case.getValue("snapshots").jsonArray[step], actual, "${case.getValue("name")} step=$step")
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

    private fun parse(wire: String): TerminalGraphicsCommand {
        val parser = TerminalGraphicsCommandParser(1024 * 1024)
        parser.feed(wire.encodeToByteArray())
        return assertNotNull(parser.complete(),wire)
    }
    private fun decode(command: TerminalGraphicsCommand) = decodeTerminalGraphicsImage(
        command.unsigned('f',32).toInt(),command.unsigned('s'),command.unsigned('v'),
        command.unsigned('o') == 'z'.code.toLong(),command.data,animationFrame = command.action == 'f')
}
