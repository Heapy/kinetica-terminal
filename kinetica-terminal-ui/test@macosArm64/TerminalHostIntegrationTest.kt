@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import io.heapy.kinetica.appkit.renderAppKitApp
import platform.AppKit.*
import platform.Foundation.NSMakeRect
import kotlin.test.*

class TerminalHostIntegrationTest {
    @Test fun hostRetainsStandaloneSurfaceAndUpdatesItsFont() {
        NSApplication.sharedApplication()
        val root = NSView(NSMakeRect(0.0, 0.0, 640.0, 320.0))
        val session = TerminalSession()
        var size = 14.0
        val app = renderAppKitApp(root, hostWidgets = appKitTerminalHosts(
            mapOf("shell" to session), rendering = TerminalRendering.SOFTWARE, controls = true,
        )) { terminal("shell", fontSize = size) }
        try {
            val pane = root.subviews.single() as AppKitTerminalPane
            session.write("retained output")
            size = 18.0
            app.render()
            assertEquals(pane, root.subviews.single())
            assertSame(session, pane.terminal.session)
            assertEquals(18.0, pane.terminal.currentFontSize)
            assertContains(pane.terminal.accessibilityValue().toString(), "retained output")
        } finally { app.dispose() }
        // The framework only owns the surface; the application can continue using its session.
        session.write(" after unmount")
        assertContains(session.screenLine(0).text(), "after unmount")
    }
}
