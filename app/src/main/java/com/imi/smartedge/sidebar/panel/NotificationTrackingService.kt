package com.imi.smartedge.sidebar.panel

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArraySet

class NotificationTrackingService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationTracker"

        // Packages that currently have active notifications. A single
        // @Volatile snapshot swapped whole: the old clear() + addAll() on a
        // concurrent set let a reader on another thread (AppRepository
        // .getPanelApps on IO) see an empty or partial set, so notification
        // apps briefly dropped out of the sidebar.
        @Volatile
        private var notificationPackages: Set<String> = emptySet()

        fun getActiveNotificationPackages(): List<String> {
            return notificationPackages.toList()
        }

        // Multi-observer callback list. FloatingPanelService and AppPickerPanelView
        // both need notification updates; a single var was overwriting the other.
        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        fun addOnNotificationsChangedListener(listener: () -> Unit) {
            listeners.add(listener)
        }

        fun removeOnNotificationsChangedListener(listener: () -> Unit) {
            listeners.remove(listener)
        }

        // Audit fix: listeners were invoked synchronously on the
        // NotificationListenerService binder thread, so UI-touching
        // listeners raced/janked there. Post to the main looper instead.
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        private fun notifyListeners() {
            // Snapshot the listener set: a listener may add/remove listeners
            // while being notified. One batched runnable posts all
            // invocations to the main looper, in registration order.
            val snapshot = listeners.toList()
            mainHandler.post {
                snapshot.forEach { listener ->
                    try {
                        listener.invoke()
                    } catch (_: Exception) {
                        // Ignore individual listener failures
                    }
                }
            }
        }

        // Backward-compatible single-slot API (deprecated path). Prefer add/remove.
        @Deprecated("Use addOnNotificationsChangedListener / removeOnNotificationsChangedListener")
        var onNotificationsChanged: (() -> Unit)? = null
            set(value) {
                field?.let { removeOnNotificationsChangedListener(it) }
                field = value
                value?.let { addOnNotificationsChangedListener(it) }
            }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateActiveNotifications()
    }

    /**
     * Audit fix: the service previously had no disconnect/destroy
     * handling, so if notification-listener permission was revoked or
     * disabled, `notificationPackages` kept stale packages forever
     * (getActiveNotificationPackages() kept returning them) and the
     * static listener set was never cleared by the service itself.
     * On disconnect the tracked set is no longer trustworthy — clear it
     * and notify once so the sidebar drops its notification apps.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (notificationPackages.isNotEmpty()) {
            notificationPackages = emptySet()
            notifyListeners()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear only the tracked packages (stale after destroy). Do NOT
        // clear `listeners` here: hosts (FloatingPanelService /
        // AppPickerPanelView) register once in onCreate and remove their
        // own listener on destroy; clearing the set here would silently
        // drop their registrations across a service rebind, and
        // onListenerConnected would then notify nobody.
        notificationPackages = emptySet()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null || !isTrackable(sbn)) return
        val pkg = sbn.packageName ?: return
        val current = notificationPackages
        if (!current.contains(pkg)) {
            notificationPackages = current + pkg
            notifyListeners()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        updateActiveNotifications()
    }

    /**
     * Whether a notification counts as user activity worth promoting to the
     * front of the sidebar. Excluded: our own foreground-service
     * notification, other users' notifications, and ongoing/persistent ones
     * (media playback, VPN, foreground services) — an app with only a
     * persistent notification is not "active" in the sense the sidebar means.
     */
    private fun isTrackable(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        if (pkg == packageName) return false
        if (sbn.user != android.os.Process.myUserHandle()) return false
        if (!sbn.isClearable) return false
        val flags = sbn.notification?.flags ?: 0
        if (flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        return true
    }

    private fun updateActiveNotifications() {
        try {
            val current = mutableSetOf<String>()
            val sbns = try { getActiveNotifications() } catch (e: Exception) { null }

            // A failed read is not "zero notifications": getActiveNotifications
            // can throw (e.g. the listener was just unbound), and treating
            // that as an empty set would wipe the tracked set and fire a
            // spurious "all notifications cleared" update.
            if (sbns == null) return

            for (sbn in sbns) {
                if (isTrackable(sbn)) {
                    sbn.packageName?.let { current.add(it) }
                }
            }

            if (notificationPackages != current) {
                notificationPackages = current
                notifyListeners()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}
