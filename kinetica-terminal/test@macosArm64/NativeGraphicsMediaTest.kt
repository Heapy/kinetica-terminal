@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import kotlinx.serialization.json.*
import platform.Foundation.*
import platform.posix.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.TimeSource

class NativeGraphicsMediaTest {
    private class Resources {
        val token = NSUUID().UUIDString.replace("-", "").take(15)
        val temp = "/private/tmp/kinetica-media-$token"
        // The checkout itself may live under /tmp. Keep "outside" fixtures in a
        // user cache directory so they cannot be admitted as system temporary files.
        val work = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String) +
            "/kinetica-media-$token"
        private val shared = mutableListOf<String>()
        init { directory(temp); directory(work) }
        fun directory(path: String) { assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(path,true,null,null)) }
        fun file(path: String, bytes: ByteArray) {
            val file = assertNotNull(fopen(path,"wb"))
            try { if (bytes.isNotEmpty()) bytes.usePinned { assertEquals(bytes.size.toULong(),fwrite(it.addressOf(0),1u,bytes.size.toULong(),file)) } }
            finally { fclose(file) }
        }
        fun shm(name: String, bytes: ByteArray, size: Int = bytes.size) {
            val fd = shm_open(name,O_RDWR or O_CREAT or O_EXCL,384)
            assertTrue(fd >= 0,"shm_open errno=$errno")
            shared += name
            try {
                assertEquals(0,ftruncate(fd,size.toLong()))
                if (size > 0) {
                    val mapping = mmap(null,size.toULong(),PROT_READ or PROT_WRITE,MAP_SHARED,fd,0)
                    assertTrue(mapping != null && mapping != MAP_FAILED)
                    try { if (bytes.isNotEmpty()) bytes.usePinned { memcpy(mapping,it.addressOf(0),bytes.size.toULong()) } }
                    finally { munmap(mapping,size.toULong()) }
                }
            } finally { close(fd) }
        }
        fun exists(path: String) = NSFileManager.defaultManager.fileExistsAtPath(path)
        fun shmExists(name: String): Boolean {
            val fd = shm_open(name,O_RDONLY,0)
            if (fd >= 0) { close(fd); return true }
            assertEquals(ENOENT,errno,"Unexpected shared-memory reopen error")
            return false
        }
        fun dispose() {
            shared.forEach { shm_unlink(it) }
            NSFileManager.defaultManager.removeItemAtPath(temp,null)
            NSFileManager.defaultManager.removeItemAtPath(work,null)
        }
    }

    private fun request(medium: TerminalGraphicsMedium, name: String, offset: Long = 0, size: Long = 0,
        format: Int = 24, width: Long = 1, height: Long = 1, compressed: Boolean = false, maxBytes: Int = 400*1024*1024) =
        TerminalGraphicsMediaRequest(medium,name.encodeToByteArray(),offset,size,format,width,height,compressed,maxBytes,10_000)

    private fun resultName(result: TerminalGraphicsMediaResult): String? = when (result) {
        is TerminalGraphicsMediaResult.Data -> null
        TerminalGraphicsMediaResult.UNSUPPORTED -> "UnsupportedMedium"
        TerminalGraphicsMediaResult.INVALID_DATA -> "InvalidData"
        TerminalGraphicsMediaResult.TEMPORARY_FILE_NOT_IN_TEMP_DIR -> "TemporaryFileNotInTempDir"
        TerminalGraphicsMediaResult.TEMPORARY_FILE_NOT_NAMED_CORRECTLY -> "TemporaryFileNotNamedCorrectly"
        TerminalGraphicsMediaResult.DIMENSIONS_TOO_LARGE -> "DimensionsTooLarge"
    }

    @Test fun nativeReadersAndCleanupMatchOriginalGhostty() {
        for (encoded in terminalNativeMediaFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            fun text(key: String) = case.getValue(key).jsonPrimitive.content
            fun number(key: String) = case.getValue(key).jsonPrimitive.long
            fun flag(key: String) = case.getValue(key).jsonPrimitive.boolean
            val resources = Resources()
            val label = text("name")
            try {
                val custom = resources.work + "/custom"; resources.directory(custom)
                var configured = custom
                if (flag("configuredAlias")) {
                    configured = resources.work + "/alias"
                    assertEquals(0,symlink(custom,configured))
                }
                val data = text("data").hexToByteArray()
                val filename = if (flag("goodName")) "tty-graphics-protocol-image" else "image"
                val parent = when (text("location")) {
                    "temp" -> resources.temp; "custom" -> custom
                    "outside" -> resources.work; else -> resources.work + "/custom-other"
                }
                resources.directory(parent)
                var target = "$parent/$filename"
                var link: String? = null
                var shm: String? = null
                var path: String
                val kind = text("pathKind")
                val medium = when (text("medium")) {
                    "f" -> TerminalGraphicsMedium.FILE; "t" -> TerminalGraphicsMedium.TEMPORARY_FILE
                    else -> TerminalGraphicsMedium.SHARED_MEMORY
                }
                if (medium == TerminalGraphicsMedium.SHARED_MEMORY) {
                    val stem = "kn" + resources.token
                    path = when (text("shmName")) {
                        "slash" -> "/$stem"; "plain" -> stem; "group" -> "kg.group/$stem"
                        "slash_only" -> "/"; else -> "x".repeat(256)
                    }
                    if (kind !in listOf("missing","empty") && text("shmName") !in listOf("slash_only","too_long")) {
                        shm = path
                        resources.shm(path,data,maxOf(data.size,number("objectSize").toInt()))
                    }
                } else {
                    if (kind == "directory") resources.directory(target)
                    else if (kind !in listOf("missing","empty","device")) resources.file(target,data)
                    if (kind.startsWith("symlink")) {
                        assertEquals(0,unlink(target))
                        when (kind) {
                            "symlink_outside" -> { target = resources.work + "/$filename"; link = resources.temp + "/$filename" }
                            "symlink_inside" -> { target = resources.temp + "/$filename"; link = resources.work + "/$filename" }
                            else -> { target = resources.temp + "/image"; link = resources.work + "/$filename" }
                        }
                        resources.file(target,data); assertEquals(0,symlink(target,link))
                    }
                    path = link ?: target
                    if (kind == "relative") {
                        val depth = NSFileManager.defaultManager.currentDirectoryPath.split('/').count { it.isNotEmpty() }
                        path = "../".repeat(depth) + target.removePrefix("/")
                    }
                    if (kind == "device") path = "/dev/null"
                }
                if (kind == "nul") path += "\u0000suffix"
                if (kind == "empty") path = ""
                val reader = MacOsTerminalGraphicsMedia(flag("allowed"),flag("allowed"),flag("allowed"),configured)
                val result = reader.read(request(medium,path,number("offset"),number("size"),number("format").toInt(),number("width"),number("height"),flag("compressed")))
                val expected = case.getValue("expected").jsonObject
                if ("upstreamCrash" in expected) {
                    // The original Zig file reader overflows on a seek past EOF with S omitted.
                    // Exercise our defined EOF recovery, without calling it original conformance.
                    assertContentEquals(byteArrayOf(),assertIs<TerminalGraphicsMediaResult.Data>(result,label).bytes,label)
                    assertEquals(medium != TerminalGraphicsMedium.TEMPORARY_FILE,resources.exists(target),label)
                } else {
                    assertEquals(expected.getValue("error_name").jsonPrimitive.contentOrNull,resultName(result),label)
                    assertEquals(expected.getValue("bytes").jsonPrimitive.contentOrNull,(result as? TerminalGraphicsMediaResult.Data)?.bytes?.toHexString(),label)
                    expected["exists"]?.jsonPrimitive?.booleanOrNull?.let { exists ->
                        assertEquals(exists,if (shm != null) resources.shmExists(shm) else resources.exists(target),label)
                    }
                }
                expected["linkExists"]?.jsonPrimitive?.booleanOrNull?.let { exists ->
                    assertEquals(exists,NSFileManager.defaultManager.destinationOfSymbolicLinkAtPath(assertNotNull(link),null) != null,label)
                }
            } finally { resources.dispose() }
        }
    }

    @Test fun nativePolicyBoundsAndNonRegularFilesDoNotLeakDescriptors() {
        val resources = Resources()
        fun descriptors() = (0 until 1024).filter { fcntl(it,F_GETFD) >= 0 }.toSet()
        try {
            val path = resources.temp + "/tty-graphics-protocol-image"
            val reader = MacOsTerminalGraphicsMedia(true,true,true,maxReadBytes = 4)
            resources.file(path,ByteArray(8) { it.toByte() })
            assertSame(TerminalGraphicsMediaResult.UNSUPPORTED,MacOsTerminalGraphicsMedia().read(request(TerminalGraphicsMedium.TEMPORARY_FILE,path)))
            assertTrue(resources.exists(path))
            assertSame(TerminalGraphicsMediaResult.INVALID_DATA,reader.read(request(TerminalGraphicsMedium.FILE,path)))
            assertContentEquals(byteArrayOf(2,3,4),assertIs<TerminalGraphicsMediaResult.Data>(reader.read(request(TerminalGraphicsMedium.FILE,path,2,3))).bytes)
            val before = descriptors()
            repeat(64) {
                resources.file(path,byteArrayOf(1,2))
                assertSame(TerminalGraphicsMediaResult.INVALID_DATA,reader.read(request(TerminalGraphicsMedium.TEMPORARY_FILE,path,size=3)))
                assertFalse(resources.exists(path))
                val name = "/kn${resources.token}-$it"
                resources.shm(name,byteArrayOf(1,2,3),16384)
                assertSame(TerminalGraphicsMediaResult.INVALID_DATA,reader.read(request(TerminalGraphicsMedium.SHARED_MEMORY,name,offset=20000)))
                assertFalse(resources.shmExists(name))
            }
            assertEquals(before,descriptors())
            val fifo = resources.temp + "/fifo"
            assertEquals(0,mkfifo(fifo,384u))
            val start = TimeSource.Monotonic.markNow()
            assertSame(TerminalGraphicsMediaResult.INVALID_DATA,reader.read(request(TerminalGraphicsMedium.FILE,fifo)))
            assertTrue(start.elapsedNow().inWholeMilliseconds < 1000,"A FIFO must never block a session")
        } finally { resources.dispose() }
    }

    @Test fun realMediaIntegrateWithQueriesFramesAndDecodeFailureCleanup() {
        val resources = Resources()
        try {
            val session = TerminalSession(10,6)
            session.setGraphicsStorageLimit(1024*1024)
            val binding = session.attachGraphicsMedia(MacOsTerminalGraphicsMedia(true,true,true))
            val replies = StringBuilder()
            session.onInput { replies.append(it.toByteArray().decodeToString()) }
            fun command(header: String, path: String) { replies.clear(); session.write("\u001b_G$header;${Base64.encode(path.encodeToByteArray())}\u001b\\") }
            for (medium in "fts") {
                val path = if (medium == 's') "/kn${resources.token}" else resources.temp + "/tty-graphics-protocol-image"
                fun create(data: ByteArray) {
                    if (medium == 's') resources.shm(path,data,maxOf(16384,data.size)) else resources.file(path,data)
                }
                fun consumed() { if (medium == 's') assertFalse(resources.shmExists(path)) else assertEquals(medium == 'f',resources.exists(path)) }
                create(byteArrayOf(1,2,3)); command("a=q,i=31,t=$medium,f=24,s=1,v=1",path)
                assertEquals("\u001b_Gi=31;OK\u001b\\",replies.toString()); assertNull(session.graphicsState.storage.image(31)); consumed()
                create(byteArrayOf(1,2,3)); command("a=T,i=31,t=$medium,f=24,s=1,v=1",path)
                assertEquals("\u001b_Gi=31;OK\u001b\\",replies.toString()); consumed()
                val image = assertNotNull(session.graphicsState.storage.image(31))
                assertContentEquals(byteArrayOf(1,2,3),image.pixels.data)
                create(byteArrayOf(4,5,6)); command("a=f,i=31,t=$medium,f=24,s=1,v=1",path)
                assertEquals("\u001b_Gi=31,r=2;OK\u001b\\",replies.toString()); assertEquals(2,image.animation.frameCount); consumed()
                // Read succeeds but PNG decoding fails; consumed media must still disappear.
                create(byteArrayOf(1,2,3)); command("a=t,i=31,t=$medium,f=100,S=3",path)
                assertTrue(replies.contains("EINVAL:")); assertNull(session.graphicsState.storage.image(31)); consumed()
                val png = terminalGraphicsPngFixtures().first()
                create(Base64.decode(png.png)); command("a=t,i=31,t=$medium,f=100,S=${Base64.decode(png.png).size}",path)
                assertEquals("\u001b_Gi=31;OK\u001b\\",replies.toString()); consumed()
                assertContentEquals(Base64.decode(png.rgba),assertNotNull(session.graphicsState.storage.image(31)).pixels.data)
                session.write("\u001b_Ga=d,d=I,i=31\u001b\\")
            }
            binding.dispose()
        } finally { resources.dispose() }
    }
}
