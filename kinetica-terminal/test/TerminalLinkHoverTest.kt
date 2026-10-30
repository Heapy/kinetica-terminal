package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalLinkHoverTest {
    @Test fun osc8HoveredCellsMatchOriginalGhosttyWholeAndBytewise() {
        for (encoded in terminalLinkHoverFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            for (bytewise in listOf(false, true)) {
                val session = TerminalSession(case.getValue("columns").jsonPrimitive.int, case.getValue("rows").jsonPrimitive.int)
                val viewport = TerminalViewport(session, {})
                try {
                    for ((step, event) in case.getValue("events").jsonArray.withIndex()) {
                        val value = event.jsonObject
                        value["text"]?.jsonPrimitive?.content?.encodeToByteArray()?.let { bytes ->
                            if (bytewise) for (byte in bytes) session.write(byteArrayOf(byte)) else session.write(bytes)
                        }
                        value["resize"]?.jsonArray?.let { session.resize(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) }
                        value["scroll"]?.jsonPrimitive?.int?.let(viewport::scroll)
                        val snapshot = case.getValue("snapshots").jsonArray[step].jsonObject
                        val columns = snapshot.getValue("columns").jsonPrimitive.int
                        val rows = snapshot.getValue("rows").jsonPrimitive.int
                        assertEquals(columns, viewport.columns); assertEquals(rows, viewport.rows)
                        for ((position, expected) in snapshot.getValue("cells").jsonArray.withIndex()) {
                            viewport.setLinkHover(position % columns, position / columns)
                            val actual = (0 until columns * rows).filter { viewport.linkHover?.contains(it % columns, it / columns) == true }
                            assertEquals(expected.jsonArray.map { it.jsonPrimitive.int }, actual,
                                "${case.getValue("name")} step=$step point=$position bytewise=$bytewise")
                        }
                    }
                } finally { viewport.dispose() }
            }
        }
    }

    @Test fun hoverUsesVisibleSnapshotsCurrentPolicyAndDetectedSemanticRanges() {
        val session = TerminalSession(12, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("\u001b]8;id=one;https://hidden.example\u001b\\https://a.io\u001b]8;;\u001b\\")
            assertEquals(TerminalLinkTarget("https://hidden.example", true), viewport.setLinkHover(1, 0))
            session.write("\u001b[?2026h\u001b[H\u001b[2Kchanged")
            assertEquals("https://hidden.example", viewport.linkHover?.target?.uri)
            session.osc8LinksEnabled = false
            assertEquals(TerminalLinkTarget("https://a.io", false), viewport.linkHover?.target)
            session.osc8LinksEnabled = true
            assertEquals("https://hidden.example", viewport.linkHover?.target?.uri)
            session.write("\u001b[?2026l")
            assertNull(viewport.linkHover)
            session.reset()
            session.write("\u001b]133;A\u0007https://a.io\u001b]133;B\u0007/suffix")
            assertEquals("https://a.io", viewport.setLinkHover(1, 0)?.uri)
            assertTrue(viewport.linkHover!!.contains(11, 0)); assertFalse(viewport.linkHover!!.contains(12, 0))
            assertFalse(viewport.linkHover!!.contains(0, 1), "Adjacent command text is not part of the prompt link")
            assertNull(viewport.setLinkHover(-1, 0)); assertNull(viewport.linkHover)
            assertNull(viewport.setLinkHover(0, 4)); assertNull(viewport.linkHover)
            viewport.setLinkHover(1, 0); viewport.dispose()
            assertNull(viewport.linkHover); assertNull(viewport.setLinkHover(1, 0))
        } finally { viewport.dispose() }
    }

    @Test fun hoverUnderlinesMatchOriginalStyleRuleAndRestoreWithoutMutatingCells() {
        val session = TerminalSession(8, 2)
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        try {
            session.write("\u001b[?25l\u001b]8;;https://example.org\u001b\\a\u001b[4mb\u001b[4:2mc\u001b[4:3md\u001b[4:4me\u001b[4:5mf\u001b[8mg\u001b[0m\u001b]8;;\u001b\\z")
            val original = (0..7).map { session.screenLine(0).style(it) }
            viewport.setLinkHover(0, 0); frame.update(viewport, 10.0, 20.0)
            val decorations = frame.rows[0].decorations
            for (x in 0..5) assertTrue(decorations.any { it.x <= x * 10.0 && it.x + it.width >= (x + 1) * 10.0 && it.y == 18.0 }, "hover underline at $x")
            assertEquals(1, decorations.count { it.y == 16.0 }, "Only an original single underline becomes double")
            assertEquals(10.0, decorations.single { it.y == 16.0 }.x)
            assertFalse(decorations.any { it.x >= 60.0 }, "Invisible and unrelated cells have no hover underline")
            assertEquals(original, (0..7).map { session.screenLine(0).style(it) })
            viewport.setLinkHover(); frame.update(viewport, 10.0, 20.0)
            assertFalse(frame.rows[0].decorations.any { it.x == 0.0 })
            assertEquals(original, (0..7).map { session.screenLine(0).style(it) })
        } finally { viewport.dispose() }
    }

    @Test fun softwareRunsSplitAtHoverBoundariesAndKeepUnderlineColors() {
        val session = TerminalSession(24, 2)
        val viewport = TerminalViewport(session, {})
        val decorations = mutableListOf<TerminalRect>()
        val painter = object : TerminalPainter {
            override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {}
            override fun text(value: String, x: Double, y: Double, color: Int, style: Int) {}
            override fun decoration(x: Double, y: Double, width: Double, height: Double, color: Int) {
                decorations += TerminalRect(x, y, width, height, color)
            }
        }
        try {
            session.write("\u001b[?25l\u001b[58;2;1;2;3m(https://a.io) tail")
            viewport.setLinkHover(2, 0); viewport.paint(painter, 10.0, 20.0)
            assertEquals(listOf(TerminalRect(10.0, 18.0, 120.0, 1.0, 0x010203)), decorations)
            decorations.clear(); viewport.setLinkHover(); viewport.paint(painter, 10.0, 20.0)
            assertTrue(decorations.isEmpty())
        } finally { viewport.dispose() }
    }

    @Test fun hoverRepaintsDisjointRowsWhenTheTargetUnderThePointerChanges() {
        val session = TerminalSession(12, 3)
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        try {
            session.write("\u001b[?25l\u001b]8;id=one;https://example.org\u001b\\aa\u001b[3;1Hbb\u001b]8;;\u001b\\")
            viewport.setLinkHover(0, 0); frame.update(viewport, 10.0, 20.0)
            assertTrue(frame.rows[2].decorations.isNotEmpty())
            session.write("\u001b[Hxx")
            assertNull(viewport.linkHover)
            frame.update(viewport, 10.0, 20.0)
            assertTrue(frame.rows[2].decorations.isEmpty(), "Old distant highlights must be invalidated too")
            viewport.setLinkHover(0, 2)
            session.screenLine(2).compress()
            assertEquals("https://example.org", viewport.setLinkHover(1, 2)?.uri)
        } finally { viewport.dispose() }
    }
}
