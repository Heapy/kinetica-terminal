package app.native.terminal

import io.heapy.kinetica.*

@UiComponent
internal fun ComponentScope.TerminalAboutContent(close: () -> Unit) {
    host("column", props = mapOf("padding" to "24", "spacing" to "12")) {
        host("appkit:label", props = mapOf("value" to "Kinetica Terminal", "fontSize" to "24"))
        host("appkit:label", props = mapOf("value" to "Version ${terminalApplicationVersion()}", "secondary" to "true"))
        text("A native macOS terminal built with Kotlin.")
        text("Powered by Kinetica.")
        host("appkit:label", props = mapOf("value" to "Licensed under Apache 2.0.", "secondary" to "true"))
        row {
            host("appkit:spacer")
            button(onClick = close, semantics = Semantics(label = "Close About window", testTag = "about-close")) {
                text("Close")
            }
        }
    }
}
