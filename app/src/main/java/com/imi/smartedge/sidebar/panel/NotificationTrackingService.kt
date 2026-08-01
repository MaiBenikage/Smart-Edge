package com.imi.smartedge.sidebar.panel

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArraySet

class NotificationTrackingService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationTracker"

        // Concurrent set of packages that currently have active notifications.
        private val notificationPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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

        private fun notifyListeners() {
            listeners.forEach { listener ->
                try {
                    listener.invoke()
                } catch (_: Exception) {
                    // Ignore individual listener failures
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.packageName?.let { pkg ->
            if (notificationPackages.add(pkg)) {
                notifyListeners()
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        updateActiveNotifications()
    }

    private fun updateActiveNotifications() {
        try {
            val current = mutableSetOf<String>()
            val sbns = try { getActiveNotifications() } catch (e: Exception) { null }

            if (sbns != null) {
                for (sbn in sbns) {
                    sbn.packageName?.let { current.add(it) }
                }
            }

            if (notificationPackages != current) {
                notificationPackages.clear()
                notificationPackages.addAll(current)
                notifyListeners()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}
