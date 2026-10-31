@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsCompositionTest {
    @Test fun inputSourceSwitchDuringInterpretationDoesNotSendTextCommandsOrKeyRelease() = withView { session, view, window, out ->
        for (mode in listOf("legacy", "modify2", "kitty31")) {
            session.reset()
            session.write(when (mode) { "modify2" -> "\u001b[>4;2m"; "kitty31" -> "\u001b[>31u"; else -> "" })
            for ((before, after) in listOf("us" to "ru", "us" to null, null to "us")) {
                for (command in listOf(false, true)) {
                    var layout: String? = before
                    val code = if (command) 36 else 0
                    val text = if (command) "\r" else "a"
                    out.clear()
                    view.interpretTerminalKey(event(window, code, text), keyboardLayoutId = { layout }) {
                        layout = after
                        if (command) view.doCommandBySelector(NSSelectorFromString("insertNewline:"))
                        else view.insertText("a", absentRange())
                    }
                    view.keyUp(event(window, code, text, release = true))
                    assertBytes("", out.toString(), "$mode $before -> $after command=$command")
                    view.insertText("next", absentRange())
                    assertBytes("next", out.toString(), "Suppressed events must release the interpretation transaction")
                }
            }
        }
    }

    @Test fun unchangedOrUnavailableInputSourcePreservesTextAndExistingPreeditSkipsTheQuery() = withView { session, view, window, out ->
        session.write("\u001b[>31u")
        for (layout in listOf("us", null)) {
            var queries = 0
            out.clear()
            view.interpretTerminalKey(event(window, 0, "a"), keyboardLayoutId = { queries++; layout }) {
                view.insertText("a", absentRange())
            }
            view.releaseTerminalKey(event(window, 0, "a", release = true), unshiftedCodepoint = 97)
            assertBytes("\u001b[97;;97u\u001b[97;1:3u", out.toString())
            assertEquals(2, queries, "Compare input source before and after interpretation")
        }
        out.clear(); mark(view)
        view.interpretTerminalKey(event(window, 36, "\r"), keyboardLayoutId = { fail("Existing preedit must not query the layout") }) {
            view.insertText("한", absentRange())
        }
        assertBytes("한", out.toString())
    }

    @Test fun committedPreeditPrecedesOnlyTheNavigationKeysGhosttyReplays() = withView { session, view, window, out ->
        for (mode in listOf("legacy", "modify2", "kitty31")) for (repeat in listOf(false, true)) {
            session.reset()
            session.write(when (mode) { "modify2" -> "\u001b[>4;2m"; "kitty31" -> "\u001b[>31u"; else -> "" })
            for ((code, chars, final) in listOf(Triple(123, "\uf702", "D"), Triple(124, "\uf703", "C"),
                Triple(125, "\uf701", "B"), Triple(126, "\uf700", "A"), Triple(36, "\r", ""),
                Triple(48, "\t", ""), Triple(51, "\u007f", ""), Triple(53, "\u001b", ""), Triple(0, "a", ""))) {
                for (flags in listOf(0uL, NSEventModifierFlagShift, NSEventModifierFlagControl)) {
                    mark(view); out.clear()
                    val key = event(window, code, chars, flags, repeat)
                    view.interpretTerminalKey(key) {
                        view.insertText("한", absentRange()); view.insertText("국", absentRange())
                        assertEquals("", out.toString(), "Callbacks are accumulated until interpretation ends")
                    }
                    val modifier = 1 + (if (flags and NSEventModifierFlagShift != 0uL) 1 else 0) +
                        (if (flags and NSEventModifierFlagControl != 0uL) 4 else 0)
                    val replay = final.isNotEmpty() && (code != 123 || flags != 0uL)
                    val suffix = when {
                        !replay -> ""
                        mode == "kitty31" -> "\u001b[1;$modifier:${if (repeat) 2 else 1}$final"
                        modifier != 1 -> "\u001b[1;$modifier$final"
                        else -> "\u001b[$final"
                    }
                    assertBytes("한국$suffix", out.toString(), "$mode repeat=$repeat key=$code flags=$flags")
                }
            }
        }
    }

    @Test fun controlInputAndCommandCallbacksBelongToTheCompositionEvenWhenItEnds() = withView { session, view, window, out ->
        session.write("\u001b[>31u")
        for (point in 0..31) {
            mark(view); out.clear()
            view.interpretTerminalKey(event(window, 36, "\r")) { view.insertText(point.toChar().toString(), absentRange()) }
            assertBytes("", out.toString(), "single composing C0=$point")
        }
        for (text in listOf("\u007f", "\u0085", "\r\n", "\b字", "😀")) {
            mark(view); out.clear()
            view.interpretTerminalKey(event(window, 36, "\r")) { view.insertText(text, absentRange()) }
            assertBytes(text, out.toString(), "Only a single C0 scalar is suppressed")
        }
        for (command in listOf("insertNewline:", "insertTab:", "deleteBackward:", "cancelOperation:")) {
            mark(view); out.clear()
            view.doCommandBySelector(NSSelectorFromString(command))
            assertBytes("", out.toString(), "Direct command while composing: $command")
            view.interpretTerminalKey(event(window, 51, "\u007f")) {
                view.unmarkText(); view.doCommandBySelector(NSSelectorFromString(command))
            }
            assertBytes("", out.toString(), "A command that cancels composition must not delete preceding terminal text")
        }
    }

    @Test fun partialCommitKeepsTheNewPreeditAndPreservesModifiedLeftReplay() = withView { session, view, window, out ->
        session.write("\u001b[>31u")
        for ((flags, modifier) in listOf(NSEventModifierFlagShift to 2, NSEventModifierFlagControl to 5,
            NSEventModifierFlagOption to 3, NSEventModifierFlagCommand to 9)) {
            mark(view); out.clear()
            view.interpretTerminalKey(event(window, 123, "\uf702", flags)) {
                view.insertText("한", absentRange()); mark(view, "국")
            }
            assertBytes("한\u001b[1;$modifier:1D", out.toString())
            assertTrue(view.hasMarkedText())
        }
    }

    @Test fun ordinaryInputCommandsAndFailuresDoNotRetainAnImeTransaction() = withView { session, view, window, out ->
        session.write("\u001b[>31u")
        view.interpretTerminalKey(event(window, 0, "a")) { view.insertText("a", absentRange()) }
        assertBytes("\u001b[97;;97u", out.toString()); out.clear()
        view.interpretTerminalKey(event(window, 36, "\r")) { view.doCommandBySelector(NSSelectorFromString("insertNewline:")) }
        assertBytes("\u001b[13u", out.toString()); out.clear()
        assertFailsWith<IllegalStateException> {
            view.interpretTerminalKey(event(window, 0, "a")) { error("input method failure") }
        }
        view.insertText("dictated", absentRange()); assertBytes("dictated", out.toString()); out.clear()
        session.keyboardLockAllowed = true; session.write("\u001b[2h")
        view.interpretTerminalKey(event(window, 0, "a")) { fail("Locked input must not enter the input method") }
        assertBytes("", out.toString())
    }

    private fun mark(view: AppKitTerminalView, text: String = "한") =
        view.setMarkedText(text, NSMakeRange(text.length.toULong(), 0u), absentRange())
    private fun absentRange() = NSMakeRange(NSNotFound.toULong(), 0u)
    private fun event(window: NSWindow, code: Int, text: String, flags: ULong = 0uL, repeat: Boolean = false, release: Boolean = false) =
        assertNotNull(NSEvent.keyEventWithType(if (release) NSEventTypeKeyUp else NSEventTypeKeyDown, NSMakePoint(0.0, 0.0), flags,
            0.0, window.windowNumber, null, text, text, repeat, code.toUShort()))
    private fun assertBytes(expected: String, actual: String, message: String? = null) =
        assertEquals(expected.encodeToByteArray().toList(), actual.encodeToByteArray().toList(), message)
    private fun withView(test: (TerminalSession, AppKitTerminalView, NSWindow, StringBuilder) -> Unit) {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val out = StringBuilder()
        val observation = session.onInput { out.append(it.toByteArray().decodeToString()) }
        try { assertTrue(window.makeFirstResponder(view)); test(session, view, window, out) }
        finally { observation.dispose(); view.dispose(); window.close() }
    }
}
