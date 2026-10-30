@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import platform.QuartzCore.CACurrentMediaTime
import kotlin.test.*

class NativeHistoryCompressionTest {
    private fun waitFor(diagnostics: () -> String, condition: () -> Boolean) {
        val deadline = CACurrentMediaTime() + 5.0
        while (!condition() && CACurrentMediaTime() < deadline) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        assertTrue(condition(), "Native idle timer did not complete: ${diagnostics()}")
    }

    @Test fun appKitRunLoopCompressesHistoryAndDisposalCancelsMaintenance() {
        NSApplication.sharedApplication()
        for (backend in listOf(TerminalRendering.SOFTWARE, TerminalRendering.GPU)) {
            val session = TerminalSession(80, 6)
            val view = AppKitTerminalView(session, rendering = backend)
            try {
                session.write("\u001b[?25l")
                repeat(400) { session.write("\u001b[38;5;${it % 256}mrow $it á 界 🙂\u001b[0m\r\n") }
                view.scrollTo(30)
                val text = view.accessibilityValue()
                val logical = session.historyStorageBytes
                waitFor({ "$backend logical=$logical resident=${session.historyResidentStorageBytes}, columns=${session.columns}" }) {
                    // Combining strings retain their metadata/reference array independently of cell compression.
                    session.historyResidentStorageBytes < logical / 2
                }
                assertEquals(logical, session.historyStorageBytes)
                assertEquals(text, view.accessibilityValue(), "$backend restored styled Unicode history")
                view.scrollTo(70); view.scrollTo(30)
                assertEquals(text, view.accessibilityValue())
                view.dispose()
                session.reset(); repeat(100) { session.write("after disposal\r\n") }
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(1.2))
                assertEquals(session.historyStorageBytes, session.historyResidentStorageBytes,
                    "$backend disposed view must not keep compressing its session")
            } finally { view.dispose() }
        }
    }
}
