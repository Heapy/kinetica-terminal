package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalSelectionTest {
    @Test fun pointerLinksRequireAnUncancelledClickOnTheOriginalCell() {
        val session = TerminalSession(40, 3)
        val viewport = TerminalViewport(session, {})
        fun point(x: Double, row: Int = 0) = terminalSelectionPointer(x * 10, row * 20.0 + 10, 10.0, 20.0, 1.0)
        try {
            session.write("\u001b]8;;https://example.org\u001b\\click\u001b]8;;\u001b\\")
            viewport.beginPointerSelection(point(1.2))
            assertEquals("https://example.org", viewport.pointerLinkAt(point(1.3))?.uri)
            assertNull(viewport.pointerLinkAt(point(2.2)), "Release in another cell is not a click")
            viewport.extendPointerSelection(point(2.2), false)
            viewport.extendPointerSelection(point(1.2), false)
            assertNull(viewport.pointerLinkAt(point(1.2)), "Returning to the press cell cannot undo a drag")
            viewport.beginPointerSelection(point(1.2))
            viewport.extendPointerSelection(point(1.8), false)
            viewport.extendPointerSelection(point(1.2), false)
            assertNull(viewport.pointerLinkAt(point(1.2)), "Crossing the within-cell threshold cancels the click")
            viewport.beginPointerSelection(point(1.2)); viewport.cancelSelectionDrag()
            assertNull(viewport.pointerLinkAt(point(1.2)))
            viewport.beginPointerSelection(point(1.2)); session.osc8LinksEnabled = false
            assertNull(viewport.pointerLinkAt(point(1.2)))
            session.osc8LinksEnabled = true
            session.write("\u001b[?2026h\u001b[Hchanged")
            viewport.beginPointerSelection(point(1.2))
            assertEquals("https://example.org", viewport.pointerLinkAt(point(1.2))?.uri, "Frozen visible target wins over hidden output")
            viewport.dispose()
            assertNull(viewport.pointerLinkAt(point(1.2)))
        } finally { viewport.dispose() }
    }

    @Test fun pointerThresholdsSelectWithinACellAndDoNotTurnDraggedPromptsIntoClicks() {
        val session = TerminalSession(12, 3)
        val viewport = TerminalViewport(session, {})
        val replies = StringBuilder()
        val subscription = session.onInput { replies.append(it.toByteArray().decodeToString()) }
        fun point(x: Double, row: Int = 0) = terminalSelectionPointer(x * 10, row * 20.0 + 10, 10.0, 20.0, 1.0)
        try {
            session.write("\u001b]133;A;click_events=1\u0007$ \u001b]133;B\u0007hello")
            viewport.beginPointerSelection(point(2.2))
            viewport.extendPointerSelection(point(2.8), false)
            assertEquals("h", viewport.selectedText())
            viewport.extendPointerSelection(point(2.3), false)
            assertEquals("", viewport.selectedText(), "Returning before the threshold clears the pointer selection")
            viewport.endPointerSelection(point(2.3))
            assertEquals("", replies.toString(), "A same-cell drag consumes the prompt click")
            viewport.beginPointerSelection(point(2.2)); viewport.endPointerSelection(point(3.8))
            assertEquals("", viewport.selectedText(), "Release alone does not synthesize a drag")
            assertEquals("", replies.toString())
        } finally { subscription.dispose(); viewport.dispose() }
    }

    @Test fun pointerRectanglesUseCurrentModifiersAndRetainHorizontalPositionDuringAutoscroll() {
        val session = TerminalSession(8, 3).also { it.scrollbackCompression = false }
        session.write((0..9).joinToString("\r\n") { "x${it}yz" })
        val clock = Clock(); val viewport = TerminalViewport(session, {}, clock)
        fun point(x: Double, y: Int) = terminalSelectionPointer(x * 10, y * 20.0 + 10, 10.0, 20.0, 1.0)
        try {
            viewport.beginPointerSelection(point(1.2, 1))
            viewport.extendPointerSelection(point(3.8, -1), true)
            assertEquals("7yz\n8yz", viewport.selectedText())
            clock.advance(50)
            assertEquals("6yz\n7yz\n8yz", viewport.selectedText())
            viewport.selectionModifiersChanged(false)
            clock.advance(50)
            assertEquals("\nx6yz\nx7yz\nx", viewport.selectedText(), "The autoscroll tick reads updated modifiers")
            viewport.endPointerSelection(point(3.8, -1))
            assertEquals(0, clock.pending)
        } finally { viewport.dispose() }
    }

    @Test fun rectangleAutoscrollPreservesColumnsAndAnchorsThroughOutput() {
        val session = TerminalSession(8, 3).also { it.scrollbackCompression = false }
        session.write((0..9).joinToString("\r\n") { "x${it}yz" })
        val clock = Clock(); val viewport = TerminalViewport(session, {}, clock)
        try {
            viewport.beginSelection(1, 1); viewport.extendSelection(3, -1, rectangle = true)
            assertEquals("7y\n8y", viewport.selectedText())
            clock.advance(50)
            assertEquals("6y\n7y\n8y", viewport.selectedText())
            viewport.endSelection(3, -1)
            assertEquals(0, clock.pending)
            session.write("\r\nxAyz")
            assertEquals("6y\n7y\n8y", viewport.selectedText())
            viewport.beginSelection(1, 0, TerminalSelectionMode.WORD)
            viewport.extendSelection(3, 1, rectangle = true)
            assertEquals("x6yz\nx7yz", viewport.selectedText(), "Rectangle geometry applies only to character gestures")
        } finally { viewport.dispose() }
    }

    @Test fun rectanglesCopyAndPaintWholeWideGlyphsOnEachRowAndKeepFrozenShape() {
        val session = TerminalSession(8, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("a界xy\r\nabc界z\r\n0123456")
            viewport.beginSelection(2, 0); viewport.extendSelection(4, 2, rectangle = true)
            assertEquals("界x\nc界\n23", viewport.selectedText())
            val frame = TerminalGpuFrame(); frame.update(viewport, 8.0, 16.0)
            assertEquals(listOf(TerminalRect(8.0, 0.0, 24.0, 16.0, session.theme.selection)),
                frame.rows[0].backgrounds.filter { it.color == session.theme.selection })
            assertEquals(listOf(TerminalRect(16.0, 16.0, 24.0, 16.0, session.theme.selection)),
                frame.rows[1].backgrounds.filter { it.color == session.theme.selection })
            assertEquals(listOf(TerminalRect(16.0, 32.0, 16.0, 16.0, session.theme.selection)),
                frame.rows[2].backgrounds.filter { it.color == session.theme.selection })
            session.write("\u001b[?2026h\u001b[Hchanged")
            assertEquals("界x\nc界\n23", viewport.selectedText())
            frame.update(viewport, 8.0, 16.0)
            assertEquals(listOf(TerminalRect(16.0, 32.0, 16.0, 16.0, session.theme.selection)),
                frame.rows[2].backgrounds.filter { it.color == session.theme.selection })
            viewport.beginSelection(4, 2); viewport.extendSelection(2, 0, rectangle = true)
            assertEquals("界x\nc界\n23", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun rectangleWatchesTrackEachRowAndShapeChangesRestoreInteriorWatches() {
        val session = TerminalSession(8, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("0123456\r\nabcdefg\r\nABCDEFG")
            viewport.beginSelection(2, 0); viewport.extendSelection(4, 2, rectangle = true)
            session.write("\u001b[2;1Hx\u001b[2;7Hy")
            assertEquals("23\ncd\nCD", viewport.selectedText(), "Writes outside the rectangle preserve it")
            viewport.extendSelection(4, 2, rectangle = false)
            assertEquals("23456\nxbcdefy\nABCD", viewport.selectedText())
            session.write("\u001b[2;1Hz")
            assertEquals("", viewport.selectedText(), "An interior row becomes fully watched after changing shape")
            viewport.beginSelection(2, 0); viewport.extendSelection(4, 2, rectangle = true)
            session.write("\u001b[2;3H!")
            assertEquals("", viewport.selectedText(), "Every rectangle row watches its selected columns")
            viewport.beginSelection(2, 0); viewport.extendSelection(4, 2, rectangle = true)
            session.line(1).wrapped = true
            assertEquals("", viewport.selectedText(), "A wrap boundary changes copied text")
        } finally { viewport.dispose() }
    }

    @Test fun outputSelectionExpandsByCommandsAndReturnsToOriginalBlockOnPromptDrag() {
        val session = TerminalSession(30, 6)
        val viewport = TerminalViewport(session, {})
        val text = "\u001b]133;A\u0007$ \u001b]133;B\u0007one\u001b]133;C\u0007\r\nfirst\r\nsecond\r\n" +
            "\u001b]133;A\u0007$ \u001b]133;B\u0007two\u001b]133;C\u0007\r\nthird"
        try {
            session.write(text)
            viewport.beginSelection(1, 1, TerminalSelectionMode.OUTPUT)
            assertEquals("first\nsecond", viewport.selectedText())
            viewport.extendSelection(1, 4)
            assertEquals("first\nsecond\n$ two\nthird", viewport.selectedText())
            viewport.extendSelection(1, 3)
            assertEquals("first\nsecond", viewport.selectedText(), "A non-output destination restores the original block")
            viewport.beginSelection(1, 4, TerminalSelectionMode.OUTPUT); viewport.extendSelection(1, 1)
            assertEquals("first\nsecond\n$ two\nthird", viewport.selectedText())
            viewport.extendSelection(1, 0); assertEquals("third", viewport.selectedText())
            viewport.beginSelection(1, 0, TerminalSelectionMode.OUTPUT); viewport.extendSelection(1, 1)
            assertEquals("", viewport.selectedText(), "Prompt origin cannot start an output selection")
            session.write("\u001b[?2026h\u001b[Hchanged")
            viewport.beginSelection(1, 1, TerminalSelectionMode.OUTPUT)
            assertEquals("first\nsecond", viewport.selectedText())
            viewport.extendSelection(1, 4)
            assertEquals("first\nsecond\n$ two\nthird", viewport.selectedText())
            session.write("\u001b[?2026l"); session.reset(); session.write("no shell markers")
            viewport.beginSelection(1, 0, TerminalSelectionMode.OUTPUT)
            assertEquals("", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun wordLookupReadsVisibleWordsWithoutChangingSelectionOrGesture() {
        val session = TerminalSession(30, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("alpha-beta gamma")
            viewport.beginSelection(0, 0); viewport.extendSelection(5, 0)
            assertEquals(TerminalWordLookup("gamma", TerminalCellPosition(0, 11)), viewport.lookupWord(12, 0))
            assertEquals("alpha", viewport.selectedText())
            viewport.extendSelection(10, 0); assertEquals("alpha-beta", viewport.selectedText())
            session.selectionWordChars = "- "
            assertEquals(TerminalWordLookup("beta", TerminalCellPosition(0, 6)), viewport.lookupWord(7, 0))
            assertNull(viewport.lookupWord(25, 0)); assertNull(viewport.lookupWord(-1, 0))
            session.write("\u001b[?2026h\u001b[Hchanged")
            assertEquals("beta", viewport.lookupWord(7, 0)?.text)
            session.write("\u001b[?2026l"); session.reset(); session.write("https://example.org")
            assertEquals("https://example.org", viewport.lookupWord(2, 0)?.text, "Custom delimiters apply to lookup")
            session.selectionWordChars = " :/"
            assertEquals("https", viewport.lookupWord(2, 0)?.text, "Lookup has no detected-URL override")
        } finally { viewport.dispose() }
    }

    @Test fun lookupAnchorsClipToTheViewportWhileRetainingWholeWords() {
        val session = TerminalSession(5, 3, 20)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("abcdefghijklmnopq")
            assertEquals(TerminalWordLookup("abcdefghijklmnopq", TerminalCellPosition(0, 0)), viewport.lookupWord(1, 0))
            viewport.selectAll()
            assertEquals(TerminalCellPosition(0, 0), viewport.selectionForLookup()?.start)
            session.reset(); session.write((0..10).joinToString("\r\n") { "r$it" })
            viewport.beginSelection(0, 2, TerminalSelectionMode.WORD); viewport.endSelection(0, 2)
            assertEquals("r10", viewport.selectionForLookup()?.text)
            viewport.scrollTo(session.historySize)
            assertNull(viewport.selectionForLookup()?.start, "Offscreen selection has no viewport anchor")
            assertEquals("r10", viewport.selectionForLookup()?.text)
        } finally { viewport.dispose() }
    }

    @Test fun deepPressSelectsOriginalWordConsumesDragAndStopsAutoscroll() {
        val session = TerminalSession(30, 4).also { it.scrollbackCompression = false }
        val clock = Clock()
        val viewport = TerminalViewport(session, {}, clock)
        try {
            session.write((0..30).joinToString("\r\n") { "alpha-$it beta" })
            viewport.beginSelection(2, 1); viewport.extendSelection(8, -2)
            assertTrue(clock.pending > 0)
            val original = session.line(session.historySize + 1).text().substringBefore(' ')
            assertTrue(viewport.deepPressSelection())
            assertEquals(original, viewport.selectedText()); assertEquals(0, clock.pending)
            val offset = viewport.scrollOffset
            viewport.extendSelection(20, 3); clock.advance(1000)
            assertEquals(original, viewport.selectedText()); assertEquals(offset, viewport.scrollOffset)
            assertFalse(viewport.deepPressSelection()); assertFalse(viewport.endPointerSelection(20, 3))
            session.reset(); session.write("https://example.org")
            viewport.beginSelection(2, 0, TerminalSelectionMode.WORD)
            assertEquals("https://example.org", viewport.selectedText())
            assertTrue(viewport.deepPressSelection()); assertEquals("https", viewport.selectedText())
            viewport.beginSelection(2, 0); viewport.cancelSelectionDrag()
            assertFalse(viewport.deepPressSelection())
            viewport.beginSelection(2, 0); session.write("\u001b[?1049h")
            assertFalse(viewport.deepPressSelection(), "Screen switching cancels the original anchor")
        } finally { viewport.dispose() }
    }

    @Test fun deepPressOnBlankConsumesGestureAndFrozenSelectionUsesVisibleText() {
        val session = TerminalSession(20, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("alpha-beta gamma")
            viewport.beginSelection(1, 2); viewport.extendSelection(6, 0)
            val before = viewport.selectedText()
            assertTrue(viewport.deepPressSelection())
            assertEquals(before, viewport.selectedText(), "No word retains the existing selection")
            viewport.extendSelection(0, 0); assertEquals(before, viewport.selectedText())
            session.write("\u001b[?2026h\u001b[Hchanged")
            viewport.beginSelection(7, 0)
            session.selectionWordChars = "- "
            assertTrue(viewport.deepPressSelection()); assertEquals("beta", viewport.selectedText())
            session.write("\u001b[?2026l")
            assertFalse(viewport.deepPressSelection())
        } finally { viewport.dispose() }
    }

    @Test fun wordBoundaryPolicySupportsUnicodeChangesFrozenFramesAndResets() {
        val session = TerminalSession(30, 4)
        val viewport = TerminalViewport(session, {})
        fun select(x: Int): String {
            viewport.beginSelection(x, 0, TerminalSelectionMode.WORD); viewport.endSelection(x, 0)
            return viewport.selectedText()
        }
        try {
            session.write("one-two:three")
            assertEquals("one-two", select(1))
            session.selectionWordChars = "-"
            assertEquals("two:three", select(5))
            assertFailsWith<IllegalArgumentException> { session.selectionWordChars = "\ud800" }
            assertFailsWith<IllegalArgumentException> { session.selectionWordChars = "\udc00" }
            assertEquals("two:three", select(5), "Invalid Unicode must not change the policy")
            session.write("\u001b[?2026h\u001b[Hchanged")
            session.selectionWordChars = ":"
            assertEquals("one-two", select(1), "Frozen selection uses the current host policy and retained cells")
            session.write("\u001b[?2026l")
            session.selectionWordChars = "界😀"
            session.reset(); session.write("a界界b😀😀c")
            assertEquals("界界", select(2))
            assertEquals("😀😀", select(7), "A supplementary code point is one delimiter")
            session.selectionWordChars = ""
            assertEquals("a界界b😀😀c", select(0), "Empty policy joins all written characters")
        } finally { viewport.dispose() }
    }

    @Test fun lineSelectionsRespectSemanticRegionsAndFrozenFrames() {
        val session = TerminalSession(20, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("\u001b]133;A\u0007 p \u001b]133;B\u0007 cmd \u001b]133;C\u0007 out ")
            viewport.beginSelection(4, 0, TerminalSelectionMode.LINE)
            assertEquals("cmd", viewport.selectedText())
            viewport.extendSelection(10, 0)
            assertEquals("cmd  out", viewport.selectedText())
            viewport.extendSelection(1, 0)
            assertEquals("p  cmd", viewport.selectedText())
            viewport.extendSelection(0, 3)
            assertEquals("p  cmd", viewport.selectedText(), "Dragging over a blank region retains the last selection")
            session.write("\u001b[?2026h\u001b[Hchanged")
            viewport.beginSelection(4, 0, TerminalSelectionMode.LINE)
            assertEquals("cmd", viewport.selectedText(), "Frozen cells retain their semantic content")
            session.write("\u001b[?2026l")
            viewport.beginSelection(0, 3, TerminalSelectionMode.LINE)
            assertEquals("", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun directTextExtractionPreservesWholeWideGlyphsAndEmptyRanges() {
        val session = TerminalSession(8, 3)
        session.write("a界éZ")
        assertEquals("界", session.selectedText(0, 2, 0, 3))
        assertEquals("界", session.selectedText(0, 1, 0, 2))
        assertEquals("界é", session.selectedText(0, 2, 0, 4))
        assertEquals("", session.selectedText(0, 2, 0, 2))
        assertEquals("", session.selectedText(0, 3, 0, 1))
        assertEquals("a界éZ", session.selectedText(0, -10, 0, 100))
    }

    @Test fun selectedCellsSurviveUnrelatedOutputAndPaletteButNotAnOverwrite() {
        val session = TerminalSession(16, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("alpha beta")
            viewport.beginSelection(0, 0); viewport.endSelection(5, 0)
            session.write("\u001b[4;1Hprogress\u001b[1;12HX\u001b[6n\u001b[2 q\u001b]4;1;#123456\u0007")
            assertEquals("alpha", viewport.selectedText())
            session.write("\u001b[1;3HZ")
            assertEquals("", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun anchorsMoveIntoHistoryAndSurviveTrimmingBeforeTheSelectedRows() {
        val session = TerminalSession(8, 3, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("one\r\ntwo\r\nthree")
            viewport.beginSelection(0, 1); viewport.endSelection(3, 1)
            for (next in listOf("four", "five", "six", "seven")) {
                session.write("\r\n$next")
                assertEquals("two", viewport.selectedText(), next)
            }
            assertEquals("two", session.line(0).text())
            session.write("\r\neight")
            assertEquals("", viewport.selectedText(), "A recycled anchor must never select new text")
        } finally { viewport.dispose() }
    }

    @Test fun scrollingKeepsTheSameTopRowUntilThatRowIsPruned() {
        val session = TerminalSession(8, 2, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write((0..6).joinToString("\r\n"))
            viewport.scroll(2)
            val top = viewport.visibleText().substringBefore('\n')
            session.write("\r\n7")
            assertEquals(top, viewport.visibleText().substringBefore('\n'))
            session.write("\r\n8")
            assertEquals(session.line(0).text(), viewport.visibleText().substringBefore('\n'))
            assertNotEquals(top, viewport.visibleText().substringBefore('\n'))
            session.write("\u001b[3J")
            assertEquals(0, viewport.scrollOffset)
        } finally { viewport.dispose() }
    }

    @Test fun partialScrollingPreservesCoherentAnchorsAndInvalidatesReplacedInteriors() {
        val session = TerminalSession(8, 5)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("a\r\nb\r\nc\r\nd\r\ne")
            viewport.beginSelection(0, 2); viewport.endSelection(1, 3)
            session.write("\u001b[2;4r\u001b[S")
            assertEquals("c\nd", viewport.selectedText())
            viewport.beginSelection(0, 0); viewport.endSelection(1, 4)
            session.write("\u001b[S")
            assertEquals("", viewport.selectedText())
            viewport.beginSelection(0, 0); viewport.endSelection(1, 0)
            session.write("\u001b[S")
            assertEquals("a", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun screenRoundTripsResetAndResizeCannotResurrectOldSelections() {
        val session = TerminalSession(8, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("old")
            viewport.beginSelection(0, 0); viewport.endSelection(3, 0)
            session.write("\u001b[?47hnew\u001b[?47l")
            assertEquals("", viewport.selectedText())
            viewport.beginSelection(0, 0); viewport.endSelection(3, 0)
            session.resize(10, 4)
            assertEquals("", viewport.selectedText())
            session.write("\r\nline".repeat(8)); viewport.scroll(3)
            session.write("\u001b[?47h\u001b[?47l")
            assertEquals(0, viewport.scrollOffset)
            viewport.scroll(3); session.reset()
            assertEquals(0, viewport.scrollOffset)
        } finally { viewport.dispose() }
    }

    @Test fun wideAndCombinedGlyphsSelectWholeCellsInEitherDirection() {
        val session = TerminalSession(8, 2, graphemeClustering = true)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("A界e\u0301😀Z")
            viewport.beginSelection(2, 0); viewport.endSelection(3, 0)
            assertEquals("界", viewport.selectedText())
            val frame = TerminalGpuFrame(); frame.update(viewport, 8.0, 16.0)
            assertEquals(listOf(TerminalRect(8.0, 0.0, 16.0, 16.0, session.theme.selection)),
                frame.rows[0].backgrounds.filter { it.color == session.theme.selection })
            viewport.beginSelection(3, 0); viewport.endSelection(2, 0)
            assertEquals("界", viewport.selectedText())
            viewport.beginSelection(4, 0); viewport.endSelection(5, 0)
            assertEquals("😀", viewport.selectedText())
            viewport.beginSelection(3, 0); viewport.endSelection(4, 0)
            assertEquals("e\u0301", viewport.selectedText())
            session.write("\u001b[1;5H\u0308")
            assertEquals("", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun wordsAndLogicalLinesSpanSoftWrapsWithoutCrossingHardLineBreaks() {
        val session = TerminalSession(6, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("alpha-beta gamma\r\nnext")
            viewport.beginSelection(2, 0, TerminalSelectionMode.WORD)
            assertEquals("alpha-beta", viewport.selectedText())
            viewport.extendSelection(1, 2)
            assertEquals("alpha-beta gamma", viewport.selectedText())
            viewport.beginSelection(1, 2, TerminalSelectionMode.WORD); viewport.endSelection(1, 0)
            assertEquals("alpha-beta gamma", viewport.selectedText())
            viewport.beginSelection(1, 1, TerminalSelectionMode.LINE)
            assertEquals("alpha-beta gamma", viewport.selectedText())
            viewport.endSelection(1, 3)
            assertEquals("alpha-beta gamma\nnext", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun wordsHandleWhitespacePunctuationWidePaddingAndBidirectionalDragging() {
        val session = TerminalSession(30, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("one  (two);three")
            viewport.beginSelection(3, 0, TerminalSelectionMode.WORD)
            assertEquals("  (", viewport.selectedText())
            viewport.beginSelection(5, 0, TerminalSelectionMode.WORD)
            assertEquals("  (", viewport.selectedText())
            viewport.beginSelection(7, 0, TerminalSelectionMode.WORD); viewport.extendSelection(0, 0)
            assertEquals("one  (two", viewport.selectedText())
            viewport.extendSelection(13, 0)
            assertEquals("two);three", viewport.selectedText())
            viewport.beginSelection(3, 1, TerminalSelectionMode.LINE)
            assertEquals("", viewport.selectedText())
            session.resize(4, 4); session.reset(); session.write("abc界z")
            viewport.beginSelection(1, 1, TerminalSelectionMode.WORD)
            assertEquals("abc界z", viewport.selectedText(), "The wide-glyph padding before a wrap is not whitespace")
            viewport.beginSelection(3, 0, TerminalSelectionMode.WORD)
            assertEquals("abc界z", viewport.selectedText(), "Clicking the padding selects the word that crosses it")
        } finally { viewport.dispose() }
    }

    @Test fun changingAWrapOnlyInvalidatesSelectionsThatContainItsLineBoundary() {
        val session = TerminalSession(8, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("keep xx")
            viewport.beginSelection(0, 0); viewport.endSelection(4, 0)
            session.write("YZ") // Completes row 0 and soft-wraps; "keep" is untouched.
            assertEquals("keep", viewport.selectedText())
            viewport.beginSelection(0, 0); viewport.endSelection(1, 1)
            assertEquals("keep xxYZ", viewport.selectedText())
            session.write("\u001b[1;8H\u001b[K")
            assertEquals("", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun independentSelectionsObserveOnlyTheirOwnCellsAndClearAllPaintedRows() {
        val session = TerminalSession(12, 3)
        val one = TerminalViewport(session, {}); val two = TerminalViewport(session, {})
        try {
            session.write("first second\r\nthird")
            one.beginSelection(0, 0); one.endSelection(5, 1)
            two.beginSelection(6, 0); two.endSelection(12, 0)
            session.write("\u001b[2;2HZ")
            assertEquals("", one.selectedText()); assertEquals("second", two.selectedText())
            val frame = TerminalGpuFrame(); frame.update(one, 8.0, 16.0)
            assertTrue(frame.rows.flatMap { it.backgrounds }.none { it.color == session.theme.selection })
            one.dispose(); session.write("\u001b[1;2HX")
            assertEquals("second", two.selectedText())
        } finally { one.dispose(); two.dispose() }
    }

    @Test fun synchronizedFramesKeepSelectionTextEvenOutsideTheVisibleSnapshot() {
        val session = TerminalSession(8, 3, 20)
        val viewport = TerminalViewport(session, {})
        try {
            session.write((0..9).joinToString("\r\n"))
            viewport.scroll(5); viewport.beginSelection(0, 0)
            viewport.scroll(-5); viewport.endSelection(1, 2)
            val selected = viewport.selectedText()
            assertEquals((2..9).joinToString("\n"), selected)
            session.write("\u001b[?2026h\r\nnew".repeat(30))
            assertEquals(selected, viewport.selectedText(), "Copy uses the captured frame, not recycled live rows")
            session.write("\u001b[?2026l")
            assertEquals("", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    // Timer counts below cover autoscroll; idle compaction has its own scheduling tests.
    @Test fun autoscrollExtendsSelectionAndStopsOnReleaseCancelFocusLossAndDispose() {
        for (stop in listOf<(TerminalViewport) -> Unit>({ it.endSelection(0, -2) },
            { it.cancelSelectionDrag() }, { it.setFocused(false) }, { it.dispose() })) {
            val session = TerminalSession(8, 3).also { it.scrollbackCompression = false }
            session.write((0..20).joinToString("\r\n"))
            val clock = Clock(); val viewport = TerminalViewport(session, {}, clock)
            try {
                viewport.beginSelection(0, 2); viewport.extendSelection(0, -2)
                assertEquals(1, clock.pending)
                clock.advance(100)
                assertEquals(4, viewport.scrollOffset)
                assertEquals((14..19).joinToString("\n", postfix = "\n"), viewport.selectedText())
                stop(viewport); assertEquals(0, clock.pending)
                val offset = viewport.scrollOffset
                clock.advance(1000); assertEquals(offset, viewport.scrollOffset)
            } finally { viewport.dispose() }
        }
    }

    @Test fun autoscrollUsesTheLatestDirectionAndStopsAtHistoryLimits() {
        val session = TerminalSession(8, 3).also { it.scrollbackCompression = false }
        session.write((0..20).joinToString("\r\n"))
        val clock = Clock(); val viewport = TerminalViewport(session, {}, clock)
        try {
            viewport.scroll(5); viewport.beginSelection(0, 1); viewport.extendSelection(0, -2)
            viewport.extendSelection(8, 4)
            clock.advance(50)
            assertEquals(3, viewport.scrollOffset)
            clock.advance(500)
            assertEquals(0, viewport.scrollOffset); assertEquals(0, clock.pending)
            viewport.extendSelection(0, -100)
            clock.advance(1000)
            assertEquals(session.historySize, viewport.scrollOffset); assertEquals(0, clock.pending)
            viewport.extendSelection(0, 1)
            assertEquals(0, clock.pending)
        } finally { viewport.dispose() }
    }

    @Test fun largeSelectionsDoNotRescanHistoryOnOutputOrSmallDragChanges() {
        val rows = Array(10_000) { TerminalLine(2).also { row -> row.historySlot = it } }
        var reads = 0
        val selection = TerminalSelection({ 0 }, { rows.lastIndex }, { reads++; rows[it] }, { reads++; it.historySlot })
        try {
            selection.begin(TerminalCellPosition(0, 0), TerminalSelectionMode.CHARACTER)
            selection.extend(TerminalCellPosition(rows.lastIndex, 1))
            reads = 0
            repeat(100) { assertNotNull(selection.range()) }
            assertTrue(reads <= 300, "Output validation should resolve only endpoint anchors: $reads")
            reads = 0
            selection.extend(TerminalCellPosition(rows.lastIndex - 1, 1))
            assertTrue(reads < 20, "Dragging one row should update only its edge watches: $reads")
        } finally { selection.clear() }
    }

    private class Clock : TerminalScheduler {
        private class Job(val time: Long, val action: () -> Unit)
        private var time = 0L
        private val jobs = mutableListOf<Job>()
        val pending: Int get() = jobs.size
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
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
