package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsGeometryTest {
    @Test fun sourceScalingOffsetsAndLayersMatchOriginalLibghostty() = compare { "margins" !in it && "history" !in it }
    @Test fun cursorAdvanceAndScrolledOriginsMatchOriginalLibghostty() = compare { "history" in it }
    @Test fun marginClippingAndContainmentMatchOriginalLibghostty() = compare { "margins" in it }

    private fun compare(selected: (JsonObject) -> Boolean) {
        val cases = Json.parseToJsonElement(Base64.decode(terminalGraphicsGeometryFixtures()).decodeToString()).jsonArray
        for (case in cases.map { it.jsonObject }.filter(selected)) {
            val name = case.getValue("name").jsonPrimitive.content
            val image = case.getValue("image").jsonArray
            val width = image[0].jsonPrimitive.int; val height = image[1].jsonPrimitive.int
            var cell = case.getValue("cell").jsonArray.let { TerminalCellSize(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) }
            var geometry: TerminalGraphicsPlacementGeometry? = null
            var column = 0; var row = 0
            var anchorColumn = 0; var anchorRow = 0
            var virtual = false
            var history = 0
            for ((index, value) in case.getValue("events").jsonArray.withIndex()) {
                val event = value.jsonObject
                event["cursor"]?.jsonArray?.let { column = it[0].jsonPrimitive.int; row = it[1].jsonPrimitive.int }
                when {
                    "place" in event -> {
                        val parser = TerminalGraphicsCommandParser(1024)
                        parser.feed(event.getValue("place").jsonPrimitive.content.encodeToByteArray())
                        val command = assertNotNull(parser.complete())
                        geometry = TerminalGraphicsPlacementGeometry.from(command, cell)
                        virtual = command.virtual
                        anchorColumn = column; anchorRow = row
                        if (command.moveCursor && !virtual) {
                            val advance = graphicsCursorAdvance(geometry.resolve(width, height, cell).grid, column, 10, 6, 5 - row)
                            val scrolled = maxOf(0, row + advance.indexRows - 5)
                            row = minOf(5, row + advance.indexRows); column = advance.column
                            history += scrolled; anchorRow -= scrolled
                            assertTrue(advance.indexRows <= 11, "$name bounded cursor movement")
                        }
                    }
                    "resize" in event -> event.getValue("resize").jsonArray.let { cell = TerminalCellSize(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) }
                    else -> geometry?.let {
                        val margins = case.getValue("margins").jsonArray.map { v -> v.jsonPrimitive.int }
                        val moved = graphicsScrollWithinMargins(it, width, height, cell, anchorColumn, anchorRow,
                            10, margins[0], margins[1], margins[2], margins[3], event.getValue("scroll").jsonPrimitive.int)
                        geometry = moved?.geometry
                        moved?.let { anchorRow = it.row }
                    }
                }
                val expected = case.getValue("snapshots").jsonArray[index].jsonObject
                val context = "$name event=$index"
                val placements = expected.getValue("kittyPlacements").jsonArray
                val current = geometry
                if (current == null) { assertTrue(placements.isEmpty(), context); continue }
                val actual = current.resolve(width, height, cell)
                val placement = placements.single().jsonArray
                assertEquals(virtual, placement[2].jsonPrimitive.boolean, context)
                val fields = listOf(current.xOffset, current.yOffset, current.sourceX, current.sourceY,
                    current.sourceWidth, current.sourceHeight, current.columns, current.rows, current.z.toLong(),
                    actual.pixels.width, actual.pixels.height, actual.grid.width, actual.grid.height,
                    actual.source.x, actual.source.y, actual.source.width, actual.source.height)
                assertEquals(placement.subList(3,20).map { it.jsonPrimitive.long }, fields, context)
                val viewport = if (virtual) null else graphicsViewportPosition(anchorColumn, anchorRow, actual.grid, 10, 6)
                assertEquals(placement[20].jsonPrimitive.intOrNull, viewport?.column, context)
                assertEquals(placement[21].jsonPrimitive.intOrNull, viewport?.row, context)
                for ((layer, entries) in expected.getValue("kittyLayers").jsonArray.withIndex()) {
                    assertEquals(current.layer.ordinal == layer, entries.jsonArray.isNotEmpty(), "$context layer=$layer")
                }
                if ("margins" !in case) {
                    assertEquals(expected.getValue("cursor").jsonArray.map { it.jsonPrimitive.int }, listOf(column,row), context)
                    assertFalse(expected.getValue("pendingWrap").jsonPrimitive.boolean, context)
                    assertEquals(expected.getValue("history").jsonPrimitive.int, history, context)
                }
            }
        }
    }

    @Test fun uint32AndSignedCoordinatesNeverWrapOrDriveUnboundedCursorWork() {
        val huge = TerminalGraphicsPlacementGeometry(columns = 0xffffffff, rows = 0xffffffff, xOffset = 0xffffffff, yOffset = 0xffffffff)
        val actual = huge.resolve(10000, 1, TerminalCellSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(TerminalGraphicsSize(2147483649,2147483649), actual.pixels)
        assertEquals(TerminalGraphicsSize(0xffffffff,0xffffffff), actual.grid)
        val advance = graphicsCursorAdvance(actual.grid, 4095, 4096, 4096, 4095)
        assertEquals(TerminalGraphicsCursorAdvance(8191,0), advance)
        assertNotNull(graphicsViewportPosition(Int.MIN_VALUE, Int.MIN_VALUE, actual.grid, 80, 24))
        assertNull(graphicsViewportPosition(Int.MAX_VALUE, 0, actual.grid, 80, 24))
        assertNull(graphicsScrollWithinMargins(TerminalGraphicsPlacementGeometry(columns=2,rows=3),
            13,35,TerminalCellSize(8,16),2,1,10,1,4,0,9,Int.MIN_VALUE))
        assertNull(graphicsScrollWithinMargins(TerminalGraphicsPlacementGeometry(columns=2,rows=3),
            13,35,TerminalCellSize(8,16),2,1,10,1,4,0,9,Int.MAX_VALUE))
    }

    @Test fun geometryValuesAreValidatedAndFrozenAcrossResolution() {
        assertFailsWith<IllegalArgumentException> { TerminalGraphicsPlacementGeometry(columns = -1) }
        assertFailsWith<IllegalArgumentException> { TerminalGraphicsPlacementGeometry(sourceX = 0x100000000) }
        val original = TerminalGraphicsPlacementGeometry(xOffset=100,yOffset=100,columns=3,rows=3)
        val unknown = original.atCreation(null)
        val known = original.atCreation(TerminalCellSize(8,16))
        assertEquals(100, unknown.xOffset); assertEquals(100, unknown.yOffset)
        assertEquals(7, known.xOffset); assertEquals(15, known.yOffset)
        unknown.resolve(13,35,TerminalCellSize(8,16))
        assertEquals(original, unknown)
        val clipped = assertNotNull(known.clipTop(13,35,TerminalCellSize(8,16),1,3))
        assertEquals(0, known.sourceY); assertEquals(3, known.rows)
        assertEquals(11, clipped.sourceY); assertEquals(24, clipped.sourceHeight); assertEquals(2, clipped.rows)
    }
}
