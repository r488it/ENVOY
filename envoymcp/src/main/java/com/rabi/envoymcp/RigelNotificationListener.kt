package com.rabi.envoymcp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.rabi.envoymcp.call.IncomingNotice

/**
 * 「通知へのアクセス」の受け口。
 * - Android は、他のアプリの再生 (MediaSession) を読めるのを、通知へのアクセスを許可したアプリだけにしているため、そのために置く
 * - かかってきた電話 (電話・LINE など) の通知から、どのアプリか・かけてきた人・「応答」を読む (IncomingCall)。
 *   それ以外の通知は読まない
 */
class RigelNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onNotificationPosted(sbn: StatusBarNotification) { if (isCall(sbn)) onCallNotice?.invoke(sbn, false) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (isCall(sbn)) onCallNotice?.invoke(sbn, true) }
    private fun isCall(sbn: StatusBarNotification) =
        sbn.notification.category == Notification.CATEGORY_CALL || sbn.notification.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) != 0
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    /** 着信の通知 (無ければ null) */
    fun incomingCall(): IncomingNotice? = runCatching { activeNotifications }.getOrNull().orEmpty()
        .firstNotNullOfOrNull { IncomingNotice.of(it) }

    companion object {
        @Volatile var instance: RigelNotificationListener? = null
        /** 通話の通知が出た・変わった (removed = false)、消えた (removed = true)。IncomingCall が受ける (メインスレッド) */
        @Volatile var onCallNotice: ((StatusBarNotification, Boolean) -> Unit)? = null
    }
}
