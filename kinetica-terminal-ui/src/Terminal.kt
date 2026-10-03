package io.heapy.kinetica.terminal

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.host

public const val TERMINAL_HOST_TAG: String = "kinetica:terminal"

/** Bind [sessionId] to a session in browserTerminalHosts/appKitTerminalHosts on the renderer. */
public fun ComponentScope.terminal(
    sessionId: String,
    key: String = sessionId,
    fontSize: Double = 14.0,
    semantics: Semantics = Semantics(label = "Terminal", focusable = true),
    fontFamily: String? = null,
) {
    require(sessionId.isNotBlank())
    require(fontSize.isFinite() && fontSize in 6.0..96.0)
    require(fontFamily == null || fontFamily.isNotBlank() && fontFamily.length <= 256 && fontFamily.none { it.code < 32 })
    host(TERMINAL_HOST_TAG, props = mapOf("session" to sessionId, "fontSize" to fontSize.toString(),
        "fontFamily" to fontFamily.orEmpty()), key = key, semantics = semantics)
}
