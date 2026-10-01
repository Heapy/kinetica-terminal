package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalPresentationTest {
    @Test fun liveResizeRetainsTheCompletedFrameThroughReflowAndFragmentedRedraw() {
        val session = TerminalSession(12, 4)
        val clock = Clock()
        val viewport = TerminalViewport(session, {}, clock)
        val other = TerminalViewport(session, {})
        try {
            session.write("HEADER\r\nbody\r\n\r\n> prompt")
            val before = viewport.visibleText()
            viewport.resizeSession(12, 3)
            assertEquals(3, session.rows, "Notify the PTY of its new grid immediately")
            assertEquals(3, other.rows, "Only the resizing viewport retains its presentation")
            assertEquals(before, viewport.visibleText())
            assertFalse(session.synchronizedOutput, "A host resize must not change VT protocol state")
            session.write("\u001b[2J\u001b[HHEAD")
            clock.advance(7)
            assertEquals(before, viewport.visibleText())
            session.write("ER\r\nREADY")
            clock.advance(7)
            assertEquals(before, viewport.visibleText())
            clock.advance(1)
            assertEquals("HEADER\nREADY\n", viewport.visibleText())
            assertEquals(3, viewport.rows)
            assertEquals(0, clock.pending)
        } finally { viewport.dispose(); other.dispose() }
    }

    @Test fun synchronizedRedrawUsesThePreResizeFrameAndItsOwnDeadline() {
        val session = TerminalSession(12, 4)
        val clock = Clock()
        val viewport = TerminalViewport(session, {}, clock)
        try {
            session.write("HEADER\r\nbody\r\n\r\n> prompt")
            val before = viewport.visibleText()
            viewport.resizeSession(12, 3)
            session.write("\u001b[?2026h\u001b[2J\u001b[HPARTIAL")
            clock.advance(200)
            assertEquals(before, viewport.visibleText(), "Do not freeze the already-reflowed grid")
            session.write("\rFINISHED\u001b[?2026l")
            assertEquals("FINISHED\n\n", viewport.visibleText())
            assertEquals(0, clock.pending)
            viewport.resizeSession(12, 4)
            session.write("\u001b[?2026h\rABANDONED")
            clock.advance(999)
            assertEquals("FINISHED\n\n", viewport.visibleText())
            clock.advance(1)
            assertFalse(session.synchronizedOutput)
            assertContains(viewport.visibleText(), "ABANDONED")
            assertEquals(0, clock.pending)
        } finally { viewport.dispose() }
    }

    @Test fun repeatedLiveResizeCannotFreezeSilentOutputAndDisposalCancelsTheWait() {
        val session = TerminalSession(12, 4)
        val clock = Clock()
        var invalidations = 0
        val viewport = TerminalViewport(session, { invalidations++ }, clock)
        session.write("HEADER\r\nbody\r\n\r\n> prompt")
        val before = viewport.visibleText()
        viewport.resizeSession(12, 3)
        repeat(4) {
            clock.advance(10)
            viewport.resizeSession(13 + it, 3)
            assertEquals(before, viewport.visibleText())
        }
        clock.advance(10)
        assertEquals(session.columns, viewport.columns)
        assertEquals(session.rows, viewport.rows)
        assertEquals(0, clock.pending)
        viewport.resizeSession(20, 4)
        assertEquals(1, clock.pending)
        viewport.dispose()
        val disposedInvalidations = invalidations
        clock.advance(1000)
        session.write("AFTER DISPOSAL")
        assertEquals(disposedInvalidations, invalidations)
        assertEquals(0, clock.pending)
    }

    @Test fun mountingDuringAHoldWaitsForACompletedFrame() {
        val session = TerminalSession(8, 2)
        session.write("old\u001b[?2026h\rpartial")
        val clock = Clock()
        val viewport = TerminalViewport(session, {}, clock)
        try {
            assertEquals("\n", viewport.visibleText())
            session.write("\rcomplete\u001b[?2026l")
            assertEquals("complete\n", viewport.visibleText())
            assertEquals(0, clock.pending)
        } finally { viewport.dispose() }
    }

    @Test fun releaseUpdatesScrollAnchorsBeforeAnImmediateRepaint() {
        val session = TerminalSession(8, 2, 100)
        session.write((0..8).joinToString("\r\n"))
        val frames = mutableListOf<String>()
        var observing: TerminalViewport? = null
        val viewport = TerminalViewport(session, { observing?.let { frames += it.visibleText() } })
        observing = viewport
        try {
            viewport.scroll(3)
            val before = viewport.visibleText()
            frames.clear()
            session.write("\u001b[?2026h\r\n9\r\n10\u001b[?2026l")
            assertTrue(frames.isNotEmpty())
            assertTrue(frames.all { it == before }, "A synchronous painter must never see a transient scroll jump: $frames")
        } finally { viewport.dispose() }
    }

    @Test fun holdCapturesAtTheEscapeBoundaryAndFreezesCellsPaletteCursorAndAccessibility() {
        val session = TerminalSession(8, 3, 2)
        val clock = Clock()
        val viewport = TerminalViewport(session, {}, clock)
        val frame = TerminalGpuFrame()
        try {
            session.write("OLD")
            frame.update(viewport, 8.0, 16.0)
            session.write("\r\u001b[31mPRE\u001b[?2026h\u001b[2J\u001b[HPOST\u001b[2 q\u001b]4;1;#123456\u0007\u001b]11;#654321\u0007")
            assertEquals("POST", session.screenLine(0).text())
            assertEquals("PRE\n\n", viewport.visibleText())
            assertEquals(3, viewport.cursorColumn)
            assertEquals(0x1e1e1e, viewport.theme.background)
            frame.update(viewport, 8.0, 16.0)
            assertEquals("PRE", frame.rows[0].glyphs.joinToString("") { it.key.text })
            assertTrue(frame.rows[0].glyphs.all { it.color == terminalPalette(1) })
            // Simulate forced repaint/context recovery after all old live rows have been recycled.
            session.write("\r\nnew".repeat(10))
            viewport.markAll(); frame.update(viewport, 10.0, 20.0)
            assertEquals("PRE", frame.rows[0].glyphs.joinToString("") { it.key.text })
            viewport.beginSelection(0, 0); viewport.extendSelection(3, 0)
            assertEquals("PRE", viewport.selectedText())
            session.write("\u001b[?2026l")
            assertFalse(session.synchronizedOutput)
            assertEquals(0x654321, viewport.theme.background)
            assertNotEquals("PRE\n\n", viewport.visibleText())
            assertEquals(0, clock.pending)
        } finally { viewport.dispose() }
    }

    @Test fun repeatedEnableCannotExtendDeadlineAndRepliesAndInputContinue() {
        val session = TerminalSession(8, 2)
        val clock = Clock()
        val viewport = TerminalViewport(session, {}, clock)
        val replies = mutableListOf<String>()
        val holds = mutableListOf<Boolean>()
        session.onInput { replies.add(it.toByteArray().decodeToString()) }; session.onRenderHold(holds::add)
        try {
            session.write("old\u001b[?2026h\rnew\u001b[6n")
            assertTrue(session.sendKey(TerminalKey("ArrowUp")))
            assertEquals(listOf("\u001b[1;4R", "\u001b[A"), replies)
            clock.advance(900)
            session.write("\u001b[?2026h\u001b[?2026\$p")
            assertEquals("\u001b[?2026;1\$y", replies.last())
            assertEquals(1, clock.pending)
            clock.advance(100)
            assertFalse(session.synchronizedOutput)
            assertEquals(listOf(true, false), holds)
            assertEquals("new\n", viewport.visibleText())
            assertEquals(0, clock.pending)
        } finally { viewport.dispose() }
    }

    @Test fun resizeResetAndEofReleaseSnapshotsAndCancelTimers() {
        for (end in listOf<(TerminalSession) -> Unit>({ it.resize(it.columns, it.rows) },
            { it.resize(4, 3) }, { it.reset() }, { it.finishInput() }, { it.write("\u001b[?2026l") })) {
            val session = TerminalSession(8, 2)
            val clock = Clock()
            val viewport = TerminalViewport(session, {}, clock)
            try {
                session.write("old\u001b[?2026h\rnew")
                assertTrue(session.synchronizedOutput)
                end(session)
                assertFalse(session.synchronizedOutput)
                assertEquals(session.columns, viewport.columns)
                assertEquals(session.rows, viewport.rows)
                assertEquals((0 until session.rows).joinToString("\n") { session.screenLine(it).text() }, viewport.visibleText())
                assertEquals(0, clock.pending)
            } finally { viewport.dispose() }
        }
    }

    @Test fun twoSurfacesAndUnmountCancelTheirOwnTimers() {
        val session = TerminalSession(8, 2)
        val clock = Clock()
        var invalidations = 0
        val first = TerminalViewport(session, { invalidations++ }, clock)
        val second = TerminalViewport(session, {}, clock)
        session.write("old\u001b[?2026h\rnew")
        assertEquals(2, clock.pending)
        first.dispose(); first.dispose()
        val disposedCount = invalidations
        assertEquals(1, clock.pending)
        clock.advance(1000)
        assertFalse(session.synchronizedOutput)
        assertEquals(disposedCount, invalidations)
        assertEquals("new\n", second.visibleText())
        session.write("\u001b[1 q")
        assertEquals(1, clock.pending)
        second.dispose()
        assertEquals(0, clock.pending)
        clock.advance(2000)
        assertEquals(disposedCount, invalidations)
    }

    @Test fun cursorBlinkFocusVisibilityAndHoldUseBoundedOneShotTimers() {
        val session = TerminalSession(4, 1)
        val clock = Clock()
        val viewport = TerminalViewport(session, {}, clock)
        val frame = TerminalGpuFrame()
        try {
            session.write("X\r\u001b[1 q")
            frame.update(viewport, 8.0, 16.0)
            assertEquals(session.theme.cursor, frame.rows[0].backgrounds.first().color)
            assertEquals(session.theme.background, frame.rows[0].glyphs.single().color)
            clock.advance(500); frame.update(viewport, 8.0, 16.0)
            assertEquals(session.theme.background, frame.rows[0].backgrounds.first().color)
            assertEquals(session.theme.foreground, frame.rows[0].glyphs.single().color)
            clock.advance(500); frame.update(viewport, 8.0, 16.0)
            assertEquals(session.theme.cursor, frame.rows[0].backgrounds.first().color)
            viewport.setFocused(false); frame.update(viewport, 8.0, 16.0)
            assertEquals(0, clock.pending)
            assertEquals(4, frame.rows[0].decorations.size)
            viewport.setFocused(true)
            assertEquals(1, clock.pending)
            session.write("\u001b[?2026h\u001b[?25l")
            assertEquals(1, clock.pending, "Only the hold deadline remains")
            clock.advance(500); frame.update(viewport, 8.0, 16.0)
            assertEquals(session.theme.cursor, frame.rows[0].backgrounds.first().color)
            session.write("\u001b[?2026l")
            assertEquals(0, clock.pending)
            frame.update(viewport, 8.0, 16.0)
            assertEquals(session.theme.background, frame.rows[0].backgrounds.first().color)
        } finally { viewport.dispose() }
    }

    @Test fun cursorShapesCoverWideCellsAndBarsStayAboveGlyphs() {
        val session = TerminalSession(4, 1)
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        try {
            session.write("界X\u001b[1;2H\u001b[4 q")
            frame.update(viewport, 8.0, 16.0)
            assertEquals(TerminalRect(0.0, 14.0, 16.0, 2.0, session.theme.cursor), frame.rows[0].decorations.single())
            session.write("\u001b[6 q")
            frame.update(viewport, 8.0, 16.0)
            assertEquals(TerminalRect(0.0, 0.0, 2.0, 16.0, session.theme.cursor), frame.rows[0].decorations.single())
            session.write("\u001b[2 q")
            frame.update(viewport, 8.0, 16.0)
            assertEquals(16.0, frame.rows[0].backgrounds.first().width)
            assertEquals(session.theme.cursor, frame.rows[0].backgrounds.first().color)
            assertEquals(session.theme.background, frame.rows[0].glyphs.first().color)
        } finally { viewport.dispose() }
    }

    @Test fun defaultCursorConfigurationSurvivesExplicitStylesUntilReset() {
        val session = TerminalSession(cursorStyle = TerminalCursorStyle.BAR, cursorBlink = true)
        session.write("\u001b[2 q")
        assertEquals(TerminalCursorStyle.BLOCK, session.cursorStyle); assertFalse(session.cursorBlink)
        session.write("\u001b[0 q")
        assertEquals(TerminalCursorStyle.BAR, session.cursorStyle); assertTrue(session.cursorBlink)
        session.write("\u001b[4 q\u001bc")
        assertEquals(TerminalCursorStyle.BAR, session.cursorStyle); assertTrue(session.cursorBlink)
    }

    private class Clock : TerminalScheduler {
        private class Job(val time: Long, val action: () -> Unit)
        private var time = 0L
        private val jobs = mutableListOf<Job>()
        val pending: Int get() = jobs.size
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            require(delayMillis > 0)
            val job = Job(time + delayMillis, action); jobs += job
            return TerminalDisposable { jobs.remove(job) }
        }
        fun advance(millis: Int) {
            val end = time + millis
            while (true) {
                val job = jobs.minByOrNull { it.time }?.takeIf { it.time <= end } ?: break
                jobs.remove(job); time = job.time; job.action()
            }
            time = end
        }
    }
}
