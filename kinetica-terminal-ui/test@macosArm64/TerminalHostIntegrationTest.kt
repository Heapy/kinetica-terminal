@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import io.heapy.kinetica.appkit.renderAppKitApp
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class TerminalHostIntegrationTest {
    @Test fun hostRetainsStandaloneSurfaceAndUpdatesItsFont() {
        NSApplication.sharedApplication()
        val root = NSView(NSMakeRect(0.0, 0.0, 640.0, 320.0))
        val window = NSWindow(NSMakeRect(0.0,0.0,640.0,320.0),NSWindowStyleMaskTitled,NSBackingStoreBuffered,false)
        window.setReleasedWhenClosed(false); window.contentView = root
        val session = TerminalSession()
        val output = StringBuilder()
        val input = session.onInput { output.append(it.toByteArray().decodeToString()) }
        var size = 14.0
        val app = renderAppKitApp(root, hostWidgets = appKitTerminalHosts(
            mapOf("shell" to session), rendering = TerminalRendering.SOFTWARE, controls = true,
            optionAsAlt = AppKitTerminalOptionAsAlt.RIGHT,
        )) { terminal("shell", fontSize = size) }
        try {
            val pane = root.subviews.single() as AppKitTerminalPane
            assertTrue(window.makeFirstResponder(pane.terminal))
            for (side in listOf(0x20uL,0x40uL)) {
                output.clear()
                val event = requireNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown,NSMakePoint(0.0,0.0),
                    NSEventModifierFlagOption or side,0.0,window.windowNumber,null,"å","a",false,0u))
                pane.terminal.keyDown(event)
                assertEquals(if (side == 0x40uL) "\u001b${event.charactersByApplyingModifiers(0uL)}" else "å",output.toString())
            }
            session.write("retained output")
            size = 18.0
            app.render()
            assertEquals(pane, root.subviews.single())
            assertSame(session, pane.terminal.session)
            assertEquals(18.0, pane.terminal.currentFontSize)
            assertContains(pane.terminal.accessibilityValue().toString(), "retained output")
        } finally { input.dispose(); app.dispose(); window.close() }
        // The framework only owns the surface; the application can continue using its session.
        session.write(" after unmount")
        assertContains(session.screenLine(0).text(), "after unmount")
    }
}
