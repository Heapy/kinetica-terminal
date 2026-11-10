package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalLineSnapshotTest {
    private fun row(): TerminalLine {
        val session = TerminalSession(80, 2)
        session.write("\u001b]133;A\u0007\u001b[1;4:3;31;44;58;5;5m" +
            "\u001b]8;id=a;https://example.test/a\u0007á界😀\u001b[1\"qP")
        return session.screenLine(0)
    }

    private fun contents(row: TerminalLine): List<Any?> = row.readPreservingCompression {
        buildList {
            add(row.wrapped); add(row.wrapContinuation); add(row.promptMarker)
            for (x in 0 until row.columns) {
                add(row.text(x)); add(row.width(x)); add(row.rawForeground(x)); add(row.rawBackground(x))
                add(row.style(x)); add(row.rawUnderlineColor(x)); add(row.hyperlink(x)); add(row.isProtected(x))
                add(row.semanticContent(x)); add(row.hasText(x)); add(row.isSpacerHead(x))
            }
            add(row.storageBytes); add(row.hyperlinkBytes)
        }
    }

    @Test fun compressedSnapshotsKeepPackedStorageUntilReadOrWritten() {
        val source = row()
        source.compress(); assertTrue(source.compressed)
        val expected = contents(source)
        val snapshot = source.snapshot()
        assertTrue(source.compressed)
        assertTrue(snapshot.compressed, "Capturing history must not allocate a decoded cell array per row")
        assertEquals(source.residentStorageBytes, snapshot.residentStorageBytes)
        source.put(0, 'Z'.code, 1, -1, -1, 0)
        assertEquals(expected, contents(snapshot))
        assertTrue(snapshot.compressed)
        snapshot.put(1, 'Q'.code, 1, 0x123456, -1, TerminalStyle.ITALIC)
        assertEquals("Z", source.text(0))
        assertEquals("á", snapshot.text(0))
        assertEquals("界", source.text(1))
    }

    @Test fun mutationsDetachCellsGraphemesLinksAndWidthsInEitherDirection() {
        val mutations: List<(TerminalLine) -> Unit> = listOf(
            { it.put(0, 'X'.code, 1, 0x123456, -1, TerminalStyle.ITALIC) },
            { it.combine(0, 0x302) },
            { it.clear(0, it.columns, -1, -1, 0) },
            { it.setWidth(1, 1); it.setWidth(2, 1) },
            { it.replaceWithBlank(0) },
            { it.replaceWithSpacerHead(0) },
            { it.putSpacerHead(0, 1, 2, TerminalStyle.BOLD) },
            { it.putBlank(0, 3, 4, TerminalStyle.FAINT, null, false, TerminalSemanticContent.OUTPUT) },
            { it.copyCell(it, 0, 10); it.copyGrapheme(it, 0, 11) },
            { it.wrapped = true; it.wrapContinuation = true; it.promptMarker = TerminalPromptMarker.CONTINUATION },
        )
        for ((index, mutate) in mutations.withIndex()) for (packed in listOf(false, true)) {
            val source = row()
            if (packed) source.compress()
            val first = source.snapshot()
            val second = first.snapshot()
            val expected = contents(source)
            mutate(source)
            assertEquals(expected, contents(first), "source mutation $index packed=$packed")
            assertEquals(expected, contents(second), "nested snapshot $index packed=$packed")
            val sourceAfter = contents(source)
            mutate(first)
            assertEquals(expected, contents(second), "snapshot mutation $index packed=$packed")
            assertEquals(sourceAfter, contents(source), "reverse isolation $index packed=$packed")
        }
    }

    @Test fun aSnapshotTakenByAMutationObserverRetainsThePreMutationCells() {
        val source = row()
        val expected = contents(source)
        var captured: TerminalLine? = null
        val observation = source.observeText { _, _ -> if (captured == null) captured = source.snapshot() }
        try {
            source.put(0, 'Z'.code, 1, -1, -1, 0)
            assertEquals(expected, contents(assertNotNull(captured)))
            assertEquals("Z", source.text(0))
        } finally { observation.dispose() }
    }

    @Test fun snapshotsDoNotInheritMutationObserversOrHistoryIdentity() {
        val source = row()
        source.historySlot = 42; source.recycleEpoch = 7
        var changes = 0
        val observation = source.observeText { _, _ -> changes++ }
        try {
            val copy = source.snapshot()
            assertEquals(-1, copy.historySlot); assertEquals(0, copy.recycleEpoch)
            copy.clear(0, copy.columns, -1, -1, 0)
            assertEquals(0, changes)
            assertEquals("á", source.text(0))
        } finally { observation.dispose() }
    }
}
