package com.rabi.envoymcp.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.mcp.McpServer
import com.rabi.envoymcp.mcp.TunnelClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MCP サーバー: オン・オフ、状態、リゲルのタップで止めたときの再開 */
@Composable
internal fun ServerCard(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val server by g.server.state.collectAsState()
    val enabled = remember(tick) { g.settings.serverEnabled }
    Card(CardTitle.SERVER, server.label, server is McpServer.State.Running, icon = Icons.Rounded.Dns) {
        Note("オンにしている間だけ、このスマホの中 (127.0.0.1) で MCP サーバーが動き、通知に出し続けます。ChatGPT からは、tunnel-client (OpenAI の Secure MCP Tunnel) を通して届きます。Claude からは、下の「Claude から使う」で入口を開けたときだけ届きます。呼べる道具は、下の「MCP の道具」で見られます。")
        Toggle("MCP サーバーを動かす", enabled) { a.setServer(it) }
        Line("状態", server.label, server is McpServer.State.Running)
        if (g.stoppedByUser()) {
            Line("止めた", "リゲルのタップで止めました。しばらく道具を受け付けません", false)
            Btn("受け付けを再開する") { g.clearStop(); say("受け付けを再開しました") }
        }
    }
}

/** スマホの中の tunnel-client: オン・オフ、tunnel_id、実行用キー、記録、ライセンス */
@Composable
internal fun TunnelCard(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val st by g.tunnel.state.collectAsState()
    val log by g.tunnel.log.collectAsState()
    val installed = remember(tick) { g.tunnel.installed() }
    val enabled = remember(tick) { g.settings.tunnelEnabled }
    val hasKey = remember(tick) { g.tunnelKey.read() != null }
    var id by remember(tick) { mutableStateOf(g.settings.tunnelId) }
    var showLicense by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val ok = when (st) { is TunnelClient.State.Ready -> true; is TunnelClient.State.Off, is TunnelClient.State.Starting -> null; else -> false }
    Card(CardTitle.TUNNEL, st.label, ok, icon = Icons.Rounded.Cloud) {
        Note("ChatGPT とこのスマホを OpenAI の Secure MCP Tunnel でつなぐ中継 (tunnel-client v0.0.16、Apache 2.0) を、このアプリの中で動かします。Mac は要りません。MCP サーバーが動いている間だけ動きます。Mac の tunnel-client と同じトンネルで同時に動かさないでください。")
        Line("本体", if (installed) "入っています" else "入っていません (scripts/build_tunnel_client.sh でビルドして入れ直す)", installed)
        Toggle("スマホの中で動かす", enabled) { a.setTunnel(it) }
        Line("状態", st.label, ok)
        if (g.tunnel.lastForwardAt > 0) Line("最後の取り次ぎ", SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(g.tunnel.lastForwardAt)))
        OutlinedTextField(id, { id = it }, label = { Text("tunnel_id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Btn("tunnel_id を保存") { say(a.saveTunnelId(id)) }
        Line("実行用キー", if (hasKey) "保存済み" else "未設定", hasKey)
        Btns {
            Btn("クリップボードから保存", true) { say(a.saveKeyFromClipboard()) }
            if (hasKey) Btn("消す") { a.clearKey(); say("実行用キーを消しました") }
        }
        Note("実行用キーは Platform の API keys で作り (Tunnels の Read + Use)、コピーしてから「クリップボードから保存」を押します。キーは画面に出さず、Android の Keystore で暗号化してこの端末だけに保存し、保存後にクリップボードを空にします。")
        if (log.isNotEmpty()) {
            Btn(if (showLog) "記録を閉じる" else "tunnel-client の記録を見る (${log.size} 行)") { showLog = !showLog }
            if (showLog) {
                Note("新しい順。キーと合言葉は伏せてあります。")
                log.take(12).forEach { Mono(it) }
            }
        }
        Btn(if (showLicense) "ライセンスを閉じる" else "tunnel-client のライセンス") { showLicense = !showLicense }
        if (showLicense) {
            val text = remember { runCatching { listOf("NOTICE", "LICENSE").joinToString("\n\n") { f -> a.assets.open("licenses/tunnel-client/$f").bufferedReader().readText() } }.getOrDefault("ライセンスの表示がありません (scripts/build_tunnel_client.sh で入ります)") }
            Mono(text)
        }
    }
}

/** ChatGPT のアプリ: 入っているか、開く、デジタル アシスタントに選ばれているか */
@Composable
internal fun ChatGptCard(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val installed = remember(tick) { runCatching { a.packageManager.getPackageInfo(McpGraph.CHATGPT, 0) }.isSuccess }
    Card("ChatGPT", if (installed) "入っています" else "入っていません", installed, icon = Icons.Rounded.RecordVoiceOver) {
        Line("アプリ", if (installed) "入っています" else "入っていません", installed)
        Btn("ChatGPT を開く (リゲルのタップと同じ)") { if (!g.openChatGptVoice()) say("ChatGPT を開けませんでした") }
        Note("音声モードを直接開けるかは未確認です。開けないときは ChatGPT が開くだけなので、音声は自分で始めてください。")
        val assistant = remember(tick) { a.isAssistant() }
        Line("デジタル アシスタント", if (assistant) "ENVOY MCP" else "ほかのアプリ (ENVOY MCP ではない)", assistant)
        Btn("デジタル アシスタントの設定を開く") { a.openAssistantSettings() }
        Note("「デジタル アシスタント アプリ」で ENVOY MCP を選ぶと、アシスタントを呼ぶ操作 (電源ボタンの長押し・画面の下の角からのスワイプなど) で、リゲルのタップと同じように ChatGPT の音声モードが開きます。そのあいだ Gemini は呼べません。戻すときは、同じ画面で選び直してください。")
    }
}
