package com.rabi.envoymcp

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import com.rabi.envoymcp.call.IncomingNotice

/**
 * 「通知へのアクセス」の受け口。
 * - Android は、他のアプリの再生 (MediaSession) を読めるのを、通知へのアクセスを許可したアプリだけにしているため、そのために置く
 * - かかってきた電話 (電話のアプリ・LINE) の通知から、どのアプリか・かけてきた人・「応答」を読む (IncomingCall)。
 *   鳴りやんだあとは、通話の通知が続いているか (だれかが出たか) を見る。それ以外の通知は読まない
 * - 通話の通知とみなすのは、電話のアプリと LINE が出したものだけ (IncomingNotice.fromCallApp)。ほかのアプリが通話の形の通知を出しても読まない
 */
class RigelNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onNotificationPosted(sbn: StatusBarNotification) { if (isCall(sbn)) onCallNotice?.invoke(sbn, false) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (isCall(sbn)) onCallNotice?.invoke(sbn, true) }
    private fun isCall(sbn: StatusBarNotification) =
        (sbn.notification.category == Notification.CATEGORY_CALL || sbn.notification.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) != 0) &&
            IncomingNotice.fromCallApp(sbn.packageName) { isPhoneApp(it) }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    /** この端末の電話のアプリか: 既定・システムの電話のアプリ (Android が決めたもの) か、システムに入っているアプリ */
    private fun isPhoneApp(pkg: String): Boolean {
        val tm = runCatching { getSystemService(TelecomManager::class.java) }.getOrNull()
        if (tm != null && (pkg == tm.defaultDialerPackage || pkg == tm.systemDialerPackage)) return true
        return runCatching { packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false)
    }

    /** 着信の通知 (無ければ null) */
    fun incomingCall(): IncomingNotice? = runCatching { activeNotifications }.getOrNull().orEmpty()
        .filter { isCall(it) }.firstNotNullOfOrNull { IncomingNotice.of(it) }

    /** pkg の通話が続いている (出たあとの、消せない通話の通知がある) */
    fun ongoingCall(pkg: String): Boolean = runCatching { activeNotifications }.getOrNull().orEmpty().any {
        it.packageName == pkg && isCall(it) && IncomingNotice.of(it) == null && it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    }

    companion object {
        @Volatile var instance: RigelNotificationListener? = null
        /** 通話の通知が出た・変わった (removed = false)、消えた (removed = true)。IncomingCall が受ける (メインスレッド) */
        @Volatile var onCallNotice: ((StatusBarNotification, Boolean) -> Unit)? = null
    }
}
