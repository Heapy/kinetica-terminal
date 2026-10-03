@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.AppKit.*
import platform.CoreGraphics.*
import platform.CoreFoundation.CFRelease
import platform.posix.*
import kotlin.test.*
import kotlin.time.TimeSource

/** Live applications. Requires tmux and Neovim (KINETICA_TMUX/KINETICA_NVIM); no user rc files or existing PTYs. */
class MacOsTuiTest {
    private class Shell {
        // AF_UNIX socket paths are short on macOS; NSTemporaryDirectory + UUID exceeds the limit.
        val directory = memScoped {
            checkNotNull(mkdtemp("/private/tmp/kinetica-tui-XXXXXX".cstr.ptr)).toKString()
        }
        val session = TerminalSession(80, 24)
        var exit: Int? = null
        val pty: MacOsPty
        var tmuxExecutable: String? = null
        init {
            pty = MacOsPty(session, "/bin/zsh", listOf("-d", "-f", "-i"), workingDirectory = directory,
                environment = mapOf("ZDOTDIR" to directory, "PS1" to "KINETICA> ", "RPS1" to "", "RPROMPT" to "",
                    "HISTFILE" to "/dev/null", "LC_ALL" to "en_US.UTF-8", "LESSHISTFILE" to "-", "LESS" to "",
                    "XDG_CONFIG_HOME" to "$directory/config", "XDG_DATA_HOME" to "$directory/data",
                    "XDG_STATE_HOME" to "$directory/state", "XDG_CACHE_HOME" to "$directory/cache",
                    "TERM_PROGRAM" to "Kinetica", "TMUX" to ""),
                onInputRejected = { fail("TUI input was rejected") }, onExit = { exit = it })
            try { await("initial prompt") { prompt() } }
            catch (error: Throwable) { close(); throw error }
        }
        fun screen() = (0 until session.rows).joinToString("\n") { session.screenLine(it).text() }
        fun prompt() = !session.alternateScreen && session.screenLine(session.cursorRow).text().startsWith("KINETICA>")
        fun await(label: String, condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition() && start.elapsedNow().inWholeSeconds < 10 && exit == null)
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            assertTrue(condition(), "$label (exit=$exit, alt=${session.alternateScreen}, cursor=${session.cursorColumn},${session.cursorRow})\n${screen()}")
        }
        fun send(text: String) = session.sendInput(text)
        fun key(name: String, control: Boolean = false) { assertTrue(session.sendKey(TerminalKey(name, control = control))) }
        fun file(name: String, text: String) {
            val bytes = text.encodeToByteArray()
            val fd = open("$directory/$name", O_WRONLY or O_CREAT or O_TRUNC, 384)
            check(fd >= 0)
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val count = bytes.usePinned { write(fd, it.addressOf(offset), (bytes.size - offset).toULong()) }.toInt()
                    if (count < 0 && errno == EINTR) continue
                    check(count > 0); offset += count
                }
            } finally { close(fd) }
        }
        fun file(name: String): String {
            val data = NSFileManager.defaultManager.contentsAtPath("$directory/$name") ?: return ""
            return data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt())?.decodeToString().orEmpty()
        }
        fun close() {
            tmuxExecutable?.let { executable ->
                val task = NSTask()
                task.launchPath = executable
                task.arguments = listOf("-S", "$directory/tmux.sock", "kill-server")
                task.standardOutput = NSFileHandle.fileHandleWithNullDevice
                task.standardError = NSFileHandle.fileHandleWithNullDevice
                task.launch()
                val start = TimeSource.Monotonic.markNow()
                while (task.running && start.elapsedNow().inWholeSeconds < 5)
                    NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
                if (task.running) { kill(task.processIdentifier, SIGKILL); task.waitUntilExit() }
            }
            var closed = false
            pty.close { closed = true }
            val start = TimeSource.Monotonic.markNow()
            while (!closed && start.elapsedNow().inWholeSeconds < 10)
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            try { assertTrue(closed, "TUI shell was not reaped") }
            finally { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
        }
    }

    @Test fun nanoScrollsBothDirectionsWithTheMouseWheelWithoutEditingTheFile() {
        NSApplication.sharedApplication()
        val shell = Shell()
        val view = AppKitTerminalView(shell.session, rendering = TerminalRendering.SOFTWARE)
        fun wheel(lines: Int) {
            val event = checkNotNull(CGEventCreateScrollWheelEvent2(null, kCGScrollEventUnitLine, 1u, lines, 0, 0))
            try { view.scrollWheel(checkNotNull(NSEvent.eventWithCGEvent(event))) } finally { CFRelease(event) }
        }
        try {
            val initial = (1..120).joinToString("\n", postfix = "\n") { "ROW ${it.toString().padStart(3, '0')}" }
            shell.file("nano-scroll.txt", initial)
            // macOS /usr/bin/nano is Pico; exercise the command users actually launch.
            shell.send("/usr/bin/nano nano-scroll.txt\r")
            shell.await("nano ready") { shell.session.alternateScreen && shell.screen().contains("ROW 001") }
            assertEquals(0, shell.session.mouseTracking)
            wheel(-20)
            shell.await("wheel scrolls nano down") { shell.screen().contains("ROW 061") && !shell.screen().contains("ROW 001") }
            wheel(20)
            shell.await("wheel scrolls nano up") { shell.screen().contains("ROW 001") }
            shell.key("x", control = true)
            shell.await("nano exits without a save prompt") { shell.prompt() }
            assertEquals(initial, shell.file("nano-scroll.txt"))
        } finally { view.dispose(); shell.close() }
    }

    @Test fun zshEditsUnicodeCommandsAndRestoresPromptAfterInterruptAndResize() {
        val shell = Shell()
        try {
            shell.send("this command must not run")
            shell.key("u", control = true)
            shell.session.paste("printf '%s%s\\n' SHELL_ 'OK界😀'")
            shell.key("Enter")
            shell.await("Unicode shell command") { shell.screen().contains("SHELL_OK界😀") && shell.prompt() }
            shell.send("/bin/sh -c 'printf \"SLEEP_%s\\n\" READY; exec /bin/sleep 60'\r")
            shell.await("foreground command is running") { shell.screen().contains("SLEEP_READY") && !shell.prompt() }
            shell.key("c", control = true)
            shell.await("interrupt returns to prompt") { shell.prompt() }
            shell.session.resize(100, 32)
            shell.send("printf 'SIZE:%s\\n' \"$(stty size)\"\r")
            shell.await("PTY resize reaches shell") { shell.screen().contains("SIZE:32 100") && shell.prompt() }
            shell.send("exit\r")
            shell.await("shell exits") { shell.exit != null }
            assertEquals(0, shell.exit)
        } finally { shell.close() }
    }

    @Test fun vimEditsPasteMouseAndResizeThenRestoresTheShellScreen() {
        val shell = Shell()
        try {
            val initial = (1..120).joinToString("\n", postfix = "\n") { "ROW ${it.toString().padStart(3, '0')}" }
            shell.file("edit.txt", initial)
            shell.send("/usr/bin/vim -Nu NONE -n -i NONE --noplugin -N -c 'set noshowmode noruler laststatus=2 statusline=KINETICA_VIM mouse=a ttymouse=sgr' edit.txt\r")
            shell.await("Vim alternate screen") {
                shell.session.alternateScreen && shell.session.screenLine(0).text() == "ROW 001" &&
                    shell.screen().contains("KINETICA_VIM") && shell.session.mouseTracking != 0
            }
            shell.send("gg0i")
            shell.session.paste("EDIT界😀\n")
            shell.key("Escape")
            shell.send(":w\r")
            shell.await("Vim writes bracketed Unicode paste") { shell.file("edit.txt") == "EDIT界😀\n$initial" }
            shell.send("40Gzt")
            shell.await("Vim scrolling") { shell.session.screenLine(0).text() == "ROW 039" }
            shell.session.sendMouse(2, 2, 0)
            shell.session.sendMouse(2, 2, 0, release = true)
            shell.send("0iMOUSE:")
            shell.key("Escape"); shell.send(":w\r")
            shell.await("Vim mouse selects the intended row") { shell.file("edit.txt").contains("\nMOUSE:ROW 041\n") }
            shell.session.resize(100, 32)
            shell.key("l", control = true)
            shell.await("Vim redraw after resize") { shell.session.screenLine(30).text().contains("KINETICA_VIM") }
            shell.send(":q\r")
            shell.await("Vim restores primary screen") { shell.prompt() }
            assertEquals(0, shell.session.mouseTracking)
            shell.send("printf '%s%s\\n' AFTER_ VIM\r")
            shell.await("shell accepts input after Vim") { shell.screen().contains("AFTER_VIM") && shell.prompt() }
        } finally { shell.close() }
    }

    @Test fun neovimEditsUnicodeMouseSearchAndResizeThenRestoresTheShell() {
        val executable = getenv("KINETICA_NVIM")?.toKString() ?: sequenceOf("/opt/homebrew/bin/nvim", "/usr/local/bin/nvim")
            .plus(getenv("PATH")?.toKString().orEmpty().split(':').filter { it.isNotEmpty() }.map { "$it/nvim" })
            .firstOrNull { access(it, X_OK) == 0 }
            ?: error("Live Neovim test requires nvim; install it or set KINETICA_NVIM to its executable")
        val quoted = "'" + executable.replace("'", "'\\''") + "'"
        val shell = Shell()
        try {
            val initial = (1..120).joinToString("\n", postfix = "\n") { "ROW ${it.toString().padStart(3, '0')}" }
            shell.file("edit.txt", initial)
            shell.send("$quoted -u NONE -n -i NONE --noplugin -c 'set noshowmode noruler laststatus=2 statusline=KINETICA_NVIM mouse=a' edit.txt\r")
            shell.await("Neovim ready") {
                shell.session.alternateScreen && shell.session.screenLine(0).text() == "ROW 001" &&
                    shell.screen().contains("KINETICA_NVIM") && shell.session.mouseTracking != 0 && shell.session.bracketedPaste &&
                    !shell.session.synchronizedOutput && shell.session.cursorRow == 0 && shell.session.cursorColumn == 0
            }
            shell.send("gg0i")
            shell.await("Neovim insert cursor") { shell.session.cursorStyle == TerminalCursorStyle.BAR }
            shell.session.paste("EDIT界😀é\n")
            shell.await("Neovim finishes paste") { !shell.session.synchronizedOutput && shell.session.screenLine(0).text() == "EDIT界😀é" }
            shell.key("Escape"); shell.send(":w\r")
            shell.await("Neovim saves bracketed Unicode paste") { shell.file("edit.txt") == "EDIT界😀é\n$initial" }
            shell.send("40Gzt")
            shell.await("Neovim scrolling") { shell.session.screenLine(0).text() == "ROW 039" }
            shell.session.sendMouse(2, 2, 0); shell.session.sendMouse(2, 2, 0, release = true)
            shell.send("0iMOUSE:"); shell.key("Escape"); shell.send(":w\r")
            shell.await("Neovim mouse targets intended row") { shell.file("edit.txt").contains("\nMOUSE:ROW 041\n") }
            shell.send(":set termguicolors | highlight KineticaProbe gui=undercurl guisp=#12ee34 | call matchadd('KineticaProbe', 'ROW 039')\r")
            shell.await("Neovim detects and emits colored undercurl") {
                shell.session.screenLine(0).style(0) and TerminalStyle.UNDERCURL != 0 &&
                    shell.session.screenLine(0).underlineColor(0) == 0x12ee34
            }
            shell.send("/ROW 100\r")
            shell.await("Neovim search") { shell.session.screenLine(shell.session.cursorRow).text() == "ROW 100" }
            shell.session.resize(100, 32); shell.key("l", control = true)
            shell.await("Neovim redraw after resize") { shell.session.screenLine(30).text().contains("KINETICA_NVIM") }
            // A non-text key after resize must reach the application without leaking an escape suffix.
            val row = shell.session.cursorRow
            shell.key("ArrowUp")
            shell.await("Neovim arrow navigation") { shell.session.cursorRow == row - 1 && shell.session.screenLine(row - 1).text() == "ROW 099" }
            shell.send(":q\r"); shell.await("Neovim restores primary screen") { shell.prompt() }
            assertEquals(0, shell.session.mouseTracking)
            assertFalse(shell.session.synchronizedOutput)
            shell.send("printf '%s%s\\n' AFTER_ NVIM\r")
            shell.await("shell accepts input after Neovim") { shell.screen().contains("AFTER_NVIM") && shell.prompt() }
        } finally { shell.close() }
    }

    @Test fun lessSearchesPagesResizesAndRestoresTheShellScreen() {
        val shell = Shell()
        try {
            shell.file("pages.txt", (1..180).joinToString("\n", postfix = "\n") { "LINE ${it.toString().padStart(3, '0')} — 界" })
            shell.send("/usr/bin/less -R -P KINETICA_LESS pages.txt\r")
            shell.await("less alternate screen") { shell.session.alternateScreen && shell.session.screenLine(0).text() == "LINE 001 — 界" }
            shell.send("/LINE 100\r")
            shell.await("less search") { shell.screen().contains("LINE 100 — 界") && !shell.screen().contains("LINE 001 — 界") }
            shell.key("PageDown")
            shell.await("less page down") { shell.screen().contains("LINE 130 — 界") }
            shell.session.resize(100, 32)
            shell.send("g")
            shell.await("less redraw after resize") {
                shell.session.screenLine(0).text() == "LINE 001 — 界" && shell.session.screenLine(31).text().contains("KINETICA_LESS")
            }
            shell.send("q")
            shell.await("less restores primary screen") { shell.prompt() }
            shell.send("printf '%s%s\\n' AFTER_ LESS\r")
            shell.await("shell accepts input after less") { shell.screen().contains("AFTER_LESS") && shell.prompt() }
        } finally { shell.close() }
    }

    @Test fun tmuxSplitsPanesResizesAndDetachesBackToTheShell() {
        val executable = getenv("KINETICA_TMUX")?.toKString() ?: sequenceOf("/opt/homebrew/bin/tmux", "/usr/local/bin/tmux")
            .plus(getenv("PATH")?.toKString().orEmpty().split(':').filter { it.isNotEmpty() }.map { "$it/tmux" })
            .firstOrNull { access(it, X_OK) == 0 }
            ?: error("Live tmux test requires tmux; install it or set KINETICA_TMUX to its executable")
        fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
        val shell = Shell()
        try {
            shell.tmuxExecutable = executable
            shell.file("tmux.conf", """
                set -g default-command "/bin/zsh -d -f -i"
                set -g status-left '[kinetica] '
                set -g status-right ''
                set -g automatic-rename off
                set -g set-titles off
                set -g allow-rename off
            """.trimIndent() + "\n")
            shell.send("${quote(executable)} -S \"\$PWD/tmux.sock\" -f tmux.conf new-session -s kinetica /bin/zsh -d -f -i\r")
            shell.await("tmux ready") {
                shell.session.alternateScreen && shell.screen().contains("[kinetica]") && shell.screen().contains("KINETICA>")
            }
            shell.send("printf '%s%s\\n' PANE_ ONE\r")
            shell.await("first pane") { shell.screen().contains("PANE_ONE") }
            shell.key("b", control = true); shell.send("%")
            shell.send("printf '%s%s\\n' PANE_ TWO\r")
            shell.await("second pane") { shell.screen().contains("PANE_ONE") && shell.screen().contains("PANE_TWO") }
            shell.session.resize(100, 32)
            shell.await("tmux outer redraw") { shell.session.screenLine(31).text().contains("[kinetica]") }
            // tmux may draw before its rate-limited inner PTY resize; wait on the kernel size.
            // Write readiness out of band so an echoed command cannot satisfy the assertion.
            shell.send("while [ \"\$(stty size)\" != '31 49' ]; do /bin/sleep .02; done; stty size > pane-size\r")
            shell.await("tmux inner PTY resize") { shell.file("pane-size").trim() == "31 49" }
            shell.send("printf 'SIZE:%s\\n' \"\$(stty size)\"\r")
            shell.await("resized pane output") { shell.screen().contains("SIZE:31 49") }
            shell.key("b", control = true); shell.send("d")
            shell.await("detach restores shell") { shell.prompt() }
            shell.send("printf '%s%s\\n' AFTER_ TMUX\r")
            shell.await("shell after tmux") { shell.screen().contains("AFTER_TMUX") && shell.prompt() }
        } finally { shell.close() }
    }
}
