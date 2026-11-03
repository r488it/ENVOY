package com.rabi.envoymcp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class McpApp : Application() {
    lateinit var graph: McpGraph; private set

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        // MCP サーバーの通知では、アプリのアイコンに点を付けない (点は新しい通話の記録だけにする)。
        // 点の付け方はチャンネルを作ったあとには変えられないので、点が付く OLD_CH_SERVER は消し、CH_SERVER を使う
        runCatching { nm.deleteNotificationChannel(OLD_CH_SERVER) }
        nm.createNotificationChannel(NotificationChannel(CH_SERVER, getString(R.string.channel_server), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, "通話の記録", NotificationManager.IMPORTANCE_LOW))
        graph = McpGraph(this)
    }

    companion object {
        /** MCP サーバーが動いている間、出し続ける通知 (アイコンに点を付けない) */
        const val CH_SERVER = "server2"
        private const val OLD_CH_SERVER = "server"
        const val CH_ALERT = "alert"
        /** リゲルが代わりにかけた・出た電話の新しい記録 (アイコンに点、長押しで件数) */
        const val CH_CALLS = "calls"
    }
}
