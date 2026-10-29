// AppKit mappings follow Ghostty's SurfaceView_AppKit.swift / Cursor.swift.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// See ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import platform.AppKit.NSCursor

/** Like Ghostty's macOS host, unavailable shapes preserve the previously displayed cursor. */
internal fun nativePointerCursor(shape: TerminalPointerShape): NSCursor? = when (shape) {
    TerminalPointerShape.DEFAULT -> NSCursor.arrowCursor
    TerminalPointerShape.TEXT -> NSCursor.IBeamCursor
    TerminalPointerShape.GRAB -> NSCursor.openHandCursor
    TerminalPointerShape.GRABBING -> NSCursor.closedHandCursor
    TerminalPointerShape.POINTER -> NSCursor.pointingHandCursor
    TerminalPointerShape.W_RESIZE -> NSCursor.resizeLeftCursor
    TerminalPointerShape.E_RESIZE -> NSCursor.resizeRightCursor
    TerminalPointerShape.N_RESIZE -> NSCursor.resizeUpCursor
    TerminalPointerShape.S_RESIZE -> NSCursor.resizeDownCursor
    TerminalPointerShape.NS_RESIZE -> NSCursor.resizeUpDownCursor
    TerminalPointerShape.EW_RESIZE -> NSCursor.resizeLeftRightCursor
    TerminalPointerShape.VERTICAL_TEXT -> NSCursor.IBeamCursorForVerticalLayout
    TerminalPointerShape.CONTEXT_MENU -> NSCursor.contextualMenuCursor
    TerminalPointerShape.CROSSHAIR -> NSCursor.crosshairCursor
    TerminalPointerShape.NOT_ALLOWED -> NSCursor.operationNotAllowedCursor
    else -> null
}
