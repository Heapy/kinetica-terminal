@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import platform.AppKit.*
import platform.Foundation.*
import platform.posix.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class NativeTerminalLinksTest {
    @Test fun commandClickActivatesOnReleaseAndDraggingOrExtraModifiersCancelIt() {
        NSApplication.sharedApplication()
        val session = TerminalSession(80, 5)
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 800.0, 480.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val opened = mutableListOf<String>()
        view.openLink = { opened += it; true }
        fun event(type: NSEventType, x: Double = 12.0, flags: ULong = NSEventModifierFlagCommand): NSEvent =
            assertNotNull(NSEvent.mouseEventWithType(type, view.convertPoint(NSMakePoint(x, 5.0), toView = null),
                flags, 0.0, window.windowNumber, null, 0, 1, 1.0f))
        fun click(flags: ULong = NSEventModifierFlagCommand) {
            view.mouseDown(event(NSEventTypeLeftMouseDown, flags = flags))
            view.mouseUp(event(NSEventTypeLeftMouseUp, flags = flags))
        }
        try {
            session.write("https://example.org")
            view.mouseDown(event(NSEventTypeLeftMouseDown))
            assertTrue(opened.isEmpty(), "Press must not activate a link")
            view.mouseUp(event(NSEventTypeLeftMouseUp))
            assertEquals(listOf("https://example.org"), opened); opened.clear()
            view.mouseDown(event(NSEventTypeLeftMouseDown))
            view.mouseDragged(event(NSEventTypeLeftMouseDragged, 40.0))
            view.mouseDragged(event(NSEventTypeLeftMouseDragged))
            view.mouseUp(event(NSEventTypeLeftMouseUp))
            assertTrue(opened.isEmpty(), "Drag away and back remains a drag")
            view.mouseDown(event(NSEventTypeLeftMouseDown))
            view.mouseUp(event(NSEventTypeLeftMouseUp, 40.0))
            assertTrue(opened.isEmpty(), "Release alone cannot open another cell")
            for (extra in listOf(NSEventModifierFlagShift, NSEventModifierFlagOption, NSEventModifierFlagControl))
                click(NSEventModifierFlagCommand or extra)
            click(0uL)
            assertTrue(opened.isEmpty(), "Only the exact binding opens links")
            session.write("\u001b[?1000h\u001b[?1006h")
            click(); assertTrue(opened.isEmpty(), "TUI capture wins over link activation")
            click(NSEventModifierFlagCommand or NSEventModifierFlagShift)
            assertEquals(listOf("https://example.org"), opened, "Shift escapes capture and is removed from link modifiers")
        } finally { view.dispose(); window.close() }
    }

    @Test fun detectedPathsResolveAgainstTerminalDirectoryBeforeNativeConversion() {
        val files = NSFileManager.defaultManager
        val root = "/tmp/kinetica-link-paths-${NSUUID().UUIDString}"
        assertTrue(files.createDirectoryAtPath("$root/sub", true, null, null))
        fun file(path: String) {
            val handle = assertNotNull(fopen(path, "wb"))
            try { assertTrue(fputs("fixture", handle) >= 0) } finally { fclose(handle) }
            assertEquals(0, chmod(path, 420u))
        }
        try {
            file("$root/with space.txt"); file("$root/unsafe.command")
            file("$root/ssh:literal"); file("$root/with%20space.txt")
            assertEquals(0, symlink("unsafe.command", "$root/safe-link.txt"))
            for (path in listOf("./with space.txt", "sub/../with space.txt", "sub//../../${root.substringAfterLast('/')}/with space.txt")) {
                val target = nativeDetectedLinkTarget(path, root)
                assertEquals("$root/with space.txt", assertIs<NativeLinkDecision.Allow>(classifyNativeLink(target)).url.path)
            }
            assertEquals("$root/ssh:literal", assertIs<NativeLinkDecision.Allow>(
                classifyNativeLink(nativeDetectedLinkTarget("ssh:literal", root))).url.path,
                "Original core tries an existing local path before interpreting a scheme")
            assertEquals("$root/with%20space.txt", assertIs<NativeLinkDecision.Allow>(
                classifyNativeLink(nativeDetectedLinkTarget("./with%20space.txt", root))).url.path,
                "Literal percent signs in paths must not be URI-decoded")
            assertEquals(NativeLinkDenial.UNSAFE_FILE, assertIs<NativeLinkDecision.Deny>(
                classifyNativeLink(nativeDetectedLinkTarget("./safe-link.txt", root))).reason)
            assertEquals(nativeDetectedLinkTarget("./missing.txt", null), nativeDetectedLinkTarget("./missing.txt", root),
                "Missing relative paths fall through to the original native conversion")
            assertEquals("ssh://example.org", nativeDetectedLinkTarget("ssh://example.org", root))
            for (prefix in listOf("~", "\$HOME")) {
                assertTrue(files.createDirectoryAtPath("$root/$prefix", false, null, null))
                file("$root/$prefix/with space.txt")
                assertEquals("$root/$prefix/with space.txt", assertIs<NativeLinkDecision.Allow>(
                    classifyNativeLink(nativeDetectedLinkTarget("$prefix/with space.txt", root))).url.path,
                    "Existing literal directories win before tilde expansion; variables are never shell-expanded")
            }
            assertEquals(NativeLinkDenial.UNSAFE_CHARACTERS, assertIs<NativeLinkDecision.Deny>(
                classifyNativeLink(nativeDetectedLinkTarget("./a\u202eb.txt", root))).reason)
        } finally { assertTrue(files.removeItemAtPath(root, null)) }
    }

    @Test fun defaultCommandClickResolvesPathsAndScopesOsc8ApprovalToItsOrigin() {
        NSApplication.sharedApplication()
        val files = NSFileManager.defaultManager
        val root = "/tmp/kinetica-link-click-${NSUUID().UUIDString}"
        assertTrue(files.createDirectoryAtPath(root, false, null, null))
        val path = "$root/with space.txt"
        val file = assertNotNull(fopen(path, "wb")); fclose(file); assertEquals(0, chmod(path, 420u))
        val session = TerminalSession(80, 5)
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 800.0, 480.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val opened = mutableListOf<NSURL>()
        val decisions = mutableListOf<NativeLinkDecision>()
        var answer: ((Boolean) -> Unit)? = null
        view.launchLink = { opened += it }
        view.presentLink = { decision, _, complete ->
            decisions += decision; answer = complete; TerminalDisposable {}
        }
        fun click() {
            val point = view.convertPoint(NSMakePoint(12.0, 5.0), toView = null)
            view.mouseDown(assertNotNull(NSEvent.mouseEventWithType(NSEventTypeLeftMouseDown, point,
                NSEventModifierFlagCommand, 0.0, window.windowNumber, null, 0, 1, 1.0f)))
            view.mouseUp(assertNotNull(NSEvent.mouseEventWithType(NSEventTypeLeftMouseUp, point,
                NSEventModifierFlagCommand, 0.0, window.windowNumber, null, 0, 1, 1.0f)))
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.02))
        }
        try {
            session.write("\u001b]7;file://localhost$root\u0007./with space.txt")
            click(); assertEquals(path, opened.single().path); opened.clear()
            session.reset(); session.write("\u001b]8;;./with space.txt\u001b\\click\u001b]8;;\u001b\\")
            click(); assertIs<NativeLinkDecision.Deny>(decisions.last()); answer?.invoke(true)
            assertTrue(opened.isEmpty(), "Explicit OSC 8 targets never become local paths")
            session.reset(); session.osc8LinksEnabled = false; session.write("ssh://visible.example")
            click(); assertIs<NativeLinkDecision.Confirm>(decisions.last()); assertNotNull(answer).invoke(true)
            assertEquals("ssh://visible.example", opened.single().absoluteString); opened.clear()
            click(); session.detectedLinksEnabled = false; assertNotNull(answer).invoke(true)
            assertTrue(opened.isEmpty(), "Disabling detection revokes a pending detected-link approval")
            session.reset(); session.osc8LinksEnabled = true
            session.write("\u001b]8;;ssh://explicit.example\u001b\\click\u001b]8;;\u001b\\")
            click(); assertNotNull(answer).invoke(true)
            assertEquals("ssh://explicit.example", opened.single().absoluteString,
                "Detection policy does not disable explicit OSC 8 approval")
            opened.clear(); session.detectedLinksEnabled = true
            session.reset(); session.osc8LinksEnabled = true
            session.write("\u001b]8;;ssh://hidden.example\u001b\\click\u001b]8;;\u001b\\")
            click(); session.osc8LinksEnabled = false; assertNotNull(answer).invoke(true)
            assertTrue(opened.isEmpty(), "Disabling OSC 8 revokes a pending explicit link")
            session.reset(); session.write("$path")
            session.write("\u001b[?2026h\u001b[Hchanged")
            click(); assertEquals(path, opened.single().path)
        } finally { view.dispose(); window.close(); assertTrue(files.removeItemAtPath(root, null)) }
    }

    @Test fun decisionsAndVisibleTargetsMatchOriginalGhostty() {
        val fixture = Json.parseToJsonElement(Base64.decode(terminalNativeLinkFixtures()).decodeToString()).jsonObject
        val files = NSFileManager.defaultManager
        val work = "/private/tmp/kinetica-links-${NSUUID().UUIDString}"
        val root = "$work/resources"
        val previous = files.currentDirectoryPath
        assertTrue(files.createDirectoryAtPath(root, true, null, null))
        try {
            for (element in fixture.getValue("resources").jsonArray) {
                val item = element.jsonObject
                fun text(key: String) = item.getValue(key).jsonPrimitive.content
                val path = "$root/${text("name")}"
                when (text("kind")) {
                    "file" -> {
                        val file = assertNotNull(fopen(path, "wb"))
                        try { assertTrue(fputs("ordinary test fixture\n", file) >= 0) } finally { fclose(file) }
                        assertEquals(0, chmod(path, item.getValue("mode").jsonPrimitive.int.toUShort()))
                    }
                    "directory" -> assertTrue(files.createDirectoryAtPath(path, false, null, null))
                    "symlink" -> assertEquals(0, symlink(text("target"), path))
                    "fifo" -> assertEquals(0, mkfifo(path, 420u))
                    else -> fail("Unknown resource")
                }
            }
            assertTrue(files.changeCurrentDirectoryPath(root))
            val failures = mutableListOf<String>()
            for (element in fixture.getValue("cases").jsonArray) {
                val case = element.jsonObject
                fun text(key: String) = case.getValue(key).jsonPrimitive.content.replace("<ROOT>", root).replace("<WORK>", work)
                    .replace("<SHORT_ROOT>", root.removePrefix("/private")).replace("<SHORT_WORK>", work.removePrefix("/private"))
                    .replace("<HOME>", NSHomeDirectory())
                val value = text("value")
                val label = escapeNativeLinkDisplay(value)
                try {
                    val target = if (text("kind") == "detected") nativeDetectedLinkTarget(value, null) else value
                    assertEquals(text("target"), target, label)
                    val decision = classifyNativeLink(target)
                    when (text("decision")) {
                        "allow" -> assertEquals(text("url"), assertIs<NativeLinkDecision.Allow>(decision, label).url.absoluteString, label)
                        "confirm" -> assertEquals(text("url"), assertIs<NativeLinkDecision.Confirm>(decision, label).url.absoluteString, label)
                        "deny" -> {
                            val reason = assertIs<NativeLinkDecision.Deny>(decision, label).reason
                            val originalName = when (reason) {
                                NativeLinkDenial.MALFORMED -> "malformedURL"
                                NativeLinkDenial.UNSAFE_CHARACTERS -> "unsafeCharacters"
                                NativeLinkDenial.INVALID_WEB -> "invalidWebURL"
                                NativeLinkDenial.INACCESSIBLE_FILE -> "inaccessibleFile"
                                NativeLinkDenial.UNSAFE_FILE -> "unsafeFile"
                            }
                            assertEquals(text("reason"), originalName, label)
                        }
                        else -> fail("Unknown original decision")
                    }
                    assertEquals(text("display"), nativeLinkDisplay(target), label)
                } catch (failure: AssertionError) { failures += failure.message.orEmpty() }
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        } finally {
            assertTrue(files.changeCurrentDirectoryPath(previous))
            assertTrue(files.removeItemAtPath(work, null))
        }
    }

    @Test fun alertsDefaultToCancelShowFullPlainTextAndOnlyCopyDeniedTargets() {
        NSApplication.sharedApplication()
        val decision = classifyNativeLink("ssh://example.org")
        val display = "ssh://example.org/" + "long-target/".repeat(100)
        val alert = nativeLinkAlert(decision, display)
        val buttons = alert.buttons.filterIsInstance<NSButton>()
        assertEquals(listOf("Cancel", "Open Link"), buttons.map { it.title })
        assertEquals("\u001b", buttons.first().keyEquivalent, "Escape cancels")
        assertTrue(buttons.drop(1).all { it.keyEquivalent != "\r" }, "Return must not approve a custom handler")
        val scroll = assertIs<NSScrollView>(alert.accessoryView)
        val text = assertIs<NSTextView>(scroll.documentView)
        assertEquals(display, text.string)
        assertTrue(text.selectable); assertFalse(text.editable); assertFalse(text.richText)
        val copies = mutableListOf<String>()
        assertFalse(nativeLinkAlertResponse(decision, NSAlertFirstButtonReturn, display, copies::add))
        assertFalse(nativeLinkAlertResponse(decision, NSModalResponseCancel, display, copies::add))
        assertTrue(nativeLinkAlertResponse(decision, NSAlertSecondButtonReturn, display, copies::add))
        val denied = classifyNativeLink("https://example.org/\u202eevil")
        val safeDisplay = nativeLinkDisplay("https://example.org/\u202eevil")
        val blocked = nativeLinkAlert(denied, safeDisplay)
        assertEquals(listOf("OK", "Copy Link"), blocked.buttons.filterIsInstance<NSButton>().map { it.title })
        assertFalse(nativeLinkAlertResponse(denied, NSAlertFirstButtonReturn, safeDisplay, copies::add))
        assertTrue(copies.isEmpty())
        assertFalse(nativeLinkAlertResponse(denied, NSAlertSecondButtonReturn, safeDisplay, copies::add))
        assertEquals(listOf("https://example.org/\\u{202E}evil"), copies)
    }

    private class Timer : TerminalScheduler {
        class Task(val action: () -> Unit) { var cancelled = false }
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            assertTrue(delayMillis > 0)
            val task = Task(action); tasks += task
            return TerminalDisposable { task.cancelled = true }
        }
        fun fire() { val task = tasks.removeAt(0); if (!task.cancelled) task.action() }
    }

    private class Harness {
        val timer = Timer()
        val opened = mutableListOf<String>()
        val dialogs = mutableListOf<Dialog>()
        var enabled = true
        class Dialog(val decision: NativeLinkDecision, val display: String, val answer: (Boolean) -> Unit) {
            var disposed = false
        }
        val opener = NativeTerminalLinkOpener(timer, { decision, display, answer ->
            val dialog = Dialog(decision, display, answer); dialogs += dialog
            TerminalDisposable { dialog.disposed = true; answer(true) } // Simulate a late callback during dismissal.
        }, { opened += assertNotNull(it.absoluteString) }, { enabled })
    }

    @Test fun customSchemesRequireDeferredExplicitConfirmationAndDenialsNeverLaunch() {
        val h = Harness()
        try {
            assertTrue(h.opener.open("https://example.org"))
            assertEquals(listOf("https://example.org"), h.opened)
            h.opened.clear()
            assertTrue(h.opener.open("ssh://example.org"))
            assertTrue(h.dialogs.isEmpty()); assertTrue(h.opened.isEmpty())
            h.timer.fire()
            assertIs<NativeLinkDecision.Confirm>(h.dialogs.last().decision)
            assertEquals("ssh://example.org", h.dialogs.last().display)
            h.dialogs.last().answer(false)
            assertTrue(h.opened.isEmpty())
            h.opener.open("ssh://example.org"); h.timer.fire(); h.dialogs.last().answer(true)
            assertEquals(listOf("ssh://example.org"), h.opened)
            h.opened.clear()
            h.opener.open("https://example.org/\u202eevil"); h.timer.fire()
            assertIs<NativeLinkDecision.Deny>(h.dialogs.last().decision)
            assertEquals("https://example.org/\\u{202E}evil", h.dialogs.last().display)
            h.dialogs.last().answer(true)
            assertTrue(h.opened.isEmpty(), "A blocked target is handled even if the presenter returns true")
        } finally { h.opener.dispose() }
    }

    @Test fun replacementDisposalAndPolicyChangesInvalidatePendingApproval() {
        val h = Harness()
        h.opener.open("ssh://old.example")
        val staleTimer = h.timer.tasks.last()
        h.opener.open("ssh://new.example")
        assertTrue(staleTimer.cancelled)
        staleTimer.action()
        assertTrue(h.dialogs.isEmpty(), "A queued timer cannot revive a replaced request")
        h.timer.fire(); h.timer.fire()
        val first = h.dialogs.last()
        h.opener.open("ssh://third.example")
        assertTrue(first.disposed)
        first.answer(true)
        assertTrue(h.opened.isEmpty())
        h.timer.fire()
        h.enabled = false; h.dialogs.last().answer(true)
        assertTrue(h.opened.isEmpty(), "Revoked OSC 8 policy prevents approval")
        h.enabled = true
        h.opener.open("ssh://fourth.example"); h.timer.fire()
        val last = h.dialogs.last()
        h.opener.dispose(); h.opener.dispose(); last.answer(true)
        assertTrue(last.disposed); assertTrue(h.opened.isEmpty())
        assertTrue(h.opener.open("https://example.org"))
        assertTrue(h.opened.isEmpty(), "Disposed view cannot open even a directly allowed target")
    }

    @Test fun disposalBeforePresentationAndSynchronousPresenterAreHandled() {
        val h = Harness()
        h.opener.open("ssh://example.org")
        val task = h.timer.tasks.single()
        h.opener.dispose(); task.action()
        assertTrue(task.cancelled); assertTrue(h.dialogs.isEmpty())
        val timer = Timer()
        var released = 0
        var launches = 0
        val opener = NativeTerminalLinkOpener(timer, { _, _, complete ->
            complete(false)
            TerminalDisposable { released++ }
        }, { launches++ })
        opener.open("ssh://example.org"); timer.fire(); opener.dispose()
        assertEquals(1, released); assertEquals(0, launches)
        var answer: Boolean? = null
        presentNativeLinkDecision(null, classifyNativeLink("ssh://example.org"), "ssh://example.org") { answer = it }.dispose()
        assertEquals(false, answer, "Detached view cancels without opening an orphan alert")
    }

    @Test fun commandClickUsesPolicyAndHonorsOsc8SettingInExistingRows() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 800.0, 480.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val h = Harness()
        view.openLink = h.opener::open
        fun click() {
            val point = view.convertPoint(NSMakePoint(12.0, 5.0), toView = null)
            view.mouseDown(assertNotNull(NSEvent.mouseEventWithType(NSEventTypeLeftMouseDown, point,
                NSEventModifierFlagCommand, 0.0, window.windowNumber, null, 0, 1, 1.0f)))
            view.mouseUp(assertNotNull(NSEvent.mouseEventWithType(NSEventTypeLeftMouseUp, point,
                NSEventModifierFlagCommand, 0.0, window.windowNumber, null, 0, 1, 1.0f)))
        }
        try {
            session.write("\u001b]8;;ssh://example.org\u001b\\click\u001b]8;;\u001b\\")
            click(); assertTrue(h.opened.isEmpty()); h.timer.fire()
            h.dialogs.last().answer(true)
            assertEquals(listOf("ssh://example.org"), h.opened)
            session.osc8LinksEnabled = false
            click(); assertTrue(h.timer.tasks.isEmpty())
            session.osc8LinksEnabled = true
            click(); h.timer.fire(); h.dialogs.last().answer(false)
            assertEquals(1, h.opened.size)
        } finally { h.opener.dispose(); view.dispose(); window.close() }
    }
}
