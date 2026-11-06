package com.rabi.envoymcp.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.rabi.envoymcp.Notices

/** 着信の通知: アプリ・かけてきた人の名前 (番号のこともある。外から届いた文なので記号を除いて短くする)・「応答」 (分からなければ null) */
data class IncomingNotice(val pkg: String, val name: String, val answer: PendingIntent?) {
    companion object {
        private val ANSWER_WORDS = listOf("応答", "Answer", "Accept")

        /**
         * 着信の通知を出してよいアプリか: LINE と、電話のアプリ (NumberMask.DIALERS) のうち、この端末の電話のアプリ (isPhoneApp:
         * 既定・システムの電話のアプリか、システムに入っているもの)。ほかのアプリは、通話の通知の形でも着信とみなさない
         * (偽の着信で、名前を ChatGPT に送ったり、お知らせに残したりできないように。端末に無い電話のアプリと同じ名前で入れたアプリも除く)
         */
        fun fromCallApp(pkg: String, isPhoneApp: (String) -> Boolean): Boolean =
            pkg == LineCaller.LINE || (pkg in NumberMask.DIALERS && isPhoneApp(pkg))

        /**
         * 着信の通知なら読む (通話の通知で、着信の種類か「応答」があるもの。発信中・通話中の通知は除く)。
         * どのアプリの通知かは、呼ぶ側 (RigelNotificationListener) が fromCallApp で先に確かめる
         */
        fun of(sbn: StatusBarNotification): IncomingNotice? {
            val n = sbn.notification
            val x = n.extras
            val callType = x.getInt(Notification.EXTRA_CALL_TYPE, 0)
            if (n.category != Notification.CATEGORY_CALL && callType == 0) return null
            if (callType != 0 && callType != Notification.CallStyle.CALL_TYPE_INCOMING) return null
            val answer = parcel(x, Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
                ?: n.actions?.firstOrNull { a -> ANSWER_WORDS.any { w -> a.title?.toString()?.contains(w) == true } }?.actionIntent
            if (callType == 0 && answer == null) return null
            val person = parcel(x, Notification.EXTRA_CALL_PERSON, Person::class.java)
            val name = person?.name?.toString() ?: x.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            return IncomingNotice(sbn.packageName, Notices.clean(name, Notices.NAME_MAX_CHARS), answer)
        }

        private fun <T> parcel(b: Bundle, key: String, cls: Class<T>): T? =
            if (Build.VERSION.SDK_INT >= 33) b.getParcelable(key, cls)
            else @Suppress("DEPRECATION") cls.cast(b.getParcelable(key))
    }
}
