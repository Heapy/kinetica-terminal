package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalScrollAccumulatorTest {
    @Test fun preciseScrollingAccumulatesDistanceAndHandlesDirectionChanges() {
        val wheel = TerminalScrollAccumulator()
        repeat(19) { assertEquals(0, wheel.consume(1.0, true, 20.0)) }
        assertEquals(1, wheel.consume(1.0, true, 20.0))
        assertEquals(3, wheel.consume(65.0, true, 20.0))
        assertEquals(-1, wheel.consume(-20.0, true, 20.0))
        assertEquals(0, wheel.consume(Double.NaN, true, 20.0))
        wheel.consume(19.0, true, 20.0); wheel.reset()
        assertEquals(0, wheel.consume(1.0, true, 20.0))
    }
    @Test fun ordinaryWheelRetainsFractionalTicks() {
        val wheel = TerminalScrollAccumulator()
        assertEquals(3, wheel.consume(1.0, false, 20.0))
        assertEquals(0, wheel.consume(0.25, false, 20.0))
        assertEquals(1, wheel.consume(0.25, false, 20.0))
    }
}
