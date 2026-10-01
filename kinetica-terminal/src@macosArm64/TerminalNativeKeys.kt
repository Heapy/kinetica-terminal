package io.heapy.kinetica.terminal

/** Preserve Latin layouts; use physical US key identity when Ctrl/Cmd sees non-Latin text. */
internal fun terminalShortcutKey(characters: String, keyCode: Int): String {
    if (characters.length != 1 || characters[0].code < 128) return characters.lowercase()
    return when (keyCode) {
        0 -> "a"; 1 -> "s"; 2 -> "d"; 3 -> "f"; 4 -> "h"; 5 -> "g"; 6 -> "z"; 7 -> "x"
        8 -> "c"; 9 -> "v"; 11 -> "b"; 12 -> "q"; 13 -> "w"; 14 -> "e"; 15 -> "r"
        16 -> "y"; 17 -> "t"; 31 -> "o"; 32 -> "u"; 34 -> "i"; 35 -> "p"
        37 -> "l"; 38 -> "j"; 40 -> "k"; 45 -> "n"; 46 -> "m"
        33 -> "["; 30 -> "]"; 42 -> "\\"; 41 -> ";"; 39 -> "'"; 43 -> ","; 47 -> "."; 44 -> "/"
        else -> characters
    }
}
