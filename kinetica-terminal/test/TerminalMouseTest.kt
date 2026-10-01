package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalMouseTest {
    @Test fun legacyCoordinatesAreRawBytesAndReleasesKeepModifiers() {
        val session = TerminalSession(240, 240)
        val output = mutableListOf<ByteArray>()
        session.onInput { output.add(it.toByteArray()) }
        session.write("\u001b[?1000h")
        session.sendMouse(95, 222, 0)
        session.sendMouse(95, 222, 0, release = true, alt = true, control = true)
        assertContentEquals(byteArrayOf(27, 91, 77, 32, 128.toByte(), 255.toByte()), output[0])
        assertContentEquals(byteArrayOf(27, 91, 77, 59, 128.toByte(), 255.toByte()), output[1])
        assertTrue(session.sendMouse(223, 0, 0)) // Unrepresentable, but owned by the application.
        assertEquals(2, output.size)
        assertFalse(session.sendMouse(0, 0, Int.MAX_VALUE))
        assertFalse(session.sendMouse(0, 0, 3))
        assertFalse(session.sendMouse(0, 0, 64, release = true))
    }

    @Test fun protocolEventsAreImmutableAcrossObservers() {
        val session = TerminalSession(120, 24)
        val retained = mutableListOf<TerminalInputData>()
        session.onInput { retained.add(it); it.toByteArray().fill(0) }
        val output = mutableListOf<Byte>()
        session.onInput { output.addAll(it.toByteArray().toList()) }
        session.sendInput("界😀")
        session.write("\u001b[?1000h")
        session.sendMouse(95, 0, 0)
        val expected = "界😀".encodeToByteArray() + byteArrayOf(27, 91, 77, 32, 128.toByte(), 33)
        assertContentEquals(expected, output.toByteArray())
        assertContentEquals(expected, retained.flatMap { it.toByteArray().toList() }.toByteArray())
    }

    @Test fun surfacesDeduplicateIndependentlyAndResetOnModeChangesOrResize() {
        val session = TerminalSession()
        val first = TerminalMouseReporter(session); val second = TerminalMouseReporter(session)
        val output = mutableListOf<String>()
        session.onInput { output.add(it.toByteArray().decodeToString()) }
        session.write("\u001b[?1003h\u001b[?1006h")
        first.send(2, 3, 3, motion = true)
        first.send(2, 3, 3, motion = true, control = true)
        second.send(2, 3, 3, motion = true)
        assertEquals(listOf("\u001b[<35;3;4M", "\u001b[<35;3;4M"), output)
        session.write("\u001b[?1003l\u001b[?1003h")
        first.send(2, 3, 3, motion = true)
        session.resize(81, 24)
        first.send(2, 3, 3, motion = true)
        assertEquals(4, output.size)
        first.send(-1, -1, 3, motion = true)
        assertEquals(4, output.size) // No hover report from outside the viewport.
    }

    @Test fun shiftSelectionDoesNotLeakReleaseAndReportedPressAlwaysFinishes() {
        val session = TerminalSession()
        val mouse = TerminalMouseReporter(session)
        val output = mutableListOf<String>()
        session.onInput { output.add(it.toByteArray().decodeToString()) }
        session.write("\u001b[?1002h\u001b[?1006h")
        assertFalse(mouse.send(2, 3, 0, shift = true))
        assertFalse(mouse.send(2, 3, 0, release = true))
        assertTrue(output.isEmpty())
        mouse.send(2, 3, 2)
        mouse.send(2, 3, 2, motion = true)
        mouse.send(3, 3, 2, motion = true)
        mouse.send(3, 3, 2, release = true, shift = true)
        assertEquals(listOf("\u001b[<2;3;4M", "\u001b[<34;4;4M", "\u001b[<2;4;4m"), output)
        output.clear()
        mouse.send(2, 3, 1)
        mouse.send(2, 3, 1, motion = true, shift = true)
        mouse.cancel(); mouse.cancel()
        assertEquals(listOf("\u001b[<1;3;4M", "\u001b[<1;3;4m"), output)
    }
}
