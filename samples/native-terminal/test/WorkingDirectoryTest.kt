@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import platform.Foundation.*
import kotlin.test.*

class WorkingDirectoryTest {
    @Test fun usesOnlyExistingLocalReportedDirectories() {
        val path = NSTemporaryDirectory() + "kinetica-cwd-" + NSUUID().UUIDString + " space"
        val files = NSFileManager.defaultManager
        assertTrue(files.createDirectoryAtPath(path, true, null, null))
        val terminal = TerminalWindow(1, TerminalSettingsStore(null), path)
        try {
            terminal.session.write("\u001b]7;file://localhost${path.replace(" ", "%20")}\u0007")
            assertEquals(path, terminal.currentWorkingDirectory())
            terminal.session.write("\u001b]7;kitty-shell-cwd://localhost$path\u0007")
            assertEquals(path, terminal.currentWorkingDirectory())
            terminal.session.write("\u001b]7;file://remote$path\u0007")
            assertNull(terminal.currentWorkingDirectory())
            terminal.session.write("\u001b]7;file://localhost$path/missing\u0007")
            assertNull(terminal.currentWorkingDirectory())
            terminal.session.write("\u001b]7;\u0007")
            assertNull(terminal.currentWorkingDirectory())
        } finally {
            terminal.close {}
            files.removeItemAtPath(path, null)
        }
    }
}
