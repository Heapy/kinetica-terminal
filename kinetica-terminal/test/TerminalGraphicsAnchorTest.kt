package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsAnchorTest {
    @Test fun trackedRowsAndReflowMatchOriginalPlacements() {
        val cases = Json.parseToJsonElement(Base64.decode(terminalGraphicsAnchorFixtures()).decodeToString()).jsonArray
        for (caseValue in cases) for (bytewise in listOf(false, true)) {
            val case = caseValue.jsonObject
            val terminal = TerminalSession(8, 5, scrollback = 1000)
            val anchors = mutableMapOf<Int, TerminalGraphicsAnchor>()
            val retired = mutableSetOf<Int>()
            fun write(value: String) {
                val bytes = value.encodeToByteArray()
                if (bytewise) bytes.forEach { terminal.write(byteArrayOf(it)) } else terminal.write(bytes)
            }
            for ((index, value) in case.getValue("events").jsonArray.withIndex()) {
                val event = value.jsonObject
                when {
                    "anchor" in event -> {
                        val fields = event.getValue("anchor").jsonArray.map { it.jsonPrimitive.int }
                        write("\u001b[${fields[2] + 1};${fields[1] + 1}H")
                        anchors.put(fields[0], terminal.graphicsAnchor())?.dispose()
                    }
                    "resize" in event -> event.getValue("resize").jsonArray.let {
                        terminal.resize(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int, TerminalCellSize(8,16))
                    }
                    else -> {
                        val text = event.getValue("write").jsonPrimitive.content
                        if (case["anchorPolicy"]?.jsonPrimitive?.content == "retire-cleared-history" && text == "\u001b[3J") {
                            // Explicit policy difference: original eraseHistory relocates erased pins to (0,0).
                            // Our strict retained-row policy releases them; raw reference snapshots stay intact.
                            for ((id, anchor) in anchors) if ((terminal.graphicsAnchorPosition(anchor)?.y ?: 0) < 0) retired += id
                        }
                        write(text)
                    }
                }
                val expected = case.getValue("snapshots").jsonArray[index].jsonObject
                val context = "${case.getValue("name").jsonPrimitive.content} event=$index bytewise=$bytewise"
                val placements = expected.getValue("kittyPlacements").jsonArray.associate {
                    it.jsonArray[1].jsonPrimitive.int to it.jsonArray
                }
                for ((id, anchor) in anchors) {
                    if (id in retired) {
                        assertNull(anchor.line, "$context id=$id retired history")
                        continue
                    }
                    val position = terminal.graphicsAnchorPosition(anchor)
                    val viewport = position?.let {
                        graphicsViewportPosition(it.x, it.y, TerminalGraphicsSize(1,1000), terminal.columns, terminal.rows)
                    }
                    assertEquals(placements[id]?.get(20)?.jsonPrimitive?.intOrNull, viewport?.column, "$context id=$id column")
                    assertEquals(placements[id]?.get(21)?.jsonPrimitive?.intOrNull, viewport?.row, "$context id=$id row")
                }
                assertEquals(expected.getValue("cursor").jsonArray.map { it.jsonPrimitive.int },
                    listOf(terminal.cursorColumn, terminal.cursorRow), "$context cursor")
                assertEquals(expected.getValue("pendingWrap").jsonPrimitive.boolean, terminal.cursorPendingWrap, "$context wrap")
                assertEquals(expected.getValue("history").jsonPrimitive.int, terminal.historySize, "$context history")
                assertEquals(expected.getValue("alternate").jsonPrimitive.boolean, terminal.alternateScreen, "$context screen")
            }
        }
    }

    @Test fun strictHistoryRetirementReleasesEveryAnchor() {
        val terminal = TerminalSession(8, 2, scrollback = 2)
        val first = terminal.graphicsAnchor()
        terminal.write("one\r\ntwo\r\nthree")
        assertEquals(-1, terminal.graphicsAnchorPosition(first)?.y)
        terminal.write("\r\nfour\r\nfive")
        assertNull(first.line)
        assertNull(terminal.graphicsAnchorPosition(first))
        val active = terminal.graphicsAnchor()
        terminal.write("\r\nsix\r\nseven")
        assertNotNull(active.line)
        terminal.clearHistory()
        assertNull(active.line)
    }

    @Test fun budgetEvictionAndRingClearReleaseAllRetiredRows() {
        val rows = List(4) { TerminalLine(8) }
        val anchors = rows.map { TerminalGraphicsAnchor(it, 0, false) }
        val ring = LineRing(10, rows[0].storageBytes * 3)
        rows.take(3).forEach { ring.add(it) }
        val oversized = TerminalLine(100)
        val tooLarge = TerminalGraphicsAnchor(oversized, 0, false)
        ring.add(oversized)
        anchors.take(3).forEach { assertNull(it.line) }
        assertNull(tooLarge.line)
        ring.add(rows[3]); ring.clear()
        assertNull(anchors[3].line)
        val noHistory = LineRing(0, Long.MAX_VALUE)
        val rejected = TerminalGraphicsAnchor(rows[0], 0, false)
        noHistory.add(rows[0]); assertNull(rejected.line)
    }

    @Test fun resetReleasesInactiveAndActiveScreenAnchors() {
        val terminal = TerminalSession(8, 3)
        val primary = terminal.graphicsAnchor()
        terminal.write("\u001b[?47h")
        assertNull(terminal.graphicsAnchorPosition(primary))
        assertNotNull(primary.line)
        val alternate = terminal.graphicsAnchor()
        terminal.reset()
        assertNull(primary.line); assertNull(alternate.line)
    }

    @Test fun recyclingAndDisposalDoNotAttachImagesToUnrelatedText() {
        val terminal = TerminalSession(8, 3, scrollback = 0)
        val anchor = terminal.graphicsAnchor()
        val line = assertNotNull(anchor.line)
        terminal.write("\u001b[3;1H\n")
        assertNull(anchor.line)
        assertFalse(line.hasGraphicsAnchors)
        val disposed = terminal.graphicsAnchor()
        val active = assertNotNull(disposed.line)
        disposed.dispose(); disposed.dispose()
        assertFalse(active.hasGraphicsAnchors)
        assertNull(terminal.graphicsAnchorPosition(disposed))
        terminal.resize(1, 1)
        assertNull(disposed.line)
    }
}
