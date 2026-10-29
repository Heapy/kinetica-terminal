// Names and aliases adapted from Ghostty terminal/mouse.zig at the pinned revision.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// See ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** Application-requested pointer shape (OSC 22), independent of the terminal text cursor. */
public enum class TerminalPointerShape {
    DEFAULT, CONTEXT_MENU, HELP, POINTER, PROGRESS, WAIT, CELL, CROSSHAIR,
    TEXT, VERTICAL_TEXT, ALIAS, COPY, MOVE, NO_DROP, NOT_ALLOWED, GRAB, GRABBING,
    ALL_SCROLL, COL_RESIZE, ROW_RESIZE, N_RESIZE, E_RESIZE, S_RESIZE, W_RESIZE,
    NE_RESIZE, NW_RESIZE, SE_RESIZE, SW_RESIZE, EW_RESIZE, NS_RESIZE, NESW_RESIZE,
    NWSE_RESIZE, ZOOM_IN, ZOOM_OUT;

    public val cssName: String = name.lowercase().replace('_', '-')

    internal companion object {
        fun parse(value: String): TerminalPointerShape? = entries.firstOrNull { it.cssName == value } ?: when (value) {
            "" -> TEXT
            "left_ptr" -> DEFAULT
            "question_arrow" -> HELP
            "hand" -> POINTER
            "left_ptr_watch" -> PROGRESS
            "watch" -> WAIT
            "cross" -> CROSSHAIR
            "xterm" -> TEXT
            "dnd-link" -> ALIAS
            "dnd-copy" -> COPY
            "dnd-move" -> MOVE
            "dnd-no-drop" -> NO_DROP
            "crossed_circle" -> NOT_ALLOWED
            "hand1" -> GRAB
            "right_side" -> E_RESIZE
            "top_side" -> N_RESIZE
            "top_right_corner" -> NE_RESIZE
            "top_left_corner" -> NW_RESIZE
            "bottom_side" -> S_RESIZE
            "bottom_right_corner" -> SE_RESIZE
            "bottom_left_corner" -> SW_RESIZE
            "left_side" -> W_RESIZE
            "fleur" -> ALL_SCROLL
            else -> null
        }
    }
}
