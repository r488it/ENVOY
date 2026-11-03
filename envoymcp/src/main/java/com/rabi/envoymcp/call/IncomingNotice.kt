package com.rabi.envoymcp.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification

/** 着信の通知: アプリ・かけてきた人の名前 (番号のこともある)・「応答」 (分からなければ null) */
data class IncomingNotice(val pkg: String, val name: String, val answer: PendingIntent?) {
    companion object {
        private val ANSWER_WORDS = listOf("応答", "Answer", "Accept")

        /** 着信の通知なら読む (通話の通知で、着信の種類か「応答」があるもの。発信中・通話中の通知は除く) */
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
            return IncomingNotice(sbn.packageName, name.trim(), answer)
        }

        private fun <T> parcel(b: Bundle, key: String, cls: Class<T>): T? =
            if (Build.VERSION.SDK_INT >= 33) b.getParcelable(key, cls)
            else @Suppress("DEPRECATION") cls.cast(b.getParcelable(key))
    }
}
