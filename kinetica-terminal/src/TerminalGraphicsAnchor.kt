package io.heapy.kinetica.terminal

/** A tracked grid position. Erasing text does not erase a placement; retiring its row does. */
internal class TerminalGraphicsAnchor(line: TerminalLine, column: Int, val alternateScreen: Boolean) {
    var line: TerminalLine? = null
        private set
    var column: Int = column
        private set

    init { moveTo(line, column) }

    fun moveTo(line: TerminalLine, column: Int) {
        require(column in 0 until line.columns)
        this.line?.detachGraphicsAnchor(this)
        this.line = line
        this.column = column
        line.attachGraphicsAnchor(this)
    }

    fun dispose() {
        line?.detachGraphicsAnchor(this)
        line = null
    }
}
