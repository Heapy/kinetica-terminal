package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalMouseShiftTest {
    @Test fun captureRequestsMatchOriginalParserAndResetState() {
        for (encoded in terminalMouseShiftFixtures()) for (chunked in listOf(false, true)) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession()
            val replies = StringBuilder()
            session.onInput { replies.append(it.toByteArray().decodeToString()) }
            for ((index, event) in case.getValue("events").jsonArray.withIndex()) {
                val bytes = event.jsonObject.getValue("text").jsonPrimitive.content.encodeToByteArray()
                if (chunked) bytes.forEach { session.write(byteArrayOf(it)) } else session.write(bytes)
                val expected = case.getValue("snapshots").jsonArray[index].jsonObject
                val actual = buildJsonObject {
                    put("replies", replies.toString())
                    put("requested", session.mouseShiftCaptureRequested?.let(::JsonPrimitive) ?: JsonNull)
                }
                assertEquals(expected, actual, "${case["name"]} step=$index chunked=$chunked")
            }
        }
    }

    @Test fun hostDefaultsAndForcedPolicySurviveResetsWhileRequestsRemainObservable() {
        val session = TerminalSession()
        assertFalse(session.mouseShiftCapture)
        session.configureMouseShiftCapture(defaultCapture = true)
        assertTrue(session.mouseShiftCapture)
        session.write("\u001b[>s"); assertFalse(session.mouseShiftCapture)
        session.write("\u001bc"); assertTrue(session.mouseShiftCapture)
        session.configureMouseShiftCapture(defaultCapture = false, allowApplication = false)
        session.write("\u001b[>1s")
        assertEquals(true, session.mouseShiftCaptureRequested); assertFalse(session.mouseShiftCapture)
        session.configureMouseShiftCapture(defaultCapture = true, allowApplication = false)
        session.write("\u001b[>0s"); assertTrue(session.mouseShiftCapture)
        session.write("\u001bc"); assertTrue(session.mouseShiftCapture)
        assertNull(session.mouseShiftCaptureRequested)
    }

    @Test fun captureEncodesModifiersAndPreservesGestureReleaseWhenCaptureIsDisabled() {
        val session = TerminalSession()
        val mouse = TerminalMouseReporter(session)
        val output = StringBuilder()
        session.onInput { output.append(it.toByteArray().decodeToString()) }
        session.write("\u001b[?1003h\u001b[?1006h\u001b[>1s")
        assertTrue(mouse.send(1, 2, 0, shift = true, alt = true, control = true))
        mouse.send(2, 2, 0, motion = true, shift = true)
        mouse.send(2, 2, 0, release = true, shift = true)
        assertEquals("\u001b[<28;2;3M\u001b[<36;3;3M\u001b[<4;3;3m", output.toString())
        output.clear()
        mouse.send(1, 2, 0, shift = true)
        session.write("\u001b[>0s")
        assertFalse(mouse.send(2, 2, 0, motion = true, shift = true))
        assertTrue(mouse.send(2, 2, 0, release = true, shift = true))
        assertEquals("\u001b[<4;2;3M\u001b[<0;3;3m", output.toString())
        assertFalse(mouse.send(2, 2, 0, shift = true))
        assertFalse(mouse.send(2, 2, 0, release = true))
    }

    @Test fun captureAppliesToEveryFormatWheelAndPhysicalPixelsButX10IgnoresModifiers() {
        val session = TerminalSession()
        val output = StringBuilder()
        session.onInput { output.append(it.toByteArray().decodeToString()) }
        val geometry = TerminalMouseGeometry(80.0, 160.0, 8.0, 16.0)
        for ((mode, expected) in listOf(0 to "\u001b[M<!!", 1005 to "\u001b[M<!!",
            1006 to "\u001b[<28;1;1M", 1015 to "\u001b[60;1;1M", 1016 to "\u001b[<28;2;3M")) {
            session.reset(); output.clear()
            session.write("\u001b[?1003h\u001b[?$mode" + "h\u001b[>1s")
            assertTrue(session.sendMousePixels(2.0, 3.0, geometry, 0, shift = true, alt = true, control = true))
            assertEquals(expected, output.toString(), "mode=$mode")
        }
        output.clear(); session.write("\u001b[?1006h")
        assertTrue(session.sendMouse(0, 0, 64, shift = true))
        assertEquals("\u001b[<68;1;1M", output.toString())
        output.clear(); session.write("\u001b[?1006l\u001b[?9h")
        assertTrue(session.sendMouse(0, 0, 0, shift = true, alt = true, control = true))
        assertEquals("\u001b[M !!", output.toString())
    }
}
