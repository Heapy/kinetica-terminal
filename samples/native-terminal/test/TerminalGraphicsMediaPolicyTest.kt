@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import io.heapy.kinetica.terminal.AppKitTerminalView
import platform.AppKit.NSApplication
import platform.Foundation.NSUUID
import platform.posix.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsMediaPolicyTest {
    @Test fun localTerminalConsumesTemporaryGraphicsAndDetachesPolicyOnClose() {
        NSApplication.sharedApplication()
        val path = "/private/tmp/tty-graphics-protocol-${NSUUID().UUIDString}"
        val terminal = TerminalWindow(1, TerminalSettingsStore(null), "/private/tmp")
        val session = terminal.session
        val view = AppKitTerminalView(session = session, graphicsStorageBytes = 1024)
        val replies = StringBuilder()
        val observation = session.onInput { replies.append(it.toByteArray().decodeToString()) }
        fun create() {
            val file = assertNotNull(fopen(path, "wb"))
            try { repeat(3) { assertEquals(it + 1, fputc(it + 1, file)) } }
            finally { fclose(file) }
        }
        fun query() {
            replies.clear()
            session.write("\u001b_Ga=q,i=1,t=t,f=24,s=1,v=1;${Base64.encode(path.encodeToByteArray())}\u001b\\")
        }
        try {
            create(); query()
            assertEquals("\u001b_Gi=1;OK\u001b\\", replies.toString())
            assertEquals(-1, access(path, F_OK))
            assertEquals(ENOENT, errno)
            var closed = false
            terminal.close { closed = true }
            assertTrue(closed)
            create(); query()
            assertTrue(replies.contains("unsupported medium"), replies.toString())
            assertEquals(0, access(path, F_OK), "Detached policy must not consume files")
        } finally {
            observation.dispose()
            view.dispose()
            terminal.close {}
            unlink(path)
        }
    }
}
