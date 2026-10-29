package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalProgramStatusTest {
    private fun TerminalSession.status(body: String) = write("\u001b]7501;$body\u001b\\")

    @Test fun reportsAndRepliesMatchOriginalGhostty() {
        for (encoded in terminalProgramStatusFixtures()) for (bytewise in listOf(false,true)) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession(12,6)
            val reports = mutableListOf<JsonElement>()
            val replies = StringBuilder()
            fun observe() = session.onProgramStatus { change -> change.report?.let { r ->
                reports += buildJsonObject {
                    put("state",r.state.name.lowercase()); put("id",r.id); put("app",r.app)
                    put("kind",r.kind?.name?.lowercase()); put("progress",r.progress)
                    put("title",r.title); put("message",r.message)
                }
            } }
            var observer: TerminalDisposable? = observe()
            session.onInput { replies.append(it.toByteArray().decodeToString()) }
            try {
                for ((step,value) in case.getValue("events").jsonArray.withIndex()) {
                    reports.clear(); replies.clear()
                    val event = value.jsonObject
                    event["enabled"]?.jsonPrimitive?.boolean?.let { enabled ->
                        observer?.dispose(); observer = if (enabled) observe() else null
                    }
                    event["text"]?.jsonPrimitive?.content?.let { text ->
                        if (bytewise) text.encodeToByteArray().forEach { session.write(byteArrayOf(it)) }
                        else session.write(text)
                    }
                    val actual = buildJsonObject { put("replies",replies.toString()); put("reports",JsonArray(reports.toList())) }
                    assertEquals(case.getValue("snapshots").jsonArray[step],actual,"${case.getValue("name")} step=$step bytewise=$bytewise")
                }
            } finally { observer?.dispose() }
        }
    }

    @Test fun recordsReplaceCompletelyInheritAppsAndClearOnlyAddressedSubtree() {
        val session = TerminalSession()
        val changes = mutableListOf<TerminalProgramStatusChange>()
        session.onProgramStatus { changes += it; assertEquals(it.records,session.programStatuses) }
        session.status("state=idle:app=root")
        session.status("state=working:id=build:app=cargo:title=UGxhbg:progress=40")
        session.status("state=done:id=build/test")
        session.status("state=error:id=builder")
        assertEquals("cargo",session.programStatusApp("build/test"))
        assertEquals("root",session.programStatusApp("builder"))
        val snapshot = session.programStatuses
        session.status("state=done:id=build")
        assertEquals("root",session.programStatusApp("build/test"))
        assertEquals(TerminalProgramStatus(TerminalProgramState.DONE,"build"),session.programStatuses.last())
        assertEquals("cargo",snapshot.first { it.id == "build" }.app,"Snapshots remain stable")
        session.status("state=clear:id=build")
        assertEquals(listOf(null,"builder"),session.programStatuses.map { it.id })
        session.status("state=clear:id=bad//id")
        assertEquals(6,changes.size,"Malformed clear must never fall back to the root")
        session.status("state=clear")
        assertTrue(session.programStatuses.isEmpty())
    }

    @Test fun promptExitAndResetHaveDistinctLifetimes() {
        val session = TerminalSession()
        val changes = mutableListOf<TerminalProgramStatusChange>()
        session.onProgramStatus { changes += it }
        fun populate() {
            session.status("state=idle")
            session.status("state=working:id=parent:app=build")
            session.status("state=done:id=parent/done")
            session.status("state=blocked:id=blocked:kind=auth")
            session.status("state=error:id=error")
        }
        populate()
        session.write("\u001b[?1049h\u001b[!p\u001b[?1049l\u001b[?2026h")
        assertEquals(5,session.programStatuses.size)
        session.write("\u001b]133;A\u001b\\")
        assertEquals(listOf(null,"parent/done","error"),session.programStatuses.map { it.id })
        assertNull(changes.last().report)
        assertTrue(session.synchronizedOutput,"Status lifecycle is independent of rendering")
        populate(); session.finishInput()
        assertEquals(setOf(null,"parent/done","error"),session.programStatuses.map { it.id }.toSet())
        val count = changes.size
        session.finishInput()
        assertEquals(count,changes.size)
        session.write("\u001bc")
        assertTrue(session.programStatuses.isEmpty())
        assertEquals(TerminalProgramState.CLEAR,changes.last().report?.state)
    }

    @Test fun capacityEvictsLeastRecentlyUpdatedRecordWithoutDeletingItsChildren() {
        val session = TerminalSession()
        session.onProgramStatus {}
        session.status("state=idle:app=root")
        session.status("state=done:id=child")
        repeat(254) { session.status("state=idle:id=job$it") }
        assertEquals(256,session.programStatuses.size)
        session.status("state=done:id=child") // refresh this record's recency
        session.status("state=idle:id=new")
        assertEquals(256,session.programStatuses.size)
        assertFalse(session.programStatuses.any { it.id == null })
        assertTrue(session.programStatuses.any { it.id == "child" })
        assertNull(session.programStatusApp("child"))
        session.status("state=idle:id=newer")
        assertFalse(session.programStatuses.any { it.id == "job0" })
    }

    @Test fun supportAndStorageFollowObserverLifetime() {
        val session = TerminalSession()
        val replies = StringBuilder()
        session.onInput { replies.append(it.toByteArray().decodeToString()) }
        session.status("?"); session.status("state=done")
        assertEquals("",replies.toString()); assertTrue(session.programStatuses.isEmpty())
        val first = session.onProgramStatus {}
        val second = session.onProgramStatus {}
        session.status("state=done"); first.dispose(); first.dispose()
        assertEquals(1,session.programStatuses.size)
        session.status("?")
        assertTrue(replies.isNotEmpty())
        second.dispose(); replies.clear()
        assertTrue(session.programStatuses.isEmpty())
        session.status("?"); session.status("state=done")
        assertEquals("",replies.toString()); assertTrue(session.programStatuses.isEmpty())
    }
}
