@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import io.heapy.kinetica.HostNode
import io.heapy.kinetica.render.HostWidget
import io.heapy.kinetica.render.HostWidgetFactory
import platform.AppKit.NSView

public fun appKitTerminalHosts(
    sessions: Map<String, TerminalSession>,
    optionAsMeta: Boolean = false,
    rendering: TerminalRendering = TerminalRendering.AUTO,
    renderObserver: TerminalRenderObserver? = null,
    controls: Boolean = false,
    onView: (String, AppKitTerminalView?) -> Unit = { _, _ -> },
): Map<String, HostWidgetFactory<NSView>> = mapOf(TERMINAL_HOST_TAG to HostWidgetFactory { node ->
    val id = node.props.getValue("session")
    val terminal = AppKitTerminalView(sessions.getValue(id), id, optionAsMeta, rendering, renderObserver)
    val pane = if (controls) AppKitTerminalPane(terminal) else null
    onView(id, terminal)
    object : HostWidget<NSView> {
        override val view: NSView = pane ?: terminal
        override fun update(node: HostNode) {
            require(node.props["session"] == id) { "Changing terminal sessions requires changing the host key" }
            terminal.configureFont(node.props["fontFamily"]?.takeIf { it.isNotBlank() },
                node.props["fontSize"]?.toDoubleOrNull() ?: 14.0)
        }
        override fun requestFocus(): Boolean = terminal.window?.makeFirstResponder(terminal) == true
        override fun dispose() {
            try { onView(id, null) } finally { pane?.dispose() ?: terminal.dispose() }
        }
    }
})
