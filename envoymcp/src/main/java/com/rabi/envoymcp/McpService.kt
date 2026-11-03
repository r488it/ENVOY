package com.rabi.envoymcp

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.mcp.McpServer
import com.rabi.envoymcp.mcp.TunnelClient
import com.rabi.envoymcp.ui.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MCP サーバーを動かしている間のフォアグラウンドサービス (動いていることを通知に出し続ける)。
 * - 本人が設定の画面でオンにしたときだけ始める (初期値はオフ)。通知の「止める」か設定の画面でオフにすると止まる
 * - 落ちても勝手には立ち上げ直さない (START_NOT_STICKY)。再起動後の自動再開もしない (最小の接続の検証には要らない)
 */
class McpService : Service() {
    private var scope: CoroutineScope? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val g = (application as McpApp).graph
        if (intent?.action == ACTION_STOP) {
            RabiLog.i("mcp", "stopped from notification")
            g.settings.serverEnabled = false
            stopSelf(); return START_NOT_STICKY
        }
        if (!g.settings.serverEnabled) { stopSelf(); return START_NOT_STICKY }
        if (!goForeground(g)) { stopSelf(); return START_NOT_STICKY }
        g.settings.token   // 合言葉を用意しておく (無ければここで作る。Mac から試すときに読めるように)
        g.server.start()
        if (g.server.state.value is McpServer.State.Failed) {
            getSystemService(NotificationManager::class.java).notify(ID, notification(g.settings.port, g.server.state.value.label))
            return START_NOT_STICKY
        }
        applyTunnel(g)
        // AgentARM (本人が設定でオンにしたときだけ)
        if (g.settings.armEnabled) g.arm.start() else g.arm.stop()
        // 音楽が流れたらリゲルの画面を出す・踊る (見張りは、サービスが動いている間だけ)
        g.serviceRunning = true
        g.refreshMusic()
        g.monitorStage.start()   // モニター (PC モード) につながったら、全画面のリゲルを出し、操作先をモニターにする
        // 前面のアプリを読むための、端末の操作の接続 (ペアリング済みのときだけ。待たせないよう裏で)
        if (g.adb.paired && !g.adb.isConnected) bg().launch { g.adb.ensureConnected() }
        watchNetwork(g)
        return START_NOT_STICKY
    }

    /**
     * 通知を出して常駐する。AgentARM を使い、カメラ・マイクの許可があるときは、カメラ・マイクの種類も付ける
     * (マイクは、音楽に合わせて踊るときに端末の出力の音を読むため)
     * (ChatGPT などが前面にあるときも、顔を追えるように。Android はカメラを使う常駐を、アプリが見えているときにしか始めさせない。
     * 見えていないときに断られたら、カメラの種類を外して常駐し、設定の画面を開いたときに付け直す)
     */
    private fun goForeground(g: McpGraph): Boolean {
        fun granted(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        var extra = 0
        if (g.settings.armEnabled && granted(android.Manifest.permission.CAMERA)) extra = extra or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (g.settings.armEnabled && granted(android.Manifest.permission.RECORD_AUDIO)) extra = extra or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (extra != 0) {
            try {
                startForeground(ID, notification(g.settings.port), base or extra)
                return true
            } catch (e: Exception) {
                RabiLog.w("mcp", "startForeground with camera/mic failed: ${e.javaClass.simpleName}")
            }
        }
        return try {
            startForeground(ID, notification(g.settings.port), base)
            true
        } catch (e: Exception) {
            // 見えていない状態から始めようとした (Android の制限) など
            RabiLog.w("mcp", "startForeground failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** スマホの中の tunnel-client: 本人がオンにしていて、本体・tunnel_id・実行用キーがそろっているときだけ動かす */
    private fun applyTunnel(g: McpGraph) {
        val s = g.settings
        if (!s.tunnelEnabled) { g.tunnel.stop(); return }
        val key = g.tunnelKey.read()
        when {
            !g.tunnel.installed() -> g.tunnel.notReady("tunnel-client が入っていません (scripts/build_tunnel_client.sh でビルドして入れ直す)")
            !s.tunnelId.startsWith("tunnel_") -> g.tunnel.notReady("tunnel_id がありません")
            key.isNullOrBlank() -> g.tunnel.notReady("実行用キーがありません")
            else -> g.tunnel.start(TunnelClient.Config(key, s.tunnelId, s.port, s.token))
        }
    }

    private fun bg(): CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }

    /**
     * Wi-Fi などのネットが戻ったら、端末の操作の接続 (ワイヤレスデバッグ) をつなぎ直す。
     * Wi-Fi が一度切れて戻ったあと、つなぎ直さないと音声モードの覆いも道具も使えないため
     */
    private fun watchNetwork(g: McpGraph) {
        if (netCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!g.adb.paired || g.adb.isConnected) return
                bg().launch { delay(NET_SETTLE_MS); if (!g.adb.isConnected) g.adb.ensureConnected() }
            }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) }
            .onSuccess { netCallback = cb }
            .onFailure { RabiLog.w("mcp", "network callback failed: ${it.javaClass.simpleName}") }
    }

    override fun onDestroy() {
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        netCallback = null
        (application as McpApp).graph.tunnel.stop()
        (application as McpApp).graph.server.stop()
        (application as McpApp).graph.arm.stop()
        (application as McpApp).graph.serviceRunning = false
        (application as McpApp).graph.refreshMusic()
        scope?.cancel()
        super.onDestroy()
    }

    private fun notification(port: Int, problem: String? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val via = if ((application as McpApp).graph.settings.tunnelEnabled) "スマホの中の tunnel-client で ChatGPT とつなぎます" else "ChatGPT からは tunnel-client を通して届きます"
        val text = problem ?: "このスマホの中 (127.0.0.1:$port) だけで受け付けています。$via"
        return Notification.Builder(this, McpApp.CH_SERVER)
            .setSmallIcon(R.drawable.ic_stat_orb)
            .setContentTitle(if (problem == null) "MCP サーバーが動いています" else "MCP サーバーを動かせません")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "止める", stop).build())
            .build()
    }

    companion object {
        private const val ID = 1
        /** ネットが戻ってから、ワイヤレスデバッグが待ち受けを始めるまでの待ち */
        private const val NET_SETTLE_MS = 2_000L
        private const val ACTION_STOP = "com.rabi.envoymcp.STOP"

        /** 見えている画面 (設定・ホーム) から呼ぶ */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, McpService::class.java)) }
                .onFailure { RabiLog.w("mcp", "start failed: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, McpService::class.java)) }
    }
}
