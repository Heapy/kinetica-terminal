@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.Foundation.NSBundle
import platform.Foundation.NSUUID
import platform.UserNotifications.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

internal fun nativeNotificationContent(notification: TerminalNotification): UNMutableNotificationContent =
    UNMutableNotificationContent().also { it.setTitle(notification.title.ifEmpty { "Terminal" }); it.setBody(notification.body) }

/** Callbacks return on the UI thread. Kept injectable so tests never post desktop notifications. */
internal interface NativeNotificationCenter {
    fun authorize(complete: (Boolean) -> Unit)
    fun authorized(complete: (Boolean) -> Unit)
    fun add(identifier: String, content: UNMutableNotificationContent, complete: () -> Unit)
    fun remove(identifier: String)
}

private class SystemNativeNotificationCenter : NativeNotificationCenter {
    private val center = UNUserNotificationCenter.currentNotificationCenter()
    override fun authorize(complete: (Boolean) -> Unit) {
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert) { allowed, _ ->
            dispatch_async(dispatch_get_main_queue()) { complete(allowed) }
        }
    }
    override fun authorized(complete: (Boolean) -> Unit) {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            dispatch_async(dispatch_get_main_queue()) { complete(settings?.authorizationStatus == UNAuthorizationStatusAuthorized) }
        }
    }
    override fun add(identifier: String, content: UNMutableNotificationContent, complete: () -> Unit) {
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(identifier, content, null)) { _ ->
            dispatch_async(dispatch_get_main_queue()) { complete() }
        }
    }
    override fun remove(identifier: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(identifier))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(identifier))
    }
}

/** Requires a bundled app and OS authorization. Does not replace the application's global notification delegate. */
public class AppKitTerminalNotifications internal constructor(private val center: NativeNotificationCenter?) : TerminalNotificationDelivery {
    public constructor() : this(if (NSBundle.mainBundle.bundleURL.path?.endsWith(".app") == true) SystemNativeNotificationCenter() else null)
    private val identifier = "kinetica-terminal-${NSUUID().UUIDString}"
    private var disposed = false
    private var revision = 0L
    private var latestIdentifier: String? = null
    private var pending: TerminalNotification? = null
    private var inFlight = false

    override fun requestPermission(complete: (Boolean) -> Unit) {
        if (disposed || center == null) { complete(false); return }
        center.authorize { complete(!disposed && it) }
    }
    override fun show(notification: TerminalNotification) {
        if (disposed) return
        pending = notification
        deliverPending()
    }
    private fun deliverPending() {
        if (disposed || inFlight) return
        val notification = pending ?: return
        pending = null
        val center = center ?: return
        latestIdentifier?.let(center::remove)
        val expected = ++revision
        val requestIdentifier = "$identifier-$expected"
        latestIdentifier = requestIdentifier
        inFlight = true
        center.authorized { allowed ->
            if (!disposed && revision == expected && allowed) {
                center.add(requestIdentifier, nativeNotificationContent(notification)) {
                    if (disposed || revision != expected) center.remove(requestIdentifier)
                    inFlight = false; deliverPending()
                }
            } else {
                inFlight = false; deliverPending()
            }
        }
    }
    override fun clear() {
        revision++; pending = null
        latestIdentifier?.let { center?.remove(it) }
        latestIdentifier = null
    }
    override fun dispose() { if (!disposed) { disposed = true; clear() } }
}
