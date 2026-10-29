package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsExecutionTest {
    @Test fun completeCommandStreamsMatchOriginalLibghostty() {
        for (encoded in terminalGraphicsExecutionFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            for (bytewise in listOf(false, true)) replay(case, bytewise)
        }
    }

    private fun replay(case: JsonObject, bytewise: Boolean) {
        lateinit var terminal: TerminalSession
        val replies = StringBuilder()
        var ids = emptyList<Long>()
        var checkpoint = 0
        for (commandValue in case.getValue("commands").jsonArray) {
            val command = commandValue.jsonPrimitive.content
            val arguments = command.split(' ')
            when (arguments[0]) {
                "N" -> {
                    terminal = TerminalSession(arguments[1].toInt(), arguments[2].toInt(), arguments[3].toInt())
                    terminal.onInput { replies.append(it.toByteArray().toHexString()) }
                }
                "G" -> {
                    terminal.setGraphicsStorageLimit(arguments[1].toInt())
                    ids = arguments.drop(2).map(String::toLong)
                }
                "R" -> terminal.resize(arguments[1].toInt(), arguments[2].toInt(), TerminalCellSize(arguments[3].toInt(), arguments[4].toInt()))
                "W" -> {
                    val bytes = arguments[1].hexToByteArray()
                    if (bytewise) bytes.indices.forEach { terminal.write(bytes, it, 1) } else terminal.write(bytes)
                }
                "C", "J" -> Unit // Shared default colors; J only enables oracle inspection.
                "S" -> {
                    val expected = case.getValue("snapshots").jsonArray[checkpoint].jsonObject
                    val context = "${case.getValue("name").jsonPrimitive.content} step=${checkpoint++} bytewise=$bytewise"
                    fun compare(key: String, value: JsonElement) = assertEquals(expected.getValue(key), value, "$context $key")
                    compare("replies", JsonPrimitive(replies.toString()))
                    compare("cursor", buildJsonArray { add(terminal.cursorColumn); add(terminal.cursorRow) })
                    compare("pendingWrap", JsonPrimitive(terminal.cursorPendingWrap))
                    compare("alternate", JsonPrimitive(terminal.alternateScreen))
                    compare("history", JsonPrimitive(terminal.historySize))
                    val state = terminal.graphicsState
                    val images = ids.mapNotNull(state.storage::image)
                    assertEquals(state.storage.imageCount, images.size, "$context unprobed image")
                    compare("kittyImages", buildJsonArray { images.forEach { image -> add(buildJsonArray {
                        add(image.id); add(image.number); add(image.pixels.width); add(image.pixels.height)
                        add(image.pixels.format); add(image.pixels.data.toHexString())
                    }) } })
                    val actual = state.placements.map { placement -> buildJsonArray {
                        val g = placement.geometry
                        val size = g.resolve(placement.image.pixels.width, placement.image.pixels.height, terminal.cellSize)
                        val origin = state.position(placement)
                        val viewport = origin?.let { graphicsViewportPosition(it.x, it.y, size.grid, terminal.columns, terminal.rows) }
                        add(placement.key.image); add(placement.key.id); add(placement.virtual)
                        listOf(g.xOffset,g.yOffset,g.sourceX,g.sourceY,g.sourceWidth,g.sourceHeight,g.columns,g.rows,g.z.toLong(),
                            size.pixels.width,size.pixels.height,size.grid.width,size.grid.height,size.source.x,size.source.y,size.source.width,size.source.height).forEach { add(it) }
                        add(viewport?.column?.let(::JsonPrimitive) ?: JsonNull); add(viewport?.row?.let(::JsonPrimitive) ?: JsonNull)
                    } }
                    assertEquals(expected.getValue("kittyPlacements").jsonArray.sortedBy(JsonElement::toString),
                        actual.sortedBy(JsonElement::toString), "$context placements")
                    for ((layer, values) in expected.getValue("kittyLayers").jsonArray.withIndex()) {
                        val actualLayer = state.placements.filter { it.geometry.layer.ordinal == layer }.map {
                            buildJsonArray { add(it.key.image); add(it.key.id) }
                        }
                        assertEquals(values.jsonArray.sortedBy(JsonElement::toString), actualLayer.sortedBy(JsonElement::toString), "$context layer=$layer")
                    }
                    compare("textRows", buildJsonArray { repeat(terminal.lineCount) { row ->
                        val line = terminal.line(row)
                        add(buildJsonArray {
                            add(line.wrapped)
                            add(buildJsonArray { repeat(terminal.columns) { x -> add(buildJsonArray { add(line.text(x)); add(line.width(x)) }) } })
                        })
                    } })
                    assertEquals(images.sumOf { it.pixels.data.size.toLong() }, state.storage.totalBytes, "$context image accounting")
                    for (image in images) assertEquals(state.placements.count { it.image === image }, image.placementCount, "$context placement accounting")
                    assertEquals(0, terminal.retainedGraphicsCommandBytes, "$context APC buffer released")
                }
                else -> error("Unknown fixture command: $command")
            }
        }
    }

    @Test fun imageCommandsRemainDisabledUntilTheHostCanRenderThem() {
        val terminal = TerminalSession()
        val replies = mutableListOf<String>()
        terminal.onInput { replies += it.toByteArray().decodeToString() }
        terminal.write("\u001b_Ga=q,i=1,f=24,s=1,v=1;AQID\u001b\\")
        terminal.write("\u001b_Ga=T,i=1,f=24,s=1,v=1;AQID\u001b\\")
        assertTrue(replies.isEmpty()); assertEquals(0, terminal.graphicsState.storage.imageCount)
        assertEquals(0, terminal.retainedGraphicsCommandBytes)
    }

    @Test fun disablingAndResetReleaseIncompleteCaptureAndBothScreens() {
        val terminal = TerminalSession(10,6)
        terminal.setGraphicsStorageLimit(100)
        terminal.write("\u001b_Ga=T,i=1,f=24,s=1,v=1,C=1;AQID\u001b\\")
        val primary = terminal.graphicsState
        val anchor = assertNotNull(primary.placements.single().anchor)
        terminal.write("\u001b[?47h\u001b_Ga=t,i=2,f=24,s=1,v=1,m=1;AQ==\u001b\\")
        val alternate = terminal.graphicsState
        assertTrue(alternate.storage.hasPendingTransfer)
        terminal.write("\u001b_Gm=1;AQID")
        assertTrue(terminal.retainedGraphicsCommandBytes > 0)
        terminal.setGraphicsStorageLimit(0)
        assertEquals(0, terminal.retainedGraphicsCommandBytes)
        assertEquals(0, alternate.storage.retainedLoadingBytes)
        assertEquals(0, primary.storage.totalBytes); assertNull(anchor.line)
        terminal.write("\u001b\\")
        terminal.setGraphicsStorageLimit(100)
        terminal.write("\u001b_Ga=T,i=2,f=24,s=1,v=1,C=1;AQID\u001b\\")
        val old = alternate.storage.image(2)!!.generation
        terminal.reset()
        assertTrue(primary.placements.isEmpty()); assertTrue(alternate.placements.isEmpty())
        terminal.write("\u001b[?47h\u001b_Ga=T,i=2,f=24,s=1,v=1,C=1;AQID\u001b\\")
        assertNotSame(old, alternate.storage.image(2)!!.generation)
    }

    @Test fun boundedCaptureRecoversWithoutExecutingTheOversizedCommand() {
        val terminal = TerminalSession()
        terminal.setGraphicsStorageLimit(100)
        terminal.write("\u001b_Ga=t,i=1,f=24,s=1,v=1;")
        repeat(257) { terminal.write("A".repeat(4096)) }
        assertEquals(0, terminal.retainedGraphicsCommandBytes)
        terminal.write("\u001b\\ok")
        assertEquals(0, terminal.graphicsState.storage.imageCount)
        assertEquals("ok", terminal.screenLine(0).text(0)+terminal.screenLine(0).text(1))
    }

    @Test fun placementsAreBoundedWhileReplacementRemainsPossible() {
        val terminal = TerminalSession(10,6)
        val state = TerminalGraphicsState(terminal, {}, placementLimit = 1)
        state.setLimit(100)
        val replies = mutableListOf<String>()
        terminal.onInput { replies += it.toByteArray().decodeToString() }
        fun execute(header: String) {
            val parser = TerminalGraphicsCommandParser(1024)
            parser.feed(header.encodeToByteArray())
            state.execute(assertNotNull(parser.complete()))
        }
        execute("a=T,i=1,f=24,s=1,v=1,p=7,C=1;AQID")
        val oldAnchor = state.placements.single().anchor!!
        execute("a=p,i=1,p=8,C=1")
        assertTrue(replies.last().contains("ENOMEM")); assertEquals(1, state.placements.size)
        execute("a=p,i=1,p=7,c=2,r=2,C=1")
        assertNull(oldAnchor.line); assertTrue(replies.last().contains(";OK"))
        assertEquals(1, state.storage.image(1)!!.placementCount)
    }

    @Test fun graphicsChangesRespectSynchronizedDamagePublication() {
        val terminal = TerminalSession()
        terminal.setGraphicsStorageLimit(100)
        val changes = mutableListOf<TerminalChange>()
        terminal.observe(changes::add)
        val replies = mutableListOf<String>()
        terminal.onInput { replies += it.toByteArray().decodeToString() }
        terminal.write("\u001b[?2026h")
        changes.clear() // Entering the hold publishes any damage from before the hold.
        terminal.write("\u001b_Ga=T,i=1,f=24,s=1,v=1,C=1;AQID\u001b\\")
        assertTrue(changes.isEmpty()); assertTrue(replies.single().contains(";OK"))
        terminal.write("\u001b[?2026l")
        assertTrue(changes.first().full)
    }
}
