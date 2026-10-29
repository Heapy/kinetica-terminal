package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsUnicodeTest {
    @Test fun placeholderRunsAndGeometryMatchOriginalGhosttyZigModule() {
        for (encoded in terminalGraphicsUnicodeFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            for (bytewise in listOf(false, true)) {
                val session = TerminalSession(case["columns"]?.jsonPrimitive?.int ?: 12, case["rows"]?.jsonPrimitive?.int ?: 6, 20)
                session.setGraphicsStorageLimit(1_000_000)
                val cell = case["cell"]?.jsonArray?.map { it.jsonPrimitive.int } ?: listOf(8,16)
                session.resize(session.columns, session.rows, TerminalCellSize(cell[0],cell[1]))
                val viewport = TerminalViewport(session, {})
                try {
                    for ((step, value) in case.getValue("events").jsonArray.withIndex()) {
                        val event = value.jsonObject
                        event["text"]?.jsonPrimitive?.content?.encodeToByteArray()?.let { bytes ->
                            if (bytewise) bytes.indices.forEach { session.write(bytes,it,1) } else session.write(bytes)
                        }
                        event["resize"]?.jsonArray?.map { it.jsonPrimitive.int }?.let { session.resize(it[0],it[1],TerminalCellSize(it[2],it[3])) }
                        event["scroll"]?.jsonPrimitive?.int?.let { viewport.scroll(-it) }
                        val runs = (0 until viewport.rows).flatMap { terminalGraphicsUnicodeRuns(session.line(session.historySize - viewport.scrollOffset + it),it) }
                        val actual = buildJsonArray {
                            for (run in runs) add(buildJsonObject {
                                put("x",run.x); put("y",run.y); put("image",run.image); put("placement",run.placement)
                                put("row",run.row); put("col",run.col); put("width",run.width)
                                val image = session.graphicsState.storage.image(run.image)
                                val target = if (run.placement > 0) session.graphicsState.placement(TerminalGraphicsPlacementKey(run.image,run.placement,true))
                                    else session.graphicsState.placements.filter { it.key.image == run.image && it.virtual }
                                        .minWithOrNull(compareBy({ !it.key.external },{ it.key.id }))
                                val geometry = if (image != null && target != null) run.geometry(image.pixels,target.geometry,session.cellSize) else null
                                put("visible", geometry != null && geometry.pixels.width > 0 && geometry.pixels.height > 0)
                                put("err", when {
                                    image == null -> JsonPrimitive("NoImage")
                                    target == null -> JsonPrimitive("PlacementMissingPlacement")
                                    geometry == null -> JsonPrimitive("PlacementGridOutOfBounds")
                                    else -> JsonNull
                                })
                                put("render", geometry?.let { g -> buildJsonArray {
                                    listOf(g.offset.width,g.offset.height,g.source.x,g.source.y,g.source.width,g.source.height,g.pixels.width,g.pixels.height).forEach { add(it) }
                                } } ?: JsonNull)
                            })
                        }
                        assertEquals(case.getValue("snapshots").jsonArray[step],actual,"${case.getValue("name")} step=$step bytewise=$bytewise")
                    }
                } finally { viewport.dispose() }
            }
        }
    }

    @Test fun virtualImageFramesAndRelativeChildrenFollowTextAndFreezeTogether() {
        val session = TerminalSession(12,6,20)
        session.resize(12,6,TerminalCellSize(8,16)); session.setGraphicsStorageLimit(100)
        val viewport = TerminalViewport(session,{})
        val p = "\uDBFB\uDEEE\u0305\u0305"
        try {
            session.write("\u001b_Ga=T,i=1,q=2,f=24,s=1,v=1,U=1,p=7,c=2,r=2;AQID\u001b\\")
            session.write("\u001b_Ga=T,i=2,q=2,f=24,s=1,v=1,p=9,P=1,Q=7,H=1,V=1,C=1;BAUG\u001b\\")
            assertTrue(viewport.graphicsFrame().isEmpty())
            session.write("\u001b[2;3H\u001b[38;2;0;0;1m$p")
            val frames = viewport.graphicsFrame()
            assertEquals(listOf(1L,2L),frames.map { it.image.id })
            assertEquals(3,frames.last().column); assertEquals(2L,frames.last().row)
            val gpu = TerminalGpuFrame(); gpu.update(viewport,8.0,16.0)
            assertTrue(gpu.keys.none { it.text.contains("\uDBFB\uDEEE") }, "Placeholders must never reach a font atlas")
            session.write("\u001b[?2026h\u001b[2J")
            assertEquals(frames,viewport.graphicsFrame())
            session.write("\u001b[?2026l")
            assertTrue(viewport.graphicsFrame().isEmpty())
        } finally { viewport.dispose() }
    }

    @Test fun roundedZeroSpansSamplePremultipliedTexelBoundariesAndClampEdges() {
        val pixels = TerminalGraphicsPixels(2,1,32,byteArrayOf(200.toByte(),0,0,255.toByte(),0,100,0,0))
        val image = TerminalGraphicsStoredImage(1,0,pixels,false,false,0)
        val quad = TerminalImageQuad(image.renderContent,TerminalGraphicsLayer.BELOW_TEXT,0.0,0.0,10.0,10.0,1.0,0.0,0.0,1.0)
        val draw = quad.tiles().single()
        assertEquals(10.0,draw.quad.width); assertEquals(1.0,draw.quad.sourceWidth)
        assertContentEquals(byteArrayOf(100,0,0,128.toByte()),pixels.tileBytes(draw.tile,true))
        assertContentEquals(byteArrayOf(200.toByte(),0,0,128.toByte()),pixels.tileBytes(draw.tile,false))
        val edge = quad.copy(sourceX=2.0,sourceWidth=1.0,cropX=2,cropWidth=1).tiles().single()
        assertContentEquals(byteArrayOf(0,100,0,0),pixels.tileBytes(edge.tile,false))
        val corner = quad.copy(sourceX=0.0,sourceY=0.0,sourceWidth=0.0,sourceHeight=0.0).tiles().single()
        assertContentEquals(byteArrayOf(200.toByte(),0,0,255.toByte()),pixels.tileBytes(corner.tile,true))
    }
}
