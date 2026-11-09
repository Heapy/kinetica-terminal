@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsTerminalVersionTest {
    @Test fun realShellEnvironmentAndVersionReplyAgree() {
        val session = TerminalSession().apply { terminalVersion = "9.8.7" }
        val expected = "\u001bP>|Kinetica 9.8.7\u001b\\"
        var exitCode: Int? = null
        val command = """
            stty -echo -icanon min 1 time 0
            printf '\033[>q'
            reply=${'$'}(dd bs=1 count=${expected.length} 2>/dev/null)
            expected=${'$'}(printf '\033P>|%s %s\033\\' "${'$'}TERM_PROGRAM" "${'$'}TERM_PROGRAM_VERSION")
            printf 'ENV=%s/%s\n' "${'$'}TERM_PROGRAM" "${'$'}TERM_PROGRAM_VERSION"
            if [ "${'$'}reply" = "${'$'}expected" ]; then printf 'VERSION_MATCH\n'; else exit 7; fi
        """.trimIndent()
        val pty = MacOsPty(session, "/bin/sh", listOf("-c", command), onExit = { exitCode = it })
        try {
            val start = TimeSource.Monotonic.markNow()
            while (exitCode == null && start.elapsedNow().inWholeSeconds < 10) {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            }
            assertEquals(0, exitCode, "Version probe must exit successfully")
            val text = (0 until session.rows).joinToString("\n") { session.screenLine(it).text() }
            assertContains(text, "ENV=Kinetica/9.8.7")
            assertContains(text, "VERSION_MATCH")
        } finally { pty.dispose() }
    }
}
