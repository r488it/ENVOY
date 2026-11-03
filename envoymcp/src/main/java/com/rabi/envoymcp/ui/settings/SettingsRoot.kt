package com.rabi.envoymcp.ui.settings

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.R
import com.rabi.envoymcp.device.adb.AdbState
import com.rabi.envoymcp.device.adb.PairingNotifier
import com.rabi.envoymcp.mcp.McpServer
import com.rabi.envoymcp.mcp.ToolLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SettingsRoot(g: McpGraph, a: SettingsActivity, tick: Int) {
    val scope = rememberCoroutineScope()
    val server by g.server.state.collectAsState()
    val adb by g.adb.state.collectAsState()
    val entries by g.log.entries.collectAsState()
    var out by remember { mutableStateOf("") }
    var showTools by remember { mutableStateOf(false) }
    val enabled = remember(tick) { g.settings.serverEnabled }
    val search = remember { SettingsSearch() }
    var query by remember { mutableStateOf("") }
    search.setQuery(query)
    val opened = remember { OpenCards(g.settings.settingsOpen) { g.settings.settingsOpen = it } }
    // 開発・試験の画面 (試しの機能・記録・接続の情報。ふだんの設定から分ける)
    var dev by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = dev) { dev = false }
    CompositionLocalProvider(LocalSearch provides search, LocalQuery provides query, LocalOpen provides opened) {
    // 上の時刻・電池の帯と、下のナビゲーションの帯に重ならないように (下まで動かしたとき、最後の「開発・試験」が隠れない)
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0D12)).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), color = Color(0xFFA8DFFF), fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, modifier = Modifier.padding(top = 8.dp))
        if (dev) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { dev = false }) { Text("‹ 設定", color = Color(0xFFA8DFFF), fontSize = 15.sp) }
                Text("開発・試験", color = Color.White, fontSize = 16.sp)
            }
        } else Text("設定 (検証用)", color = Color.White, fontSize = 16.sp)
        // 設定を探す: 見出しが合えばカードを丸ごと、合わなければ合う項目だけを出す (ふだんの設定と開発・試験の両方から)
        OutlinedTextField(query, { query = it }, label = { Text("設定を探す (例: 録画・マイク・リゲル)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("消す") } })
        if (search.active && !search.anyVisible()) Note("「${query.trim()}」に合う項目はありません")
        if (out.isNotBlank()) Mono(out)
        val showMain = search.active || !dev
        val showDev = search.active || dev

        val mainCards: @Composable () -> Unit = {
        Card("MCP サーバー", server.label, server is McpServer.State.Running) {
            Note("オンにしている間だけ、このスマホの中 (127.0.0.1) で MCP サーバーが動き、通知に出し続けます。ChatGPT からは、tunnel-client (OpenAI の Secure MCP Tunnel) を通して届きます。今の道具は「スマホの状態」(get_state、読むだけ) だけです。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MCP サーバーを動かす", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { a.setServer(it) })
            }
            Line("状態", server.label, server is McpServer.State.Running)
            if (g.stoppedByUser()) {
                Line("止めた", "リゲルのタップで止めました。しばらく道具を受け付けません", false)
                Btn("受け付けを再開する") { g.clearStop(); out = "受け付けを再開しました" }
            }
        }

        OperateCard(g, a, tick)

        VisionCard(g, a, tick)

        ControlCard(g, a, tick)

        ArmCard(g, a, tick) { out = it }

        UnoqWifiCard(g, tick)

        MonitorCard(g)

        Card("端末の操作の接続 (ワイヤレスデバッグ)", adb.label, adb is AdbState.Connected) {
            Note("「スマホの状態」で前面のアプリを読むのに使います (無くても、ほかの状態は読めます)。ペアリングは最初に一度だけ要ります。")
            Line("状態", adb.label, adb is AdbState.Connected)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("ペアリングを始める") { PairingNotifier.show(a); a.open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
                Btn("接続", true) { scope.launch { out = if (g.adb.ensureConnected()) "✓ 接続しました" else "✗ ${g.adb.state.value.label}" } }
            }
            Note("ペアリング: 開発者向けオプション › ワイヤレスデバッグ › 「ペア設定コードによるデバイスのペア設定」を開いたまま、通知の返信欄に 6 桁のコードを入れます。")
        }

        PhoneCard(g, a, tick)

        PhotoCard(g, a, tick)

        val chatGptInstalled = remember(tick) { runCatching { a.packageManager.getPackageInfo(McpGraph.CHATGPT, 0) }.isSuccess }
        Card("ChatGPT", if (chatGptInstalled) "入っています" else "入っていません", chatGptInstalled) {
            val installed = chatGptInstalled
            Line("アプリ", if (installed) "入っています" else "入っていません", installed)
            Btn("ChatGPT を開く (リゲルのタップと同じ)") { if (!g.openChatGptVoice()) out = "ChatGPT を開けませんでした" }
            Note("音声モードを直接開けるかは未確認です。開けないときは ChatGPT が開くだけなので、音声は自分で始めてください。")
            val assistant = remember(tick) { a.isAssistant() }
            Line("デジタル アシスタント", if (assistant) "ENVOY MCP" else "ほかのアプリ (ENVOY MCP ではない)", assistant)
            Btn("デジタル アシスタントの設定を開く") { a.openAssistantSettings() }
            Note("「デジタル アシスタント アプリ」で ENVOY MCP を選ぶと、アシスタントを呼ぶ操作 (電源ボタンの長押し・画面の下の角からのスワイプなど) で、リゲルのタップと同じように ChatGPT の音声モードが開きます。そのあいだ Gemini は呼べません。戻すときは、同じ画面で選び直してください。")
        }

        // 開発・試験の画面へ
        if (!search.active) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF14171E)).clickable { dev = true }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("開発・試験", color = Color(0xFFFFD86B), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("試しの機能・道具の記録・接続の情報・トンネル", color = Color(0xFF9AA3B5), fontSize = 13.sp)
            }
            Text("›", color = Color(0xFF7F8796), fontSize = 22.sp)
        }
        }

        val devCards: @Composable () -> Unit = {
        TunnelCard(g, a, tick) { out = it }

        Card("道具の記録", if (entries.isEmpty()) "まだありません" else "${entries.size} 件") {
            if (entries.isEmpty()) Note("まだありません")
            else {
                // 最後の 1 件だけ見せ、残りは折りたたむ (長くて見づらいため)
                val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.JAPAN) }
                fun line(e: ToolLog.Entry) = "${fmt.format(Date(e.at))} ${if (e.ok) "●" else "○"} ${e.name}  ${e.detail}"
                Mono("最後: " + line(entries.first()))
                Btn(if (showTools) "記録を閉じる" else "記録を見る (${entries.size} 件)") { showTools = !showTools }
                if (showTools) {
                    Note("新しい順です。")
                    entries.take(30).forEach { Mono(line(it)) }
                }
            }
        }

        DevCard(g, a) { out = it }
        }

        // 出していない側の画面のカードも見えない形で作り、項目の文字を覚えておく (どちらの画面からでも、全部の項目を探せるように)
        if (showMain) mainCards() else Hidden(mainCards)
        if (showDev) devCards() else Hidden(devCards)
    }
    }
}
