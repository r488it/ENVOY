package com.rabi.envoymcp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.mcp.TunnelClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    Card("トンネル (スマホの中の tunnel-client)", st.label, when (st) { is TunnelClient.State.Ready -> true; is TunnelClient.State.Off, is TunnelClient.State.Starting -> null; else -> false }) {
        Note("ChatGPT とこのスマホを OpenAI の Secure MCP Tunnel でつなぐ中継 (tunnel-client v0.0.16、Apache 2.0) を、このアプリの中で動かします。Mac は要りません。MCP サーバーが動いている間だけ動きます。Mac の tunnel-client と同じトンネルで同時に動かさないでください。")
        Line("本体", if (installed) "入っています" else "入っていません (scripts/build_tunnel_client.sh でビルドして入れ直す)", installed)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("スマホの中で動かす", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { a.setTunnel(it) })
        }
        Line("状態", st.label, when (st) { is TunnelClient.State.Ready -> true; is TunnelClient.State.Off, is TunnelClient.State.Starting -> null; else -> false })
        if (g.tunnel.lastForwardAt > 0) Line("最後の取り次ぎ", SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(g.tunnel.lastForwardAt)))
        OutlinedTextField(id, { id = it }, label = { Text("tunnel_id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Btn("tunnel_id を保存") { say(a.saveTunnelId(id)) }
        Line("実行用キー", if (hasKey) "保存済み" else "未設定", hasKey)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** 開発者向け (ふだんは閉じておく): 接続の情報と合言葉の作り直し。Mac で試すとき・調べるときだけ使う */
@Composable
internal fun DevCard(g: McpGraph, a: SettingsActivity, say: (String) -> Unit) {
    Card("開発者向け (接続の情報)") {
        run {
            Line("URL", "http://127.0.0.1:${g.settings.port}/mcp/••••")
            Note("•••• は合言葉です (画面には出しません)。合言葉は URL の最後か、ヘッダー Authorization: Bearer <合言葉> のどちらで渡しても通ります。合言葉の無い接続・違う接続、ブラウザからの接続は断ります。スマホの中の tunnel-client には、アプリが自動で渡します。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("URL を写す") { a.copyUrl(); say("合言葉の入った URL を写しました") }
                Btn("合言葉を作り直す") { say(a.regenerateToken()) }
            }
        }
    }
}
