package com.imi.smartedge.sidebar.panel

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Intent

class NotificationTrackingService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationTracker"
        
        // List of unique package names that currently have active notifications.
        // Round-7 L-High: switched from mutableSetOf<String>() to a
        // ConcurrentHashMap-backed key set. The set is mutated from the main
        // thread inside onNotificationPosted/onNotificationRemoved and from
        // updateActiveNotifications(), while AppRepository.getPanelApps()
        // iterates a snapshot via .toList() on Dispatchers.IO. The previous
        // plain HashSet would intermittently throw ConcurrentModificationException
        // when the IO iterator caught the set mid-mutation, producing a
        // NullPointerException further down `getAppsForIdentifiers` and a
        // visible sidebar flicker (an item vanishing for one refresh cycle).
        private val notificationPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        
        fun getActiveNotificationPackages(): List<String> {
            return notificationPackages.toList()
        }
        
        // Callback for UI updates
        var onNotificationsChanged: (() -> Unit)? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.packageName?.let { pkg ->
            if (notificationPackages.add(pkg)) {
                onNotificationsChanged?.invoke()
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
                onNotificationsChanged?.invoke()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}
