@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import app.native.terminal.finder.KineticaFinderServiceProtocol
import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.darwin.NSObject

/** Registered separately from the framework-owned application delegate. */
internal class FinderService(private val openDirectory: (String) -> Unit) : NSObject(), KineticaFinderServiceProtocol {
    override fun openKineticaHere(pasteboard: NSPasteboard, userData: String?, error: CPointer<ObjCObjectVar<String?>>?) {
        val directories = finderDirectories(pasteboard)
        if (directories.isEmpty()) {
            error?.pointed?.value = "Select an existing local folder in Finder."
            return
        }
        try {
            directories.forEach(openDirectory)
        } catch (failure: Exception) {
            error?.pointed?.value = "Could not open the selected folder: ${failure.message.orEmpty()}"
        }
    }
}

internal fun finderDirectories(pasteboard: NSPasteboard): List<String> {
    val urls = pasteboard.readObjectsForClasses(listOf(NSURL.`class`()),
        mapOf(NSPasteboardURLReadingFileURLsOnlyKey to true)).orEmpty().filterIsInstance<NSURL>()
    val paths = if (urls.isNotEmpty()) urls.mapNotNull { url ->
        // Never interpret file URLs naming a remote machine as local filesystem paths.
        if (url.fileURL && (url.host.isNullOrEmpty() || url.host == "localhost")) url.path else null
    } else {
        (pasteboard.propertyListForType(NSFilenamesPboardType) as? List<*>)?.filterIsInstance<String>().orEmpty()
    }
    return paths.filter { path ->
        path.startsWith('/') && '\u0000' !in path && memScoped {
            val directory = alloc<BooleanVar>()
            NSFileManager.defaultManager.fileExistsAtPath(path, directory.ptr) && directory.value
        }
    }.distinct()
}
