package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalVisibilityTest {
    @Test fun visibilityReportsMatchOriginalGhostty() {
        for (encoded in terminalVisibilityFixtures()) for (bytewise in listOf(false,true)) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession(12,6)
            val host = session.attachVisibility(true)
            val replies = StringBuilder()
            session.onInput { replies.append(it.toByteArray().decodeToString()) }
            try {
                for ((step,value) in case.getValue("events").jsonArray.withIndex()) {
                    val event = value.jsonObject
                    event["visible"]?.jsonPrimitive?.boolean?.let(host::setVisible)
                    event["text"]?.jsonPrimitive?.content?.let { text ->
                        if (bytewise) text.encodeToByteArray().forEach { session.write(byteArrayOf(it)) }
                        else session.write(text)
                    }
                    val actual = buildJsonObject {
                        put("replies",replies.toString()); put("visible",session.potentiallyVisible)
                        put("reporting",session.visibilityReporting)
                    }
                    assertEquals(case.getValue("snapshots").jsonArray[step],actual,"${case.getValue("name")} step=$step bytewise=$bytewise")
                }
            } finally { host.dispose() }
        }
    }

    @Test fun multipleSurfacesAggregateVisibilityAndDisposedHandlesCannotResurrectThem() {
        val session = TerminalSession()
        val reports = mutableListOf<String>()
        session.onInput { reports += it.toByteArray().decodeToString() }
        session.write("\u001b[?2033h")
        assertEquals(listOf("\u001b[?999;1n"),reports)
        reports.clear()
        val first = session.attachVisibility(false)
        val second = session.attachVisibility(false)
        first.setVisible(true); second.setVisible(true)
        first.setVisible(false); first.dispose()
        second.setVisible(false)
        first.setVisible(true); first.dispose()
        val replacement = session.attachVisibility(true)
        second.dispose(); second.setVisible(true)
        replacement.dispose(); replacement.dispose(); replacement.setVisible(true)
        assertEquals(listOf(2,1,2,1,2).map { "\u001b[?999;${it}n" },reports)
        assertFalse(session.potentiallyVisible)
    }

    @Test fun headlessViewportDoesNotClaimHostVisibility() {
        val session = TerminalSession()
        val viewport = TerminalViewport(session,{})
        try {
            viewport.setVisible(false)
            assertTrue(session.potentiallyVisible,"Unknown host visibility is potentially visible")
            val host = session.attachVisibility(false)
            viewport.setVisible(true)
            assertFalse(session.potentiallyVisible,"A rendering viewport must not override a real host")
            host.dispose()
            assertFalse(session.potentiallyVisible)
        } finally { viewport.dispose() }
    }
}
