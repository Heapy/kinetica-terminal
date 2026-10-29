// Native media semantics adapted from Ghostty kitty/graphics_image.zig (MIT).
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import io.heapy.kinetica.terminal.media.*
import platform.Foundation.NSTemporaryDirectory
import platform.posix.*

/**
 * Local Kitty graphics media. Every medium requires explicit host permission; attach with
 * [TerminalSession.attachGraphicsMedia]. Calls are synchronous on the owning session thread.
 * Relative filenames resolve against the application's working directory, as in Ghostty.
 * Authorized temporary files and shared-memory names are consumed, including failed reads.
 */
public class MacOsTerminalGraphicsMedia(
    private val allowFiles: Boolean = false,
    private val allowTemporaryFiles: Boolean = false,
    private val allowSharedMemory: Boolean = false,
    private val temporaryDirectory: String = NSTemporaryDirectory(),
    private val maxReadBytes: Int = 400 * 1024 * 1024,
) : TerminalGraphicsMedia {
    init {
        require('\u0000' !in temporaryDirectory)
        require(maxReadBytes in 0..400 * 1024 * 1024)
    }

    override fun read(request: TerminalGraphicsMediaRequest): TerminalGraphicsMediaResult {
        val allowed = when (request.medium) {
            TerminalGraphicsMedium.FILE -> allowFiles
            TerminalGraphicsMedium.TEMPORARY_FILE -> allowTemporaryFiles
            TerminalGraphicsMedium.SHARED_MEMORY -> allowSharedMemory
        }
        if (!allowed) return TerminalGraphicsMediaResult.UNSUPPORTED
        val name = request.name
        if (name.isEmpty() || name.size >= PATH_MAX || name.any { it == 0.toByte() })
            return TerminalGraphicsMediaResult.INVALID_DATA
        if (request.medium == TerminalGraphicsMedium.SHARED_MEMORY &&
            (name.size > NAME_MAX || name.contentEquals(byteArrayOf('/'.code.toByte()))))
            return TerminalGraphicsMediaResult.INVALID_DATA
        return try {
            memScoped {
                val path = allocArray<ByteVar>(name.size + 1)
                name.forEachIndexed { i, byte -> path[i] = byte }; path[name.size] = 0
                if (request.medium == TerminalGraphicsMedium.SHARED_MEMORY) readSharedMemory(request,path)
                else readFile(request,path)
            }
        } catch (error: TerminalGraphicsMediaException) { error.result }
    }

    private fun readFile(request: TerminalGraphicsMediaRequest, path: CPointer<ByteVar>): TerminalGraphicsMediaResult = memScoped {
        // Nonblocking open prevents a FIFO from hanging the session before fstat rejects it.
        val canonical = allocArray<ByteVar>(PATH_MAX)
        val fd = kinetica_graphics_open_file(path)
        if (fd < 0) return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
        var delete = false
        try {
            if (fcntl(fd,F_GETPATH,canonical) != 0) return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
            val resolved = pathBytes(canonical)
            val text = resolved.decodeToString()
            if (text.startsWith("/proc/") || text.startsWith("/sys/") ||
                text.startsWith("/dev/") && !text.startsWith("/dev/shm/"))
                return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
            if (request.medium == TerminalGraphicsMedium.TEMPORARY_FILE) {
                if (!inTemporaryDirectory(resolved)) return@memScoped TerminalGraphicsMediaResult.TEMPORARY_FILE_NOT_IN_TEMP_DIR
                if (!text.contains("tty-graphics-protocol")) return@memScoped TerminalGraphicsMediaResult.TEMPORARY_FILE_NOT_NAMED_CORRECTLY
                delete = true
            }
            val info = alloc<stat>()
            if (fstat(fd,info.ptr) != 0 || info.st_mode.toInt() and S_IFMT != S_IFREG || info.st_size < 0)
                return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
            val available = (info.st_size - request.offset).coerceAtLeast(0)
            val length = request.readLength(available)
            if (length > maxReadBytes) return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
            val data = ByteArray(length.toInt())
            if (data.isNotEmpty()) data.usePinned { pinned ->
                var position = 0
                while (position < data.size) {
                    val count = pread(fd,pinned.addressOf(position),(data.size - position).toULong(),request.offset + position)
                    if (count < 0 && errno == EINTR) continue
                    if (count <= 0) return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
                    position += count.toInt()
                }
            }
            if (request.size == 0L) {
                // Reject a growing file instead of silently truncating its omitted-size read.
                val extra = alloc<ByteVar>()
                var count: Long
                do { count = pread(fd,extra.ptr,1u,request.offset + length) } while (count < 0 && errno == EINTR)
                if (count != 0L) return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
            }
            TerminalGraphicsMediaResult.Data(data)
        } finally {
            close(fd)
            if (delete) kinetica_graphics_unlink_file(canonical)
        }
    }

    private fun inTemporaryDirectory(path: ByteArray): Boolean {
        if (inDirectory("/tmp",path) || inDirectory("/private/tmp",path) ||
            inDirectory("/dev/shm",path) || inDirectory(temporaryDirectory,path)) return true
        return memScoped {
            val canonical = allocArray<ByteVar>(PATH_MAX)
            realpath(temporaryDirectory,canonical) != null && inDirectory(pathBytes(canonical),path)
        }
    }

    private fun readSharedMemory(request: TerminalGraphicsMediaRequest, name: CPointer<ByteVar>): TerminalGraphicsMediaResult = memScoped {
        val fd = kinetica_graphics_open_shm(name)
        if (fd < 0) return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
        try {
            val info = alloc<stat>()
            if (fstat(fd,info.ptr) != 0 || info.st_size <= 0)
                return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
            val length = request.readLength(info.st_size - request.offset)
            if (length > maxReadBytes) return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
            if (length == 0L) return@memScoped TerminalGraphicsMediaResult.Data(byteArrayOf())
            val page = sysconf(_SC_PAGESIZE)
            if (page <= 0) return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
            val offset = request.offset / page * page
            val prefix = request.offset - offset
            val mappedLength = (prefix + length).toULong()
            // Map only the requested range plus alignment, not an arbitrarily large object.
            val mapping = mmap(null,mappedLength,PROT_READ,MAP_SHARED,fd,offset)
            if (mapping == MAP_FAILED || mapping == null) return@memScoped TerminalGraphicsMediaResult.INVALID_DATA
            try {
                val data = ByteArray(length.toInt())
                data.usePinned { memcpy(it.addressOf(0),mapping.reinterpret<ByteVar>() + prefix.toInt(),length.toULong()) }
                TerminalGraphicsMediaResult.Data(data)
            } finally { munmap(mapping,mappedLength) }
        } finally {
            kinetica_graphics_unlink_shm(name)
            close(fd)
        }
    }

    private fun inDirectory(directory: String, path: ByteArray): Boolean = inDirectory(directory.encodeToByteArray(),path)
    private fun pathBytes(path: CPointer<ByteVar>): ByteArray {
        var size = 0
        while (size < PATH_MAX && path[size] != 0.toByte()) size++
        return path.readBytes(size)
    }
    private fun inDirectory(directory: ByteArray, path: ByteArray): Boolean = directory.isNotEmpty() &&
        path.size >= directory.size && directory.indices.all { path[it] == directory[it] } &&
        (path.size == directory.size || directory.last() == '/'.code.toByte() || path[directory.size] == '/'.code.toByte())
}
