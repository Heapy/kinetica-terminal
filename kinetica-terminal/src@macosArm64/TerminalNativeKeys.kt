package io.heapy.kinetica.terminal

internal fun nativeBaseCodepoint(code: Int): Int? = when (code) {
    18 -> '1'; 19 -> '2'; 20 -> '3'; 21 -> '4'; 23 -> '5'; 22 -> '6'; 26 -> '7'; 28 -> '8'; 25 -> '9'; 29 -> '0'
    27 -> '-'; 24 -> '='; 50 -> '`'; 49 -> ' '
    else -> terminalShortcutKey("ф", code).singleOrNull()?.takeIf { it.code < 128 }
}?.code

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
