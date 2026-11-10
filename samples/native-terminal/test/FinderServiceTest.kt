@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import app.native.terminal.finder.KineticaFinderServiceProtocol
import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

class FinderServiceTest {
    @Test fun readsFileUrlsAndLegacyPathsWithoutInterpretingShellCharacters() = withFolders { first, second, pasteboard ->
        val file = "$first/file.txt"
        assertTrue(NSFileManager.defaultManager.createFileAtPath(file, null, null))
        assertTrue(pasteboard.writeObjects(listOf(NSURL.fileURLWithPath(first), NSURL.fileURLWithPath(second),
            NSURL.fileURLWithPath(file), NSURL.fileURLWithPath("$first/missing"))))
        assertEquals(listOf(first, second), finderDirectories(pasteboard))

        pasteboard.clearContents()
        pasteboard.declareTypes(listOf(NSFilenamesPboardType), null)
        assertTrue(pasteboard.setPropertyList(listOf(first, second, first, file), NSFilenamesPboardType))
        assertEquals(listOf(first, second), finderDirectories(pasteboard))

        pasteboard.clearContents()
        pasteboard.setString("file://remote-host/", NSPasteboardTypeFileURL)
        assertEquals(emptyList(), finderDirectories(pasteboard))
        pasteboard.clearContents()
        pasteboard.setString(first, NSPasteboardTypeString)
        assertEquals(emptyList(), finderDirectories(pasteboard))
    }

    @Test fun reportsInvalidSelectionAndOpeningFailureThroughServiceError() = withFolders { first, _, pasteboard ->
        val opened = mutableListOf<String>()
        val provider: KineticaFinderServiceProtocol = FinderService(opened::add)
        assertTrue(provider.respondsToSelector(NSSelectorFromString("openKineticaHere:userData:error:")))
        memScoped {
            val error = alloc<ObjCObjectVar<String?>>()
            error.value = null
            provider.openKineticaHere(pasteboard, null, error.ptr)
            assertNotNull(error.value)
            assertTrue(opened.isEmpty())
            pasteboard.writeObjects(listOf(NSURL.fileURLWithPath(first)))
            error.value = null
            provider.openKineticaHere(pasteboard, null, error.ptr)
            assertNull(error.value)
            assertEquals(listOf(first), opened)
            val failing: KineticaFinderServiceProtocol = FinderService { throw IllegalStateException("PTY unavailable") }
            failing.openKineticaHere(pasteboard, null, error.ptr)
            assertTrue(error.value.orEmpty().contains("PTY unavailable"))
            failing.openKineticaHere(pasteboard, null, null)
        }
    }

    @Test fun serviceLaunchAndRepeatedRequestsOpenShellsInSelectedDirectories() = withFolders { first, second, pasteboard ->
        val application = NSApplication.sharedApplication()
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        application.finishLaunching()
        val previousProvider = application.servicesProvider
        val owner = TerminalApplication(application, settings = TerminalSettingsStore(null))
        fun sameDirectory(actual: String?, expected: String): Boolean = actual != null &&
            NSFileManager.defaultManager.attributesOfItemAtPath(actual, null)?.get(NSFileSystemFileNumber) ==
                NSFileManager.defaultManager.attributesOfItemAtPath(expected, null)?.get(NSFileSystemFileNumber)
        fun await(condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition() && start.elapsedNow().inWholeSeconds < 10) {
                application.nextEventMatchingMask(NSEventMaskAny, NSDate.dateWithTimeIntervalSinceNow(0.01), NSDefaultRunLoopMode, true)
                    ?.let(application::sendEvent)
            }
            assertTrue(condition(), "Finder shell lifecycle timed out")
        }
        fun launch(default: Boolean) = NSNotificationCenter.defaultCenter.postNotificationName(
            NSApplicationDidFinishLaunchingNotification, application,
            mapOf(NSApplicationLaunchIsDefaultLaunchKey to NSNumber(bool = default)),
        )
        try {
            launch(false)
            assertTrue(owner.terminals.isEmpty())
            val provider = assertIs<KineticaFinderServiceProtocol>(application.servicesProvider)
            pasteboard.writeObjects(listOf(NSURL.fileURLWithPath(first), NSURL.fileURLWithPath(second)))
            memScoped {
                val error = alloc<ObjCObjectVar<String?>>()
                error.value = null
                provider.openKineticaHere(pasteboard, null, error.ptr)
                assertNull(error.value)
            }
            assertEquals(2, owner.terminals.size)
            await { owner.terminals.zip(listOf(first, second)).all { (terminal, path) -> sameDirectory(terminal.currentWorkingDirectory(), path) } }
            assertTrue(owner.terminals.all { it.window.visible })
            assertTrue(owner.terminals.all { (it.window.tabGroup?.windows?.size ?: 1) == 1 })
            launch(true) // A late startup notification must not create an extra home shell.
            assertEquals(2, owner.terminals.size)
            pasteboard.clearContents()
            pasteboard.writeObjects(listOf(NSURL.fileURLWithPath(first)))
            provider.openKineticaHere(pasteboard, null, null)
            assertEquals(3, owner.terminals.size)
            await { sameDirectory(owner.terminals.last().currentWorkingDirectory(), first) }
        } finally {
            owner.dispose()
            await { owner.shell.closingWindowCount == 0 }
            assertEquals(previousProvider, application.servicesProvider)
        }
        val normal = TerminalApplication(application, settings = TerminalSettingsStore(null))
        try {
            launch(true)
            assertEquals(1, normal.terminals.size)
        } finally {
            normal.dispose()
            await { normal.shell.closingWindowCount == 0 }
        }
    }

    private fun withFolders(test: (String, String, NSPasteboard) -> Unit) {
        val root = NSTemporaryDirectory() + "kinetica-finder-" + NSUUID().UUIDString
        val first = "$root/Папка with 'quotes' \$() ; & %"
        val second = "$root/Second folder"
        val files = NSFileManager.defaultManager
        assertTrue(files.createDirectoryAtPath(first, true, null, null))
        assertTrue(files.createDirectoryAtPath(second, true, null, null))
        val pasteboard = NSPasteboard.pasteboardWithUniqueName()
        try { test(first, second, pasteboard) } finally {
            pasteboard.releaseGlobally()
            files.removeItemAtPath(root, null)
        }
    }
}
