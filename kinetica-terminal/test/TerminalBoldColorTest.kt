package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalBoldColorTest {
    @Test fun foregroundResolutionMatchesOriginalGhostty() {
        for (encoded in terminalBoldColorFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val default = case.getValue("default").jsonPrimitive.int
            val colors = TerminalColors(TerminalTheme(foreground = default))
            for ((index, value) in case.getValue("palette").jsonArray.withIndex()) {
                colors.operation(4, "$index;#${value.jsonPrimitive.int.toString(16).padStart(6, '0')}", 7, {})
            }
            val policy = when (val value = case.getValue("policy").jsonPrimitive.intOrNull) {
                null -> null
                -1 -> TerminalBoldColor.BRIGHT
                else -> TerminalBoldColor.Rgb(value)
            }
            val row = TerminalLine(1, colors)
            val raw = case.getValue("raw").jsonArray.map { it.jsonPrimitive.int }
            val resolved = case.getValue("resolved").jsonArray.map { it.jsonPrimitive.int }
            for ((offset, flags) in listOf(0, TerminalStyle.BOLD).withIndex()) for ((index, foreground) in raw.withIndex()) {
                row.put(0, 'A'.code, 1, foreground, -1, flags)
                assertEquals(resolved[offset * raw.size + index], terminalForegroundColor(row, 0, default, policy),
                    "${case["name"]} raw=$foreground flags=$flags")
                assertEquals(foreground, row.rawForeground(0), "Rendering must preserve color identity")
            }
        }
    }

    private fun frames() = listOf(TerminalGpuFrame(), TerminalGpuFrame({ text, _ -> TerminalResolvedFontCell(0, text) }) { run ->
        run.input.clusters.map { column -> TerminalShapedGlyph(0, 42, column, 0, 0, usesForeground = true) }
    })

    @Test fun softwareAndShapedRenderingResolveDistinctColorIdentitiesAndRepaintPolicyChanges() {
        val session = TerminalSession(12, 2, theme = TerminalTheme(foreground = 0x123456))
        val view = TerminalViewport(session, {})
        val frames = frames()
        val painted = mutableListOf<Pair<String, Int>>()
        val painter = object : TerminalPainter {
            override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {}
            override fun text(value: String, x: Double, y: Double, color: Int, style: Int) { if (y == 0.0) painted += value to color }
        }
        try {
            session.write("\u001b[?25l\u001b]4;1;#123456;9;#204060\u0007" +
                "\u001b[1;53;4;9mA\u001b[31mB\u001b[38;2;18;52;86mC\u001b[38;2;32;64;96mD" +
                "\u001b[91mE\u001b[38;5;200mF\u001b[22;31mG")
            val cases = listOf(null to listOf(0x123456, 0x123456, 0x123456),
                TerminalBoldColor.BRIGHT to listOf(0x123456, 0x204060, 0x123456),
                TerminalBoldColor.Rgb(0x2468ac) to listOf(0x2468ac, 0x204060, 0x2468ac),
                null to listOf(0x123456, 0x123456, 0x123456))
            for ((policy, first) in cases) {
                session.boldColor = policy
                val expected = first + listOf(0x204060, 0x204060, session.paletteColor(200), 0x123456)
                for (frame in frames) {
                    view.markAll(); frame.update(view, 8.0, 16.0)
                    assertEquals(expected, frame.rows[0].glyphs.take(7).map { it.color })
                    for (x in 0..6) assertTrue(frame.rows[0].decorations.filter { it.x == x * 8.0 }.all { it.color == expected[x] })
                }
                painted.clear(); view.paint(painter, 8.0, 16.0, force = true)
                assertEquals(expected, painted.flatMap { (text, color) -> List(text.length) { color } }.take(7),
                    "Equal underlying RGB must not merge differently resolved default/indexed colors")
            }
        } finally { view.dispose() }
    }

    @Test fun inverseSelectionCursorAndUnderlineApplyAfterBoldForegroundResolution() {
        val session = TerminalSession(8, 2)
        val view = TerminalViewport(session, {})
        session.boldColor = TerminalBoldColor.BRIGHT
        try {
            session.write("\u001b[?25l\u001b[1;31;4;53;9mA\u001b[7;48;2;1;2;3mB\u001b[27;58;2;4;5;6mC")
            view.beginSelection(0, 0); view.endSelection(1, 0)
            for (cursor in listOf(false, true)) {
                if (cursor) session.write("\u001b[2 q\u001b[?25h\u001b[H")
                for (frame in frames()) {
                    view.markAll(); frame.update(view, 8.0, 16.0)
                    val row = frame.rows[0]
                    assertEquals(listOf(if (cursor) session.theme.background else session.paletteColor(9), 0x010203,
                        session.paletteColor(9)), row.glyphs.take(3).map { it.color })
                    fun background(x: Double) = row.backgrounds.last { x >= it.x && x < it.x + it.width }.color
                    assertEquals(if (cursor) session.theme.cursor else session.theme.selection, background(0.0))
                    assertEquals(session.paletteColor(9), background(8.0), "Inverse uses resolved bold foreground as background")
                    assertTrue(row.decorations.any { it.x == 16.0 && it.y == 14.0 && it.color == 0x040506 })
                    assertTrue(row.decorations.any { it.x == 16.0 && it.y == 0.0 && it.color == session.paletteColor(9) })
                }
            }
        } finally { view.dispose() }
    }

    @Test fun heldFramesRetainNullablePoliciesAndPaletteBeforeRevealingChanges() {
        val policies = listOf(null, TerminalBoldColor.BRIGHT, TerminalBoldColor.Rgb(0x2468ac))
        for ((index, before) in policies.withIndex()) {
            val session = TerminalSession(8, 2)
            val view = TerminalViewport(session, {})
            val frame = frames().last()
            try {
                session.boldColor = before
                session.write("\u001b[?25l\u001b[1;31mA\u001b[39mB")
                frame.update(view, 8.0, 16.0)
                val captured = frame.rows[0].glyphs.take(2)
                session.write("\u001b[?2026h")
                val after = policies[(index + 1) % policies.size]
                session.boldColor = after
                session.write("\u001b]4;9;#abcdef\u0007\u001b]10;#654321\u0007")
                view.markAll(); frame.update(view, 8.0, 16.0)
                assertEquals(captured, frame.rows[0].glyphs.take(2))
                session.write("\u001b[?2026l")
                frame.update(view, 8.0, 16.0)
                assertEquals(listOf(if (after == null) session.paletteColor(1) else 0xabcdef,
                    (after as? TerminalBoldColor.Rgb)?.value ?: 0x654321), frame.rows[0].glyphs.take(2).map { it.color })
            } finally { view.dispose() }
        }
    }

    @Test fun hostPolicySurvivesResetsWithoutChangingClipboardOrPaletteState() {
        assertFailsWith<IllegalArgumentException> { TerminalBoldColor.Rgb(-1) }
        assertFailsWith<IllegalArgumentException> { TerminalBoldColor.Rgb(0x1000000) }
        val session = TerminalSession(8, 2)
        val view = TerminalViewport(session, {})
        try {
            session.write("\u001b[1;31mhello")
            view.selectAll()
            val copy = view.copyData()
            session.boldColor = TerminalBoldColor.Rgb(0)
            assertEquals(copy, view.copyData(), "Copy formats retain SGR/color identity independently of display policy")
            assertEquals(INDEXED_COLOR or 1, session.screenLine(0).rawForeground(0))
            session.write("\u001b[!p\u001bc")
            session.configureTheme(TerminalTheme(foreground = 0x123456))
            assertEquals(TerminalBoldColor.Rgb(0), session.boldColor)
        } finally { view.dispose() }
    }
}
