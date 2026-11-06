package com.rabi.envoymcp.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
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
import com.rabi.envoymcp.mcp.NgrokSetup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Claude から使う (外からつなぐ): ngrok の中継のオン・オフ、認証トークン、ドメイン、状態と記録、コネクターの URL、はじめの手順。
 * オンにする (インターネットからの入口を開ける) ときは、ご本人か確かめてから。オフはすぐ
 */
@Composable
internal fun PublicTunnelCard(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val st by g.publicTunnel.state.collectAsState()
    val log by g.publicTunnel.log.collectAsState()
    val installed = remember(tick) { g.publicTunnel.installed() }
    val enabled = remember(tick) { g.settings.publicTunnelEnabled }
    // 認証トークンは画面に出さない (最後の 4 文字だけ)
    val tokenTail = remember(tick) { g.ngrokToken.read()?.let { NgrokSetup.tail(it) } }
    val saved = remember(tick) { g.settings.publicDomain }
    var domain by remember(tick) { mutableStateOf(saved) }
    val url = NgrokSetup.connectorUrl(saved)
    var showLog by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    val status = if (enabled) st.label else "オフ"
    val ok = if (enabled) st.ok else null
    Card(CardTitle.PUBLIC, status, ok, icon = Icons.Rounded.Public) {
        Note("Claude のアプリ (音声の会話も) から、このスマホを使えるようにします。ngrok という中継で、インターネットからこのスマホへの入口を開けます。入口が開くのは、ここをオンにして、MCP サーバーが動いている間だけです。")
        Line("本体", if (installed) "入っています" else "入っていません (scripts/build_ngrok_tunnel.sh でビルドして入れ直す)", installed)
        Toggle("外からつなぐ", enabled) { a.setPublicTunnel(it, say) }
        Line("状態", status, ok)
        if (g.publicTunnel.lastRequestAt > 0) Line("最後の取り次ぎ", SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(g.publicTunnel.lastRequestAt)))
        Note("オンにするときは、指紋などでご本人か確かめます。オフにするのは、すぐです。")

        Sub("1. ngrok を用意する")
        Note("ngrok (ngrok.com) で無料のアカウントを作ります。ngrok のページで、認証トークン (Your Authtoken) と、無料のドメイン (Domains。例: xxxx.ngrok-free.app) を用意します。")
        Line("認証トークン", if (tokenTail != null) "保存済み（$tokenTail）" else "未設定", tokenTail != null)
        Btns {
            Btn("クリップボードから保存", tokenTail == null) { say(a.saveNgrokTokenFromClipboard()) }
            if (tokenTail != null) Btn("消す") { a.clearNgrokToken(); say("ngrok の認証トークンを消しました") }
        }
        Note("認証トークンをコピーしてから「クリップボードから保存」を押します。トークンは画面に出さず (最後の 4 文字だけ)、Android の Keystore で暗号化してこの端末だけに保存し、保存後にクリップボードを空にします。")
        Line("ドメイン", saved.ifEmpty { "未設定" }, saved.isNotEmpty())
        if (shown("ドメイン", "ngrok-free.app")) {
            OutlinedTextField(domain, { domain = it }, label = { Text("ドメイン (例: xxxx.ngrok-free.app)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Btn("ドメインを保存") { say(a.savePublicDomain(domain)) }

        Sub("2. Claude に登録する")
        Line("URL", url ?: "ドメインを保存すると出ます")
        if (url != null) Btn("URL をコピー") { a.copyPlain(url); say("URL をコピーしました: $url") }
        Note("claude.ai を開き、設定 › コネクター › カスタムコネクターを追加 で、この URL を貼り付けます。")

        Sub("3. 接続して、スマホで承認する")
        Note("「接続」を押すと、このスマホに承認の画面が出ます。指紋などで承認してください。ブラウザーに ngrok の確認の画面が出たときは「Visit Site」を押して進みます。")

        Sub("4. Claude のアプリで使う")
        Note("登録すると、Android の Claude のアプリにも出ます。音声モードからも使えます。")

        if (log.isNotEmpty()) {
            Btn(if (showLog) "記録を閉じる" else "中継の記録を見る (${log.size} 行)") { showLog = !showLog }
            if (showLog) {
                Note("新しい順。認証トークンと合言葉は伏せてあります。")
                log.take(12).forEach { Mono(it) }
            }
        }
        Btn(if (showLicense) "ライセンスを閉じる" else "中継 (ngrok-go) のライセンス") { showLicense = !showLicense }
        if (showLicense) {
            val text = remember { runCatching { a.assets.open("licenses/ngrok-go/LICENSES.txt").bufferedReader().readText() }.getOrDefault("ライセンスの表示がありません (scripts/build_ngrok_tunnel.sh で入ります)") }
            Mono(text)
        }
    }
}
