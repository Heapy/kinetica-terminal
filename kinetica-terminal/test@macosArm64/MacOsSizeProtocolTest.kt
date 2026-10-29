@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import kotlinx.cinterop.useContents
import platform.AppKit.*
import platform.Foundation.*
import kotlin.math.ceil
import kotlin.test.*

class MacOsSizeProtocolTest {
    @Test fun reportsCommittedPhysicalGeometryAndDefersFontChangesDuringAtomicOutput() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = StringBuilder()
        val subscription = session.onInput { output.append(it.toByteArray().decodeToString()) }
        fun expected(size: Double): String {
            val font = terminalNativeFont(null, size)
            val width = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
            val height = ceil(font.ascender - font.descender + font.leading + 2.0)
            val scale = window.backingScaleFactor
            return "\u001b[48;${session.rows};${session.columns};${session.rows * (height * scale).toInt()};${session.columns * (width * scale).toInt()}t"
        }
        try {
            view.viewDidChangeBackingProperties()
            session.write("\u001b[?2048h")
            val original = expected(14.0)
            assertEquals(original, output.toString())
            output.clear()
            session.write("\u001b[?2026h")
            view.configureFont(null, 18.0)
            assertTrue(session.synchronizedOutput)
            assertTrue(output.isEmpty())
            session.write("\u001b[?2048h")
            assertEquals(original, output.toString())
            output.clear()
            session.write("\u001b[?2026l")
            assertEquals(expected(18.0), output.toString())
            assertNotEquals(original, output.toString())
            output.clear()
            session.write("\u001b[?2048l")
            view.configureFont(null, 20.0)
            assertTrue(output.isEmpty())
        } finally { subscription.dispose(); view.dispose(); window.close() }
    }
}
