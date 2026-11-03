package com.rabi.envoymcp.device.adb

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.R
import com.rabi.launcher.adb.PairingReceiver

/**
 * 同じ端末でのペアリング: 設定アプリの「ペア設定コード」のダイアログは、画面を離れると閉じてコードが無効になる。
 * そこで通知の返信欄にコードを入れてもらい、ダイアログを開いたまま裏でペアリングする。
 */
object PairingNotifier {
    const val KEY = "code"
    private const val ID = 7

    fun show(context: Context, message: String = "設定の「ペア設定コードによるデバイスのペア設定」を開いたまま、ここに 6 桁のコードを入れてください") {
        val reply = RemoteInput.Builder(KEY).setLabel("6 桁のペア設定コード").build()
        val pi = PendingIntent.getBroadcast(context, 3, Intent(context, PairingReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action = Notification.Action.Builder(null, "コードを入力", pi).addRemoteInput(reply).build()
        val n = Notification.Builder(context, McpApp.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_orb)
            .setContentTitle(context.getString(R.string.app_name) + ": 端末の操作のペアリング")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .addAction(action)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
    }

    fun done(context: Context, text: String) {
        val n = Notification.Builder(context, McpApp.CH_ALERT).setSmallIcon(R.drawable.ic_stat_orb).setContentTitle(context.getString(R.string.app_name) + ": ペアリング").setContentText(text).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
    }
}
