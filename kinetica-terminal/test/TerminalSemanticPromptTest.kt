package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalSemanticPromptTest {
    private fun osc(value: String) = "\u001b]133;$value\u0007"

    @Test fun hostClearScreenRetainsCurrentPromptAndRemovesRecycledMarkers() {
        val session = TerminalSession(12, 4)
        session.write(osc("A") + "first\r\n" + osc("A") + "second\r\n" + osc("A") + "$ ")
        session.clearScreen()
        assertEquals("$", session.screenLine(0).text().trimEnd())
        assertEquals(TerminalPromptMarker.PROMPT, session.screenLine(0).promptMarker)
        assertEquals(TerminalSemanticContent.PROMPT, session.screenLine(0).semanticContent(0))
        for (row in 1 until session.rows) assertEquals(TerminalPromptMarker.NONE, session.screenLine(row).promptMarker)
    }

    @Test fun promptNavigationSkipsContinuationsAndRespectsHoldsAndDisposal() {
        val session = TerminalSession(8, 3)
        val viewport = TerminalViewport(session, {})
        repeat(8) { session.write(osc("A") + "p$it\r\n" + osc("P;k=c") + "> " + osc("B") + "in\r\n" + osc("C") + "out\r\n") }
        assertTrue(viewport.jumpToPrompt(-1))
        val first = session.historySize - viewport.scrollOffset
        assertEquals(TerminalPromptMarker.PROMPT, session.line(first).promptMarker)
        assertTrue(viewport.jumpToPrompt(-1))
        assertEquals(first - 3, session.historySize - viewport.scrollOffset)
        assertTrue(viewport.jumpToPrompt(1))
        assertEquals(first, session.historySize - viewport.scrollOffset)
        session.write("\u001b[?2026h")
        assertFalse(viewport.jumpToPrompt(-1))
        session.flushSynchronizedOutput()
        session.write("\u001b[?1049h")
        assertFalse(viewport.jumpToPrompt(-1))
        session.write("\u001b[?1049l")
        viewport.dispose()
        assertFalse(viewport.jumpToPrompt(-1))
    }

    @Test fun eventsPreserveCommandMetadataAndApplyBeforeNotification() {
        val session = TerminalSession()
        val events = mutableListOf<TerminalSemanticPrompt>()
        val observation = session.onSemanticPrompt {
            events += it
            if (it.action == TerminalPromptAction.NEW_COMMAND) assertEquals(TerminalSemanticContent.PROMPT, session.cursorSemanticContent)
        }
        session.write("\u001b[?2026h" + osc("N;aid=task;redraw=last;cl=line;click_events=2;special_key=1"))
        assertTrue(session.hasSemanticPrompts); assertTrue(session.cursorAtPrompt)
        assertEquals(TerminalPromptRedraw.LAST, session.promptRedraw)
        assertEquals(TerminalPromptClick.RELATIVE, session.promptClickMode)
        assertEquals("task", events.single().commandId); assertEquals(true, events.single().specialKey)
        session.write(osc("P;k=s;redraw=0;cl=w"))
        assertEquals(TerminalPromptRedraw.LAST, session.promptRedraw)
        assertEquals(TerminalPromptClick.RELATIVE, session.promptClickMode)
        session.write(osc("C;cmdline_url=echo%20hello%3B%20true;aid=task"))
        assertContentEquals("echo hello; true".encodeToByteArray(), events.last().commandLineBytes())
        session.write(osc("D;-2;err=failure;aid=task"))
        assertEquals(-2, events.last().exitCode); assertEquals("failure", events.last().error)
        assertEquals(TerminalSemanticContent.OUTPUT, session.cursorSemanticContent)
        observation.dispose(); session.write(osc("A")); assertEquals(4, events.size)
    }

    @Test fun optionPrecedenceAndScreenOwnershipAreStable() {
        val session = TerminalSession()
        assertEquals(TerminalPromptRedraw.NONE, session.promptRedraw)
        session.write(osc("A;k=wrong;k=r;redraw=bad;redraw=1;click_events=0;click_events=2;cl=v"))
        assertEquals(TerminalPromptMarker.PROMPT, session.screenLine(0).promptMarker)
        assertEquals(TerminalPromptRedraw.NONE, session.promptRedraw)
        assertEquals(TerminalPromptClick.CONSERVATIVE_VERTICAL, session.promptClickMode)
        session.write("\u001b[?1049h")
        assertFalse(session.cursorAtPrompt); assertFalse(session.hasSemanticPrompts)
        assertNull(session.promptClickMode)
        session.write(osc("A;click_events=1") + "\u001b[?1049l")
        assertEquals(TerminalPromptClick.CONSERVATIVE_VERTICAL, session.promptClickMode)
        session.reset()
        assertFalse(session.hasSemanticPrompts); assertFalse(session.cursorAtPrompt)
        assertNull(session.promptClickMode)
        // RIS restores Terminal defaults, as distinct from libghostty-vt's initialization override.
        assertEquals(TerminalPromptRedraw.ALL, session.promptRedraw)
    }

    @Test fun commandDecodingMatchesPinnedGhosttyEscapesAndPreservesBinaryBytes() {
        fun decoded(option: String) = parseSemanticPrompt("C;$option")!!.commandLineBytes()
        assertContentEquals("echo a b\n\t\u001b\u000b\r\\\"'$".encodeToByteArray(),
            decoded("cmdline=$'echo a\\ b\\n\\t\\e\\v\\r\\\\\\\"\\'\\$'"))
        assertContentEquals("plain text".encodeToByteArray(), decoded("cmdline='plain text'"))
        assertContentEquals(byteArrayOf(0, -1, 43, 32), decoded("cmdline_url=%00%FF+%20"))
        assertContentEquals(byteArrayOf(), decoded("cmdline=;cmdline_url=fallback"))
        assertContentEquals("first".encodeToByteArray(), decoded("cmdline=first;cmdline=second"))
        for (option in listOf("cmdline=$'", "cmdline='", "cmdline=a\\", "cmdline=\\x41", "cmdline=\\d;cmdline_url=valid",
            "cmdline_url=%", "cmdline_url=%f", "cmdline_url=%xz")) assertNull(decoded(option), option)
        assertNull(parseSemanticPrompt("C")!!.commandLineBytes())
    }

    @Test fun malformedAndOversizedCommandsDoNotEmitEvents() {
        val session = TerminalSession()
        val events = mutableListOf<TerminalSemanticPrompt>()
        session.onSemanticPrompt(events::add)
        for (value in listOf("", "X", "AA", "Ainvalid", "L;", "B;" + "я".repeat(1024))) session.write(osc(value))
        assertTrue(events.isEmpty()); assertFalse(session.hasSemanticPrompts)
        session.write(osc("A;" + "я".repeat(1023)))
        assertEquals(1, events.size)
        assertTrue(session.hasSemanticPrompts)
    }
}
