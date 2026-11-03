package com.rabi.launcher.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.device.adb.PairingNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ペアリングの通知の返信欄に入れたコードを受ける。
 * 通知の PendingIntent の宛先なので、クラスの名前 (com.rabi.launcher.adb.PairingReceiver) は変えない
 */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifier.KEY)?.toString() ?: return
        val g = (context.applicationContext as McpApp).graph
        val pr = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                PairingNotifier.show(context, "ペアリング中…")
                val r = g.adb.pair(code)
                if (r.isSuccess) {
                    val c = g.adb.ensureConnected()
                    PairingNotifier.done(context, if (c) "ペアリングして接続しました" else "ペアリングしました。接続は設定画面から試してください")
                } else PairingNotifier.show(context, "できませんでした: ${r.exceptionOrNull()?.message ?: ""}。もう一度コードを入れてください")
            } finally { pr.finish() }
        }
    }
}
