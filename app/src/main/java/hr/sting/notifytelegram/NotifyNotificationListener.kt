package hr.sting.notifytelegram

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

/**
 * Receives every notification and hands the ones from selected packages to
 * [NotificationForwarder]. The system binds this service as soon as the user
 * grants notification access and rebinds it after a reboot; no foreground
 * service, wake lock or polling connection is involved.
 */
class NotifyNotificationListener : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notify-forwarder").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        LogBus.log("Notification listener connected")
        // Deliberately does not replay currently active notifications: after a
        // rebind that would resend old items. New posts and updates are enough.
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogBus.warn("Notification listener disconnected, requesting rebind")
        runCatching {
            requestRebind(ComponentName(this, NotifyNotificationListener::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        executor.execute { NotificationForwarder.maybeForward(this, posted) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { NotificationForwarder.forget(it.key) }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: NotifyNotificationListener? = null
            private set
    }
}
