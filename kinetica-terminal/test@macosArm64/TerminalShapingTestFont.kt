@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.NSFont
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import kotlin.io.encoding.Base64

internal fun <T> withTerminalShapingFont(size: Double = 14.0, block: (NSFont) -> T): T {
    val bytes = Base64.decode(terminalShapingFont())
    val data = bytes.usePinned { checkNotNull(CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong())) }
    try {
        val provider = checkNotNull(CGDataProviderCreateWithCFData(data))
        try {
            val graphicsFont = checkNotNull(CGFontCreateWithDataProvider(provider))
            try {
                val font = checkNotNull(CTFontCreateWithGraphicsFont(graphicsFont, size, null, null))
                try { return block(interpretObjCPointer<NSFont>(font.rawValue)) }
                finally { CFRelease(font) }
            } finally { CGFontRelease(graphicsFont) }
        } finally { CGDataProviderRelease(provider) }
    } finally { CFRelease(data) }
}
