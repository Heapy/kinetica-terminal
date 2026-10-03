package io.heapy.kinetica.terminal

import io.heapy.kinetica.HostNode
import io.heapy.kinetica.render.HostWidget
import io.heapy.kinetica.render.HostWidgetFactory
import org.w3c.dom.Element

/** Sessions are application-owned; unmounting a surface only releases its UI resources. */
public fun browserTerminalHosts(
    sessions: Map<String, TerminalSession>,
    rendering: TerminalRendering = TerminalRendering.AUTO,
    renderObserver: TerminalRenderObserver? = null,
): Map<String, HostWidgetFactory<Element>> = mapOf(TERMINAL_HOST_TAG to HostWidgetFactory { node ->
    val id = node.props.getValue("session")
    val terminal = BrowserTerminalView(sessions.getValue(id), id, rendering, renderObserver)
    object : HostWidget<Element> {
        override val view: Element = terminal.view
        override fun update(node: HostNode) {
            require(node.props["session"] == id) { "Changing terminal sessions requires changing the host key" }
            terminal.configureFont(node.props["fontFamily"]?.takeIf { it.isNotBlank() },
                node.props["fontSize"]?.toDoubleOrNull() ?: 14.0)
        }
        override fun dispose() = terminal.dispose()
    }
})
