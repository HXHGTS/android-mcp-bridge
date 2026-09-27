package com.ikun.androidmcp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NotificationCaptureService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Cache.clear()
        Cache.setConnected(true)
        runCatching { activeNotifications?.forEach { Cache.put(it, packageName) } }
    }

    override fun onListenerDisconnected() {
        Cache.setConnected(false)
        Cache.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Cache.put(sbn, packageName)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Cache.remove(sbn.key)
    }

    object Cache {
        private val items = LinkedHashMap<String, JSONObject>()
        @Volatile private var connected = false

        @Synchronized fun put(sbn: StatusBarNotification, ownPackage: String) {
            if (sbn.packageName == ownPackage) return
            val extras = sbn.notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
            val row = JSONObject()
                .put("packageName", sbn.packageName)
                .put("title", title.take(300))
                .put("text", text.take(800))
                .put("postedAtMs", sbn.postTime)
                .put("ongoing", sbn.isOngoing)
            items[sbn.key] = row
            while (items.size > 100) items.remove(items.keys.first())
        }

        @Synchronized fun remove(key: String) { items.remove(key) }
        @Synchronized fun snapshot(): List<JSONObject> = items.values.map { JSONObject(it.toString()) }.takeLast(50)
        @Synchronized fun clear() { items.clear() }
        fun setConnected(value: Boolean) { connected = value }
        fun isConnected(): Boolean = connected
    }
}
